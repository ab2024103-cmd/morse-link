package com.morselink.app.feature.transfer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.morselink.app.R
import com.morselink.app.core.model.TransferItem
import com.morselink.app.core.model.TransferItemState
import com.morselink.app.core.transfer.TransferEngine
import kotlinx.coroutines.launch

/**
 * Full queue view (spec Section 10.8): drag-handle reordering of QUEUED items,
 * batch pause/resume, clear completed, retry failed.
 */
class QueueSheet : BottomSheetDialogFragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var retryAll: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.sheet_queue, container, false)
        recycler = view.findViewById(R.id.queue_list)
        retryAll = view.findViewById(R.id.button_retry_failed)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val current = (recycler.adapter as? QueueAdapter)?.items ?: return false
                // Reordering in-progress or completed items is a no-op with a
                // clear affordance (spec Section 10.8) — only QUEUED items move.
                if (from >= current.size || to >= current.size) return false
                if (current[from].state != TransferItemState.QUEUED ||
                    current[to].state != TransferItemState.QUEUED
                ) {
                    Toast.makeText(
                        requireContext(), R.string.state_queued, Toast.LENGTH_SHORT
                    ).show()
                    return false
                }
                TransferEngine.reorder(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(recycler)

        view.findViewById<MaterialButton>(R.id.button_pause_all).setOnClickListener {
            TransferEngine.pauseAll()
        }
        view.findViewById<MaterialButton>(R.id.button_resume_all).setOnClickListener {
            TransferEngine.resumeAll()
        }
        view.findViewById<MaterialButton>(R.id.button_clear_completed).setOnClickListener {
            TransferEngine.clearCompleted()
        }
        retryAll.setOnClickListener { TransferEngine.retryAllFailed() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    TransferEngine.items.collect { items ->
                        val sendQueue = items.filter {
                            it.direction == com.morselink.app.core.model.TransferDirection.SENDING
                        }
                        val current = recycler.adapter as? QueueAdapter
                        if (current == null) {
                            recycler.adapter = QueueAdapter(sendQueue)
                        } else {
                            current.update(sendQueue)
                        }
                        retryAll.isVisibleOrGone(sendQueue.any { it.state == TransferItemState.FAILED })
                    }
                }
            }
        }
        return view
    }

    private fun View.isVisibleOrGone(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }
}
