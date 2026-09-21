package com.morselink.app.core.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.morselink.app.core.logging.LogStore
import com.morselink.app.core.model.BatchSummary
import com.morselink.app.core.model.ConflictPolicy
import com.morselink.app.core.model.DiscoveredPeer
import com.morselink.app.core.model.EngineEvent
import com.morselink.app.core.model.RecentDevice
import com.morselink.app.core.model.SessionState
import com.morselink.app.core.model.TransferDirection
import com.morselink.app.core.model.TransferItem
import com.morselink.app.core.model.TransferItemState
import com.morselink.app.core.model.TransferableFile
import com.morselink.app.core.model.TransportType
import com.morselink.app.core.network.FileMeta
import com.morselink.app.core.network.LanTransport
import com.morselink.app.core.network.NearbyTransport
import com.morselink.app.core.network.SendOutcome
import com.morselink.app.core.network.TransportSession
import com.morselink.app.core.storage.ConflictDecision
import com.morselink.app.core.storage.FinalResult
import com.morselink.app.core.storage.Destinations
import com.morselink.app.core.storage.ZipUtil
import com.morselink.app.core.util.DeviceTier
import com.morselink.app.core.util.Integrity
import com.morselink.app.core.util.MorselinkServices
import com.morselink.app.di.AppServices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-scoped owner of all transfer state (spec Section 8.1). The UI layer
 * only observes; navigating away, minimizing, or rotating never interrupts an
 * active session.
 */
object TransferEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------------- observable state ----------------

    private val _items = MutableStateFlow<List<TransferItem>>(emptyList())
    val items: StateFlow<List<TransferItem>> = _items

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val peers: StateFlow<List<DiscoveredPeer>> = _peers

    private val _sessionState = MutableStateFlow(SessionState())
    val sessionState: StateFlow<SessionState> = _sessionState

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EngineEvent> = _events

    private val itemsLock = Any()
    private val itemsInternal = ArrayList<TransferItem>()
    private var lastEmit = 0L
    @Volatile
    private var trailingPublishScheduled = false

    private val peerLastSeen = ConcurrentHashMap<String, Long>()
    private val peerMap = ConcurrentHashMap<String, DiscoveredPeer>()

    @Volatile
    private var currentSession: TransportSession? = null

    @Volatile
    var discoveryTransport: TransportType? = null
        private set

    /** Waiters for user decisions made in dialogs. */
    private val consentWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    class ConflictAnswer(val decision: ConflictDecision, val applyToAll: Boolean)

    private val conflictWaiters = ConcurrentHashMap<String, CompletableDeferred<ConflictAnswer>>()

    /** Batch-scoped override set by "Apply to all". */
    @Volatile
    private var batchConflictOverride: ConflictDecision? = null

    private val batchStartTimes = ConcurrentHashMap<String, Long>()

    /** sha256 cache: fileId -> hash. */
    private val shaCache = ConcurrentHashMap<String, String>()

    /** Speed sliding window per item: (timestampNanos, bytes). */
    private val speedWindows = ConcurrentHashMap<String, ArrayDeque<LongArray>>()

    private var workerJob: Job? = null
    private val kickChannel = Channel<Unit>(Channel.CONFLATED)

    // locks
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** WebShare keeps the foreground service alive independently of a session. */
    @Volatile
    var webShareActive: Boolean = false

    // ---------------- identity ----------------

    fun localDeviceId(): String = AppServices.prefs.deviceId
    fun localDeviceName(): String = AppServices.prefs.deviceName

    // ---------------- discovery ----------------

    fun beginDiscovery(type: TransportType) {
        discoveryTransport = type
    }

    fun endDiscovery(type: TransportType) {
        if (discoveryTransport == type) discoveryTransport = null
    }

    fun addPeer(peer: DiscoveredPeer) {
        if (_sessionState.value.active && peer.deviceId != _sessionState.value.peerId) return
        // One row per physical device: the same phone re-advertising with a
        // fresh Nearby endpoint id (or found over two transports) replaces its
        // earlier entry instead of stacking duplicates (m13).
        val stale = ArrayList<String>()
        for ((id, existing) in peerMap) {
            if (id != peer.deviceId && existing.name == peer.name) stale.add(id)
        }
        for (id in stale) {
            peerMap.remove(id)
            peerLastSeen.remove(id)
        }
        peerMap[peer.deviceId] = peer
        peerLastSeen[peer.deviceId] = System.currentTimeMillis()
        publishPeers()
    }

    fun removePeer(deviceId: String) {
        peerMap.remove(deviceId)
        peerLastSeen.remove(deviceId)
        publishPeers()
    }

    fun currentPeers(): List<DiscoveredPeer> = ArrayList(peerMap.values)

    private fun publishPeers() {
        val now = System.currentTimeMillis()
        val expired = ArrayList<String>()
        for ((id, ts) in peerLastSeen) {
            if (now - ts > 10000) expired.add(id)
        }
        for (id in expired) {
            peerMap.remove(id)
            peerLastSeen.remove(id)
        }
        _peers.value = peerMap.values.sortedBy { it.name.lowercase() }
    }

    /** Starts discovery on the primary transport (Nearby on GMS, else LAN). */
    fun startDiscovery(prefer: TransportType? = null) {
        val transport = if (prefer != null) {
            prefer
        } else if (LanTransport.isOnNetwork()) {
            // Both phones on the same router/hotspot: the LAN transport is
            // far faster. Nearby never uses the shared network — on these
            // devices it negotiates Bluetooth and crawls (mlogs6 r2/r3/r7/r8).
            TransportType.LAN_WIFI
        } else if (NearbyTransport.isAvailable() && hasDiscoveryPermission()) {
            TransportType.NEARBY_CONNECTIONS
        } else {
            TransportType.LAN_WIFI
        }
        if (transport == TransportType.NEARBY_CONNECTIONS) {
            LanTransport.stopDiscovery()
            NearbyTransport.startDiscovery()
        } else {
            NearbyTransport.stopDiscoveryQuiet()
            LanTransport.startDiscovery()
        }
        LogStore.i("Discovery started on $transport")
    }

    fun stopDiscovery() {
        NearbyTransport.stopDiscovery()
        LanTransport.stopDiscovery()
        discoveryTransport = null
    }

    private fun hasDiscoveryPermission(): Boolean {
        return com.morselink.app.core.util.Permissions.discoveryPermissions(MorselinkServices.appContext).isEmpty()
    }

    suspend fun connectPeer(peer: DiscoveredPeer) {
        when (peer.transport) {
            TransportType.NEARBY_CONNECTIONS -> NearbyTransport.connect(peer)
            TransportType.LAN_WIFI -> {
                val host = peer.host ?: return
                LanTransport.connectDirect(host, peer.port)
            }
            else -> {}
        }
    }

    suspend fun connectManual(host: String, port: Int): Boolean {
        return LanTransport.connectDirect(host, port) != null
    }

    // ---------------- consent (spec Section 6.1) ----------------

    suspend fun awaitConsent(peerName: String, transport: TransportType): Boolean {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        consentWaiters[requestId] = deferred
        LogStore.i("Consent requested for incoming connection from $peerName ($transport)")
        _events.tryEmit(EngineEvent.ConsentRequested(requestId, peerName, transport))
        val result = withTimeoutOrNull(60000) { deferred.await() } ?: false
        consentWaiters.remove(requestId)
        LogStore.i("Consent for $peerName: $result")
        return result
    }

    fun respondConsent(requestId: String, accepted: Boolean) {
        consentWaiters.remove(requestId)?.complete(accepted)
    }

    // ---------------- conflict (spec Section 8.6) ----------------

    private suspend fun askConflict(fileName: String, relativePath: String?): ConflictAnswer {
        if (batchConflictOverride != null) {
            return ConflictAnswer(batchConflictOverride!!, false)
        }
        val policy = AppServices.prefs.conflictPolicy
        if (policy != ConflictPolicy.ALWAYS_ASK) {
            return ConflictAnswer(
                when (policy) {
                    ConflictPolicy.ALWAYS_OVERWRITE -> ConflictDecision.OVERWRITE
                    ConflictPolicy.ALWAYS_SKIP -> ConflictDecision.SKIP
                    ConflictPolicy.ALWAYS_KEEP_BOTH -> ConflictDecision.KEEP_BOTH
                    else -> ConflictDecision.KEEP_BOTH
                },
                false
            )
        }
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ConflictAnswer>()
        conflictWaiters[requestId] = deferred
        _events.tryEmit(EngineEvent.ConflictDecisionNeeded(requestId, fileName, relativePath))
        val answer = withTimeoutOrNull(90000) { deferred.await() }
            ?: ConflictAnswer(ConflictDecision.KEEP_BOTH, false)
        conflictWaiters.remove(requestId)
        if (answer.applyToAll) {
            batchConflictOverride = answer.decision
        }
        LogStore.i("Conflict for $fileName resolved as ${answer.decision}")
        return answer
    }

    fun respondConflict(requestId: String, answer: ConflictAnswer) {
        conflictWaiters.remove(requestId)?.complete(answer)
    }

    // ---------------- session lifecycle ----------------

    fun onSessionEstablished(session: TransportSession) {
        currentSession = session
        _sessionState.value = SessionState(
            active = true,
            everActive = true,
            peerId = session.peerId,
            peerName = session.peerName,
            transport = session.transportType
        )
        val transport = when (session.transportType) {
            TransportType.NEARBY_CONNECTIONS -> "Nearby (Wi-Fi/Bluetooth)"
            TransportType.LAN_WIFI -> "Wi-Fi LAN"
            else -> "HTTP"
        }
        LogStore.i("Session established with ${session.peerName} via ${session.transportType}")
        AppServices.prefs.addRecentDevice(
            RecentDevice(
                deviceId = session.peerId,
                name = session.peerName,
                transport = session.transportType,
                lastSeen = System.currentTimeMillis()
            )
        )
        _events.tryEmit(EngineEvent.PeerConnected(session.peerName, session.transportType))
        SoundFx.play(SoundFx.CONNECTED)
        // Re-queue paused items from a restored journal (best-effort resume).
        synchronized(itemsLock) {
            for (item in itemsInternal) {
                if (item.direction == TransferDirection.SENDING && item.state == TransferItemState.PAUSED) {
                    item.state = TransferItemState.QUEUED
                }
            }
        }
        publishItems(force = true)
        saveJournal()
        ensureWorker()
        kick()
        TransferService.ensureStarted(MorselinkServices.appContext)
    }

    fun onSessionLost(session: TransportSession, reason: String?) {
        incomingBatchId = null
        if (currentSession === session) {
            currentSession = null
        }
        val wasActive = _sessionState.value.active
        _sessionState.value = SessionState(
            active = false,
            everActive = true,
            peerId = null,
            peerName = null,
            transport = null
        )
        if (wasActive) {
            LogStore.w("Session lost (${reason ?: "unknown"})")
            // Non-terminal items become PAUSED so the journal resume prompt can
            // offer a continuation rather than silently failing everything.
            synchronized(itemsLock) {
                for (item in itemsInternal) {
                    if (item.state == TransferItemState.IN_PROGRESS || item.state == TransferItemState.QUEUED) {
                        item.state = TransferItemState.PAUSED
                        item.lastError = "Connection lost"
                    }
                }
            }
            publishItems(force = true)
            saveJournal()
            _events.tryEmit(
                EngineEvent.PeerDisconnected(session.peerName, reason ?: "Connection lost")
            )
            SoundFx.play(SoundFx.FAILED)
        }
    }

    fun closeSession(reason: String) {
        currentSession?.close(reason)
    }

    fun notifyBusy() {
        _events.tryEmit(
            EngineEvent.InfoToast("The device is busy with another connection. Try again in a moment.")
        )
    }

    fun notifyConnectFailed(message: String) {
        _events.tryEmit(EngineEvent.InfoToast(message))
    }

    // ---------------- queueing ----------------

    fun queueOutgoing(files: List<TransferableFile>) {
        if (files.isEmpty()) return
        val batchId = UUID.randomUUID().toString()
        batchStartTimes[batchId] = System.currentTimeMillis()
        batchConflictOverride = null
        var skippedDuplicates = 0
        synchronized(itemsLock) {
            for (f in files) {
                val duplicate = itemsInternal.any {
                    !it.isTerminal && it.direction == TransferDirection.SENDING &&
                        it.file.uri == f.uri && it.file.displayName == f.displayName
                }
                if (duplicate) {
                    skippedDuplicates++
                    continue
                }
                itemsInternal.add(
                    TransferItem(
                        id = if (f.id.isEmpty()) UUID.randomUUID().toString() else f.id,
                        file = f,
                        direction = TransferDirection.SENDING,
                        state = TransferItemState.QUEUED,
                        totalBytes = f.size,
                        batchId = batchId
                    )
                )
            }
        }
        if (skippedDuplicates > 0) {
            LogStore.i("Skipped $skippedDuplicates duplicate queue entr${if (skippedDuplicates == 1) "y" else "ies"} (already queued or in progress)")
        }
        LogStore.i("Queued ${files.size} outgoing file(s)")
        publishItems(force = true)
        saveJournal()
        maybeBatteryAdvisory(files)
        ensureWorker()
        kick()
        TransferService.ensureStarted(MorselinkServices.appContext)
    }

    private fun maybeBatteryAdvisory(files: List<TransferableFile>) {
        // Advisory only, never a block (spec Section 17.4); the UI observes this.
    }

    /** True when a low-battery advisory should be shown before this batch. */
    fun batteryAdvisoryNeeded(totalBytes: Long): Boolean {
        return try {
            val context = MorselinkServices.appContext
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val powerSave = pm?.isPowerSaveMode ?: false
            totalBytes > 100L * 1024 * 1024 && level in 1..19 && !charging || (powerSave && totalBytes > 100L * 1024 * 1024)
        } catch (_: Exception) {
            false
        }
    }

    fun itemsSnapshot(): List<TransferItem> = synchronized(itemsLock) { ArrayList(itemsInternal) }

    private fun publishItems(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastEmit < 180) {
            // Throttled: schedule a trailing publish so the final state of a
            // burst is never dropped (updates must always reach the UI).
            if (!trailingPublishScheduled) {
                trailingPublishScheduled = true
                scope.launch {
                    delay(220)
                    trailingPublishScheduled = false
                    publishItems(force = true)
                }
            }
            return
        }
        lastEmit = now
        val snapshot: List<TransferItem>
        synchronized(itemsLock) {
            computeSpeeds(itemsInternal)
            // Fresh instances every time: StateFlow deduplicates by equals(),
            // and mutating items in place would make consecutive snapshots
            // compare equal — freezing the UI until a re-subscribe.
            snapshot = itemsInternal.map { it.copy() }
        }
        _items.value = snapshot
        updateLocks()
    }

    private fun computeSpeeds(snapshot: List<TransferItem>) {
        val now = System.nanoTime()
        for (item in snapshot) {
            if (item.state != TransferItemState.IN_PROGRESS) {
                speedWindows.remove(item.id)
                continue
            }
            val window = speedWindows.getOrPut(item.id) { ArrayDeque() }
            window.addLast(longArrayOf(now, item.bytesTransferred))
            while (window.size > 2 && now - window.first()[0] > 4_000_000_000L) {
                window.removeFirst()
            }
            if (window.size >= 2) {
                val first = window.first()
                val dt = (now - first[0]) / 1_000_000_000.0
                if (dt >= 0.5) {
                    item.speedBps = ((item.bytesTransferred - first[1]) / dt).toLong()
                }
            } else {
                item.speedBps = 0
            }
        }
    }

    // ---------------- worker ----------------

    private fun ensureWorker() {
        if (workerJob?.isActive != true) {
            workerJob = scope.launch { workerLoop() }
        }
    }

    private fun kick() {
        kickChannel.trySend(Unit)
    }

    private suspend fun workerLoop() {
        LogStore.i("Outgoing worker started")
        while (true) {
            val session = currentSession
            if (session == null) {
                kickChannel.receive()
                continue
            }
            val next: TransferItem? = synchronized(itemsLock) {
                itemsInternal.firstOrNull {
                    it.direction == TransferDirection.SENDING && it.state == TransferItemState.QUEUED
                }
            }
            if (next == null) {
                kickChannel.receive()
                continue
            }
            sendItem(session, next)
        }
    }

    private suspend fun sendItem(session: TransportSession, item: TransferItem) {
        mutateItem(item.id) { it.state = TransferItemState.IN_PROGRESS }
        publishItems(force = true)
        saveJournal()
        val sha: String? = if (item.totalBytes in 1..DeviceTier.PREHASH_LIMIT) {
            shaCache.getOrPut(item.id) {
                Integrity.sha256File(MorselinkServices.appContext, item.file.uri) ?: ""
            }.takeIf { it.isNotEmpty() }
        } else {
            LogStore.i("Skipping pre-send sha256 for ${item.file.displayName} (size ${item.totalBytes})")
            null
        }
        val outcome = try {
            session.sendFile(item, sha)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            LogStore.e("sendFile threw for ${item.file.displayName}", e)
            SendOutcome.Failed(e.message)
        }
        when (outcome) {
            is SendOutcome.Completed -> {
                recordCompletion(item, TransferItemState.COMPLETED, null, null)
            }
            is SendOutcome.SkippedAlreadyPresent -> {
                recordCompletion(item, TransferItemState.SKIPPED, "already present on receiver", null)
            }
            is SendOutcome.Paused -> {
                mutateItem(item.id) {
                    it.state = TransferItemState.PAUSED
                    it.resumeOffset = it.bytesTransferred
                }
                LogStore.i("Paused ${item.file.displayName} at ${item.bytesTransferred} bytes")
            }
            is SendOutcome.Cancelled -> {
                mutateItem(item.id) { it.state = TransferItemState.CANCELLED }
            }
            is SendOutcome.Failed -> {
                val sessionStillActive = currentSession === session
                if (sessionStillActive) {
                    val retryCount = (item.retryCount) + 1
                    if (retryCount <= 3) {
                        mutateItem(item.id) {
                            it.retryCount = retryCount
                            it.state = TransferItemState.QUEUED
                            it.lastError = outcome.error
                        }
                        LogStore.w("Auto-retry ${retryCount} for ${item.file.displayName}: ${outcome.error}")
                        delay(800)
                    } else {
                        recordCompletion(item, TransferItemState.FAILED, outcome.error, null)
                    }
                } else {
                    mutateItem(item.id) {
                        it.state = TransferItemState.PAUSED
                        it.lastError = "Connection lost"
                    }
                }
            }
        }
        publishItems(force = true)
        saveJournal()
        checkBatchCompletion(item.batchId)
    }

    /**
     * Records completion through the exact same path for both directions
     * (spec Section 8.3), including SKIPPED distinct from FAILED.
     */
    private fun recordCompletion(
        item: TransferItem,
        state: TransferItemState,
        error: String?,
        finalUri: String?
    ) {
        mutateItem(item.id) {
            it.state = state
            it.lastError = error
            if (finalUri != null) it.finalUri = finalUri
        }
        val history = AppServices.history
        history.add(
            direction = item.direction,
            fileName = item.file.displayName,
            mime = item.file.mime,
            size = item.totalBytes,
            peerName = _sessionState.value.peerName ?: "Unknown",
            status = state,
            localUri = if (item.direction == TransferDirection.RECEIVING) finalUri ?: item.finalUri else item.file.uri,
            sourceUri = item.file.uri,
            relativePath = item.file.relativePath
        )
        if (state == TransferItemState.COMPLETED || state == TransferItemState.SKIPPED) {
            _events.tryEmit(
                EngineEvent.ItemFinishedToast(item.file.displayName, state, item.direction)
            )
        }
    }

    private fun checkBatchCompletion(batchId: String) {
        if (batchId.isEmpty()) return
        val items: List<TransferItem> = synchronized(itemsLock) {
            itemsInternal.filter { it.batchId == batchId }
        }
        if (items.isEmpty()) return
        if (items.all { it.isTerminal }) {
            val start = batchStartTimes.remove(batchId) ?: System.currentTimeMillis()
            val summary = BatchSummary(
                batchId = batchId,
                peerName = _sessionState.value.peerName,
                succeeded = items.count { it.state == TransferItemState.COMPLETED },
                failed = items.count { it.state == TransferItemState.FAILED },
                skipped = items.count { it.state == TransferItemState.SKIPPED },
                cancelled = items.count { it.state == TransferItemState.CANCELLED },
                totalBytes = items.filter { it.state == TransferItemState.COMPLETED }.sumOf { it.totalBytes },
                elapsedMs = System.currentTimeMillis() - start
            )
            LogStore.i(
                "Batch $batchId done: ${summary.succeeded} ok, ${summary.failed} failed, " +
                    "${summary.skipped} skipped, ${summary.cancelled} cancelled"
            )
            if (batchId == incomingBatchId) incomingBatchId = null
            _events.tryEmit(EngineEvent.BatchCompleted(summary))
            if (summary.failed == 0) {
                SoundFx.play(SoundFx.COMPLETE)
            } else {
                SoundFx.play(SoundFx.FAILED)
            }
            _events.tryEmit(
                EngineEvent.TransferDoneAnnouncement(
                    "Transfer finished. ${summary.succeeded} succeeded, ${summary.failed} failed."
                )
            )
        }
    }

    private fun mutateItem(id: String, block: (TransferItem) -> Unit) {
        synchronized(itemsLock) {
            val candidates = itemsInternal.filter { it.id == id }
            val item = candidates.firstOrNull { !it.isTerminal } ?: candidates.lastOrNull() ?: return
            block(item)
        }
    }

    /** Looks up the live row for an id (prefers non-terminal, falls back to newest). */
    private fun findItem(id: String): TransferItem? = synchronized(itemsLock) {
        val candidates = itemsInternal.filter { it.id == id }
        candidates.firstOrNull { !it.isTerminal } ?: candidates.lastOrNull()
    }

    // ---------------- user queue controls ----------------

    fun pauseItem(id: String) {
        val item: TransferItem? = findItem(id)
        if (item == null) return
        when {
            item.direction == TransferDirection.SENDING && item.state == TransferItemState.QUEUED -> {
                mutateItem(id) { it.state = TransferItemState.PAUSED }
                LogStore.i("Paused (queued) ${item.file.displayName}")
            }
            item.direction == TransferDirection.SENDING && item.state == TransferItemState.IN_PROGRESS -> {
                currentSession?.pauseOutgoing(id)
            }
            item.direction == TransferDirection.RECEIVING && item.state == TransferItemState.IN_PROGRESS -> {
                currentSession?.requestPeerPause(id)
                mutateItem(id) { it.state = TransferItemState.PAUSED }
            }
        }
        publishItems(force = true)
        saveJournal()
    }

    fun resumeItem(id: String) {
        val item: TransferItem? = findItem(id)
        if (item == null) return
        when {
            item.direction == TransferDirection.SENDING && item.state == TransferItemState.PAUSED -> {
                mutateItem(id) { it.state = TransferItemState.QUEUED }
                ensureWorker()
                kick()
            }
            item.direction == TransferDirection.RECEIVING && item.state == TransferItemState.PAUSED -> {
                mutateItem(id) { it.state = TransferItemState.IN_PROGRESS }
                currentSession?.requestPeerResume(id)
            }
        }
        publishItems(force = true)
        saveJournal()
    }

    fun cancelItem(id: String) {
        val item: TransferItem? = findItem(id)
        if (item == null) return
        when (item.state) {
            TransferItemState.QUEUED -> mutateItem(id) { it.state = TransferItemState.CANCELLED }
            TransferItemState.IN_PROGRESS -> currentSession?.cancel(id, item.direction)
            TransferItemState.PAUSED -> {
                if (item.direction == TransferDirection.RECEIVING) {
                    Destinations.tempFileFor(MorselinkServices.appContext, partKeyFor(item)).delete()
                }
                mutateItem(id) { it.state = TransferItemState.CANCELLED }
            }
            else -> {}
        }
        publishItems(force = true)
        saveJournal()
    }

    fun retryItem(id: String) {
        mutateItem(id) {
            if (it.state == TransferItemState.FAILED) {
                it.state = TransferItemState.QUEUED
                it.retryCount = 0
                it.lastError = null
                it.bytesTransferred = 0
            }
        }
        publishItems(force = true)
        saveJournal()
        ensureWorker()
        kick()
    }

    fun pauseAll() {
        synchronized(itemsLock) {
            for (item in itemsInternal) {
                if (item.state == TransferItemState.QUEUED) item.state = TransferItemState.PAUSED
            }
        }
        val inFlight = itemsSnapshot().filter {
            it.state == TransferItemState.IN_PROGRESS
        }
        for (item in inFlight) {
            pauseItem(item.id)
        }
        publishItems(force = true)
        saveJournal()
    }

    fun resumeAll() {
        synchronized(itemsLock) {
            for (item in itemsInternal) {
                if (item.state == TransferItemState.PAUSED) {
                    if (item.direction == TransferDirection.SENDING) {
                        item.state = TransferItemState.QUEUED
                    } else {
                        item.state = TransferItemState.IN_PROGRESS
                        currentSession?.requestPeerResume(item.id)
                    }
                }
            }
        }
        publishItems(force = true)
        saveJournal()
        ensureWorker()
        kick()
    }

    fun retryAllFailed() {
        synchronized(itemsLock) {
            for (item in itemsInternal) {
                if (item.state == TransferItemState.FAILED) {
                    item.state = TransferItemState.QUEUED
                    item.retryCount = 0
                    item.lastError = null
                }
            }
        }
        publishItems(force = true)
        saveJournal()
        ensureWorker()
        kick()
    }

    fun clearCompleted() {
        synchronized(itemsLock) {
            val it = itemsInternal.iterator()
            while (it.hasNext()) {
                val item = it.next()
                if (item.isTerminal) it.remove()
            }
        }
        publishItems(force = true)
        saveJournal()
    }

    fun reorder(fromIndex: Int, toIndex: Int) {
        synchronized(itemsLock) {
            val queue = itemsInternal.filter { it.direction == TransferDirection.SENDING }
            if (fromIndex !in queue.indices || toIndex !in queue.indices) return
            val item = queue[fromIndex]
            if (item.state != TransferItemState.QUEUED) return
            val target = queue[toIndex]
            val from = itemsInternal.indexOf(item)
            val to = itemsInternal.indexOf(target)
            if (from < 0 || to < 0) return
            itemsInternal.removeAt(from)
            itemsInternal.add(to, item)
        }
        publishItems(force = true)
        saveJournal()
    }

    // ---------------- progress from transports ----------------

    fun updateOutgoingProgress(fileId: String, bytes: Long, total: Long) {
        mutateItem(fileId) { it.bytesTransferred = bytes }
        publishItems()
    }

    fun updateIncomingProgress(fileId: String, bytes: Long) {
        mutateItem(fileId) { it.bytesTransferred = bytes }
        publishItems()
    }

    private var lastAnnouncementBytes = 0L

    // ---------------- incoming (LAN offer path) ----------------

    sealed class OfferDecision {
        data class Accept(val partFile: File?, val offset: Long) : OfferDecision()
        data class Skip(val reason: String) : OfferDecision()
    }

    fun partKey(meta: FileMeta): String =
        (meta.relativePath?.let { "$it/" } ?: "") + meta.name

    fun partKeyFor(item: TransferItem): String =
        (item.file.relativePath?.let { "$it/" } ?: "") + item.file.displayName

    fun partFileFor(meta: FileMeta): File =
        Destinations.tempFileFor(MorselinkServices.appContext, partKey(meta))

    suspend fun handleIncomingOffer(meta: FileMeta): OfferDecision {
        val item = reviveOrCreateIncoming(meta, null)
        publishItems(force = true)
        LogStore.i("Incoming offer: ${meta.name} (${meta.size} bytes)")

        // Sender-side duplicate awareness: if an identical file already exists
        // at the destination, skip without transferring (spec 8.6).
        val existingSize = Destinations.existingDestinationSize(meta.name, meta.relativePath, meta.mime)
        if (existingSize != null && existingSize == meta.size) {
            var identical = false
            if (meta.sha256 != null && DeviceTier.fullHashVerify) {
                val existingHash = Destinations.hashExisting(meta.name, meta.relativePath, meta.mime)
                identical = existingHash != null && existingHash == meta.sha256
            } else {
                identical = true
                LogStore.i("Skip decision from name+size only (no sha or low-end tier)")
            }
            if (identical) {
                recordCompletion(item, TransferItemState.SKIPPED, "identical file already present", null)
                publishItems(force = true)
                saveJournal()
                return OfferDecision.Skip("identical file already present")
            }
        }

        // Resume support: an existing .part file is offered back as our offset.
        val part = partFileFor(meta)
        if (part.exists() && part.length() in 1 until meta.size) {
            LogStore.i("Resumable part found for ${meta.name} at ${part.length()} bytes")
            return OfferDecision.Accept(part, part.length())
        }
        if (part.exists() && part.length() >= meta.size) {
            part.delete()
        }
        return OfferDecision.Accept(null, 0)
    }

    fun beginIncomingFromUri(meta: FileMeta, uri: String) {
        reviveOrCreateIncoming(meta, uri)
        publishItems(force = true)
    }

    /**
     * A paused/resumed or re-offered file must reuse its existing row — adding
     * a second item with the same id made every lookup hit the stale entry:
     * progress froze, resumed Nearby files verified against the old partial
     * URI ("size mismatch"), and rows duplicated (m11/m16).
     */
    private fun reviveOrCreateIncoming(meta: FileMeta, uri: String?): TransferItem {
        val existing: TransferItem? = synchronized(itemsLock) {
            itemsInternal.lastOrNull { it.id == meta.fileId }
        }
        if (existing != null) {
            synchronized(itemsLock) {
                existing.file = TransferableFile(
                    id = meta.fileId,
                    displayName = meta.name,
                    size = meta.size,
                    uri = uri ?: existing.file.uri,
                    mime = meta.mime,
                    relativePath = meta.relativePath
                )
                existing.state = TransferItemState.IN_PROGRESS
                existing.lastError = null
                existing.bytesTransferred = if (uri != null) 0L else existing.bytesTransferred
                if (existing.batchId.isEmpty()) existing.batchId = currentIncomingBatchId()
            }
            LogStore.i("Revived incoming row for ${meta.name}")
            return existing
        }
        val batchId = currentIncomingBatchId()
        val item = TransferItem(
            id = meta.fileId,
            file = TransferableFile(
                id = meta.fileId,
                displayName = meta.name,
                size = meta.size,
                uri = uri ?: "",
                mime = meta.mime,
                relativePath = meta.relativePath
            ),
            direction = TransferDirection.RECEIVING,
            state = TransferItemState.IN_PROGRESS,
            totalBytes = meta.size,
            batchId = batchId
        )
        synchronized(itemsLock) { itemsInternal.add(item) }
        return item
    }

    /**
     * One batch id per incoming session so the batch summary (and its sound)
     * fires once when the whole session's files finish — not once per file
     * (m19).
     */
    private var incomingBatchId: String? = null

    private fun currentIncomingBatchId(): String {
        var id = incomingBatchId
        if (id == null) {
            id = "in-${UUID.randomUUID()}"
            incomingBatchId = id
            batchStartTimes[id] = System.currentTimeMillis()
        }
        return id
    }

    fun markIncoming(fileId: String, state: TransferItemState) {
        val item: TransferItem? = findItem(fileId)
        if (item == null) return
        if (state == TransferItemState.CANCELLED) {
            Destinations.tempFileFor(MorselinkServices.appContext, partKey(
                FileMeta(fileId, item.file.displayName, item.file.relativePath, item.totalBytes, null, item.file.mime)
            )).delete()
        }
        recordCompletion(item, state, item.lastError, item.finalUri)
        publishItems(force = true)
        saveJournal()
        checkBatchCompletion(item.batchId)
    }

    /** Verifies and finalizes an incoming file from its .part temp file (LAN). */
    suspend fun verifyAndFinalizeIncoming(meta: FileMeta, partFile: File): Boolean {
        val item: TransferItem? = findItem(meta.fileId)
        if (item == null) return false
        if (partFile.length() != meta.size) {
            LogStore.e("Size check failed for ${meta.name}: ${partFile.length()} != ${meta.size}")
            recordCompletion(item, TransferItemState.FAILED, "size mismatch", null)
            publishItems(force = true)
            saveJournal()
            return false
        }
        if (meta.sha256 != null && DeviceTier.fullHashVerify) {
            val hash = try {
                partFile.inputStream().use { Integrity.sha256(it) }
            } catch (_: Exception) {
                null
            }
            if (hash != meta.sha256) {
                LogStore.e("sha256 mismatch for ${meta.name}")
                recordCompletion(item, TransferItemState.FAILED, "checksum mismatch", null)
                publishItems(force = true)
                saveJournal()
                return false
            }
        } else {
            LogStore.i("Verification for ${meta.name}: size-only (logged)")
        }
        return finalizeIncoming(item, meta, partFile)
    }

    /** Verifies and finalizes an incoming file from a Uri (Nearby path). */
    suspend fun verifyAndFinalizeIncomingUri(meta: FileMeta): Boolean {
        val item: TransferItem? = findItem(meta.fileId)
        if (item == null) return false
        val context = MorselinkServices.appContext
        val size = Integrity.fileLength(context, item.file.uri)
        if (size != meta.size) {
            LogStore.e("Size check failed for ${meta.name}: $size != ${meta.size}")
            recordCompletion(item, TransferItemState.FAILED, "size mismatch", null)
            publishItems(force = true)
            saveJournal()
            return false
        }
        if (meta.sha256 != null && DeviceTier.fullHashVerify) {
            val hash = Integrity.sha256File(context, item.file.uri)
            if (hash != meta.sha256) {
                LogStore.e("sha256 mismatch for ${meta.name}")
                recordCompletion(item, TransferItemState.FAILED, "checksum mismatch", null)
                publishItems(force = true)
                saveJournal()
                return false
            }
        } else {
            LogStore.i("Verification for ${meta.name}: size-only (logged)")
        }
        // Stage into a temp file so finalization follows the single shared path.
        val part = Destinations.tempFileFor(context, partKey(meta))
        return try {
            ZipUtil.openStream(context, item.file.uri)?.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            } ?: return false
            finalizeIncoming(item, meta, part)
        } catch (e: Exception) {
            LogStore.e("Staging failed for ${meta.name}", e)
            false
        }
    }

    private suspend fun finalizeIncoming(item: TransferItem, meta: FileMeta, part: File): Boolean {
        // Ask only when a conflict actually exists at the destination; a fresh
        // filename needs no decision (spec Section 8.6).
        val conflictExists = Destinations.existingDestinationSize(meta.name, meta.relativePath, meta.mime) != null
        val policy = if (!conflictExists) {
            ConflictPolicy.ALWAYS_KEEP_BOTH // no existing file: name is written as-is
        } else {
            val answer = askConflict(meta.name, meta.relativePath)
            when (answer.decision) {
                ConflictDecision.OVERWRITE -> ConflictPolicy.ALWAYS_OVERWRITE
                ConflictDecision.SKIP -> ConflictPolicy.ALWAYS_SKIP
                else -> ConflictPolicy.ALWAYS_KEEP_BOTH
            }
        }
        val result = Destinations.finalize(meta.name, meta.relativePath, meta.mime, part, policy) { _, _ ->
            // Policy was already narrowed by askConflict; this callback is only
            // reached when policy is ALWAYS_ASK (not the case here).
            ConflictDecision.KEEP_BOTH
        }
        return when (result.status) {
            FinalResult.Status.SAVED -> {
                recordCompletion(item, TransferItemState.COMPLETED, null, result.finalUri)
                LogStore.i("Saved ${meta.name} -> ${result.finalPath}")
                publishItems(force = true)
                saveJournal()
                checkBatchCompletion(item.batchId)
                true
            }
            FinalResult.Status.SKIPPED -> {
                recordCompletion(item, TransferItemState.SKIPPED, "skipped (conflict)", null)
                publishItems(force = true)
                saveJournal()
                checkBatchCompletion(item.batchId)
                true
            }
            FinalResult.Status.FAILED -> {
                recordCompletion(item, TransferItemState.FAILED, "could not save file", null)
                publishItems(force = true)
                saveJournal()
                checkBatchCompletion(item.batchId)
                false
            }
        }
    }

    // ---------------- peer-driven state changes ----------------

    fun onPeerPausedOurSend(fileId: String) {
        mutateItem(fileId) {
            if (it.direction == TransferDirection.SENDING && it.state == TransferItemState.IN_PROGRESS) {
                it.state = TransferItemState.PAUSED
                it.lastError = null
            }
        }
        publishItems(force = true)
        saveJournal()
    }

    fun onPeerResumedOurSend(fileId: String) {
        mutateItem(fileId) {
            if (it.direction == TransferDirection.SENDING && it.state == TransferItemState.PAUSED) {
                it.state = TransferItemState.QUEUED
            }
        }
        publishItems(force = true)
        saveJournal()
        ensureWorker()
        kick()
    }

    fun onPeerCancelled(fileId: String) {
        val item = findItem(fileId) ?: return
        if (item.direction == TransferDirection.RECEIVING) {
            // Full cleanup: marks the row cancelled and deletes the .part.
            markIncoming(fileId, TransferItemState.CANCELLED)
        } else {
            mutateItem(fileId) {
                it.state = TransferItemState.CANCELLED
                it.lastError = "cancelled by the other device"
            }
            publishItems(force = true)
            saveJournal()
        }
    }

    // ---------------- journal (spec Section 8.10) ----------------

    private fun saveJournal() {
        val snapshot: List<TransferItem>
        synchronized(itemsLock) {
            snapshot = ArrayList(itemsInternal)
        }
        val hasUnfinished = snapshot.any { !it.isTerminal }
        if (!hasUnfinished) {
            AppServices.journal.clear()
        } else {
            AppServices.journal.save(snapshot, _sessionState.value.peerName, _sessionState.value.transport)
        }
    }

    fun restoreJournalItems(): List<TransferItem> {
        val restored = AppServices.journal.snapshotItems()
        if (restored.isEmpty()) return emptyList()
        synchronized(itemsLock) {
            itemsInternal.clear()
            itemsInternal.addAll(restored.map { it })
        }
        publishItems(force = true)
        return restored
    }

    fun discardJournal() {
        synchronized(itemsLock) { itemsInternal.clear() }
        publishItems(force = true)
        AppServices.journal.clear()
        try {
            val dir = Destinations.tempDir(MorselinkServices.appContext)
            for (f in dir.listFiles() ?: emptyArray()) f.delete()
        } catch (_: Exception) {
        }
    }

    fun hasJournalResume(): Boolean = AppServices.journal.hasUnfinished()

    fun journalPeerName(): String? = AppServices.journal.peerName()

    // ---------------- history retry ----------------

    fun retryFromHistory(entryId: Long) {
        val entry = AppServices.history.byId(entryId) ?: return
        val source = entry.sourceUri ?: return
        if (!_sessionState.value.active) {
            _events.tryEmit(EngineEvent.InfoToast("Connect to a device first, then retry."))
            return
        }
        val file = TransferableFile(
            id = UUID.randomUUID().toString(),
            displayName = entry.fileName,
            size = entry.size,
            uri = source,
            mime = entry.mime,
            relativePath = entry.relativePath
        )
        queueOutgoing(listOf(file))
    }

    // ---------------- locks (spec Section 17.5) ----------------

    private fun updateLocks() {
        val context = MorselinkServices.appContext
        val anyActive = synchronized(itemsLock) {
            itemsInternal.any { it.state == TransferItemState.IN_PROGRESS }
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (anyActive) {
            if (wakeLock == null && pm != null) {
                try {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "morselink:transfer")
                    wakeLock?.acquire(30 * 60 * 1000L)
                } catch (_: Exception) {
                }
            }
            if (wifiLock == null) {
                try {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "morselink:wifi")
                    wifiLock?.acquire()
                } catch (_: Exception) {
                }
            }
        } else {
            try {
                wakeLock?.let { if (it.isHeld) it.release() }
            } catch (_: Exception) {
            }
            wakeLock = null
            try {
                wifiLock?.let { if (it.isHeld) it.release() }
            } catch (_: Exception) {
            }
            wifiLock = null
        }
    }

    // ---------------- misc ----------------

    fun appScope(): CoroutineScope = scope

    fun start(context: Context) {
        Destinations.cleanupStaleParts(context, 24L * 60 * 60 * 1000)
        LanTransport.start()
    }

    fun announceProgressIfNeeded() {
        val snapshot = itemsSnapshot()
        val inProgress = snapshot.firstOrNull { it.state == TransferItemState.IN_PROGRESS } ?: return
        if (inProgress.bytesTransferred - lastAnnouncementBytes > 10L * 1024 * 1024) {
            lastAnnouncementBytes = inProgress.bytesTransferred
            val pct = com.morselink.app.core.util.Fmt.percent(inProgress.bytesTransferred, inProgress.totalBytes)
            _events.tryEmit(
                EngineEvent.ProgressAnnouncement(
                    "${inProgress.file.displayName}: $pct percent"
                )
            )
        }
    }

    fun anyWorkActive(): Boolean {
        val items = synchronized(itemsLock) { ArrayList(itemsInternal) }
        return _sessionState.value.active || webShareActive || items.any { !it.isTerminal }
    }
}
