package io.github.tianli0.switchtether

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import io.github.tianli0.switchtether.widget.StatusWidgetRenderer
import io.github.tianli0.switchtether.widget.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class StatusWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun inflate(s: WidgetSnapshot?): View =
        StatusWidgetRenderer.render(context, s).apply(context, FrameLayout(context))

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun waitingSessionsAreListedWithWhatTheyWaitFor() {
        val approval = SessionSummary(id = "managed:a", agent = "claude_code", title = "Fix login", liveness = "live", status = "waiting", waitingFor = "approval")
        val question = SessionSummary(id = "managed:b", agent = "codex", cwd = "D:\\work\\api", liveness = "live", status = "waiting", waitingFor = "input")
        val v = inflate(WidgetSnapshot(desktop = "Desk", online = true, waiting = listOf(approval, question), running = 1))
        assertEquals("SwitchTether · Desk", v.text(R.id.widget_title))
        assertEquals(context.getString(R.string.widget_counts, 2, 1), v.text(R.id.widget_counts))
        assertEquals("Fix login · " + context.getString(R.string.waiting_for_approval), v.text(R.id.widget_row0))
        assertEquals("api · " + context.getString(R.string.waiting_for_input), v.text(R.id.widget_row1))
        assertEquals(View.VISIBLE, v.findViewById<View>(R.id.widget_row1).visibility)
        assertEquals(View.GONE, v.findViewById<View>(R.id.widget_row2).visibility)
        assertEquals("", v.text(R.id.widget_empty))
    }

    @Test
    fun offlineAndNoProcessStatesSaySo() {
        val off = inflate(WidgetSnapshot(desktop = "Desk", online = false, waiting = emptyList(), running = 0))
        assertEquals(context.getString(R.string.widget_offline), off.text(R.id.widget_empty))
        val none = inflate(null)
        assertEquals(context.getString(R.string.widget_open_app), none.text(R.id.widget_empty))
        assertEquals(View.GONE, none.findViewById<View>(R.id.widget_row0).visibility)
    }
}
