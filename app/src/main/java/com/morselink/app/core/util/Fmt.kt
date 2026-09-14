package com.morselink.app.core.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.util.Locale

/** Formatting helpers shared by every screen. */
object Fmt {

    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun speed(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/s"

    fun eta(seconds: Long): String {
        if (seconds < 0 || seconds > 60L * 60L * 24L * 30L) return "--:--"
        if (seconds < 60) return String.format(Locale.US, "00:%02d", seconds)
        val m = seconds / 60
        val s = seconds % 60
        if (m < 60) return String.format(Locale.US, "%02d:%02d", m, s)
        val h = m / 60
        return String.format(Locale.US, "%d:%02d:%02d", h, m % 60, s)
    }

    fun percent(done: Long, total: Long): Int {
        if (total <= 0) return 100
        val p = (done * 100) / total
        if (p < 0) return 0
        if (p > 100) return 100
        return p.toInt()
    }
}
