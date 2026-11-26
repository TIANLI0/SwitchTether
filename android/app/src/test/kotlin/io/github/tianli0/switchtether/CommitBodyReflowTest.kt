package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.ui.workspace.reflowCommitBody
import org.junit.Assert.assertEquals
import org.junit.Test

class CommitBodyReflowTest {
    @Test
    fun wrappedLinesOfAParagraphAreJoined() {
        val body = "The failed-message delete now asks first; the dialog\nwindow draws vector icons.\n\nSecond paragraph\nwraps too."
        assertEquals(
            "The failed-message delete now asks first; the dialog window draws vector icons.\n\nSecond paragraph wraps too.",
            reflowCommitBody(body),
        )
    }

    @Test
    fun listItemsIndentedLinesAndTrailersKeepTheirLines() {
        val body = "Changes:\n- one\n- two\n  continued\n\n    code line\n\nCo-Authored-By: Someone <a@b.c>"
        assertEquals(body, reflowCommitBody(body))
    }
}
