package com.morselink.app.di

import android.content.Context
import com.morselink.app.core.data.HistoryStore
import com.morselink.app.core.data.JournalStore
import com.morselink.app.core.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph. Process-scoped singletons owned here, never by a
 * fragment or activity (spec Section 8.1).
 */
object AppServices {
    lateinit var prefs: Prefs
    lateinit var history: HistoryStore
    lateinit var journal: JournalStore

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = Prefs(context)
        history = HistoryStore(context)
        journal = JournalStore(context)
    }
}
