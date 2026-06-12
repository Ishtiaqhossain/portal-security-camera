package com.meta.portal.security

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket signaling client for the camera role. Speaks the same JSON protocol
 * as signaling-server/server.js. The camera is the ANSWERER: it receives offers
 * from viewers and replies with answers, addressing each viewer by id via `to`.
 */
class SignalingClient(
    private val webSocketUrl: String,
    private val token: String,
    // Per-device identity. When cameraId is set, the camera authenticates by
    // signing the server's nonce; otherwise it falls back to the shared token.
    private val cameraId: String,
    private val identity: CameraIdentity?,
    private val listener: Listener,
) {
    interface Listener {
        fun onWelcome(iceServers: List<IceServer>)
        fun onOffer(from: String, sdp: String)
        fun onRemoteIce(from: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String)
        fun onPeerLeft(id: String)
        fun onClosed()
        fun onError(code: String, message: String)
    }

    data class IceServer(val urls: String, val username: String?, val credential: String?)

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(webSocketUrl).build()
        ws = client.newWebSocket(request, socketListener)
    }

    fun close() {
        try {
            ws?.send(JSONObject().put("type", "bye").toString())
        } catch (_: Exception) {}
        ws?.close(1000, "bye")
        ws = null
    }

    private fun send(obj: JSONObject) {
        ws?.send(obj.toString())
    }

    fun sendAnswer(to: String, sdp: String) {
        send(JSONObject().put("type", "answer").put("to", to).put("sdp", sdp))
    }

    fun sendIce(to: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        // The browser expects an RTCIceCandidateInit shape under `candidate`.
        val cand = JSONObject()
            .put("candidate", candidate)
            .put("sdpMid", sdpMid)
            .put("sdpMLineIndex", sdpMLineIndex)
        send(JSONObject().put("type", "ice").put("to", to).put("candidate", cand))
    }

    fun sendMotion(level: Int, timestamp: Long) {
        send(JSONObject().put("type", "motion").put("level", level).put("ts", timestamp))
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val reg = JSONObject().put("type", "register").put("role", "camera")
            if (cameraId.isNotBlank() && identity != null) {
                Log.i(TAG, "ws open; registering as camera $cameraId (key)")
                reg.put("cameraId", cameraId)
            } else {
                Log.i(TAG, "ws open; registering as camera (token)")
                reg.put("token", token)
            }
            send(reg)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (msg.optString("type")) {
                "camera-challenge" -> {
                    // Prove possession of our private key by signing the nonce.
                    val sig = runCatching { identity?.signNonce(msg.optString("nonce")) }.getOrNull()
                    if (sig != null) send(JSONObject().put("type", "camera-auth").put("signature", sig))
                    else listener.onError("no_key", "cannot sign camera challenge")
                }
                "welcome" -> listener.onWelcome(parseIceServers(msg.optJSONArray("iceServers")))
                "offer" -> listener.onOffer(msg.optString("from"), msg.optString("sdp"))
                "ice" -> {
                    val from = msg.optString("from")
                    val c = msg.optJSONObject("candidate") ?: return
                    listener.onRemoteIce(
                        from,
                        if (c.isNull("sdpMid")) null else c.optString("sdpMid"),
                        c.optInt("sdpMLineIndex", 0),
                        c.optString("candidate"),
                    )
                }
                "peer-left" -> listener.onPeerLeft(msg.optString("id"))
                "error" -> listener.onError(msg.optString("code"), msg.optString("message"))
                // "peer-joined" needs no action: the viewer will send us an offer.
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "ws failure: ${t.message}")
            listener.onClosed()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "ws closed: $reason")
            listener.onClosed()
        }
    }

    private fun parseIceServers(arr: JSONArray?): List<IceServer> {
        if (arr == null) return emptyList()
        val out = ArrayList<IceServer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // `urls` may be a string or array; take the first if an array.
            val urls = when (val u = o.opt("urls")) {
                is JSONArray -> u.optString(0)
                else -> u?.toString() ?: continue
            }
            out.add(
                IceServer(
                    urls = urls,
                    username = if (o.isNull("username")) null else o.optString("username"),
                    credential = if (o.isNull("credential")) null else o.optString("credential"),
                )
            )
        }
        return out
    }

    companion object { private const val TAG = "Signaling" }
}
