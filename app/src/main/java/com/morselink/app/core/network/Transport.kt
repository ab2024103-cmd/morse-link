package com.morselink.app.core.network

import com.morselink.app.core.model.DiscoveredPeer
import com.morselink.app.core.model.TransferDirection
import com.morselink.app.core.model.TransferItem
import com.morselink.app.core.model.TransportType

/** Outcome of one outgoing file send, reported by a transport session. */
sealed class SendOutcome {
    object Completed : SendOutcome()
    object SkippedAlreadyPresent : SendOutcome()
    object Paused : SendOutcome()
    object Cancelled : SendOutcome()
    data class Failed(val error: String?) : SendOutcome()
}

/** Minimal session abstraction both transports implement. */
interface TransportSession {
    val peerId: String
    val peerName: String
    val transportType: TransportType
    val supportsOffsetResume: Boolean

    /**
     * Sends one file. Suspends until a terminal outcome for this file.
     * The engine serializes calls per session.
     */
    suspend fun sendFile(item: TransferItem, sha256: String?): SendOutcome

    /** Pause the file we are currently sending (exact-offset where supported). */
    fun pauseOutgoing(fileId: String)

    /** Ask the peer to pause what it is sending to us. */
    fun requestPeerPause(fileId: String)

    /** Ask the peer to resume a file it paused sending to us. */
    fun requestPeerResume(fileId: String)

    /** Cancel this file in whichever direction it is active. */
    fun cancel(fileId: String, direction: TransferDirection)

    fun close(reason: String?)
}

/** Provider abstraction (spec Section 7.1). */
interface TransportProvider {
    val id: TransportType
    fun isAvailable(): Boolean
    fun startDiscovery()
    fun stopDiscovery()
    suspend fun connect(peer: DiscoveredPeer): TransportSession?
}

/** Metadata announced over the wire for every file. */
data class FileMeta(
    val fileId: String,
    val name: String,
    val relativePath: String?,
    val size: Long,
    val sha256: String?,
    val mime: String
)
