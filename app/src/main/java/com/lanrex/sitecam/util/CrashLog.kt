package com.lanrex.sitecam.util

import android.content.Context
import android.os.Build
import com.lanrex.sitecam.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the last crash to a file so it can be shared from Settings.
 * There is no internet reporting; the report stays on the phone.
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(appContext.filesDir, FILE_NAME).writeText(report(thread, error))
            } catch (_: Throwable) {
                // Nothing else we can do while crashing.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? {
        val f = File(context.filesDir, FILE_NAME)
        return if (f.exists()) f.readText() else null
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    /** Records a handled problem without crashing (kept small: last few entries). */
    fun note(context: Context, message: String, error: Throwable? = null) {
        try {
            val f = File(context.filesDir, "problems.txt")
            val old = if (f.exists()) f.readText().takeLast(20_000) else ""
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val trace = error?.let { "\n" + stackTrace(it).take(4000) } ?: ""
            f.writeText("$old\n[$stamp] $message$trace\n")
        } catch (_: Throwable) {
        }
    }

    fun readProblems(context: Context): String? {
        val f = File(context.filesDir, "problems.txt")
        return if (f.exists()) f.readText() else null
    }

    fun clearProblems(context: Context) {
        File(context.filesDir, "problems.txt").delete()
    }

    private fun report(thread: Thread, error: Throwable): String = buildString {
        append("SiteCam ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("Time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())).append('\n')
        append("Thread: ").append(thread.name).append("\n\n")
        append(stackTrace(error))
    }

    private fun stackTrace(error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
