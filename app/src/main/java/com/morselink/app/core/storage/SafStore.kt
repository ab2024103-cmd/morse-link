package com.morselink.app.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.morselink.app.core.util.MorselinkServices
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persisted SAF folder grants (spec Section 5.2). Every grant is persisted via
 * takePersistableUriPermission immediately so the user is never re-asked.
 */
object SafStore {

    private const val PREFS = "saf_store"

    fun persistedGrants(): List<Uri> {
        val context = MorselinkServices.appContext
        val out = ArrayList<Uri>()
        val infos = context.contentResolver.persistedUriPermissions
        for (info in infos) {
            if (info.isReadPermission) out.add(info.uri)
        }
        return out
    }

    fun persist(uri: Uri) {
        val context = MorselinkServices.appContext
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    fun removeAccess(uri: Uri) {
        val context = MorselinkServices.appContext
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    fun treeLabel(uri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            // e.g. "primary:Download" -> "Download"; "12AF:MyFolder" -> "MyFolder (SD card)"
            val idx = docId.indexOf(':')
            val volume = if (idx > 0) docId.substring(0, idx) else ""
            val path = if (idx >= 0 && idx + 1 < docId.length) docId.substring(idx + 1) else docId
            val clean = path.replace('/', ' ').trim()
            if (volume.isEmpty() || volume == "primary") {
                if (clean.isEmpty()) "Internal storage" else clean
            } else {
                if (clean.isEmpty()) volume else "$clean ($volume)"
            }
        } catch (_: Exception) {
            uri.lastPathSegment ?: "Folder"
        }
    }

    /** A stored marker of "extra" roots the user added (beyond persisted grants). */
    fun extraRoots(): List<String> {
        val context = MorselinkServices.appContext
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString("extra_roots", null) ?: return emptyList()
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (_: Exception) {
        }
        return out
    }

    fun addExtraRoot(uri: Uri) {
        val context = MorselinkServices.appContext
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = ArrayList(extraRoots())
        if (!list.contains(uri.toString())) list.add(uri.toString())
        sp.edit().putString("extra_roots", JSONArray(list).toString()).apply()
    }

    fun rootDocumentFile(uri: Uri): DocumentFile? {
        return try {
            DocumentFile.fromTreeUri(MorselinkServices.appContext, uri)
        } catch (_: Exception) {
            null
        }
    }

    /** Internal storage root as a plain File (only usable with all-files access or pre-Q). */
    fun storageRootFile(): File? {
        return try {
            val f = File("/storage/emulated/0")
            if (f.exists() && f.canRead()) f else null
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- document listing at arbitrary depth ----------------

    class SafEntry(
        val name: String,
        val documentId: String,
        val isDirectory: Boolean,
        val size: Long,
        val mime: String,
        val uri: Uri
    )

    /** The document uri of a tree's root folder. */
    fun treeRootDocumentUri(treeUri: Uri): Uri {
        return DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
    }

    /**
     * Lists the children of [folderUri] within [treeUri] at any depth, via the
     * documents contract. Returns null on access failure — distinct from an
     * empty list, so access-denied is never mistaken for an empty folder
     * (spec Section 5.3).
     */
    fun listChildren(context: Context, folderUri: Uri): List<SafEntry>? {
        return try {
            val docId = try {
                DocumentsContract.getDocumentId(folderUri)
            } catch (_: IllegalArgumentException) {
                DocumentsContract.getTreeDocumentId(folderUri)
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri, docId
            )
            val out = ArrayList<SafEntry>()
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: "application/octet-stream"
                    val size = cursor.getLong(3)
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, id)
                    out.add(
                        SafEntry(
                            name = name,
                            documentId = id,
                            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            size = size,
                            mime = mime,
                            uri = childUri
                        )
                    )
                }
            } ?: return null
            out
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
