package com.morselink.app.core.webshare

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.morselink.app.core.logging.LogStore
import com.morselink.app.core.network.LanTransport
import com.morselink.app.core.transfer.TransferEngine
import com.morselink.app.core.transfer.TransferService
import com.morselink.app.core.util.MorselinkServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom

data class WebShareState(
    val running: Boolean = false,
    val url: String? = null,
    val hotspotMode: Boolean = false,
    val ssid: String? = null,
    val password: String? = null,
    val manualSetup: Boolean = false,
    val error: String? = null
)

/**
 * Owns the WebShare HTTP server, hotspot lifecycle, mDNS advertisement and
 * idle teardown (spec Section 7.5).
 */
object WebShareController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(WebShareState())
    val state: StateFlow<WebShareState> = _state

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private var server: WebShareServer? = null
    private var hotspot: HotspotController? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdRegistration: NsdManager.RegistrationListener? = null
    private var teardownJob: Job? = null
    private var lastToken: String? = null

    fun start(hotspotMode: Boolean): Boolean {
        if (_isRunning.value) return true
        val token = newToken()
        lastToken = token
        val s = WebShareServer(token)
        try {
            s.start(5000)
        } catch (e: Exception) {
            LogStore.e("WebShare: server failed to start", e)
            _state.value = WebShareState(error = "Could not start server: port may be in use")
            return false
        }
        server = s
        if (hotspotMode) {
            _state.value = WebShareState(running = true, hotspotMode = true, url = null)
            hotspot = Hotspots.create(MorselinkServices.appContext)
            hotspot?.start { result ->
                when (result) {
                    is HotspotResult.Ready -> {
                        val url = "http://${result.gatewayIp}:${WebShareServer.PORT}/#t=$token"
                        _state.value = WebShareState(
                            running = true, hotspotMode = true, url = url,
                            ssid = result.ssid, password = result.password
                        )
                        LogStore.i("WebShare: hotspot mode ready at $url (ssid=${result.ssid})")
                    }
                    is HotspotResult.ManualSetupRequired -> {
                        _state.value = WebShareState(
                            running = true, hotspotMode = true, manualSetup = true,
                            url = "http://192.168.43.1:${WebShareServer.PORT}/#t=$token"
                        )
                        LogStore.i("WebShare: manual hotspot setup; showing default gateway URL")
                        pollForHotspotAddress(token)
                    }
                    is HotspotResult.Failed -> {
                        _state.value = _state.value.copy(error = result.reason)
                        LogStore.w("WebShare: hotspot failed: ${result.reason}")
                    }
                }
            }
        } else {
            val ip = LanTransport.preferredAddress()
            if (ip == null) {
                _state.value = WebShareState(error = "Not connected to Wi-Fi. Use hotspot mode instead.")
                s.stop()
                server = null
                return false
            }
            val url = "http://$ip:${WebShareServer.PORT}/#t=$token"
            _state.value = WebShareState(running = true, hotspotMode = false, url = url)
            LogStore.i("WebShare: LAN mode ready at $url")
        }
        _isRunning.value = true
        registerMdns()
        TransferEngine.webShareActive = true
        TransferService.ensureStarted(MorselinkServices.appContext)
        startTeardownWatcher()
        return true
    }

    private fun pollForHotspotAddress(token: String) {
        scope.launch {
            var attempts = 0
            while (isActive && _isRunning.value && attempts < 120) {
                val ip = LanTransport.preferredAddress()
                if (ip != null && ip.startsWith("192.168.")) {
                    // Heuristic: once an AP interface is up we expose our address.
                    _state.value = _state.value.copy(
                        manualSetup = false,
                        url = "http://$ip:${WebShareServer.PORT}/#t=$token"
                    )
                    return@launch
                }
                attempts++
                delay(2000)
            }
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return com.morselink.app.core.util.Integrity.toHex(bytes)
    }

    private fun registerMdns() {
        try {
            val context = MorselinkServices.appContext
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                multicastLock = wm.createMulticastLock("morselink:mdns")
                multicastLock?.setReferenceCounted(false)
                multicastLock?.acquire()
            }
            val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsd != null) {
                val info = NsdServiceInfo()
                info.serviceName = "MorseLink (${com.morselink.app.di.AppServices.prefs.deviceName})"
                info.serviceType = "_http._tcp."
                info.port = WebShareServer.PORT
                val listener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                        LogStore.i("WebShare: mDNS registered")
                    }

                    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        LogStore.w("WebShare: mDNS registration failed: $errorCode")
                    }

                    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                }
                nsdRegistration = listener
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        } catch (e: Exception) {
            LogStore.w("WebShare: mDNS setup skipped: ${e.message}")
        }
    }

    private fun startTeardownWatcher() {
        teardownJob?.cancel()
        teardownJob = scope.launch {
            while (isActive && _isRunning.value) {
                delay(20000)
                val s = server ?: break
                val context = MorselinkServices.appContext
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val interactive = pm?.isInteractive ?: true
                val idleMs = System.currentTimeMillis() - s.lastClientActivity
                val engineBusy = TransferEngine.anyWorkActive() && !TransferEngine.sessionState.value.active.not()
                if (!interactive && idleMs > 180000 && s.activeOperations == 0) {
                    LogStore.i("WebShare: auto-teardown (screen off, no client for 3 minutes)")
                    stop()
                    break
                }
            }
        }
    }

    fun stop() {
        teardownJob?.cancel()
        teardownJob = null
        try {
            nsdRegistration?.let {
                val nsd = MorselinkServices.appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
                nsd?.unregisterService(it)
            }
        } catch (_: Exception) {
        }
        nsdRegistration = null
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        multicastLock = null
        hotspot?.stop()
        hotspot = null
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        lastToken = null
        _state.value = WebShareState()
        _isRunning.value = false
        TransferEngine.webShareActive = false
        LogStore.i("WebShare: stopped")
    }
}
