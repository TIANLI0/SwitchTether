package io.github.tianli0.switchtether

import io.github.tianli0.switchtether.remote.session.SlashCommand
import io.github.tianli0.switchtether.ui.sessions.SlashAction
import io.github.tianli0.switchtether.ui.sessions.SlashEntry
import io.github.tianli0.switchtether.ui.sessions.builtinSlashCommands
import io.github.tianli0.switchtether.ui.sessions.filterSlashCommands
import io.github.tianli0.switchtether.ui.sessions.mergeSlashCommands
import io.github.tianli0.switchtether.ui.sessions.slashActionAvailable
import io.github.tianli0.switchtether.ui.sessions.slashActionFor
import io.github.tianli0.switchtether.ui.sessions.slashQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules of the composer's "/" panel. */
class SlashCommandsTest {
    private fun names(l: List<SlashEntry>) = l.map { it.name }

    @Test
    fun queryIsTheTextAfterTheSlashWhileTheDraftIsOneToken() {
        assertEquals("", slashQuery("/"))
        assertEquals("comp", slashQuery("/comp"))
        assertEquals("Compact", slashQuery("/Compact"))
        assertNull(slashQuery(""))
        assertNull(slashQuery("hello"))
        assertNull(slashQuery(" /compact")) // not at the start
        assertNull(slashQuery("/compact ")) // the command is chosen: arguments follow
        assertNull(slashQuery("/review 12"))
        assertNull(slashQuery("/a\nb"))
        assertNull(slashQuery("see /compact"))
    }

    @Test
    fun filterPutsPrefixMatchesFirstThenSubstringsAndCapsTheList() {
        val all = listOf("compact", "clear", "context", "cost", "mcp", "recompile", "export").map { SlashEntry(it) }
        // Prefix matches keep their order, then names that only contain the query.
        assertEquals(listOf("compact", "context", "cost", "recompile"), names(filterSlashCommands(all, "co")))
        assertEquals(listOf("mcp"), names(filterSlashCommands(all, "MC")))
        assertEquals(listOf("export", "context"), names(filterSlashCommands(all, "ex")))
        assertEquals(listOf("compact", "clear", "context", "cost", "mcp", "recompile", "export"), names(filterSlashCommands(all, "")))
        assertTrue(filterSlashCommands(all, "zzz").isEmpty())

        val many = (1..20).map { SlashEntry("cmd$it") }
        assertEquals(8, filterSlashCommands(many, "").size)
        assertEquals(8, filterSlashCommands(many, "cmd").size)
        assertEquals("cmd1", filterSlashCommands(many, "cmd").first().name)
        // A prefix match is never pushed out by the substring matches listed before it.
        val late = (1..10).map { SlashEntry("xa$it") } + SlashEntry("a-last")
        assertEquals("a-last", filterSlashCommands(late, "a").first().name)
        assertEquals(8, filterSlashCommands(late, "a").size)
    }

    @Test
    fun builtinsExistOnlyForClaudeCode() {
        // No /memory: TUI-only in Claude Code (local-jsx, no headless variant), it does nothing as a message.
        val expected = listOf("compact", "clear", "context", "cost", "model", "permissions", "plan", "mcp", "rename", "export", "btw", "review", "init", "help")
        assertEquals(expected, names(builtinSlashCommands("claude_code")))
        assertEquals(expected, names(builtinSlashCommands("claude")))
        builtinSlashCommands("claude_code").forEach { assertNotNull("${it.name} has a description", it.descriptionRes) }
        assertTrue(builtinSlashCommands("codex").isEmpty())
        assertTrue(builtinSlashCommands("gemini").isEmpty())
        assertTrue(builtinSlashCommands(null).isEmpty())
    }

    @Test
    fun withoutTheDesktopsListTheBuiltinsAreUsedAlone() {
        val builtin = builtinSlashCommands("claude_code")
        assertEquals(builtin, mergeSlashCommands(builtin, emptyList()))
    }

    /**
     * The process's list is what it runs headless: built-ins that are messages stay only when it
     * lists them (by name or alias); the phone's own actions always stay, with the phone's text.
     */
    @Test
    fun theDesktopsListDecidesWhichMessagesAreOffered() {
        val builtin = builtinSlashCommands("claude_code")
        val merged = mergeSlashCommands(
            builtin,
            listOf(
                SlashCommand("deploy", "Ship it", "<env>"),
                SlashCommand("compact", "Desktop text", "[instructions]"),
                SlashCommand("Clear", "", "[name]", listOf("reset", "new")),
                SlashCommand("usage", "Show session cost", "", listOf("cost", "stats")),
                SlashCommand("model", "Set the AI model for Claude Code", "<model>"),
                SlashCommand("rename", "Rename the current conversation", "[name]", listOf("name")),
                SlashCommand("deploy", "a repeat", ""),
            ),
        )
        // review and init are not offered by this process: gone. cost is the process's "usage".
        assertEquals(
            listOf("compact", "Clear", "context", "usage", "model", "permissions", "plan", "mcp", "rename", "export", "btw", "help", "deploy"),
            names(merged),
        )
        val compact = merged.first { it.name == "compact" }
        assertEquals("Desktop text", compact.description)
        assertEquals("[instructions]", compact.argumentHint)
        // The desktop's blank description leaves the phone's own text to show.
        val clear = merged.first { it.name == "Clear" }
        assertEquals("", clear.description)
        assertEquals(builtin.first { it.name == "clear" }.descriptionRes, clear.descriptionRes)
        assertEquals(listOf("reset", "new"), clear.aliases)
        val usage = merged.first { it.name == "usage" }
        assertEquals(builtin.first { it.name == "cost" }.descriptionRes, usage.descriptionRes)
        // A phone-side command keeps the phone's text and no terminal argument hint.
        val model = merged.first { it.name == "model" }
        assertEquals(SlashEntry("model", descriptionRes = builtin.first { it.name == "model" }.descriptionRes), model)
        assertEquals(listOf("name"), merged.first { it.name == "rename" }.aliases)
        assertEquals(SlashEntry("deploy", "Ship it", "<env>"), merged.last())
        // No built-ins (another agent): only what the desktop gave.
        assertEquals(listOf("deploy"), names(mergeSlashCommands(emptyList(), listOf(SlashCommand("deploy")))))
    }

    @Test
    fun aliasesFindTheirCommand() {
        val all = listOf(SlashEntry("usage", aliases = listOf("cost", "stats")), SlashEntry("compact"), SlashEntry("rename", aliases = listOf("name")))
        assertEquals(listOf("usage", "compact"), names(filterSlashCommands(all, "co")))
        assertEquals(listOf("usage"), names(filterSlashCommands(all, "stat")))
        assertEquals(listOf("rename"), names(filterSlashCommands(all, "nam")))
    }

    @Test
    fun actionsMapToWhatThePhoneDoesItself() {
        assertEquals(SlashAction.OPEN_MODEL, slashActionFor("model", false))
        assertEquals(SlashAction.OPEN_PERMISSION, slashActionFor("permissions", false))
        assertEquals(SlashAction.CONTEXT, slashActionFor("context", false))
        assertEquals(SlashAction.MCP, slashActionFor("mcp", false))
        assertEquals(SlashAction.PLAN, slashActionFor("plan", false))
        assertEquals(SlashAction.EXPORT, slashActionFor("export", false))
        assertEquals(SlashAction.RENAME, slashActionFor("rename", false))
        assertEquals(SlashAction.SIDE_QUESTION, slashActionFor("btw", false))
        assertEquals(SlashAction.HELP, slashActionFor("help", false))
        // A phone-side command stays one even when the desktop says it takes arguments.
        assertEquals(SlashAction.OPEN_MODEL, slashActionFor("Model", true))
        assertEquals(SlashAction.RENAME, slashActionFor("rename", true))
        // Everything else is a message: sent at once without arguments, inserted with them.
        assertEquals(SlashAction.SEND, slashActionFor("compact", false))
        assertEquals(SlashAction.INSERT, slashActionFor("compact", true))
        // /clear throws the context away: never sent by one tap, it waits in the draft.
        assertEquals(SlashAction.INSERT, slashActionFor("clear", false))
        assertEquals(SlashAction.INSERT, slashActionFor("Clear", true))
        assertEquals(SlashAction.SEND, slashActionFor("my-command", false))
        assertEquals(SlashAction.INSERT, slashActionFor("my-command", true))
        assertEquals(SlashAction.INSERT, slashActionFor(SlashEntry("deploy", argumentHint = "<env>")))
        assertEquals(SlashAction.SEND, slashActionFor(SlashEntry("deploy", argumentHint = " ")))
    }

    @Test
    fun phoneSideActionsNeedTheirTools() {
        for (a in listOf(SlashAction.SEND, SlashAction.INSERT, SlashAction.HELP)) {
            assertTrue(slashActionAvailable(a, claudeTools = false, canChangeModel = false))
        }
        assertTrue(slashActionAvailable(SlashAction.OPEN_MODEL, claudeTools = false, canChangeModel = true))
        assertTrue(slashActionAvailable(SlashAction.OPEN_MODEL, claudeTools = true, canChangeModel = false))
        assertFalse(slashActionAvailable(SlashAction.OPEN_MODEL, claudeTools = false, canChangeModel = false))
        val toolBacked = listOf(
            SlashAction.OPEN_PERMISSION, SlashAction.OPEN_TOOLS, SlashAction.CONTEXT, SlashAction.MCP,
            SlashAction.PLAN, SlashAction.EXPORT, SlashAction.RENAME, SlashAction.SIDE_QUESTION,
        )
        for (a in toolBacked) {
            assertTrue(slashActionAvailable(a, claudeTools = true, canChangeModel = false))
            assertFalse(slashActionAvailable(a, claudeTools = false, canChangeModel = true))
        }
    }
}
