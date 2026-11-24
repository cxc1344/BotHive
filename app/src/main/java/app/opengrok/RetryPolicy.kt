package app.opengrok

/**
 * Pure decision logic for model API calls (no Android, no I/O), so it is covered by unit tests.
 *
 * v0.7.0: the single-key path used to return straight from the first attempt, which skipped the
 * 429 backoff, the gateway retries and the text-only image fallback entirely — and a single key is
 * what most users have. Every call now goes through the same policy.
 */
internal object RetryPolicy {
    const val MAX_RETRIES = 5

    enum class Action { ROTATE_KEY, BACKOFF, STRIP_IMAGES, FAIL }

    private val TRANSIENT_CODES = setOf(408, 500, 502, 503, 504, 520, 521, 522, 523, 524)
    private val CODE_RE = Regex("""^HTTP (\d{3})""")

    /** A Bot / Provider key field may hold several keys, one per line. Never empty. */
    fun parseKeyPool(raw: String): List<String> =
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("") }

    /** The key to use for one-off requests (model list, self-check): the first key of the pool. */
    fun firstKey(raw: String): String = parseKeyPool(raw).first()

    /** Status code from an exception message of the form "HTTP 429: ...", or null. */
    fun httpCode(message: String): Int? = CODE_RE.find(message)?.groupValues?.get(1)?.toIntOrNull()

    fun looksLikeImageUnsupported(message: String): Boolean =
        message.contains("image input", ignoreCase = true) ||
            message.contains("Image Support", ignoreCase = true) ||
            message.contains("vision", ignoreCase = true)

    /**
     * @param isIo whether the failure was an IOException (network-level when there is no HTTP code)
     * @param hasMoreKeys whether another key of the pool is still untried
     * @param retriesSoFar backoff retries already spent on the current key
     * @param hasImages whether the request still carries image parts
     */
    fun decide(message: String, isIo: Boolean, hasMoreKeys: Boolean, retriesSoFar: Int, hasImages: Boolean): Action {
        val code = httpCode(message)
        if (code == 429) {
            if (hasMoreKeys) return Action.ROTATE_KEY
            return if (retriesSoFar < MAX_RETRIES) Action.BACKOFF else Action.FAIL
        }
        if ((code == 400 || code == 404) && hasImages && looksLikeImageUnsupported(message)) return Action.STRIP_IMAGES
        if (code == 401 || code == 403) return if (hasMoreKeys) Action.ROTATE_KEY else Action.FAIL
        val transient = (code != null && code in TRANSIENT_CODES) || (code == null && isIo)
        if (transient && retriesSoFar < MAX_RETRIES) return Action.BACKOFF
        return Action.FAIL
    }

    /** Linear backoff, capped at 5 s; 429 waits a little longer per step. */
    fun backoffMs(retry: Int, rateLimited: Boolean): Long =
        ((if (rateLimited) 1_200L else 1_000L) * retry.coerceAtLeast(1)).coerceAtMost(5_000L)
}
