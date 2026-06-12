package com.meta.portal.security

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * REST client for the camera-management endpoints under /camera. Authenticates
 * with the camera token. All calls are blocking — invoke from a background
 * dispatcher.
 */
class CameraApi(private val baseUrl: String, private val cameraToken: String) {

    data class Viewer(val id: String, val name: String, val revoked: Boolean, val lastSeenAt: Long?)

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    private fun authed(builder: Request.Builder) =
        builder.header("Authorization", "Bearer $cameraToken")

    /** Mint an enrollment ticket and return the full URL to encode in the QR. */
    fun startEnroll(name: String): String {
        val body = JSONObject().put("name", name).toString().toRequestBody(jsonType)
        val req = authed(Request.Builder().url("$baseUrl/camera/enroll/start").post(body)).build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("enroll/start failed: ${res.code} $text")
            val token = JSONObject(text).getString("token")
            // The fragment (#t=) keeps the token out of server logs / referrers.
            return "$baseUrl/enroll.html#t=$token"
        }
    }

    fun listViewers(): List<Viewer> {
        val req = authed(Request.Builder().url("$baseUrl/camera/viewers")).build()
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
        val body = JSONObject().put("id", id).toString().toRequestBody(jsonType)
        val req = authed(Request.Builder().url("$baseUrl/camera/$path").post(body)).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("$path failed: ${res.code}")
        }
    }
}
