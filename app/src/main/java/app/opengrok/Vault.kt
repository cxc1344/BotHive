package app.opengrok

import android.content.Context
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf

/**
 * v0.7.0 credential vault (凭据保险箱). Values are AES-GCM encrypted with the Android Keystore key
 * (see [Secrets]) and stored in their own SharedPreferences file, outside state.json, so they are
 * never exported or shared.
 *
 * A value is NEVER returned to a model, a log, a tool output or a notification: models only see
 * names (tool `secret_list`) and ask the app to type a value into the AI browser
 * (`browser_fill_secret`), which reports nothing but "已填入".
 */
object Vault {
    private const val PREFS = "vault"
    private const val PREFIX = "v_"
    private val NAME_RE = Regex("[\\p{L}\\p{N}_.@ -]{1,40}")

    @Volatile
    private var appCtx: Context? = null

    val revision: MutableIntState = mutableIntStateOf(0)

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    private fun prefs(context: Context? = null) =
        (context?.applicationContext ?: appCtx)?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isValidName(name: String): Boolean = NAME_RE.matches(name.trim())

    fun names(): List<String> =
        prefs()?.all?.keys?.filter { it.startsWith(PREFIX) }?.map { it.removePrefix(PREFIX) }?.sorted() ?: emptyList()

    /** Stores (or replaces) a credential. Returns false on an invalid name, empty value or a Keystore failure. */
    fun put(context: Context, name: String, value: String): Boolean {
        appCtx = context.applicationContext
        val n = name.trim()
        if (!isValidName(n) || value.isEmpty() || value.length > 4_000) return false
        val enc = try { Secrets.enc(value) } catch (_: Exception) { return false }
        if (enc.isEmpty()) return false
        val ok = prefs(context)?.edit()?.putString(PREFIX + n, enc)?.commit() ?: false
        revision.intValue = revision.intValue + 1
        return ok
    }

    fun remove(context: Context, name: String) {
        appCtx = context.applicationContext
        prefs(context)?.edit()?.remove(PREFIX + name.trim())?.apply()
        revision.intValue = revision.intValue + 1
    }

    /** Decrypted value for the app's own use (browser fill). Internal: must never be shown or sent to a model. */
    internal fun valueOf(name: String): String? {
        val raw = prefs()?.getString(PREFIX + name.trim(), null) ?: return null
        return Secrets.decOrNull(raw)?.takeIf { it.isNotEmpty() }
    }

    /** True when [text] contains any stored value (length >= 4): the leak guard escalates such tool calls. */
    internal fun leaksAny(text: String): Boolean {
        if (text.length < 4) return false
        for (n in names()) {
            val v = valueOf(n) ?: continue
            if (v.length >= 4 && text.contains(v)) return true
        }
        return false
    }

    /** Replaces any stored value in [text] with a placeholder (defence in depth for outputs). */
    internal fun redact(text: String): String {
        if (text.length < 4) return text
        var s = text
        for (n in names()) {
            val v = valueOf(n) ?: continue
            if (v.length >= 4 && s.contains(v)) s = s.replace(v, "[凭据「$n」已隐藏]")
        }
        return s
    }
}
