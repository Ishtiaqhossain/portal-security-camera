package com.meta.portal.security

import android.content.Context

/** Persisted connection settings for the camera agent. */
data class Config(
    val serverUrl: String = "",
    val cameraToken: String = "",
    val motionEnabled: Boolean = true,
    // On-demand: the camera stays in standby and only captures while a viewer is
    // connected (camera off otherwise). When false, the camera captures
    // continuously so motion detection can run even with no viewer watching.
    val onDemand: Boolean = true,
) {
    val isValid: Boolean
        get() = serverUrl.isNotBlank() && cameraToken.isNotBlank()

    /** Normalize http(s) -> ws(s) for the WebSocket connection. */
    val webSocketUrl: String
        get() = serverUrl.trim()
            .replace(Regex("^http"), "ws")
            .removeSuffix("/")

    companion object {
        private const val PREFS = "portal_security"

        fun load(context: Context): Config {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Config(
                serverUrl = p.getString("serverUrl", "") ?: "",
                cameraToken = p.getString("cameraToken", "") ?: "",
                motionEnabled = p.getBoolean("motionEnabled", true),
                onDemand = p.getBoolean("onDemand", true),
            )
        }

        fun save(context: Context, config: Config) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("serverUrl", config.serverUrl)
                .putString("cameraToken", config.cameraToken)
                .putBoolean("motionEnabled", config.motionEnabled)
                .putBoolean("onDemand", config.onDemand)
                .apply()
        }
    }
}
