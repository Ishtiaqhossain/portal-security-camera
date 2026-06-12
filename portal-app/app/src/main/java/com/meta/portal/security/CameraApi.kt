package com.meta.portal.security

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * REST client for the camera-management endpoints under /camera.
 *
 * Management calls (enroll/viewers/revoke/enable) authenticate by **signing the
 * request with the device's Keystore key** — no shared secret on the device.
 * Provisioning is the bootstrap before a key is registered: it relies on the
 * server's trust-on-first-use window, carrying the legacy CAMERA_TOKEN only if
 * one is still configured (migration). All calls are blocking — invoke from a
 * background dispatcher.
 */
class CameraApi(
    private val baseUrl: String,
    private val cameraToken: String = "",
    private val cameraId: String = "",
    private val identity: CameraIdentity? = null,
) {

    data class Viewer(val id: String, val name: String, val revoked: Boolean, val lastSeenAt: Long?)

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    // Bootstrap auth for provisioning (no device key exists yet). Sends the
    // shared token only if one is configured; otherwise unauthenticated and the
    // server's TOFU claim window must be open.
    private fun bootstrapAuth(b: Request.Builder) =
        if (cameraToken.isNotBlank()) b.header("Authorization", "Bearer $cameraToken") else b

    // Sign a management request with the device key. Canonical string matches the
    // server's verifier: METHOD\npath\ntimestamp\nbase64(sha256(body)). Falls back
    // to the shared token if the device hasn't been provisioned yet.
    private fun signed(b: Request.Builder, method: String, path: String, body: ByteArray): Request.Builder {
        if (cameraId.isNotBlank() && identity != null) {
            val ts = System.currentTimeMillis()
            val hash = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(body), Base64.NO_WRAP)
            val sig = identity.signData("$method\n$path\n$ts\n$hash")
            return b.header("X-Camera-Id", cameraId)
                .header("X-Camera-Timestamp", ts.toString())
                .header("X-Camera-Signature", sig)
        }
        return if (cameraToken.isNotBlank()) b.header("Authorization", "Bearer $cameraToken") else b
    }

    /** Register this camera's public key (trust-on-first-use). Returns cameraId. */
    fun provision(name: String, publicKeyB64: String): String {
        val body = JSONObject().put("name", name).put("publicKey", publicKeyB64)
            .toString().toRequestBody(jsonType)
        val req = bootstrapAuth(Request.Builder().url("$baseUrl/camera/provision").post(body)).build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("provision failed: ${res.code} $text")
            return JSONObject(text).getString("id")
        }
    }

    /** Mint an enrollment ticket and return the full URL to encode in the QR. */
    fun startEnroll(name: String): String {
        val json = JSONObject().put("name", name).toString()
        val req = signed(
            Request.Builder().url("$baseUrl/camera/enroll/start").post(json.toRequestBody(jsonType)),
            "POST", "/camera/enroll/start", json.toByteArray(Charsets.UTF_8),
        ).build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("enroll/start failed: ${res.code} $text")
            val token = JSONObject(text).getString("token")
            // The fragment (#t=) keeps the token out of server logs / referrers.
            return "$baseUrl/enroll.html#t=$token"
        }
    }

    fun listViewers(): List<Viewer> {
        val req = signed(
            Request.Builder().url("$baseUrl/camera/viewers"),
            "GET", "/camera/viewers", ByteArray(0),
        ).build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("viewers failed: ${res.code}")
            val arr: JSONArray = JSONObject(text).getJSONArray("viewers")
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Viewer(
                    id = o.getString("id"),
                    name = o.optString("name", "Device"),
                    revoked = o.optBoolean("revoked", false),
                    lastSeenAt = if (o.isNull("lastSeenAt")) null else o.optLong("lastSeenAt"),
                )
            }
        }
    }

    fun setRevoked(id: String, revoked: Boolean) {
        val path = if (revoked) "revoke" else "enable"
        val json = JSONObject().put("id", id).toString()
        val req = signed(
            Request.Builder().url("$baseUrl/camera/$path").post(json.toRequestBody(jsonType)),
            "POST", "/camera/$path", json.toByteArray(Charsets.UTF_8),
        ).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("$path failed: ${res.code}")
        }
    }
}
