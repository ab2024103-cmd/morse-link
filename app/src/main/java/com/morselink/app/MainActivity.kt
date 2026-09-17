package com.morselink.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.morselink.app.core.model.BatchSummary
import com.morselink.app.core.model.EngineEvent
import com.morselink.app.core.model.TransferDirection
import com.morselink.app.core.model.TransferItemState
import com.morselink.app.core.model.TransferableFile
import com.morselink.app.core.storage.ConflictDecision
import com.morselink.app.core.transfer.TransferEngine
import com.morselink.app.core.ui.Ui
import com.morselink.app.core.util.Fmt
import com.morselink.app.core.util.Integrity
import com.morselink.app.core.util.MorselinkServices
import com.morselink.app.databinding.ActivityMainBinding
import com.morselink.app.core.logging.LogStore
import com.morselink.app.di.AppServices
import com.morselink.app.feature.dashboard.DashboardFragment
import com.morselink.app.feature.filemanager.FileManagerFragment
import com.morselink.app.feature.history.HistoryFragment
import com.morselink.app.feature.onboarding.OnboardingActivity
import com.morselink.app.feature.settings.SettingsFragment
import com.morselink.app.feature.transfer.TransferFragment
import com.morselink.app.feature.webshare.WebShareFragment
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var journalPromptShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            switchTo(DashboardFragment(), "connect")
            if (!AppServices.prefs.onboardingDone) {
                startActivity(Intent(this, OnboardingActivity::class.java))
            }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            clearOverlays()
            when (item.itemId) {
                R.id.nav_connect -> switchTo(DashboardFragment(), "connect")
                R.id.nav_files -> switchTo(FileManagerFragment(), "files")
                R.id.nav_history -> switchTo(HistoryFragment(), "history")
                R.id.nav_settings -> switchTo(SettingsFragment(), "settings")
            }
            true
        }

        supportFragmentManager.addOnBackStackChangedListener {
            val overlayOpen = supportFragmentManager.backStackEntryCount > 0
            binding.bottomNav.isVisible = !overlayOpen
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                } else {
                    finish()
                }
            }
        })

        observeEngineEvents()
        observeWebShareApprovals()
        maybeOfferCrashReport()
        handleShareIntent(intent)

        if (!journalPromptShown) {
            journalPromptShown = true
            maybeOfferJournalResume()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun applyTheme() {
        val mode = when (AppServices.prefs.themeMode) {
            com.morselink.app.core.ui.PrefsTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            com.morselink.app.core.ui.PrefsTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun switchTo(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, fragment, tag)
            .commit()
    }

    fun clearOverlays() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
    }

    fun openOverlay(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun openTransferScreen(mode: TransferFragment.Mode) {
        openOverlay(TransferFragment.newInstance(mode), "transfer")
    }

    private fun openOverlay(fragment: Fragment, name: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, fragment)
            .addToBackStack(name)
            .commit()
    }

    /** Pops everything above the transfer screen (e.g. the picker). */
    fun clearOverlaysKeepTransfer() {
        val fm = supportFragmentManager
        if (fm.backStackEntryCount == 0) return
        val popped = fm.popBackStackImmediate("transfer", 0)
        if (!popped && fm.backStackEntryCount > 1) {
            // No named transfer entry below: plain back navigation.
            fm.popBackStackImmediate()
        }
    }

    // ---------------- share intents (spec Section 10.4) ----------------

    private fun handleShareIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val files = ArrayList<TransferableFile>()
        if (action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null) {
                files.add(toTransferable(uri, intent.type))
            }
        } else {
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (uris != null) {
                for (uri in uris) {
                    files.add(toTransferable(uri, intent.type))
                }
            }
        }
        if (files.isEmpty()) return
        TransferEngine.queueOutgoing(files)
        openTransferScreen(TransferFragment.Mode.SEND)
        Toast.makeText(this, getString(R.string.action_send) + ": " + files.size, Toast.LENGTH_SHORT).show()
    }

    private fun toTransferable(uri: Uri, type: String?): TransferableFile {
        val size = Integrity.fileLength(this, uri.toString())
        val name = queryName(uri) ?: "file"
        return TransferableFile(
            id = UUID.randomUUID().toString(),
            displayName = name,
            size = size,
            uri = uri.toString(),
            mime = type ?: "application/octet-stream"
        )
    }

    private fun queryName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else uri.lastPathSegment
                } else null
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    // ---------------- engine events ----------------

    /**
     * If the app crashed since the last visit, surface the report from the home
     * screen (b-settings): Settings itself may be what crashed, so the report
     * must be reachable without it.
     */
    private fun maybeOfferCrashReport() {
        val size = LogStore.crashFileSize()
        if (size > AppServices.prefs.lastSeenCrashSize) {
            AppServices.prefs.lastSeenCrashSize = size
            AlertDialog.Builder(this)
                .setTitle(R.string.crash_dialog_title)
                .setMessage(getString(R.string.crash_dialog_message))
                .setPositiveButton(R.string.crash_dialog_view) { d, _ ->
                    d.dismiss()
                    openOverlay(com.morselink.app.feature.settings.LogViewerFragment())
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    /** WebShare pairing consent (b1): each new browser must be accepted on the phone. */
    private fun observeWebShareApprovals() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    com.morselink.app.core.webshare.WebShareController.approvalRequests.collect { req ->
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.webshare_request_title)
                            .setMessage(getString(R.string.webshare_request_message, req.second))
                            .setPositiveButton(R.string.webshare_request_allow) { d, _ ->
                                d.dismiss()
                                com.morselink.app.core.webshare.WebShareController.respondApproval(req.first, true)
                            }
                            .setNegativeButton(R.string.webshare_request_deny) { d, _ ->
                                d.dismiss()
                                com.morselink.app.core.webshare.WebShareController.respondApproval(req.first, false)
                            }
                            .setOnCancelListener {
                                com.morselink.app.core.webshare.WebShareController.respondApproval(req.first, false)
                            }
                            .show()
                    }
                }
            }
        }
    }

    private fun observeEngineEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    TransferEngine.events.collect { event ->
                        handleEvent(event)
                    }
                }
            }
        }
    }

    private fun handleEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.ConsentRequested -> showConsentDialog(event)
            is EngineEvent.ConflictDecisionNeeded -> showConflictDialog(event)
            is EngineEvent.BatchCompleted -> showBatchSummary(event.summary)
            is EngineEvent.InfoToast -> Toast.makeText(this, event.text, Toast.LENGTH_LONG).show()
            is EngineEvent.PeerConnected -> {
                Toast.makeText(
                    this,
                    getString(R.string.dashboard_connected_to, event.name),
                    Toast.LENGTH_SHORT
                ).show()
                announce(getString(R.string.dashboard_connected_to, event.name))
            }
            is EngineEvent.PeerDisconnected -> {
                Toast.makeText(this, getString(R.string.transfer_waiting_peer), Toast.LENGTH_SHORT).show()
                announce(event.reason ?: "Connection lost")
            }
            is EngineEvent.ProgressAnnouncement -> announce(event.text)
            is EngineEvent.TransferDoneAnnouncement -> announce(event.text)
            is EngineEvent.ItemFinishedToast -> {
                // Deliberately quiet per-file: batch summary covers completion.
            }
        }
    }

    private fun announce(text: String) {
        findViewById<View>(android.R.id.content)?.announceForAccessibility(text)
    }

    private fun showConsentDialog(event: EngineEvent.ConsentRequested) {
        val transportLabel = when (event.transport) {
            com.morselink.app.core.model.TransportType.NEARBY_CONNECTIONS -> getString(R.string.transfer_via_nearby)
            com.morselink.app.core.model.TransportType.LAN_WIFI -> getString(R.string.transfer_via_lan)
            else -> "WebShare"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.consent_title)
            .setMessage(getString(R.string.consent_message, event.peerName) + "\n\n$transportLabel")
            .setPositiveButton(R.string.consent_accept) { d, _ ->
                d.dismiss()
                TransferEngine.respondConsent(event.requestId, true)
            }
            .setNegativeButton(R.string.consent_reject) { d, _ ->
                d.dismiss()
                TransferEngine.respondConsent(event.requestId, false)
            }
            .setOnCancelListener {
                TransferEngine.respondConsent(event.requestId, false)
            }
            .show()
    }

    private fun showConflictDialog(event: EngineEvent.ConflictDecisionNeeded) {
        val view = layoutInflater.inflate(R.layout.dialog_conflict, null)
        val applyAll = view.findViewById<android.widget.CheckBox>(R.id.apply_all)
        AlertDialog.Builder(this)
            .setTitle(R.string.conflict_title)
            .setMessage(getString(R.string.conflict_message, event.fileName))
            .setView(view)
            .setPositiveButton(R.string.conflict_overwrite) { d, _ ->
                d.dismiss()
                TransferEngine.respondConflict(
                    event.requestId,
                    TransferEngine.ConflictAnswer(ConflictDecision.OVERWRITE, applyAll.isChecked)
                )
            }
            .setNeutralButton(R.string.conflict_skip) { d, _ ->
                d.dismiss()
                TransferEngine.respondConflict(
                    event.requestId,
                    TransferEngine.ConflictAnswer(ConflictDecision.SKIP, applyAll.isChecked)
                )
            }
            .setNegativeButton(R.string.conflict_keep_both) { d, _ ->
                d.dismiss()
                TransferEngine.respondConflict(
                    event.requestId,
                    TransferEngine.ConflictAnswer(ConflictDecision.KEEP_BOTH, applyAll.isChecked)
                )
            }
            .setOnCancelListener {
                TransferEngine.respondConflict(
                    event.requestId,
                    TransferEngine.ConflictAnswer(ConflictDecision.KEEP_BOTH, false)
                )
            }
            .show()
    }

    private fun showBatchSummary(summary: BatchSummary) {
        val avg = if (summary.elapsedMs > 0) {
            Fmt.speed((summary.totalBytes / (summary.elapsedMs / 1000.0)).toLong())
        } else ""
        val message = getString(
            R.string.transfer_summary_line,
            summary.succeeded, summary.failed, summary.skipped
        ) + (if (summary.cancelled > 0) "\n+ ${summary.cancelled} cancelled" else "") +
            if (avg.isNotEmpty()) "\n${getString(R.string.transfer_summary_speed, avg)}" else ""
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.transfer_summary_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_done, null)
        if (summary.failed > 0) {
            builder.setNegativeButton(R.string.action_retry_failed) { d, _ ->
                d.dismiss()
                TransferEngine.retryAllFailed()
            }
        }
        builder.show()
    }

    // ---------------- journal resume (spec Section 8.10) ----------------

    private fun maybeOfferJournalResume() {
        if (!TransferEngine.hasJournalResume()) return
        val peer = TransferEngine.journalPeerName() ?: "a device"
        val items = com.morselink.app.di.AppServices.journal.snapshotItems()
        val remaining = items.count { !it.isTerminal }
        if (remaining == 0) {
            TransferEngine.discardJournal()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.journal_resume_title)
            .setMessage(getString(R.string.journal_resume_message, peer, remaining))
            .setPositiveButton(R.string.journal_resume) { d, _ ->
                d.dismiss()
                TransferEngine.restoreJournalItems()
                openTransferScreen(TransferFragment.Mode.SEND)
            }
            .setNegativeButton(R.string.action_discard) { d, _ ->
                d.dismiss()
                TransferEngine.discardJournal()
            }
            .setOnCancelListener {
                // Leave the journal intact: the user may resume later.
            }
            .show()
    }
}
