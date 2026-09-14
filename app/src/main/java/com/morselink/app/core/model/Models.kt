package com.morselink.app.core.model

/** Physical transports available in this build. */
enum class TransportType {
    NEARBY_CONNECTIONS,
    LAN_WIFI,
    WEBSHARE_HTTP
}

/** A device found during discovery, regardless of transport. */
data class DiscoveredPeer(
    val deviceId: String,
    val name: String,
    val transport: TransportType,
    val endpointId: String? = null,
    val host: String? = null,
    val port: Int = 0
)

/** A file selected for sending. `uri` is a content:// or file:// Uri string. */
data class TransferableFile(
    val id: String,
    val displayName: String,
    val size: Long,
    val uri: String,
    val mime: String,
    val relativePath: String? = null,
    val sourceKind: String = "file"
)

enum class TransferItemState { QUEUED, IN_PROGRESS, PAUSED, COMPLETED, FAILED, SKIPPED, CANCELLED }

enum class TransferDirection { SENDING, RECEIVING }

/**
 * Canonical queue item. The TransferEngine's item list is the single source of
 * truth consumed by the transfer screen, the queue sheet, the persistent
 * notification and the batch summary.
 */
data class TransferItem(
    val id: String,
    val file: TransferableFile,
    val direction: TransferDirection,
    var state: TransferItemState = TransferItemState.QUEUED,
    var bytesTransferred: Long = 0,
    val totalBytes: Long = 0,
    var retryCount: Int = 0,
    var lastError: String? = null,
    var resumeOffset: Long = 0,
    var speedBps: Long = 0,
    var finalUri: String? = null,
    var finalPath: String? = null,
    var batchId: String = ""
) {
    val isTerminal: Boolean
        get() = state == TransferItemState.COMPLETED ||
            state == TransferItemState.FAILED ||
            state == TransferItemState.SKIPPED ||
            state == TransferItemState.CANCELLED
}

/** Session/connection state exposed to the UI as a flow (never a plain field). */
data class SessionState(
    val active: Boolean = false,
    val everActive: Boolean = false,
    val peerId: String? = null,
    val peerName: String? = null,
    val transport: TransportType? = null
)

/** Recent/trusted device stored locally for quick reconnect. */
data class RecentDevice(
    val deviceId: String,
    val name: String,
    val transport: TransportType,
    val host: String? = null,
    val port: Int = 0,
    val lastSeen: Long
)

enum class ConflictPolicy(val key: String, val label: String) {
    ALWAYS_ASK("ask", "Always ask"),
    ALWAYS_OVERWRITE("overwrite", "Always overwrite"),
    ALWAYS_KEEP_BOTH("keep_both", "Always keep both"),
    ALWAYS_SKIP("skip", "Always skip");

    companion object {
        fun fromKey(key: String?): ConflictPolicy {
            for (p in values()) {
                if (p.key == key) return p
            }
            return ALWAYS_ASK
        }
    }
}

/** Batch summary derived from the same queue used for live progress. */
data class BatchSummary(
    val batchId: String,
    val peerName: String?,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
    val cancelled: Int,
    val totalBytes: Long,
    val elapsedMs: Long
)

/** One-shot events surfaced to the UI layer. */
sealed class EngineEvent {
    data class ConsentRequested(
        val requestId: String,
        val peerName: String,
        val transport: TransportType
    ) : EngineEvent()

    data class BatchCompleted(val summary: BatchSummary) : EngineEvent()
    data class PeerConnected(val name: String, val transport: TransportType) : EngineEvent()
    data class PeerDisconnected(val name: String?, val reason: String?) : EngineEvent()
    data class ProgressAnnouncement(val text: String) : EngineEvent()
    data class TransferDoneAnnouncement(val text: String) : EngineEvent()
    /** Conflict decision needed on the receiving side; blocks the item until resolved. */
    data class ConflictDecisionNeeded(
        val requestId: String,
        val fileName: String,
        val relativePath: String?
    ) : EngineEvent()
    data class ItemFinishedToast(
        val name: String,
        val state: TransferItemState,
        val direction: TransferDirection
    ) : EngineEvent()
    data class InfoToast(val text: String) : EngineEvent()
}
