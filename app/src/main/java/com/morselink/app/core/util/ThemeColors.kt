package com.morselink.app.core.util

import android.content.Context
import androidx.core.content.ContextCompat
import com.morselink.app.R

/**
 * Small helper so custom views (radar etc.) can follow palette without a
 * MaterialComponents theme dependency inside onDraw().
 */
object ThemeColors {
    fun accent(context: Context): Int =
        ContextCompat.getColor(context, R.color.primary)

    fun success(context: Context): Int =
        ContextCompat.getColor(context, R.color.status_success)

    fun error(context: Context): Int =
        ContextCompat.getColor(context, R.color.status_error)
}
