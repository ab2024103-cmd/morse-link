package com.morselink.app.core.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.morselink.app.core.util.MorselinkServices

/** A media/document item as shown in lists and grids. */
data class MediaItem(
    val id: Long,
    val uri: String,
    val name: String,
    val size: Long,
    val mime: String,
    val dateModifiedSec: Long,
    val dateTakenMs: Long,
    val durationMs: Long,
    val artist: String? = null,
    val path: String? = null,
    val synthetic: Boolean = false
)

/** Installed app entry for the Apps tab. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val apkPath: String,
    val size: Long,
    val versionName: String?
)

/** Queryable categories. */
enum class MediaCategory(val key: String) {
    PHOTOS("photos"),
    VIDEOS("videos"),
    MUSIC("music"),
    DOCUMENTS("documents"),
    EBOOKS("ebooks"),
    ARCHIVES("archives"),
    APK_FILES("apk_files"),
    LARGE_FILES("large_files"),
    DOWNLOADS("downloads");

    companion object {
        fun fromKey(key: String): MediaCategory? {
            for (c in values()) {
                if (c.key == key) return c
            }
            return null
        }
    }
}

enum class SortKey(val key: String) { DATE("date"), NAME("name"), SIZE("size") }

/**
 * Paginated MediaStore queries (spec Sections 7.5 / 10.5). All queries are
 * indexed MediaStore queries — never raw recursive filesystem walks.
 */
object MediaLibrary {

    private val DOCUMENT_MIMES = listOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain",
        "text/csv",
        "text/html",
        "application/rtf"
    )
    private val EBOOK_MIMES = listOf(
        "application/epub+zip",
        "application/x-mobipocket-ebook",
        "application/pdf"
    )
    private val ARCHIVE_MIMES = listOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/x-rar-compressed",
        "application/vnd.rar",
        "application/x-7z-compressed",
        "application/gzip",
        "application/x-tar",
        "application/x-bzip2"
    )

    fun baseUri(category: MediaCategory): Uri = when (category) {
        MediaCategory.PHOTOS -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaCategory.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaCategory.MUSIC -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        MediaCategory.DOWNLOADS -> if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        else -> MediaStore.Files.getContentUri("external")
    }

    private fun selectionFor(category: MediaCategory): Pair<String?, List<String>> {
        return when (category) {
            MediaCategory.PHOTOS -> Pair(null, emptyList())
            MediaCategory.VIDEOS -> Pair(null, emptyList())
            MediaCategory.MUSIC -> Pair("${MediaStore.Audio.Media.IS_MUSIC} != 0", emptyList())
            MediaCategory.DOCUMENTS -> inMimes(DOCUMENT_MIMES)
            MediaCategory.EBOOKS -> inMimes(EBOOK_MIMES)
            MediaCategory.ARCHIVES -> inMimes(ARCHIVE_MIMES)
            MediaCategory.APK_FILES ->
                Pair("${MediaStore.Files.FileColumns.MIME_TYPE} = ?", listOf("application/vnd.android.package-archive"))
            MediaCategory.LARGE_FILES ->
                Pair("${MediaStore.Files.FileColumns.SIZE} > ?", listOf((100L * 1024 * 1024).toString()))
            MediaCategory.DOWNLOADS -> Pair(null, emptyList())
        }
    }

    private fun inMimes(mimes: List<String>): Pair<String, List<String>> {
        val placeholders = StringBuilder()
        for (i in mimes.indices) {
            if (i > 0) placeholders.append(",")
            placeholders.append("?")
        }
        return Pair("${MediaStore.Files.FileColumns.MIME_TYPE} IN ($placeholders)", mimes)
    }

    private fun orderBy(sortKey: SortKey, descending: Boolean): String {
        val col = when (sortKey) {
            SortKey.DATE -> MediaStore.MediaColumns.DATE_MODIFIED
            SortKey.NAME -> MediaStore.MediaColumns.DISPLAY_NAME
            SortKey.SIZE -> MediaStore.MediaColumns.SIZE
        }
        val dir = if (descending) "DESC" else "ASC"
        val suffix = if (sortKey == SortKey.NAME) " COLLATE NOCASE" else ""
        return "$col$suffix $dir, ${MediaStore.MediaColumns._ID} DESC"
    }

    fun page(
        category: MediaCategory,
        offset: Int,
        limit: Int,
        sortKey: SortKey,
        descending: Boolean,
        query: String?,
        folder: String? = null
    ): List<MediaItem> {
        val context = MorselinkServices.appContext
        val uri = if (category == MediaCategory.DOWNLOADS && Build.VERSION.SDK_INT < 29) {
            MediaStore.Files.getContentUri("external")
        } else {
            baseUri(category)
        }
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA
        )
        if (category == MediaCategory.PHOTOS) projection.add(MediaStore.Images.Media.DATE_TAKEN)
        if (category == MediaCategory.VIDEOS) projection.add(MediaStore.Video.Media.DURATION)
        if (category == MediaCategory.MUSIC) {
            projection.add(MediaStore.Audio.Media.DURATION)
            projection.add(MediaStore.Audio.Media.ARTIST)
        }

        var selection = selectionFor(category).first
        var args = selectionFor(category).second.toMutableList()
        if (category == MediaCategory.DOWNLOADS && Build.VERSION.SDK_INT < 29) {
            // Pre-Q there is no Downloads collection; approximate via Download dir path.
            val dl = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )?.absolutePath ?: "/Download"
            selection = (selection?.plus(" AND ") ?: "") + "${MediaStore.MediaColumns.DATA} LIKE ?"
            args.add("$dl%")
        }
        if (!folder.isNullOrBlank()) {
            // Folder browse (WebShare a2/a3): filter by parent directory name.
            val esc = folder.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            selection = (selection?.plus(" AND ") ?: "") + "${MediaStore.MediaColumns.DATA} LIKE ? ESCAPE '\\'"
            args.add("%/" + esc + "/%")
        }
        if (!query.isNullOrBlank()) {
            // Match DISPLAY_NAME or the data path: older MediaStore rows often
            // have a null DISPLAY_NAME, which made search return nothing.
            val like = "%" + query.replace("%", "").replace("_", "") + "%"
            selection = (selection?.plus(" AND ") ?: "") +
                "(${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DATA} LIKE ?)"
            args.add(like)
            args.add(like)
        }

        val out = ArrayList<MediaItem>()
        var cursorQuery: android.database.Cursor? = null
        try {
            try {
                cursorQuery = context.contentResolver.query(
                    uri, projection.toTypedArray(), selection, args.toTypedArray(),
                    orderBy(sortKey, descending) + " LIMIT $limit OFFSET $offset"
                )
            } catch (e: Exception) {
                // Some OEM MediaProvider builds reject LIMIT in sortOrder —
                // fall back to an unbounded query and window it here.
                com.morselink.app.core.logging.LogStore.w(
                    "Media query with LIMIT failed (${category.key}): ${e.message}; retrying without LIMIT"
                )
                cursorQuery = null
            }
            if (cursorQuery == null && offset == 0) {
                try {
                    cursorQuery = context.contentResolver.query(
                        uri, projection.toTypedArray(), selection, args.toTypedArray(),
                        orderBy(sortKey, descending)
                    )
                } catch (e: Exception) {
                    com.morselink.app.core.logging.LogStore.e("Media query failed (${category.key})", e)
                }
            }
            cursorQuery?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val takenCol = if (category == MediaCategory.PHOTOS) cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN) else -1
                val durCol = if (category == MediaCategory.VIDEOS || category == MediaCategory.MUSIC) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                } else -1
                val artistCol = if (category == MediaCategory.MUSIC) cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST) else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(baseUri(category), id)
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    // DISPLAY_NAME is often null on older MediaStore rows (Android 6
                    // Files collection) — fall back to the file's real name from DATA,
                    // never a generic "file".
                    val name = cursor.getString(nameCol)
                        ?: dataPath?.trimEnd('/')?.substringAfterLast('/')
                        ?: "file"
                    val mime = cursor.getString(mimeCol) ?: guessMime(name)
                    out.add(
                        MediaItem(
                            id = id,
                            uri = contentUri.toString(),
                            name = name,
                            size = cursor.getLong(sizeCol),
                            mime = mime,
                            dateModifiedSec = cursor.getLong(dateCol),
                            dateTakenMs = if (takenCol >= 0 && !cursor.isNull(takenCol)) cursor.getLong(takenCol) else 0L,
                            durationMs = if (durCol >= 0 && !cursor.isNull(durCol)) cursor.getLong(durCol) else 0L,
                            artist = if (artistCol >= 0 && !cursor.isNull(artistCol)) cursor.getString(artistCol) else null,
                            path = dataPath
                        )
                    )
                }
            }
        } catch (e: Exception) {
            com.morselink.app.core.logging.LogStore.e("Media page() failed (${category.key})", e)
        } finally {
            try {
                cursorQuery?.close()
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** Folder (parent directory) counts for a category, largest first. */
    fun folderCounts(category: MediaCategory): List<Pair<String, Int>> {
        val context = MorselinkServices.appContext
        val selection = selectionFor(category).first
        val args = selectionFor(category).second.toTypedArray()
        val counts = HashMap<String, Int>()
        try {
            context.contentResolver.query(
                baseUri(category), arrayOf(MediaStore.MediaColumns.DATA), selection, args, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val p = cursor.getString(0) ?: continue
                    val label = p.trimEnd('/').substringBeforeLast('/').substringAfterLast('/')
                    if (label.isEmpty()) continue
                    counts[label] = (counts[label] ?: 0) + 1
                }
            }
        } catch (e: Exception) {
            com.morselink.app.core.logging.LogStore.e("Media folderCounts failed (${category.key})", e)
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(300)
            .map { it.key to it.value }
    }

    fun count(category: MediaCategory, query: String? = null): Int {
        val context = MorselinkServices.appContext
        val uri = baseUri(category)
        var selection = selectionFor(category).first
        var args = selectionFor(category).second.toMutableList()
        if (!query.isNullOrBlank()) {
            // Match DISPLAY_NAME or the data path: older MediaStore rows often
            // have a null DISPLAY_NAME, which made search return nothing.
            val like = "%" + query.replace("%", "").replace("_", "") + "%"
            selection = (selection?.plus(" AND ") ?: "") +
                "(${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DATA} LIKE ?)"
            args.add(like)
            args.add(like)
        }
        return try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns._ID), selection, args.toTypedArray(), null
            )?.use { it.count } ?: 0
        } catch (e: Exception) {
            com.morselink.app.core.logging.LogStore.e("Media count() failed (${category.key})", e)
            0
        }
    }

    fun byId(category: MediaCategory, id: Long): MediaItem? {
        val context = MorselinkServices.appContext
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        return try {
            context.contentResolver.query(
                ContentUris.withAppendedId(baseUri(category), id),
                projection, null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(baseUri(category), id).toString(),
                        name = cursor.getString(1) ?: "file",
                        size = cursor.getLong(2),
                        mime = cursor.getString(3) ?: "application/octet-stream",
                        dateModifiedSec = cursor.getLong(4),
                        dateTakenMs = 0, durationMs = 0
                    )
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun guessMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".heic") || lower.endsWith(".heif") -> "image/heic"
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".3gp") -> "video/3gpp"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> "audio/mp4"
            lower.endsWith(".flac") -> "audio/flac"
            lower.endsWith(".ogg") -> "audio/ogg"
            lower.endsWith(".wav") -> "audio/x-wav"
            lower.endsWith(".apk") -> "application/vnd.android.package-archive"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".txt") -> "text/plain"
            lower.endsWith(".epub") -> "application/epub+zip"
            else -> "application/octet-stream"
        }
    }

    /** Loads installed, launchable apps (visible per manifest <queries>). */
    fun installedApps(): List<AppEntry> {
        val context = MorselinkServices.appContext
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
        intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        try {
            val resolved = pm.queryIntentActivities(intent, 0)
            for (ri in resolved) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (!seen.add(pkg)) continue
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(info)?.toString() ?: pkg
                    val apkFile = java.io.File(info.sourceDir ?: continue)
                    var version: String? = null
                    try {
                        version = pm.getPackageInfo(pkg, 0).versionName
                    } catch (_: Exception) {
                    }
                    out.add(
                        AppEntry(
                            packageName = pkg,
                            label = label,
                            apkPath = info.sourceDir,
                            size = apkFile.length(),
                            versionName = version
                        )
                    )
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        out.sortBy { it.label.lowercase() }
        return out
    }

    fun itemByUri(uriString: String): MediaItem? {
        return try {
            val uri = Uri.parse(uriString)
            val id = ContentUris.parseId(uri)
            val category = when (uri.pathSegments?.firstOrNull()) {
                "images" -> MediaCategory.PHOTOS
                "video" -> MediaCategory.VIDEOS
                "audio" -> MediaCategory.MUSIC
                else -> MediaCategory.DOWNLOADS
            }
            byId(category, id)
        } catch (_: Exception) {
            null
        }
    }
}
