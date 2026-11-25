package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.forward.DshWebUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshWebUrlsTest {
    @Test
    fun startUrlUsesOnlyAWellFormedLaunchPath() {
        assertEquals("http://localhost:3080/?token=abcdefghijklmnop_-12", DshWebUrls.startUrl(3080, "/?token=abcdefghijklmnop_-12"))
        assertEquals("http://localhost:3080/", DshWebUrls.startUrl(3080, null))
        assertEquals("http://localhost:3081/", DshWebUrls.startUrl(3081, "//evil.example/?token=abcdefghijklmnop"))
        assertEquals("http://localhost:3081/", DshWebUrls.startUrl(3081, "/?token=short"))
    }

    @Test
    fun navigationStaysOnTheForwardedOrigin() {
        assertTrue(DshWebUrls.isSameOrigin("http://localhost:3080/sessions/1?x=2", 3080))
        assertFalse(DshWebUrls.isSameOrigin("http://localhost:3081/", 3080))
        assertFalse(DshWebUrls.isSameOrigin("https://localhost:3080/", 3080))
        assertFalse(DshWebUrls.isSameOrigin("http://127.0.0.1:3080/", 3080))
        assertFalse(DshWebUrls.isSameOrigin("http://localhost.evil.example:3080/", 3080))
        assertFalse(DshWebUrls.isSameOrigin("file:///sdcard/x", 3080))
        assertFalse(DshWebUrls.isSameOrigin(null, 3080))
        assertTrue(DshWebUrls.isExternalWeb("https://github.com/deepseek-ai"))
        assertFalse(DshWebUrls.isExternalWeb("intent://x#Intent;end"))
        assertFalse(DshWebUrls.isExternalWeb("javascript:alert(1)"))
    }

    @Test
    fun tokenFromWhatTheUserPastes() {
        val t = "EXAMPLEtokenEXAMPLEtokenEXAMPLE_-123"
        assertEquals(t, DshWebUrls.tokenOf("dsh web: http://127.0.0.1:52166/?token=$t"))
        assertEquals(t, DshWebUrls.tokenOf("  http://127.0.0.1:3080/?a=1&token=$t\n"))
        assertEquals(t, DshWebUrls.tokenOf(t))
        assertNull(DshWebUrls.tokenOf("dsh web: http://127.0.0.1:3080/"))
        assertNull(DshWebUrls.tokenOf("hello"))
    }

    @Test
    fun pageColoursAndIconContrast() {
        assertEquals(0xFFFFFFFF.toInt(), DshWebUrls.parseCssColor("\"rgb(255, 255, 255)\""))
        assertEquals(0xFF101214.toInt(), DshWebUrls.parseCssColor("rgba(16, 18, 20, 1)"))
        assertNull(DshWebUrls.parseCssColor("rgba(0, 0, 0, 0)"))
        assertEquals(0xFF112233.toInt(), DshWebUrls.parseCssColor("#123"))
        assertEquals(0xFF0A0B0C.toInt(), DshWebUrls.parseCssColor("#0a0b0c"))
        assertNull(DshWebUrls.parseCssColor("canvas"))
        assertTrue(DshWebUrls.wantsDarkIcons(0xFFFFFFFF.toInt()))
        assertFalse(DshWebUrls.wantsDarkIcons(0xFF101214.toInt()))
    }
}
