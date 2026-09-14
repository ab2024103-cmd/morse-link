package com.morselink.app.core.logging

import android.content.Context
import android.content.Intent
import android.util.Log
import com.morselink.app.core.util.MorselinkServices
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

    @Volatile
    var enabled: Boolean = true

    private val lock = Any()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    /** Timestamp (millis) of the last "clear log" invocation. */
    @Volatile
    private var clearedAt: Long = 0

    fun init(context: Context) {
        synchronized(lock) {
            logFile = File(context.filesDir, "morselink.log")
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
            } catch (_: Exception) {
            }
        }
        i("Log cleared by user")
    }

    /** Writes a snapshot for sharing; returns the file or null. */
    fun exportFile(context: Context): File? {
        return try {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val out = File(dir, "morselink-log-${System.currentTimeMillis()}.txt")
            out.writeText(tail(20000).joinToString("\n"))
            out
        } catch (_: Exception) {
            null
        }
    }

    fun installCrashHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val file = File(context.filesDir, "morselink-crash.log")
                FileWriter(file, true).use { w ->
                    w.write("\n==== CRASH ${fmt.format(Date())} ====\n")
                    w.write(Log.getStackTraceString(throwable))
                }
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
