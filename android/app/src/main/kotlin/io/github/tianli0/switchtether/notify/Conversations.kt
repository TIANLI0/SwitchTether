package io.github.tianli0.switchtether.notify

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.tianli0.switchtether.MainActivity
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.notify.NotifySlot
import io.github.tianli0.switchtether.remote.protocol.SessionSummary

/**
 * Conversation notifications (docs/ANDROID_UI.md §34): every session notification is a
 * `MessagingStyle` conversation whose sender is the agent ("Claude Code · fix login"), tied to a
 * long-lived dynamic shortcut per session, so sessions show under the system's "Conversations"
 * and can be marked priority.
 *
 * Shortcut policy (pure, [ConversationPolicy]): at most [ConversationPolicy.limit] session
 * shortcuts; the least recently posted is evicted first; a session that ended more than
 * [ConversationPolicy.ENDED_TTL_MS] ago, or nothing was posted for in
 * [ConversationPolicy.UNUSED_TTL_MS], loses its shortcut; all of them go on unpair or when the
 * phone is paired with another computer.
 *
 * Thread-safe: every public method holds this object's monitor (the event stream, notification
 * buttons and the UI call in from different threads). Never calls back into [Notifier].
 */
class ConversationNotifier(private val context: Context) {
    private val prefs = context.getSharedPreferences("conversations", Context.MODE_PRIVATE)
    private val nm = NotificationManagerCompat.from(context)

    /** One message line of a conversation notification (memory only; never written to disk). */
    private data class Msg(val text: String, val atMs: Long, val fromUser: Boolean, val system: Boolean = false)

    /** Per notification id: the recent thread (≤ [HISTORY]) and the builder last posted. */
    private val threads = HashMap<Int, ArrayDeque<Msg>>()
    private val builders = LinkedHashMap<Int, NotificationCompat.Builder>()
    private val senders = HashMap<Int, Person>()

    /** Label a shortcut was last pushed with in this process (re-pushed only when it changes). */
    private val pushed = HashMap<String, String>()

    private val me: Person by lazy { Person.Builder().setName(context.getString(R.string.conv_you)).setKey("me").build() }
    private val app: Person by lazy {
        Person.Builder().setName(context.getString(R.string.app_name)).setKey("app")
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher)).build()
    }

    // ---- posting ------------------------------------------------------------------------------

    /**
     * Turns [b] into the conversation notification [id] of [sessionId]: shortcut, MessagingStyle
     * thread with [c]'s message from the agent and locus id. [replaceLast]: the same moment
     * re-announced with more detail.
     */
    @Synchronized
    fun decorate(
        b: NotificationCompat.Builder,
        id: Int,
        sessionId: String,
        summary: SessionSummary?,
        c: ConversationPolicy.Content,
        atMs: Long,
        replaceLast: Boolean,
    ) {
        val shortcut = publish(sessionId, summary, c.sender)
        val agent = agentPerson(sessionId, summary, c.sender)
        senders[id] = agent
        val thread = threads.getOrPut(id) { ArrayDeque() }
        if (replaceLast && thread.lastOrNull()?.fromUser == false) thread.removeLast()
        thread.addLast(Msg(c.message, atMs, fromUser = false))
        while (thread.size > HISTORY) thread.removeFirst()
        b.setStyle(style(agent, thread))
        if (shortcut != null) {
            b.setShortcutId(shortcut)
            b.setLocusId(LocusIdCompat(shortcut))
        }
        builders.remove(id)
        builders[id] = b // most recent last
        // Bounded: the oldest threads go (their notifications stay; a reply to one then gets the plain outcome).
        while (builders.size > MAX_THREADS) builders.keys.first().let(::forget)
    }

    /**
     * After an inline reply: the conversation shows the sent message (or why it was not sent) and
     * stops the reply spinner. False when this process did not post [id] (it was restarted): the
     * caller then posts the plain outcome notification.
     */
    @Synchronized
    fun replyResult(id: Int, typed: String, result: ConversationPolicy.ReplyResult): Boolean {
        val b = builders[id] ?: return false
        val agent = senders[id] ?: return false
        val thread = threads.getOrPut(id) { ArrayDeque() }
        val now = System.currentTimeMillis()
        if (result == ConversationPolicy.ReplyResult.Sent) {
            thread.addLast(Msg(typed, now, fromUser = true))
        } else {
            val why = when (result) {
                ConversationPolicy.ReplyResult.NeedsStepUp -> context.getString(R.string.conv_reply_step_up)
                ConversationPolicy.ReplyResult.Offline -> context.getString(R.string.ntf_offline_open)
                ConversationPolicy.ReplyResult.Unsure -> context.getString(R.string.ntf_not_sure) + " " + context.getString(R.string.ntf_open_to_check)
                is ConversationPolicy.ReplyResult.Refused -> result.reason.ifBlank { context.getString(R.string.ntf_open_to_check) }
                else -> ""
            }
            thread.addLast(Msg(context.getString(R.string.conv_reply_failed, typed.take(60), why), now, fromUser = false, system = true))
        }
        while (thread.size > HISTORY) thread.removeFirst()
        b.setStyle(style(agent, thread)).setSilent(true).setOnlyAlertOnce(true).setWhen(now)
        return runCatching { nm.notify(id, b.build()) }.isSuccess
    }

    /** Withdraws notification [id] and forgets its thread. */
    @Synchronized
    fun cancel(id: Int) {
        forget(id)
        runCatching { nm.cancel(id) }
    }

    @Synchronized
    fun forget(id: Int) {
        threads.remove(id)
        builders.remove(id)
        senders.remove(id)
    }

    private fun style(agent: Person, thread: Collection<Msg>): NotificationCompat.MessagingStyle {
        val s = NotificationCompat.MessagingStyle(me).setGroupConversation(false)
        thread.forEach { m -> s.addMessage(m.text, m.atMs, if (m.fromUser) me else if (m.system) app else agent) }
        return s
    }

    // ---- shortcuts ------------------------------------------------------------------------------

    private fun agentPerson(sessionId: String, summary: SessionSummary?, sender: String): Person =
        Person.Builder().setName(sender).setKey("agent:$sessionId").setIcon(agentIcon(summary?.agent)).build()

    /** Pushes (or refreshes) the session's long-lived shortcut and applies the eviction policy; returns its id. */
    private fun publish(sessionId: String, summary: SessionSummary?, sender: String): String? {
        val id = ConversationPolicy.shortcutId(sessionId)
        val now = System.currentTimeMillis()
        val entries = ConversationPolicy.decode(prefs.getString(KEY_ENTRIES, null)).associateBy { it.sessionId }.toMutableMap()
        entries[sessionId] = ConversationPolicy.Entry(sessionId, now, entries[sessionId]?.endedAtMs?.takeIf { summary?.isLive != true })
        val limit = ConversationPolicy.limit(runCatching { ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) }.getOrDefault(5))
        val evict = ConversationPolicy.evictions(entries.values, now, limit, keep = sessionId)
        evict.forEach { entries.remove(it) }
        prefs.edit().putString(KEY_ENTRIES, ConversationPolicy.encode(entries.values)).apply()
        if (evict.isNotEmpty()) removeShortcuts(evict)
        val label = summary?.let { io.github.tianli0.switchtether.domain.SessionNaming.title(it) ?: io.github.tianli0.switchtether.domain.SessionNaming.projectName(it) }
            ?: sender
        if (pushed[sessionId] == "$label|$sender" && isPublished(id)) return id
        val info = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label.take(ConversationPolicy.MAX_NAME).ifBlank { sender })
            .setLongLabel(sender)
            .setIcon(agentIcon(summary?.agent))
            .setIntent(
                Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            .setLongLived(true)
            .setLocusId(LocusIdCompat(id))
            .setPerson(agentPerson(sessionId, summary, sender))
            // Also a Direct Share target (res/xml/shortcuts.xml): sharing into it prefills this session.
            .setCategories(setOf(CATEGORY_CONVERSATION, io.github.tianli0.switchtether.share.ShareIntake.SHORTCUT_CATEGORY))
            .build()
        return if (runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, info) }.isSuccess) {
            pushed[sessionId] = "$label|$sender"
            id
        } else {
            null
        }
    }

    private fun isPublished(id: String): Boolean = runCatching {
        ShortcutManagerCompat.getDynamicShortcuts(context).any { it.id == id }
    }.getOrDefault(false)

    private fun removeShortcuts(sessionIds: Collection<String>) {
        val ids = sessionIds.map(ConversationPolicy::shortcutId)
        sessionIds.forEach { pushed.remove(it) }
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, ids) }
        runCatching { ShortcutManagerCompat.removeDynamicShortcuts(context, ids) }
    }

    /** The session's process ended: its shortcut is kept for [ConversationPolicy.ENDED_TTL_MS]. */
    @Synchronized
    fun onSessionEnded(sessionId: String, atMs: Long = System.currentTimeMillis()) {
        val entries = ConversationPolicy.decode(prefs.getString(KEY_ENTRIES, null)).associateBy { it.sessionId }.toMutableMap()
        val e = entries[sessionId] ?: return
        if (e.endedAtMs != null) return
        entries[sessionId] = e.copy(endedAtMs = atMs)
        prefs.edit().putString(KEY_ENTRIES, ConversationPolicy.encode(entries.values)).apply()
    }

    /** Removes expired shortcuts (app start). */
    @Synchronized
    fun prune(nowMs: Long = System.currentTimeMillis()) {
        val entries = ConversationPolicy.decode(prefs.getString(KEY_ENTRIES, null))
        val limit = ConversationPolicy.limit(runCatching { ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) }.getOrDefault(5))
        val evict = ConversationPolicy.evictions(entries, nowMs, limit)
        if (evict.isEmpty()) return
        prefs.edit().putString(KEY_ENTRIES, ConversationPolicy.encode(entries.filter { it.sessionId !in evict })).apply()
        removeShortcuts(evict)
    }

    /** Paired with [serverId]: another computer's sessions are not this one's conversations. */
    @Synchronized
    fun onServer(serverId: String?) {
        val previous = prefs.getString(KEY_SERVER, null)
        if (previous == serverId) return
        if (previous != null) forgetAllLocked()
        prefs.edit().putString(KEY_SERVER, serverId).apply()
    }

    /** Unpair / revoke: every session shortcut and thread goes. */
    @Synchronized
    fun forgetAll() {
        forgetAllLocked()
        prefs.edit().remove(KEY_SERVER).apply()
    }

    private fun forgetAllLocked() {
        val known = ConversationPolicy.decode(prefs.getString(KEY_ENTRIES, null)).map { it.sessionId }.toMutableSet()
        runCatching { ShortcutManagerCompat.getDynamicShortcuts(context) }.getOrNull()?.mapNotNullTo(known) { ConversationPolicy.sessionIdOf(it.id) }
        prefs.edit().remove(KEY_ENTRIES).apply()
        removeShortcuts(known)
        threads.keys.toList().forEach { runCatching { nm.cancel(it) } }
        threads.clear()
        builders.clear()
        senders.clear()
    }

    // ---- icons ----------------------------------------------------------------------------------

    private val icons = HashMap<String, IconCompat>()

    /** The agent's mark on a light adaptive tile (the conversation avatar); the app icon otherwise. */
    private fun agentIcon(agent: String?): IconCompat {
        val res = when (agent) {
            "claude_code", "claude" -> R.drawable.ic_agent_claude
            "codex" -> R.drawable.ic_agent_openai
            "dsh" -> R.drawable.ic_agent_deepseek
            "agy" -> R.drawable.ic_agent_antigravity
            else -> null
        } ?: return IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        return icons.getOrPut(agent!!) {
            runCatching {
                val size = ICON_PX
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(0xFFF4F1EC.toInt())
                val d = ContextCompat.getDrawable(context, res)!!
                // Adaptive icons show the middle 72 of 108 dp; the mark takes about half of that.
                val inset = size * 30 / 100
                d.setBounds(inset, inset, size - inset, size - inset)
                d.draw(canvas)
                IconCompat.createWithAdaptiveBitmap(bmp)
            }.getOrElse { IconCompat.createWithResource(context, R.mipmap.ic_launcher) }
        }
    }

    companion object {
        private const val KEY_ENTRIES = "entries"
        private const val KEY_SERVER = "server"
        private const val HISTORY = 5
        private const val MAX_THREADS = 64
        private const val ICON_PX = 216

        /** `ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION` (API 30). */
        private const val CATEGORY_CONVERSATION = "android.shortcut.conversation"

        /** The notification id of a session's slot, as [Notifier] posts it. */
        fun slotId(sessionId: String, slot: NotifySlot): Int = "$sessionId:$slot".hashCode()
    }
}
