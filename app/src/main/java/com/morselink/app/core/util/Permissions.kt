package com.morselink.app.core.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Permission sets by API level (spec Section 12), with rationale text keys
 * shown in-app before the system dialog (spec Section 18.1).
 */
object Permissions {

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Permissions needed for Nearby/LAN device discovery on this device. */
    fun discoveryPermissions(context: Context): List<String> {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = ArrayList<String>()
        for (p in needed) {
            if (!has(context, p)) missing.add(p)
        }
        return missing
    }

    /** Media library read permissions on this device. */
    fun mediaReadPermissions(context: Context): List<String> {
        val needed = if (Build.VERSION.SDK_INT >= 33) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = ArrayList<String>()
        for (p in needed) {
            if (!has(context, p)) missing.add(p)
        }
        return missing
    }

    fun notificationPermission(context: Context): List<String> {
        if (Build.VERSION.SDK_INT >= 33) {
            return if (!has(context, Manifest.permission.POST_NOTIFICATIONS)) {
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            } else emptyList()
        }
        return emptyList()
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 23) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        }
        return true
    }

    fun hasAllFilesAccess(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()
    }
}
