package io.github.tianli0.switchtether.ui.workspace

import io.github.tianli0.switchtether.R
import io.github.tianli0.switchtether.ui.common.UiText

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import io.github.tianli0.switchtether.files.DownloadRecord
import io.github.tianli0.switchtether.files.FileKind
import io.github.tianli0.switchtether.files.FileStore
import io.github.tianli0.switchtether.files.FileTypes
import io.github.tianli0.switchtether.files.SortKey
import io.github.tianli0.switchtether.files.SortOrder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tianli0.switchtether.connection.ConnectionController
import io.github.tianli0.switchtether.connection.ConnectionManager
import io.github.tianli0.switchtether.connection.ConnectionState
import io.github.tianli0.switchtether.domain.StepUpGate
import io.github.tianli0.switchtether.remote.api.ApiException
import io.github.tianli0.switchtether.remote.api.CommandResponse
import io.github.tianli0.switchtether.remote.api.RemoteApi
import io.github.tianli0.switchtether.remote.command.CommandOutcome
import io.github.tianli0.switchtether.remote.command.Commands
import io.github.tianli0.switchtether.remote.files.TextDiff
import io.github.tianli0.switchtether.remote.files.UploadPlan
import io.github.tianli0.switchtether.remote.files.copyPathFor
import io.github.tianli0.switchtether.remote.protocol.BeginUploadBody
import io.github.tianli0.switchtether.remote.protocol.ErrorCodes
import io.github.tianli0.switchtether.remote.protocol.FileContent
import io.github.tianli0.switchtether.remote.protocol.FsEntry
import io.github.tianli0.switchtether.remote.protocol.FsListing
import io.github.tianli0.switchtether.remote.protocol.GitCommit
import io.github.tianli0.switchtether.remote.protocol.GitOps
import io.github.tianli0.switchtether.remote.protocol.GitWriteBody
import io.github.tianli0.switchtether.remote.protocol.PathBody
import io.github.tianli0.switchtether.remote.protocol.RenameBody
import io.github.tianli0.switchtether.remote.protocol.RepoStatus
import io.github.tianli0.switchtether.remote.protocol.SearchHit
import io.github.tianli0.switchtether.remote.protocol.StepUpScopes
import io.github.tianli0.switchtether.remote.protocol.WriteBody
import io.github.tianli0.switchtether.remote.protocol.obj
import io.github.tianli0.switchtether.remote.protocol.str
import io.github.tianli0.switchtether.ui.common.Loadable
import io.github.tianli0.switchtether.ui.common.loadable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The desktop changed the file since it was opened (409 conflict on write). */
data class ConflictState(
    val path: String,
    val mine: String,
    val desktopText: String?,
    val desktopSha: String?,
    val suggestedCopyPath: String,
) {
    /** Native unified diff: desktop (current) → mine. */
    val diff: String get() = TextDiff.unified(desktopText.orEmpty(), mine)
}

/** [downloaded] is set when a download finished: the record the "Open" / "Manage downloads" actions work on. */
data class TransferState(
    val label: String,
    val done: Long,
    val total: Long?,
    val error: UiText? = null,
    val finished: Boolean = false,
    val downloaded: DownloadRecord? = null,
)

/** A remote file opened from a local copy (cache, or the user's download): pdf / image / video / audio. */
data class ViewerState(val path: String, val name: String, val kind: FileKind, val size: Long? = null, val modifiedMs: Long? = null)

data class FilesUiState(
    val online: Boolean = false,
    val roots: Loadable<List<FsEntry>> = Loadable.Loading,
    val listing: Loadable<FsListing>? = null,
    val showHidden: Boolean = false,
    val query: String = "",
    val searchContent: Boolean = false,
    val results: Loadable<List<SearchHit>>? = null,
    /** File opened in the viewer/editor (contents in memory only). */
    val file: Loadable<FileContent>? = null,
    val pendingSensitive: FsEntry? = null,
    val editing: Boolean = false,
    val editText: String = "",
    val saving: Boolean = false,
    val conflict: ConflictState? = null,
    val message: io.github.tianli0.switchtether.ui.common.UiText? = null,
    val transfer: TransferState? = null,
    /** A media/binary file opened from a local (cache or download) copy; takes over the pane like [file]. */
    val viewer: ViewerState? = null,
    /** Leaving the editor with unsaved changes asks first. */
    val confirmDiscard: Boolean = false,
    /** Pull-to-refresh of the current folder is running (the list stays visible). */
    val refreshing: Boolean = false,
    /** Folder order (folders first, then by this key). */
    val sort: SortOrder = SortOrder(),
    /** A folder is being opened: the previous list stays on screen (with a progress bar) until it arrives. */
    val navigatingTo: String? = null,
    // git
    val repo: String? = null,
    val git: Loadable<RepoStatus>? = null,
    val gitDiff: Pair<String, Loadable<String>>? = null,
    val gitLog: Loadable<List<GitCommit>>? = null,
    val gitShow: Pair<String, Loadable<String>>? = null,
    /** The desktop offers Git writes (feature `git_write`); without it the page stays read-only. */
    val gitWritable: Boolean = false,
    /** The Git write running now ([GitOps]); its buttons show progress. */
    val gitBusy: String? = null,
    /** Outcome of the last Git write (toast on success, dialog with git's message on failure). */
    val gitNotice: GitNotice? = null,
    val gitBranches: Loadable<io.github.tianli0.switchtether.remote.protocol.GitBranches>? = null,
    val commitMessage: String = "",
    /** The next commit replaces the last one (`--amend`). */
    val amend: Boolean = false,
    /** The desktop offers commit details, branch delete, merge and stash (feature `git_manage`). */
    val gitManage: Boolean = false,
    /** A commit opened from the history: its hash and its message / changed files. */
    val gitCommit: Pair<String, Loadable<io.github.tianli0.switchtether.remote.protocol.GitCommitDetail>>? = null,
    /** The history may have older commits than the ones loaded; a page is being loaded. */
    val gitLogMore: Boolean = false,
    val gitLogLoadingMore: Boolean = false,
    val gitStashes: Loadable<List<io.github.tianli0.switchtether.remote.protocol.GitStash>>? = null,
    /** "本轮改动": files the session's latest turn edited (as the agent wrote them) and its folder. */
    val turnFiles: List<String> = emptyList(),
    val turnCwd: String? = null,
    /** Show only the latest turn's files. */
    val onlyTurn: Boolean = false,
)

/** Text from the desktop or the network shown as is (already in the user's words or git's). */
fun plainText(text: String): UiText = UiText(R.string.files_text_plain, listOf(text))

/** Body of a file-at-commit that has no text (binary): the page shows its own label instead. */
const val GIT_SHOW_BINARY = "\u0000binary"

/** A transfer (upload or download) is running: only one at a time shares the progress card. */
val FilesUiState.transferRunning: Boolean get() = transfer.let { it != null && it.error == null && !it.finished }

/** Result of one Git write: [ok] with git's [text] (push / pull output), or the desktop's error message. */
data class GitNotice(val op: String, val ok: Boolean, val text: String? = null)

/**
 * Files & Git workspace. Reads are plain GETs; every write (save, create, rename, delete,
 * upload) first ensures a `files_write` step-up grant and carries a commandId. Saves send the
 * `baseSha256` of the version that was opened; a 409 conflict opens [ConflictState] instead of
 * overwriting anything.
 */
/** Writes after which the commit list changes. */
private val HISTORY_OPS = setOf(GitOps.COMMIT, GitOps.PULL, GitOps.CHECKOUT, GitOps.PUSH, GitOps.FETCH, GitOps.MERGE, GitOps.MERGE_ABORT)

/** Commits per history page. */
private const val LOG_PAGE = 50

class FilesViewModel(
    private val conn: ConnectionController,
    private val gate: StepUpGate,
    private val resolverProvider: () -> ContentResolver,
    private val store: FileStore? = null,
    /** Uploads run as user-initiated data transfer jobs (docs/ANDROID_UI.md §36); downloads do so inside [FileStore]. */
    private val transfers: io.github.tianli0.switchtether.transfer.TransferRunner = io.github.tianli0.switchtether.transfer.DirectTransfers,
    private val defaultRepo: suspend () -> String?,
) : ViewModel() {
    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()
    private var uploadJob: Job? = null
    private val resolver: ContentResolver get() = resolverProvider()

    init {
        viewModelScope.launch {
            conn.state.distinctUntilChangedBy { it::class }.collect { s ->
                _state.update { it.copy(online = s is ConnectionState.Online) }
                if (s is ConnectionState.Online && _state.value.roots !is Loadable.Ready) loadRoots()
            }
        }
    }

    private fun api(): RemoteApi? = conn.apiOrNull()

    fun loadRoots() {
        val api = api() ?: return
        viewModelScope.launch {
            val r = loadable { api.fsRoots().roots }
            _state.update { it.copy(roots = r) }
            if (_state.value.listing == null) r.valueOrNull?.firstOrNull()?.let { browse(it.path) }
            if (_state.value.repo == null) _state.update { it.copy(repo = runCatching { defaultRepo() }.getOrNull() ?: r.valueOrNull?.firstOrNull()?.path) }
        }
    }

    private var browseJob: Job? = null

    fun browse(path: String) {
        val api = api() ?: return
        // Opening a folder always shows that folder: drop a file / viewer left open from before. The quick filter
        // belongs to the folder it was typed in. The old list stays visible until the new one arrives (no flash).
        browseJob?.cancel()
        _state.update {
            it.copy(
                listing = if (it.listing is Loadable.Ready) it.listing else Loadable.Loading,
                navigatingTo = path, query = "", results = null, file = null, viewer = null, editing = false, conflict = null, confirmDiscard = false,
            )
        }
        browseJob = viewModelScope.launch {
            val r = loadable { api.fsList(path, _state.value.showHidden) }
            _state.update { it.copy(listing = r, navigatingTo = null) }
        }
    }

    /** UI-only: list scroll position (first item, offset) per folder path, kept while the page lives. */
    val scrollPositions: MutableMap<String, Pair<Int, Int>> = mutableMapOf()

    /** Sorts the folder by [key]; choosing the current key again flips the direction. */
    fun sortBy(key: SortKey) = _state.update { it.copy(sort = it.sort.toggled(key)) }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        currentDir()?.let(::browse)
    }

    fun currentDir(): String? = (_state.value.listing as? Loadable.Ready)?.value?.path

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /** Leaves search: the folder list comes back. */
    fun clearSearch() = _state.update { it.copy(query = "", results = null) }

    /** Reloads the current folder in place (pull-to-refresh). */
    fun refreshListing() {
        val api = api() ?: return
        val path = currentDir() ?: return loadRoots()
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val r = loadable { api.fsList(path, _state.value.showHidden) }
            _state.update { it.copy(listing = r, refreshing = false) }
        }
    }

    /** The editor has changes that are not on the desktop yet. */
    val hasUnsavedEdits: Boolean
        get() = _state.value.let { st -> st.editing && st.editText != (st.file as? Loadable.Ready)?.value?.text.orEmpty() }

    /** Back from a file: unsaved edits ask first; otherwise leave the editor, then the file. */
    fun requestCloseFile() {
        when {
            hasUnsavedEdits -> _state.update { it.copy(confirmDiscard = true) }
            _state.value.editing -> cancelEdit()
            else -> closeFile()
        }
    }

    fun discardEdits() = _state.update { it.copy(confirmDiscard = false, editing = false) }
    fun keepEditing() = _state.update { it.copy(confirmDiscard = false) }

    /** Switching between name and content search runs the search again when results are on screen. */
    fun setSearchContent(v: Boolean) {
        val rerun = _state.value.results != null && _state.value.searchContent != v
        _state.update { it.copy(searchContent = v) }
        if (rerun) search()
    }

    fun search() {
        val api = api() ?: return
        val root = currentDir() ?: return
        val q = _state.value.query.trim()
        if (q.isEmpty()) {
            _state.update { it.copy(results = null) }
            return
        }
        _state.update { it.copy(results = Loadable.Loading) }
        viewModelScope.launch {
            val r = loadable { SearchHit.parseList(api.fsSearch(root, q, _state.value.searchContent).obj()) }
            _state.update { it.copy(results = r) }
        }
    }

    /** Sensitive entries (keys, .env…) need an explicit confirmation before their content is read. */
    fun open(entry: FsEntry, confirmedSensitive: Boolean = false) {
        if (entry.isDir) return browse(entry.path)
        if (entry.sensitive && !confirmedSensitive) {
            _state.update { it.copy(pendingSensitive = entry) }
            return
        }
        // Sensitive files are never previewed from a local copy (the desktop refuses to download them);
        // they keep the confirmed in-memory text read.
        if (!entry.sensitive && store != null && FileTypes.kindOf(entry.name).isMedia) {
            return openViewer(entry.path, entry.name, entry.size.takeIf { it > 0 }, entry.modifiedMs)
        }
        openPath(entry.path, allowSensitive = entry.sensitive)
    }

    fun dismissSensitive() = _state.update { it.copy(pendingSensitive = null) }

    fun openPath(path: String, allowSensitive: Boolean = false) {
        if (!allowSensitive && store != null && FileTypes.kindOf(path).isMedia) return openViewer(path, FileTypes.fileName(path), null, null)
        val api = api() ?: return
        _state.update { it.copy(file = Loadable.Loading, pendingSensitive = null, editing = false, conflict = null) }
        viewModelScope.launch {
            val r = loadable { api.fsRead(path, allowSensitive) }
            _state.update { it.copy(file = r) }
        }
    }

    fun closeFile() = _state.update { it.copy(file = null, editing = false, conflict = null) }

    // ---- local viewing (cache) ---------------------------------------------------------------------------

    /**
     * Opens [path] in the local viewer. The viewer itself resolves a copy: an existing user download if there is
     * one, else a temporary cache copy; opening never creates a download.
     */
    fun openViewer(path: String, name: String, size: Long?, modifiedMs: Long?) {
        _state.update { it.copy(viewer = ViewerState(path, name, FileTypes.kindOf(name), size, modifiedMs), file = null, editing = false, conflict = null, pendingSensitive = null) }
    }

    fun closeViewer() = _state.update { it.copy(viewer = null) }

    fun startEdit() {
        val f = (_state.value.file as? Loadable.Ready)?.value ?: return
        if (f.text == null || f.truncated) {
            _state.update { it.copy(message = UiText(R.string.files_msg_not_editable)) }
            return
        }
        _state.update { it.copy(editing = true, editText = f.text.orEmpty()) }
    }

    fun setEditText(t: String) = _state.update { it.copy(editText = t) }

    fun cancelEdit() = _state.update { it.copy(editing = false) }

    /** Write with a files_write step-up; asks again once if the grant expired server-side. */
    private suspend fun writeOp(label: Int, op: suspend (RemoteApi, String) -> CommandResponse): CommandOutcome? {
        val api = api() ?: return null
        repeat(2) {
            if (gate.ensure(StepUpScopes.FILES_WRITE).isFailure) {
                _state.update { it.copy(message = UiText(R.string.files_msg_cancelled, listOf(label))) }
                return null
            }
            val out = Commands.run { id -> op(api, id) }
            if (out is CommandOutcome.Rejected && out.error.code == ErrorCodes.STEP_UP_REQUIRED) {
                gate.invalidate(StepUpScopes.FILES_WRITE)
            } else {
                return out
            }
        }
        return null
    }

    fun save() {
        val f = (_state.value.file as? Loadable.Ready)?.value ?: return
        val mine = _state.value.editText
        write(f, mine, base = f.sha256)
    }

    private fun write(f: FileContent, text: String, base: String?, path: String = f.path, createOnly: Boolean = false, after: () -> Unit = {}) {
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val out = writeOp(R.string.files_op_save) { api, id -> api.fsWrite(WriteBody(id, path, text, base, f.encoding, f.bom, createOnly)) }
            when (out) {
                is CommandOutcome.Succeeded -> {
                    val sha = out.response.body.obj()?.str("sha256") ?: f.sha256
                    if (path == f.path) {
                        _state.update { it.copy(saving = false, editing = false, conflict = null, file = Loadable.Ready(f.copy(text = text, sha256 = sha)), message = UiText(R.string.files_msg_saved)) }
                    } else {
                        _state.update { it.copy(saving = false, conflict = null, message = UiText(R.string.files_msg_saved_as, listOf(path))) }
                    }
                    after()
                }
                is CommandOutcome.Rejected -> {
                    val e = out.error
                    if (e.code == ErrorCodes.CONFLICT && path == f.path && !createOnly) {
                        val d = e.body.details
                        _state.update {
                            it.copy(
                                saving = false,
                                conflict = ConflictState(f.path, text, d?.str("currentText"), d?.str("currentSha256"), d?.str("suggestedCopyPath") ?: copyPathFor(f.path)),
                            )
                        }
                    } else {
                        _state.update { it.copy(saving = false, message = UiText(R.string.files_msg_rejected, listOf(R.string.files_op_save, "${e.code}: ${e.body.message}"))) }
                    }
                }
                null -> _state.update { it.copy(saving = false) }
                else -> _state.update { it.copy(saving = false, message = UiText(R.string.files_msg_unknown, listOf(R.string.files_op_save))) }
            }
        }
    }

    // ---- conflict actions ---------------------------------------------------------------------------

    /** Keep my version as a new file next to it (createOnly; never touches the desktop's file). */
    fun keepMineAsCopy() {
        val c = _state.value.conflict ?: return
        val f = (_state.value.file as? Loadable.Ready)?.value ?: return
        write(f, c.mine, base = null, path = c.suggestedCopyPath, createOnly = true) { openPath(f.path, f.sensitive) }
    }

    /** Discard my edit and show the desktop's version. */
    fun reloadDesktop() {
        val f = (_state.value.file as? Loadable.Ready)?.value ?: return
        openPath(f.path, f.sensitive)
    }

    /** Overwrite: re-read the CURRENT sha first, then write against it (explicitly confirmed in UI). */
    fun overwrite() {
        val c = _state.value.conflict ?: return
        val f = (_state.value.file as? Loadable.Ready)?.value ?: return
        val api = api() ?: return
        viewModelScope.launch {
            val current = runCatching { api.fsRead(f.path, f.sensitive) }.getOrNull()
            write(f.copy(sha256 = current?.sha256 ?: c.desktopSha.orEmpty()), c.mine, base = current?.sha256 ?: c.desktopSha)
        }
    }

    /** Manual merge: edit starting from the desktop text plus my version, saved against the desktop sha. */
    fun startManualMerge() {
        val c = _state.value.conflict ?: return
        val f = (_state.value.file as? Loadable.Ready)?.value ?: return
        val merged = "<<<<<<< desktop\n${c.desktopText.orEmpty()}\n=======\n${c.mine}\n>>>>>>> mine\n"
        _state.update {
            it.copy(conflict = null, editing = true, editText = merged, file = Loadable.Ready(f.copy(text = c.desktopText, sha256 = c.desktopSha ?: f.sha256)))
        }
    }

    fun dismissConflict() = _state.update { it.copy(conflict = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ---- create / rename / delete ---------------------------------------------------------------------

    fun mkdir(name: String) {
        val dir = currentDir() ?: return noDirectory(R.string.files_op_mkdir)
        pathOp(R.string.files_op_mkdir) { api, id -> api.fsMkdir(PathBody(id, join(dir, name))) }
    }

    /** No directory is loaded yet: report it like any other refused operation; nothing is sent. */
    private fun noDirectory(label: Int) = _state.update { it.copy(message = UiText(R.string.files_msg_rejected, listOf(label, R.string.files_err_no_directory))) }

    fun newFile(name: String) {
        val dir = currentDir() ?: return noDirectory(R.string.files_op_save)
        val path = join(dir, name)
        write(FileContent(path = path), "", base = null, path = path, createOnly = true) { refreshAfterOp(dir) }
    }

    fun rename(entry: FsEntry, newName: String) = pathOp(R.string.files_op_rename, touched = entry.path) { api, id ->
        api.fsRename(RenameBody(id, entry.path, join(entry.path.substring(0, maxOf(entry.path.lastIndexOf('\\'), entry.path.lastIndexOf('/'))), newName)))
    }

    /** Moves to the desktop's trash/recycle bin (server side). */
    fun delete(entry: FsEntry) = pathOp(R.string.files_op_delete, touched = entry.path) { api, id -> api.fsDelete(PathBody(id, entry.path)) }

    /**
     * A create / rename / delete in the current folder. Afterwards the folder list is reloaded in place: a file
     * or editor open beside it stays open, unless it is [touched] (renamed or deleted) or lies inside it.
     */
    private fun pathOp(label: Int, touched: String? = null, op: suspend (RemoteApi, String) -> CommandResponse) {
        viewModelScope.launch {
            val out = writeOp(label, op)
            when (out) {
                is CommandOutcome.Succeeded -> {
                    _state.update { it.copy(message = UiText(R.string.files_msg_ok, listOf(label))) }
                    if (touched != null) closeIfUnder(touched)
                }
                is CommandOutcome.Rejected -> _state.update { it.copy(message = UiText(R.string.files_msg_rejected, listOf(label, "${out.error.code}: ${out.error.body.message}"))) }
                null -> Unit
                else -> _state.update { it.copy(message = UiText(R.string.files_msg_unknown, listOf(label))) }
            }
            currentDir()?.let(::refreshAfterOp)
        }
    }

    /** Reloads [dir] in place after a write, if the user is still in it; nothing open is closed. */
    private fun refreshAfterOp(dir: String) {
        val api = api() ?: return
        if (currentDir() != dir) return
        viewModelScope.launch {
            val r = loadable { api.fsList(dir, _state.value.showHidden) }
            // The user may have opened another folder meanwhile: keep that one.
            _state.update { if (it.navigatingTo == null && (it.listing as? Loadable.Ready)?.value?.path == dir) it.copy(listing = r) else it }
        }
    }

    /** The open file / viewer is [path] or lies inside it (a renamed or deleted folder): close it. */
    private fun closeIfUnder(path: String) {
        val p = path.trimEnd('/', '\\')
        fun hit(open: String?) = open != null && (open == p || open.startsWith("$p/") || open.startsWith("$p\\"))
        _state.update { st ->
            val filePath = (st.file as? Loadable.Ready)?.value?.path
            var n = st
            if (hit(filePath)) n = n.copy(file = null, editing = false, conflict = null, confirmDiscard = false)
            if (hit(st.viewer?.path)) n = n.copy(viewer = null)
            n
        }
    }

    /** Before picking a file to upload: says so and returns true when a transfer already runs. */
    fun refuseIfTransferring(): Boolean = refuseBusyTransfer()

    private fun refuseBusyTransfer(): Boolean {
        if (!_state.value.transferRunning) return false
        _state.update { it.copy(message = UiText(R.string.files_msg_transfer_busy)) }
        return true
    }

    private fun join(dir: String, name: String): String {
        val sep = if (dir.contains('\\')) "\\" else "/"
        return if (dir.endsWith(sep)) dir + name else dir + sep + name
    }

    // ---- upload / download -------------------------------------------------------------------------

    /** SAF document → chunked upload into the current directory (progress + cancel). */
    fun upload(uri: Uri) {
        val dir = currentDir() ?: return
        if (refuseBusyTransfer()) return
        uploadJob = viewModelScope.launch {
            val (name, size) = withContext(Dispatchers.IO) {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) (c.getString(0) ?: "upload.bin") to c.getLong(1) else null
                } ?: ("upload.bin" to -1L)
            }
            if (size < 0) {
                _state.update { it.copy(transfer = TransferState(name, 0, null, UiText(R.string.files_err_unknown_size))) }
                return@launch
            }
            _state.update { it.copy(transfer = TransferState(name, 0, size)) }
            val begin = writeOp(R.string.files_op_upload) { api, id ->
                val v = api.beginUpload(BeginUploadBody(id, join(dir, name), size))
                CommandResponse(io.github.tianli0.switchtether.remote.protocol.ProtocolJson.encodeToJsonElement(io.github.tianli0.switchtether.remote.protocol.UploadView.serializer(), v), false)
            }
            val uploadId = (begin as? CommandOutcome.Succeeded)?.response?.body?.obj()?.str("uploadId")
            if (uploadId == null) {
                _state.update { it.copy(transfer = it.transfer?.copy(error = (begin as? CommandOutcome.Rejected)?.error?.body?.message?.let(::plainText) ?: UiText(R.string.files_err_upload_refused))) }
                return@launch
            }
            val api = api()
            if (api == null) {
                // The connection went away after the upload was opened: no request can be sent, not even the
                // cancel, but the transfer must not look in progress forever.
                _state.update { it.copy(transfer = it.transfer?.copy(error = UiText(R.string.files_err_not_connected))) }
                return@launch
            }
            try {
                transfers.run(io.github.tianli0.switchtether.transfer.TransferKind.FILE_UPLOAD, name, size) { ticket ->
                  ticket.ref(io.github.tianli0.switchtether.transfer.RemoteRef(uploadId = uploadId))
                  withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)!!.use { input ->
                        for ((offset, len) in UploadPlan.chunks(size)) {
                            ensureActive()
                            val buf = ByteArray(len.toInt())
                            var read = 0
                            while (read < len) {
                                val r = input.read(buf, read, len.toInt() - read)
                                if (r < 0) break
                                read += r
                            }
                            // The document ended before the size it reported: never report a truncated upload as done.
                            if (read < len) throw java.io.IOException("file ended after ${offset + read} of $size bytes")
                            api.uploadChunk(uploadId, offset, if (read == buf.size) buf else buf.copyOf(read))
                            ticket.progress(offset + read, size)
                            _state.update { it.copy(transfer = it.transfer?.copy(done = offset + read)) }
                        }
                    }
                  }
                }
                _state.update { it.copy(transfer = it.transfer?.copy(finished = true)) }
                refreshAfterOp(dir)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The job is already cancelled: without NonCancellable the request would never be sent and the
                // partial upload would stay on the computer.
                withContext(NonCancellable) { runCatching { api.cancelUpload(uploadId) } }
                // Also when cancelled from the transfer notification: the card must not stay "in progress".
                _state.update { it.copy(transfer = it.transfer?.takeIf { t -> t.error == null }?.copy(error = UiText(R.string.files_err_cancelled)) ?: it.transfer) }
                throw e
            } catch (e: Exception) {
                runCatching { api.cancelUpload(uploadId) }
                _state.update { it.copy(transfer = it.transfer?.copy(error = plainText((e as? ApiException)?.body?.message ?: ConnectionManager.describe(e)))) }
            }
        }
    }

    fun cancelTransfer() {
        uploadJob?.cancel()
        _state.update { it.copy(transfer = it.transfer?.copy(error = UiText(R.string.files_err_cancelled))) }
    }

    fun dismissTransfer() = _state.update { it.copy(transfer = null) }

    /**
     * Explicit download into the public Downloads collection (`Downloads/SwitchTether`, MediaStore: no storage
     * permission needed). Recorded in the download index so it can be opened and managed later; unlike a
     * preview it is never evicted automatically.
     */
    fun download(entry: FsEntry) {
        val store = store ?: return
        if (refuseBusyTransfer()) return
        uploadJob = viewModelScope.launch {
            _state.update { it.copy(transfer = TransferState(entry.name, 0, entry.size.takeIf { s -> s > 0 })) }
            try {
                val record = store.download(entry.path, entry.name, entry.size.takeIf { it > 0 }, entry.modifiedMs) { n, total -> _state.update { it.copy(transfer = it.transfer?.copy(done = n, total = total)) } }
                _state.update { it.copy(transfer = it.transfer?.copy(finished = true, downloaded = record)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(transfer = it.transfer?.copy(error = UiText(R.string.files_err_cancelled))) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(transfer = it.transfer?.copy(error = plainText((e as? ApiException)?.body?.message ?: ConnectionManager.describe(e)))) }
            }
        }
    }

    // ---- git ------------------------------------------------------------------------------------------

    fun setRepo(path: String) {
        _state.update {
            // Another repository: the session's "latest turn" no longer applies.
            val sameRepo = samePath(it.repo, path)
            it.copy(
                repo = path, git = null, gitLog = null, gitDiff = null, gitShow = null, gitBranches = null,
                gitCommit = null, gitStashes = null, gitLogMore = false,
                turnFiles = if (sameRepo) it.turnFiles else emptyList(),
                turnCwd = if (sameRepo) it.turnCwd else null,
                onlyTurn = sameRepo && it.onlyTurn,
            )
        }
        loadGit()
    }

    /** Opens the review of a session's changes: its folder's repository, the latest turn's files first. */
    fun openReview(cwd: String, turnFiles: List<String>) {
        _state.update {
            it.copy(
                repo = cwd, git = null, gitLog = null, gitDiff = null, gitShow = null, gitBranches = null,
                gitCommit = null, gitStashes = null, gitLogMore = false,
                turnFiles = turnFiles, turnCwd = cwd, onlyTurn = turnFiles.isNotEmpty(), commitMessage = "",
            )
        }
        loadGit()
    }

    fun loadGit() {
        val api = api() ?: return
        val repo = _state.value.repo ?: return
        _state.update { it.copy(git = Loadable.Loading) }
        viewModelScope.launch {
            val st = loadable { api.gitStatus(repo) }
            val log = loadable { api.gitLog(repo, limit = LOG_PAGE).commits }
            val features = runCatching { api.features() }.getOrDefault(emptySet())
            val manage = "git_manage" in features
            val stashes = if (manage) loadable { api.gitStashes(repo).stashes } else null
            _state.update {
                if (it.repo != repo) return@update it
                // A folder inside a repository opens that repository: one entry per repository,
                // not one per subfolder that was browsed (the same status under another name).
                val root = (st as? Loadable.Ready)?.value?.root?.takeIf { r -> r.isNotBlank() && !samePath(r, repo) }
                it.copy(
                    repo = root ?: repo,
                    git = st,
                    gitLog = log,
                    gitLogMore = (log as? Loadable.Ready)?.value?.size == LOG_PAGE,
                    gitWritable = "git_write" in features,
                    gitManage = manage,
                    gitStashes = stashes,
                )
            }
        }
    }

    /** The next page of the history (older commits). */
    fun loadMoreLog() {
        val api = api() ?: return
        val s0 = _state.value
        val repo = s0.repo ?: return
        val have = (s0.gitLog as? Loadable.Ready)?.value ?: return
        if (!s0.gitLogMore || s0.gitLogLoadingMore) return
        _state.update { it.copy(gitLogLoadingMore = true) }
        viewModelScope.launch {
            val page = runCatching { api.gitLog(repo, limit = LOG_PAGE, skip = have.size).commits }.getOrNull()
            _state.update {
                if (it.repo != repo) return@update it
                val cur = (it.gitLog as? Loadable.Ready)?.value.orEmpty()
                if (page == null) {
                    it.copy(gitLogLoadingMore = false)
                } else {
                    it.copy(gitLog = Loadable.Ready(cur + page), gitLogMore = page.size == LOG_PAGE, gitLogLoadingMore = false)
                }
            }
        }
    }

    fun loadStashes() {
        val api = api() ?: return
        val repo = _state.value.repo ?: return
        if (!_state.value.gitManage) return
        viewModelScope.launch {
            val st = loadable { api.gitStashes(repo).stashes }
            _state.update { if (it.repo == repo) it.copy(gitStashes = st) else it }
        }
    }

    /** Opens a commit of the history: its message and changed files (feature `git_manage`). */
    fun openCommit(hash: String) {
        val api = api() ?: return
        val repo = _state.value.repo ?: return
        _state.update { it.copy(gitCommit = hash to Loadable.Loading) }
        viewModelScope.launch {
            val d = loadable { api.gitCommit(repo, hash) }
            _state.update { if (it.gitCommit?.first == hash) it.copy(gitCommit = hash to d) else it }
        }
    }

    fun retryCommit() {
        _state.value.gitCommit?.first?.let(::openCommit)
    }

    fun closeCommit() = _state.update { it.copy(gitCommit = null) }

    /** What commit [rev] changed in [path] ([origPath]: its name before a rename). */
    fun commitFileDiff(rev: String, path: String, origPath: String? = null) {
        lastGitDetail = { commitFileDiff(rev, path, origPath) }
        val api = api() ?: return
        val repo = _state.value.repo ?: return
        val key = "${rev.take(8)}:$path"
        _state.update { it.copy(gitDiff = key to Loadable.Loading) }
        viewModelScope.launch {
            val d = loadable { api.gitDiff(repo, path, rev = rev, origPath = origPath).diff }
            _state.update { if (it.gitDiff?.first == key) it.copy(gitDiff = key to d) else it }
        }
    }

    fun setAmend(on: Boolean) {
        _state.update { st ->
            // Amending starts from the last commit's message, as `git commit --amend` does.
            val last = (st.gitLog as? Loadable.Ready)?.value?.firstOrNull()?.subject
            st.copy(amend = on, commitMessage = if (on && st.commitMessage.isBlank() && last != null) last else st.commitMessage)
        }
    }

    fun setOnlyTurn(on: Boolean) = _state.update { it.copy(onlyTurn = on) }

    fun setCommitMessage(t: String) = _state.update { it.copy(commitMessage = t) }

    fun clearGitNotice() = _state.update { it.copy(gitNotice = null) }

    fun loadBranches() {
        val api = api() ?: return
        val repo = _state.value.repo ?: return
        _state.update { it.copy(gitBranches = Loadable.Loading) }
        viewModelScope.launch {
            val b = loadable { api.gitBranches(repo) }
            _state.update { if (it.repo == repo) it.copy(gitBranches = b) else it }
        }
    }

    fun stage(paths: List<String>) = gitWrite(GitOps.STAGE) { id, repo -> GitWriteBody(id, repo, paths = paths) }

    fun stageAll() = gitWrite(GitOps.STAGE) { id, repo -> GitWriteBody(id, repo, all = true) }

    fun unstage(paths: List<String>) = gitWrite(GitOps.UNSTAGE) { id, repo -> GitWriteBody(id, repo, paths = paths) }

    /** Tracked files go back to their staged / committed version; untracked ones to the computer's trash. */
    fun discard(paths: List<String>) = gitWrite(GitOps.DISCARD) { id, repo -> GitWriteBody(id, repo, paths = paths) }

    fun commit() {
        val msg = _state.value.commitMessage.trim()
        if (msg.isEmpty()) return
        val amend = _state.value.amend
        gitWrite(GitOps.COMMIT, after = { _state.update { it.copy(commitMessage = "", amend = false) } }) { id, repo ->
            GitWriteBody(id, repo, message = msg, amend = amend)
        }
    }

    /** Deletes a local branch (never the current one); [force] also an unmerged one. */
    fun deleteBranch(branch: String, force: Boolean) =
        gitWrite(GitOps.BRANCH_DELETE, after = { loadBranches() }) { id, repo -> GitWriteBody(id, repo, branch = branch, force = force) }

    /** Merges [branch] into the current branch; conflicts leave the repository merging (abort or resolve). */
    fun merge(branch: String) =
        gitWrite(GitOps.MERGE, after = { loadBranches() }) { id, repo -> GitWriteBody(id, repo, branch = branch) }

    fun mergeAbort() = gitWrite(GitOps.MERGE_ABORT) { id, repo -> GitWriteBody(id, repo) }

    /** Puts every change (untracked files included) aside. */
    fun stashPush(message: String?) =
        gitWrite(GitOps.STASH_PUSH, after = { loadStashes() }) { id, repo -> GitWriteBody(id, repo, message = message?.trim()?.ifEmpty { null }) }

    fun stashPop(index: Int) =
        gitWrite(GitOps.STASH_POP, after = { loadStashes() }) { id, repo -> GitWriteBody(id, repo, index = index) }

    /** Pushes the current branch; a branch without upstream is published to origin (`-u`). */
    fun push() {
        val st = (_state.value.git as? Loadable.Ready)?.value
        val setUpstream = st != null && st.branch != null && st.upstream == null
        gitWrite(GitOps.PUSH) { id, repo -> GitWriteBody(id, repo, setUpstream = setUpstream) }
    }

    fun pull() = gitWrite(GitOps.PULL) { id, repo -> GitWriteBody(id, repo) }

    fun fetch() = gitWrite(GitOps.FETCH) { id, repo -> GitWriteBody(id, repo) }

    fun checkout(branch: String, create: Boolean) =
        gitWrite(GitOps.CHECKOUT, after = { loadBranches() }) { id, repo -> GitWriteBody(id, repo, branch = branch, create = create) }

    /**
     * One Git write: `git_write` step-up (asked again once if the grant expired on the desktop), a
     * commandId, the repository's new state from the answer. Never two at a time.
     */
    private fun gitWrite(op: String, after: () -> Unit = {}, body: (String, String) -> GitWriteBody) {
        val api = api() ?: return
        val repo = _state.value.repo ?: return
        if (_state.value.gitBusy != null) return
        _state.update { it.copy(gitBusy = op, gitNotice = null) }
        viewModelScope.launch {
            var outcome: CommandOutcome? = null
            for (attempt in 0 until 2) {
                if (gate.ensure(StepUpScopes.GIT_WRITE).isFailure) break
                val out = Commands.run { id -> api.gitWrite(op, body(id, repo)) }
                if (out is CommandOutcome.Rejected && out.error.code == ErrorCodes.STEP_UP_REQUIRED) {
                    gate.invalidate(StepUpScopes.GIT_WRITE)
                    continue
                }
                outcome = out
                break
            }
            when (val out = outcome) {
                null -> _state.update { it.copy(gitBusy = null, gitNotice = GitNotice(op, ok = false, text = null)) }
                is CommandOutcome.Succeeded -> {
                    val res = runCatching { api.gitWriteResult(out.response) }.getOrNull()
                    _state.update {
                        it.copy(
                            gitBusy = null,
                            git = res?.let { r -> Loadable.Ready(r.status) } ?: it.git,
                            gitNotice = GitNotice(op, ok = true, text = res?.output?.takeIf { o -> o.isNotBlank() }),
                        )
                    }
                    after()
                    if (op in HISTORY_OPS || res == null) loadGit()
                }
                is CommandOutcome.Rejected -> {
                    _state.update { it.copy(gitBusy = null, gitNotice = GitNotice(op, ok = false, text = out.error.body.message)) }
                    loadGit()
                }
                is CommandOutcome.NotSent -> {
                    _state.update { it.copy(gitBusy = null, gitNotice = GitNotice(op, ok = false, text = ConnectionManager.describe(out.cause))) }
                }
                is CommandOutcome.Unknown -> {
                    _state.update { it.copy(gitBusy = null, gitNotice = GitNotice(op, ok = false, text = ConnectionManager.describe(out.cause))) }
                    loadGit()
                }
                is CommandOutcome.InProgress -> {
                    _state.update { it.copy(gitBusy = null, gitNotice = GitNotice(op, ok = false, text = null)) }
                    loadGit()
                }
            }
        }
    }

    /** The diff / file-at-commit request on screen, so "retry" repeats it. */
    private var lastGitDetail: (() -> Unit)? = null

    fun gitDiff(path: String, staged: Boolean = false) {
        lastGitDetail = { gitDiff(path, staged) }
        val api = api() ?: return
        val repo = _state.value.repo ?: return
        _state.update { it.copy(gitDiff = path to Loadable.Loading) }
        viewModelScope.launch {
            val d = loadable { api.gitDiff(repo, path, staged).diff }
            _state.update { it.copy(gitDiff = path to d) }
        }
    }

    fun gitShow(rev: String, path: String) {
        lastGitDetail = { gitShow(rev, path) }
        val api = api() ?: return
        val repo = _state.value.repo ?: return
        _state.update { it.copy(gitShow = "$rev:$path" to Loadable.Loading) }
        viewModelScope.launch {
            val r = loadable { api.gitShow(repo, rev, path).text ?: GIT_SHOW_BINARY }
            _state.update { it.copy(gitShow = "$rev:$path" to r) }
        }
    }

    /** Loads the diff / file on screen again (the error state's retry; it used to close the page). */
    fun retryGitDetail() {
        lastGitDetail?.invoke() ?: closeGitDetail()
    }

    fun closeGitDetail() {
        lastGitDetail = null
        _state.update { it.copy(gitDiff = null, gitShow = null) }
    }
}
