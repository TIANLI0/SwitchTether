package io.github.tianli0.switchtether

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun onboarding(state: PairingUiState, linkText: String = "", secure: Boolean = true, events: MutableList<String> = mutableListOf()) {
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
                )
            }
        }
    }

    @Test
    fun pairingIntroOffersScanAndPaste() {
        val events = mutableListOf<String>()
        onboarding(PairingUiState.Intro, events = events)
        rule.onNodeWithText("Scan pairing QR code").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("use-link").assertIsNotEnabled() // nothing pasted yet
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
            detail = Loadable.Ready(SessionDetail(summary(), caps(), emptyList())),
            events = Loadable.Ready(Unit),
            log = log,
            items = log.renderItems(),
            connection = ONLINE,
        )
    }

    @Test
    fun sessionDetailRendersAllKindsNatively() {
        val state = detailState()
        rule.setContent {
            SwitchTetherTheme {
                SessionDetailContent(
                    state = state, initialTitle = null, ctx = renderCtx(CapStatus.BLOCKED), busy = null,
                    onBack = {}, onReload = {}, onLoadOlder = {}, onDraft = {}, onSend = {}, onInterrupt = {}, onRetryConnection = {},
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
        see("src/login.kt")
        see("Reproduce")
        see("Run rm -rf build")
        see("Answer on the computer", substring = true) // approve BLOCKED is explained
        see("Unknown event: brand_new_kind")
        see("rate limited")
        // Approval buttons exist but are disabled because the capability is BLOCKED.
        list.performScrollToNode(hasText("Allow"))
        rule.onNodeWithText("Allow").assertIsNotEnabled()
        // Composer is disabled with an honest explanation (sendMessage BLOCKED).
        rule.onNodeWithTag("send").assertIsNotEnabled()
        rule.onNodeWithText("Sending messages to this session is not possible", substring = true).assertExists()
        // Capability strip
        rule.onNodeWithText("approve: BLOCKED").assertExists()
    }

    @Test
    fun approvalButtonsEnabledWhenVerified() {
        val state = detailState().let { s ->
            s.copy(detail = Loadable.Ready(SessionDetail(summary(control = "full"), caps(send = "VERIFIED", approve = "VERIFIED", interrupt = "VERIFIED"), emptyList())))
        }
        rule.setContent {
            SwitchTetherTheme {
                SessionDetailContent(
                    state = state, initialTitle = null, ctx = renderCtx(CapStatus.VERIFIED), busy = null,
                    onBack = {}, onReload = {}, onLoadOlder = {}, onDraft = {}, onSend = {}, onInterrupt = {}, onRetryConnection = {},
                )
            }
        }
        rule.onNodeWithTag("event-list").performScrollToNode(hasText("Allow"))
        rule.onNodeWithText("Allow").assertIsEnabled()
        rule.onNodeWithContentDescriptionSafe("Interrupt")
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
