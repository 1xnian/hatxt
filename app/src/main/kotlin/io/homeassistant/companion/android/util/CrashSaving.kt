package io.homeassistant.companion.android.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Final location of a file on the device is /data/data/io.homeassistant.companion.android<.debug>/cache/fatalcrash/last_crash
 */

private const val FATAL_CRASH_FILE = "/fatalcrash/last_crash"

fun initCrashSaving(context: Context) {
    val handler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
        // Try saving the crash in a file
        try {
            val crashText =
                """|Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())}
                    |Thread: ${thread.name}
                    |Exception: ${exception.stackTraceToString()}
                """.trimMargin()

            val crashFile = File(context.applicationContext.cacheDir.absolutePath + FATAL_CRASH_FILE)
            if (!crashFile.exists()) {
                crashFile.parentFile?.mkdirs()
                crashFile.createNewFile()
            }
            crashFile.writeText(crashText)

            // Second copy in app-specific external storage (Android/data/<pkg>/files/…):
            // reachable via USB file transfer / most file managers even when the app
            // itself cannot start, so the crash can be retrieved without adb.
            runCatching {
                context.getExternalFilesDir(null)?.let { extDir ->
                    File(extDir, "fatalcrash/last_crash").apply {
                        parentFile?.mkdirs()
                        writeText(crashText)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.i(e, "Tried saving fatal crash but encountered exception")
        }

        // Send to crash handling and/or system (and crash)
        handler?.uncaughtException(thread, exception)
    }
}

suspend fun getLatestFatalCrash(context: Context): String? = withContext(Dispatchers.IO) {
    var toReturn: String? = null
    try {
        val crashFile = File(context.applicationContext.cacheDir.absolutePath + FATAL_CRASH_FILE)
        if (crashFile.exists() &&
            crashFile.lastModified() >= (System.currentTimeMillis() - TimeUnit.HOURS.toMillis(12))
        ) { // Existing, recent file
            toReturn = crashFile.readText().trim().ifBlank { null }
        }
    } catch (e: Exception) {
        Timber.e(e, "Encountered exception while reading crash log file")
    }
    return@withContext toReturn
}
