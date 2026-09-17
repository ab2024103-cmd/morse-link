package com.morselink.app.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.morselink.app.BuildConfig
import com.morselink.app.MainActivity
import com.morselink.app.feature.onboarding.OnboardingActivity
import com.morselink.app.R
import com.morselink.app.core.data.Prefs
import com.morselink.app.core.logging.LogStore
import com.morselink.app.core.storage.SafStore
import com.morselink.app.core.util.Permissions
import com.morselink.app.databinding.FragmentSettingsBinding
import com.morselink.app.di.AppServices
import com.morselink.app.feature.help.HelpFragment
import java.io.File

/**
 * Settings (spec Section 10.12). Hand-rolled screen: the previous
 * PreferenceFragmentCompat-based UI crashed on some devices even with a
 * correct preferenceTheme, so this uses only the plain widgets every other
 * screen in the app already uses.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val prefs: Prefs get() = AppServices.prefs

    private val treePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                SafStore.persist(uri)
                refresh()
            }
        }

    private val downloadPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                prefs.defaultDownloadDirUri = uri.toString()
                refresh()
            }
        }

    // ---------------- row model ----------------

    private sealed class Row {
        class Header(val title: String) : Row()
        class Toggle(
            val title: String,
            val summary: String?,
            val isChecked: () -> Boolean,
            val onChange: (Boolean) -> Unit
        ) : Row()

        class Click(
            val title: String,
            val summary: () -> String?,
            val onClick: () -> Unit
        ) : Row()

        class Info(val title: String, val summary: String) : Row()
    }

    private fun buildRows(): List<Row> {
        val rows = ArrayList<Row>()
        val ctx = context ?: return rows

        // --- profile ---
        rows.add(Row.Header(getString(R.string.pref_profile)))
        rows.add(Row.Click(getString(R.string.pref_device_name), { prefs.deviceName }) {
            editDeviceName()
        })
        rows.add(Row.Click(getString(R.string.pref_avatar_color), { "" }) { showColorPicker() })

        // --- transfer ---
        rows.add(Row.Header(getString(R.string.pref_transfer)))
        rows.add(Row.Click(getString(R.string.pref_download_location), { downloadSummary() }) {
            try {
                downloadPicker.launch(null)
            } catch (_: Exception) {
                toast(R.string.state_failed)
            }
        })
        rows.add(Row.Click(getString(R.string.pref_conflict_policy), { conflictLabel() }) {
            pickConflictPolicy()
        })

        // --- appearance ---
        rows.add(Row.Header(getString(R.string.pref_appearance)))
        rows.add(Row.Click(getString(R.string.pref_theme), { themeLabel() }) { pickTheme() })

        // --- sounds & notifications ---
        rows.add(Row.Header(getString(R.string.pref_sounds)))
        rows.add(Row.Toggle(getString(R.string.pref_sounds), getString(R.string.pref_sounds_summary), { prefs.soundEffects }) {
            prefs.soundEffects = it
        })
        rows.add(Row.Toggle(getString(R.string.pref_notifications), getString(R.string.pref_notifications_summary), { prefs.transferNotifications }) {
            prefs.transferNotifications = it
        })

        // --- battery ---
        rows.add(Row.Header(getString(R.string.pref_battery)))
        rows.add(Row.Click(getString(R.string.pref_battery_request), { batterySummary() }) {
            requestBatteryExemption()
        })
        rows.add(Row.Click(getString(R.string.pref_oem_guidance), { "" }) { showOemGuidance() })
        rows.add(Row.Info(getString(R.string.pref_notifications), getString(R.string.pref_battery_foreground_note)))

        // --- storage ---
        rows.add(Row.Header(getString(R.string.pref_storage_access)))
        rows.add(Row.Click(getString(R.string.pref_storage_granted), { storageSummary() }) {
            showStorageRoots()
        })
        rows.add(Row.Click(getString(R.string.pref_all_files), { allFilesSummary() }) {
            openAllFilesSettings()
        })

        // --- logs ---
        rows.add(Row.Header(getString(R.string.pref_logs)))
        rows.add(Row.Toggle(getString(R.string.pref_logging_toggle), getString(R.string.pref_logging_toggle_summary), { prefs.loggingEnabled }) {
            prefs.loggingEnabled = it
        })
        rows.add(Row.Click(getString(R.string.pref_log_open), { "" }) {
            openFragment(LogViewerFragment())
        })
        rows.add(Row.Click(getString(R.string.pref_log_export), { "" }) { exportLog() })
        rows.add(Row.Click(getString(R.string.pref_log_clear), { "" }) {
            LogStore.clear(ctx)
            toast(R.string.pref_log_cleared)
            refresh()
        })

        // --- help ---
        rows.add(Row.Header(getString(R.string.pref_help)))
        rows.add(Row.Click(getString(R.string.pref_faq), { "" }) {
            openFragment(HelpFragment())
        })
        rows.add(Row.Click(getString(R.string.pref_doctor), { "" }) {
            openFragment(ConnectionDoctorFragment())
        })
        rows.add(Row.Click(getString(R.string.pref_replay_onboarding), { "" }) {
            startActivity(Intent(ctx, OnboardingActivity::class.java))
        })
        rows.add(Row.Toggle(getString(R.string.pref_crash_reports), getString(R.string.pref_crash_reports_summary), { prefs.crashLogsEnabled }) {
            prefs.crashLogsEnabled = it
        })

        // --- about ---
        rows.add(Row.Header(getString(R.string.pref_about)))
        rows.add(Row.Info(
            getString(R.string.pref_about),
            "${getString(R.string.about_body)}\n${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        ))
        return rows
    }

    // ---------------- lifecycle ----------------

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.settingsList.layoutManager = LinearLayoutManager(requireContext())
        binding.settingsList.adapter = SettingsAdapter(buildRows())
    }

    override fun onResume() {
        super.onResume()
        // Battery / all-files states may have changed in system settings.
        if (_binding != null) refresh()
    }

    private fun refresh() {
        val adapter = binding.settingsList.adapter as? SettingsAdapter ?: return
        adapter.update(buildRows())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------------- adapter ----------------

    private inner class SettingsAdapter(private var rows: List<Row>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        fun update(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> 0
            else -> 1
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> HeaderHolder(inflater.inflate(R.layout.item_setting_header, parent, false))
                else -> RowHolder(inflater.inflate(R.layout.item_setting_row, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> {
                    holder as HeaderHolder
                    holder.title.text = row.title
                }
                is Row.Toggle -> {
                    holder as RowHolder
                    bindRow(holder, row.title, row.summary, true, row.isChecked())
                    holder.switch.isChecked = row.isChecked()
                    holder.itemView.setOnClickListener {
                        row.onChange(!row.isChecked())
                        refresh()
                    }
                }
                is Row.Click -> {
                    holder as RowHolder
                    bindRow(holder, row.title, row.summary(), false, false)
                    holder.itemView.setOnClickListener { row.onClick() }
                }
                is Row.Info -> {
                    holder as RowHolder
                    bindRow(holder, row.title, row.summary, false, false)
                    holder.itemView.isClickable = false
                }
            }
        }

        private fun bindRow(
            holder: RowHolder,
            title: String,
            summary: String?,
            showSwitch: Boolean,
            checked: Boolean
        ) {
            holder.title.text = title
            if (summary.isNullOrBlank()) {
                holder.summary.visibility = View.GONE
            } else {
                holder.summary.visibility = View.VISIBLE
                holder.summary.text = summary
            }
            holder.switch.visibility = if (showSwitch) View.VISIBLE else View.GONE
            holder.switch.isChecked = checked
            holder.itemView.isClickable = true
        }

        private inner class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.header_title)
        }

        private inner class RowHolder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.row_title)
            val summary: TextView = v.findViewById(R.id.row_summary)
            val switch: androidx.appcompat.widget.SwitchCompat = v.findViewById(R.id.row_switch)
        }
    }

    // ---------------- summaries ----------------

    private fun downloadSummary(): String {
        val dir = prefs.defaultDownloadDirUri
        return if (dir != null) SafStore.treeLabel(Uri.parse(dir))
        else getString(R.string.pref_download_default)
    }

    private fun conflictLabel(): String {
        val entries = resources.getStringArray(R.array.conflict_entries)
        val values = resources.getStringArray(R.array.conflict_values)
        val idx = values.indexOf(prefs.conflictPolicy.key)
        return if (idx >= 0) entries[idx] else entries.first()
    }

    private fun themeLabel(): String {
        val entries = resources.getStringArray(R.array.theme_entries)
        val values = resources.getStringArray(R.array.theme_values)
        val idx = values.indexOf(prefs.themeMode)
        return if (idx >= 0) entries[idx] else entries.first()
    }

    private fun batterySummary(): String =
        if (Permissions.isIgnoringBatteryOptimizations(requireContext())) {
            getString(R.string.pref_battery_status_optimized)
        } else {
            getString(R.string.pref_battery_status_restricted)
        }

    private fun storageSummary(): String {
        val roots = SafStore.persistedGrants()
        return if (roots.isEmpty()) getString(R.string.pref_storage_none)
        else roots.joinToString(", ") { SafStore.treeLabel(it) }
    }

    private fun allFilesSummary(): String = getString(R.string.pref_all_files_summary)

    // ---------------- dialogs & actions ----------------

    private fun editDeviceName() {
        val input = EditText(requireContext())
        input.setText(prefs.deviceName)
        input.hint = getString(R.string.pref_device_name)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pref_device_name)
            .setView(input)
            .setPositiveButton(R.string.action_ok) { d, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) prefs.deviceName = name
                d.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showColorPicker() {
        val names = arrayOf(
            getString(R.string.color_indigo), getString(R.string.color_teal),
            getString(R.string.color_amber), getString(R.string.color_rose),
            getString(R.string.color_sky)
        )
        var selection = prefs.avatarColorIndex
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pref_avatar_color)
            .setSingleChoiceItems(names, selection) { _, which -> selection = which }
            .setPositiveButton(R.string.action_ok) { d, _ ->
                prefs.avatarColorIndex = selection
                d.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun pickConflictPolicy() {
        val entries = resources.getStringArray(R.array.conflict_entries)
        val values = resources.getStringArray(R.array.conflict_values)
        val current = values.indexOf(prefs.conflictPolicy.key).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pref_conflict_policy)
            .setSingleChoiceItems(entries, current) { d, which ->
                prefs.conflictPolicy =
                    com.morselink.app.core.model.ConflictPolicy.fromKey(values[which])
                d.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun pickTheme() {
        val entries = resources.getStringArray(R.array.theme_entries)
        val values = resources.getStringArray(R.array.theme_values)
        val current = values.indexOf(prefs.themeMode).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pref_theme)
            .setSingleChoiceItems(entries, current) { d, which ->
                prefs.themeMode = values[which]
                d.dismiss()
                if (isAdded) requireActivity().recreate()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showOemGuidance() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pref_oem_guidance)
            .setMessage(getString(R.string.oem_guidance_body))
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun showStorageRoots() {
        val roots = SafStore.persistedGrants()
        val labels = roots.map { SafStore.treeLabel(it) }.toTypedArray()
        val uris = roots.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pref_storage_granted)
            .setItems(labels + arrayOf(getString(R.string.pref_storage_add))) { d, which ->
                if (which < uris.size) {
                    SafStore.removeAccess(uris[which])
                    refresh()
                    d.dismiss()
                } else {
                    try {
                        treePicker.launch(null)
                    } catch (_: Exception) {
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + requireContext().packageName)
                    )
                )
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + requireContext().packageName)
                    )
                )
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    openAppDetails()
                }
            }
        } else {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + requireContext().packageName)
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun exportLog() {
        try {
            val file = LogStore.exportFile(requireContext())
                ?: File(requireContext().cacheDir, "morselink_log.txt")
            if (!file.exists()) throw IllegalStateException("no log")
            val uri = FileProvider.getUriForFile(
                requireContext(), requireContext().packageName + ".fileprovider", file
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    getString(R.string.pref_log_export)
                )
            )
        } catch (_: Exception) {
            toast(R.string.state_failed)
        }
    }

    private fun openFragment(f: androidx.fragment.app.Fragment) {
        (activity as? MainActivity)?.openOverlay(f)
    }

    private fun toast(res: Int) {
        Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
    }
}
