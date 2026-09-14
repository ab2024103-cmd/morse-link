package com.morselink.app.core.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.morselink.app.di.AppServices
import com.morselink.app.core.util.Integrity
import com.morselink.app.core.util.MorselinkServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

/** How an incoming file should be placed. */
enum class ConflictDecision { OVERWRITE, SKIP, KEEP_BOTH }

data class FinalResult(
    val status: Status,
    val finalUri: String? = null,
    val finalPath: String? = null,
    val displayName: String? = null
) {
    enum class Status { SAVED, SKIPPED, FAILED }
}

/**
 * Receive-side destination handling: conflict detection (name -> size -> sha256),
 * MediaStore insertion with correct date-field units, keep-both renaming, and
 * SAF tree destinations (spec Sections 8.6 and 12.2).
 */
object Destinations {

    private const val DEFAULT_SUBDIR = "MorseLink"

    /** Called by the engine; `ask` suspends until the user answers a conflict dialog. */
    suspend fun finalize(
        fileName: String,
        relativePath: String?,
        mime: String,
        tempFile: File,
        policy: com.morselink.app.core.model.ConflictPolicy,
        ask: suspend (fileName: String, relativePath: String?) -> ConflictDecision
    ): FinalResult {
        val context = MorselinkServices.appContext
        val prefs = AppServices.prefs
        val treeUriString = prefs.defaultDownloadDirUri
        val cleanRel = relativePath?.trim('/')?.takeIf { it.isNotEmpty() }

        return if (treeUriString != null) {
            val tree = SafStore.rootDocumentFile(Uri.parse(treeUriString))
            if (tree != null && tree.canWrite()) {
                finalizeSaf(context, tree, fileName, cleanRel, mime, tempFile, policy, ask)
            } else {
                finalizeSystem(context, fileName, cleanRel, mime, tempFile, policy, ask)
            }
        } else {
            finalizeSystem(context, fileName, cleanRel, mime, tempFile, policy, ask)
        }
    }

    private suspend fun finalizeSaf(
        context: Context,
        tree: DocumentFile,
        fileName: String,
        relativePath: String?,
        mime: String,
        tempFile: File,
        policy: com.morselink.app.core.model.ConflictPolicy,
        ask: suspend (String, String?) -> ConflictDecision
    ): FinalResult {
        try {
            var dir = tree
            if (relativePath != null) {
                for (segment in relativePath.split('/')) {
                    if (segment.isEmpty()) continue
                    val next = dir.findFile(segment)
                    dir = if (next != null && next.isDirectory) next
                    else dir.createDirectory(segment) ?: return FinalResult(FinalResult.Status.FAILED)
                }
            }
            var targetName = fileName
            var existing = dir.findFile(targetName)
            if (existing != null) {
                val decision = decide(policy, ask, fileName, relativePath)
                when (decision) {
                    ConflictDecision.SKIP -> {
                        tempFile.delete()
                        return FinalResult(FinalResult.Status.SKIPPED)
                    }
                    ConflictDecision.OVERWRITE -> {
                        existing.delete()
                        existing = null
                    }
                    ConflictDecision.KEEP_BOTH -> {
                        targetName = keepBothName(fileName) { cand -> dir.findFile(cand) == null }
                    }
                    else -> {}
                }
            }
            val created = dir.createFile(mime, targetName) ?: return FinalResult(FinalResult.Status.FAILED)
            if (targetName != fileName && existing != null) {
                // keep-both: existing stays, we already created the new name
            }
            context.contentResolver.openOutputStream(created.uri, "wt")?.use { out ->
                copy(FileInputStream(tempFile), out)
            } ?: return FinalResult(FinalResult.Status.FAILED)
            tempFile.delete()
            return FinalResult(
                FinalResult.Status.SAVED,
                finalUri = created.uri.toString(),
                finalPath = targetName
            )
        } catch (e: Exception) {
            return FinalResult(FinalResult.Status.FAILED)
        }
    }

    private suspend fun finalizeSystem(
        context: Context,
        fileName: String,
        relativePath: String?,
        mime: String,
        tempFile: File,
        policy: com.morselink.app.core.model.ConflictPolicy,
        ask: suspend (String, String?) -> ConflictDecision
    ): FinalResult {
        if (Build.VERSION.SDK_INT >= 29) {
            return finalizeMediaStore(context, fileName, relativePath, mime, tempFile, policy, ask)
        }
        // Pre-Q: write directly into the public Downloads/MorseLink directory.
        return try {
            val base = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DEFAULT_SUBDIR
            )
            val dir = if (relativePath != null) File(base, relativePath) else base
            dir.mkdirs()
            var targetName = fileName
            var target = File(dir, targetName)
            if (target.exists()) {
                when (decide(policy, ask, fileName, relativePath)) {
                    ConflictDecision.SKIP -> {
                        tempFile.delete()
                        return FinalResult(FinalResult.Status.SKIPPED)
                    }
                    ConflictDecision.OVERWRITE -> target.delete()
                    ConflictDecision.KEEP_BOTH -> {
                        targetName = keepBothName(fileName) { cand -> !File(dir, cand).exists() }
                        target = File(dir, targetName)
                    }
                    else -> {}
                }
            }
            copy(FileInputStream(tempFile), FileOutputStream(target))
            tempFile.delete()
            scanFile(context, target, mime)
            FinalResult(FinalResult.Status.SAVED, finalUri = Uri.fromFile(target).toString(), finalPath = target.absolutePath)
        } catch (e: Exception) {
            FinalResult(FinalResult.Status.FAILED)
        }
    }

    private suspend fun finalizeMediaStore(
        context: Context,
        fileName: String,
        relativePath: String?,
        mime: String,
        tempFile: File,
        policy: com.morselink.app.core.model.ConflictPolicy,
        ask: suspend (String, String?) -> ConflictDecision
    ): FinalResult {
        try {
            val collection = collectionFor(mime)
            val subDir = if (relativePath != null) "$DEFAULT_SUBDIR/$relativePath" else DEFAULT_SUBDIR
            val relPath = "${topDirFor(mime)}/$subDir"

            val existing = queryExisting(context, collection, fileName, relPath)
            var targetName = fileName
            if (existing != null) {
                when (decide(policy, ask, fileName, relativePath)) {
                    ConflictDecision.SKIP -> {
                        tempFile.delete()
                        return FinalResult(FinalResult.Status.SKIPPED)
                    }
                    ConflictDecision.OVERWRITE -> {
                        try {
                            context.contentResolver.delete(existing, null, null)
                        } catch (_: Exception) {
                        }
                    }
                    ConflictDecision.KEEP_BOTH -> {
                        targetName = keepBothName(fileName) { cand ->
                            queryExisting(context, collection, cand, relPath) == null
                        }
                    }
                    else -> {}
                }
            }

            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, targetName)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            // DATE_TAKEN is stored in MILLISECONDS; DATE_ADDED and DATE_MODIFIED in SECONDS.
            // Mixing the units makes files sort to the Unix epoch (spec Section 12.2).
            val now = System.currentTimeMillis()
            if (collection == MediaStore.Images.Media.EXTERNAL_CONTENT_URI) {
                values.put(MediaStore.Images.Media.DATE_TAKEN, now) // ms
            }
            values.put(MediaStore.MediaColumns.DATE_ADDED, now / 1000L) // seconds
            values.put(MediaStore.MediaColumns.DATE_MODIFIED, now / 1000L) // seconds
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)

            val inserted = context.contentResolver.insert(collection, values)
                ?: return FinalResult(FinalResult.Status.FAILED)
            context.contentResolver.openOutputStream(inserted, "wt")?.use { out ->
                copy(FileInputStream(tempFile), out)
            } ?: run {
                context.contentResolver.delete(inserted, null, null)
                return FinalResult(FinalResult.Status.FAILED)
            }
            val done = ContentValues()
            done.put(MediaStore.MediaColumns.IS_PENDING, 0)
            try {
                context.contentResolver.update(inserted, done, null, null)
            } catch (_: Exception) {
            }
            tempFile.delete()
            val savedName = readBackName(context, inserted) ?: targetName
            return FinalResult(
                FinalResult.Status.SAVED,
                finalUri = inserted.toString(),
                finalPath = savedName
            )
        } catch (e: Exception) {
            return FinalResult(FinalResult.Status.FAILED)
        }
    }

    fun collectionFor(mime: String): Uri = when {
        mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        Build.VERSION.SDK_INT >= 29 -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        else -> MediaStore.Files.getContentUri("external")
    }

    private fun topDirFor(mime: String): String = when {
        mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
        mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
        mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
        else -> Environment.DIRECTORY_DOWNLOADS
    }

    /**
     * Size of the file that a finalize() would consider "existing", or null if
     * the destination name is free. Mirrors the three destination paths: SAF
     * tree, MediaStore (Q+), and pre-Q public Downloads (spec Section 8.6).
     */
    suspend fun existingDestinationSize(
        fileName: String,
        relativePath: String?,
        mime: String
    ): Long? = withContext(Dispatchers.IO) {
        val context = MorselinkServices.appContext
        val cleanRel = relativePath?.trim('/')?.takeIf { it.isNotEmpty() }
        try {
            val treeUriString = AppServices.prefs.defaultDownloadDirUri
            if (treeUriString != null) {
                var dir = SafStore.rootDocumentFile(Uri.parse(treeUriString))
                    ?: return@withContext null
                if (cleanRel != null) {
                    for (seg in cleanRel.split('/')) {
                        dir = dir.findFile(seg) ?: return@withContext null
                    }
                }
                val target = dir.findFile(fileName) ?: return@withContext null
                if (target.isFile) target.length() else null
            } else if (Build.VERSION.SDK_INT >= 29) {
                val collection = collectionFor(mime)
                val relPath = "${topDirFor(mime)}/" +
                    (if (cleanRel != null) "$DEFAULT_SUBDIR/$cleanRel" else DEFAULT_SUBDIR)
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns.SIZE),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf(fileName, relPath),
                    null
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: null
            } else {
                val base = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    DEFAULT_SUBDIR
                )
                val dir = if (cleanRel != null) File(base, cleanRel) else base
                val target = File(dir, fileName)
                if (target.exists() && target.isFile) target.length() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** sha256 of the existing destination file, or null when unreadable. */
    suspend fun hashExisting(
        fileName: String,
        relativePath: String?,
        mime: String
    ): String? = withContext(Dispatchers.IO) {
        val context = MorselinkServices.appContext
        val cleanRel = relativePath?.trim('/')?.takeIf { it.isNotEmpty() }
        try {
            val treeUriString = AppServices.prefs.defaultDownloadDirUri
            if (treeUriString != null) {
                var dir = SafStore.rootDocumentFile(Uri.parse(treeUriString))
                    ?: return@withContext null
                if (cleanRel != null) {
                    for (seg in cleanRel.split('/')) {
                        dir = dir.findFile(seg) ?: return@withContext null
                    }
                }
                val target = dir.findFile(fileName) ?: return@withContext null
                context.contentResolver.openInputStream(target.uri)?.use { Integrity.sha256(it) }
            } else if (Build.VERSION.SDK_INT >= 29) {
                val collection = collectionFor(mime)
                val relPath = "${topDirFor(mime)}/" +
                    (if (cleanRel != null) "$DEFAULT_SUBDIR/$cleanRel" else DEFAULT_SUBDIR)
                val id = context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf(fileName, relPath),
                    null
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                    ?: return@withContext null
                val uri = ContentUris.withAppendedId(collection, id)
                context.contentResolver.openInputStream(uri)?.use { Integrity.sha256(it) }
            } else {
                val base = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    DEFAULT_SUBDIR
                )
                val dir = if (cleanRel != null) File(base, cleanRel) else base
                val target = File(dir, fileName)
                if (target.exists()) FileInputStream(target).use { Integrity.sha256(it) } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryExisting(context: Context, collection: Uri, name: String, relPath: String): Uri? {
        return try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(name, relPath),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUrisAppend.appendId(collection, cursor.getLong(0))
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Small helper to avoid importing ContentUris in multiple places. */
    private object ContentUrisAppend {
        fun appendId(collection: Uri, id: Long): Uri =
            android.content.ContentUris.withAppendedId(collection, id)
    }

    private fun readBackName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun decide(
        policy: com.morselink.app.core.model.ConflictPolicy,
        ask: suspend (String, String?) -> ConflictDecision,
        fileName: String,
        relativePath: String?
    ): ConflictDecision = when (policy) {
        com.morselink.app.core.model.ConflictPolicy.ALWAYS_OVERWRITE -> ConflictDecision.OVERWRITE
        com.morselink.app.core.model.ConflictPolicy.ALWAYS_SKIP -> ConflictDecision.SKIP
        com.morselink.app.core.model.ConflictPolicy.ALWAYS_KEEP_BOTH -> ConflictDecision.KEEP_BOTH
        com.morselink.app.core.model.ConflictPolicy.ALWAYS_ASK -> ask(fileName, relativePath)
    }

    /** photo.jpg -> photo (1).jpg -> photo (2).jpg, probing destination names. */
    fun keepBothName(fileName: String, isFree: (String) -> Boolean): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var i = 1
        while (i < 1000) {
            val candidate = "$base ($i)$ext"
            if (isFree(candidate)) return candidate
            i++
        }
        return "$base (${System.currentTimeMillis()})$ext"
    }

    fun tempDir(context: Context): File {
        val dir = File(context.cacheDir, "incoming")
        dir.mkdirs()
        return dir
    }

    fun tempFileFor(context: Context, name: String): File {
        val safe = name.replace('/', '_').takeLast(120)
        return File(tempDir(context), "$safe.morselink.part")
    }

    /** Deletes stale .part temp files older than [maxAgeMs] with no matching journal entry. */
    fun cleanupStaleParts(context: Context, maxAgeMs: Long) {
        try {
            val dir = tempDir(context)
            val cutoff = System.currentTimeMillis() - maxAgeMs
            for (f in dir.listFiles() ?: emptyArray()) {
                if (f.name.endsWith(".morselink.part") && f.lastModified() < cutoff) {
                    f.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Deletes the underlying file for a history entry (distinct from deleting the record). */
    fun deleteUnderlying(uriString: String): Boolean {
        val context = MorselinkServices.appContext
        return try {
            val uri = Uri.parse(uriString)
            when {
                uri.scheme == "file" -> File(uri.path ?: return false).delete()
                uri.scheme == "content" -> {
                    val authority = uri.authority ?: return false
                    if (authority == "com.android.externalstorage.documents") {
                        DocumentsContractDelete.deleteDocument(uri)
                    } else {
                        context.contentResolver.delete(uri, null, null) > 0
                    }
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private object DocumentsContractDelete {
        fun deleteDocument(uri: Uri): Boolean = try {
            android.provider.DocumentsContract.deleteDocument(
                MorselinkServices.appContext.contentResolver, uri
            )
        } catch (_: Exception) {
            false
        }
    }

    fun scanFile(context: Context, file: File, mime: String) {
        try {
            android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        } catch (_: Exception) {
        }
    }

    private fun copy(input: FileInputStream, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        out.flush()
        input.close()
    }
}
