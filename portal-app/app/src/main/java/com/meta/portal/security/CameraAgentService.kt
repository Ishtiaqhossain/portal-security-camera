package com.meta.portal.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.VideoSink

/**
 * Always-on camera agent. Runs as a foreground service so capture and WebRTC
 * survive when the app is backgrounded — and so the user sees a persistent
 * "LIVE" notification (household-disclosure requirement: the camera is active
 * and remotely viewable).
 */
class CameraAgentService : Service(), SignalingClient.Listener, WebRtcEngine.Listener {

    data class AgentState(
        val running: Boolean = false,
        val online: Boolean = false,
        val capturing: Boolean = false,
        val onDemand: Boolean = true,
        val mode: CameraMode = CameraMode.DROP_IN,
        val viewerCount: Int = 0,
        val lastMotionMs: Long = 0L,
        val armedSinceMs: Long = 0L,
        val statusText: String = "Disarmed",
    )

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state

    private var engine: WebRtcEngine? = null
    private var signaling: SignalingClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        val service get() = this@CameraAgentService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    val eglContext: EglBase.Context? get() = engine?.eglBase?.eglBaseContext
    fun attachPreview(sink: VideoSink) = engine?.attachPreview(sink) ?: Unit
    fun detachPreview() = engine?.detachPreview() ?: Unit

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAgent(); return START_NOT_STICKY }
            ACTION_RESTART -> { restartAgent(); return START_STICKY }
            else -> startAgent()
        }
        return START_STICKY
    }

    private fun startAgent() {
        if (engine != null) return
        val config = Config.load(this)
        if (!config.isValid) {
            Log.w(TAG, "config invalid; not starting")
            stopSelf()
            return
        }
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        acquireWakeLock()

        engine = WebRtcEngine(
            context = this,
            listener = this,
            onDemand = config.onDemand,
            captureW = config.quality.width,
            captureH = config.quality.height,
            captureFps = config.quality.fps,
        ).also { it.setMotionEnabled(config.motionEnabled && !config.onDemand) }
        signaling = SignalingClient(config.webSocketUrl, config.cameraToken, this).also { it.connect() }
        _state.value = _state.value.copy(
            running = true,
            onDemand = config.onDemand,
            mode = config.mode,
            armedSinceMs = System.currentTimeMillis(),
            statusText = "Connecting…",
        )
    }

    /** Tear down and re-arm with the latest saved Config (applies setting changes). */
    private fun restartAgent() {
        signaling?.close(); signaling = null
        engine?.stop(); engine = null
        startAgent()
    }

    private fun stopAgent() {
        signaling?.close(); signaling = null
        engine?.stop(); engine = null
        releaseWakeLock()
        _state.value = AgentState(statusText = "Disarmed")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        signaling?.close()
        engine?.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Keep the CPU running while armed so capture + WebRTC keep streaming even if
     * the screen turns off — the foreground-service grant alone doesn't prevent
     * the CPU from sleeping. Released on disarm/destroy.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:stream").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // --- SignalingClient.Listener -------------------------------------------

    override fun onWelcome(iceServers: List<SignalingClient.IceServer>) {
        engine?.start(iceServers)
        updateState { it.copy(online = true) }
    }

    // Computes a human status from the current state and pushes it to the UI +
    // notification. In on-demand mode the camera reads "Standby" until a viewer
    // connects, then "LIVE".
    private fun updateState(transform: (AgentState) -> AgentState) {
        val s = transform(_state.value)
        val text = when {
            !s.running -> "Disarmed"
            !s.online -> "Connecting…"
            s.capturing && s.viewerCount > 0 -> "Live · ${s.viewerCount} watching"
            s.onDemand -> "Protected · Drop In standby"
            else -> "Protected · Active streaming"
        }
        _state.value = s.copy(statusText = text)
        if (s.running && s.online) updateNotification(text)
    }

    override fun onOffer(from: String, sdp: String) {
        engine?.handleOffer(from, sdp)
    }

    override fun onRemoteIce(from: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        engine?.addRemoteIce(from, sdpMid, sdpMLineIndex, candidate)
    }

    override fun onPeerLeft(id: String) {
        engine?.closePeer(id)
    }

    override fun onClosed() {
        _state.value = _state.value.copy(online = false, statusText = "reconnecting…")
        updateNotification("Reconnecting…")
        // SignalingClient/OkHttp doesn't auto-reconnect; do a simple retry.
        signaling?.let {
            val config = Config.load(this)
            signaling = SignalingClient(config.webSocketUrl, config.cameraToken, this)
            android.os.Handler(mainLooper).postDelayed({ signaling?.connect() }, 3000)
        }
    }

    override fun onError(code: String, message: String) {
        Log.w(TAG, "signaling error $code: $message")
        if (code == "bad_token") {
            _state.value = _state.value.copy(statusText = "auth failed")
            stopAgent()
        }
    }

    // --- WebRtcEngine.Listener ----------------------------------------------

    override fun onAnswer(viewerId: String, sdp: String) = signaling?.sendAnswer(viewerId, sdp) ?: Unit

    override fun onLocalIce(viewerId: String, candidate: IceCandidate) {
        signaling?.sendIce(viewerId, candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
    }

    override fun onViewerCountChanged(count: Int) {
        updateState { it.copy(viewerCount = count) }
    }

    override fun onCapturingChanged(capturing: Boolean) {
        updateState { it.copy(capturing = capturing) }
    }

    override fun onMotion(level: Int) {
        signaling?.sendMotion(level, System.currentTimeMillis())
        _state.value = _state.value.copy(lastMotionMs = System.currentTimeMillis())
    }

    // --- Notification --------------------------------------------------------

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                        .apply { description = getString(R.string.channel_desc) }
                )
            }
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "CameraAgent"
        private const val CHANNEL_ID = "camera_agent"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.meta.portal.security.STOP"
        const val ACTION_RESTART = "com.meta.portal.security.RESTART"

        fun start(context: Context) {
            val intent = Intent(context, CameraAgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CameraAgentService::class.java).setAction(ACTION_STOP))
        }

        /** Re-arm with the latest saved Config. Only meaningful while armed. */
        fun restart(context: Context) {
            val intent = Intent(context, CameraAgentService::class.java).setAction(ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
