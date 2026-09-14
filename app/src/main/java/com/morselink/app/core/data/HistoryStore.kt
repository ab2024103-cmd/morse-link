package com.morselink.app.core.data

import android.content.Context
import com.morselink.app.core.model.TransferDirection
import com.morselink.app.core.model.TransferItemState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val id: Long,
    val timestamp: Long,
    val direction: TransferDirection,
    val fileName: String,
    val mime: String,
    val size: Long,
    val peerName: String,
    val status: TransferItemState,
    /** Uri (content:// or file://) of the local file, when it exists. */
    val localUri: String?,
    /** Original source uri, for re-sending from history. */
    val sourceUri: String?,
    val relativePath: String? = null
)

/** Local transfer history persisted as a JSON file (no cloud, ever). */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, "history.json")
    private val lock = Any()
    private val entries = ArrayList<HistoryEntry>()
    private var nextId = 1L
    private val listeners = ArrayList<(Unit) -> Unit>()

    init {
        load()
    }

    private fun load() {
        synchronized(lock) {
            entries.clear()
            try {
                if (file.exists()) {
                    val arr = JSONArray(file.readText())
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        entries.add(
                            HistoryEntry(
                                id = o.getLong("id"),
                                timestamp = o.getLong("ts"),
                                direction = TransferDirection.valueOf(o.getString("dir")),
                                fileName = o.getString("name"),
                                mime = o.optString("mime", "application/octet-stream"),
                                size = o.getLong("size"),
                                peerName = o.optString("peer", "Unknown"),
                                status = TransferItemState.valueOf(o.getString("status")),
                                localUri = if (o.has("localUri")) o.getString("localUri") else null,
                                sourceUri = if (o.has("sourceUri")) o.getString("sourceUri") else null,
                                relativePath = if (o.has("rel")) o.getString("rel") else null
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                entries.clear()
            }
            nextId = (entries.maxOfOrNull { it.id } ?: 0L) + 1L
        }
    }

    fun add(
        direction: TransferDirection,
        fileName: String,
        mime: String,
        size: Long,
        peerName: String,
        status: TransferItemState,
        localUri: String?,
        sourceUri: String?,
        relativePath: String?
    ): HistoryEntry {
        val entry = HistoryEntry(
            id = nextId++,
            timestamp = System.currentTimeMillis(),
            direction = direction,
            fileName = fileName,
            mime = mime,
            size = size,
            peerName = peerName,
            status = status,
            localUri = localUri,
            sourceUri = sourceUri,
            relativePath = relativePath
        )
        synchronized(lock) {
            entries.add(0, entry)
            while (entries.size > 2000) entries.removeAt(entries.size - 1)
            persist()
        }
        notifyChanged()
        return entry
    }

    fun all(): List<HistoryEntry> = synchronized(lock) { ArrayList(entries) }

    fun deleteRecord(id: Long) {
        synchronized(lock) {
            entries.removeAll { it.id == id }
            persist()
        }
        notifyChanged()
    }

    fun clearAll() {
        synchronized(lock) {
            entries.clear()
            persist()
        }
        notifyChanged()
    }

    fun byId(id: Long): HistoryEntry? = synchronized(lock) { entries.firstOrNull { it.id == id } }

    private fun persist() {
        try {
            val arr = JSONArray()
            for (e in entries) {
                val o = JSONObject()
                o.put("id", e.id)
                o.put("ts", e.timestamp)
                o.put("dir", e.direction.name)
                o.put("name", e.fileName)
                o.put("mime", e.mime)
                o.put("size", e.size)
                o.put("peer", e.peerName)
                o.put("status", e.status.name)
                if (e.localUri != null) o.put("localUri", e.localUri)
                if (e.sourceUri != null) o.put("sourceUri", e.sourceUri)
                if (e.relativePath != null) o.put("rel", e.relativePath)
                arr.put(o)
            }
            val tmp = File(file.parentFile, "history.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(arr.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun addListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyChanged() {
        val snapshot: List<() -> Unit>
        synchronized(listeners) { snapshot = ArrayList(listeners) }
        for (l in snapshot) l.invoke()
    }
}
