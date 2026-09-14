package com.morselink.app.core.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.morselink.app.MainActivity
import com.morselink.app.R
import com.morselink.app.core.model.TransferItemState
import com.morselink.app.core.util.Fmt
import com.morselink.app.core.util.MorselinkServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps active transfers/WebShare alive with a
 * persistent notification (spec Sections 12 API 23+ and 17.3). The queue state
 * shown here is read from the same TransferEngine source of truth as the UI.
 */
class TransferService : Service() {

    companion object {
        const val CHANNEL_TRANSFER = "transfer"
        const val NOTIFICATION_ID = 11
        const val ACTION_PAUSE_ALL = "com.morselink.app.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.morselink.app.action.RESUME_ALL"
        const val ACTION_CANCEL_SESSION = "com.morselink.app.action.CANCEL_SESSION"

        fun ensureStarted(context: Context) {
            val intent = Intent(context, TransferService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun ensureStopped(context: Context) {
            try {
                context.stopService(Intent(context, TransferService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground(buildNotification())
        scope.launch {
            var idleSince = -1L
            while (isActive) {
                val active = TransferEngine.anyWorkActive()
                if (active) {
                    idleSince = -1
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 500) {
                        lastNotify = now
                        updateNotification()
                    }
                } else {
                    if (idleSince < 0) idleSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - idleSince > 3000) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                }
                delay(700)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> TransferEngine.pauseAll()
            ACTION_RESUME_ALL -> TransferEngine.resumeAll()
            ACTION_CANCEL_SESSION -> TransferEngine.closeSession("cancelled from notification")
        }
        updateNotification()
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_TRANSFER,
                getString(R.string.notif_channel_transfer),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.notif_channel_transfer_desc)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        val context = this
        val state = TransferEngine.sessionState.value
        val items = TransferEngine.itemsSnapshot()
        val activeItems = items.filter { it.state == TransferItemState.IN_PROGRESS || it.state == TransferItemState.QUEUED || it.state == TransferItemState.PAUSED }

        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_TRANSFER)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (com.morselink.app.core.webshare.WebShareController.isRunning.value) {
            val url = com.morselink.app.core.webshare.WebShareController.state.value.url ?: ""
            builder.setContentTitle(getString(R.string.notif_webshare_title))
                .setContentText(url)
                .setProgress(0, 0, false)
        } else if (state.active) {
            val done = activeItems.filter { it.state == TransferItemState.IN_PROGRESS }
            val title = getString(R.string.notif_transfer_title, state.peerName ?: "")
            var text = getString(R.string.notif_transfer_items, activeItems.size)
            val inProgress = activeItems.firstOrNull { it.state == TransferItemState.IN_PROGRESS }
            if (inProgress != null) {
                val pct = Fmt.percent(inProgress.bytesTransferred, inProgress.totalBytes)
                text = "${inProgress.file.displayName} — $pct%"
                builder.setProgress(100, pct, false)
            } else if (activeItems.isNotEmpty()) {
                text = getString(R.string.notif_transfer_items, activeItems.size)
                builder.setProgress(0, 0, activeItems.any { it.state == TransferItemState.PAUSED })
            }
            builder.setContentTitle(title).setContentText(text)

            val anyPaused = activeItems.any { it.state == TransferItemState.PAUSED }
            builder.addAction(
                0,
                if (anyPaused) getString(R.string.action_resume_all) else getString(R.string.action_pause_all),
                servicePendingIntent(
                    if (anyPaused) ACTION_RESUME_ALL else ACTION_PAUSE_ALL, 1
                )
            )
            builder.addAction(
                0,
                getString(R.string.action_cancel),
                servicePendingIntent(ACTION_CANCEL_SESSION, 2)
            )
        } else {
            builder.setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_idle_text))
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(MorselinkServices.appContext, TransferService::class.java)
        intent.action = action
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
