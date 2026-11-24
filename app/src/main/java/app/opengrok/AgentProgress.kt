package app.opengrok

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Live progress of the device Agent while it drives the screen (phone_task loop or individual
 * screen tools). Written by the agent loop (Agent.kt / PhoneOperator), read by UI surfaces:
 * the virtual-display overlay step ring, the on-screen control glow and in-app status pills.
 *
 * All writes happen on the main thread or are single-field primitive writes, which Compose
 * snapshot state tolerates from any thread.
 */
object AgentProgress {
    /** True while a phone_task run or a burst of screen-control tools is in progress. */
    val active = mutableStateOf(false)
    /** Current step (1-based) of a bounded phone_task run; 0 when not in a bounded run. */
    val step = mutableIntStateOf(0)
    /** Step budget of the bounded run (e.g. 15); 0 = free-form (individual screen tools). */
    val maxSteps = mutableIntStateOf(0)
    /** Set when the loop detected it is stuck (repeated action / unchanged screen). */
    val stuck = mutableStateOf(false)
    /** Short label of the current action, e.g. "点击 (512, 840)". */
    val label = mutableStateOf("")
    /** Wall-clock time of the latest screen action; UI fades the glow out ~2.5 s after it. */
    val lastActionMs = mutableLongStateOf(0L)

    fun begin(maxSteps: Int) {
        active.value = true
        step.intValue = 0
        this.maxSteps.intValue = maxSteps
        stuck.value = false
        label.value = ""
        lastActionMs.longValue = System.currentTimeMillis()
    }

    fun action(stepIndex: Int, text: String) {
        active.value = true
        step.intValue = stepIndex
        label.value = text
        lastActionMs.longValue = System.currentTimeMillis()
    }

    fun markStuck() {
        stuck.value = true
        lastActionMs.longValue = System.currentTimeMillis()
    }

    fun end() {
        active.value = false
        step.intValue = 0
        maxSteps.intValue = 0
        label.value = ""
    }
}
