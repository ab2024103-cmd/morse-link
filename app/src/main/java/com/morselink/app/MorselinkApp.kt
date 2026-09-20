package com.morselink.app

import android.app.Application
import com.morselink.app.core.logging.LogStore
import com.morselink.app.core.transfer.TransferEngine
import com.morselink.app.core.util.MorselinkServices
import com.morselink.app.di.AppServices

class MorselinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        MorselinkServices.appContext = this
        AppServices.init(this)
        LogStore.init(this)
        LogStore.enabled = AppServices.prefs.loggingEnabled
        LogStore.installCrashHandler(this)
        TransferEngine.start(this)
        LogStore.i("MorseLink ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started")
        LogStore.i(
            "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
        )
        if (LogStore.hasCrashReports()) {
            LogStore.w("Previous run ended in a crash — see the crash-reports section in the log viewer")
        }
    }

    companion object {
        @Volatile
        private var instance: MorselinkApp? = null
        fun get(): Application = requireNotNull(instance) { "MorselinkApp not initialized" }
    }
}
