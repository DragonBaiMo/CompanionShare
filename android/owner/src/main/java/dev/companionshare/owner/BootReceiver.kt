// Copyright (c) 2026 CompanionShare. MIT License.
package dev.companionshare.owner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Android 15+ forbids automatic camera/microphone FGS restoration after BOOT_COMPLETED. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.getSharedPreferences("companionshare", Context.MODE_PRIVATE).edit()
            .putBoolean("boot_needs_rearm", true).apply()
    }
}
