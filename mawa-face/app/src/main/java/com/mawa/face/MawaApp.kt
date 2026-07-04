package com.mawa.face

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Wall-appliance crash policy: any uncaught exception schedules a relaunch
 * in ~2 seconds and kills the process. The wall must never show a stack
 * trace or the launcher.
 */
class MawaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            Log.e("Mawa", "uncaught crash, scheduling restart", e)
            try {
                val restart = PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
                )
                val am = getSystemService(ALARM_SERVICE) as AlarmManager
                am.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 2000,
                    restart,
                )
            } finally {
                Process.killProcess(Process.myPid())
            }
        }
    }
}
