package com.morselink.app.feature.transfer

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.morselink.app.R
import com.morselink.app.core.model.TransferDirection
import com.morselink.app.core.model.TransferItem
import com.morselink.app.core.model.TransferItemState
import com.morselink.app.core.transfer.TransferEngine
import com.morselink.app.core.ui.Ui
import com.morselink.app.core.util.Fmt
import java.io.File

/**
 * Queue rows for sending/receiving lists. State is shown with icon + text +
 * color together, never color alone (spec Section 16.4). The row rendering is
 * shared with the queue sheet via [bindRow] so both surfaces always match.
 */
class TransferItemsAdapter(
    private val items: List<TransferItem>
) : RecyclerView.Adapter<TransferItemsAdapter.Holder>() {

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.item_icon)
        val name: TextView = v.findViewById(R.id.item_name)
        val status: TextView = v.findViewById(R.id.item_status)
        val progress: ProgressBar = v.findViewById(R.id.item_progress)
        val actionLeft: ImageButton = v.findViewById(R.id.action_left)
        val actionRight: ImageButton = v.findViewById(R.id.action_right)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_transfer, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        TransferRowBinder.bindRow(holder.itemView, items[position])
    }
}

/** Shared row renderer for the compact lists and the queue sheet. */
object TransferRowBinder {

    fun bindRow(root: View, item: TransferItem) {
        val context = root.context
        val name: TextView = root.findViewById(R.id.item_name)
        val status: TextView = root.findViewById(R.id.item_status)
        val icon: ImageView = root.findViewById(R.id.item_icon)
        val progress: ProgressBar = root.findViewById(R.id.item_progress)
        val actionLeft: ImageButton = root.findViewById(R.id.action_left)
        val actionRight: ImageButton = root.findViewById(R.id.action_right)

        name.text = if (item.file.relativePath != null) {
            "${item.file.relativePath}/${item.file.displayName}"
        } else {
            item.file.displayName
        }

        val stateLabel: String
        val stateIcon: Int
        val progressVisible: Boolean
        when (item.state) {
            TransferItemState.QUEUED -> {
                stateLabel = context.getString(R.string.state_queued)
                stateIcon = R.drawable.ic_clock
                progressVisible = false
            }
            TransferItemState.IN_PROGRESS -> {
                stateLabel = context.getString(R.string.state_in_progress) +
                    " · " + Fmt.bytes(item.bytesTransferred) + " / " + Fmt.bytes(item.totalBytes) +
                    (if (item.speedBps > 0) " · " + Fmt.speed(item.speedBps) else "")
                stateIcon = R.drawable.ic_upload
                progressVisible = true
            }
            TransferItemState.PAUSED -> {
                stateLabel = context.getString(R.string.state_paused) +
                    " · " + Fmt.bytes(item.bytesTransferred) + " / " + Fmt.bytes(item.totalBytes)
                stateIcon = R.drawable.ic_pause
                progressVisible = true
            }
            TransferItemState.COMPLETED -> {
                stateLabel = context.getString(R.string.state_completed)
                stateIcon = R.drawable.ic_check_circle
                progressVisible = false
            }
            TransferItemState.FAILED -> {
                stateLabel = context.getString(R.string.state_failed) +
                    (if (item.lastError != null) " — ${item.lastError}" else "")
                stateIcon = R.drawable.ic_error
                progressVisible = false
            }
            TransferItemState.SKIPPED -> {
                stateLabel = context.getString(R.string.state_skipped) +
                    (if (item.lastError != null) " — ${item.lastError}" else "")
                stateIcon = R.drawable.ic_info
                progressVisible = false
            }
            TransferItemState.CANCELLED -> {
                stateLabel = context.getString(R.string.state_cancelled)
                stateIcon = R.drawable.ic_close
                progressVisible = false
            }
        }
        status.text = stateLabel
        icon.setImageResource(stateIcon)
        if (progressVisible) {
            progress.visibility = View.VISIBLE
            progress.max = 100
            progress.progress = Fmt.percent(item.bytesTransferred, item.totalBytes)
            val tint = if (item.state == TransferItemState.PAUSED) {
                context.resources.getColor(R.color.status_warning)
            } else {
                context.resources.getColor(R.color.status_sending)
            }
            progress.progressTintList = android.content.res.ColorStateList.valueOf(tint)
        } else {
            progress.visibility = View.GONE
        }

        when {
            item.state == TransferItemState.IN_PROGRESS || item.state == TransferItemState.QUEUED -> {
                actionLeft.visibility = View.VISIBLE
                actionLeft.setImageResource(R.drawable.ic_pause)
                actionLeft.contentDescription =
                    context.getString(R.string.cd_pause_item, item.file.displayName)
                actionLeft.setOnClickListener { TransferEngine.pauseItem(item.id) }
            }
            item.state == TransferItemState.PAUSED -> {
                actionLeft.visibility = View.VISIBLE
                actionLeft.setImageResource(R.drawable.ic_play)
                actionLeft.contentDescription =
                    context.getString(R.string.cd_resume_item, item.file.displayName)
                actionLeft.setOnClickListener { TransferEngine.resumeItem(item.id) }
            }
            item.state == TransferItemState.FAILED -> {
                actionLeft.visibility = View.VISIBLE
                actionLeft.setImageResource(R.drawable.ic_retry)
                actionLeft.contentDescription =
                    context.getString(R.string.cd_retry_item, item.file.displayName)
                actionLeft.setOnClickListener { TransferEngine.retryItem(item.id) }
            }
            else -> actionLeft.visibility = View.INVISIBLE
        }

        val isApk = item.file.mime == "application/vnd.android.package-archive" ||
            item.file.displayName.lowercase().endsWith(".apk")
        if (item.state == TransferItemState.COMPLETED &&
            item.direction == TransferDirection.RECEIVING && isApk
        ) {
            actionRight.visibility = View.VISIBLE
            actionRight.setImageResource(R.drawable.ic_apk)
            actionRight.contentDescription = context.getString(R.string.action_install)
            actionRight.setOnClickListener { installApk(context, item) }
        } else if (item.isTerminal) {
            actionRight.visibility = View.INVISIBLE
        } else {
            actionRight.visibility = View.VISIBLE
            actionRight.setImageResource(R.drawable.ic_close)
            actionRight.contentDescription =
                context.getString(R.string.cd_cancel_item, item.file.displayName)
            actionRight.setOnClickListener { TransferEngine.cancelItem(item.id) }
        }
    }

    private fun installApk(context: android.content.Context, item: TransferItem) {
        try {
            val uri = Uri.parse(item.finalUri ?: return)
            val resolved = if (uri.scheme == "file") {
                FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", File(uri.path ?: return)
                )
            } else uri
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(resolved, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.article_apk_title, Toast.LENGTH_LONG).show()
            try {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }
}

