package app.opengrok

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Lightweight native voice-to-text trigger for composers.
 * Uses Android's built-in speech recognition sheet (no extra SDK or microphone boilerplate).
 */
@Composable
fun VoiceInputButton(
    onText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrBlank()) {
                onText(spoken)
            }
        }
    }

    Box(
        modifier = modifier
            .size(38.dp)
            .pressBounce {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "直接说出你的任务，Bix 帮你转文字…")
                }
                val ok = runCatching { launcher.launch(intent) }.isSuccess
                if (!ok) {
                    Toast.makeText(ctx, "当前系统未安装语音识别服务", Toast.LENGTH_SHORT).show()
                }
            }
            .harmonyGlass(
                thickness = ImmersiveThickness.ULTRA_THIN,
                radius = 999.dp,
                fallbackBg = PILL
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(Ic.Mic, "语音输入", tint = ThemeAccentPale, modifier = Modifier.size(17.dp))
    }
}
