package io.github.tianli0.switchtether.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import io.github.tianli0.switchtether.MainActivity
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.SwitchTetherApp
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.SessionQuery
import io.github.tianli0.switchtether.domain.SessionRepository
import io.github.tianli0.switchtether.remote.notify.isApprovalWait
import io.github.tianli0.switchtether.remote.protocol.ProtocolJson
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * "待处理 / 运行中" home-screen widget. Shows counts and up to three sessions waiting for the user;
 * a row opens that session. It never approves or sends anything (that needs the app). The data
 * lives in memory only; when the app process is not running the widget says so.
 */
class StatusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as? SwitchTetherApp
        val snapshot = app?.container?.widget?.snapshot
        ids.forEach { manager.updateAppWidget(it, StatusWidgetRenderer.render(context, snapshot)) }
    }
}

data class WidgetSnapshot(val desktop: String?, val online: Boolean, val waiting: List<SessionSummary>, val running: Int)

object StatusWidgetRenderer {
    fun render(context: Context, s: WidgetSnapshot?): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_status)
        v.setOnClickPendingIntent(R.id.widget_root, open(context, null))
        if (s == null) {
            v.setTextViewText(R.id.widget_counts, "—")
            v.setTextViewText(R.id.widget_empty, context.getString(R.string.widget_open_app))
            listOf(R.id.widget_row0, R.id.widget_row1, R.id.widget_row2).forEach { v.setViewVisibility(it, View.GONE) }
            return v
        }
        v.setTextViewText(R.id.widget_title, listOfNotNull(context.getString(R.string.app_name), s.desktop).joinToString(" · "))
        v.setTextViewText(R.id.widget_counts, context.getString(R.string.widget_counts, s.waiting.size, s.running))
        val rows = listOf(R.id.widget_row0, R.id.widget_row1, R.id.widget_row2)
        rows.forEachIndexed { i, id ->
            val session = s.waiting.getOrNull(i)
            if (session == null) {
                v.setViewVisibility(id, View.GONE)
            } else {
                val what = context.getString(if (isApprovalWait(session.waitingFor)) R.string.waiting_for_approval else R.string.waiting_for_input)
                v.setViewVisibility(id, View.VISIBLE)
                v.setTextViewText(id, "${title(context, session)} · $what")
                v.setOnClickPendingIntent(id, open(context, session.id))
            }
        }
        v.setTextViewText(
            R.id.widget_empty,
            when {
                !s.online -> context.getString(R.string.widget_offline)
                s.waiting.isEmpty() -> context.getString(R.string.widget_nothing_waiting)
                else -> ""
            },
        )
        return v
    }

    private fun title(context: Context, s: SessionSummary): String =
        s.title?.takeIf { it.isNotBlank() } ?: s.project ?: s.cwd?.substringAfterLast('/')?.substringAfterLast('\\') ?: context.getString(R.string.session_unnamed)

    private fun open(context: Context, sessionId: String?): PendingIntent = PendingIntent.getActivity(
        context,
        ("widget:$sessionId").hashCode(),
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            sessionId?.let { putExtra(MainActivity.EXTRA_SESSION_ID, it) }
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Keeps the widget in sync with the live session list (in memory), coalescing updates. */
class StatusWidgetUpdater(
    private val context: Context,
    private val scope: CoroutineScope,
    private val conn: ConnectionController,
    private val sessions: SessionRepository,
) {
    private val live = ConcurrentHashMap<String, SessionSummary>()
    @Volatile var snapshot: WidgetSnapshot? = null
        private set
    private var job: Job? = null

    fun start() {
        scope.launch {
            conn.state.collect { s ->
                if (s is ConnectionState.Online) runCatching { sessions.list(SessionQuery()).items }.getOrNull()?.let { items ->
                    live.clear()
                    items.filter { it.isLive }.forEach { live[it.id] = it }
                }
                schedule()
            }
        }
        scope.launch {
            conn.events.collect { ev ->
                if (ev.topic != "session.upserted") return@collect
                val data = ev.data as? JsonObject ?: return@collect
                val s = runCatching { ProtocolJson.decodeFromJsonElement(SessionSummary.serializer(), (data["session"] as? JsonObject) ?: data) }.getOrNull() ?: return@collect
                if (s.isLive) live[s.id] = s else live.remove(s.id)
                schedule()
            }
        }
    }

    private fun schedule() {
        job?.cancel()
        job = scope.launch {
            delay(1_000)
            val state = conn.state.value
            val all = live.values.toList()
            val waiting = all.filter { it.status == "waiting" || it.waitingFor != null }.sortedByDescending { it.lastActiveAtMs ?: 0L }
            snapshot = WidgetSnapshot(
                desktop = when (state) {
                    is ConnectionState.Online -> state.desktop.name
                    is ConnectionState.Offline -> state.desktop.name
                    is ConnectionState.Connecting -> state.desktop.name
                    else -> null
                },
                online = state is ConnectionState.Online,
                waiting = waiting,
                // "运行中" means a turn in flight, not merely an open (idle) session.
                running = all.count { it.status == "busy" && it !in waiting },
            )
            push()
        }
    }

    private fun push() {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = runCatching { mgr.getAppWidgetIds(ComponentName(context, StatusWidgetProvider::class.java)) }.getOrDefault(IntArray(0))
        if (ids.isEmpty()) return
        val views = StatusWidgetRenderer.render(context, snapshot)
        ids.forEach { runCatching { mgr.updateAppWidget(it, views) } }
    }
}
