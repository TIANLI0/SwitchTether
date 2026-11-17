package io.github.tianli0.switchtether.domain

import io.github.tianli0.switchtether.remote.protocol.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionNamingTest {
    private fun s(title: String? = null, cwd: String? = null, project: String? = null) =
        SessionSummary(id = "x", title = title, cwd = cwd, project = project)

    @Test fun titleIsFirstNonBlankLine() {
        assertEquals("Fix login", SessionNaming.title(s(title = "\n  Fix login \nmore")))
    }

    @Test fun missingTitleIsNotReplacedByProject() {
        assertNull(SessionNaming.title(s(title = null, cwd = "d:\\dev\\App")))
        assertNull(SessionNaming.title(s(title = "  ")))
    }

    @Test fun legacyRegistrySlugIsNotATitle() {
        assertNull(SessionNaming.title(s(title = "mediaduet-19", cwd = "d:\\develop\\MediaDuet")))
        assertEquals("mediaduet-redesign", SessionNaming.title(s(title = "mediaduet-redesign", cwd = "d:\\develop\\MediaDuet")))
    }

    @Test fun projectNames() {
        assertEquals("SwitchTether", SessionNaming.projectName(s(cwd = "d:\\develop\\SwitchTether")))
        assertEquals("proj", SessionNaming.projectName(s(cwd = "/home/u/proj/.claude/worktrees/feat-x")))
        assertEquals("proj", SessionNaming.projectName(s(project = "feat-x", cwd = "/home/u/proj/.claude/worktrees/feat-x")))
        assertEquals("Given", SessionNaming.projectName(s(project = "Given")))
        assertNull(SessionNaming.projectName(s()))
    }
}
