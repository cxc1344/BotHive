package app.opengrok

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * v0.7.0 user notifications (approvals waiting, routine events). Every text is written in Bix's
 * voice by the caller ([BixVoice]) and passed through [BixVoice.safe] here once more, so secrets
 * and amounts never reach the notification shade. Tapping opens the app on [openChatId].
 */
object Notify {
    const val CH_APPROVALS = "approvals"
    const val CH_ROUTINES = "routines"
    /** Intent extra carrying the chat id a notification wants opened (read by MainActivity). */
    const val EXTRA_OPEN_CHAT = "open_chat"

    private fun channelName(channel: String) = when (channel) {
        CH_APPROVALS -> "需要你批准"
        CH_ROUTINES -> "例行任务"
        else -> "BotHive"
    }

    fun post(ctx: Context, channel: String, id: Int, title: String, text: String, openChatId: String?) {
        try {
            val app = ctx.applicationContext
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val importance = if (channel == CH_APPROVALS) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
                nm.createNotificationChannel(NotificationChannel(channel, channelName(channel), importance))
            }
            val intent = Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (openChatId != null) intent.putExtra(EXTRA_OPEN_CHAT, openChatId)
            val pi = PendingIntent.getActivity(
                app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val safeTitle = BixVoice.safe(title, 60)
            val safeText = BixVoice.safe(text, 160)
            val n = NotificationCompat.Builder(app, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(safeTitle)
                .setContentText(safeText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(safeText))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPriority(if (channel == CH_APPROVALS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(id, n)
        } catch (_: Exception) {
            // Notifications are best effort; never crash a routine or an approval because of them.
        }
    }
}
