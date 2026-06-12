package com.meta.portal.security

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the camera + microphone capture and one PeerConnection per viewer.
 *
 * Negotiation contract (must match the web viewer): the viewer is the offerer;
 * this engine is the answerer. It sends its camera (video) + mic (audio) and
 * receives the viewer's talk-back audio, which libwebrtc plays automatically.
 */
class WebRtcEngine(
    private val context: Context,
    private val listener: Listener,
    // On-demand (Drop In): open the camera only while a viewer is connected.
    private val onDemand: Boolean = true,
    private val facing: String = "front",      // "front" | "back"
    private val captureW: Int = 1280,
    private val captureH: Int = 720,
    private val captureFps: Int = 30,
) {
    interface Listener {
        fun onAnswer(viewerId: String, sdp: String)
        fun onLocalIce(viewerId: String, candidate: IceCandidate)
        fun onViewerCountChanged(count: Int)
        fun onCapturingChanged(capturing: Boolean)
        fun onMotion(level: Int)
    }

    val eglBase: EglBase = EglBase.create()

    private lateinit var factory: PeerConnectionFactory
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null

    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    private val motionDetector = MotionDetector(onMotion = { listener.onMotion(it) })

    private var previewSink: VideoSink? = null
    private var started = false
    private var capturing = false

    fun setMotionEnabled(enabled: Boolean) { motionDetector.enabled = enabled }

    val isCapturing: Boolean get() = capturing

    /**
     * Build the factory. In always-on mode this also opens the camera so motion
     * detection runs continuously; in on-demand mode the camera stays off until
     * the first viewer connects (see [acquireMedia]).
     */
    fun start(servers: List<SignalingClient.IceServer>) {
        if (started) return
        started = true
        iceServers = servers.map { s ->
            PeerConnection.IceServer.builder(s.urls)
                .setUsername(s.username ?: "")
                .setPassword(s.credential ?: "")
                .createIceServer()
        }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()

        if (!onDemand) acquireMedia()
    }

    /** Open the camera + mic and build the local tracks. Idempotent. */
    @Synchronized
    private fun acquireMedia() {
        if (capturing) return
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val preferred = if (facing == "back") {
            names.firstOrNull { enumerator.isBackFacing(it) }
        } else {
            names.firstOrNull { enumerator.isFrontFacing(it) }
        }
        val deviceName = preferred
            ?: names.firstOrNull { enumerator.isFrontFacing(it) }
            ?: names.firstOrNull()
            ?: run { Log.e(TAG, "no camera found"); return }

        val capturer = enumerator.createCapturer(deviceName, null)
        videoCapturer = capturer
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(false)
        capturer.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
        capturer.startCapture(captureW, captureH, captureFps)

        localVideoTrack = factory.createVideoTrack(VIDEO_ID, videoSource).apply {
            setEnabled(true)
            addSink(motionDetector)
            previewSink?.let { addSink(it) }
        }

        audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack(AUDIO_ID, audioSource).apply { setEnabled(true) }

        capturing = true
        Log.i(TAG, "media acquired (camera on)")
        listener.onCapturingChanged(true)
    }

    /** Stop the camera + mic and tear down the local tracks. Idempotent. */
    @Synchronized
    private fun releaseMedia() {
        if (!capturing) return
        previewSink?.let { localVideoTrack?.removeSink(it) }
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        videoCapturer?.dispose(); videoCapturer = null
        surfaceHelper?.dispose(); surfaceHelper = null
        localVideoTrack?.dispose(); localVideoTrack = null
        localAudioTrack?.dispose(); localAudioTrack = null
        videoSource?.dispose(); videoSource = null
        audioSource?.dispose(); audioSource = null
        capturing = false
        Log.i(TAG, "media released (camera off)")
        listener.onCapturingChanged(false)
    }

    /** Attach a local preview renderer (already init()'d with eglBase context). */
    fun attachPreview(sink: VideoSink) {
        previewSink = sink
        localVideoTrack?.addSink(sink)
    }

    fun detachPreview() {
        previewSink?.let { localVideoTrack?.removeSink(it) }
        previewSink = null
    }

    /** A viewer sent an offer. Wake the camera if needed, attach tracks, answer. */
    fun handleOffer(viewerId: String, sdp: String) {
        // Close any prior peer for this viewer WITHOUT releasing media mid-offer.
        peers.remove(viewerId)?.close()
        // Ensure the camera is on (no-op if already capturing). This is what makes
        // a remote viewer's connection start the feed on demand.
        acquireMedia()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory.createPeerConnection(rtcConfig, peerObserver(viewerId)) ?: return
        peers[viewerId] = pc
        listener.onViewerCountChanged(peers.size)

        val streamIds = listOf(STREAM_ID)
        localVideoTrack?.let { pc.addTrack(it, streamIds) }
        localAudioTrack?.let { pc.addTrack(it, streamIds) }

        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        pc.setLocalDescription(SimpleSdpObserver(), desc)
                        listener.onAnswer(viewerId, desc.description)
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun addRemoteIce(viewerId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        peers[viewerId]?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun closePeer(viewerId: String) {
        peers.remove(viewerId)?.let {
            it.close()
            listener.onViewerCountChanged(peers.size)
        }
        // Last viewer gone -> in on-demand mode, turn the camera back off.
        if (onDemand && peers.isEmpty()) releaseMedia()
    }

    fun stop() {
        for (id in peers.keys.toList()) peers.remove(id)?.close()
        listener.onViewerCountChanged(0)
        releaseMedia()
        detachPreview()
        if (started) factory.dispose()
        eglBase.release()
        started = false
    }

    private fun peerObserver(viewerId: String) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) = listener.onLocalIce(viewerId, candidate)
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.d(TAG, "viewer $viewerId connection: $newState")
            if (newState == PeerConnection.PeerConnectionState.FAILED ||
                newState == PeerConnection.PeerConnectionState.CLOSED
            ) closePeer(viewerId)
        }
        // Remote (talk-back) audio is rendered automatically by libwebrtc.
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {
            (receiver?.track() as? MediaStreamTrack)?.setEnabled(true)
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: org.webrtc.MediaStream?) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    /** SdpObserver with all methods defaulted; override what you need. */
    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { Log.w(TAG, "createSDP failed: $error") }
        override fun onSetFailure(error: String?) { Log.w(TAG, "setSDP failed: $error") }
    }

    companion object {
        private const val TAG = "WebRtcEngine"
        private const val STREAM_ID = "portal-cam"
        private const val VIDEO_ID = "video0"
        private const val AUDIO_ID = "audio0"
    }
}
