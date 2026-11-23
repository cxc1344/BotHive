package app.opengrok

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Per-Bot / Per-Agent model capability preferences (persisted in SharedPreferences,
 * keeping Data.kt / Store.kt schema untouched).
 *
 *  - multimodal: whether image attachments, screen_shot, and AI browser screenshots are sent as
 *    multimodal `image_url` parts. When off (or if the upstream endpoint rejects image input),
 *    Agent gracefully falls back to text-only instead of failing with HTTP 404.
 *  - reasoningEffort: "auto" (omit param) | "none" | "low" | "medium" | "high".
 */
object ModelPrefs {
    private const val PREFS = "model_capabilities"
    private const val KEY_MM_PREFIX = "mm_"
    private const val KEY_REASON_PREFIX = "reason_"

    val EFFORT_OPTIONS = listOf(
        "auto" to "默认",
        "none" to "关闭",
        "low" to "低",
        "medium" to "中",
        "high" to "高"
    )

    @Volatile
    private var appCtx: Context? = null

    var revision by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    private fun prefs(context: Context? = null) =
        (context ?: appCtx)?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isMultimodal(botId: String, context: Context? = null): Boolean {
        val id = botId.ifBlank { AGENT_ID }
        return prefs(context)?.getBoolean(KEY_MM_PREFIX + id, true) ?: true
    }

    fun setMultimodal(context: Context, botId: String, enabled: Boolean) {
        val id = botId.ifBlank { AGENT_ID }
        appCtx = context.applicationContext
        prefs(context)?.edit()?.putBoolean(KEY_MM_PREFIX + id, enabled)?.apply()
        revision++
    }

    fun reasoningEffort(botId: String, context: Context? = null): String {
        val id = botId.ifBlank { AGENT_ID }
        return prefs(context)?.getString(KEY_REASON_PREFIX + id, "auto") ?: "auto"
    }

    fun setReasoningEffort(context: Context, botId: String, effort: String) {
        val id = botId.ifBlank { AGENT_ID }
        appCtx = context.applicationContext
        prefs(context)?.edit()?.putString(KEY_REASON_PREFIX + id, effort)?.apply()
        revision++
    }
}

/**
 * Shared Liquid Glass configuration card for Multimodal Vision toggle + Reasoning Effort level.
 * Used inside EditBotScreen, EditAgentScreen, and AgentSettingsScreen.
 */
@Composable
fun ModelCapabilitiesSection(
    botId: String,
    multimodal: Boolean,
    onMultimodalChange: (Boolean) -> Unit,
    reasoningEffort: String,
    onReasoningChange: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 16.dp, fallbackBg = Card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("多模态视觉支持（图片 / 截屏）", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "开启后向模型发送图片附件、screen_shot 与浏览器截图；若中转节点实际不支持图像输入，会自动降级纯文本防 404 报错。",
                    color = Sub, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
            LiquidSwitch(checked = multimodal, onCheckedChange = onMultimodalChange)
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("思考程度（Reasoning Effort）", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "控制推理模型的思考深度（兼容 OpenRouter reasoning.effort 与 OpenAI reasoning_effort）；「默认」表示由模型自行决定。",
                color = Sub, fontSize = 11.sp, lineHeight = 16.sp
            )
            val selectedIdx = ModelPrefs.EFFORT_OPTIONS.indexOfFirst { it.first == reasoningEffort }.coerceAtLeast(0)
            LiquidSegmented(
                options = ModelPrefs.EFFORT_OPTIONS.map { it.second },
                selected = selectedIdx,
                onSelect = { i -> onReasoningChange(ModelPrefs.EFFORT_OPTIONS[i].first) },
                modifier = Modifier.fillMaxWidth(),
                height = 38.dp
            )
        }
    }
}
