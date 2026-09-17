package com.morselink.app.feature.transfer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.morselink.app.R
import com.morselink.app.core.model.TransferItem

/** Adapter for the queue sheet; rows reuse the shared transfer row renderer. */
class QueueAdapter(var items: List<TransferItem>) : RecyclerView.Adapter<QueueAdapter.Holder>() {

    fun update(newItems: List<TransferItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_transfer, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        TransferRowBinder.bindRow(holder.itemView, items[position])
    }
}
