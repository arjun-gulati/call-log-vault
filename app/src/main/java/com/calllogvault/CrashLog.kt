package com.calllogvault

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Last crash capture. Installs a default uncaught exception handler,
 * once per process, that writes the crash to filesDir/last_crash.txt
 * and then chains to the previous handler, so the process still ends
 * the way Android expects. The Status and about dialog surfaces what
 * was captured. Instrumentation only: nothing here guesses at causes.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(appContext, thread, throwable)
            } catch (ignored: Exception) {
                // Never let crash reporting break the chain.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        var versionName = "unknown"
        var versionCode = -1L
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            versionName = pi.versionName ?: versionName
            versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION") pi.versionCode.toLong()
            }
        } catch (ignored: Exception) {
        }
        val trace = StringWriter()
        throwable.printStackTrace(PrintWriter(trace))
        val text = StringBuilder()
            .append("Time: ").append(VaultLogic.formatDateTime(System.currentTimeMillis())).append('\n')
            .append("App version: ").append(versionName).append(" (").append(versionCode).append(")\n")
            .append("Thread: ").append(thread.name).append('\n')
            .append("Exception: ").append(throwable.toString()).append('\n')
            .append('\n')
            .append(trace.toString())
            .toString()
        File(context.filesDir, FILE_NAME).writeText(text)
    }

    /** The full captured crash text, or null when there is none. */
    fun read(context: Context): String? {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists()) f.readText().ifBlank { null } else null
        } catch (e: Exception) {
            null
        }
    }

    /** Two summary lines (time and exception) for the status dialog. */
    fun summary(context: Context): String? {
        val text = read(context) ?: return null
        var time: String? = null
        var exception: String? = null
        for (line in text.lineSequence()) {
            if (time == null && line.startsWith("Time: ")) time = line.removePrefix("Time: ").trim()
            if (exception == null && line.startsWith("Exception: ")) {
                exception = line.removePrefix("Exception: ").trim()
            }
            if (time != null && exception != null) break
        }
        if (time == null && exception == null) return null
        return listOfNotNull(time, exception).joinToString("\n")
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (ignored: Exception) {
        }
    }
}
