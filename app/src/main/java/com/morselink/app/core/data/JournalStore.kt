package com.morselink.app.core.data

import android.content.Context
import com.morselink.app.core.model.TransferDirection
import com.morselink.app.core.model.TransferItem
import com.morselink.app.core.model.TransferItemState
import com.morselink.app.core.model.TransportType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Transfer journal (spec Section 8.10): the queue snapshot is persisted on
 * every state transition so an interrupted batch can be offered for resume
 * after process death. Never silently resumed or silently discarded.
 */
class JournalStore(context: Context) {

    private val file = File(context.filesDir, "journal.json")

    fun snapshotItems(): List<TransferItem> = read().mapNotNull { it.toItem() }

    fun save(items: List<TransferItem>, peerName: String?, transport: TransportType?) {
        try {
            val arr = JSONArray()
            for (item in items) {
                val o = JSONObject()
                o.put("id", item.id)
                o.put("name", item.file.displayName)
                o.put("uri", item.file.uri)
                o.put("mime", item.file.mime)
                o.put("size", item.totalBytes)
                o.put("rel", item.file.relativePath ?: "")
                o.put("direction", item.direction.name)
                o.put("state", item.state.name)
                o.put("offset", item.bytesTransferred)
                o.put("batchId", item.batchId)
                arr.put(o)
            }
            val root = JSONObject()
            root.put("peer", peerName ?: "")
            root.put("transport", transport?.name ?: "")
            root.put("items", arr)
            file.writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    fun hasUnfinished(): Boolean {
        return read().any { !it.toItem().isTerminal }
    }

    fun peerName(): String? {
        return try {
            val root = JSONObject(file.readText())
            val name = root.optString("peer", "")
            if (name.isNotEmpty()) name else null
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    private fun read(): List<JournalRow> {
        return try {
            if (!file.exists()) return emptyList()
            val root = JSONObject(file.readText())
            val arr = root.getJSONArray("items")
            val out = ArrayList<JournalRow>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    JournalRow(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        uri = o.getString("uri"),
                        mime = o.optString("mime", "application/octet-stream"),
                        size = o.optLong("size", 0),
                        relativePath = o.optString("rel", ""),
                        direction = o.getString("direction"),
                        state = o.getString("state"),
                        offset = o.optLong("offset", 0),
                        batchId = o.optString("batchId", "")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class JournalRow(
        val id: String,
        val name: String,
        val uri: String,
        val mime: String,
        val size: Long,
        val relativePath: String,
        val direction: String,
        val state: String,
        val offset: Long,
        val batchId: String
    ) {
        fun toItem(): TransferItem {
            return TransferItem(
                id = id,
                file = com.morselink.app.core.model.TransferableFile(
                    id = id,
                    displayName = name,
                    size = size,
                    uri = uri,
                    mime = mime,
                    relativePath = if (relativePath.isEmpty()) null else relativePath
                ),
                direction = try {
                    TransferDirection.valueOf(direction)
                } catch (_: Exception) {
                    TransferDirection.SENDING
                },
                state = try {
                    TransferItemState.valueOf(state)
                } catch (_: Exception) {
                    TransferItemState.FAILED
                },
                bytesTransferred = offset,
                totalBytes = size,
                batchId = batchId
            )
        }
    }
}
