package app.opengrok

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.util.Calendar

/**
* The device Agent's system tools, built only on public Android APIs (the same capability
* classes Operit exposes before its Shizuku/Root channels): device status, today's app
* usage time, launchable-app listing and app launching. Anything that needs a system
* grant reports exactly which setting the user must enable.
*/
object SystemTools {

fun deviceStatus(ctx: Context): String {
val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
val charging = bm.isCharging
val stat = StatFs(Environment.getDataDirectory().path)
val totalGb = stat.totalBytes / 1e9
val freeGb = stat.availableBytes / 1e9
val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it)}
val upMin = SystemClock.elapsedRealtime() / 60000
return buildString {
appendLine("设备:${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT})")
appendLine("电量:$level%${if (charging) "(充电中)" else ""}")
appendLine("存储:可用 ${"%.1f".format(freeGb)} GB / 共 ${"%.1f".format(totalGb)} GB")
appendLine("内存:可用 ${mi.availMem / 1024 / 1024} MB / 共 ${mi.totalMem / 1024 / 1024} MB${if (mi.lowMemory) "(系统报告内存紧张)" else ""}")
append("已开机:${upMin / 60} 小时 ${upMin % 60} 分钟")
}
}

private fun hasUsageAccess(ctx: Context): Boolean {
val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
} else {
@Suppress("DEPRECATION")
appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
}
return mode == AppOpsManager.MODE_ALLOWED
}

fun appUsage(ctx: Context): String {
if (!hasUsageAccess(ctx)) {
return "读取应用使用时长需要「使用情况访问」权限。请让用户在 Agent 模式页面点「开启使用情况访问」,在系统列表里允许 OpenGrokBot 后再试。"
}
val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
val cal = Calendar.getInstance().apply {
set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}
val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, System.currentTimeMillis())
if (stats.isNullOrEmpty()) return "今天还没有可统计的使用数据。"
val pm = ctx.packageManager
val rows = stats.filter { it.totalTimeInForeground > 0}
.groupBy { it.packageName}
.map { (pkg, list) -> pkg to list.sumOf { it.totalTimeInForeground}}
.sortedByDescending { it.second}
.take(15)
if (rows.isEmpty()) return "今天还没有应用前台使用记录。"
return rows.joinToString("\n") { (pkg, ms) ->
val label = try {
pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (_: Exception) {
pkg
}
val min = ms / 60000
val dur = if (min >= 60) "${min / 60} 小时 ${min % 60} 分" else "$min 分"
"$label:$dur($pkg)"
}
}

private fun launchableApps(ctx: Context): List<Pair<String, String>> {
val pm = ctx.packageManager
val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
@Suppress("DEPRECATION")
val resolved = pm.queryIntentActivities(intent, 0)
return resolved.map {
it.loadLabel(pm).toString() to it.activityInfo.packageName
}.distinctBy { it.second}.sortedBy { it.first}
}

fun listApps(ctx: Context): String {
val apps = launchableApps(ctx)
if (apps.isEmpty()) return "没有枚举到可启动的应用。"
return "共 ${apps.size} 个可启动应用:\n" + apps.joinToString("\n") { "${it.first}(${it.second})"}
}

fun launchApp(ctx: Context, query: String): String {
val q = query.trim()
if (q.isBlank()) return "请给出要打开的应用名或包名。"
val pm = ctx.packageManager
val apps = launchableApps(ctx)
val pkg = when {
apps.any { it.second == q} -> q
else -> apps.firstOrNull { it.first.contains(q, ignoreCase = true) || it.second.contains(q, ignoreCase = true)}?.second
?: return "找不到应用「$q」。可先用 list_apps 查看可启动应用列表。"
}
val intent = pm.getLaunchIntentForPackage(pkg)?: return "应用 $pkg 没有可启动的入口。"
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
return try {
ctx.startActivity(intent)
"已打开应用:$pkg"
} catch (e: Exception) {
"打开失败:${e.message}"
}
}
}
