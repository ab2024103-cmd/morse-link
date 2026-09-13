package com.morselink.app

import android.app.Application

class MorselinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        private var instance: MorselinkApp? = null
        fun get(): Application = requireNotNull(instance) { "MorselinkApp not initialized" }
    }
}
