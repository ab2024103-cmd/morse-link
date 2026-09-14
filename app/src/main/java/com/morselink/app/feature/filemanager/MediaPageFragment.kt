package com.morselink.app.feature.filemanager

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private val appItems = ArrayList<AppEntry>()
    private val appIconCache = HashMap<String, android.graphics.drawable.Drawable>()
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
                        requestPermissions(missing.toTypedArray(), RC_MEDIA)
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
        binding.recycler.layoutManager =
            if (wantGrid) GridLayoutManager(requireContext(), 3)
            else LinearLayoutManager(requireContext())
    }

    fun reload() {
        offset = 0
        reachedEnd = false
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

    private fun loadMore() {
        if (loading) return
        loading = true
        val query = parentManager()?.searchQuery
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
            val page = withContext(Dispatchers.IO) {
                MediaLibrary.page(category, offset, pageSize, sortKey, descending, query?.takeIf { it.isNotEmpty() })
            }
            val count = withContext(Dispatchers.IO) {
                MediaLibrary.count(category, query?.takeIf { it.isNotEmpty() })
            }
            withContext(Dispatchers.Main) {
                mediaItems.addAll(page)
                offset += page.size
                totalCount = count
                reachedEnd = page.size < pageSize || mediaItems.size >= count
                firstLoadDone = true
                loading = false
                binding.loading.visibility = View.GONE
                binding.empty.visibility =
                    if (mediaItems.isEmpty() && firstLoadDone) View.VISIBLE else View.GONE
                adapter?.notifyDataSetChanged()
            }
        }
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

    private inner class MediaAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val gridCategories = categoryKey == "photos" || categoryKey == "videos"
        private val wantGrid get() = gridCategories && com.morselink.app.di.AppServices.prefs.mediaViewGrid

        override fun getItemViewType(position: Int): Int =
            if (wantGrid) TYPE_GRID else TYPE_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_GRID) {
                GridHolder(
                    LayoutInflater.from(parent.context).inflate(R.layout.item_media_grid, parent, false)
                )
            } else {
                RowHolder(
                    LayoutInflater.from(parent.context).inflate(R.layout.item_media_row, parent, false)
                )
            }
        }

        override fun getItemCount(): Int =
            if (categoryKey == "apps") appItems.size else mediaItems.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (categoryKey == "apps") {
                val app = appItems[position]
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
            val item = mediaItems[position]
            if (holder is GridHolder) {
                // Glide cancels the previous request bound to this recycled view —
                // the recycling-token rule (spec Section 10.5).
                Glide.with(holder.thumb)
                    .load(Uri.parse(item.uri))
                    .centerCrop()
                    .placeholder(R.drawable.ic_cat_photos)
                    .into(holder.thumb)
                holder.gridLabel.visibility = View.VISIBLE
                holder.gridLabel.text = dateLabel(item.dateModifiedSec)
                if (categoryKey == "videos" && item.durationMs > 0) {
                    holder.videoDuration.visibility = View.VISIBLE
                    holder.videoDuration.text = Fmt.eta(item.durationMs / 1000)
                } else {
                    holder.videoDuration.visibility = View.GONE
                }
                bindSelection(holder.itemView, holder.check, item.uri, mediaSelectable(item))
                holder.itemView.setOnLongClickListener { v ->
                    openUri(v, Uri.parse(item.uri), item.mime)
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
                Glide.with(holder.thumb)
                    .load(Uri.parse(item.uri))
                    .centerCrop()
                    .placeholder(R.drawable.ic_cat_music)
                    .into(holder.thumb)
                bindSelection(holder.itemView, holder.check, item.uri, mediaSelectable(item))
                holder.itemView.setOnLongClickListener { v ->
                    openUri(v, Uri.parse(item.uri), item.mime)
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
        item: Selectable
    ) {
        if (SelectionState.isSelected(key)) {
            check.visibility = View.VISIBLE
            check.contentDescription = getString(R.string.cd_selected)
        } else {
            check.visibility = View.GONE
            check.contentDescription = getString(R.string.cd_not_selected)
        }
        root.setOnClickListener {
            SelectionState.toggle(item)
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

        fun newInstance(category: String): MediaPageFragment {
            val f = MediaPageFragment()
            f.arguments = Bundle().apply { putString("category", category) }
            return f
        }
    }
}
