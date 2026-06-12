package com.meta.portal.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-arms the camera agent after a device reboot when "Start on boot" is enabled,
 * so the security camera comes back online in the background without anyone
 * launching the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val config = Config.load(context)
        if (config.isValid && config.startOnBoot) {
            Log.i("BootReceiver", "auto-starting camera agent after boot")
            CameraAgentService.start(context)
        }
    }
}
