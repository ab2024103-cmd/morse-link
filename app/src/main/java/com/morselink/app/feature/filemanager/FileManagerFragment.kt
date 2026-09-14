package com.morselink.app.feature.filemanager

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.morselink.app.MainActivity
import com.morselink.app.R
import com.morselink.app.core.model.TransferableFile
import com.morselink.app.core.storage.SafStore
import com.morselink.app.core.storage.ZipUtil
import com.morselink.app.core.transfer.TransferEngine
import com.morselink.app.core.ui.Ui
import com.morselink.app.core.util.Fmt
import com.morselink.app.core.util.Permissions
import com.morselink.app.databinding.FragmentFilemanagerBinding
import com.morselink.app.di.AppServices
import com.morselink.app.feature.transfer.TransferFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * File manager + send picker — one implementation, not two lists (spec Section
 * 10.5). Exactly five tabs. The tab index drives both the indicator and the
 * dataset (ViewPager2 + mediator = single source of truth).
 */
class FileManagerFragment : Fragment() {

    private var _binding: FragmentFilemanagerBinding? = null
    private val binding get() = _binding!!

    var pickMode = false
        private set

    /** Search query applied to the visible page. */
    @Volatile
    var searchQuery: String = ""

    /** Sort settings shared with pages. */
    var sortKey: com.morselink.app.core.media.SortKey = com.morselink.app.core.media.SortKey.DATE
    var sortDescending = true

    fun notifySortChanged() {
        childFragmentManager.fragments.forEach { f ->
            (f as? MediaPageFragment)?.reload()
            (f as? FilesPageFragment)?.reload()
        }
    }

    fun notifySearchChanged() {
        childFragmentManager.fragments.forEach { f ->
            (f as? MediaPageFragment)?.reload()
            (f as? FilesPageFragment)?.reload()
        }
    }

    // SAF tree grant flow for the Files tab / picker
    private val treePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            SafStore.persist(uri)
            SafStore.addExtraRoot(uri)
            childFragmentManager.fragments.forEach { f ->
                (f as? FilesPageFragment)?.reload()
            }
            Toast.makeText(requireContext(), SafStore.treeLabel(uri), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFilemanagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickMode = arguments?.getBoolean("pickMode") ?: false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 5
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> MediaPageFragment.newInstance("photos")
                1 -> MediaPageFragment.newInstance("videos")
                2 -> MediaPageFragment.newInstance("music")
                3 -> MediaPageFragment.newInstance("apps")
                else -> FilesPageFragment.newInstance(pickMode)
            }
        }
        binding.pager.offscreenPageLimit = 4
        binding.pager.isUserInputEnabled = false

        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.files_photos)
                1 -> getString(R.string.files_videos)
                2 -> getString(R.string.files_music)
                3 -> getString(R.string.files_apps)
                else -> getString(R.string.files_files)
            }
        }.attach()

        binding.searchButton.setOnClickListener {
            val visible = binding.searchBar.visibility == View.VISIBLE
            binding.searchBar.visibility = if (visible) View.GONE else View.VISIBLE
            if (visible) {
                binding.searchInput.setText("")
                searchQuery = ""
                notifySearchChanged()
            }
        }
        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                notifySearchChanged()
            }
        })

        binding.sortButton.setOnClickListener { anchor -> showSortMenu(anchor) }
        binding.viewToggle.setImageResource(
            if (AppServices.prefs.mediaViewGrid) R.drawable.ic_list else R.drawable.ic_grid
        )
        binding.viewToggle.setOnClickListener {
            AppServices.prefs.mediaViewGrid = !AppServices.prefs.mediaViewGrid
            binding.viewToggle.setImageResource(
                if (AppServices.prefs.mediaViewGrid) R.drawable.ic_list else R.drawable.ic_grid
            )
            childFragmentManager.fragments.forEach { f ->
                (f as? MediaPageFragment)?.reload()
            }
        }

        binding.buttonSendSelection.setOnClickListener { sendSelection() }
        binding.buttonShareSelection.setOnClickListener { shareSelection() }
        binding.buttonDeleteSelection.setOnClickListener { deleteSelection() }
        binding.buttonClearSelection.setOnClickListener { SelectionState.clear() }
        binding.buttonMoreSelection.setOnClickListener { anchor -> showMoreMenu(anchor) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SelectionState.selected.collect { sel ->
                        if (sel.isEmpty()) {
                            binding.selectionBar.visibility = View.GONE
                        } else {
                            binding.selectionBar.visibility = View.VISIBLE
                            binding.buttonSendSelection.text =
                                getString(R.string.files_send_count, sel.size, Fmt.bytes(SelectionState.totalSize()))
                        }
                    }
                }
            }
        }
    }

    fun requestTreeAccess() {
        try {
            treePicker.launch(null)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), e.message ?: "SAF unavailable", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, R.string.files_sort_date)
        popup.menu.add(0, 2, 1, R.string.files_sort_name)
        popup.menu.add(0, 3, 2, R.string.files_sort_size)
        popup.menu.add(0, 4, 3, if (sortDescending) R.string.files_sort_descending else R.string.files_sort_ascending)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> sortKey = com.morselink.app.core.media.SortKey.DATE
                2 -> sortKey = com.morselink.app.core.media.SortKey.NAME
                3 -> sortKey = com.morselink.app.core.media.SortKey.SIZE
                4 -> sortDescending = !sortDescending
            }
            notifySortChanged()
            true
        }
        popup.show()
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, R.string.files_select_all)
        popup.setOnMenuItemClickListener {
            if (it.itemId == 1) {
                // Select-all applies to the current page's visible dataset
                val page = childFragmentManager.fragments.firstOrNull { f -> f is MediaPageFragment || f is FilesPageFragment }
                (page as? PageWithItems)?.selectAllVisible()
            }
            true
        }
        popup.show()
    }

    // ---------------- actions ----------------

    private fun sendSelection() {
        val selected = SelectionState.current()
        if (selected.isEmpty()) return
        val hasFolder = selected.any { it.isDirectory }
        if (hasFolder) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.transfer_folder_prompt_title)
                .setMessage(R.string.transfer_folder_prompt_message)
                .setPositiveButton(R.string.transfer_send_as_zip) { d, _ ->
                    d.dismiss()
                    sendAsZip(selected)
                }
                .setNegativeButton(R.string.transfer_send_as_files) { d, _ ->
                    d.dismiss()
                    sendAsFiles(selected)
                }
                .show()
        } else {
            queueFiles(selected.map { it.toTransferable() })
        }
    }

    private fun sendAsZip(selected: List<Selectable>) {
        val folders = selected.filter { it.isDirectory }
        val loose = selected.filter { !it.isDirectory }
        viewLifecycleOwner.lifecycleScope.launch {
            val progress = AlertDialog.Builder(requireContext())
                .setTitle(R.string.transfer_send_as_zip)
                .setMessage(R.string.files_loading)
                .setCancelable(false)
                .show()
            val result = withContext(Dispatchers.IO) {
                // Expand folders into transferables with relative paths, then zip.
                val toZip = ArrayList<TransferableFile>()
                for (item in folders) {
                    expandFolder(item, "", toZip)
                }
                for (item in loose) {
                    toZip.add(item.toTransferable(item.relativePath))
                }
                ZipUtil.createTempZip(requireContext(), toZip)
            }
            progress.dismiss()
            val zip = result
            if (zip == null) {
                Toast.makeText(requireContext(), R.string.state_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val files = ArrayList<TransferableFile>()
            files.add(
                TransferableFile(
                    id = java.util.UUID.randomUUID().toString(),
                    displayName = "morselink-files.zip",
                    size = zip.length(),
                    uri = Uri.fromFile(zip).toString(),
                    mime = "application/zip",
                    sourceKind = "file"
                )
            )
            queueFiles(files)
        }
    }

    private fun expandFolder(item: Selectable, prefix: String, out: MutableList<TransferableFile>) {
        if (item.kind == "folder_saf" || item.uri.startsWith("content")) {
            val uri = Uri.parse(item.uri)
            val children = SafStore.listChildren(requireContext(), uri) ?: return
            for (child in children.sortedBy { it.name.lowercase() }) {
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) {
                    expandFolder(
                        Selectable(
                            key = child.uri.toString(),
                            name = child.name,
                            size = 0,
                            uri = child.uri.toString(),
                            mime = child.mime,
                            isDirectory = true,
                            kind = "folder_saf"
                        ),
                        rel, out
                    )
                } else {
                    out.add(
                        TransferableFile(
                            id = java.util.UUID.randomUUID().toString(),
                            displayName = child.name,
                            size = child.size,
                            uri = child.uri.toString(),
                            mime = child.mime,
                            relativePath = rel,
                            sourceKind = "document"
                        )
                    )
                }
            }
        } else {
            val dir = File(Uri.parse(item.uri).path ?: return)
            val kids = dir.listFiles() ?: return
            for (child in kids.sortedBy { it.name.lowercase() }) {
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) {
                    expandFolder(
                        Selectable(
                            key = "file://" + child.absolutePath,
                            name = child.name,
                            size = 0,
                            uri = "file://" + child.absolutePath,
                            mime = "inode/dir",
                            isDirectory = true,
                            kind = "folder_plain"
                        ),
                        rel, out
                    )
                } else {
                    out.add(
                        TransferableFile(
                            id = java.util.UUID.randomUUID().toString(),
                            displayName = child.name,
                            size = child.length(),
                            uri = "file://" + child.absolutePath,
                            mime = MediaLibraryGuess.guess(child.name),
                            relativePath = rel,
                            sourceKind = "file"
                        )
                    )
                }
            }
        }
    }

    private fun sendAsFiles(selected: List<Selectable>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val files = withContext(Dispatchers.Default) {
                val out = ArrayList<TransferableFile>()
                for (item in selected) {
                    if (item.isDirectory) {
                        expandFolder(item, item.name, out)
                    } else {
                        out.add(item.toTransferable(item.relativePath))
                    }
                }
                out
            }
            queueFiles(files)
        }
    }

    /** Queue + advisory checks + navigation to the transfer screen. */
    fun queueFiles(files: List<TransferableFile>) {
        if (files.isEmpty()) return
        val total = files.sumOf { it.size }
        if (TransferEngine.batteryAdvisoryNeeded(total)) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.battery_warning_title)
                .setMessage(R.string.battery_warning_message)
                .setPositiveButton(R.string.action_proceed) { d, _ ->
                    d.dismiss()
                    doQueue(files)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        } else {
            doQueue(files)
        }
    }

    private fun doQueue(files: List<TransferableFile>) {
        maybeRequestNotificationPermission()
        maybeOfferBatteryExemptionOnce()
        TransferEngine.queueOutgoing(files)
        SelectionState.clear()
        val main = activity as? MainActivity ?: return
        if (pickMode) {
            // Return to the transfer screen that opened the picker.
            main.clearOverlaysKeepTransfer()
        } else {
            main.openTransferScreen(TransferFragment.Mode.SEND)
        }
    }

    private var notifPermissionAsked = false

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || notifPermissionAsked) return
        notifPermissionAsked = true
        val missing = Permissions.notificationPermission(requireContext())
        if (missing.isEmpty()) return
        requestPermissions(missing.toTypedArray(), 4102)
    }

    private fun maybeOfferBatteryExemptionOnce() {
        val prefs = AppServices.prefs
        if (prefs.batteryPromptShown) return
        if (Permissions.isIgnoringBatteryOptimizations(requireContext())) {
            prefs.batteryPromptShown = true
            return
        }
        prefs.batteryPromptShown = true
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pref_battery)
            .setMessage(R.string.pref_battery_status_optimized)
            .setPositiveButton(R.string.pref_battery_request) { d, _ ->
                d.dismiss()
                requestBatteryExemption()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun requestBatteryExemption() {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + requireContext().packageName)
            )
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun shareSelection() {
        val selected = SelectionState.current().filter { !it.isDirectory }
        if (selected.isEmpty()) return
        try {
            val uris = ArrayList<Uri>(selected.map { Uri.parse(it.uri) })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            intent.type = "*/*"
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), e.message ?: "share failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteSelection() {
        val selected = SelectionState.current()
        if (selected.isEmpty()) return
        Ui.confirm(
            requireContext(),
            getString(R.string.files_delete_confirm_title),
            getString(R.string.files_delete_confirm_message)
        ) {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    for (item in selected) {
                        try {
                            val uri = Uri.parse(item.uri)
                            when {
                                uri.scheme == "content" ->
                                    requireContext().contentResolver.delete(uri, null, null)
                                uri.scheme == "file" -> File(uri.path ?: continue).delete()
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                SelectionState.clear()
                Toast.makeText(requireContext(), R.string.action_done, Toast.LENGTH_SHORT).show()
                childFragmentManager.fragments.forEach { f ->
                    (f as? MediaPageFragment)?.reload()
                    (f as? FilesPageFragment)?.reload()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(pickMode: Boolean): FileManagerFragment {
            val f = FileManagerFragment()
            f.arguments = Bundle().apply { putBoolean("pickMode", pickMode) }
            return f
        }
    }
}

/** Contract pages implement so select-all and reload work uniformly. */
interface PageWithItems {
    fun selectAllVisible()
}

/** Tiny indirection to keep this file's imports light. */
object MediaLibraryGuess {
    fun guess(name: String): String =
        com.morselink.app.core.media.MediaLibrary.guessMime(name)
}
