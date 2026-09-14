package com.morselink.app.core.logging

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.morselink.app.BuildConfig
import com.morselink.app.core.util.MorselinkServices
import com.morselink.app.di.AppServices
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent in-app log (spec Section 15). Captures only this app's own lines
 * plus crash-relevant lines — never an unfiltered capture of every process.
 * Writes to an app-owned file, exportable and clearable.
 */
object LogStore {

    private const val TAG = "MorseLink"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val KEEP_ON_ROTATE = 512L * 1024
    private const val CRASH_FILE = "morselink-crash.log"
    private const val MAX_CRASH_BYTES = 512L * 1024
    private const val KEEP_ON_CRASH_ROTATE = 128L * 1024

    @Volatile
    var enabled: Boolean = true

    private val lock = Any()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null
    private var crashFile: File? = null

    /** Timestamp (millis) of the last "clear log" invocation. */
    @Volatile
    private var clearedAt: Long = 0

    fun init(context: Context) {
        synchronized(lock) {
            logFile = File(context.filesDir, "morselink.log")
            crashFile = File(context.filesDir, CRASH_FILE)
        }
    }

    /** True when at least one crash trace from an earlier run is on disk. */
    fun hasCrashReports(): Boolean =
        crashFile != null && crashFile!!.length() > 0L

    /** Last [maxLines] of recorded crash traces, oldest first. */
    fun crashTail(maxLines: Int): List<String> {
        val file = crashFile ?: return emptyList()
        synchronized(lock) {
            return try {
                val lines = file.readLines()
                if (lines.size > maxLines) lines.subList(lines.size - maxLines, lines.size) else lines
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun i(message: String) = write("I", message)
    fun w(message: String) = write("W", message)
    fun e(message: String, tr: Throwable? = null) {
        val detail = if (tr != null) "$message :: ${tr.javaClass.simpleName}: ${tr.message}" else message
        write("E", detail)
        if (tr != null) Log.e(TAG, message, tr)
    }

    private fun write(level: String, message: String) {
        Log.println(if (level == "E") Log.ERROR else if (level == "W") Log.WARN else Log.INFO, TAG, message)
        if (!enabled) return
        val file = logFile ?: return
        synchronized(lock) {
            try {
                if (file.length() > MAX_FILE_BYTES) rotate(file)
                FileWriter(file, true).use { w ->
                    w.write("${fmt.format(Date())} $level $message\n")
                }
            } catch (_: Exception) {
                // Logging must never crash the app.
            }
        }
    }

    private fun rotate(file: File) {
        try {
            val content = file.readText()
            val keep = content.substring(Math.max(0, content.length - KEEP_ON_ROTATE.toInt()))
            val idx = keep.indexOf('\n')
            file.writeText(if (idx >= 0 && idx + 1 < keep.length) keep.substring(idx + 1) else keep)
            file.appendText("--- log rotated ---\n")
        } catch (_: Exception) {
        }
    }

    /** Returns log lines after the last clear, newest last. */
    fun tail(maxLines: Int): List<String> {
        val file = logFile ?: return emptyList()
        synchronized(lock) {
            return try {
                val lines = file.readLines()
                val cutoffIdx = lines.indexOfLast { it.startsWith("--- cleared at ") }
                val effective = if (cutoffIdx >= 0) lines.subList(cutoffIdx + 1, lines.size) else lines
                if (effective.size > maxLines) effective.subList(effective.size - maxLines, effective.size) else effective
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** Fully deletes the app's own log file and records the moment it was cleared. */
    fun clear(context: Context) {
        synchronized(lock) {
            try {
                logFile?.delete()
                logFile?.createNewFile()
                clearedAt = System.currentTimeMillis()
                logFile?.appendText("--- cleared at ${fmt.format(Date())} ---\n")
                crashFile?.delete()
                crashFile?.createNewFile()
            } catch (_: Exception) {
            }
        }
        i("Log and crash reports cleared by user")
    }

    /**
     * Writes a full diagnostic snapshot for sharing: device/app header, the
     * app log, and any recorded crash traces — one .txt with everything
     * needed to diagnose a failure (spec Section 15).
     */
    fun exportFile(context: Context): File? {
        return try {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val out = File(dir, "morselink-log-${System.currentTimeMillis()}.txt")
            val sb = StringBuilder()
            sb.append("MorseLink ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} " +
                "(Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})\n")
            sb.append("Exported: ${fmt.format(Date())}\n")
            sb.append("\n===== APP LOG =====\n")
            sb.append(tail(20000).joinToString("\n"))
            val crashes = crashTail(800)
            if (crashes.isNotEmpty()) {
                sb.append("\n\n===== CRASH REPORTS =====\n")
                sb.append(crashes.joinToString("\n"))
            }
            sb.append('\n')
            out.writeText(sb.toString())
            out
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Always installed; the crash-report opt-out (Settings / onboarding) is
     * honoured at crash time so toggling takes effect without a restart.
     */
    fun installCrashHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                if (AppServices.prefs.crashLogsEnabled) {
                    synchronized(lock) {
                        val file = crashFile ?: File(context.filesDir, CRASH_FILE)
                        if (file.length() > MAX_CRASH_BYTES) rotateCrash(file)
                        FileWriter(file, true).use { w ->
                            w.write("\n==== CRASH ${fmt.format(Date())} thread=${thread.name} ====\n")
                            w.write(Log.getStackTraceString(throwable))
                        }
                    }
                }
            } catch (_: Exception) {
            }
            try {
                // Timeline marker in the main log (honours the logging toggle).
                write("E", "CRASH: ${throwable.javaClass.name}: ${throwable.message} " +
                    "(full trace in the crash-reports section)")
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun rotateCrash(file: File) {
        try {
            val content = file.readText()
            val keep = content.substring(Math.max(0, content.length - KEEP_ON_CRASH_ROTATE.toInt()))
            val idx = keep.indexOf("==== CRASH")
            file.writeText(if (idx > 0) keep.substring(idx) else keep)
            file.appendText("--- crash log rotated ---\n")
        } catch (_: Exception) {
        }
    }
}
