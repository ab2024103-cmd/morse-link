package com.morselink.app.feature.filemanager

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Bitmap
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.morselink.app.R
import com.morselink.app.core.media.AppEntry
import com.morselink.app.core.media.MediaCategory
import com.morselink.app.core.media.MediaItem
import com.morselink.app.core.media.MediaLibrary
import com.morselink.app.core.util.DeviceTier
import com.morselink.app.core.util.Fmt
import com.morselink.app.core.util.Permissions
import com.morselink.app.databinding.PageMediaBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Photos / Videos / Music / Apps pages. Paged loading from MediaStore; a
 * loading placeholder shows until the scan has genuinely completed — a scan in
 * progress is never rendered as "empty" (spec Section 10.5).
 */
class MediaPageFragment : Fragment(), PageWithItems {

    private var _binding: PageMediaBinding? = null
    private val binding get() = _binding!!

    private var categoryKey = "photos"
    private var offset = 0
    private var loading = false
    private var reachedEnd = false
    private var firstLoadDone = false
    private var totalCount = -1
    private val mediaItems = ArrayList<MediaItem>()

    /** Section header for date grouping (photos/videos, m2/m21). */
    class DateHeader(val key: String, val label: String, var count: Int)
    private val appItems = ArrayList<AppEntry>()
    private val appIconCache = HashMap<String, android.graphics.drawable.Drawable>()

    /** Small LRU of MediaStore mini thumbnails (photos/videos). */
    private val thumbCache = object : LinkedHashMap<Int, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>): Boolean = size > 32
    }
    private var adapter: MediaAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        categoryKey = arguments?.getString("category") ?: "photos"
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
        applyLayoutManager()
        adapter = MediaAdapter()
        binding.recycler.adapter = adapter

        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
                val last = lm.findLastVisibleItemPosition()
                val total = lm.itemCount
                if (last >= total - 6 && !loading && !reachedEnd) {
                    loadMore()
                }
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            SelectionState.selected.collect {
                adapter?.notifyDataSetChanged()
            }
        }

        binding.grantButton.setOnClickListener {
            val missing = Permissions.mediaReadPermissions(requireContext())
            if (missing.isEmpty()) {
                loadFirst()
            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.permission_denied_title)
                    .setMessage(R.string.files_grant_media_access)
                    .setPositiveButton(R.string.action_ok) { d, _ ->
                        d.dismiss()
                        requestPermissions(Permissions.mediaRequestArray(requireContext()), RC_MEDIA)
                    }
                    .setNeutralButton(R.string.action_open_settings) { d, _ ->
                        d.dismiss()
                        // Fallback path (b2): the system App Info screen, where
                        // full access can always be granted.
                        try {
                            startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + requireContext().packageName)
                                )
                            )
                        } catch (_: Exception) {
                        }
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        }
        loadFirst()
    }

    private fun applyLayoutManager() {
        val gridCategories = categoryKey == "photos" || categoryKey == "videos"
        val wantGrid = gridCategories && com.morselink.app.di.AppServices.prefs.mediaViewGrid
        if (wantGrid) {
            val glm = GridLayoutManager(requireContext(), 3)
            glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter?.getItemViewType(position) == TYPE_HEADER) 3 else 1
            }
            binding.recycler.layoutManager = glm
        } else {
            binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        }
    }

    fun reload() {
        offset = 0
        reachedEnd = false
        retriedEmptyLoad = false
        firstLoadDone = false
        mediaItems.clear()
        appItems.clear()
        totalCount = -1
        adapter?.notifyDataSetChanged()
        applyLayoutManager()
        loadFirst()
    }

    private fun parentManager(): FileManagerFragment? = parentFragment as? FileManagerFragment

    private fun loadFirst() {
        val needsPermission = categoryKey != "apps"
        if (needsPermission && Permissions.mediaReadPermissions(requireContext()).isNotEmpty()) {
            binding.loading.visibility = View.GONE
            binding.empty.visibility = View.GONE
            binding.grantButton.visibility = View.VISIBLE
            return
        }
        binding.grantButton.visibility = View.GONE
        binding.loading.visibility = View.VISIBLE
        loadMore()
    }

    private var retriedEmptyLoad = false

    private fun loadMore() {
        if (loading) return
        loading = true
        val rawQuery = parentManager()?.searchQuery
        // Whitespace-only search text must not filter the whole library (b5).
        val query = rawQuery?.trim()?.takeIf { it.isNotEmpty() }
        val sortKey = parentManager()?.sortKey ?: com.morselink.app.core.media.SortKey.DATE
        val descending = parentManager()?.sortDescending ?: true
        val pageSize = DeviceTier.pageSize
        viewLifecycleOwner.lifecycleScope.launch {
            if (categoryKey == "apps") {
                val apps = withContext(Dispatchers.IO) { MediaLibrary.installedApps() }
                appItems.clear()
                appItems.addAll(apps)
                if (query != null && query.isNotEmpty()) {
                    val filtered = appItems.filter {
                        it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, true)
                    }
                    appItems.clear()
                    appItems.addAll(filtered)
                }
                withContext(Dispatchers.Main) {
                    binding.loading.visibility = View.GONE
                    binding.empty.visibility = if (appItems.isEmpty()) View.VISIBLE else View.GONE
                    rebuildRows()
                    adapter?.notifyDataSetChanged()
                    reachedEnd = true
                    firstLoadDone = true
                    loading = false
                }
                return@launch
            }
            val category = when (categoryKey) {
                "photos" -> MediaCategory.PHOTOS
                "videos" -> MediaCategory.VIDEOS
                else -> MediaCategory.MUSIC
            }
            var page: List<MediaItem>? = null
            var count = 0
            withContext(Dispatchers.IO) {
                try {
                    page = MediaLibrary.page(category, offset, pageSize, sortKey, descending, query?.takeIf { it.isNotEmpty() })
                    count = MediaLibrary.count(category, query?.takeIf { it.isNotEmpty() })
                } catch (e: Exception) {
                    com.morselink.app.core.logging.LogStore.e("Media page load failed (${category.key})", e)
                }
            }
            withContext(Dispatchers.Main) {
                firstLoadDone = true
                loading = false
                binding.loading.visibility = View.GONE
                val loaded = page
                if (loaded == null) {
                    // A real failure — never render it as an empty library.
                    binding.empty.visibility = View.VISIBLE
                    binding.empty.text = getString(R.string.files_load_failed)
                    binding.empty.setOnClickListener { reload() }
                    return@withContext
                }
                if (loaded.isEmpty() && count > 0 && offset == 0 && !retriedEmptyLoad) {
                    // MediaStore reported items exist but returned none — usually
                    // a provider still indexing right after a grant (b5). Retry
                    // once before showing anything.
                    retriedEmptyLoad = true
                    com.morselink.app.core.logging.LogStore.w(
                        "Media page empty but count=$count (${categoryKey}) — retrying"
                    )
                    viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(600)
                        loadMore()
                    }
                    return@withContext
                }
                mediaItems.addAll(loaded)
                offset += loaded.size
                totalCount = count
                reachedEnd = loaded.size < pageSize || mediaItems.size >= count
                binding.empty.visibility =
                    if (mediaItems.isEmpty()) View.VISIBLE else View.GONE
                if (mediaItems.isEmpty()) {
                    when {
                        count > 0 -> {
                            // Index inconsistency: offer a retry, never "nothing here".
                            binding.empty.text = getString(R.string.files_load_failed)
                            binding.empty.setOnClickListener { reload() }
                        }
                        Build.VERSION.SDK_INT >= 33 && Permissions.hasPartialMediaAccess(requireContext()) -> {
                            // Android 13/14 "Select photos" grants: only chosen items are visible.
                            binding.empty.text = getString(R.string.media_partial_access)
                            binding.empty.setOnClickListener { requestFullMediaAccess() }
                        }
                        else -> {
                            binding.empty.text = getString(R.string.history_empty)
                            // Still tappable: a reload is always allowed (b5).
                            binding.empty.setOnClickListener { reload() }
                        }
                    }
                }
                rebuildRows()
                adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun requestFullMediaAccess() {
        val missing = Permissions.mediaReadPermissions(requireContext())
        if (missing.isEmpty()) return
        // Combined request: makes the "Allow all / Select photos" dialog
        // reappear on Android 14 after a partial grant (b2/b3).
        requestPermissions(Permissions.mediaRequestArray(requireContext()), RC_MEDIA)
    }

    override fun selectAllVisible() {
        val selection = ArrayList<Selectable>()
        if (categoryKey == "apps") {
            for (app in appItems) {
                selection.add(
                    Selectable(
                        key = "app:" + app.packageName,
                        name = app.label + ".apk",
                        size = app.size,
                        uri = "file://" + app.apkPath,
                        mime = "application/vnd.android.package-archive",
                        kind = "app"
                    )
                )
            }
        } else {
            for (m in mediaItems) {
                selection.add(
                    Selectable(
                        key = m.uri,
                        name = m.name,
                        size = m.size,
                        uri = m.uri,
                        mime = m.mime,
                        kind = "media"
                    )
                )
            }
        }
        SelectionState.setAll(selection, true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == RC_MEDIA) {
            reload()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------------- adapter ----------------

    /** True when this page shows date sections (photos/videos, m2/m21). */
    private val useDateSections: Boolean
        get() = categoryKey == "photos" || categoryKey == "videos"

    /** Flat row list: DateHeader and MediaItem interleaved, newest first. */
    private var adapterRows: List<Any> = emptyList()

    private fun rebuildRows() {
        adapterRows = buildRows()
    }

    private fun buildRows(): List<Any> {
        if (categoryKey == "apps") return ArrayList(appItems)
        if (!useDateSections) return ArrayList(mediaItems)
        val out = ArrayList<Any>()
        var lastKey: String? = null
        var count = 0
        var header: DateHeader? = null
        for (m in mediaItems) {
            val key = dayKey(m)
            if (key != lastKey) {
                // Header goes at the TOP of its group (b4); the same mutable
                // instance is updated as the group's count grows.
                header = DateHeader(key, dayLabel(key), 0)
                out.add(header!!)
                count = 0
                lastKey = key
            }
            count++
            header!!.count = count
        }
        return out
    }

    private fun dayKey(m: MediaItem): String {
        val ms = if (m.dateTakenMs > 0) m.dateTakenMs else m.dateModifiedSec * 1000L
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ms
        return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.MONTH)}-${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
    }

    private fun dayLabel(key: String): String {
        val parts = key.split("-")
        val cal = java.util.Calendar.getInstance()
        val today = java.util.Calendar.getInstance()
        val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        cal.set(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), 0, 0, 0)
        return when {
            cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) ->
                getString(R.string.date_today)
            cal.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR) ->
                getString(R.string.date_yesterday)
            else -> {
                val fmt = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault())
                fmt.format(cal.time)
            }
        }
    }

    private fun toggleDateGroup(header: DateHeader) {
        val items = mediaItems.filter { dayKey(it) == header.key }
        val allSelected = items.all { SelectionState.isSelected(it.uri) }
        SelectionState.setAll(items.map { mediaSelectable(it) }, !allSelected)
    }

    private inner class MediaAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = adapterRows.size

        override fun getItemViewType(position: Int): Int = when {
            adapterRows[position] is DateHeader -> TYPE_HEADER
            wantGridStatic -> TYPE_GRID
            else -> TYPE_ROW
        }

        private val wantGridStatic: Boolean
            get() = useDateSections && com.morselink.app.di.AppServices.prefs.mediaViewGrid

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                TYPE_HEADER -> HeaderHolder(
                    LayoutInflater.from(parent.context).inflate(R.layout.item_date_header, parent, false)
                )
                TYPE_GRID -> GridHolder(
                    LayoutInflater.from(parent.context).inflate(R.layout.item_media_grid, parent, false)
                )
                else -> RowHolder(
                    LayoutInflater.from(parent.context).inflate(R.layout.item_media_row, parent, false)
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = adapterRows[position]
            if (row is DateHeader) {
                holder as HeaderHolder
                holder.title.text = row.label
                holder.count.text = getString(R.string.files_item_count, row.count)
                val groupItems = mediaItems.filter { dayKey(it) == row.key }
                val allSelected = groupItems.isNotEmpty() && groupItems.all { SelectionState.isSelected(it.uri) }
                holder.check.setImageResource(
                    if (allSelected) R.drawable.ic_check_circle else R.drawable.ic_circle
                )
                holder.check.contentDescription = getString(R.string.cd_select_date_group)
                holder.root.setOnClickListener { toggleDateGroup(row) }
                return
            }
            if (categoryKey == "apps") {
                val app = row as AppEntry
                if (holder is RowHolder) {
                    holder.title.text = app.label
                    holder.subtitle.text = app.packageName
                    holder.meta.text = Fmt.bytes(app.size)
                    holder.thumb.setImageResource(R.drawable.ic_cat_apps)
                    loadAppIcon(holder.thumb, app)
                    bindSelection(holder.itemView, holder.check, "app:" + app.packageName, appSelectable(app))
                    holder.itemView.setOnLongClickListener { v ->
                        openUri(v, Uri.parse("file://" + app.apkPath), "application/vnd.android.package-archive")
                        true
                    }
                }
                return
            }
            val item = row as MediaItem
            if (holder is GridHolder) {
                loadMediaThumb(holder.thumb, item)
                holder.gridLabel.visibility = View.VISIBLE
                holder.gridLabel.text = dateLabel(item.dateModifiedSec)
                if (categoryKey == "videos" && item.durationMs > 0) {
                    holder.videoDuration.visibility = View.VISIBLE
                    holder.videoDuration.text = Fmt.eta(item.durationMs / 1000)
                } else {
                    holder.videoDuration.visibility = View.GONE
                }
                bindSelection(holder.itemView, holder.check, item.uri, mediaSelectable(item), showCircle = true)
                holder.itemView.setOnLongClickListener { v ->
                    openMedia(v, item.name, item.uri, item.mime)
                    true
                }
            } else if (holder is RowHolder) {
                holder.title.text = item.name
                when (categoryKey) {
                    "music" -> holder.subtitle.text =
                        if (item.artist.isNullOrBlank()) {
                            getString(R.string.files_unknown_artist)
                        } else item.artist
                    else -> holder.subtitle.text = dateLabel(item.dateModifiedSec)
                }
                holder.meta.text = Fmt.bytes(item.size)
                if (categoryKey == "photos" || categoryKey == "videos") {
                    loadMediaThumb(holder.thumb, item)
                } else {
                    Glide.with(holder.thumb)
                        .load(Uri.parse(item.uri))
                        .centerCrop()
                        .placeholder(R.drawable.ic_cat_music)
                        .into(holder.thumb)
                }
                bindSelection(holder.itemView, holder.check, item.uri, mediaSelectable(item))
                holder.itemView.setOnLongClickListener { v ->
                    openMedia(v, item.name, item.uri, item.mime)
                    true
                }
            }
        }
    }

    private fun mediaSelectable(m: MediaItem): Selectable = Selectable(
        key = m.uri,
        name = m.name,
        size = m.size,
        uri = m.uri,
        mime = m.mime,
        kind = "media"
    )

    private fun appSelectable(app: AppEntry): Selectable = Selectable(
        key = "app:" + app.packageName,
        name = app.label + ".apk",
        size = app.size,
        uri = "file://" + app.apkPath,
        mime = "application/vnd.android.package-archive",
        kind = "app"
    )

    private fun bindSelection(
        root: View,
        check: ImageView,
        key: String,
        item: Selectable,
        showCircle: Boolean = false
    ) {
        if (SelectionState.isSelected(key)) {
            check.visibility = View.VISIBLE
            check.alpha = 1f
            check.setImageResource(R.drawable.ic_check_circle)
            check.contentDescription = getString(R.string.cd_selected)
        } else if (showCircle) {
            // An empty circle on every tile signals "tap to select" (mlogs2).
            check.visibility = View.VISIBLE
            check.alpha = 0.6f
            check.setImageResource(R.drawable.ic_circle)
            check.contentDescription = getString(R.string.cd_not_selected)
        } else {
            check.visibility = View.GONE
            check.contentDescription = getString(R.string.cd_not_selected)
        }
        root.setOnClickListener {
            SelectionState.toggle(item)
        }
    }

    /**
     * MediaStore MINI_KIND thumbnails: far more reliable (and lighter) than
     * full decodes through Glide on old devices — photos and videos actually
     * show up in the grid (mlogs2).
     */
    private fun loadMediaThumb(target: ImageView, item: MediaItem) {
        target.tag = item.id
        val cached = synchronized(thumbCache) { thumbCache[item.id.toInt()] }
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        target.setImageResource(if (categoryKey == "videos") R.drawable.ic_cat_videos else R.drawable.ic_cat_photos)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val bmp: Bitmap? = try {
                if (categoryKey == "videos") {
                    android.provider.MediaStore.Video.Thumbnails.getThumbnail(
                        requireContext().contentResolver, item.id,
                        android.provider.MediaStore.Video.Thumbnails.MINI_KIND, null
                    )
                } else {
                    android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                        requireContext().contentResolver, item.id,
                        android.provider.MediaStore.Images.Thumbnails.MINI_KIND, null
                    )
                }
            } catch (_: Exception) {
                null
            }
            var result = bmp
            if (result == null && item.path != null) {
                // Direct sampled decode of the file — needs nothing beyond the
                // READ permission already granted, no thumbnail table, no Glide.
                result = decodeSampled(item.path!!, 256)
            }
            if (result != null) {
                synchronized(thumbCache) { thumbCache[item.id.toInt()] = result }
            }
            withContext(Dispatchers.Main) {
                if (target.tag != item.id) return@withContext
                if (result != null) {
                    target.setImageBitmap(result)
                } else {
                    // Last resort: a decode through Glide.
                    Glide.with(target)
                        .load(Uri.parse(item.uri))
                        .centerCrop()
                        .placeholder(R.drawable.ic_cat_photos)
                        .into(target)
                }
            }
        }
    }

    /** Downscaled bitmap decode (bounds first, then inSampleSize). */
    private fun decodeSampled(path: String, targetSize: Int): Bitmap? {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            var w = bounds.outWidth
            var h = bounds.outHeight
            while (w / 2 >= targetSize && h / 2 >= targetSize) {
                w /= 2
                h /= 2
                sample *= 2
            }
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeFile(path, opts)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun loadAppIcon(target: ImageView, app: AppEntry) {
        val cached = appIconCache[app.packageName]
        if (cached != null) {
            target.setImageDrawable(cached)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val icon = requireContext().packageManager.getApplicationIcon(app.packageName)
                appIconCache[app.packageName] = icon
                withContext(Dispatchers.Main) {
                    if (target.tag == app.packageName) {
                        target.setImageDrawable(icon)
                    }
                }
            } catch (_: Exception) {
            }
        }
        target.tag = app.packageName
    }

    /** Media files open in the built-in viewer; anything else goes external. */
    private fun openMedia(view: View, name: String, uri: String, mime: String) {
        val m = mime.ifBlank { MediaLibrary.guessMime(name) }
        if (m.startsWith("image/") || m.startsWith("video/") || m.startsWith("audio/")) {
            com.morselink.app.feature.viewer.ViewerActivity.start(view.context, uri, m, name)
        } else {
            openUri(view, Uri.parse(uri), m)
        }
    }

    private fun openUri(view: View, uri: Uri, mime: String) {
        try {
            val resolved = if (uri.scheme == "file") {
                androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().packageName + ".fileprovider",
                    java.io.File(uri.path ?: return)
                )
            } else uri
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(resolved, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(view.context, R.string.history_file_missing, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dateLabel(seconds: Long): String {
        val fmt = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(seconds * 1000))
    }

    private class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v
        val title: TextView = v.findViewById(R.id.date_title)
        val count: TextView = v.findViewById(R.id.date_count)
        val check: ImageView = v.findViewById(R.id.date_check)
    }

    private class GridHolder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val videoDuration: TextView = v.findViewById(R.id.video_duration)
        val gridLabel: TextView = v.findViewById(R.id.grid_label)
        val check: ImageView = v.findViewById(R.id.check)
    }

    private class RowHolder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val meta: TextView = v.findViewById(R.id.meta)
        val check: ImageView = v.findViewById(R.id.check)
    }

    companion object {
        private const val RC_MEDIA = 4103
        private const val TYPE_GRID = 1
        private const val TYPE_ROW = 2
        private const val TYPE_HEADER = 3

        fun newInstance(category: String): MediaPageFragment {
            val f = MediaPageFragment()
            f.arguments = Bundle().apply { putString("category", category) }
            return f
        }
    }
}
