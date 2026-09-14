package com.morselink.app.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.morselink.app.BuildConfig
import com.morselink.app.MainActivity
import com.morselink.app.OnboardingActivity
import com.morselink.app.R
import com.morselink.app.core.data.Prefs
import com.morselink.app.core.logging.LogStore
import com.morselink.app.core.storage.SafStore
import com.morselink.app.core.ui.Ui
import com.morselink.app.core.util.Permissions
import com.morselink.app.databinding.FragmentSettingsBinding
import com.morselink.app.di.AppServices
import com.morselink.app.feature.help.HelpFragment
import java.io.File

/**
 * Settings (spec Section 10.12): profile, transfer defaults, appearance,
 * battery/OEM guidance, storage roots, logs, help.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        if (savedInstanceState == null) {
            childFragmentManager.commit {
                replace(R.id.settings_container, PrefsRoot())
            }
        }
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class PrefsRoot : PreferenceFragmentCompat() {

        private val treePicker =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    SafStore.persist(uri)
                    refreshStorageSummary()
                }
            }

        private val downloadPicker =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    AppServices.prefs.defaultDownloadDirUri = uri.toString()
                    findPreference<Preference>("download_location")?.summary =
                        SafStore.treeLabel(uri)
                }
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_root, rootKey)
            val prefs = AppServices.prefs

            // --- profile ---
            val deviceName = findPreference<EditTextPreference>("device_name")!!
            deviceName.text = prefs.deviceName
            deviceName.summary = prefs.deviceName
            deviceName.setOnPreferenceChangeListener { _, value ->
                val name = (value as? String)?.trim().orEmpty()
                if (name.isEmpty()) {
                    false
                } else {
                    prefs.deviceName = name
                    deviceName.summary = name
                    true
                }
            }

            val avatarColor = findPreference<Preference>("avatar_color")!!
            avatarColor.setOnPreferenceClickListener {
                showColorPicker()
                true
            }

            // --- transfer ---
            val downloadLocation = findPreference<Preference>("download_location")!!
            val defaultDir = prefs.defaultDownloadDirUri
            downloadLocation.summary = if (defaultDir != null) {
                SafStore.treeLabel(Uri.parse(defaultDir))
            } else {
                getString(R.string.pref_download_default)
            }
            downloadLocation.setOnPreferenceClickListener {
                try {
                    downloadPicker.launch(null)
                } catch (_: Exception) {
                    Toast.makeText(
                        requireContext(), R.string.state_failed, Toast.LENGTH_SHORT
                    ).show()
                }
                true
            }

            val conflict = findPreference<ListPreference>("conflict_policy")!!
            conflict.value = prefs.conflictPolicy.key
            conflict.setOnPreferenceChangeListener { _, value ->
                prefs.conflictPolicy = com.morselink.app.core.model.ConflictPolicy.fromKey(value as String)
                true
            }

            // --- appearance ---
            val theme = findPreference<ListPreference>("theme")!!
            theme.value = prefs.themeMode
            theme.setOnPreferenceChangeListener { _, value ->
                prefs.themeMode = value as String
                requireActivity().recreate()
                true
            }

            // --- sounds & notifications ---
            findPreference<SwitchPreferenceCompat>("sounds")!!.apply {
                isChecked = prefs.soundEffects
                setOnPreferenceChangeListener { _, v ->
                    prefs.soundEffects = v as Boolean; true
                }
            }
            findPreference<SwitchPreferenceCompat>("transfer_notifications")!!.apply {
                isChecked = prefs.transferNotifications
                setOnPreferenceChangeListener { _, v ->
                    prefs.transferNotifications = v as Boolean; true
                }
            }

            // --- battery ---
            findPreference<Preference>("battery_status")!!.apply {
                summary = if (Permissions.isIgnoringBatteryOptimizations(requireContext())) {
                    getString(R.string.pref_battery_status_optimized)
                } else {
                    getString(R.string.pref_battery_status_restricted)
                }
                setOnPreferenceClickListener {
                    requestBatteryExemption()
                    true
                }
            }
            findPreference<Preference>("oem_guidance")!!.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pref_oem_guidance)
                    .setMessage(getString(R.string.oem_guidance_body))
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
                true
            }

            // --- storage ---
            refreshStorageSummary()
            findPreference<Preference>("storage_roots")!!.setOnPreferenceClickListener {
                showStorageRoots()
                true
            }
            findPreference<SwitchPreferenceCompat>("all_files")!!.apply {
                isChecked = Permissions.hasAllFilesAccess(requireContext())
                setOnPreferenceChangeListener { _, _ ->
                    openAllFilesSettings()
                    false
                }
            }

            // --- logs ---
            findPreference<SwitchPreferenceCompat>("logging_enabled")!!.apply {
                isChecked = prefs.loggingEnabled
                setOnPreferenceChangeListener { _, v ->
                    prefs.loggingEnabled = v as Boolean; true
                }
            }
            findPreference<Preference>("open_log")!!.setOnPreferenceClickListener {
                openFragment(LogViewerFragment())
                true
            }
            findPreference<Preference>("export_log")!!.setOnPreferenceClickListener {
                exportLog()
                true
            }
            findPreference<Preference>("clear_log")!!.setOnPreferenceClickListener {
                LogStore.clear(requireContext())
                Toast.makeText(requireContext(), R.string.pref_log_cleared, Toast.LENGTH_SHORT).show()
                true
            }

            // --- help ---
            findPreference<Preference>("faq")!!.setOnPreferenceClickListener {
                openFragment(HelpFragment())
                true
            }
            findPreference<Preference>("doctor")!!.setOnPreferenceClickListener {
                openFragment(ConnectionDoctorFragment())
                true
            }
            findPreference<Preference>("replay_onboarding")!!.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), OnboardingActivity::class.java))
                true
            }
            findPreference<SwitchPreferenceCompat>("crash_reports")!!.apply {
                isChecked = prefs.crashLogsEnabled
                setOnPreferenceChangeListener { _, v ->
                    prefs.crashLogsEnabled = v as Boolean; true
                }
            }

            // --- about ---
            findPreference<Preference>("about")!!.apply {
                summary = "${getString(R.string.about_body)}\n${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            }
        }

        private fun openFragment(f: androidx.fragment.app.Fragment) {
            (activity as? MainActivity)?.openOverlay(f)
        }

        private fun showColorPicker() {
            val names = arrayOf(
                getString(R.string.color_indigo), getString(R.string.color_teal),
                getString(R.string.color_amber), getString(R.string.color_rose),
                getString(R.string.color_sky)
            )
            var selection = AppServices.prefs.avatarColorIndex
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_avatar_color)
                .setSingleChoiceItems(names, selection) { d, which ->
                    selection = which
                }
                .setPositiveButton(R.string.action_ok) { d, _ ->
                    AppServices.prefs.avatarColorIndex = selection
                    d.dismiss()
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
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
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
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + requireContext().packageName)))
            }
        }

        private fun refreshStorageSummary() {
            val roots = SafStore.persistedGrants()
            findPreference<Preference>("storage_roots")?.summary =
                if (roots.isEmpty()) getString(R.string.pref_storage_none)
                else roots.joinToString(", ") { SafStore.treeLabel(it) }
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
                        refreshStorageSummary()
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
                Toast.makeText(requireContext(), R.string.state_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
