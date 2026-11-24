package io.github.tianli0.switchtether.push

import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.protocol.PushRegistration
import io.github.tianli0.switchtether.remote.protocol.PushTestResult
import io.github.tianli0.switchtether.remote.push.WebPushCrypto
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Push wake through UnifiedPush (spec AND_3, https://unifiedpush.org/developers/spec/android/),
 * implemented without the connector library.
 *
 * One registration (one connection token) for the paired computer. The distributor (e.g. ntfy)
 * gives an endpoint; the phone hands it to the computer with fresh RFC 8291 receiver keys
 * (`PUT /v1/push`). The computer then posts an encrypted, content-free message whenever a session
 * needs attention while this phone has no event stream. A message is only ever a reason to
 * connect: it must decrypt with our keys (anything else is ignored), and its content is not used.
 *
 * State lives in app-private preferences. The receiver private key only decrypts wake messages
 * (which contain nothing), so it is not kept in the Keystore.
 */
class PushWake(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    /** The API while connected to the paired computer, else null. */
    private val api: () -> RemoteApi?,
    /** Identifies the paired computer (`serverId/deviceId`); null when unpaired. */
    private val desktopKey: suspend () -> String?,
    private val desktopName: suspend () -> String?,
    /** Called for every genuine wake message. */
    private val onWake: () -> Unit,
    /** RFC 8291 decryption (replaced in Robolectric tests, whose sandbox has no usable JCE). */
    private val decrypt: (ByteArray, WebPushCrypto.Keys) -> ByteArray? = WebPushCrypto::decrypt,
) {
    enum class Status {
        /** Switched off. */
        OFF,
        /** On, but no UnifiedPush distributor is installed. */
        NO_DISTRIBUTOR,
        /** Waiting for the distributor's endpoint. */
        REGISTERING,
        /** Endpoint known, not yet handed to the computer (connect once). */
        PENDING_DESKTOP,
        /** The computer has the endpoint. */
        ACTIVE,
        /** The computer does not support push wake (update SwitchTether on the computer). */
        DESKTOP_UNSUPPORTED,
        /** The distributor refused or dropped the registration ([State.reason]). */
        FAILED,
    }

    data class State(
        val enabled: Boolean = false,
        val status: Status = Status.OFF,
        val distributor: String? = null,
        val reason: String? = null,
        val lastWakeMs: Long = 0,
    )

    data class Distributor(val packageName: String, val label: String)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state.asStateFlow()
    private val lock = Mutex()

    private fun load(): State {
        val enabled = prefs.getBoolean(K_ENABLED, false)
        return State(
            enabled = enabled,
            status = if (!enabled) Status.OFF else runCatching { Status.valueOf(prefs.getString(K_STATUS, null) ?: "") }.getOrDefault(Status.REGISTERING),
            distributor = prefs.getString(K_DISTRIBUTOR, null),
            reason = prefs.getString(K_REASON, null),
            lastWakeMs = prefs.getLong(K_LAST_WAKE, 0),
        )
    }

    private fun update(status: Status, reason: String? = null) {
        prefs.edit().putString(K_STATUS, status.name).putString(K_REASON, reason).apply()
        _state.value = load()
    }

    /** Installed distributors (apps that handle the distributor REGISTER broadcast). */
    fun distributors(): List<Distributor> {
        val pm = context.packageManager
        return pm.queryBroadcastReceivers(Intent(ACTION_REGISTER), 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { Distributor(it.packageName, pm.getApplicationLabel(it).toString()) }
    }

    /** Turn push wake on with [distributor] (or the only / previously chosen one). */
    suspend fun enable(distributor: String? = null) = lock.withLock {
        val all = distributors()
        val chosen = distributor ?: prefs.getString(K_DISTRIBUTOR, null)?.takeIf { p -> all.any { it.packageName == p } } ?: all.singleOrNull()?.packageName
        prefs.edit().putBoolean(K_ENABLED, true).apply()
        if (chosen == null) {
            update(Status.NO_DISTRIBUTOR)
            return@withLock
        }
        if (chosen != prefs.getString(K_DISTRIBUTOR, null)) {
            // Another distributor: the old registration is withdrawn, the new one starts fresh.
            prefs.getString(K_DISTRIBUTOR, null)?.let { old -> prefs.getString(K_TOKEN, null)?.let { unregisterAt(old, it) } }
            prefs.edit().remove(K_TOKEN).remove(K_ENDPOINT).remove(K_SENT_FOR).putString(K_DISTRIBUTOR, chosen).apply()
        }
        registerLocked()
    }

    /** Turn push wake off: withdraw the registration everywhere. */
    suspend fun disable() = lock.withLock {
        val token = prefs.getString(K_TOKEN, null)
        val dist = prefs.getString(K_DISTRIBUTOR, null)
        if (token != null && dist != null) unregisterAt(dist, token)
        runCatching { api()?.unregisterPush() }
        prefs.edit().putBoolean(K_ENABLED, false).remove(K_TOKEN).remove(K_ENDPOINT).remove(K_SENT_FOR)
            .remove(K_KEYS).remove(K_VAPID).apply()
        update(Status.OFF)
    }

    /** Ask the distributor for (or to confirm) the endpoint. Spec: done again on every start. */
    private suspend fun registerLocked() {
        val dist = prefs.getString(K_DISTRIBUTOR, null) ?: return update(Status.NO_DISTRIBUTOR)
        if (distributors().none { it.packageName == dist }) return update(Status.NO_DISTRIBUTOR)
        val token = prefs.getString(K_TOKEN, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(K_TOKEN, it).apply() }
        // The computer's VAPID key, when connected (some push servers require it).
        val vapid = runCatching { api()?.pushInfo()?.vapidPublicKey }.getOrNull() ?: prefs.getString(K_VAPID, null)
        vapid?.let { prefs.edit().putString(K_VAPID, it).apply() }
        val intent = Intent(ACTION_REGISTER).setPackage(dist)
            .putExtra(EXTRA_TOKEN, token)
            .putExtra(EXTRA_APPLICATION, context.packageName) // AND_2 distributors
            .putExtra(EXTRA_MESSAGE, "SwitchTether" + (desktopName()?.let { " · $it" } ?: ""))
        vapid?.let { intent.putExtra(EXTRA_VAPID, it) }
        if (prefs.getString(K_ENDPOINT, null) == null) update(Status.REGISTERING)
        send(intent)
    }

    private fun unregisterAt(distributor: String, token: String) =
        send(Intent(ACTION_UNREGISTER).setPackage(distributor).putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_APPLICATION, context.packageName))

    private fun send(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val opts = BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
            context.sendBroadcast(intent, null, opts.toBundle())
        } else {
            val pi = PendingIntent.getBroadcast(context, 0, Intent("org.unifiedpush.dummy_app").setPackage("org.unifiedpush.dummy_app"), PendingIntent.FLAG_IMMUTABLE)
            context.sendBroadcast(intent.putExtra(EXTRA_PI, pi))
        }
    }

    private fun ack(token: String, id: String?) {
        if (id.isNullOrEmpty()) return
        val dist = prefs.getString(K_DISTRIBUTOR, null) ?: return
        send(Intent(ACTION_ACK).setPackage(dist).putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_ID, id))
    }

    private fun keys(): WebPushCrypto.Keys? = prefs.getString(K_KEYS, null)?.split('|')?.takeIf { it.size == 3 }?.let { WebPushCrypto.Keys(it[0], it[1], it[2]) }

    /** Entry point of [PushReceiver]. Unknown tokens are ignored, as the spec requires. */
    fun handle(intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        if (!prefs.getBoolean(K_ENABLED, false) || token != prefs.getString(K_TOKEN, null)) return
        when (intent.action) {
            ACTION_NEW_ENDPOINT -> {
                ack(token, intent.getStringExtra(EXTRA_ID))
                val endpoint = intent.getStringExtra(EXTRA_ENDPOINT) ?: return
                if (endpoint != prefs.getString(K_ENDPOINT, null)) {
                    // New endpoint: new receiver keys, and the computer must learn both.
                    val k = WebPushCrypto.newKeys()
                    prefs.edit().putString(K_ENDPOINT, endpoint).putString(K_KEYS, "${k.privatePkcs8}|${k.publicKey}|${k.auth}").remove(K_SENT_FOR).apply()
                }
                if (prefs.getString(K_SENT_FOR, null) == null) update(Status.PENDING_DESKTOP)
                scope.launch { sync() }
            }
            ACTION_MESSAGE -> {
                ack(token, intent.getStringExtra(EXTRA_ID))
                val bytes = intent.getByteArrayExtra(EXTRA_BYTES) ?: intent.getStringExtra("message")?.toByteArray() ?: return
                val k = keys() ?: return
                // Only a message the computer encrypted for us counts; its content says nothing more.
                if (decrypt(bytes, k) == null) return
                prefs.edit().putLong(K_LAST_WAKE, System.currentTimeMillis()).apply()
                _state.value = load()
                onWake()
            }
            ACTION_REGISTRATION_FAILED -> {
                // Spec: the next registration must use a new token.
                prefs.edit().remove(K_TOKEN).remove(K_ENDPOINT).remove(K_SENT_FOR).apply()
                update(Status.FAILED, intent.getStringExtra(EXTRA_REASON) ?: "INTERNAL_ERROR")
            }
            ACTION_UNREGISTERED -> {
                prefs.edit().remove(K_TOKEN).remove(K_ENDPOINT).remove(K_SENT_FOR).apply()
                val next = intent.getStringExtra(EXTRA_USE_DISTRIBUTOR)
                if (next != null) {
                    prefs.edit().putString(K_DISTRIBUTOR, next).apply()
                    scope.launch { lock.withLock { registerLocked() } }
                } else {
                    update(Status.FAILED, "UNREGISTERED")
                    scope.launch { runCatching { api()?.unregisterPush() } }
                }
            }
            ACTION_TEMP_UNAVAILABLE -> Unit // the distributor sends NEW_ENDPOINT when it is back
        }
    }

    /**
     * Connected to the computer: re-register with the distributor (spec: on every start) and hand the
     * endpoint to the computer when it does not have it yet. Called on every connection.
     */
    suspend fun sync() = lock.withLock {
        if (!prefs.getBoolean(K_ENABLED, false)) return@withLock
        val remote = api() ?: return@withLock
        val info = try {
            remote.pushInfo()
        } catch (e: ApiException) {
            if (e.httpStatus == 404) update(Status.DESKTOP_UNSUPPORTED)
            return@withLock
        } catch (_: Exception) {
            return@withLock
        }
        val endpoint = prefs.getString(K_ENDPOINT, null)
        val keys = keys()
        // No endpoint yet, or the computer's VAPID key changed: ask the distributor (again).
        if (endpoint == null || keys == null || (info.vapidPublicKey != null && info.vapidPublicKey != prefs.getString(K_VAPID, null))) {
            registerLocked()
            return@withLock
        }
        val target = desktopKey() ?: return@withLock
        val sentFor = prefs.getString(K_SENT_FOR, null)
        if (!info.registered || sentFor != "$target|$endpoint") {
            val ok = runCatching { remote.registerPush(PushRegistration(endpoint, keys.publicKey, keys.auth)) }.isSuccess
            if (!ok) return@withLock
            prefs.edit().putString(K_SENT_FOR, "$target|$endpoint").apply()
        }
        update(Status.ACTIVE)
    }

    /** Settings "send a test": the computer pushes one message right now. */
    suspend fun test(): PushTestResult? = runCatching { api()?.testPush() }.getOrNull()

    /** Unpaired: the next computer gets the endpoint again. */
    fun forgetDesktop() {
        prefs.edit().remove(K_SENT_FOR).apply()
        if (prefs.getBoolean(K_ENABLED, false) && prefs.getString(K_ENDPOINT, null) != null) update(Status.PENDING_DESKTOP)
    }

    companion object {
        const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
        const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
        const val ACTION_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
        const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
        const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
        const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
        const val ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
        const val ACTION_TEMP_UNAVAILABLE = "org.unifiedpush.android.connector.TEMP_UNAVAILABLE"
        const val ACTION_RAISE = "org.unifiedpush.android.connector.RAISE_TO_FOREGROUND"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_ID = "id"
        const val EXTRA_BYTES = "bytesMessage"
        const val EXTRA_REASON = "reason"
        const val EXTRA_VAPID = "vapid"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PI = "pi"
        const val EXTRA_APPLICATION = "application"
        const val EXTRA_USE_DISTRIBUTOR = "useDistributor"

        const val PREFS = "push_wake"
        private const val K_ENABLED = "enabled"
        private const val K_STATUS = "status"
        private const val K_REASON = "reason"
        private const val K_DISTRIBUTOR = "distributor"
        private const val K_TOKEN = "token"
        private const val K_ENDPOINT = "endpoint"
        private const val K_KEYS = "keys"
        private const val K_VAPID = "vapid"
        private const val K_SENT_FOR = "sent_for"
        private const val K_LAST_WAKE = "last_wake"
    }
}
