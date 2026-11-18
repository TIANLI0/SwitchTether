package io.github.tianli0.switchtether

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tianli0.switchtether.domain.PairingResult
import io.github.tianli0.switchtether.remote.pairing.PairingLink
import io.github.tianli0.switchtether.remote.protocol.CapStatus
import io.github.tianli0.switchtether.remote.protocol.SessionDetail
import io.github.tianli0.switchtether.remote.session.AgentEventLog
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.onboarding.OnboardingContent
import io.github.tianli0.switchtether.ui.onboarding.PairingUiState
import io.github.tianli0.switchtether.ui.sessions.SessionDetailContent
import io.github.tianli0.switchtether.ui.sessions.SessionDetailState
import io.github.tianli0.switchtether.ui.sessions.render.AgentEventRegistry
import io.github.tianli0.switchtether.ui.sessions.render.RenderContext
import io.github.tianli0.switchtether.ui.theme.SwitchTetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Compose UI tests on the JVM (Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ComposeScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val fp = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    private val link = PairingLink.parse("switchtether://pair?v=1&h=10.0.2.2,192.168.1.20&p=47615&fp=$fp&id=p1&s=${"A".repeat(43)}&n=DESKTOP-01")

    private fun onboarding(
        state: PairingUiState,
        linkText: String = "",
        secure: Boolean = true,
        events: MutableList<String> = mutableListOf(),
        nearby: List<io.github.tianli0.switchtether.remote.discovery.DiscoveredDesktop> = emptyList(),
    ) {
        rule.setContent {
            SwitchTetherTheme {
                OnboardingContent(
                    state = state,
                    linkText = linkText,
                    deviceSecure = secure,
                    revokedReason = null,
                    onLinkTextChange = { events += "text:$it" },
                    onSubmitLink = { events += "submit" },
                    onScan = { events += "scan" },
                    onScanned = {},
                    onPair = { events += "pair" },
                    onBack = { events += "back" },
                    onDone = { events += "done" },
                    onRejectCodes = { events += "reject" },
                    onPickNearby = { events += "nearby:${it.host}" },
                    onEnterAddress = { events += "address" },
                    onSubmitCode = { a, c -> events += "code:$a:$c" },
                    nearbyOverride = nearby,
                )
            }
        }
    }

    @Test
    fun nearbyComputerAndTypedAddressLeadToCodeEntry() {
        val events = mutableListOf<String>()
        val pc = io.github.tianli0.switchtether.remote.discovery.DiscoveredDesktop("svc", "id", "DESKTOP-01", fp, "192.168.8.4", 47615)
        onboarding(PairingUiState.Intro, events = events, nearby = listOf(pc))
        rule.onNodeWithTag("nearby-${fp.take(8)}").performScrollTo().performClick()
        rule.onNodeWithTag("enter-address").performScrollTo().performClick()
        assertEquals(listOf("nearby:192.168.8.4", "address"), events)
    }

    @Test
    fun codeEntryNeedsSixDigitsAndWarnsToCompareCodes() {
        val events = mutableListOf<String>()
        onboarding(PairingUiState.EnterCode(null, null, 47615, null, manual = true), events = events)
        rule.onNodeWithText("Without the QR code", substring = true).assertExists()
        rule.onNodeWithTag("pair-code-submit").assertIsNotEnabled()
        rule.onNodeWithTag("pair-address").performTextInput("192.168.8.4")
        rule.onNodeWithTag("pair-code-input").performTextInput("12a3456")
        rule.onNodeWithTag("pair-code-submit").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("code:192.168.8.4:123456"), events)
    }

    @Test
    fun pairingIntroOffersScanAndPaste() {
        val events = mutableListOf<String>()
        onboarding(PairingUiState.Intro, events = events)
        rule.onNodeWithText("Scan pairing QR code").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("use-link").assertDoesNotExist() // nothing pasted yet: a paste button instead
        rule.onNodeWithTag("paste-link").assertExists()
        rule.onNodeWithText("How this connection is protected").assertExists()
        assertEquals(listOf("scan"), events)
    }

    @Test
    fun pairingIntroWithLinkEnablesSubmitAndWarnsWithoutLockScreen() {
        val events = mutableListOf<String>()
        onboarding(PairingUiState.Intro, linkText = "switchtether://pair?v=1", secure = false, events = events)
        rule.onNodeWithTag("use-link").performScrollTo().assertIsEnabled().performClick()
        assertTrue(events.contains("submit"))
        rule.onAllNodesWithText("This phone has no secure lock screen", substring = true).fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
    }

    @Test
    fun pairingConfirmShowsDesktopAndGroupedFingerprint() {
        val events = mutableListOf<String>()
        onboarding(PairingUiState.Confirm(link), events = events)
        rule.onNodeWithText("DESKTOP-01").assertIsDisplayed()
        rule.onNodeWithText(fp.chunked(4).joinToString(" ")).assertExists()
        rule.onNodeWithTag("pair").performScrollTo().performClick()
        assertEquals(listOf("pair"), events)
    }

    @Test
    fun pairingDoneShowsVerificationCodeAndBlocksMismatch() {
        onboarding(PairingUiState.Done(PairingResult(TEST_DESKTOP, serverCode = "042317", localCode = "042317", stepUpEnabled = true)))
        rule.onNodeWithTag("verification-code").assertIsDisplayed()
        rule.onNodeWithText("042 317").assertExists()
        rule.onNodeWithTag("verify-done").assertIsEnabled()
    }

    @Test
    fun pairingDoneWithMismatchedCodesDisablesContinue() {
        onboarding(PairingUiState.Done(PairingResult(TEST_DESKTOP, serverCode = "111111", localCode = "222222", stepUpEnabled = false)))
        rule.onNodeWithTag("verify-done").assertIsNotEnabled()
        rule.onNodeWithText("The code reported by the desktop differs", substring = true).assertExists()
    }

    private fun renderCtx(approve: CapStatus) = RenderContext(
        agent = "claude_code",
        agentLabel = "Claude Code",
        approve = approve,
        answer = approve,
        capabilityReason = "external interactive session",
        online = true,
        onApprove = { _, _, _ -> },
        onAnswer = { _, _ -> },
        onLoadPayload = {},
        payloadOf = { null },
        onToggleCollapse = {},
        isCollapsed = { false },
        onLink = {},
    )

    private fun detailState(): SessionDetailState {
        val diff = "--- a/src/login.kt\n+++ b/src/login.kt\n@@ -1,2 +1,2 @@\n fun login() {\n-  check(false)\n+  check(true)\n"
        val log = AgentEventLog.of(
            listOf(
                event("u1", 1, "user_message", """{"text":"Fix the login bug","source":"desktop"}"""),
                event("a1", 2, "assistant_message", """{"text":"Looking at **login.kt** now.","model":"claude-x"}"""),
                event("t1", 3, "tool_call", """{"callId":"c1","name":"Bash","title":"run unit tests","category":"shell","status":"completed","input":{"command":"gradle test"}}"""),
                event("r1", 4, "tool_result", """{"callId":"c1","isError":false,"text":"BUILD SUCCESSFUL","truncated":false,"bytes":16,"exitCode":0}"""),
                event("f1", 5, "file_change", """{"path":"src/login.kt","change":"modify","diff":${kotlinx.serialization.json.JsonPrimitive(diff)},"additions":1,"deletions":1,"truncated":false}"""),
                event("p1", 6, "plan", """{"items":[{"text":"Reproduce","status":"completed"},{"text":"Fix","status":"in_progress"}]}"""),
                event("ap1", 7, "approval_request", """{"requestId":"q1","kind":"command","title":"Run rm -rf build","detail":"cleanup","command":"rm -rf build","options":[{"id":"allow","label":"Allow"},{"id":"deny","label":"Deny"}],"resolved":false}"""),
                event("z1", 8, "brand_new_kind", """{"foo":1}"""),
                event("e1", 9, "error", """{"message":"rate limited"}"""),
            ),
        )
        return SessionDetailState(
            sessionId = "s1",
            detail = Loadable.Ready(SessionDetail(summary(), caps(continueSession = "PARTIAL"), emptyList())),
            events = Loadable.Ready(Unit),
            connection = ONLINE,
        ).derive(log = log)
    }

    @Test
    fun sessionDetailRendersAllKindsNatively() {
        val state = detailState()
        var continued = 0
        rule.setContent {
            SwitchTetherTheme {
                SessionDetailContent(
                    state = state, initialTitle = null, ctx = renderCtx(CapStatus.BLOCKED), busy = null,
                    onBack = {}, onReload = {}, onLoadOlder = {}, onToggleSteps = {}, onDraft = {}, onSend = {}, onInterrupt = {},
                    onContinue = { continued++ }, onRetryConnection = {},
                )
            }
        }
        val list = rule.onNodeWithTag("event-list")
        fun see(text: String, substring: Boolean = false) {
            list.performScrollToNode(hasText(text, substring = substring))
            rule.onNodeWithText(text, substring = substring).assertExists()
        }
        see("Fix the login bug")
        see("Looking at login.kt now.") // markdown bold rendered as styled text, no asterisks
        see("run unit tests")
        see("login.kt")
        see("Reproduce")
        see("Run rm -rf build")
        see("Answer on the computer", substring = true) // approve BLOCKED is explained
        see("Unknown event: brand_new_kind")
        see("rate limited")
        // Approval buttons exist but are disabled because the capability is BLOCKED.
        list.performScrollToNode(hasText("Allow"))
        rule.onNodeWithText("Allow").assertIsNotEnabled()
        // Read-only session: no dead input field, an explicit reason instead.
        assertTrue(rule.onAllNodesWithTag("send").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Read-only on the phone").assertExists()
        rule.onNodeWithTag("readonly-tip").assertExists()
        // Pending approval banner is shown above the timeline.
        rule.onNodeWithTag("pending-banner").assertExists()
        // "Continue on phone" is offered because the desktop advertises continueSession.
        rule.onNodeWithTag("continue").performClick()
        assertEquals(1, continued)
    }

    @Test
    fun approvalButtonsEnabledWhenVerified() {
        val state = detailState().let { s ->
            s.copy(detail = Loadable.Ready(SessionDetail(summary(control = "full", status = "busy"), caps(send = "VERIFIED", approve = "VERIFIED", interrupt = "VERIFIED"), emptyList())))
        }
        rule.setContent {
            SwitchTetherTheme {
                SessionDetailContent(
                    state = state, initialTitle = null, ctx = renderCtx(CapStatus.VERIFIED), busy = null,
                    onBack = {}, onReload = {}, onLoadOlder = {}, onToggleSteps = {}, onDraft = {}, onSend = {}, onInterrupt = {},
                    onContinue = {}, onRetryConnection = {},
                )
            }
        }
        assertTrue(rule.onAllNodesWithTag("continue").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("event-list").performScrollToNode(hasText("Allow"))
        rule.onNodeWithText("Allow").assertIsEnabled()
        rule.onNodeWithContentDescriptionSafe("Interrupt")
    }

    @Test
    fun attachedSessionKeepsComposerAndOffersOpenTerminal() {
        val attached = summary(control = "full", status = "busy").copy(origin = "vscode", managed = false, terminalId = "t-7")
        val state = detailState().let { s ->
            s.copy(detail = Loadable.Ready(SessionDetail(attached, caps(send = "VERIFIED", approve = "VERIFIED", interrupt = "VERIFIED", continueSession = "PARTIAL"), emptyList())))
        }
        var opened: String? = null
        rule.setContent {
            SwitchTetherTheme {
                SessionDetailContent(
                    state = state, initialTitle = null, ctx = renderCtx(CapStatus.VERIFIED), busy = null,
                    onBack = {}, onReload = {}, onLoadOlder = {}, onToggleSteps = {}, onDraft = {}, onSend = {}, onInterrupt = {},
                    onContinue = {}, onRetryConnection = {}, onOpenTerminal = { opened = it },
                )
            }
        }
        // Full control: normal composer, no read-only bar and no "continue on phone".
        rule.onNodeWithTag("send").assertExists()
        assertTrue(rule.onAllNodesWithText("Read-only on the phone").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag("continue").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Attached · VS Code").assertExists()
        rule.onNodeWithContentDescriptionSafe("Open terminal")
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Open terminal")).performClick()
        assertEquals("t-7", opened)
    }

    @Test
    fun homeShowsAttentionFirstAndNoConnectedCard() {
        val waiting = summary(id = "w1", status = "waiting").copy(title = "Needs approval session", waitingFor = "approval")
        val running = summary(id = "r1", status = "busy").copy(title = "Busy session")
        val state = io.github.tianli0.switchtether.ui.home.HomeState(
            connection = ONLINE,
            sessions = Loadable.Ready(listOf(running, waiting)),
        )
        rule.setContent {
            SwitchTetherTheme {
                io.github.tianli0.switchtether.ui.home.HomeContent(state, onOpen = {}, onSelectTab = {}, onRetry = {}, onRefresh = {})
            }
        }
        rule.onNodeWithText("Needs you · 1").assertExists()
        rule.onNodeWithText("Running · 1").assertExists()
        val att = rule.onNodeWithText("Needs approval session").fetchSemanticsNode().boundsInRoot.top
        val run = rule.onNodeWithText("Busy session").fetchSemanticsNode().boundsInRoot.top
        assertTrue("attention rows come before running rows", att < run)
        // Healthy connection: no connection card / banner text at all.
        assertTrue(rule.onAllNodesWithText("Connected", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun registryCoversEveryProtocolKind() {
        val expected = io.github.tianli0.switchtether.remote.protocol.EventKinds.ALL
        assertEquals(expected, AgentEventRegistry.supportedKinds)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionSafe(desc: String) {
        assertTrue(onAllNodes(androidx.compose.ui.test.hasContentDescription(desc)).fetchSemanticsNodes().isNotEmpty())
    }
}
