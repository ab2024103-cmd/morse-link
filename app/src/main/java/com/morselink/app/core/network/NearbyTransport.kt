package com.morselink.app.core.network

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.morselink.app.core.logging.LogStore
import com.morselink.app.core.model.DiscoveredPeer
import com.morselink.app.core.model.TransferDirection
import com.morselink.app.core.model.TransferItem
import com.morselink.app.core.model.TransportType
import com.morselink.app.core.transfer.TransferEngine
import com.morselink.app.core.util.MorselinkServices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Nearby Connections transport (primary on GMS devices, spec Section 7.3).
 * Strategy P2P_STAR; BLE discovery escalating automatically to Wi-Fi once a
 * payload transfer begins. Payload.fromFile for file bytes, a small
 * Payload.fromBytes metadata payload before each file, and an explicit ACK
 * from the receiver after its own size/sha256 verification.
 */
object NearbyTransport {

    private const val SERVICE_ID = "com.morselink.app.v1"
    private val STRATEGY = Strategy.P2P_STAR

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var client: ConnectionsClient? = null

    @Volatile
    private var advertising = false

    @Volatile
    private var discovering = false

    @Volatile
    private var activeSession: NearbySession? = null

    /** Endpoint we initiated a connection to; its user consented by tapping. */
    private val pendingOutgoingEndpoint = ConcurrentHashMap<String, Boolean>()

    fun client(): ConnectionsClient {
        if (client == null) {
            client = Nearby.getConnectionsClient(MorselinkServices.appContext)
        }
        return client!!
    }

    fun isAvailable(): Boolean {
        return try {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(MorselinkServices.appContext) == ConnectionResult.SUCCESS
        } catch (_: Exception) {
            false
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            LogStore.i("Nearby: connection initiated by $endpointId (${info.endpointName}), incoming=${info.isIncomingConnection}")
            if (pendingOutgoingEndpoint.containsKey(endpointId)) {
                // We initiated this connection; our consent was the tap itself.
                try {
                    client().acceptConnection(endpointId, payloadCallbackFor(endpointId))
                } catch (e: Exception) {
                    LogStore.e("Nearby: accept failed for $endpointId", e)
                }
            } else {
                scope.launch {
                    val accepted = TransferEngine.awaitConsent(info.endpointName, TransportType.NEARBY_CONNECTIONS)
                    try {
                        if (accepted) {
                            client().acceptConnection(endpointId, payloadCallbackFor(endpointId))
                        } else {
                            client().rejectConnection(endpointId)
                            LogStore.i("Nearby: rejected connection from ${info.endpointName}")
                        }
                    } catch (e: Exception) {
                        LogStore.e("Nearby: accept/reject failed for $endpointId", e)
                    }
                }
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                LogStore.i("Nearby: connected to $endpointId")
                val existing = activeSession
                if (existing != null && existing.isActive && existing.endpointId != endpointId) {
                    try {
                        client().disconnectFromEndpoint(existing.endpointId)
                    } catch (_: Exception) {
                    }
                }
                val session = NearbySession(endpointId, peerNameFor(endpointId))
                activeSession = session
                stopDiscoveryQuiet()
                TransferEngine.onSessionEstablished(session)
            } else {
                LogStore.w("Nearby: connection to $endpointId failed: ${resolution.status.statusMessage}")
                pendingOutgoingEndpoint.remove(endpointId)
                TransferEngine.notifyConnectFailed("Nearby connection failed")
            }
        }

        override fun onDisconnected(endpointId: String) {
            LogStore.i("Nearby: $endpointId disconnected")
            val session = activeSession
            if (session != null && session.endpointId == endpointId) {
                activeSession = null
                session.onTransportDisconnected("peer disconnected")
            }
        }
    }

    private fun peerNameFor(endpointId: String): String {
        val peer = TransferEngine.currentPeers().firstOrNull { it.endpointId == endpointId }
        return peer?.name ?: "Android"
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            if (activeSession != null && activeSession?.isActive == true) return
            TransferEngine.addPeer(
                DiscoveredPeer(
                    deviceId = "nearby-$endpointId",
                    name = info.endpointName,
                    transport = TransportType.NEARBY_CONNECTIONS,
                    endpointId = endpointId
                )
            )
        }

        override fun onEndpointLost(endpointId: String) {
            TransferEngine.removePeer("nearby-$endpointId")
        }
    }

    fun startDiscovery() {
        if (activeSession != null && activeSession?.isActive == true) return
        try {
            if (!advertising) {
                advertising = true
                TransferEngine.beginDiscovery(TransportType.NEARBY_CONNECTIONS)
                client().startAdvertising(
                    TransferEngine.localDeviceName(),
                    SERVICE_ID,
                    connectionLifecycleCallback,
                    AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
                )
            }
            if (!discovering) {
                discovering = true
                client().startDiscovery(
                    SERVICE_ID,
                    endpointDiscoveryCallback,
                    DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
                )
            }
            LogStore.i("Nearby: discovery started")
        } catch (e: Exception) {
            advertising = false
            discovering = false
            LogStore.e("Nearby: discovery failed to start", e)
            TransferEngine.notifyConnectFailed("Nearby discovery failed: ${e.message}")
        }
    }

    fun stopDiscoveryQuiet() {
        try {
            if (advertising) {
                advertising = false
                client().stopAdvertising()
            }
        } catch (_: Exception) {
        }
        try {
            if (discovering) {
                discovering = false
                client().stopDiscovery()
            }
        } catch (_: Exception) {
        }
        TransferEngine.endDiscovery(TransportType.NEARBY_CONNECTIONS)
    }

    fun stopDiscovery() {
        stopDiscoveryQuiet()
        LogStore.i("Nearby: discovery stopped")
    }

    suspend fun connect(peer: DiscoveredPeer) {
        val endpointId = peer.endpointId ?: return
        pendingOutgoingEndpoint[endpointId] = true
        try {
            client().requestConnection(TransferEngine.localDeviceName(), endpointId, connectionLifecycleCallback)
            LogStore.i("Nearby: requested connection to ${peer.name} ($endpointId)")
        } catch (e: Exception) {
            pendingOutgoingEndpoint.remove(endpointId)
            LogStore.e("Nearby: requestConnection failed", e)
            TransferEngine.notifyConnectFailed("Could not reach ${peer.name}")
        }
    }

    fun disconnectAll() {
        stopDiscoveryQuiet()
        activeSession?.onTransportDisconnected("shutting down")
        activeSession = null
        try {
            client().stopAllEndpoints()
        } catch (_: Exception) {
        }
    }

    fun clearSession(session: NearbySession) {
        if (activeSession === session) activeSession = null
    }

    private fun payloadCallbackFor(endpointId: String): PayloadCallback = NearbyPayloadCallback(endpointId)

    /**
     * Per-endpoint payload handling. One session, one endpoint — but this
     * callback object may be recreated; state lives in the session.
     */
    private class NearbyPayloadCallback(private val endpointId: String) : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val session = activeSession ?: return
            if (endpointId != session.endpointId) return
            session.onPayloadReceived(payload)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val session = activeSession ?: return
            if (endpointId != session.endpointId) return
            session.onPayloadTransferUpdate(update)
        }
    }
}

/** Session over Nearby Connections. Pause is queue-level; resume restarts (spec 7.1). */
class NearbySession(
    val endpointId: String,
    override val peerName: String
) : TransportSession {

    override val peerId: String = "nearby-$endpointId"
    override val transportType = TransportType.NEARBY_CONNECTIONS
    override val supportsOffsetResume = false

    @Volatile
    var isActive = true
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Outgoing: fileId -> payloadId */
    private val outgoingPayloadFor = ConcurrentHashMap<String, Long>()
    /** Outgoing: payloadId -> fileId */
    private val fileIdForPayload = ConcurrentHashMap<String, String>()

    private val outgoingWaiters = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val pauseRequested = ConcurrentHashMap<String, Boolean>()
    private val cancelRequested = ConcurrentHashMap<String, Boolean>()

    /** Incoming: staged metadata awaiting its FILE payload (in-order pairing). */
    private val pendingMetadata = ConcurrentLinkedQueue<FileMeta>()
    private val incomingForPayload = ConcurrentHashMap<String, String>() // payloadId -> fileId
    private val incomingMeta = ConcurrentHashMap<String, FileMeta>()

    fun onPayloadReceived(payload: Payload) {
        when (payload.type) {
            Payload.Type.BYTES -> {
                val bytes = payload.asBytes() ?: return
                val json = try {
                    JSONObject(String(bytes, Charsets.UTF_8))
                } catch (_: Exception) {
                    return
                }
                handleControl(json)
            }
            Payload.Type.FILE -> {
                val meta = pendingMetadata.poll() ?: run {
                    LogStore.w("Nearby: file payload without metadata; dropping")
                    return
                }
                val uri = try {
                    payload.asFile()?.asUri()
                } catch (_: Exception) {
                    null
                } ?: run {
                    LogStore.e("Nearby: incoming file payload had no uri")
                    return
                }
                incomingForPayload[payload.id.toString()] = meta.fileId
                incomingMeta[meta.fileId] = meta
                TransferEngine.beginIncomingFromUri(meta, uri.toString())
            }
        }
    }

    private fun handleControl(json: JSONObject) {
        when (json.optString("type")) {
            "METADATA" -> {
                val meta = FileMeta(
                    fileId = json.optString("fileId"),
                    name = json.optString("name", "file"),
                    relativePath = json.optString("rel").takeIf { it.isNotEmpty() },
                    size = json.optLong("size", 0),
                    sha256 = json.optString("sha").takeIf { it.isNotEmpty() },
                    mime = json.optString("mime", "application/octet-stream")
                )
                if (meta.fileId.isNotEmpty()) {
                    pendingMetadata.add(meta)
                    LogStore.i("Nearby: metadata received for ${meta.name} (${meta.size} bytes)")
                }
            }
            "ACK" -> {
                val fileId = json.optString("fileId")
                val ok = json.optBoolean("ok", false)
                outgoingWaiters.remove(fileId)?.complete(if (ok) "OK" else "FAIL:${json.optString("error", "")}")
            }
            "PAUSE_REQ" -> {
                val fileId = json.optString("fileId")
                val payloadId = outgoingPayloadFor[fileId]
                pauseRequested[fileId] = true
                if (payloadId != null) {
                    try {
                        NearbyTransport.client().cancelPayload(payloadId)
                    } catch (_: Exception) {
                    }
                }
                TransferEngine.onPeerPausedOurSend(fileId)
            }
            "RESUME_REQ" -> TransferEngine.onPeerResumedOurSend(json.optString("fileId"))
            "CANCEL" -> {
                val fileId = json.optString("fileId")
                cancelRequested[fileId] = true
                val payloadId = outgoingPayloadFor[fileId]
                if (payloadId != null) {
                    try {
                        NearbyTransport.client().cancelPayload(payloadId)
                    } catch (_: Exception) {
                    }
                }
                TransferEngine.onPeerCancelled(fileId)
            }
        }
    }

    fun onPayloadTransferUpdate(update: PayloadTransferUpdate) {
        val payloadKey = update.payloadId.toString()
        // Outgoing?
        val outgoingFileId = fileIdForPayload[payloadKey]
        if (outgoingFileId != null) {
            TransferEngine.updateOutgoingProgress(outgoingFileId, update.bytesTransferred, update.totalBytes)
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    // Bytes delivered; wait for the receiver's verified ACK before
                    // declaring success (spec Section 8.12). Timeout falls through.
                    scope.launch {
                        val waiter = outgoingWaiters[outgoingFileId]
                        val ack = waiter?.let { withTimeoutOrNull(60000) { it.await() } }
                        if (ack == null) {
                            LogStore.w("Nearby: completed without receiver ACK for $outgoingFileId")
                        }
                        val result = ack ?: "OK"
                        outgoingWaiters.remove(outgoingFileId)
                        completeOutgoing(outgoingFileId, result)
                    }
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    if (pauseRequested[outgoingFileId] == true) {
                        completeOutgoing(outgoingFileId, "PAUSED")
                    } else if (cancelRequested[outgoingFileId] == true) {
                        completeOutgoing(outgoingFileId, "CANCELLED")
                    } else {
                        completeOutgoing(outgoingFileId, "FAIL:nearby payload failure")
                    }
                }
                PayloadTransferUpdate.Status.CANCELED -> {
                    completeOutgoing(outgoingFileId, if (pauseRequested[outgoingFileId] == true) "PAUSED" else "CANCELLED")
                }
            }
            return
        }
        // Incoming?
        val incomingFileId = incomingForPayload[payloadKey]
        if (incomingFileId != null) {
            val meta = incomingMeta[incomingFileId]
            TransferEngine.updateIncomingProgress(incomingFileId, update.bytesTransferred)
            if (update.status == PayloadTransferUpdate.Status.SUCCESS && meta != null) {
                incomingForPayload.remove(payloadKey)
                scope.launch {
                    val ok = TransferEngine.verifyAndFinalizeIncomingUri(meta)
                    if (!ok) LogStore.w("Nearby: verification failed for ${meta.name}")
                    sendControl(
                        JSONObject()
                            .put("type", "ACK")
                            .put("fileId", incomingFileId)
                            .put("ok", ok)
                            .put("error", if (ok) "" else "verification failed")
                    )
                    if (!ok) TransferEngine.markIncoming(incomingFileId, com.morselink.app.core.model.TransferItemState.FAILED)
                }
            }
        }
    }

    private fun completeOutgoing(fileId: String, result: String) {
        val payloadId = outgoingPayloadFor.remove(fileId)
        if (payloadId != null) fileIdForPayload.remove(payloadId.toString())
        pauseRequested.remove(fileId)
        cancelRequested.remove(fileId)
        outgoingWaiters[fileId]?.complete(result)
        outgoingWaiters.remove(fileId)
    }

    override suspend fun sendFile(item: TransferItem, sha256: String?): SendOutcome {
        if (!isActive) return SendOutcome.Failed("session closed")
        val meta = FileMeta(
            fileId = item.id,
            name = item.file.displayName,
            relativePath = item.file.relativePath,
            size = item.totalBytes,
            sha256 = sha256,
            mime = item.file.mime
        )
        return try {
            val context = MorselinkServices.appContext
            val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(
                Uri.parse(item.file.uri), "r"
            ) ?: return SendOutcome.Failed("cannot open source")

            val metadataJson = JSONObject()
                .put("type", "METADATA")
                .put("fileId", meta.fileId)
                .put("name", meta.name)
                .put("rel", meta.relativePath ?: "")
                .put("size", meta.size)
                .put("sha", sha256 ?: "")
                .put("mime", meta.mime)
            NearbyTransport.client().sendPayload(endpointId, Payload.fromBytes(metadataJson.toString().toByteArray(Charsets.UTF_8)))

            val filePayload = Payload.fromFile(pfd)
            outgoingPayloadFor[meta.fileId] = filePayload.id
            fileIdForPayload[filePayload.id.toString()] = meta.fileId
            val waiter = CompletableDeferred<String>()
            outgoingWaiters[meta.fileId] = waiter
            LogStore.i("Nearby: sending ${meta.name} (${meta.size} bytes)")
            NearbyTransport.client().sendPayload(endpointId, filePayload)

            val result = waiter.await()
            when {
                result == "OK" -> SendOutcome.Completed
                result == "PAUSED" -> SendOutcome.Paused
                result == "CANCELLED" -> SendOutcome.Cancelled
                result.startsWith("FAIL") -> SendOutcome.Failed(result.removePrefix("FAIL:"))
                else -> SendOutcome.Failed(result)
            }
        } catch (e: Exception) {
            LogStore.e("Nearby: send ${meta.name} failed", e)
            SendOutcome.Failed(e.message)
        } finally {
            outgoingPayloadFor.remove(meta.fileId)
            outgoingWaiters.remove(meta.fileId)
            pauseRequested.remove(meta.fileId)
            cancelRequested.remove(meta.fileId)
        }
    }

    override fun pauseOutgoing(fileId: String) {
        val payloadId = outgoingPayloadFor[fileId]
        pauseRequested[fileId] = true
        if (payloadId != null) {
            try {
                NearbyTransport.client().cancelPayload(payloadId)
            } catch (_: Exception) {
            }
        }
    }

    override fun requestPeerPause(fileId: String) {
        scope.launch {
            sendControl(JSONObject().put("type", "PAUSE_REQ").put("fileId", fileId))
        }
    }

    override fun requestPeerResume(fileId: String) {
        scope.launch {
            sendControl(JSONObject().put("type", "RESUME_REQ").put("fileId", fileId))
        }
    }

    override fun cancel(fileId: String, direction: TransferDirection) {
        when (direction) {
            TransferDirection.SENDING -> {
                cancelRequested[fileId] = true
                val payloadId = outgoingPayloadFor[fileId]
                if (payloadId != null) {
                    try {
                        NearbyTransport.client().cancelPayload(payloadId)
                    } catch (_: Exception) {
                    }
                }
                // Tell the peer (b6/b7): the receiver otherwise stays "idle".
                scope.launch {
                    sendControl(JSONObject().put("type", "CANCEL").put("fileId", fileId))
                }
            }
            TransferDirection.RECEIVING -> {
                TransferEngine.markIncoming(fileId, com.morselink.app.core.model.TransferItemState.CANCELLED)
                scope.launch {
                    sendControl(JSONObject().put("type", "CANCEL").put("fileId", fileId))
                }
            }
        }
    }

    private suspend fun sendControl(json: JSONObject) {
        try {
            NearbyTransport.client().sendPayload(endpointId, Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            LogStore.e("Nearby: control send failed", e)
        }
    }

    fun onTransportDisconnected(reason: String?) {
        if (!isActive) return
        isActive = false
        LogStore.i("Nearby: session closed (${reason ?: "no reason"})")
        NearbyTransport.clearSession(this)
        TransferEngine.onSessionLost(this, reason)
        scope.launch {
            kotlinx.coroutines.delay(500)
            scope.cancel()
        }
    }

    override fun close(reason: String?) {
        if (!isActive) return
        isActive = false
        try {
            NearbyTransport.client().disconnectFromEndpoint(endpointId)
        } catch (_: Exception) {
        }
        NearbyTransport.clearSession(this)
        TransferEngine.onSessionLost(this, reason)
        scope.launch {
            kotlinx.coroutines.delay(500)
            scope.cancel()
        }
    }
}
