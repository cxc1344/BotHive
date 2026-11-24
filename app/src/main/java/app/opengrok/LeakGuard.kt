package app.opengrok

/**
 * v0.7.0 leak guard: detects (and masks) data that must not leave the device through a tool call
 * or reach a model unasked: bank card numbers (Luhn), CN resident ID numbers (checksum),
 * verification codes, API keys, Bearer tokens and PEM private keys.
 *
 * Pure Kotlin on purpose (unit-tested in LeakGuardTest). A heuristic, not a DLP product: it
 * prefers false positives (an extra approval card) over false negatives.
 */
object LeakGuard {
    const val CARD = "银行卡号"
    const val CN_ID = "身份证号"
    const val OTP = "验证码"
    const val API_KEY = "API 密钥"
    const val BEARER = "Bearer 令牌"
    const val PRIVATE_KEY = "私钥"

    private val PEM = Regex("-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----[\\s\\S]*?(?:-----END (?:[A-Z0-9]+ )*PRIVATE KEY-----|$)")
    private val BEARER_RE = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{16,}")
    private val KEY_RES = listOf(
        Regex("(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{16,}"),
        Regex("(?<![A-Za-z0-9])ghp_[A-Za-z0-9]{20,}"),
        Regex("(?<![A-Za-z0-9])github_pat_[A-Za-z0-9_]{20,}"),
        Regex("(?<![A-Za-z0-9])AKIA[0-9A-Z]{16}(?![0-9A-Z])"),
        Regex("(?<![A-Za-z0-9])xox[bp]-[A-Za-z0-9-]{10,}")
    )
    private val CARD_RE = Regex("(?<![0-9])[2-6][0-9](?:[ -]?[0-9]){11,17}(?![0-9])")
    private val CN_ID_RE = Regex(
        "(?<![0-9A-Za-z])[1-9][0-9]{5}(?:18|19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9A-Za-z])"
    )
    private val OTP_RE = Regex(
        "(?i)(验证码|校验码|动态码|动态密码|确认码|verification code|security code|one[- ]time (?:pass)?code|otp|code)([^0-9\\n]{0,12}?)([0-9]{4,8})(?![0-9])"
    )
    private val OTP_REVERSE_RE = Regex(
        "(?i)(?<![0-9])([0-9]{4,8})(?![0-9])(\\s*(?:is your|为你的|为您的|是你的|是您的)[^0-9\\n]{0,10}(?:验证码|verification code|code|otp))"
    )

    /** Luhn checksum over a digit string. */
    fun luhnValid(digits: String): Boolean {
        if (digits.length < 13 || digits.length > 19 || digits.any { !it.isDigit() }) return false
        var sum = 0
        var dbl = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (dbl) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            dbl = !dbl
        }
        return sum % 10 == 0
    }

    private val ID_WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    private const val ID_CHECK = "10X98765432"

    /** GB 11643 checksum of an 18-character resident ID number. */
    fun cnIdValid(id: String): Boolean {
        if (id.length != 18) return false
        var sum = 0
        for (i in 0 until 17) {
            val c = id[i]
            if (!c.isDigit()) return false
            sum += (c - '0') * ID_WEIGHTS[i]
        }
        return ID_CHECK[sum % 11] == id[17].uppercaseChar()
    }

    private fun cardHit(raw: String): Boolean = luhnValid(raw.filter { it.isDigit() })

    /** Kinds of sensitive data found in [text] (distinct, stable order); empty = clean. */
    fun scan(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        if (PEM.containsMatchIn(text)) out.add(PRIVATE_KEY)
        if (BEARER_RE.containsMatchIn(text)) out.add(BEARER)
        if (KEY_RES.any { it.containsMatchIn(text) }) out.add(API_KEY)
        if (CN_ID_RE.findAll(text).any { cnIdValid(it.value) }) out.add(CN_ID)
        if (CARD_RE.findAll(text).any { cardHit(it.value) }) out.add(CARD)
        if (OTP_RE.containsMatchIn(text) || OTP_REVERSE_RE.containsMatchIn(text)) out.add(OTP)
        return out.toList()
    }

    /** Returns [text] with every detected secret replaced by a short placeholder. */
    fun mask(text: String): String {
        if (text.isEmpty()) return text
        var s = text
        s = PEM.replace(s, "[私钥已隐藏]")
        s = BEARER_RE.replace(s, "Bearer [令牌已隐藏]")
        for (re in KEY_RES) {
            s = re.replace(s) { m -> m.value.take(4) + "…[密钥已隐藏]" }
        }
        s = CN_ID_RE.replace(s) { m -> if (cnIdValid(m.value)) "[身份证号已隐藏]" else m.value }
        s = CARD_RE.replace(s) { m ->
            val digits = m.value.filter { it.isDigit() }
            if (luhnValid(digits)) "[卡号 ****" + digits.takeLast(4) + "]" else m.value
        }
        s = OTP_RE.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + "••••" }
        s = OTP_REVERSE_RE.replace(s) { m -> "••••" + m.groupValues[2] }
        return s
    }
}
