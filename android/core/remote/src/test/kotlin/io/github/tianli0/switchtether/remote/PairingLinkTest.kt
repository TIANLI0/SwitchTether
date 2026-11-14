package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.pairing.PairingLink
import io.github.tianli0.switchtether.remote.pairing.PairingLinkException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingLinkTest {
    private val fp = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    // 32 random bytes, base64url, no padding (43 chars)
    private val secret = "Xq3v0-9aZb_1234567890abcdefghijklmnopqrstuv"

    private fun link(extra: String = "", h: String = "192.168.1.20,100.88.1.7", n: String = "DESKTOP-01") =
        "switchtether://pair?v=1&h=$h&p=47615&fp=$fp&id=2f6c1b8e-4b0d&s=$secret&n=$n$extra"

    @Test
    fun parsesProtocolExample() {
        val l = PairingLink.parse(link())
        assertEquals(1, l.version)
        assertEquals(listOf("192.168.1.20", "100.88.1.7"), l.hosts)
        assertEquals(47615, l.port)
        assertEquals(fp, l.fingerprint)
        assertEquals("2f6c1b8e-4b0d", l.pairingId)
        assertEquals(secret, l.secret)
        assertEquals("DESKTOP-01", l.desktopName)
    }

    @Test
    fun toStringNeverContainsSecret() {
        assertFalse(PairingLink.parse(link()).toString().contains(secret))
    }

    @Test
    fun bracketedIpv6AndPercentEncodedName() {
        val l = PairingLink.parse(link(h = "10.0.2.2,[fd62:1234::5],%5Bfe80::1%25eth0%5D", n = "My%20PC%20%E2%9C%93"))
        assertEquals(listOf("10.0.2.2", "fd62:1234::5", "fe80::1%eth0"), l.hosts)
        assertEquals("My PC ✓", l.desktopName)
    }

    @Test
    fun trimsWhitespaceAndAcceptsUppercaseSchemeAndFingerprint() {
        val l = PairingLink.parse("  " + link().replace("switchtether://", "SwitchTether://").replace(fp, fp.uppercase()) + "\n")
        assertEquals(fp, l.fingerprint)
    }

    @Test
    fun defaultPortWhenMissing() {
        val l = PairingLink.parse(link().replace("&p=47615", ""))
        assertEquals(PairingLink.DEFAULT_PORT, l.port)
    }

    @Test
    fun nameFallsBackToFirstHostAndStripsControlChars() {
        assertEquals("192.168.1.20", PairingLink.parse(link(n = "")).desktopName)
        assertEquals("ab", PairingLink.parse(link(n = "a%07%E2%80%AEb")).desktopName)
    }

    @Test
    fun rejectsInvalidLinks() {
        fun reason(s: String) = try {
            PairingLink.parse(s)
            null
        } catch (e: PairingLinkException) {
            e.reason
        }
        assertEquals(PairingLink.Reason.NOT_A_PAIRING_LINK, reason("https://example.com/pair?v=1"))
        assertEquals(PairingLink.Reason.NOT_A_PAIRING_LINK, reason("switchtether://other?v=1"))
        assertEquals(PairingLink.Reason.UNSUPPORTED_VERSION, reason(link().replace("v=1", "v=2")))
        assertEquals(PairingLink.Reason.INVALID_FIELD, reason(link().replace(fp, fp.dropLast(2))))
        assertEquals(PairingLink.Reason.INVALID_FIELD, reason(link().replace(fp, "zz" + fp.drop(2))))
        assertEquals(PairingLink.Reason.INVALID_FIELD, reason(link().replace(secret, "c2hvcnQ")))
        assertEquals(PairingLink.Reason.INVALID_FIELD, reason(link().replace(secret, "$secret+/")))
        assertEquals(PairingLink.Reason.INVALID_FIELD, reason(link().replace("p=47615", "p=70000")))
        assertEquals(PairingLink.Reason.INVALID_FIELD, reason(link(h = "evil host")))
        assertEquals(PairingLink.Reason.MISSING_FIELD, reason(link().replace("&id=2f6c1b8e-4b0d", "")))
        assertEquals(PairingLink.Reason.MISSING_FIELD, reason(link(h = "")))
        assertEquals(PairingLink.Reason.DUPLICATE_FIELD, reason(link("&s=$secret")))
        assertNull(PairingLink.parseOrNull("not a link"))
    }

    @Test
    fun hostListIsDeduplicatedAndBounded() {
        assertEquals(listOf("a", "b"), PairingLink.parse(link(h = "a,b,a")).hosts)
        val many = (1..9).joinToString(",") { "10.0.0.$it" }
        assertTrue(PairingLink.parseOrNull(link(h = many)) == null)
    }
}
