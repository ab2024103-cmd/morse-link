package com.morselink.app.feature.filemanager

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.morselink.app.R
import com.morselink.app.core.media.MediaCategory
import com.morselink.app.core.media.MediaItem
import com.morselink.app.core.media.MediaLibrary
import com.morselink.app.core.storage.SafStore
import com.morselink.app.core.ui.Ui
import com.morselink.app.core.util.Fmt
import com.morselink.app.core.util.Permissions
import com.morselink.app.databinding.PageMediaBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Files tab: smart categories (indexed queries with real-time counts), folder
 * browsing over SAF grants / all-files access / pre-Q filesystem, and an
 * interactive address bar (spec Sections 10.5/10.6). Access-denied is distinct
 * from empty (spec Section 5.3).
 */
class FilesPageFragment : Fragment(), PageWithItems {

    private var _binding: PageMediaBinding? = null
    private val binding get() = _binding!!

    private sealed class BrowseState {
        object Root : BrowseState()
        data class Category(val category: MediaCategory) : BrowseState()
        data class SafFolder(val treeUri: Uri, val folderUri: Uri, val segments: List<String>) : BrowseState()
        data class PlainFolder(val dir: File, val segments: List<String>) : BrowseState()
    }

    private var state: BrowseState = BrowseState.Root
    private var pickMode = false
    private var categoryOffset = 0
    private var categoryReachedEnd = false
    private var categoryLoading = false
    private var categoryError = false
    private val categoryItems = ArrayList<MediaItem>()

    private val parentManager: FileManagerFragment?
        get() = parentFragment as? FileManagerFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickMode = arguments?.getBoolean("pickMode") ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PageMediaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.addressBar.visibility = View.GONE
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = FilesAdapter(emptyList())

        binding.buttonNewFolder.setOnClickListener { createFolder() }

        // Direct path entry: long-press the address bar (spec Section 10.6).
        binding.addressBar.setOnLongClickListener {
            val current = when (val s = state) {
                is BrowseState.SafFolder -> s.segments.joinToString("/")
                is BrowseState.PlainFolder -> s.dir.absolutePath
                else -> ""
            }
            Ui.input(requireContext(), getString(R.string.files_search_hint), current) { path ->
                if (path.isNotEmpty()) navigateByPath(path)
            }
            true
        }

        binding.grantButton.setOnClickListener {
            parentManager?.requestTreeAccess()
        }

        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val st = state
                if (st !is BrowseState.Category || categoryReachedEnd || isLoading()) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= lm.itemCount - 6) {
                    loadCategoryMore(st.category)
                }
            }
        })

        loadState()
    }

    fun reload() {
        loadState()
    }

    private fun loadState() {
        binding.grantButton.visibility = View.GONE
        binding.empty.visibility = View.GONE
        when (state) {
            is BrowseState.Root -> loadRoot()
            is BrowseState.Category -> loadCategory((state as BrowseState.Category).category)
            is BrowseState.SafFolder -> loadSafFolder(state as BrowseState.SafFolder)
            is BrowseState.PlainFolder -> loadPlainFolder(state as BrowseState.PlainFolder)
        }
    }

    // ---------------- root ----------------

    private class CatRow(
        val label: String,
        val icon: Int,
        val category: MediaCategory?
    )

    private fun loadRoot() {
        binding.addressBar.visibility = View.GONE
        binding.loading.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = ArrayList<Any>() // CatRow | Header | FolderEntry
            rows.add(Header(getString(R.string.files_smart_categories)))
            val categories = listOf(
                CatRow(getString(R.string.files_documents), R.drawable.ic_cat_documents, MediaCategory.DOCUMENTS),
                CatRow(getString(R.string.files_ebooks), R.drawable.ic_cat_ebooks, MediaCategory.EBOOKS),
                CatRow(getString(R.string.files_archives), R.drawable.ic_cat_archives, MediaCategory.ARCHIVES),
                CatRow(getString(R.string.files_apks), R.drawable.ic_cat_apps, MediaCategory.APK_FILES),
                CatRow(getString(R.string.files_large_files), R.drawable.ic_cat_large, MediaCategory.LARGE_FILES)
            )
            // Counts load asynchronously; show a loading placeholder per row
            // until the scan genuinely completes (never a hard 0 — spec 10.5).
            val counts = HashMap<String, Int>()
            for (c in categories) {
                rows.add(CatEntry(c.label, c.icon, -1, c.category))
            }
            rows.add(Header(getString(R.string.files_folders)))
            rows.add(FolderEntry(getString(R.string.files_download_folder), null, null))
            for (uri in SafStore.persistedGrants()) {
                rows.add(FolderEntry(SafStore.treeLabel(uri), uri, SafStore.treeRootDocumentUri(uri)))
            }
            rows.add(
                FolderEntry(
                    getString(R.string.files_internal_storage), null,
                    if (canBrowseInternal()) File("/storage/emulated/0") else LockedStorage
                )
            )
            rows.add(AddFolderEntry())
            withContext(Dispatchers.Main) {
                binding.loading.visibility = View.GONE
                binding.recycler.adapter = FilesAdapter(rows)
                // Fill counts in the background, updating the adapter rows.
                launch(Dispatchers.IO) {
                    for (c in categories) {
                        val n = MediaLibrary.count(c.category ?: continue)
                        counts[c.label] = n
                        withContext(Dispatchers.Main) {
                            (binding.recycler.adapter as? FilesAdapter)?.updateCount(c.label, n)
                        }
                    }
                }
            }
        }
    }

    /** Marker for the internal-storage row when all-files access is missing. */
    private object LockedStorage

    private fun canBrowseInternal(): Boolean {
        if (Permissions.hasAllFilesAccess(requireContext())) return true
        return Build.VERSION.SDK_INT < 29 &&
            Permissions.has(requireContext(), android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    // ---------------- category listing ----------------

    private fun loadCategory(category: MediaCategory) {
        binding.addressBar.visibility = View.VISIBLE
        renderBreadcrumb(listOf(getString(R.string.files_files), categoryLabel(category)))
        categoryOffset = 0
        categoryReachedEnd = false
        categoryItems.clear()
        loadCategoryMore(category)
    }

    private fun isLoading(): Boolean = categoryLoading || binding.loading.visibility == View.VISIBLE

    private fun loadCategoryMore(category: MediaCategory) {
        if (categoryLoading) return
        categoryLoading = true
        categoryError = false
        binding.loading.visibility = View.VISIBLE
        binding.empty.visibility = View.GONE
        val query = parentManager?.searchQuery?.takeIf { it.isNotEmpty() }
        val sortKey = parentManager?.sortKey ?: com.morselink.app.core.media.SortKey.DATE
        val descending = parentManager?.sortDescending ?: true
        viewLifecycleOwner.lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) {
                try {
                    MediaLibrary.page(category, categoryOffset, 100, sortKey, descending, query)
                } catch (e: Exception) {
                    com.morselink.app.core.logging.LogStore.e("Files category load failed", e)
                    null
                }
            }
            withContext(Dispatchers.Main) {
                categoryLoading = false
                binding.loading.visibility = View.GONE
                if (page == null) {
                    categoryError = true
                    binding.empty.visibility = View.VISIBLE
                    binding.empty.text = getString(R.string.files_load_failed)
                    binding.empty.setOnClickListener {
                        loadCategory(category)
                    }
                    return@withContext
                }
                categoryItems.addAll(page)
                categoryOffset += page.size
                categoryReachedEnd = page.size < 100
                binding.empty.visibility =
                    if (categoryItems.isEmpty()) View.VISIBLE else View.GONE
                if (categoryItems.isEmpty()) {
                    binding.empty.text = getString(R.string.history_empty)
                    binding.empty.setOnClickListener(null)
                }
                val rows = ArrayList<Any>(
                    categoryItems.map { m ->
                        FileEntry(m.name, m.size, m.mime, m.uri)
                    }
                )
                binding.recycler.adapter = FilesAdapter(rows)
            }
        }
    }

    private fun categoryLabel(category: MediaCategory): String = when (category) {
        MediaCategory.DOCUMENTS -> getString(R.string.files_documents)
        MediaCategory.EBOOKS -> getString(R.string.files_ebooks)
        MediaCategory.ARCHIVES -> getString(R.string.files_archives)
        MediaCategory.APK_FILES -> getString(R.string.files_apks)
        MediaCategory.LARGE_FILES -> getString(R.string.files_large_files)
        else -> category.key
    }

    // ---------------- SAF folder ----------------

    private fun loadSafFolder(folder: BrowseState.SafFolder) {
        binding.addressBar.visibility = View.VISIBLE
        renderBreadcrumb(listOf(getString(R.string.files_files)) + folder.segments)
        binding.loading.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val children = withContext(Dispatchers.IO) {
                SafStore.listChildren(requireContext(), folder.folderUri)
            }
            withContext(Dispatchers.Main) {
                binding.loading.visibility = View.GONE
                if (children == null) {
                    // Access denied is NOT an empty folder (spec Section 5.3).
                    binding.empty.visibility = View.GONE
                    binding.grantButton.visibility = View.VISIBLE
                    binding.recycler.adapter = FilesAdapter(emptyList())
                    return@withContext
                }
                val rows = ArrayList<Any>()
                for (c in children) {
                    if (c.isDirectory) {
                        rows.add(FolderEntry(c.name, folder.treeUri, c.uri))
                    } else {
                        rows.add(FileEntry(c.name, c.size, c.mime, c.uri.toString()))
                    }
                }
                binding.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                binding.recycler.adapter = FilesAdapter(rows)
            }
        }
    }

    private fun loadPlainFolder(folder: BrowseState.PlainFolder) {
        binding.addressBar.visibility = View.VISIBLE
        renderBreadcrumb(listOf(getString(R.string.files_files)) + folder.segments)
        binding.loading.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val children = withContext(Dispatchers.IO) {
                try {
                    val files = folder.dir.listFiles()
                    files?.map { f ->
                        if (f.isDirectory) {
                            FolderEntry(f.name, null, f)
                        } else {
                            FileEntry(f.name, f.length(), MediaLibrary.guessMime(f.name), "file://" + f.absolutePath)
                        }
                    }?.sortedBy { it.javaClass.simpleName + it.toString() }
                } catch (_: Exception) {
                    null
                }
            }
            withContext(Dispatchers.Main) {
                binding.loading.visibility = View.GONE
                if (children == null) {
                    binding.empty.visibility = View.GONE
                    binding.grantButton.visibility = View.VISIBLE
                    binding.recycler.adapter = FilesAdapter(emptyList())
                    return@withContext
                }
                val dirs = children.filterIsInstance<FolderEntry>()
                val files = children.filterIsInstance<FileEntry>()
                val rows = ArrayList<Any>(dirs) + files
                binding.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                binding.recycler.adapter = FilesAdapter(rows)
            }
        }
    }

    /** Android 11+: browsing all of storage needs the All-files-access grant. */
    private fun promptAllFilesAccess() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.files_all_files_title)
            .setMessage(R.string.files_all_files_message)
            .setPositiveButton(R.string.action_open_settings) { d, _ ->
                d.dismiss()
                try {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + requireContext().packageName)
                        )
                    )
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {
                        Toast.makeText(requireContext(), R.string.state_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun createFolder() {
        val current = state
        Ui.input(requireContext(), getString(R.string.files_new_folder_prompt), "") { name ->
            if (name.isEmpty()) return@input
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        when (current) {
                            is BrowseState.SafFolder -> {
                                DocumentFile.fromTreeUri(requireContext(), current.folderUri)
                                    ?.createDirectory(name) != null
                            }
                            is BrowseState.PlainFolder -> File(current.dir, name).mkdirs()
                            else -> false
                        }
                    } catch (_: Exception) {
                        false
                    }
                }
                if (ok) loadState() else {
                    Toast.makeText(requireContext(), R.string.state_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun navigateByPath(path: String) {
        // SAF paths can't be typed; support internal-storage paths only.
        val f = File(path)
        if (f.exists() && f.isDirectory && f.canRead()) {
            val segments = f.absolutePath.removePrefix("/storage/emulated/0").split("/").filter { it.isNotEmpty() }
            state = BrowseState.PlainFolder(f, segments)
            loadState()
        } else {
            Toast.makeText(requireContext(), R.string.history_file_missing, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- breadcrumb (spec Section 10.6) ----------------

    private fun renderBreadcrumb(segments: List<String>) {
        val crumb = binding.breadcrumb
        crumb.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        fun addCrumb(label: String, index: Int) {
            val button = inflater.inflate(R.layout.item_crumb, crumb, false) as TextView
            button.text = label
            button.isAllCaps = false
            button.minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
            button.gravity = android.view.Gravity.CENTER_VERTICAL
            button.setOnClickListener {
                navigateToCrumb(index)
            }
            crumb.addView(button)
        }
        segments.forEachIndexed { index, s -> addCrumb(s, index) }
        if (segments.isEmpty()) {
            addCrumb(getString(R.string.files_files), 0)
        }
    }

    private fun navigateToCrumb(index: Int) {
        when (val s = state) {
            is BrowseState.Root -> {}
            is BrowseState.Category -> if (index == 0) {
                state = BrowseState.Root
                loadState()
            }
            is BrowseState.SafFolder -> {
                if (index == 0) {
                    state = BrowseState.Root
                } else {
                    var folder = s.treeUri
                    var docUri = SafStore.treeRootDocumentUri(s.treeUri)
                    for (i in 1 until index) {
                        val children = SafStore.listChildren(requireContext(), docUri) ?: return
                        val target = children.firstOrNull { it.name == s.segments.getOrNull(i) } ?: return
                        docUri = target.uri
                    }
                    state = BrowseState.SafFolder(
                        s.treeUri, docUri, s.segments.subList(0, index)
                    )
                }
                loadState()
            }
            is BrowseState.PlainFolder -> {
                if (index == 0) {
                    state = BrowseState.Root
                } else {
                    val target = File("/storage/emulated/0/" + s.segments.subList(0, index).joinToString("/"))
                    state = BrowseState.PlainFolder(target, s.segments.subList(0, index))
                }
                loadState()
            }
        }
    }

    // ---------------- selection ----------------

    override fun selectAllVisible() {
        val adapter = binding.recycler.adapter as? FilesAdapter ?: return
        val all = ArrayList<Selectable>()
        for (row in adapter.rows) {
            val sel = rowToSelectable(row) ?: continue
            if (!sel.isDirectory) all.add(sel)
        }
        SelectionState.setAll(all, true)
    }

    private fun rowToSelectable(row: Any): Selectable? = when (row) {
        is FileEntry -> Selectable(
            key = row.uri, name = row.name, size = row.size, uri = row.uri, mime = row.mime, kind = "document"
        )
        is FolderEntry -> when (val doc = row.docUri) {
            is File -> Selectable(
                key = "file://" + doc.absolutePath,
                name = row.name, size = 0,
                uri = "file://" + doc.absolutePath,
                mime = "inode/dir", isDirectory = true, kind = "folder_plain"
            )
            is Uri -> Selectable(
                key = doc.toString(), name = row.name, size = 0,
                uri = doc.toString(), mime = "inode/dir",
                isDirectory = true, kind = "folder_saf"
            )
            else -> null
        }
        else -> null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------------- row models ----------------

    class Header(val label: String)
    class CatEntry(val label: String, val icon: Int, var count: Int, val category: MediaCategory?)
    class FolderEntry(val name: String, val treeUri: Uri?, val docUri: Any?) // docUri: Uri (SAF) or File (plain)
    class AddFolderEntry
    class FileEntry(val name: String, val size: Long, val mime: String, val uri: String)

    private inner class FilesAdapter(val rows: List<Any>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Header -> 1
            is CatEntry -> 2
            is FolderEntry -> 3
            is AddFolderEntry -> 3
            is FileEntry -> 4
            else -> 4
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                1 -> HeaderHolder(inflater.inflate(R.layout.item_header, parent, false))
                2 -> CatHolder(inflater.inflate(R.layout.item_category, parent, false))
                3 -> GenericHolder(inflater.inflate(R.layout.item_category, parent, false))
                else -> RowHolder(inflater.inflate(R.layout.item_media_row, parent, false))
            }
        }

        override fun getItemCount(): Int = rows.size

        fun updateCount(label: String, count: Int) {
            for (i in rows.indices) {
                val row = rows[i]
                if (row is CatEntry && row.label == label) {
                    row.count = count
                    notifyItemChanged(i)
                    return
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Header -> (holder as HeaderHolder).text.setText(row.label)
                is CatEntry -> {
                    val h = holder as CatHolder
                    h.icon.setImageResource(row.icon)
                    h.name.text = row.label
                    // -1 = scan not finished yet: loading placeholder, not 0.
                    h.count.text = if (row.count < 0) "…" else row.count.toString()
                    h.root.setOnClickListener {
                        state = BrowseState.Category(row.category ?: return@setOnClickListener)
                        loadState()
                    }
                }
                is FolderEntry -> {
                    val h = holder as GenericHolder
                    h.icon.setImageResource(R.drawable.ic_folder)
                    h.name.text = row.name
                    h.count.text = ""
                    h.root.setOnClickListener {
                        when (val doc = row.docUri) {
                            is Uri -> {
                                state = BrowseState.SafFolder(
                                    row.treeUri ?: return@setOnClickListener,
                                    doc,
                                    listOf(row.name)
                                )
                                loadState()
                            }
                            is File -> {
                                state = BrowseState.PlainFolder(doc, listOf(row.name))
                                loadState()
                            }
                            null -> {
                                // Download folder: open the Downloads category
                                state = BrowseState.Category(MediaCategory.DOWNLOADS)
                                loadState()
                            }
                            else -> promptAllFilesAccess()
                        }
                    }
                }
                is AddFolderEntry -> {
                    val h = holder as GenericHolder
                    h.icon.setImageResource(R.drawable.ic_plus)
                    h.name.setText(R.string.files_add_folder)
                    h.count.text = ""
                    h.root.setOnClickListener {
                        parentManager?.requestTreeAccess()
                    }
                }
                is FileEntry -> {
                    val h = holder as RowHolder
                    h.title.text = row.name
                    h.subtitle.text = row.mime
                    h.meta.text = Fmt.bytes(row.size)
                    h.thumb.setImageResource(Ui.iconForFile(row.name, row.mime))
                    if (SelectionState.isSelected(row.uri)) {
                        h.check.visibility = View.VISIBLE
                        h.check.contentDescription = getString(R.string.cd_selected)
                    } else {
                        h.check.visibility = View.GONE
                        h.check.contentDescription = getString(R.string.cd_not_selected)
                    }
                    h.itemView.setOnClickListener {
                        SelectionState.toggle(
                            Selectable(row.uri, row.name, row.size, row.uri, row.mime, kind = "document")
                        )
                    }
                    h.itemView.setOnLongClickListener { v ->
                        showFileMenu(v, row)
                        true
                    }
                }
            }
        }

        private fun showFileMenu(anchor: View, row: FileEntry) {
            val popup = PopupMenu(anchor.context, anchor)
            popup.menu.add(0, 1, 0, R.string.action_open)
            popup.menu.add(0, 2, 1, R.string.files_rename)
            popup.menu.add(0, 3, 2, R.string.action_delete)
            popup.menu.add(0, 4, 3, R.string.files_properties)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> openFile(row)
                    2 -> renameFile(row)
                    3 -> deleteFile(row)
                    4 -> showProperties(row)
                }
                true
            }
            popup.show()
        }

        private fun openFile(row: FileEntry) {
            try {
                val uri = Uri.parse(row.uri)
                val resolved = if (uri.scheme == "file") {
                    androidx.core.content.FileProvider.getUriForFile(
                        requireContext(),
                        requireContext().packageName + ".fileprovider",
                        File(uri.path ?: return)
                    )
                } else uri
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(resolved, row.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (_: Exception) {
                Toast.makeText(requireContext(), R.string.history_file_missing, Toast.LENGTH_SHORT).show()
            }
        }

        private fun renameFile(row: FileEntry) {
            val uri = Uri.parse(row.uri)
            if (uri.scheme != "content") {
                Toast.makeText(requireContext(), R.string.state_failed, Toast.LENGTH_SHORT).show()
                return
            }
            Ui.input(requireContext(), getString(R.string.files_rename_prompt), row.name) { newName ->
                if (newName.isEmpty() || newName == row.name) return@input
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            DocumentFile.fromSingleUri(requireContext(), uri)?.renameTo(newName) == true
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (ok) loadState() else {
                        Toast.makeText(requireContext(), R.string.state_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        private fun deleteFile(row: FileEntry) {
            Ui.confirm(
                requireContext(),
                getString(R.string.files_delete_confirm_title),
                getString(R.string.files_delete_confirm_message)
            ) {
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            val uri = Uri.parse(row.uri)
                            when (uri.scheme) {
                                "content" -> requireContext().contentResolver.delete(uri, null, null)
                                "file" -> File(uri.path ?: return@withContext).delete()
                            }
                        } catch (_: Exception) {
                        }
                    }
                    loadState()
                }
            }
        }

        private fun showProperties(row: FileEntry) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.files_properties)
                .setMessage(
                    "${getString(R.string.props_name)}: ${row.name}\n" +
                        "${getString(R.string.props_size)}: ${Fmt.bytes(row.size)}\n" +
                        "${getString(R.string.props_mime)}: ${row.mime}"
                )
                .setPositiveButton(R.string.action_ok, null)
                .show()
        }
    }

    private class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.header_text)
    }

    private class CatHolder(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v
        val icon: ImageView = v.findViewById(R.id.cat_icon)
        val name: TextView = v.findViewById(R.id.cat_name)
        val count: TextView = v.findViewById(R.id.cat_count)
    }

    private class GenericHolder(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v
        val icon: ImageView = v.findViewById(R.id.cat_icon)
        val name: TextView = v.findViewById(R.id.cat_name)
        val count: TextView = v.findViewById(R.id.cat_count)
    }

    private class RowHolder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val meta: TextView = v.findViewById(R.id.meta)
        val check: ImageView = v.findViewById(R.id.check)
    }

    companion object {
        fun newInstance(pickMode: Boolean): FilesPageFragment {
            val f = FilesPageFragment()
            f.arguments = Bundle().apply { putBoolean("pickMode", pickMode) }
            return f
        }
    }
}
