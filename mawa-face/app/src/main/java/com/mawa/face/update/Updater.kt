package com.mawa.face.update

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.mawa.face.BuildConfig
import java.io.File
import java.net.URL

/**
 * Self-update from the rolling GitHub release. CI publishes version.txt
 * (the run number) next to the APK; if it's newer than this build, we
 * download the APK and hand it to the system installer — one tap on the
 * phone, no browser, no cable.
 *
 * Requires the APK to be signed with the same persistent key as the
 * installed build (CI signing secrets), or Android rejects the update.
 */
object Updater {
    private const val TAG = "Updater"
    private const val BASE = "https://github.com/SteveMama/Mawa/releases/download/latest"
    private const val VERSION_URL = "$BASE/version.txt"
    private const val APK_URL = "$BASE/mawa-face.apk"

    @Volatile
    private var running = false

    fun checkAsync(activity: Activity, onStatus: (String) -> Unit = {}) {
        if (running) return
        running = true
        Thread {
            try {
                val remote = URL(VERSION_URL).openStream().bufferedReader()
                    .use { it.readText() }.trim().toIntOrNull()
                if (remote == null || remote <= BuildConfig.VERSION_CODE) return@Thread

                Log.i(TAG, "update available: $remote > ${BuildConfig.VERSION_CODE}")
                onStatus("updating to build $remote")

                val apk = File(activity.cacheDir, "update.apk")
                URL(APK_URL).openStream().use { input ->
                    apk.outputStream().use { input.copyTo(it) }
                }

                val uri = FileProvider.getUriForFile(
                    activity, activity.packageName + ".fileprovider", apk,
                )
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                or Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                )
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
            } finally {
                running = false
            }
        }.start()
    }
}
