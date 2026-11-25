package app.opengrok

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
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

    /**
     * v0.7.0 (F18): which notifications are kept at all. Ongoing ones (music, downloads, our own
     * keep-alive), BotHive's own and VISIBILITY_SECRET ones never enter the buffer or a trigger.
     */
    internal fun shouldKeep(ownPackage: String, pkg: String, ongoing: Boolean, visibility: Int): Boolean {
        if (ongoing) return false
        if (pkg == ownPackage) return false
        if (visibility == Notification.VISIBILITY_SECRET) return false
        return true
    }
}

class OgNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val n = sbn.notification ?: return
            if (!Notifications.shouldKeep(packageName, sbn.packageName, sbn.isOngoing, n.visibility)) return
            val extras = n.extras
            val title = extras.getCharSequence("android.title")?.toString().orEmpty()
            val rawText = extras.getCharSequence("android.text")?.toString().orEmpty()
            if (title.isBlank() && rawText.isBlank()) return
            val app = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(sbn.packageName, 0)
                ).toString()
            } catch (_: Exception) {
                sbn.packageName
            }
            // v0.7.0 (F18): verification codes, card numbers and keys are masked before anything
            // can hand them to a model (recent_notifications, notification-triggered routines).
            Policy.init(applicationContext)
            val mask = Policy.maskOtp
            val safeTitle = if (mask) LeakGuard.mask(title) else title
            val safeText = if (mask) LeakGuard.mask(rawText) else rawText
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sbn.postTime))
            Notifications.push("[$time] $app:$safeTitle $safeText".take(300))
            val pkg = sbn.packageName
            val store = (applicationContext as? OgApp)?.store ?: return
            Handler(Looper.getMainLooper()).post {
                runCatching { store.onNotification(pkg, app, safeTitle, safeText) }
                runCatching { SuperRouter.onIncomingNotification(store, pkg, app, safeTitle, safeText) }
            }
        } catch (_: Exception) {
        }
    }
}
