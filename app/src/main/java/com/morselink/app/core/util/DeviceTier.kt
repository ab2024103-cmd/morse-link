package com.morselink.app.core.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Device performance tier computed at startup (spec Section 13). Affects only
 * cosmetic, performance-tunable, or verification-thoroughness aspects; never a
 * core feature.
 */
object DeviceTier {

    val isLowEndDevice: Boolean by lazy {
        Build.VERSION.SDK_INT < 23 || run {
            val am = MorselinkServices.activityManager
            am != null && am.isLowRamDevice
        } || Runtime.getRuntime().availableProcessors() <= 2
    }

    /** Page size for paginated list loading. */
    val pageSize: Int get() = if (isLowEndDevice) 60 else 200

    /** Whether full sha256 verification runs post-transfer on Nearby. */
    val fullHashVerify: Boolean get() = !isLowEndDevice

    /** Files larger than this are not pre-hashed before sending (size-check only). */
    const val PREHASH_LIMIT: Long = 256L * 1024 * 1024
}

/** Small indirection so DeviceTier can read system services without a context param. */
object MorselinkServices {
    lateinit var appContext: Context

    val activityManager: ActivityManager?
        get() = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
}
