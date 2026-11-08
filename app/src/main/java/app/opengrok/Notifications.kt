package app.opengrok

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recent-notification buffer filled by OgNotificationListener (user-granted 通知使用权）. */
object Notifications {
    private val buffer = ArrayDeque<String>()

    fun isEnabled(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun recent(ctx: Context): String {
        if (!isEnabled(ctx)) {
            return "通知读取未开启:请用户在 Agent 面板点「通知使用权」开启(系统设置 → 通知 → 通知使用权)后重试。"
        }
        val items = synchronized(buffer) { buffer.toList() }
        return if (items.isEmpty()) {
            "(暂无缓存通知:监听开启后,新进来的通知会出现在这里)"
        } else {
            items.takeLast(30).joinToString("\n")
        }
    }

    fun push(line: String) {
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > 50) buffer.removeFirst()
        }
    }
}

class OgNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString().orEmpty()
            val text = extras.getCharSequence("android.text")?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return
            val app = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(sbn.packageName, 0)
                ).toString()
            } catch (_: Exception) {
                sbn.packageName
            }
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sbn.postTime))
            Notifications.push("[$time] $app:$title $text".take(300))
        } catch (_: Exception) {
        }
    }
}
