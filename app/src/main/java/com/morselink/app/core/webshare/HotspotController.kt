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

/** Finds this phone's address on the hotspot/AP interface, preferring real APs. */
internal fun hotspotGatewayIp(): String {
    try {
        val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
        var fallback: String? = null
        for (ni in interfaces) {
            if (!ni.isUp || ni.isLoopback) continue
            val name = ni.name ?: continue
            val cellular = name.startsWith("rmnet") || name.startsWith("ccmni") ||
                name.startsWith("usb") || name.startsWith("vtun") || name.startsWith("tun")
            for (ia in java.util.Collections.list(ni.inetAddresses)) {
                if (!ia.isLoopbackAddress && ia is java.net.Inet4Address) {
                    val addr = ia.hostAddress ?: continue
                    if (cellular) continue
                    if (name.contains("ap") || name.contains("swlan") || name == "wlan1") return addr
                    if (addr.startsWith("192.168.")) return addr
                    if (fallback == null) fallback = addr
                }
            }
        }
        if (fallback != null) return fallback
    } catch (_: Exception) {
    }
    return "192.168.43.1"
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
                            ssid = cfg?.SSID?.removeSurrounding("\"")
                            password = cfg?.preSharedKey?.removeSurrounding("\"")
                        }
                    } catch (e: Exception) {
                        LogStore.w("Could not read hotspot config: ${e.message}")
                    }
                    val ip = hotspotGatewayIp()
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
        callback = onResult
        // 1. Hotspot already on (m5): deliver Ready immediately — no settings trip.
        if (isApActive()) {
            deliverReady()
            return
        }
        // 2. Try to switch it on programmatically (works on most API<=25 builds).
        val toggled = tryEnableAp()
        // 3. Poll in the background; only deep-link into settings if it stays off.
        Thread {
            var openedSettings = false
            var waited = 0
            while (waited < 60) {
                if (isApActive()) {
                    deliverReady()
                    return@Thread
                }
                if (!openedSettings && waited >= (if (toggled) 20 else 6)) {
                    openedSettings = true
                    openHotspotSettings()
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                waited++
            }
            postResult(HotspotResult.Failed("hotspot did not turn on"))
        }.apply { isDaemon = true }.start()
    }

    @Volatile
    private var callback: ((HotspotResult) -> Unit)? = null

    private fun postResult(result: HotspotResult) {
        val cb = callback ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { cb(result) }
    }

    private fun deliverReady() {
        val cfg = readApConfig()
        val ip = hotspotGatewayIp()
        LogStore.i("Legacy hotspot ready: ssid=${cfg?.first} ip=$ip")
        postResult(HotspotResult.Ready(cfg?.first ?: "AndroidHotspot", cfg?.second, ip))
    }

    private fun openHotspotSettings() {
        try {
            val intent = Intent("android.settings.WIFI_AP_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_WIRELESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                LogStore.w("Could not open hotspot settings: ${e.message}")
            }
        }
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

    private fun tryEnableAp(): Boolean {
        return try {
            val method = wm?.javaClass?.getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
            method?.isAccessible = true
            val ok = method?.invoke(wm, null, true) as? Boolean
            LogStore.i("setWifiApEnabled reflection result: $ok")
            ok == true
        } catch (e: Exception) {
            LogStore.w("setWifiApEnabled not available: ${e.message}")
            false
        }
    }

    /** Reads (ssid, password) via the hidden tethering API, best-effort. */
    fun readApConfig(): Pair<String?, String?>? {
        return try {
            val method = wm?.javaClass?.getMethod("getWifiApConfiguration")
            val cfg = method?.invoke(wm) as? WifiConfiguration ?: return null
            Pair(
                cfg.SSID?.removeSurrounding("\""),
                cfg.preSharedKey?.removeSurrounding("\"")
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun stop() {
        // Legacy path cannot programmatically stop the user's tethering.
    }
}
