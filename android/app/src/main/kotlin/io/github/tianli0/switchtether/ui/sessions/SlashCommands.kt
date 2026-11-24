package io.github.tianli0.switchtether.ui.sessions

import androidx.annotation.StringRes
import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.remote.session.SlashCommand

/**
 * Slash commands in the composer ("/" like in Claude Code): the pure rules. A command is run by
 * sending "/name args" as an ordinary message; a few names are things the phone already does
 * itself (model, permission mode, session tools) and open that instead.
 *
 * What Claude Code runs when a command arrives as a message in a stream-json session (the managed
 * and taken-over sessions) is decided by its command metadata (2.1.331): `prompt` commands and
 * `local` commands with `supportsNonInteractive`. `local-jsx` commands draw a terminal UI and have
 * no effect there (`/memory`, `/plan`, `/export`, `/btw`, `/permissions`, `/help`, the TUI variant
 * of `/model` …). The process's own list (desktop `claude/commands`) holds exactly what it runs, so
 * when it is there it replaces the phone's guesses for commands that are messages.
 */

/** One row of the "/" panel. */
data class SlashEntry(
    /** Without the leading slash. */
    val name: String,
    /** The desktop's description; when blank, [descriptionRes] (the phone's own text) is shown. */
    val description: String = "",
    val argumentHint: String = "",
    @param:StringRes val descriptionRes: Int? = null,
    /** Other names the agent accepts for it (`cost` for `usage`); typing one finds the entry. */
    val aliases: List<String> = emptyList(),
)

/** What tapping a command does. */
enum class SlashAction {
    /** Send "/name" at once (the command takes nothing). */
    SEND,

    /** Put "/name " into the draft and let the user type the argument (or just send). */
    INSERT,
    OPEN_MODEL,
    OPEN_TOOLS,
    OPEN_PERMISSION,
    CONTEXT,
    RENAME,
    EXPORT,
    SIDE_QUESTION,
    MCP,
    PLAN,
    HELP,
}

/** Agents the phone knows slash commands for. Nothing is invented for the others. */
fun supportsSlashCommands(agent: String?): Boolean = agent == "claude_code" || agent == "claude"

/**
 * The phone's list. Commands that are messages here are only those Claude Code runs headless:
 * `compact`, `clear` (local, `supportsNonInteractive`), `cost` (alias of the headless `usage`),
 * `init` (prompt) and `review` (alias of the bundled `code-review` skill). `memory` is not here:
 * it is TUI-only (`local-jsx`) and has no headless variant. The others are the phone's own actions.
 */
private val CLAUDE_BUILTINS: List<SlashEntry> = listOf(
    SlashEntry("compact", descriptionRes = R.string.slash_compact),
    SlashEntry("clear", descriptionRes = R.string.slash_clear),
    SlashEntry("context", descriptionRes = R.string.slash_context),
    SlashEntry("cost", descriptionRes = R.string.slash_cost),
    SlashEntry("model", descriptionRes = R.string.slash_model),
    SlashEntry("permissions", descriptionRes = R.string.slash_permissions),
    SlashEntry("plan", descriptionRes = R.string.slash_plan),
    SlashEntry("mcp", descriptionRes = R.string.slash_mcp),
    SlashEntry("rename", descriptionRes = R.string.slash_rename),
    SlashEntry("export", descriptionRes = R.string.slash_export),
    SlashEntry("btw", descriptionRes = R.string.slash_btw),
    SlashEntry("review", descriptionRes = R.string.slash_review),
    SlashEntry("init", descriptionRes = R.string.slash_init),
    SlashEntry("help", descriptionRes = R.string.slash_help),
)

/** The phone's own list for [agent]: Claude Code only, empty for every other agent. */
fun builtinSlashCommands(agent: String?): List<SlashEntry> = if (supportsSlashCommands(agent)) CLAUDE_BUILTINS else emptyList()

/** Whether tapping [action] is the phone's own screen rather than a message to the agent. */
fun SlashAction.isPhoneSide(): Boolean = this != SlashAction.SEND && this != SlashAction.INSERT

/**
 * The built-in list alone when the desktop gave none. Otherwise the running process's list is
 * what it can run:
 * - a built-in the phone does itself stays, with the phone's text (the desktop's description and
 *   argument hint describe the terminal command, not what the phone opens);
 * - a built-in that is a message stays only when the process lists it, by name or alias, and
 *   then shows as the process's entry (its name, description unless blank, hint, aliases);
 * - the process's other commands follow in its order.
 */
fun mergeSlashCommands(builtin: List<SlashEntry>, desktop: List<SlashCommand>): List<SlashEntry> {
    if (desktop.isEmpty()) return builtin
    val byName = LinkedHashMap<String, SlashCommand>()
    desktop.forEach { c -> if (c.name.isNotBlank()) byName.putIfAbsent(c.name.lowercase(), c) }
    val byAlias = HashMap<String, SlashCommand>()
    byName.values.forEach { c -> c.aliases.forEach { a -> byAlias.putIfAbsent(a.lowercase(), c) } }
    val used = HashSet<String>()
    fun take(name: String): SlashCommand? {
        val c = byName[name.lowercase()] ?: byAlias[name.lowercase()] ?: return null
        return c.takeIf { used.add(it.name.lowercase()) }
    }
    val merged = builtin.mapNotNull { b ->
        val d = take(b.name)
        when {
            slashActionFor(b).isPhoneSide() -> b.copy(aliases = d?.aliases.orEmpty())
            d == null -> null // the running process does not offer it
            else -> SlashEntry(d.name, d.description, d.argumentHint, descriptionRes = b.descriptionRes, aliases = d.aliases)
        }
    }
    return merged + byName.values.filter { it.name.lowercase() !in used }.map { SlashEntry(it.name, it.description, it.argumentHint, aliases = it.aliases) }
}

/**
 * What tapping "/[name]" does; [hasArgumentHint] decides between sending at once and inserting.
 * `clear` is never sent by one tap: it throws the whole context away, so it waits in the draft
 * for the send key.
 */
fun slashActionFor(name: String, hasArgumentHint: Boolean): SlashAction = when (name.lowercase()) {
    "model" -> SlashAction.OPEN_MODEL
    "permissions" -> SlashAction.OPEN_PERMISSION
    "context" -> SlashAction.CONTEXT
    "mcp" -> SlashAction.MCP
    "plan" -> SlashAction.PLAN
    "export" -> SlashAction.EXPORT
    "rename" -> SlashAction.RENAME
    "btw" -> SlashAction.SIDE_QUESTION
    "help" -> SlashAction.HELP
    "clear" -> SlashAction.INSERT
    else -> if (hasArgumentHint) SlashAction.INSERT else SlashAction.SEND
}

fun slashActionFor(entry: SlashEntry): SlashAction = slashActionFor(entry.name, entry.argumentHint.isNotBlank())

/**
 * Whether the phone can do [action] for this session now: sending and the list itself always;
 * the model sheet when its capsule is shown; everything on the session tools page only while
 * the Claude Code tools are offered (a conversation that is not running has none).
 */
fun slashActionAvailable(action: SlashAction, claudeTools: Boolean, canChangeModel: Boolean): Boolean = when (action) {
    SlashAction.SEND, SlashAction.INSERT, SlashAction.HELP -> true
    SlashAction.OPEN_MODEL -> canChangeModel || claudeTools
    else -> claudeTools
}

/**
 * The text after "/" while the draft is one token starting with "/" (`""` for a lone slash);
 * null once there is a space or a line break, or when it does not start with "/".
 */
fun slashQuery(draft: String): String? {
    if (!draft.startsWith("/")) return null
    val rest = draft.substring(1)
    return if (rest.any { it.isWhitespace() }) null else rest
}

const val SLASH_SUGGESTION_LIMIT = 8

/**
 * Names (or aliases) starting with [query] first, then names containing it; case-insensitive,
 * at most [limit].
 */
fun filterSlashCommands(all: List<SlashEntry>, query: String, limit: Int = SLASH_SUGGESTION_LIMIT): List<SlashEntry> {
    val q = query.lowercase()
    val (prefix, rest) = all.partition { e -> e.name.lowercase().startsWith(q) || e.aliases.any { it.lowercase().startsWith(q) } }
    return (prefix + rest.filter { it.name.lowercase().contains(q) }).take(limit)
}

/** The "/" commands this session offers: only Claude Code, only where the composer can send. */
val SessionDetailState.slashCommands: List<SlashEntry>
    get() {
        val agent = detail.valueOrNull?.summary?.agent
        if (!supportsSlashCommands(agent) || !(canSend || canQueue)) return emptyList()
        return mergeSlashCommands(builtinSlashCommands(agent), desktopSlashCommands)
            .filter { slashActionAvailable(slashActionFor(it), claudeTools, canChangeModel) }
    }

/** Rows of the panel over the composer for the current draft (empty = no panel). */
val SessionDetailState.slashSuggestions: List<SlashEntry>
    get() = slashQuery(draft)?.let { q -> filterSlashCommands(slashCommands, q) }.orEmpty()
