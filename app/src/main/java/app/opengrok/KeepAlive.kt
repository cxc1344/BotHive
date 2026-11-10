package app.opengrok

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Background keep-alive ("后台保活"), the standard trio for an agent app:
 *  - a START_STICKY foreground service with a persistent notification keeps the process
 *    important so the routine scheduler and proactive messages keep working;
 *  - onTaskRemoved schedules an exact-alarm restart (exact alarms may start an FGS);
 *  - a BOOT_COMPLETED receiver relaunches it after reboot.
 * The enabled flag lives in SharedPreferences so the boot receiver can read it before any
 * app state exists. Final survival still depends on the OEM: on aggressive ROMs the user
 * must also whitelist the app (battery exemption + autostart), which the settings UI links to.
 */
object KeepAlive {
    private const val PREFS = "og_prefs"
    private const val KEY = "keep_alive"

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        if (on) start(ctx) else runCatching { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) }
    }

    fun start(ctx: Context) {
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java))
        } catch (_: Exception) {
            // Some ROMs refuse background FGS starts; the next app open retries.
        }
    }

    fun isBatteryExempt(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun batteryExemptIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))

    /** Vendor autostart/background settings where available; falls back to the app info page. */
    fun autoStartIntent(ctx: Context): Intent {
        val candidates = listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        )
        for ((pkg, cls) in candidates) {
            val intent = Intent().setClassName(pkg, cls)
            if (ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) return intent
        }
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
    }
}

class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "后台保活", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("BotHive 正在后台待命")
            .setContentText("例行任务与主动消息通道保持运行")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from recents: ask the alarm clock to bring the service back shortly.
        try {
            val am = getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getService(
                this, 7, Intent(this, KeepAliveService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val at = SystemClock.elapsedRealtime() + 3_000
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val CHANNEL = "keep_alive"
        private const val NOTIF_ID = 42
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            if (KeepAlive.isEnabled(context)) KeepAlive.start(context)
        }
    }
}
