package io.github.tianli0.switchtether

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.domain.StepUpGate
import io.github.tianli0.switchtether.remote.protocol.GitStatusEntry
import io.github.tianli0.switchtether.remote.protocol.RepoStatus
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import io.github.tianli0.switchtether.ui.workspace.FilesUiState
import io.github.tianli0.switchtether.ui.workspace.FilesViewModel
import io.github.tianli0.switchtether.ui.workspace.GitPane
import io.github.tianli0.switchtether.ui.workspace.GitReviewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val STATUS = RepoStatus(
    root = "/home/u/r",
    branch = "main",
    upstream = "origin/main",
    ahead = 1,
    entries = listOf(
        GitStatusEntry("README.md", index = ".", worktree = "M"),
        GitStatusEntry("src/a.kt", index = "M", worktree = "."),
        GitStatusEntry("src/b.kt", index = ".", worktree = "M"),
        GitStatusEntry("new/", index = "?", worktree = "?", untracked = true),
    ),
)

/** Pure: which entries the review shows, in which order. */
class GitReviewModelTest {
    @Test
    fun turnFilesComeFirstAndCanBeTheOnlyOnes() {
        val turn = listOf("/home/u/r/src/b.kt", "src/a.kt", "/elsewhere/x.kt", "new/n.txt")
        val all = GitReviewModel.of(STATUS, turn, turnCwd = "/home/u/r", onlyTurn = false)
        assertEquals(listOf("src/a.kt"), all.staged.map { it.path })
        // Turn files (b.kt, and the untracked folder holding n.txt) before README.
        assertEquals(listOf("src/b.kt", "new/", "README.md"), all.unstaged.map { it.path })
        assertEquals(3, all.turnInRepo) // x.kt is outside the repository
        assertEquals(setOf("src/a.kt", "src/b.kt", "new"), all.turnPaths)

        val only = GitReviewModel.of(STATUS, turn, turnCwd = "/home/u/r", onlyTurn = true)
        assertEquals(listOf("src/b.kt", "new/"), only.unstaged.map { it.path })
        assertTrue(only.isTurn(only.staged.single()))
        // A session without turn files shows everything even with the filter on.
        assertEquals(3, GitReviewModel.of(STATUS, emptyList(), null, onlyTurn = true).unstaged.size)
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-xxhdpi")
class GitReviewScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun vm() = FilesViewModel(FakeConnection(), StepUpGate({ _, _ -> error("not used") }), { error("no resolver") }) { null }

    private fun show(state: FilesUiState) {
        val vm = vm()
        rule.setContent { SwitchTetherTheme { GitPane(state, vm) } }
        rule.waitForIdle()
    }

    @Test
    fun theLatestTurnIsMarkedAndFiltered() {
        show(
            FilesUiState(
                repo = "/home/u/r",
                git = Loadable.Ready(STATUS),
                gitLog = Loadable.Ready(emptyList()),
                gitWritable = true,
                turnFiles = listOf("/home/u/r/src/b.kt"),
                turnCwd = "/home/u/r",
                onlyTurn = true,
            ),
        )
        rule.onNodeWithTag("git-only-turn").assertExists()
        // A file row names the file; its folder is the line under it.
        rule.onNodeWithText("b.kt").assertExists()
        rule.onNodeWithText("src").assertExists()
        rule.onAllNodesWithText("README.md").assertCountEquals(0)
        rule.onAllNodesWithText("This turn").assertCountEquals(1)
        // Writes are offered; committing needs a message first.
        rule.onNodeWithTag("git-stage-all").assertExists()
        rule.onNodeWithTag("git-commit").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("git-push").assertExists()
    }

    @Test
    fun historyAndBranchesAreTabs() {
        show(
            FilesUiState(
                repo = "/home/u/r",
                git = Loadable.Ready(STATUS),
                gitLog = Loadable.Ready(listOf(io.github.tianli0.switchtether.remote.protocol.GitCommit("0123456789abcdef", "Ada", "a@x", 1_700_000_000_000, "fix: the thing"))),
                gitWritable = true,
                gitManage = true,
                gitBranches = Loadable.Ready(
                    io.github.tianli0.switchtether.remote.protocol.GitBranches(
                        root = "/home/u/r",
                        current = "main",
                        local = listOf(
                            io.github.tianli0.switchtether.remote.protocol.GitBranch("main", current = true, upstream = "origin/main", ahead = 2),
                            io.github.tianli0.switchtether.remote.protocol.GitBranch("feat"),
                        ),
                    ),
                ),
            ),
        )
        rule.onNodeWithTag("git-tabs").assertExists()
        rule.onNodeWithText("History").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("fix: the thing").assertExists()
        rule.onNodeWithText("01234567", substring = true).assertExists()
        rule.onNodeWithText("Branches").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Current").assertExists()
        rule.onNodeWithText("↑2 ↓0", substring = true).assertExists()
        // Uncommitted changes: switching asks first.
        rule.onNodeWithText("feat").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Switch to feat?").assertExists()
    }

    @Test
    fun aStoppedMergeOffersAbort() {
        show(
            FilesUiState(
                repo = "/home/u/r",
                git = Loadable.Ready(STATUS.copy(merging = true)),
                gitLog = Loadable.Ready(emptyList()),
                gitWritable = true,
                gitManage = true,
            ),
        )
        rule.onNodeWithText("Merge stopped on conflicts").assertExists()
        rule.onNodeWithTag("git-merge-abort").assertExists()
    }

    @Test
    fun anOlderDesktopKeepsThePageReadOnly() {
        show(FilesUiState(repo = "/home/u/r", git = Loadable.Ready(STATUS), gitLog = Loadable.Ready(emptyList()), gitWritable = false))
        rule.onNodeWithText("README.md").assertExists()
        rule.onAllNodesWithText("Stage all").assertCountEquals(0)
        rule.onAllNodesWithText("Fetch").assertCountEquals(0)
        rule.onNodeWithText("SwitchTether on this computer only shows Git. Update it to stage, commit and push from the phone.").performScrollTo().assertExists()
    }
}
