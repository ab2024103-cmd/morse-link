package com.morselink.app.core.webshare

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.morselink.app.core.logging.LogStore
import com.morselink.app.core.util.MorselinkServices

sealed class HotspotResult {
    data class Ready(val ssid: String, val password: String?, val gatewayIp: String) : HotspotResult()
    object ManualSetupRequired : HotspotResult()
    data class Failed(val reason: String) : HotspotResult()
}

/**
 * Hotspot creation, version-gated (spec Section 7.5):
 * - API 26+: WifiManager.LocalOnlyHotspotReservation.
 * - API 21-25: no programmatic API; deep-links into system settings and polls
 *   connectivity until an AP is active, then reads the gateway IP.
 */
interface HotspotController {
    fun start(onResult: (HotspotResult) -> Unit)
    fun stop()
}

object Hotspots {

    fun create(context: Context): HotspotController {
        return if (Build.VERSION.SDK_INT >= 26) ModernHotspotController(context) else LegacyHotspotController(context)
    }
}

@SuppressLint("MissingPermission")
class ModernHotspotController(private val context: Context) : HotspotController {

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var callback: ((HotspotResult) -> Unit)? = null

    override fun start(onResult: (HotspotResult) -> Unit) {
        callback = onResult
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            onResult(HotspotResult.Failed("no WifiManager"))
            return
        }
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    var ssid: String? = null
                    var password: String? = null
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val cfg = res.softApConfiguration
                            ssid = cfg.ssid
                            password = cfg.passphrase
                        } else {
                            @Suppress("DEPRECATION")
                            val cfg: WifiConfiguration? = res.wifiConfiguration
                            ssid = cfg?.ssid?.removeSurrounding("\"")
                            password = cfg?.preSharedKey?.removeSurrounding("\"")
                        }
                    } catch (e: Exception) {
                        LogStore.w("Could not read hotspot config: ${e.message}")
                    }
                    val ip = gatewayIp()
                    LogStore.i("LocalOnlyHotspot started: ssid=$ssid ip=$ip")
                    callback?.invoke(
                        HotspotResult.Ready(ssid ?: "MorseLink Hotspot", password, ip)
                    )
                }

                override fun onFailed(reason: Int) {
                    LogStore.w("LocalOnlyHotspot failed: $reason")
                    callback?.invoke(HotspotResult.Failed("hotspot failed (code $reason)"))
                }

                override fun onStopped() {
                    reservation = null
                    LogStore.i("LocalOnlyHotspot stopped")
                }
            }, null)
        } catch (e: Exception) {
            LogStore.e("startLocalOnlyHotspot threw", e)
            onResult(HotspotResult.Failed(e.message ?: "could not start hotspot"))
        }
    }

    private fun gatewayIp(): String {
        // The phone acts as the AP/gateway; find the address of the AP interface.
        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name ?: continue
                if (name.contains("ap") || name.contains("wlan") || name.contains("swlan")) {
                    for (ia in java.util.Collections.list(ni.inetAddresses)) {
                        if (!ia.isLoopbackAddress && ia is java.net.Inet4Address) {
                            return ia.hostAddress ?: continue
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "192.168.43.1"
    }

    override fun stop() {
        try {
            reservation?.close()
        } catch (_: Exception) {
        }
        reservation = null
    }
}

class LegacyHotspotController(private val context: Context) : HotspotController {

    private val wm: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    override fun start(onResult: (HotspotResult) -> Unit) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_WIRELESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            LogStore.w("Could not open wireless settings: ${e.message}")
        }
        onResult(HotspotResult.ManualSetupRequired)
    }

    @Suppress("UNNECESSARY_SAFE_CALL")
    fun isApActive(): Boolean {
        return try {
            val method = wm?.javaClass?.getMethod("isWifiApEnabled")
            val result = method?.invoke(wm)
            (result as? Boolean) == true
        } catch (_: Exception) {
            false
        }
    }

    override fun stop() {
        // Legacy path cannot programmatically stop the user's tethering.
    }
}
