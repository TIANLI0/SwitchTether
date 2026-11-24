package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.PushInfo
import io.github.tianli0.switchtether.remote.protocol.PushRegistration
import io.github.tianli0.switchtether.remote.protocol.PushTestResult
import io.github.tianli0.switchtether.remote.protocol.SessionSearch
import io.github.tianli0.switchtether.remote.push.WebPushCrypto
import java.io.File
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Push wake (PROTOCOL §7.12) and session search (§7.13) models, and the RFC 8291 receiver. */
class PushAndSearchTest {
    private fun fixture(name: String): String {
        val dir = System.getProperty("switchtether.fixtures") ?: "../../../protocol/fixtures"
        return File(dir, name).readText()
    }

    @Test
    fun rfc8291AppendixAVectorDecrypts() {
        val message = WebPushCrypto.decode(
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
        )
        val plain = WebPushCrypto.decrypt(
            message,
            WebPushCrypto.privateFromScalar(WebPushCrypto.decode("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")),
            WebPushCrypto.decode("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
            WebPushCrypto.decode("BTBZMqHH6r4Tts7J_aSIgg"),
        )
        assertEquals("When I grow up, I want to be a watermelon", plain?.toString(Charsets.UTF_8))
    }

    @Test
    fun messageFromTheDesktopEncryptorDecrypts() {
        val f = ProtocolJson.parseToJsonElement(fixture("push_wake.json")).jsonObject
        val reg = ProtocolJson.decodeFromJsonElement(PushRegistration.serializer(), f["register"]!!)
        val plain = WebPushCrypto.decrypt(
            WebPushCrypto.decode(f["message"]!!.jsonPrimitive.content),
            WebPushCrypto.privateFromScalar(WebPushCrypto.decode(f["receiverPrivateKey"]!!.jsonPrimitive.content)),
            WebPushCrypto.decode(reg.p256dh),
            WebPushCrypto.decode(reg.auth),
        )
        assertEquals(f["plaintext"]!!.jsonPrimitive.content, plain?.toString(Charsets.UTF_8))
        // Wrong auth secret: not ours, ignored.
        assertNull(
            WebPushCrypto.decrypt(
                WebPushCrypto.decode(f["message"]!!.jsonPrimitive.content),
                WebPushCrypto.privateFromScalar(WebPushCrypto.decode(f["receiverPrivateKey"]!!.jsonPrimitive.content)),
                WebPushCrypto.decode(reg.p256dh),
                ByteArray(16),
            ),
        )
        val info = ProtocolJson.decodeFromJsonElement(PushInfo.serializer(), f["info"]!!)
        assertTrue(info.registered)
        assertEquals(87, info.vapidPublicKey!!.length)
        assertTrue(ProtocolJson.decodeFromJsonElement(PushTestResult.serializer(), f["test"]!!).delivered)
        // What the phone sends has exactly the wire field names.
        assertEquals(f["register"], ProtocolJson.parseToJsonElement(ProtocolJson.encodeToString(PushRegistration.serializer(), reg)))
    }

    @Test
    fun freshKeysHaveTheWireShapes() {
        val k = WebPushCrypto.newKeys()
        assertEquals(65, WebPushCrypto.decode(k.publicKey).size)
        assertEquals(0x04.toByte(), WebPushCrypto.decode(k.publicKey)[0])
        assertEquals(16, WebPushCrypto.decode(k.auth).size)
        assertFalse(k.publicKey.contains('='))
        // Garbage never decrypts and never throws.
        assertNull(WebPushCrypto.decrypt(ByteArray(10), k))
        assertNull(WebPushCrypto.decrypt(ByteArray(200), k))
        assertArrayEquals(WebPushCrypto.decode(k.auth), WebPushCrypto.decode(k.auth + "=="))
    }

    @Test
    fun sessionSearchFixture() {
        val s = ProtocolJson.decodeFromString(SessionSearch.serializer(), fixture("session_search.json"))
        assertFalse(s.partial)
        assertEquals(2, s.results.size)
        val r = s.results[0]
        assertEquals("claude:2b1c9f8e-0d1a-4c55-9a77-5e6f7a8b9c0d", r.sessionId)
        assertEquals("claude_code", r.agent)
        assertEquals("修复登录页面", r.title)
        assertEquals("D:\\proj\\app", r.cwd)
        assertEquals(1763452800000L, r.timeMs)
        assertEquals(2, r.matchCount)
        assertTrue(r.snippet.contains("login"))
        assertNull(s.results[1].title)
        assertNull(s.results[1].timeMs)
    }
}
