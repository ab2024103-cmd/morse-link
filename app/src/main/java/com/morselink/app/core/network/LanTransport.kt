package com.morselink.app.core.network

import com.morselink.app.core.logging.LogStore
import com.morselink.app.core.model.DiscoveredPeer
import com.morselink.app.core.model.TransferDirection
import com.morselink.app.core.model.TransferItem
import com.morselink.app.core.model.TransferItemState
import com.morselink.app.core.model.TransportType
import com.morselink.app.core.transfer.TransferEngine
import com.morselink.app.core.util.Integrity
import com.morselink.app.core.util.MorselinkServices
import com.morselink.app.core.storage.ZipUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LAN/Wi-Fi transport: UDP broadcast discovery + a line-JSON control channel
 * guarded by a single mutex, and per-file TCP data connections carrying a
 * CRC32 chunk protocol with exact-offset pause/resume (spec Section 7.4).
 *
 * Works whenever both devices share a subnet: the same router Wi-Fi, or one
 * phone hosting a hotspot the other joined (the WebShare topology).
 */
object LanTransport {

    const val TCP_PORT = 33456
    const val UDP_PORT = 33457
    private const val CHUNK_SIZE = 256 * 1024
    private const val DISCOVERY_PERIOD_MS = 1200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var announceJob: Job? = null

    @Volatile
    private var announcing = false

    @Volatile
    var activeSession: LanSession? = null
        private set

    val isServerRunning: Boolean
        get() = serverSocket?.isClosed == false

    fun start() {
        if (serverSocket != null && serverSocket?.isClosed == false) return
        scope.launch {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(TCP_PORT))
                serverSocket = server
                LogStore.i("LAN: server listening on $TCP_PORT")
                while (isActive) {
                    val socket = try {
                        server.accept()
                    } catch (e: Exception) {
                        if (server.isClosed) break
                        delay(200)
                        continue
                    }
                    scope.launch { handleConnection(socket) }
                }
            } catch (e: Exception) {
                LogStore.e("LAN: failed to start server", e)
            }
        }
        scope.launch { udpListenLoop() }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        stopDiscovery()
        activeSession?.close("transport stopped")
    }

    // ---------------- discovery ----------------

    fun startDiscovery() {
        start()
        if (announcing) return
        announcing = true
        TransferEngine.beginDiscovery(TransportType.LAN_WIFI)
        announceJob = scope.launch {
            while (announcing && isActive) {
                announceOnce()
                delay(DISCOVERY_PERIOD_MS)
            }
        }
    }

    fun stopDiscovery() {
        if (!announcing) return
        announcing = false
        announceJob?.cancel()
        announceJob = null
        TransferEngine.endDiscovery(TransportType.LAN_WIFI)
    }

    private fun announceOnce() {
        try {
            val msg = JSONObject()
                .put("app", "morselink")
                .put("id", TransferEngine.localDeviceId())
                .put("name", TransferEngine.localDeviceName())
                .put("port", TCP_PORT)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val socket = DatagramSocket()
            socket.broadcast = true
            try {
                val targets = HashSet<String>()
                targets.add("255.255.255.255")
                for (addr in localAddresses()) {
                    targets.add(subnetBroadcast(addr))
                }
                for (target in targets) {
                    try {
                        val ia = InetAddress.getByName(target)
                        socket.send(DatagramPacket(msg, msg.size, ia, UDP_PORT))
                    } catch (_: Exception) {
                    }
                }
            } finally {
                socket.close()
            }
        } catch (_: Exception) {
        }
    }

    private fun udpListenLoop() {
        try {
            val socket = DatagramSocket(UDP_PORT)
            socket.broadcast = true
            val buf = ByteArray(2048)
            while (true) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val json = JSONObject(text)
                    if (json.optString("app") != "morselink") continue
                    val id = json.optString("id")
                    if (id.isEmpty() || id == TransferEngine.localDeviceId()) continue
                    val peer = DiscoveredPeer(
                        deviceId = id,
                        name = json.optString("name", "Android"),
                        transport = TransportType.LAN_WIFI,
                        host = packet.address.hostAddress,
                        port = json.optInt("port", TCP_PORT)
                    )
                    TransferEngine.addPeer(peer)
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            LogStore.w("LAN: udp listen stopped: ${e.message}")
        }
    }

    private fun localAddresses(): List<String> {
        val out = ArrayList<String>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in Collections.list(ni.inetAddresses)) {
                    if (!ia.isLoopbackAddress && ia is java.net.Inet4Address) {
                        out.add(ia.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun subnetBroadcast(address: String): String {
        val parts = address.split(".")
        if (parts.size != 4) return address
        return "${parts[0]}.${parts[1]}.${parts[2]}.255"
    }

    /** The address another device on this network can likely reach us on. */
    fun preferredAddress(): String? {
        val addrs = localAddresses()
        for (a in addrs) {
            if (a.startsWith("192.168.") || a.startsWith("10.") || a.startsWith("172.")) return a
        }
        return addrs.firstOrNull()
    }

    fun isOnNetwork(): Boolean = preferredAddress() != null

    // ---------------- connections ----------------

    private suspend fun handleConnection(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val firstLine = reader.readLine() ?: run { socket.close(); return }
            socket.soTimeout = 0
            val json = JSONObject(firstLine)
            when (json.optString("type")) {
                "HELLO" -> handleHello(socket, reader, json)
                "DATA" -> {
                    val fileId = json.optString("fileId")
                    val session = activeSession
                    if (session == null || !session.isActive) {
                        try {
                            socket.close()
                        } catch (_: Exception) {
                        }
                    } else {
                        session.handleDataConnection(socket, fileId)
                    }
                }
                else -> socket.close()
            }
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun handleHello(socket: Socket, reader: BufferedReader, json: JSONObject) {
        val peerId = json.optString("id")
        val peerName = json.optString("name", "Android")
        val peerPort = json.optInt("port", TCP_PORT)
        if (peerId.isEmpty()) {
            writeLine(socket, """{"type":"REJECT","reason":"bad hello"}""")
            socket.close()
            return
        }
        val existing = activeSession
        if (existing != null && existing.isActive && existing.peerId != peerId) {
            writeLine(socket, """{"type":"BUSY"}""")
            socket.close()
            return
        }
        if (existing != null && existing.isActive && existing.peerId == peerId) {
            existing.close("reconnect from same peer")
        }
        val accepted = TransferEngine.awaitConsent(peerName, TransportType.LAN_WIFI)
        if (!accepted) {
            writeLine(socket, """{"type":"REJECT"}""")
            socket.close()
            LogStore.i("LAN: connection from $peerName rejected by user")
            return
        }
        val ack = JSONObject()
            .put("type", "HELLO_ACK")
            .put("id", TransferEngine.localDeviceId())
            .put("name", TransferEngine.localDeviceName())
            .put("port", TCP_PORT)
        writeLine(socket, ack.toString())
        val session = LanSession(
            socket, reader, peerId, peerName,
            socket.inetAddress?.hostAddress ?: "", peerPort
        )
        activeSession = session
        session.startLoops()
        TransferEngine.onSessionEstablished(session)
        LogStore.i("LAN: session established with $peerName (${socket.inetAddress?.hostAddress})")
    }

    private fun writeLine(socket: Socket, line: String) {
        try {
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            writer.write(line)
            writer.write("\n")
            writer.flush()
        } catch (_: Exception) {
        }
    }

    fun clearSession(session: LanSession) {
        if (activeSession === session) {
            activeSession = null
        }
    }

    /** Manual/direct connect to [host]:[port]. */
    suspend fun connectDirect(host: String, port: Int): LanSession? {
        start()
        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 8000)
                socket.soTimeout = 60000
                val hello = JSONObject()
                    .put("type", "HELLO")
                    .put("id", TransferEngine.localDeviceId())
                    .put("name", TransferEngine.localDeviceName())
                    .put("port", TCP_PORT)
                val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                writer.write(hello.toString())
                writer.write("\n")
                writer.flush()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val replyLine = reader.readLine()
                socket.soTimeout = 0
                if (replyLine == null) {
                    socket.close()
                    return@withContext null
                }
                val reply = JSONObject(replyLine)
                when (reply.optString("type")) {
                    "HELLO_ACK" -> {
                        val session = LanSession(
                            socket, reader, reply.optString("id"),
                            reply.optString("name", "Android"), host, port
                        )
                        activeSession = session
                        session.startLoops()
                        TransferEngine.onSessionEstablished(session)
                        LogStore.i("LAN: connected directly to ${session.peerName} at $host")
                        session
                    }
                    "BUSY" -> {
                        socket.close()
                        TransferEngine.notifyBusy()
                        null
                    }
                    else -> {
                        socket.close()
                        LogStore.i("LAN: peer rejected manual connection")
                        null
                    }
                }
            } catch (e: Exception) {
                LogStore.e("LAN: direct connect to $host failed", e)
                null
            }
        }
    }
}

/**
 * One paired LAN session. The control socket is shared between the background
 * reader loop and on-demand sends; every read/write goes through one mutex
 * (spec Section 7.4 — a boolean busy flag is not sufficient). A logical
 * request+reply pair holds the lock across both operations as one atomic unit.
 */
class LanSession(
    private val controlSocket: Socket,
    private val controlReader: BufferedReader,
    override val peerId: String,
    override val peerName: String,
    val peerHost: String,
    private val peerTcpPort: Int
) : TransportSession {

    override val transportType = TransportType.LAN_WIFI
    override val supportsOffsetResume = true

    private val channelMutex = Mutex()
    private val controlWriter: Writer = OutputStreamWriter(controlSocket.getOutputStream(), Charsets.UTF_8)

    @Volatile
    var isActive = true
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val doneWaiters = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    /** Control events that arrived while a request/reply held the mutex. */
    private val pendingEvents = ConcurrentLinkedQueue<JSONObject>()

    /** Files we are receiving, keyed by fileId. */
    private val incoming = ConcurrentHashMap<String, IncomingContext>()

    private val pauseOutgoingFlags = ConcurrentHashMap<String, Boolean>()
    private val cancelOutgoingFlags = ConcurrentHashMap<String, Boolean>()

    private class IncomingContext(val meta: FileMeta) {
        var partFile: File? = null
        val cancelled = AtomicBoolean(false)
    }

    fun startLoops() {
        scope.launch { controlReadLoop() }
    }

    private suspend fun controlReadLoop() {
        while (isActive) {
            // Drain stashed events first so nothing is lost (spec 7.4/9.5 spirit).
            while (true) {
                val next = pendingEvents.poll() ?: break
                dispatchControl(next)
            }
            val line = readIncomingLine()
            if (line == null) {
                delay(120)
                continue
            }
            try {
                dispatchControl(JSONObject(line))
            } catch (e: Exception) {
                LogStore.e("LAN: control dispatch failed", e)
            }
        }
    }

    private suspend fun dispatchControl(json: JSONObject) {
        when (json.optString("type")) {
            "OFFER" -> scope.launch { handleOffer(json) }
            "PAUSE_OUTGOING" -> {
                val fileId = json.optString("fileId")
                pauseOutgoingFlags[fileId] = true
                TransferEngine.onPeerPausedOurSend(fileId)
            }
            "RESUME_INCOMING" -> {
                TransferEngine.onPeerResumedOurSend(json.optString("fileId"))
            }
            "CANCEL" -> TransferEngine.onPeerCancelled(json.optString("fileId"))
            "DONE" -> {
                val fileId = json.optString("fileId")
                doneWaiters.remove(fileId)?.complete(json)
            }
            "BYE" -> close("peer said goodbye")
        }
    }

    /** Reads one pending control line, or null if none buffered (spec 7.4). */
    private suspend fun readIncomingLine(): String? = channelMutex.withLock {
        try {
            if (controlReader.ready()) controlReader.readLine() else null
        } catch (e: Exception) {
            if (isActive) close("control read failure: ${e.message}")
            null
        }
    }

    private suspend fun sendLine(json: JSONObject) = channelMutex.withLock {
        try {
            controlWriter.write(json.toString())
            controlWriter.write("\n")
            controlWriter.flush()
        } catch (e: Exception) {
            if (isActive) close("control write failure: ${e.message}")
        }
    }

    /**
     * Sends a request and awaits its direct reply as one atomic unit — the
     * mutex is held across both. Events that arrive meanwhile are stashed and
     * re-processed by the reader loop, never dropped.
     */
    private suspend fun sendAndAwaitReply(
        request: JSONObject,
        replyTypes: Set<String>,
        timeoutMs: Long = 20000
    ): JSONObject? = channelMutex.withLock {
        try {
            controlWriter.write(request.toString())
            controlWriter.write("\n")
            controlWriter.flush()
        } catch (e: Exception) {
            if (isActive) close("control write failure: ${e.message}")
            return@withLock null
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var reply: JSONObject? = null
        while (System.currentTimeMillis() < deadline && isActive) {
            val line = try {
                if (controlReader.ready()) controlReader.readLine() else null
            } catch (e: Exception) {
                if (isActive) close("control read failure: ${e.message}")
                null
            }
            if (line == null) {
                delay(15)
                continue
            }
            val json = try {
                JSONObject(line)
            } catch (_: Exception) {
                continue
            }
            if (json.optString("type") in replyTypes) {
                reply = json
                break
            } else {
                pendingEvents.add(json)
            }
        }
        reply
    }

    // ---------------- incoming offer ----------------

    private suspend fun handleOffer(json: JSONObject) {
        val meta = FileMeta(
            fileId = json.optString("fileId"),
            name = json.optString("name", "file"),
            relativePath = json.optString("rel").takeIf { it.isNotEmpty() },
            size = json.optLong("size", 0),
            sha256 = json.optString("sha").takeIf { it.isNotEmpty() },
            mime = json.optString("mime", "application/octet-stream")
        )
        if (meta.fileId.isEmpty()) return
        val ctx = IncomingContext(meta)
        incoming[meta.fileId] = ctx
        val decision = TransferEngine.handleIncomingOffer(meta)
        when (decision) {
            is TransferEngine.OfferDecision.Accept -> {
                ctx.partFile = decision.partFile
                sendLine(
                    JSONObject()
                        .put("type", "ACCEPT")
                        .put("fileId", meta.fileId)
                        .put("offset", decision.offset)
                )
                LogStore.i("LAN: accepted ${meta.name} from offset ${decision.offset}")
            }
            is TransferEngine.OfferDecision.Skip -> {
                incoming.remove(meta.fileId)
                sendLine(JSONObject().put("type", "SKIP").put("fileId", meta.fileId))
                LogStore.i("LAN: skipped ${meta.name} — ${decision.reason}")
            }
        }
    }

    /** Handles an accepted DATA connection carrying chunk frames. */
    fun handleDataConnection(socket: Socket, fileId: String) {
        val ctx = incoming[fileId] ?: run {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            return
        }
        scope.launch {
            var partFile: File? = ctx.partFile
            var part: FileOutputStream? = null
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 25000
                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), CHUNK_SIZE + 64))
                if (partFile != null && partFile.exists()) {
                    part = FileOutputStream(partFile, true)
                } else {
                    partFile = TransferEngine.partFileFor(ctx.meta)
                    ctx.partFile = partFile
                    part = FileOutputStream(partFile, true)
                }
                val magic = ByteArray(4)
                while (isActive && !ctx.cancelled.get()) {
                    try {
                        input.readFully(magic)
                        if (magic[0] != 'M'.code.toByte() || magic[1] != 'L'.code.toByte() ||
                            magic[2] != 'N'.code.toByte() || magic[3] != 'K'.code.toByte()
                        ) {
                            throw IOException("chunk magic mismatch — framing slipped")
                        }
                        val seq = input.readInt()
                        val len = input.readInt()
                        if (len < 0 || len > CHUNK_SIZE * 4) throw IOException("bad chunk length $len")
                        val payload = ByteArray(len)
                        input.readFully(payload)
                        val crc = input.readInt().toLong() and 0xFFFFFFFFL
                        val computed = Integrity.crc32(payload, len)
                        if (computed != crc) {
                            // A CRC mismatch fails the file — it is never "fixed" by
                            // appending (spec Section 7.4).
                            throw IOException("chunk crc mismatch seq=$seq")
                        }
                        part!!.write(payload, 0, len)
                        part!!.flush()
                        val realSize = partFile!!.length() // real on-disk state (spec Section 15)
                        TransferEngine.updateIncomingProgress(fileId, realSize)
                        if (realSize >= ctx.meta.size) break
                    } catch (e: SocketTimeoutException) {
                        if (ctx.cancelled.get() || !isActive) break
                        // Idle is not closed (spec 7.4): loop and keep waiting.
                        continue
                    }
                }
                part?.close()
                part = null
                val finalSize = partFile?.length() ?: 0
                if (ctx.cancelled.get()) {
                    partFile?.delete()
                    TransferEngine.markIncoming(fileId, TransferItemState.CANCELLED)
                } else if (finalSize == ctx.meta.size) {
                    val ok = TransferEngine.verifyAndFinalizeIncoming(ctx.meta, partFile!!)
                    val done = JSONObject()
                        .put("type", "DONE")
                        .put("fileId", fileId)
                        .put("result", if (ok) "OK" else "FAIL")
                    if (!ok) done.put("error", "verification failed")
                    sendLine(done)
                    if (!ok) partFile.delete()
                } else {
                    // Incomplete without cancel: keep .part for resume, mark paused.
                    sendLine(JSONObject().put("type", "DONE").put("fileId", fileId).put("result", "INTERRUPTED"))
                    TransferEngine.markIncoming(fileId, TransferItemState.PAUSED)
                }
            } catch (e: Exception) {
                LogStore.e("LAN: data connection for $fileId failed", e)
                val pf = ctx.partFile
                if (ctx.cancelled.get() && pf != null) pf.delete()
                TransferEngine.markIncoming(fileId, TransferItemState.PAUSED)
            } finally {
                incoming.remove(fileId)
                try {
                    part?.close()
                } catch (_: Exception) {
                }
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---------------- sending ----------------

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
        LogStore.i("LAN: offering ${meta.name} (${meta.size} bytes) to $peerName")
        val offer = JSONObject()
            .put("type", "OFFER")
            .put("fileId", meta.fileId)
            .put("name", meta.name)
            .put("rel", meta.relativePath ?: "")
            .put("size", meta.size)
            .put("sha", sha256 ?: "")
            .put("mime", meta.mime)
        val reply = sendAndAwaitReply(offer, setOf("ACCEPT", "SKIP"))
            ?: return SendOutcome.Failed("no reply to offer")
        return when (reply.optString("type")) {
            "SKIP" -> SendOutcome.SkippedAlreadyPresent
            "ACCEPT" -> streamFile(item, meta, reply.optLong("offset", 0))
            else -> SendOutcome.Failed("unexpected reply ${reply.optString("type")}")
        }
    }

    private suspend fun streamFile(item: TransferItem, meta: FileMeta, startOffset: Long): SendOutcome {
        var dataSocket: Socket? = null
        // Register the DONE waiter BEFORE streaming so a fast confirmation is
        // never missed.
        val waiter = CompletableDeferred<JSONObject>()
        doneWaiters[meta.fileId] = waiter
        try {
            if (startOffset > 0) {
                LogStore.i("LAN: resuming ${meta.name} at offset $startOffset")
            }
            dataSocket = Socket()
            dataSocket.tcpNoDelay = true
            dataSocket.connect(InetSocketAddress(peerHost, peerTcpPort), 8000)
            val out = DataOutputStream(BufferedOutputStream(dataSocket.getOutputStream(), CHUNK_SIZE + 64))
            val header = JSONObject().put("type", "DATA").put("fileId", meta.fileId).toString() + "\n"
            out.write(header.toByteArray(Charsets.UTF_8))

            val input = ZipUtil.openStream(MorselinkServices.appContext, item.file.uri)
                ?: return SendOutcome.Failed("cannot open source")
            input.use { stream ->
                var toSkip = startOffset
                while (toSkip > 0) {
                    val skipped = stream.skip(toSkip)
                    if (skipped <= 0) break
                    toSkip -= skipped
                }
                var sent = startOffset
                var seq = (startOffset / CHUNK_SIZE).toInt()
                val buf = ByteArray(CHUNK_SIZE)
                TransferEngine.updateOutgoingProgress(item.id, sent, meta.size)
                while (sent < meta.size) {
                    if (cancelOutgoingFlags[meta.fileId] == true) {
                        out.flush()
                        return SendOutcome.Cancelled
                    }
                    if (pauseOutgoingFlags[meta.fileId] == true) {
                        // Clean pause: flush, tell the peer, close the data channel.
                        out.flush()
                        sendLine(JSONObject().put("type", "PAUSE_DATA").put("fileId", meta.fileId))
                        LogStore.i("LAN: paused ${meta.name} at offset $sent (clean close)")
                        return SendOutcome.Paused
                    }
                    val n = stream.read(buf)
                    if (n < 0) throw IOException("source ended early")
                    out.write(intToBytes(0x4D4C4E4B)) // "MLNK"
                    out.writeInt(seq)
                    out.writeInt(n)
                    out.write(buf, 0, n)
                    out.writeInt(Integrity.crc32(buf, n).toInt())
                    out.flush() // progress counted only after flush (spec 7.4)
                    sent += n
                    seq++
                    TransferEngine.updateOutgoingProgress(item.id, sent, meta.size)
                }
                out.flush()
            }
            val done = withTimeoutOrNull(60000) { waiter.await() }
            return when (done?.optString("result")) {
                "OK" -> SendOutcome.Completed
                null -> SendOutcome.Failed("peer did not confirm completion")
                else -> SendOutcome.Failed("peer reported ${done?.optString("result")} ${done?.optString("error", "")}")
            }
        } catch (e: Exception) {
            LogStore.e("LAN: send ${meta.name} failed", e)
            return SendOutcome.Failed(e.message)
        } finally {
            doneWaiters.remove(meta.fileId)
            pauseOutgoingFlags.remove(meta.fileId)
            cancelOutgoingFlags.remove(meta.fileId)
            try {
                dataSocket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun intToBytes(v: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = ((v ushr 24) and 0xFF).toByte()
        b[1] = ((v ushr 16) and 0xFF).toByte()
        b[2] = ((v ushr 8) and 0xFF).toByte()
        b[3] = (v and 0xFF).toByte()
        return b
    }

    // ---------------- controls ----------------

    override fun pauseOutgoing(fileId: String) {
        pauseOutgoingFlags[fileId] = true
    }

    override fun requestPeerPause(fileId: String) {
        scope.launch {
            sendLine(JSONObject().put("type", "PAUSE_OUTGOING").put("fileId", fileId))
        }
    }

    override fun requestPeerResume(fileId: String) {
        scope.launch {
            sendLine(JSONObject().put("type", "RESUME_INCOMING").put("fileId", fileId))
        }
    }

    override fun cancel(fileId: String, direction: TransferDirection) {
        when (direction) {
            TransferDirection.SENDING -> cancelOutgoingFlags[fileId] = true
            TransferDirection.RECEIVING -> {
                val ctx = incoming[fileId]
                ctx?.cancelled?.set(true)
                scope.launch {
                    sendLine(JSONObject().put("type", "CANCEL").put("fileId", fileId))
                }
            }
        }
    }

    fun close(reason: String?) {
        if (!isActive) return
        isActive = false
        LogStore.i("LAN: session closed (${reason ?: "no reason"})")
        try {
            controlWriter.write("""{"type":"BYE"}""")
            controlWriter.write("\n")
            controlWriter.flush()
        } catch (_: Exception) {
        }
        try {
            controlSocket.close()
        } catch (_: Exception) {
        }
        LanTransport.clearSession(this)
        TransferEngine.onSessionLost(this, reason)
        scope.launch {
            delay(1500)
            scope.cancel()
        }
    }
}
