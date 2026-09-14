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
        if (AppServices.prefs.crashLogsEnabled) {
            LogStore.installCrashHandler(this)
        }
        TransferEngine.start(this)
        LogStore.i("MorseLink started (version ${BuildConfig.VERSION_NAME})")
    }

    companion object {
        @Volatile
        private var instance: MorselinkApp? = null
        fun get(): Application = requireNotNull(instance) { "MorselinkApp not initialized" }
    }
}
