package com.morselink.app.core.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/** Integrity helpers: sha256 for whole files, CRC32 for chunk framing. */
object Integrity {

    fun sha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = inputStream.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return toHex(digest.digest())
    }

    fun sha256File(context: Context, uriString: String): String? {
        return try {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { sha256(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun crc32(bytes: ByteArray, length: Int): Long {
        val crc = CRC32()
        crc.update(bytes, 0, length)
        return crc.value
    }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    fun fileLength(context: Context, uriString: String): Long {
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") {
                File(uri.path ?: return 0L).length()
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }
}
