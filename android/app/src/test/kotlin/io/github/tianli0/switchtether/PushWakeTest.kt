package io.github.tianli0.switchtether

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.push.PushWake
import io.github.tianli0.switchtether.remote.api.Endpoint
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.push.WebPushCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** UnifiedPush connector (spec AND_3) and the desktop hand-over of push wake (ANDROID_UI.md §28.1). */
@RunWith(AndroidJUnit4::class)
class PushWakeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("push_wake_test", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var wakes = 0
    private var api: RemoteApi? = null
    private lateinit var server: MockWebServer

    private fun pushWake() = PushWake(
        context = context,
        scope = scope,
        prefs = prefs,
        api = { api },
        desktopKey = { "server-1/device-1" },
        desktopName = { "Desk" },
        onWake = { wakes++ },
        // Only a message "encrypted" for the stored keys decrypts (the real RFC 8291 path is tested in core:remote).
        decrypt = { bytes, keys -> if (bytes.contentEquals(sealedFor(keys.publicKey))) "{\"v\":1}".toByteArray() else null },
    )

    private fun sealedFor(publicKey: String) = "sealed:$publicKey".toByteArray()

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        shadowOf(context as android.app.Application).clearRegisteredReceivers()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun installDistributor(pkg: String, label: String = pkg) {
        val ri = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.Receiver"
                applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label }
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(PushWake.ACTION_REGISTER), ri)
    }

    private fun sent(): List<Intent> = shadowOf(context as android.app.Application).broadcastIntents

    /** The API of a fake computer over plain HTTP (the TLS layer is covered elsewhere). */
    private fun fakeDesktopApi(): RemoteApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request()
            chain.proceed(r.newBuilder().url(r.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()).build())
        }.build()
        return RemoteApi(client, Endpoint(server.hostName, server.port))
    }

    @Test
    fun withoutADistributorItSaysSo() = runBlocking {
        val p = pushWake()
        p.enable()
        assertEquals(PushWake.Status.NO_DISTRIBUTOR, p.state.value.status)
        assertTrue(p.state.value.enabled)
        p.disable()
        assertEquals(PushWake.Status.OFF, p.state.value.status)
    }

    @Test
    fun registrationEndpointMessagesAndFailures() = runBlocking {
        installDistributor("io.heckel.ntfy", "ntfy")
        val p = pushWake()
        assertEquals(listOf("io.heckel.ntfy"), p.distributors().map { it.packageName })
        p.enable()
        assertEquals(PushWake.Status.REGISTERING, p.state.value.status)
        val reg = sent().last { it.action == PushWake.ACTION_REGISTER }
        assertEquals("io.heckel.ntfy", reg.`package`)
        val token = reg.getStringExtra(PushWake.EXTRA_TOKEN)!!
        assertTrue(token.length in 30..100)
        assertTrue(reg.getStringExtra(PushWake.EXTRA_MESSAGE)!!.contains("Desk"))

        // Another app's (or a stale) token is ignored.
        p.handle(Intent(PushWake.ACTION_NEW_ENDPOINT).putExtra(PushWake.EXTRA_TOKEN, "nope").putExtra(PushWake.EXTRA_ENDPOINT, "https://evil/x"))
        assertEquals(PushWake.Status.REGISTERING, p.state.value.status)

        p.handle(
            Intent(PushWake.ACTION_NEW_ENDPOINT).putExtra(PushWake.EXTRA_TOKEN, token)
                .putExtra(PushWake.EXTRA_ENDPOINT, "https://ntfy.example/upAbc?up=1").putExtra(PushWake.EXTRA_ID, "m1"),
        )
        assertEquals(PushWake.Status.PENDING_DESKTOP, p.state.value.status)
        val ack = sent().last { it.action == PushWake.ACTION_ACK }
        assertEquals("m1", ack.getStringExtra(PushWake.EXTRA_ID))
        assertEquals(token, ack.getStringExtra(PushWake.EXTRA_TOKEN))

        // A message the computer encrypted for our keys wakes; anything else does not.
        val keys = prefs.getString("keys", null)!!.split('|')
        assertEquals(65, WebPushCrypto.decode(keys[1]).size)
        val message = sealedFor(keys[1])
        p.handle(Intent(PushWake.ACTION_MESSAGE).putExtra(PushWake.EXTRA_TOKEN, token).putExtra(PushWake.EXTRA_BYTES, ByteArray(120)).putExtra(PushWake.EXTRA_ID, "m2"))
        assertEquals(0, wakes)
        p.handle(Intent(PushWake.ACTION_MESSAGE).putExtra(PushWake.EXTRA_TOKEN, "nope").putExtra(PushWake.EXTRA_BYTES, message))
        assertEquals(0, wakes)
        p.handle(Intent(PushWake.ACTION_MESSAGE).putExtra(PushWake.EXTRA_TOKEN, token).putExtra(PushWake.EXTRA_BYTES, message).putExtra(PushWake.EXTRA_ID, "m3"))
        assertEquals(1, wakes)
        assertTrue(p.state.value.lastWakeMs > 0)
        assertEquals("m3", sent().last { it.action == PushWake.ACTION_ACK }.getStringExtra(PushWake.EXTRA_ID))

        // The distributor refuses: the token is not used again.
        p.handle(Intent(PushWake.ACTION_REGISTRATION_FAILED).putExtra(PushWake.EXTRA_TOKEN, token).putExtra(PushWake.EXTRA_REASON, "ACTION_REQUIRED"))
        assertEquals(PushWake.Status.FAILED, p.state.value.status)
        assertEquals("ACTION_REQUIRED", p.state.value.reason)
        p.handle(Intent(PushWake.ACTION_MESSAGE).putExtra(PushWake.EXTRA_TOKEN, token).putExtra(PushWake.EXTRA_BYTES, message))
        assertEquals(1, wakes)
        p.enable()
        val again = sent().last { it.action == PushWake.ACTION_REGISTER }.getStringExtra(PushWake.EXTRA_TOKEN)
        assertNotNull(again)
        assertFalse(again == token)

        // Off: the registration is withdrawn at the distributor.
        p.disable()
        assertEquals(again, sent().last { it.action == PushWake.ACTION_UNREGISTER }.getStringExtra(PushWake.EXTRA_TOKEN))
        assertNull(prefs.getString("keys", null))
    }

    @Test
    fun theComputerGetsTheEndpointOnceAndOlderComputersAreRecognised() = runBlocking {
        installDistributor("io.heckel.ntfy")
        val p = pushWake()
        p.enable()
        val token = sent().last { it.action == PushWake.ACTION_REGISTER }.getStringExtra(PushWake.EXTRA_TOKEN)!!
        p.handle(Intent(PushWake.ACTION_NEW_ENDPOINT).putExtra(PushWake.EXTRA_TOKEN, token).putExtra(PushWake.EXTRA_ENDPOINT, "https://ntfy.example/upAbc"))
        api = fakeDesktopApi()

        // First connection: the computer does not know it yet → PUT /v1/push.
        server.enqueue(MockResponse.Builder().body("""{"registered":false,"vapidPublicKey":null}""").build())
        server.enqueue(MockResponse.Builder().body("""{"registered":true}""").build())
        // The connection that NEW_ENDPOINT started may already have done it; wait for the state.
        p.sync()
        val requests = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }.toList()
        val put = requests.first { it.method == "PUT" }
        assertEquals("/v1/push", put.url.encodedPath)
        val body = ProtocolJson.parseToJsonElement(put.body!!.utf8()).jsonObject
        assertEquals("https://ntfy.example/upAbc", body["endpoint"]!!.jsonPrimitive.content)
        assertEquals(65, WebPushCrypto.decode(body["p256dh"]!!.jsonPrimitive.content).size)
        assertEquals(16, WebPushCrypto.decode(body["auth"]!!.jsonPrimitive.content).size)
        assertEquals(PushWake.Status.ACTIVE, p.state.value.status)

        // Next connection: it has it → only GET.
        server.enqueue(MockResponse.Builder().body("""{"registered":true}""").build())
        p.sync()
        val next = generateSequence { server.takeRequest(500, java.util.concurrent.TimeUnit.MILLISECONDS) }.toList()
        assertEquals(listOf("GET"), next.map { it.method })

        // A computer without push wake answers 404.
        server.enqueue(MockResponse.Builder().code(404).body("""{"code":"not_found","message":"no such endpoint"}""").build())
        p.sync()
        assertEquals(PushWake.Status.DESKTOP_UNSUPPORTED, p.state.value.status)
    }
}
