package com.morselink.app.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.morselink.app.R
import com.morselink.app.di.AppServices
import com.morselink.app.core.util.MorselinkServices

/** Shared UI helpers: avatar tiles, file icons, dialogs, one-time tips. */
object Ui {

    val AVATAR_COLORS = intArrayOf(
        0xFF1FA36B.toInt(),
        0xFF2D9CDB.toInt(),
        0xFFE0A030.toInt(),
        0xFF8E7CC3.toInt(),
        0xFFE5484D.toInt(),
        0xFF5F8B4B.toInt(),
        0xFFB07D2B.toInt(),
        0xFF4B7BB0.toInt()
    )

    fun avatarDrawable(context: Context, name: String, colorIndex: Int): Drawable {
        val color = AVATAR_COLORS[Math.floorMod(colorIndex, AVATAR_COLORS.size)]
        val size = context.resources.getDimensionPixelSize(R.dimen.avatar_size)
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * 0.42f
        paint.isFakeBoldText = true
        val letter = if (name.isEmpty()) "?" else name.substring(0, 1).uppercase()
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, size / 2f, y, paint)
        return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
    }

    /** File icon resource by mime/name. */
    fun iconForFile(name: String, mime: String?): Int {
        val lower = name.lowercase()
        val m = mime ?: ""
        return when {
            m.startsWith("image/") -> R.drawable.ic_cat_photos
            m.startsWith("video/") -> R.drawable.ic_cat_videos
            m.startsWith("audio/") -> R.drawable.ic_cat_music
            m == "application/vnd.android.package-archive" || lower.endsWith(".apk") -> R.drawable.ic_cat_apps
            lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") ||
                lower.endsWith(".tar") || lower.endsWith(".gz") -> R.drawable.ic_cat_archives
            lower.endsWith(".epub") || lower.endsWith(".mobi") -> R.drawable.ic_cat_ebooks
            m.startsWith("text/") || m == "application/pdf" || m.contains("word") ||
                m.contains("excel") || m.contains("powerpoint") || m.contains("presentation") ||
                m.contains("sheet") -> R.drawable.ic_cat_documents
            else -> R.drawable.ic_cat_files
        }
    }

    /** One-time dismissible tip (spec Section 18.4). */
    fun maybeShowTip(fragment: Fragment, key: String, text: String) {
        if (AppServices.prefs.tipShown(key)) return
        AppServices.prefs.markTipShown(key)
        val view = fragment.view ?: return
        com.google.android.material.snackbar.Snackbar.make(view, text, 6000).show()
    }

    fun confirm(context: Context, title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.action_yes) { d, _ ->
                d.dismiss()
                onYes()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    fun input(context: Context, title: String, initial: String, onOk: (String) -> Unit) {
        val input = android.widget.EditText(context)
        input.setText(initial)
        input.setSelection(initial.length)
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(R.string.action_ok) { d, _ ->
                d.dismiss()
                onOk(input.text.toString().trim())
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    fun themeLabelRes(mode: String): Int = when (mode) {
        PrefsTheme.LIGHT -> R.string.theme_light
        PrefsTheme.DARK -> R.string.theme_dark
        else -> R.string.theme_system
    }
}

/** Small indirection so Ui doesn't import Prefs just for constants. */
object PrefsTheme {
    const val LIGHT = "light"
    const val DARK = "dark"
    const val SYSTEM = "system"
}
