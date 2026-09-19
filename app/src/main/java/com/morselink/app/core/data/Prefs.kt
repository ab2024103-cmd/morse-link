package com.morselink.app.core.data

import android.content.Context
import android.content.SharedPreferences
import com.morselink.app.core.model.ConflictPolicy
import com.morselink.app.core.model.RecentDevice
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** App preferences (SharedPreferences-backed). */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("morselink_prefs", Context.MODE_PRIVATE)

    var deviceName: String
        get() = sp.getString(KEY_DEVICE_NAME, null) ?: android.os.Build.MODEL ?: "Android"
        set(value) = sp.edit().putString(KEY_DEVICE_NAME, value).apply()

    val deviceId: String
        get() {
            val existing = sp.getString(KEY_DEVICE_ID, null)
            if (existing != null) return existing
            val fresh = UUID.randomUUID().toString()
            sp.edit().putString(KEY_DEVICE_ID, fresh).apply()
            return fresh
        }

    var avatarColorIndex: Int
        get() = sp.getInt(KEY_AVATAR_COLOR, 0)
        set(value) = sp.edit().putInt(KEY_AVATAR_COLOR, value).apply()

    var themeMode: String
        get() = sp.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = sp.edit().putString(KEY_THEME, value).apply()

    var soundEffects: Boolean
        get() = sp.getBoolean(KEY_SOUNDS, true)
        set(value) = sp.edit().putBoolean(KEY_SOUNDS, value).apply()

    var transferNotifications: Boolean
        get() = sp.getBoolean(KEY_TRANSFER_NOTIF, true)
        set(value) = sp.edit().putBoolean(KEY_TRANSFER_NOTIF, value).apply()

    var conflictPolicy: ConflictPolicy
        get() = ConflictPolicy.fromKey(sp.getString(KEY_CONFLICT_POLICY, null))
        set(value) = sp.edit().putString(KEY_CONFLICT_POLICY, value.key).apply()

    /** SAF tree uri of the default download location, or null for system default. */
    var defaultDownloadDirUri: String?
        get() = sp.getString(KEY_DOWNLOAD_DIR, null)
        set(value) = sp.edit().putString(KEY_DOWNLOAD_DIR, value).apply()

    var lastSeenCrashSize: Long
        get() = sp.getLong("lastSeenCrashSize", 0L)
        set(v) = sp.edit().putLong("lastSeenCrashSize", v).apply()

    var onboardingDone: Boolean
        get() = sp.getBoolean(KEY_ONBOARDING, false)
        set(value) = sp.edit().putBoolean(KEY_ONBOARDING, value).apply()

    var crashLogsEnabled: Boolean
        get() = sp.getBoolean(KEY_CRASH_LOGS, true)
        set(value) = sp.edit().putBoolean(KEY_CRASH_LOGS, value).apply()

    var loggingEnabled: Boolean
        get() = sp.getBoolean(KEY_LOGGING, true)
        set(value) = sp.edit().putBoolean(KEY_LOGGING, value).apply()

    var batteryPromptShown: Boolean
        get() = sp.getBoolean(KEY_BATTERY_PROMPT, false)
        set(value) = sp.edit().putBoolean(KEY_BATTERY_PROMPT, value).apply()

    /** Original hotspot config to restore after a temporary link (TempLink). */
    var tempLinkRestoreSsid: String?
        get() = sp.getString(KEY_TEMP_SSID, null)
        set(value) = sp.edit().putString(KEY_TEMP_SSID, value).apply()

    var tempLinkRestoreKey: String?
        get() = sp.getString(KEY_TEMP_KEY, null)
        set(value) = sp.edit().putString(KEY_TEMP_KEY, value).apply()

    /** Grid (true) or list (false) view for media tabs, persisted. */
    var mediaViewGrid: Boolean
        get() = sp.getBoolean(KEY_MEDIA_GRID, true)
        set(value) = sp.edit().putBoolean(KEY_MEDIA_GRID, value).apply()

    // ---- one-time contextual tips ----
    fun tipShown(key: String): Boolean = sp.getBoolean("tip_$key", false)
    fun markTipShown(key: String) = sp.edit().putBoolean("tip_$key", true).apply()

    // ---- recent devices ----
    fun recentDevices(): List<RecentDevice> {
        val raw = sp.getString(KEY_RECENT, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<RecentDevice>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    RecentDevice(
                        deviceId = o.getString("deviceId"),
                        name = o.getString("name"),
                        transport = com.morselink.app.core.model.TransportType.valueOf(o.getString("transport")),
                        host = if (o.has("host")) o.getString("host") else null,
                        port = if (o.has("port")) o.getInt("port") else 0,
                        lastSeen = o.getLong("lastSeen")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addRecentDevice(device: RecentDevice) {
        // Dedupe by id AND by name+transport: Nearby endpoint ids change every
        // session, so the same phone would otherwise stack duplicate rows.
        val list = ArrayList(recentDevices().filter {
            it.deviceId != device.deviceId &&
                !(it.name == device.name && it.transport == device.transport)
        })
        list.add(0, device)
        while (list.size > 5) list.removeAt(list.size - 1)
        val arr = JSONArray()
        for (d in list) {
            val o = JSONObject()
            o.put("deviceId", d.deviceId)
            o.put("name", d.name)
            o.put("transport", d.transport.name)
            if (d.host != null) o.put("host", d.host)
            if (d.port != 0) o.put("port", d.port)
            o.put("lastSeen", d.lastSeen)
            arr.put(o)
        }
        sp.edit().putString(KEY_RECENT, arr.toString()).apply()
    }

    companion object {
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_AVATAR_COLOR = "avatar_color"
        private const val KEY_THEME = "theme"
        private const val KEY_SOUNDS = "sounds"
        private const val KEY_TRANSFER_NOTIF = "transfer_notif"
        private const val KEY_CONFLICT_POLICY = "conflict_policy"
        private const val KEY_DOWNLOAD_DIR = "download_dir"
        private const val KEY_ONBOARDING = "onboarding_done"
        private const val KEY_CRASH_LOGS = "crash_logs"
        private const val KEY_LOGGING = "logging_enabled"
        private const val KEY_BATTERY_PROMPT = "battery_prompt_shown"
        private const val KEY_MEDIA_GRID = "media_view_grid"
        private const val KEY_RECENT = "recent_devices"
        private const val KEY_TEMP_SSID = "temp_link_restore_ssid"
        private const val KEY_TEMP_KEY = "temp_link_restore_key"
    }
}
