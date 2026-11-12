package app.opengrok

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Owns the Agent's screen-operation mode: main screen (default, existing behaviour) or the
 * virtual display with its floating preview.
 *
 * Switching is deliberately fail-loud: every precondition is checked up front and the caller gets
 * a concrete reason instead of a silent fallback to main-screen mode.
 */
object AgentMode {
    enum class Mode { Main, Virtual }

    /** Current mode as persisted in the user's settings. */
    val mode = mutableStateOf(Mode.Main)
    /** Last switch error, surfaced in Settings so the user knows why it did not take effect. */
    val lastError = mutableStateOf<String?>(null)

    /** Set by the app to cancel the current Agent run when the floating window's stop button is tapped. */
    var stopHook: (() -> Unit)? = null

    private const val PREFS = "agent_mode"
    private const val KEY = "mode"

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "main")
        mode.value = if (saved == "virtual") Mode.Virtual else Mode.Main
    }

    private fun persist(context: Context, m: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, if (m == Mode.Virtual) "virtual" else "main").apply()
    }

    /** Preconditions for virtual mode, in the order the user should fix them. */
    fun blockers(context: Context): List<String> {
        val out = mutableListOf<String>()
        if (!VirtualScreen.supported()) out.add("系统版本低于 Android 8,不支持虚拟屏悬浮窗")
        if (!ShizukuTools.ready()) out.add("Shizuku 未运行或未授权(虚拟屏上的启动与点击都走 Shizuku)")
        if (!VirtualDisplayOverlay.hasPermission(context)) out.add("缺少「显示在其他应用上层」权限")
        return out
    }

    fun canUseVirtual(context: Context): Boolean = blockers(context).isEmpty()

    /** Human-readable status for the Agent tool. */
    fun describe(): String {
        val base = when (mode.value) {
            Mode.Main -> "当前操控模式:主屏(用无障碍截屏 + 手势操作你正在看的屏幕)"
            Mode.Virtual -> "当前操控模式:虚拟屏悬浮窗(${VirtualScreen.describe()})"
        }
        return base + if (mode.value == Mode.Virtual) {
            "\n注意:虚拟屏模式下无障碍节点树不可用,screen_dump / tap_text 请改用 screen_shot 看图 + tap_at / long_press / drag_at。"
        } else ""
    }

    /**
     * Switches mode. Returns a human-readable result; on failure the previous mode is kept and the
     * reason is stored in [lastError] so the Settings screen can show it too.
     */
    fun switchTo(context: Context, owner: String, virtual: Boolean): String {
        if (!virtual) {
            teardown()
            mode.value = Mode.Main
            persist(context, Mode.Main)
            lastError.value = null
            return "已切回主屏模式。"
        }
        val blocking = blockers(context)
        if (blocking.isNotEmpty()) {
            lastError.value = blocking.joinToString("；")
            return "无法切换到虚拟屏模式:" + blocking.joinToString("；") +
                "。已保持主屏模式,请先处理上面的前置条件。"
        }
        val created = VirtualScreen.ensure(context, owner)
        if (created != null) {
            lastError.value = created
            return "无法创建虚拟屏:$created。已保持主屏模式。"
        }
        // Wire the overlay's stop button to cancel the running Agent task.
        VirtualDisplayOverlay.onStop = { stopHook?.invoke() }
        val shown = VirtualDisplayOverlay.show(context, owner)
        if (shown != null) {
            VirtualScreen.release()
            lastError.value = shown
            return "悬浮窗没能显示:$shown。已保持主屏模式。"
        }
        mode.value = Mode.Virtual
        persist(context, Mode.Virtual)
        lastError.value = null
        return "已切到虚拟屏模式:${VirtualScreen.describe()}。现在用 launch_app 在虚拟屏上打开 App," +
            "再用 screen_shot 看画面、tap_at / long_press / drag_at 操作。你的主屏不受影响。"
    }

    /** Releases the virtual display and dismisses the window; used when a task ends or the user stops. */
    fun teardown() {
        VirtualDisplayOverlay.dismiss()
        VirtualScreen.release()
    }

    /**
     * Called when a chat turn finishes.
     *
     * The virtual display is deliberately NOT released here. Releasing per turn was a real bug:
     * the Agent would open an app on the virtual screen in one turn, the display got torn down at
     * the end of that turn, and the next turn's screen_shot/tap_at reported "虚拟屏未运行" while the
     * user could still see the window. The model then retried and burned tokens.
     *
     * The display now lives for the whole virtual-mode session: it is released only when the user
     * switches back to main-screen mode, taps stop on the floating window, or the app process dies.
     * If the OS ever drops it underneath us, [ensureVirtual] rebuilds it on the next tool call.
     */
    fun onTaskFinished() {
        // Intentionally empty: see the doc comment above.
    }

    /**
     * Guarantees a live virtual display, rebuilding it when the previous one was released or lost.
     * Returns null on success, or a human-readable error.
     */
    fun ensureVirtual(context: Context, owner: String): String? {
        if (VirtualScreen.active.value && VirtualScreen.displayId.value >= 0) {
            VirtualScreen.ownerName.value = owner
            return null
        }
        val blocking = blockers(context)
        if (blocking.isNotEmpty()) return blocking.joinToString("；")
        val err = VirtualScreen.ensure(context, owner)
        if (err != null) return err
        // Re-attach the floating window if it went away with the display.
        if (!VirtualDisplayOverlay.isShowing()) {
            VirtualDisplayOverlay.onStop = { stopHook?.invoke() }
            VirtualDisplayOverlay.show(context, owner)?.let { msg ->
                VirtualScreen.release()
                return msg
            }
        }
        return null
    }
}
