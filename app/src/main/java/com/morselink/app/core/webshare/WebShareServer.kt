package com.morselink.app.core.webshare

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.morselink.app.core.logging.LogStore
import com.morselink.app.core.media.AppEntry
import com.morselink.app.core.media.MediaCategory
import com.morselink.app.core.media.MediaLibrary
import com.morselink.app.core.storage.ConflictDecision
import com.morselink.app.core.storage.Destinations
import com.morselink.app.core.storage.FinalResult
import com.morselink.app.core.storage.ZipUtil
import com.morselink.app.di.AppServices
import com.morselink.app.core.util.MorselinkServices
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phone-hosted HTTP server for WebShare (spec Section 7.5). Every endpoint
 * except the index page requires the per-session token. Responses are served
 * with Cache-Control: no-store. Downloads support Range; folder downloads are
 * ZIP64-capable streams built on the fly and aborted on broken pipe.
 */
class WebShareServer(private val token: String) : NanoHTTPD("0.0.0.0", PORT) {

    companion object {
        const val PORT = 33455
        private const val MAX_PAGE = 500
    }

    @Volatile
    var lastClientActivity: Long = System.currentTimeMillis()

    @Volatile
    var activeOperations = 0

    private val uploadParts = ConcurrentHashMap<String, File>()
    private var appsCache: Pair<Long, List<AppEntry>>? = null

    // ---------------- routing ----------------

    override fun serve(session: IHTTPSession): Response {
        lastClientActivity = System.currentTimeMillis()
        return try {
            route(session)
        } catch (e: Exception) {
            LogStore.e("WebShare: error serving ${session.uri}", e)
            newJson(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("error", "internal error")
            ).noStore()
        }
    }

    private fun route(session: IHTTPSession): Response {
        val path = session.uri ?: "/"
        if (session.method == Method.GET && (path == "/" || path == "/index.html")) {
            return indexPage()
        }
        if (!authorized(session)) {
            return newJson(Response.Status.UNAUTHORIZED, JSONObject().put("error", "invalid or missing session token")).noStore()
        }
        return when {
            session.method == Method.GET && path == "/api/info" -> apiInfo()
            session.method == Method.GET && path == "/api/counts" -> apiCounts()
            session.method == Method.GET && path == "/api/files" -> apiFiles(session)
            session.method == Method.GET && path == "/api/upload-status" -> apiUploadStatus(session)
            session.method == Method.GET && path == "/api/qr" -> apiQr()
            session.method == Method.GET && path == "/thumbnail" -> thumbnail(session)
            session.method == Method.GET && path == "/download" -> download(session)
            session.method == Method.GET && path == "/download-folder" -> downloadFolder(session)
            session.method == Method.POST && path == "/upload" -> upload(session)
            else -> newJson(Response.Status.NOT_FOUND, JSONObject().put("error", "not found")).noStore()
        }
    }

    /**
     * PNG QR of the full session URL so another device (or any camera) can
     * join without typing the address (m4).
     */
    private fun apiQr(): Response {
        val url = WebShareController.state.value.url
            ?: "http://127.0.0.1:$PORT/"
        val qr = com.morselink.app.core.util.Qr.encode(url, 560)
        if (qr == null) {
            return newJson(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "qr failed"))
        }
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            qr.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
            out.toByteArray()
        }
        val resp = newFixedLengthResponse(
            Response.Status.OK, "image/png",
            java.io.ByteArrayInputStream(bytes), bytes.size.toLong()
        )
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    private fun authorized(session: IHTTPSession): Boolean {
        val provided = session.parameters["t"]?.firstOrNull()
            ?: session.headers?.get("x-session-token")
        return provided != null && provided == token
    }

    private fun indexPage(): Response {
        val html = try {
            MorselinkServices.appContext.assets.open("webshare/index.html")
                .use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            "<html><body>WebShare UI missing</body></html>"
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).noStore()
    }

    private fun Response.noStore(): Response {
        this.addHeader("Cache-Control", "no-store")
        return this
    }

    private fun noStoreJson(response: Response): Response {
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun newJson(status: Response.Status, json: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", json.toString())

    // ---------------- APIs ----------------

    private fun apiInfo(): Response {
        return noStoreJson(
            newJson(
                Response.Status.OK,
                JSONObject()
                    .put("deviceName", AppServices.prefs.deviceName)
                    .put("version", "1.0")
                    .put("transport", "WebShare")
            )
        )
    }

    private fun apiCounts(): Response {
        val apps = cachedApps()
        val json = JSONObject()
            .put("photos", MediaLibrary.count(MediaCategory.PHOTOS))
            .put("videos", MediaLibrary.count(MediaCategory.VIDEOS))
            .put("music", MediaLibrary.count(MediaCategory.MUSIC))
            .put("apps", apps.size)
            .put("files", MediaLibrary.count(MediaCategory.DOWNLOADS))
        return newJson(Response.Status.OK, json).noStore()
    }

    private fun cachedApps(): List<AppEntry> {
        val now = System.currentTimeMillis()
        val cache = appsCache
        if (cache != null && now - cache.first < 60000) return cache.second
        val list = MediaLibrary.installedApps()
        appsCache = Pair(now, list)
        return list
    }

    private fun apiFiles(session: IHTTPSession): Response {
        val category = session.parameters["category"]?.firstOrNull() ?: "photos"
        val path = session.parameters["path"]?.firstOrNull() ?: ""
        val cursor = session.parameters["cursor"]?.firstOrNull()?.toIntOrNull() ?: 0
        var pageSize = session.parameters["pageSize"]?.firstOrNull()?.toIntOrNull() ?: 100
        if (pageSize < 1) pageSize = 50
        if (pageSize > MAX_PAGE) pageSize = MAX_PAGE

        val json = JSONObject()
        val items = JSONArray()

        if (category == "apps") {
            val apps = cachedApps()
            val page = apps.drop(cursor).take(pageSize)
            for (app in page) {
                items.put(
                    JSONObject()
                        .put("id", app.packageName)
                        .put("name", app.label)
                        .put("size", app.size)
                        .put("mime", "application/vnd.android.package-archive")
                        .put("version", app.versionName ?: "")
                )
            }
            json.put("items", items)
            if (cursor + page.size < apps.size) json.put("nextCursor", cursor + page.size)
            return newJson(Response.Status.OK, json).noStore()
        }

        if (category == "files" || category == "downloads") {
            // Browseable folders + files under the Downloads collection.
            val result = browseDownloads(path, cursor, pageSize)
            json.put("items", result.first)
            if (result.second != null) json.put("nextCursor", result.second)
            json.put("folders", result.third)
            return newJson(Response.Status.OK, json).noStore()
        }

        val mediaCategory = MediaCategory.fromKey(category)
            ?: return newJson(Response.Status.NOT_FOUND, JSONObject().put("error", "unknown category")).noStore()
        val page = MediaLibrary.page(mediaCategory, cursor, pageSize, com.morselink.app.core.media.SortKey.DATE, true, null)
        for (m in page) {
            items.put(mediaJson(m))
        }
        json.put("items", items)
        if (page.size == pageSize) json.put("nextCursor", cursor + page.size)
        return newJson(Response.Status.OK, json).noStore()
    }

    private fun mediaJson(m: com.morselink.app.core.media.MediaItem): JSONObject {
        return JSONObject()
            .put("id", m.id.toString())
            .put("name", m.name)
            .put("size", m.size)
            .put("mime", m.mime)
            .put("date", m.dateModifiedSec * 1000L)
            .put("duration", m.durationMs)
            .put("artist", m.artist ?: "")
            .put("hasThumb", true)
    }

    /** Returns (items, nextCursor, folders) for a Downloads-relative path. */
    private fun browseDownloads(path: String, cursor: Int, pageSize: Int): Triple<JSONArray, Int?, JSONArray> {
        val items = JSONArray()
        val folders = JSONArray()
        val context = MorselinkServices.appContext
        val seenFolders = HashSet<String>()
        var nextCursor: Int? = null
        try {
            val cleanPath = path.trim('/')
            val uri = if (Build.VERSION.SDK_INT >= 29) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Files.getContentUri("external")
            }
            val selection: String
            val args: Array<String>
            if (Build.VERSION.SDK_INT >= 29) {
                val rel = "Download/${if (cleanPath.isEmpty()) "" else "$cleanPath/"}"
                selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                args = arrayOf("$rel%")
            } else {
                val base = android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)?.absolutePath
                    ?: "/sdcard/Download"
                val dir = if (cleanPath.isEmpty()) "$base/" else "$base/$cleanPath/"
                selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
                args = arrayOf("$dir%")
            }
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
            )
            var collected = 0
            context.contentResolver.query(
                uri, projection, selection, args,
                "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC LIMIT ${pageSize * 4} OFFSET $cursor"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val size = c.getLong(2)
                    val mime = c.getString(3) ?: "application/octet-stream"
                    val date = c.getLong(4) * 1000L
                    val container = c.getString(5) ?: ""
                    val prefix = if (Build.VERSION.SDK_INT >= 29) {
                        "Download/${if (cleanPath.isEmpty()) "" else "$cleanPath/"}"
                    } else {
                        val base = android.os.Environment
                            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)?.absolutePath
                            ?: "/sdcard/Download"
                        if (cleanPath.isEmpty()) "$base/" else "$base/$cleanPath/"
                    }
                    if (!container.startsWith(prefix)) continue
                    val rest = container.substring(prefix.length)
                    if (rest.contains('/')) {
                        // Nested content: expose the next folder level.
                        val folder = rest.substringBefore('/')
                        if (folder.isNotEmpty() && seenFolders.add(folder)) {
                            val folderPath = if (cleanPath.isEmpty()) folder else "$cleanPath/$folder"
                            folders.put(
                                JSONObject().put("name", folder).put("path", folderPath)
                            )
                        }
                    } else {
                        if (collected >= pageSize) {
                            nextCursor = cursor + pageSize
                            break
                        }
                        items.put(
                            JSONObject()
                                .put("id", id.toString())
                                .put("name", name)
                                .put("size", size)
                                .put("mime", mime)
                                .put("date", date)
                        )
                        collected++
                    }
                }
            }
        } catch (e: Exception) {
            LogStore.e("WebShare: browseDownloads failed", e)
        }
        return Triple(items, nextCursor, folders)
    }

    // ---------------- thumbnails ----------------

    private fun thumbnail(session: IHTTPSession): Response {
        val kind = session.parameters["kind"]?.firstOrNull() ?: "photos"
        val idRaw = session.parameters["id"]?.firstOrNull() ?: return notFound()
        val cacheDir = File(MorselinkServices.appContext.filesDir, "webthumbs").apply { mkdirs() }
        if (kind == "apps") {
            val icon = appIconFile(idRaw, cacheDir)
            if (icon != null) {
                return serveFile(icon, "image/png")
            }
            return notFound()
        }
        val id = idRaw.toLongOrNull() ?: return notFound()
        val category = when (kind) {
            "photos" -> MediaCategory.PHOTOS
            "videos" -> MediaCategory.VIDEOS
            "music" -> MediaCategory.MUSIC
            "files" -> MediaCategory.DOWNLOADS
            else -> return notFound()
        }
        val item = MediaLibrary.byId(category, id)
        if (item == null) {
            // Music/Downloads entries may not resolve by category; serve nothing.
            return notFound()
        }
        val cacheFile = File(cacheDir, "${kind}_$id.bin")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return serveFile(cacheFile, "image/jpeg").also { it.addHeader("Cache-Control", "max-age=3600") }
        }
        val bmp = generateThumbnail(category, id)
            ?: return notFound()
        try {
            FileOutputStream(cacheFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
        } catch (_: Exception) {
        }
        bmp.recycle()
        return serveFile(cacheFile, "image/jpeg").also { it.addHeader("Cache-Control", "max-age=3600") }
    }

    private fun appIconFile(packageName: String, cacheDir: File): File? {
        val cacheFile = File(cacheDir, "app_${packageName.replace('.', '_')}.png")
        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile
        return try {
            val context = MorselinkServices.appContext
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            intent.setPackage(packageName)
            val resolve = pm.queryIntentActivities(intent, 0)
            if (resolve.isEmpty()) return null
            val drawable: Drawable = resolve[0].loadIcon(pm)
            val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, 96, 96)
            drawable.draw(canvas)
            FileOutputStream(cacheFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bmp.recycle()
            cacheFile
        } catch (_: Exception) {
            null
        }
    }

    private fun generateThumbnail(category: MediaCategory, id: Long): Bitmap? {
        val context = MorselinkServices.appContext
        val uri = ContentUris.withAppendedId(MediaLibrary.baseUri(category), id)
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(uri, Size(320, 320), null)
            } else {
                when (category) {
                    MediaCategory.PHOTOS -> decodeSampled(uri, 320)
                    MediaCategory.VIDEOS -> videoFrame(uri)
                    MediaCategory.MUSIC -> albumArt(uri)
                    else -> decodeSampled(uri, 320)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSampled(uri: Uri, target: Int): Bitmap? {
        return try {
            val context = MorselinkServices.appContext
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }

    private fun videoFrame(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(MorselinkServices.appContext, uri)
            retriever.getFrameAtTime(1_000_000)
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun albumArt(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(MorselinkServices.appContext, uri)
            val data = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    // ---------------- downloads ----------------

    private fun notFound(): Response =
        newJson(Response.Status.NOT_FOUND, JSONObject().put("error", "not found")).noStore()

    private fun download(session: IHTTPSession): Response {
        val kind = session.parameters["kind"]?.firstOrNull() ?: "photos"
        val idRaw = session.parameters["id"]?.firstOrNull() ?: return notFound()
        activeOperations++
        try {
            if (kind == "apps") {
                val apps = cachedApps()
                val app = apps.firstOrNull { it.packageName == idRaw } ?: return notFound()
                val file = File(app.apkPath)
                if (!file.exists()) return notFound()
                return rangedFile(session, file, app.label + ".apk", "application/vnd.android.package-archive")
            }
            val id = idRaw.toLongOrNull() ?: return notFound()
            val category = when (kind) {
                "photos" -> MediaCategory.PHOTOS
                "videos" -> MediaCategory.VIDEOS
                "music" -> MediaCategory.MUSIC
                "files", "downloads" -> MediaCategory.DOWNLOADS
                "documents" -> MediaCategory.DOCUMENTS
                "archives" -> MediaCategory.ARCHIVES
                else -> return notFound()
            }
            val item = MediaLibrary.byId(category, id) ?: return notFound()
            val stream = ZipUtil.openStream(MorselinkServices.appContext, item.uri) ?: return notFound()
            return rangedStream(session, stream, item.size, item.name, item.mime)
        } finally {
            activeOperations--
        }
    }

    private fun downloadFolder(session: IHTTPSession): Response {
        val kind = session.parameters["kind"]?.firstOrNull() ?: "photos"
        val path = session.parameters["path"]?.firstOrNull() ?: ""
        activeOperations++
        val pipedIn = PipedInputStream(256 * 1024)
        val pipeOut = PipedOutputStream(pipedIn)
        Thread {
            try {
                val zos = ZipOutputStream(pipeOut)
                streamCategoryIntoZip(zos, kind, path)
                zos.finish()
                zos.flush()
                pipeOut.close()
            } catch (e: IOException) {
                // Broken pipe: the browser aborted the download — stop generating
                // immediately (spec Section 7.5).
                LogStore.i("WebShare: zip download aborted by client (${e.message})")
                try {
                    pipeOut.close()
                } catch (_: Exception) {
                }
            } catch (e: Exception) {
                LogStore.e("WebShare: zip generation failed", e)
                try {
                    pipeOut.close()
                } catch (_: Exception) {
                }
            } finally {
                activeOperations--
            }
        }.start()

        val name = if (path.isBlank()) "morselink-$kind" else path.trim('/').replace('/', '_')
        val response = newChunkedResponse(Response.Status.OK, "application/zip", pipedIn)
        response.addHeader(
            "Content-Disposition",
            "attachment; filename=\"$name.zip\"; filename*=UTF-8''${percentEncode("$name.zip")}"
        )
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun streamCategoryIntoZip(zos: ZipOutputStream, kind: String, path: String) {
        val context = MorselinkServices.appContext
        if (kind == "apps") {
            for (app in cachedApps()) {
                try {
                    val f = File(app.apkPath)
                    if (!f.exists()) continue
                    zos.putNextEntry(ZipEntry(ZipUtil.sanitize(app.label + ".apk")))
                    FileInputStream(f).use { input -> copyStreams(input, zos) }
                    zos.closeEntry()
                } catch (_: Exception) {
                }
            }
            return
        }
        val category = when (kind) {
            "photos" -> MediaCategory.PHOTOS
            "videos" -> MediaCategory.VIDEOS
            "music" -> MediaCategory.MUSIC
            "downloads", "files" -> MediaCategory.DOWNLOADS
            else -> MediaCategory.PHOTOS
        }
        var offset = 0
        val pageSize = 200
        while (true) {
            val page = MediaLibrary.page(category, offset, pageSize, com.morselink.app.core.media.SortKey.DATE, true, null)
            if (page.isEmpty()) break
            for (item in page) {
                if (path.isNotBlank()) {
                    val container = item.path ?: ""
                    if (!container.contains("/$path/") && !container.endsWith("/$path")) continue
                }
                try {
                    val input = ZipUtil.openStream(context, item.uri) ?: continue
                    zos.putNextEntry(ZipEntry(ZipUtil.sanitize(item.name)))
                    input.use { copyStreams(it, zos) }
                    zos.closeEntry()
                } catch (e: IOException) {
                    throw e
                } catch (_: Exception) {
                    // skip this file, keep going
                }
            }
            if (page.size < pageSize) break
            offset += pageSize
        }
    }

    private fun copyStreams(input: InputStream, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    private fun percentEncode(s: String): String {
        return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }

    // ---------------- uploads (chunked, resumable) ----------------

    private fun apiUploadStatus(session: IHTTPSession): Response {
        val uploadId = session.parameters["id"]?.firstOrNull() ?: return notFound()
        val part = uploadParts[uploadId]
        return noStoreJson(
            newJson(
                Response.Status.OK,
                JSONObject().put("received", part?.length() ?: 0L)
            )
        )
    }

    private fun upload(session: IHTTPSession): Response {
        activeOperations++
        try {
            val headers = session.headers ?: return badRequest("no headers")
            val uploadId = headers["x-upload-id"] ?: return badRequest("missing upload id")
            val fileName = try {
                URLDecoder.decode(headers["x-file-name"] ?: "file", "UTF-8")
            } catch (_: Exception) {
                "file"
            }
            val totalSize = headers["x-total-size"]?.toLongOrNull()
            val offset = headers["x-chunk-offset"]?.toLongOrNull() ?: 0L
            val length = headers["content-length"]?.toLongOrNull() ?: 0L

            val dir = File(MorselinkServices.appContext.cacheDir, "incoming").apply { mkdirs() }
            val part = uploadParts.getOrPut(uploadId) { File(dir, "upload-${uploadId.hashCode()}.part") }

            if (offset != part.length()) {
                // Client and server offsets diverged: report the real offset so the
                // client resyncs (true offset-based resume, spec Section 10.4/11.4).
                consumeBody(session, length)
                return noStoreJson(
                    newJson(
                        Response.Status.CONFLICT,
                        JSONObject().put("received", part.length()).put("error", "offset mismatch")
                    )
                )
            }

            val input = session.inputStream
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            FileOutputStream(part, true).use { out ->
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val n = input.read(buffer, 0, toRead)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    remaining -= n
                }
                out.flush()
            }

            val written = part.length()
            if (totalSize != null && totalSize > 0 && written >= totalSize) {
                // Complete: finalize through the shared destination path.
                val mime = MediaLibrary.guessMime(fileName)
                val policy = AppServices.prefs.conflictPolicy
                val result = runBlocking {
                    Destinations.finalize(
                        fileName = fileName,
                        relativePath = null,
                        mime = mime,
                        tempFile = part,
                        policy = if (policy == com.morselink.app.core.model.ConflictPolicy.ALWAYS_ASK) {
                            com.morselink.app.core.model.ConflictPolicy.ALWAYS_KEEP_BOTH
                        } else policy
                    ) { _, _ -> ConflictDecision.KEEP_BOTH }
                }
                uploadParts.remove(uploadId)
                return noStoreJson(
                    newJson(
                        Response.Status.OK,
                        JSONObject()
                            .put("done", true)
                            .put("ok", result.status == FinalResult.Status.SAVED)
                            .put("bytesWritten", written)
                            .put("path", result.finalPath ?: "")
                            .put("status", result.status.name)
                    )
                )
            }
            return noStoreJson(
                newJson(
                    Response.Status.OK,
                    JSONObject().put("done", false).put("bytesWritten", written)
                )
            )
        } finally {
            activeOperations--
        }
    }

    private fun consumeBody(session: IHTTPSession, length: Long) {
        try {
            val input = session.inputStream
            var remaining = length
            val buf = ByteArray(16 * 1024)
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                remaining -= n
            }
        } catch (_: Exception) {
        }
    }

    private fun badRequest(msg: String): Response =
        newJson(Response.Status.BAD_REQUEST, JSONObject().put("error", msg)).noStore()

    // ---------------- range support ----------------

    private fun rangedFile(session: IHTTPSession, file: File, name: String, mime: String): Response {
        val stream = try {
            FileInputStream(file)
        } catch (_: Exception) {
            return notFound()
        }
        return rangedStream(session, stream, file.length(), name, mime)
    }

    private fun rangedStream(session: IHTTPSession, stream: InputStream, size: Long, name: String, mime: String): Response {
        val rangeHeader = session.headers?.get("range")
        val disposition = "attachment; filename=\"$name\"; filename*=UTF-8''${percentEncode(name)}"
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").split("-")
            val start = spec.getOrNull(0)?.toLongOrNull() ?: 0L
            var end = spec.getOrNull(1)?.toLongOrNull() ?: (size - 1)
            if (end >= size) end = size - 1
            if (start < 0 || start > end || start >= size) {
                try {
                    stream.close()
                } catch (_: Exception) {
                }
                val resp = newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "range not satisfiable"
                )
                resp.addHeader("Content-Range", "bytes */$size")
                return resp.noStore()
            }
            try {
                var toSkip = start
                while (toSkip > 0) {
                    val skipped = stream.skip(toSkip)
                    if (skipped <= 0) break
                    toSkip -= skipped
                }
            } catch (_: Exception) {
            }
            val length = end - start + 1
            val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, stream, length)
            response.addHeader("Content-Range", "bytes $start-$end/$size")
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Disposition", disposition)
            return response.noStore()
        }
        val response = newFixedLengthResponse(Response.Status.OK, mime, stream, size)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Disposition", disposition)
        return response.noStore()
    }

    private fun serveFile(file: File, mime: String): Response {
        return try {
            newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length()).noStore()
        } catch (_: Exception) {
            notFound()
        }
    }
}
