package com.morselink.app.core.storage

import android.content.Context
import android.net.Uri
import com.morselink.app.core.model.TransferableFile
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** ZIP helpers shared by the folder-send flow and the Files tab. */
object ZipUtil {

    /**
     * Zips the given files into [out], preserving any relativePath components.
     * Entries larger than 4GB or archives with >65,535 entries are handled by
     * java.util.zip's automatic ZIP64 extensions.
     */
    fun zipFiles(context: Context, files: List<TransferableFile>, out: OutputStream, onProgress: (Long) -> Unit) {
        var written = 0L
        val zos = ZipOutputStream(out)
        zos.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION)
        for (f in files) {
            val entryName = f.relativePath ?: f.displayName
            try {
                val input = openStream(context, f.uri) ?: continue
                input.use { stream ->
                    val entry = ZipEntry(sanitize(entryName))
                    entry.time = System.currentTimeMillis()
                    zos.putNextEntry(entry)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        zos.write(buf, 0, n)
                        written += n
                        onProgress(written)
                    }
                    zos.closeEntry()
                }
            } catch (_: Exception) {
                // One file's failure never aborts the batch (spec Section 7.4).
            }
        }
        zos.finish()
        zos.flush()
    }

    fun openStream(context: Context, uriString: String): InputStream? {
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") FileInputStream(File(uri.path ?: return null))
            else context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        }
    }

    fun sanitize(name: String): String {
        val cleaned = name.replace('\\', '_').trimStart('/')
        return if (cleaned.isEmpty()) "file" else cleaned
    }

    /** Extracts a zip into [destDir]. Returns the number of extracted entries. */
    fun extract(zipFile: File, destDir: File, onProgress: (Int) -> Unit): Int {
        var count = 0
        destDir.mkdirs()
        ZipInputStream(FileInputStream(zipFile).buffered()).use { zis ->
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                try {
                    val name = sanitize(entry.name)
                    val target = File(destDir, name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        // Basic zip-slip guard
                        if (target.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                            FileOutputStream(target).use { fos ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zis.read(buf)
                                    if (n < 0) break
                                    fos.write(buf, 0, n)
                                }
                            }
                            count++
                            onProgress(count)
                        }
                    }
                } catch (_: Exception) {
                }
                zis.closeEntry()
            }
        }
        return count
    }

    /** Builds a temp zip for "Send as ZIP" of folders; returns the file. */
    fun createTempZip(context: Context, files: List<TransferableFile>): File? {
        return try {
            val dir = File(context.cacheDir, "outgoing").apply { mkdirs() }
            val out = File(dir, "morselink-${System.currentTimeMillis()}.zip")
            FileOutputStream(out).use { fos ->
                zipFiles(context, files, fos) { }
            }
            out
        } catch (_: Exception) {
            null
        }
    }
}
