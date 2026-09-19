package com.morselink.app

import android.app.Application
import com.morselink.app.core.logging.LogStore
import com.morselink.app.core.transfer.TransferEngine
import com.morselink.app.core.util.MorselinkServices
import com.morselink.app.di.AppServices

class MorselinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        MorselinkServices.appContext = this
        AppServices.init(this)
        LogStore.init(this)
        LogStore.enabled = AppServices.prefs.loggingEnabled
        LogStore.installCrashHandler(this)
        TransferEngine.start(this)
        LogStore.i("MorseLink ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started")
        LogStore.i(
            "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
        )
        if (LogStore.hasCrashReports()) {
            LogStore.w("Previous run ended in a crash — see the crash-reports section in the log viewer")
        }
        restoreTempLinkConfig()
    }

    /**
     * If a temporary link was interrupted (app killed) the phone's hotspot
     * configuration may still hold the one-time credentials — put the user's
     * own back as soon as the hotspot is off (TempLink).
     */
    private fun restoreTempLinkConfig() {
        Thread {
            try {
                val prefs = AppServices.prefs
                val ssid = prefs.tempLinkRestoreSsid ?: return@Thread
                if (ssid.isBlank()) return@Thread
                val key = prefs.tempLinkRestoreKey ?: ""
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager
                    ?: return@Thread
                val active = try {
                    val m = wm.javaClass.getMethod("isWifiApEnabled")
                    (m.invoke(wm) as? Boolean) == true
                } catch (_: Exception) {
                    true // unknown state: do not touch it now
                }
                if (active) return@Thread
                val cfg = android.net.wifi.WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    if (key.isNotBlank()) preSharedKey = "\"$key\""
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                val m = wm.javaClass.getMethod(
                    "setWifiApConfiguration",
                    android.net.wifi.WifiConfiguration::class.java
                )
                m.invoke(wm, cfg)
                prefs.tempLinkRestoreSsid = null
                prefs.tempLinkRestoreKey = null
                LogStore.i("TempLink: hotspot configuration restored after restart")
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        @Volatile
        private var instance: MorselinkApp? = null
        fun get(): Application = requireNotNull(instance) { "MorselinkApp not initialized" }
    }
}
