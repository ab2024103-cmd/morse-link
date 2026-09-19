package com.morselink.app.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.morselink.app.core.logging.LogStore
import com.morselink.app.core.webshare.hotspotGatewayIp
import java.security.SecureRandom

/**
 * Temporary "stranger" link (mlogs log 1789836543546 request):
 *
 * Sharing files with someone new normally means handing over your hotspot
 * password (or joining theirs) — and Android remembers the network forever,
 * so each side must go to Settings and forget it afterwards.
 *
 * TempLink instead creates a one-time link:
 * - HOST: a hotspot with a fresh random name + password is created for the
 *   session. On API < 26 the phone's own hotspot configuration is saved and
 *   RESTORED when the link ends, so the temporary credentials stop working
 *   and never come back (a new random one is generated next time).
 * - CLIENT: the network is added by MorseLink itself, and REMOVED again when
 *   leaving — the OS never keeps it, so there is nothing to forget.
 *
 * File transfer itself needs no extra wiring: both devices end up on the
 * same network, where the LAN transport's UDP discovery finds them.
 */
object TempLink {

    fun randomSsid(): String {
        return "MorseLink-" + token(4)
    }

    fun randomPassword(): String {
        // 10 alphanumerics — easy to read aloud, strong enough for a session.
        return token(10)
    }

    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"

    private fun token(len: Int): String {
        val rng = SecureRandom()
        val sb = StringBuilder(len)
        for (i in 0 until len) sb.append(ALPHABET[rng.nextInt(ALPHABET.length)])
        return sb.toString()
    }
}

/**
 * Host side. API 26+ uses LocalOnlyHotspot (already random + non-persistent).
 * API < 26 switches the legacy tethering AP to a temporary WifiConfiguration
 * and restores the user's own configuration on stop().
 */
@SuppressLint("MissingPermission")
class TempLinkHost(private val context: Context) {

    private val wm: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var originalCfg: WifiConfiguration? = null

    @Volatile
    var running: Boolean = false
        private set

    /** ssid/password are the values the system actually applied. */
    fun start(
        onReady: (ssid: String, password: String, ip: String) -> Unit,
        onFailed: (reason: String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= 26) startModern(onReady, onFailed) else startLegacy(onReady, onFailed)
    }

    // ---------------- API 26+ ----------------

    private fun startModern(
        onReady: (String, String, String) -> Unit,
        onFailed: (String) -> Unit
    ) {
        val manager = wm
        if (manager == null) {
            onFailed("no WifiManager")
            return
        }
        try {
            manager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    var ssid = TempLink.randomSsid()
                    var pass = TempLink.randomPassword()
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            ssid = res.softApConfiguration.ssid ?: ssid
                            pass = res.softApConfiguration.passphrase ?: pass
                        } else {
                            @Suppress("DEPRECATION")
                            val cfg = res.wifiConfiguration
                            ssid = cfg?.SSID?.removeSurrounding("\"") ?: ssid
                            pass = cfg?.preSharedKey?.removeSurrounding("\"") ?: pass
                        }
                    } catch (_: Exception) {
                    }
                    running = true
                    LogStore.i("TempLink: local-only hotspot ready (ssid=$ssid)")
                    onReady(ssid, pass, hotspotGatewayIp())
                }

                override fun onFailed(reason: Int) {
                    LogStore.w("TempLink: local-only hotspot failed ($reason)")
                    onFailed("hotspot failed (code $reason)")
                }

                override fun onStopped() {
                    reservation = null
                    running = false
                }
            }, null)
        } catch (e: Exception) {
            LogStore.e("TempLink: startLocalOnlyHotspot threw", e)
            onFailed(e.message ?: "could not start hotspot")
        }
    }

    // ---------------- API < 26 ----------------

    private fun startLegacy(
        onReady: (String, String, String) -> Unit,
        onFailed: (String) -> Unit
    ) {
        Thread {
            try {
                // A previous link may have ended without restoring (app killed);
                // put the user's own configuration back before anything else.
                restorePendingIfNeeded()
                // Remember the user's own hotspot settings to restore later.
                originalCfg = invoke("getWifiApConfiguration") as? WifiConfiguration
                if (originalCfg != null) {
                    persistRestorePoint(originalCfg!!)
                    LogStore.i("TempLink: saved original hotspot configuration")
                }
                // If the user's hotspot is already on, restart it so the
                // temporary configuration actually applies.
                if (apActive()) {
                    setApEnabled(null, false)
                    Thread.sleep(1500)
                }
                val ssid = TempLink.randomSsid()
                val pass = TempLink.randomPassword()
                val temp = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    preSharedKey = "\"$pass\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                val toggled = setApEnabled(temp, true)
                var waited = 0
                while (waited < 30 && !apActive()) {
                    Thread.sleep(1000)
                    waited++
                }
                if (!apActive()) {
                    post { onFailed(if (toggled) "hotspot did not turn on" else "cannot control the hotspot on this phone") }
                    return@Thread
                }
                running = true
                // OEMs may override the requested name — report what applies.
                val applied = invoke("getWifiApConfiguration") as? WifiConfiguration
                val realSsid = applied?.SSID?.removeSurrounding("\"")?.takeIf { it.isNotBlank() } ?: ssid
                val realPass = applied?.preSharedKey?.removeSurrounding("\"") ?: pass
                LogStore.i("TempLink: temporary hotspot ready (ssid=$realSsid)")
                post { onReady(realSsid, realPass, hotspotGatewayIp()) }
            } catch (e: Exception) {
                LogStore.e("TempLink: temporary hotspot failed", e)
                post { onFailed(e.message ?: "could not start hotspot") }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                reservation?.close()
            } catch (_: Exception) {
            }
            reservation = null
        } else {
            Thread {
                try {
                    setApEnabled(null, false)
                    // Put the user's own hotspot configuration back so their
                    // normal name/password return and the temporary
                    // credentials die with the link.
                    val original = originalCfg
                    if (original != null) {
                        val ok = invokeWith(
                            "setWifiApConfiguration",
                            WifiConfiguration::class.java,
                            original
                        ) as? Boolean
                        LogStore.i("TempLink: original hotspot configuration restored ($ok)")
                    } else {
                        LogStore.w("TempLink: no original configuration to restore")
                    }
                } catch (e: Exception) {
                    LogStore.w("TempLink: restore failed: ${e.message}")
                }
                originalCfg = null
                clearRestorePoint()
            }.apply { isDaemon = true }.start()
        }
        running = false
        LogStore.i("TempLink: link ended")
    }

    // ---------------- restore persistence ----------------

    /**
     * The original configuration is kept in preferences so it survives an app
     * kill mid-link: the next TempLink use (or app start, when the hotspot is
     * off) puts it back.
     */
    private fun persistRestorePoint(cfg: WifiConfiguration) {
        try {
            val prefs = com.morselink.app.di.AppServices.prefs
            prefs.tempLinkRestoreSsid = cfg.SSID?.removeSurrounding("\"") ?: ""
            prefs.tempLinkRestoreKey = cfg.preSharedKey?.removeSurrounding("\"") ?: ""
        } catch (_: Exception) {
        }
    }

    private fun clearRestorePoint() {
        try {
            val prefs = com.morselink.app.di.AppServices.prefs
            prefs.tempLinkRestoreSsid = null
            prefs.tempLinkRestoreKey = null
        } catch (_: Exception) {
        }
    }

    private fun restorePendingIfNeeded() {
        try {
            val prefs = com.morselink.app.di.AppServices.prefs
            val ssid = prefs.tempLinkRestoreSsid ?: return
            if (ssid.isBlank()) return
            val key = prefs.tempLinkRestoreKey ?: ""
            val cfg = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (key.isNotBlank()) preSharedKey = "\"$key\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
            invokeWith("setWifiApConfiguration", WifiConfiguration::class.java, cfg)
            LogStore.i("TempLink: restored previous hotspot configuration")
            clearRestorePoint()
        } catch (_: Exception) {
        }
    }

    // ---------------- reflection helpers ----------------

    private fun apActive(): Boolean {
        return try {
            val m = wm?.javaClass?.getMethod("isWifiApEnabled")
            (m?.invoke(wm) as? Boolean) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun setApEnabled(cfg: WifiConfiguration?, enable: Boolean): Boolean {
        return try {
            val m = wm?.javaClass?.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType
            )
            m?.isAccessible = true
            (m?.invoke(wm, cfg, enable) as? Boolean) == true
        } catch (e: Exception) {
            LogStore.w("TempLink: setWifiApEnabled unavailable: ${e.message}")
            false
        }
    }

    private fun invoke(name: String): Any? {
        return try {
            val m = wm?.javaClass?.getMethod(name)
            m?.isAccessible = true
            m?.invoke(wm)
        } catch (_: Exception) {
            null
        }
    }

    private fun invokeWith(name: String, argType: Class<*>, arg: Any): Any? {
        return try {
            val m = wm?.javaClass?.getMethod(name, argType)
            m?.isAccessible = true
            m?.invoke(wm, arg)
        } catch (_: Exception) {
            null
        }
    }

    private fun post(r: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { r() }
    }
}

/**
 * Client side. On API < 29 MorseLink adds the network itself (and removes it
 * in leave(), so the OS forgets it — nothing to clean up in Settings). On
 * API 29+ apps can no longer join networks programmatically; join() reports
 * "manual" so the UI can guide the user.
 */
@SuppressLint("MissingPermission")
class TempLinkClient(private val context: Context) {

    private val wm: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var netId: Int = -1
    private var lastSsid: String = ""

    val joinedSsid: String? get() = if (netId >= 0) lastSsid else null

    fun canJoinProgrammatically(): Boolean = Build.VERSION.SDK_INT < 29

    fun join(
        ssid: String,
        password: String,
        onConnected: (ip: String) -> Unit,
        onFailed: (reason: String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= 29) {
            onFailed("manual")
            return
        }
        val manager = wm
        if (manager == null) {
            onFailed("no WifiManager")
            return
        }
        Thread {
            try {
                val cfg = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    preSharedKey = "\"$password\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                val id = manager.addNetwork(cfg)
                if (id < 0) {
                    post { onFailed("could not add the network") }
                    return@Thread
                }
                netId = id
                lastSsid = ssid
                manager.enableNetwork(id, true)
                var waited = 0
                while (waited < 30) {
                    val info = manager.connectionInfo
                    val cur = info?.ssid?.removeSurrounding("\"")
                    if (info != null && info.ipAddress != 0 &&
                        cur != null && cur.equals(ssid, ignoreCase = true)
                    ) {
                        val ip = android.text.format.Formatter.formatIpAddress(info.ipAddress)
                        LogStore.i("TempLink: joined $ssid ($ip)")
                        post { onConnected(ip) }
                        return@Thread
                    }
                    Thread.sleep(1000)
                    waited++
                }
                // Failed to connect: remove the network right away so nothing
                // is remembered.
                leave()
                post { onFailed("could not join $ssid") }
            } catch (e: Exception) {
                LogStore.e("TempLink: join failed", e)
                leave()
                post { onFailed(e.message ?: "could not join") }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Forgets the temporary network — the OS keeps nothing afterwards. */
    fun leave() {
        val id = netId
        if (id < 0) return
        netId = -1
        try {
            wm?.removeNetwork(id)
            @Suppress("DEPRECATION")
            wm?.saveConfiguration()
            LogStore.i("TempLink: temporary network removed (nothing remembered)")
        } catch (e: Exception) {
            LogStore.w("TempLink: remove network failed: ${e.message}")
        }
    }

    private fun post(r: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { r() }
    }
}
