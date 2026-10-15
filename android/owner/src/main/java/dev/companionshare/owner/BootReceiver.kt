// Copyright (c) 2026 CompanionShare. MIT License.
package dev.companionshare.owner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** After reboot restore only the Owner's previously enabled messaging connection. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("companionshare", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("boot_needs_rearm", true).apply()
        if (!prefs.getBoolean("boot_resume_connection", false)) return
        // NOTE: Android 15+ prohibits booting a camera/microphone FGS. Only remoteMessaging is allowed.
        try {
            context.startForegroundService(Intent(context, ShareService::class.java)
                .setAction(ShareService.ACTION_BOOT_ONLINE))
        } catch (error: RuntimeException) {
            // OEM boot policies may still block the allowed messaging service.
            Log.w("CompanionShare", "Messaging-only boot restore rejected", error)
        }
    }
}
