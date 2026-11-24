package app.opengrok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.7.0 leak guard (pure). */
class LeakGuardTest {

    @Test fun luhnAcceptsValidCardsOnly() {
        assertTrue(LeakGuard.luhnValid("4111111111111111"))
        assertFalse(LeakGuard.luhnValid("4111111111111112"))
        assertFalse(LeakGuard.luhnValid("123456789012"))          // too short
        assertFalse(LeakGuard.luhnValid("41111111111111a1"))
    }

    @Test fun cardNumbersAreFoundAndMaskedToLastFour() {
        val text = "卡号 4111 1111 1111 1111 谢谢"
        assertEquals(listOf(LeakGuard.CARD), LeakGuard.scan(text))
        val masked = LeakGuard.mask(text)
        assertFalse(masked.contains("4111 1111"))
        assertTrue(masked.contains("****1111"))
    }

    @Test fun phoneNumbersAndLuhnFailuresAreNotCards() {
        assertFalse(LeakGuard.scan("电话 13800138000").contains(LeakGuard.CARD))
        assertFalse(LeakGuard.scan("订单 4111 1111 1111 1112").contains(LeakGuard.CARD))
    }

    @Test fun cnIdChecksum() {
        assertTrue(LeakGuard.cnIdValid("11010519491231002X"))
        assertTrue(LeakGuard.cnIdValid("11010519491231002x"))
        assertFalse(LeakGuard.cnIdValid("110105194912310021"))
        assertFalse(LeakGuard.cnIdValid("1101051949123100"))
    }

    @Test fun cnIdIsFoundAndMasked() {
        val text = "身份证 11010519491231002X 号"
        assertTrue(LeakGuard.scan(text).contains(LeakGuard.CN_ID))
        assertFalse(LeakGuard.mask(text).contains("11010519491231002X"))
        assertFalse(LeakGuard.scan("身份证 110105194912310021 号").contains(LeakGuard.CN_ID))
    }

    @Test fun otpIsFoundAndMasked() {
        val sms = "【某银行】您的验证码是 482913,5分钟内有效"
        assertTrue(LeakGuard.scan(sms).contains(LeakGuard.OTP))
        val masked = LeakGuard.mask(sms)
        assertFalse(masked.contains("482913"))
        assertTrue(masked.contains("验证码"))
        val en = "582013 is your verification code"
        assertTrue(LeakGuard.scan(en).contains(LeakGuard.OTP))
        assertFalse(LeakGuard.mask(en).contains("582013"))
    }

    @Test fun apiKeysAreFoundAndMasked() {
        val keys = listOf(
            "sk-abcdefghijklmnop1234",
            "ghp_" + "a".repeat(36),
            "AKIA" + "ABCDEFGHIJKLMNOP",
            "xoxb-1234567890-abcdef"
        )
        for (k in keys) {
            val text = "key=$k end"
            assertTrue(k, LeakGuard.scan(text).contains(LeakGuard.API_KEY))
            assertFalse(k, LeakGuard.mask(text).contains(k))
        }
    }

    @Test fun bearerTokensAndPemKeysAreMasked() {
        val bearer = "Authorization: Bearer abcdefghijklmnopqrstuvwxyz123456"
        assertTrue(LeakGuard.scan(bearer).contains(LeakGuard.BEARER))
        assertFalse(LeakGuard.mask(bearer).contains("abcdefghijklmnopqrstuvwxyz123456"))
        val pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----"
        assertTrue(LeakGuard.scan(pem).contains(LeakGuard.PRIVATE_KEY))
        assertFalse(LeakGuard.mask(pem).contains("MIIEowIBAAKCAQEA"))
    }

    @Test fun cleanTextIsUntouched() {
        val text = "明天下午三点开会,会议室 302,记得带电脑"
        assertTrue(LeakGuard.scan(text).isEmpty())
        assertEquals(text, LeakGuard.mask(text))
        assertEquals("", LeakGuard.mask(""))
    }
}
