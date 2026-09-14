package com.morselink.app.feature.history

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.morselink.app.R
import com.morselink.app.core.data.HistoryEntry
import com.morselink.app.core.model.TransferDirection
import com.morselink.app.core.model.TransferItemState
import com.morselink.app.core.storage.Destinations
import com.morselink.app.core.transfer.TransferEngine
import com.morselink.app.core.ui.Ui
import com.morselink.app.core.util.Fmt
import com.morselink.app.databinding.FragmentHistoryBinding
import com.morselink.app.di.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Transfer history with Received/Sent toggle implemented as part of the same
 * reactive data stream (spec Section 10.9).
 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private var historyListener: () -> Unit = {}
    private var direction = TransferDirection.RECEIVING
    private var statusFilter: TransferItemState? = null
    private var query = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.historyList.layoutManager = LinearLayoutManager(requireContext())

        binding.directionToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            direction = if (checkedId == R.id.tab_received) {
                TransferDirection.RECEIVING
            } else {
                TransferDirection.SENDING
            }
            render()
        }

        binding.historySearchButton.setOnClickListener {
            val visible = binding.historySearchLayout.visibility == View.VISIBLE
            binding.historySearchLayout.visibility = if (visible) View.GONE else View.VISIBLE
            if (visible) {
                binding.historySearchInput.setText("")
                query = ""
                render()
            }
        }
        binding.historySearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString()?.trim() ?: ""
                render()
            }
        })

        binding.historyFilterButton.setOnClickListener { anchor -> showFilterMenu(anchor) }
        binding.historyClearButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.history_clear)
                .setMessage(R.string.history_clear_confirm)
                .setPositiveButton(R.string.action_yes) { d, _ ->
                    d.dismiss()
                    AppServices.history.clearAll()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        historyListener = { render() }
        AppServices.history.addListener(historyListener)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    TransferEngine.events.collect { event ->
                        // Re-render when transfers complete so history is live.
                        if (event is com.morselink.app.core.model.EngineEvent.BatchCompleted ||
                            event is com.morselink.app.core.model.EngineEvent.InfoToast
                        ) {
                            render()
                        }
                    }
                }
            }
        }
        render()
    }

    private fun showFilterMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 0, 0, R.string.history_filter_all)
        popup.menu.add(0, 1, 1, R.string.history_filter_completed)
        popup.menu.add(0, 2, 2, R.string.history_filter_failed)
        popup.menu.add(0, 3, 3, R.string.history_filter_skipped)
        popup.setOnMenuItemClickListener {
            statusFilter = when (it.itemId) {
                1 -> TransferItemState.COMPLETED
                2 -> TransferItemState.FAILED
                3 -> TransferItemState.SKIPPED
                else -> null
            }
            render()
            true
        }
        popup.show()
    }

    private fun render() {
        val all = AppServices.history.all()
        val filtered = ArrayList<HistoryEntry>()
        for (e in all) {
            if (e.direction != direction) continue
            if (statusFilter != null && e.status != statusFilter) continue
            if (query.isNotEmpty() &&
                !e.fileName.contains(query, ignoreCase = true) &&
                !e.peerName.contains(query, ignoreCase = true)
            ) continue
            filtered.add(e)
        }

        // Group by date
        val rows = ArrayList<Any>()
        val dayFmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
        var lastGroup = ""
        val now = Calendar.getInstance()
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val startOfYesterday = startOfToday - 86400000L
        val startOfWeek = startOfToday - 6 * 86400000L
        for (e in filtered) {
            val group = when {
                e.timestamp >= startOfToday -> getString(R.string.history_today)
                e.timestamp >= startOfYesterday -> getString(R.string.history_yesterday)
                e.timestamp >= startOfWeek -> getString(R.string.history_this_week)
                else -> getString(R.string.history_older)
            }
            if (group != lastGroup) {
                rows.add(group)
                lastGroup = group
            }
            rows.add(e)
        }

        binding.historyEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.historyList.adapter = HistoryAdapter(rows)
    }

    // ---------------- adapter ----------------

    private inner class HistoryAdapter(private val rows: List<Any>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is String) 1 else 2

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 1) {
                HeaderHolder(inflater.inflate(R.layout.item_header, parent, false))
            } else {
                EntryHolder(inflater.inflate(R.layout.item_history, parent, false))
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (row is String) {
                (holder as HeaderHolder).text.text = row
                return
            }
            val e = row as HistoryEntry
            val h = holder as EntryHolder
            h.title.text = if (e.relativePath != null) "${e.relativePath}/${e.fileName}" else e.fileName
            h.subtitle.text = "${e.peerName} · ${timeFmt.format(Date(e.timestamp))}"
            h.meta.text = Fmt.bytes(e.size)

            when (e.status) {
                TransferItemState.COMPLETED -> {
                    h.status.setImageResource(R.drawable.ic_check_circle)
                    h.status.setColorFilter(resources.getColor(R.color.status_success))
                }
                TransferItemState.FAILED -> {
                    h.status.setImageResource(R.drawable.ic_error)
                    h.status.setColorFilter(resources.getColor(R.color.status_error))
                }
                TransferItemState.SKIPPED -> {
                    h.status.setImageResource(R.drawable.ic_info)
                    h.status.setColorFilter(resources.getColor(R.color.status_warning))
                }
                else -> {
                    h.status.setImageResource(R.drawable.ic_close)
                    h.status.setColorFilter(resources.getColor(R.color.status_warning))
                }
            }

            if (e.localUri != null && (
                e.mime.startsWith("image/") || e.mime.startsWith("video/") ||
                    (e.sourceUri ?: "").startsWith("content://media")
                )
            ) {
                Glide.with(h.thumb)
                    .load(Uri.parse(e.localUri))
                    .centerCrop()
                    .placeholder(Ui.iconForFile(e.fileName, e.mime))
                    .into(h.thumb)
            } else {
                h.thumb.setImageResource(Ui.iconForFile(e.fileName, e.mime))
            }

            h.itemView.setOnClickListener { openEntry(e) }
            h.overflow.setOnClickListener { anchor -> showEntryMenu(anchor, e) }
        }

        private fun openEntry(e: HistoryEntry) {
            val uriString = e.localUri ?: e.sourceUri
            if (uriString == null || uriString.isEmpty()) {
                Toast.makeText(requireContext(), R.string.history_file_missing, Toast.LENGTH_SHORT).show()
                return
            }
            try {
                val uri = Uri.parse(uriString)
                val resolved = if (uri.scheme == "file") {
                    val f = File(uri.path ?: throw IllegalArgumentException("gone"))
                    if (!f.exists()) throw IllegalArgumentException("gone")
                    FileProvider.getUriForFile(
                        requireContext(), requireContext().packageName + ".fileprovider", f
                    )
                } else uri
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(resolved, e.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (_: Exception) {
                Toast.makeText(requireContext(), R.string.history_file_missing, Toast.LENGTH_SHORT).show()
            }
        }

        private fun showEntryMenu(anchor: View, e: HistoryEntry) {
            val popup = PopupMenu(anchor.context, anchor)
            popup.menu.add(0, 1, 0, R.string.action_open)
            popup.menu.add(0, 2, 1, R.string.action_share)
            if (e.status == TransferItemState.FAILED && e.direction == TransferDirection.SENDING) {
                popup.menu.add(0, 3, 2, R.string.action_retry)
            }
            popup.menu.add(0, 4, 3, R.string.history_delete_record)
            popup.menu.add(0, 5, 4, R.string.history_delete_file)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> openEntry(e)
                    2 -> shareEntry(e)
                    3 -> TransferEngine.retryFromHistory(e.id)
                    4 -> AppServices.history.deleteRecord(e.id)
                    5 -> deleteUnderlying(e)
                }
                true
            }
            popup.show()
        }

        private fun shareEntry(e: HistoryEntry) {
            val uriString = e.localUri ?: e.sourceUri ?: return
            try {
                val uri = Uri.parse(uriString)
                val resolved = if (uri.scheme == "file") {
                    FileProvider.getUriForFile(
                        requireContext(), requireContext().packageName + ".fileprovider",
                        File(uri.path ?: return)
                    )
                } else uri
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType(e.mime)
                            .putExtra(Intent.EXTRA_STREAM, resolved)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        getString(R.string.action_share)
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(requireContext(), R.string.history_file_missing, Toast.LENGTH_SHORT).show()
            }
        }

        private fun deleteUnderlying(e: HistoryEntry) {
            val uriString = e.localUri ?: return
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.history_delete_file)
                .setMessage(getString(R.string.files_delete_confirm_message))
                .setPositiveButton(R.string.action_yes) { d, _ ->
                    d.dismiss()
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            Destinations.deleteUnderlying(uriString)
                        }
                        AppServices.history.deleteRecord(e.id)
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.header_text)
    }

    private class EntryHolder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val meta: TextView = v.findViewById(R.id.meta)
        val status: ImageView = v.findViewById(R.id.status_icon)
        val overflow: ImageButton = v.findViewById(R.id.overflow)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        AppServices.history.removeListener(historyListener)
        _binding = null
    }
}
