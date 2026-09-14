package com.morselink.app.feature.filemanager

import com.morselink.app.core.model.TransferableFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * A selectable item in the file manager / picker. One canonical selection
 * source read by both rendering and the pending-send queue (spec Sections
 * 10.5 and 14.3) — clearing it clears it everywhere.
 */
data class Selectable(
    val key: String,
    val name: String,
    val size: Long,
    val uri: String,
    val mime: String,
    val relativePath: String? = null,
    val isDirectory: Boolean = false,
    val kind: String = "media" // media | app | document | folder
) {
    fun toTransferable(relative: String? = null): TransferableFile {
        return TransferableFile(
            id = UUID.randomUUID().toString(),
            displayName = name,
            size = size,
            uri = uri,
            mime = mime,
            relativePath = relative,
            sourceKind = kind
        )
    }
}

object SelectionState {
    private val _selected = MutableStateFlow<List<Selectable>>(emptyList())
    val selected: StateFlow<List<Selectable>> = _selected

    private val map = LinkedHashMap<String, Selectable>()

    fun isSelected(key: String): Boolean = map.containsKey(key)

    fun toggle(item: Selectable) {
        if (map.containsKey(item.key)) map.remove(item.key) else map[item.key] = item
        publish()
    }

    fun setAll(items: List<Selectable>, selected: Boolean) {
        if (selected) {
            for (i in items) map[i.key] = i
        } else {
            for (i in items) map.remove(i.key)
        }
        publish()
    }

    fun clear() {
        map.clear()
        publish()
    }

    fun current(): List<Selectable> = ArrayList(map.values)

    fun totalSize(): Long = map.values.sumOf { it.size }

    private fun publish() {
        _selected.value = ArrayList(map.values)
    }
}
