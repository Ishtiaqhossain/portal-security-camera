package com.meta.portal.security

import android.content.Context

/** Capture mode for the security camera. */
enum class CameraMode {
    /** Drop In — camera stays off until a viewer connects, then streams on demand. */
    DROP_IN,

    /** Active — camera streams continuously in the background (enables motion alerts). */
    ACTIVE;

    val storage: String get() = if (this == ACTIVE) "active" else "dropin"

    companion object {
        fun from(s: String?): CameraMode = if (s == "active") ACTIVE else DROP_IN
    }
}

/** Video capture quality presets. */
enum class VideoQuality(val label: String, val width: Int, val height: Int, val fps: Int) {
    LOW("480p", 640, 480, 30),
    MEDIUM("720p", 1280, 720, 30),
    HIGH("1080p", 1920, 1080, 30);

    companion object {
        fun from(s: String?): VideoQuality = entries.firstOrNull { it.label == s } ?: MEDIUM
    }
}

/** Persisted configuration for the camera agent. */
data class Config(
    val serverUrl: String = "",
    val cameraToken: String = "",
    val mode: CameraMode = CameraMode.DROP_IN,
    val motionEnabled: Boolean = false,
    val quality: VideoQuality = VideoQuality.MEDIUM,
    val startOnBoot: Boolean = false,
    // Per-device camera id, assigned by the server when this Portal provisions
    // its public key. Blank until provisioned (then it authenticates by signing).
    val cameraId: String = "",
) {
    // Only the server URL is required: the device provisions its own key on
    // first arm (trust-on-first-use) and authenticates by signing — no token to
    // type. `cameraToken` is an optional legacy/migration fallback.
    val isValid: Boolean
        get() = serverUrl.isNotBlank()

    /** Drop In mode means capture only while a viewer is connected. */
    val onDemand: Boolean
        get() = mode == CameraMode.DROP_IN

    /** Normalize http(s) -> ws(s) for the WebSocket connection. */
    val webSocketUrl: String
        get() = serverUrl.trim()
            .replace(Regex("^http"), "ws")
            .removeSuffix("/")

    /** Normalize ws(s) -> http(s) for REST calls (camera management endpoints). */
    val httpBaseUrl: String
        get() = serverUrl.trim()
            .replace(Regex("^ws"), "http")
            .removeSuffix("/")

    companion object {
        private const val PREFS = "portal_security"

        fun load(context: Context): Config {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Config(
                serverUrl = p.getString("serverUrl", "") ?: "",
                cameraToken = p.getString("cameraToken", "") ?: "",
                // Back-compat: the old `onDemand` boolean maps onto the mode.
                mode = when {
                    p.contains("mode") -> CameraMode.from(p.getString("mode", "dropin"))
                    p.contains("onDemand") -> if (p.getBoolean("onDemand", true)) CameraMode.DROP_IN else CameraMode.ACTIVE
                    else -> CameraMode.DROP_IN
                },
                motionEnabled = p.getBoolean("motionEnabled", false),
                quality = VideoQuality.from(p.getString("quality", "720p")),
                startOnBoot = p.getBoolean("startOnBoot", false),
                cameraId = p.getString("cameraId", "") ?: "",
            )
        }

        fun save(context: Context, config: Config) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("serverUrl", config.serverUrl)
                .putString("cameraToken", config.cameraToken)
                .putString("mode", config.mode.storage)
                .putBoolean("motionEnabled", config.motionEnabled)
                .putString("quality", config.quality.label)
                .putBoolean("startOnBoot", config.startOnBoot)
                .putString("cameraId", config.cameraId)
                .apply()
        }
    }
}
