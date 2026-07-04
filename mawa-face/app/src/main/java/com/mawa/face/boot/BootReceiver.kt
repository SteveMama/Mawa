package com.mawa.face.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mawa.face.MainActivity

/**
 * Relaunches Mawa after the phone reboots (power blip, forced update).
 * On Android 10+ background activity launches are restricted — grant Mawa
 * "Display over other apps" once in Settings and this works reliably.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
