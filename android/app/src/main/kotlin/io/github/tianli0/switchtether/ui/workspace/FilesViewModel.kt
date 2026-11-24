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
    val error: String? = null,
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
)

/**
 * Files & Git workspace. Reads are plain GETs; every write (save, create, rename, delete,
 * upload) first ensures a `files_write` step-up grant and carries a commandId. Saves send the
 * `baseSha256` of the version that was opened; a 409 conflict opens [ConflictState] instead of
 * overwriting anything.
 */
class FilesViewModel(
    private val conn: ConnectionController,
    private val gate: StepUpGate,
    private val resolverProvider: () -> ContentResolver,
    private val store: FileStore? = null,
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

    fun setSearchContent(v: Boolean) = _state.update { it.copy(searchContent = v) }

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
        write(f, c.mine, base = null, path = c.suggestedCopyPath, createOnly = true) { openPath(f.path) }
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

    fun mkdir(name: String) = pathOp(R.string.files_op_mkdir) { api, id -> api.fsMkdir(PathBody(id, join(currentDir() ?: return@pathOp null, name))) }

    fun newFile(name: String) {
        val dir = currentDir() ?: return
        val path = join(dir, name)
        write(FileContent(path = path), "", base = null, path = path, createOnly = true) { browse(dir) }
    }

    fun rename(entry: FsEntry, newName: String) = pathOp(R.string.files_op_rename) { api, id ->
        api.fsRename(RenameBody(id, entry.path, join(entry.path.substring(0, maxOf(entry.path.lastIndexOf('\\'), entry.path.lastIndexOf('/'))), newName)))
    }

    /** Moves to the desktop's trash/recycle bin (server side). */
    fun delete(entry: FsEntry) = pathOp(R.string.files_op_delete) { api, id -> api.fsDelete(PathBody(id, entry.path)) }

    private fun pathOp(label: Int, op: suspend (RemoteApi, String) -> CommandResponse?) {
        viewModelScope.launch {
            val out = writeOp(label) { api, id -> op(api, id) ?: throw IllegalStateException("no directory") }
            when (out) {
                is CommandOutcome.Succeeded -> _state.update { it.copy(message = UiText(R.string.files_msg_ok, listOf(label))) }
                is CommandOutcome.Rejected -> _state.update { it.copy(message = UiText(R.string.files_msg_rejected, listOf(label, "${out.error.code}: ${out.error.body.message}"))) }
                null -> Unit
                else -> _state.update { it.copy(message = UiText(R.string.files_msg_unknown, listOf(label))) }
            }
            currentDir()?.let(::browse)
        }
    }

    private fun join(dir: String, name: String): String {
        val sep = if (dir.contains('\\')) "\\" else "/"
        return if (dir.endsWith(sep)) dir + name else dir + sep + name
    }

    // ---- upload / download -------------------------------------------------------------------------

    /** SAF document → chunked upload into the current directory (progress + cancel). */
    fun upload(uri: Uri) {
        val dir = currentDir() ?: return
        uploadJob = viewModelScope.launch {
            val (name, size) = withContext(Dispatchers.IO) {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) (c.getString(0) ?: "upload.bin") to c.getLong(1) else null
                } ?: ("upload.bin" to -1L)
            }
            if (size < 0) {
                _state.update { it.copy(transfer = TransferState(name, 0, null, "unknown file size")) }
                return@launch
            }
            _state.update { it.copy(transfer = TransferState(name, 0, size)) }
            val begin = writeOp(R.string.files_op_upload) { api, id ->
                val v = api.beginUpload(BeginUploadBody(id, join(dir, name), size))
                CommandResponse(io.github.tianli0.switchtether.remote.protocol.ProtocolJson.encodeToJsonElement(io.github.tianli0.switchtether.remote.protocol.UploadView.serializer(), v), false)
            }
            val uploadId = (begin as? CommandOutcome.Succeeded)?.response?.body?.obj()?.str("uploadId")
            if (uploadId == null) {
                _state.update { it.copy(transfer = it.transfer?.copy(error = (begin as? CommandOutcome.Rejected)?.error?.body?.message ?: "upload refused")) }
                return@launch
            }
            val api = api() ?: return@launch
            try {
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
                            _state.update { it.copy(transfer = it.transfer?.copy(done = offset + read)) }
                        }
                    }
                }
                _state.update { it.copy(transfer = it.transfer?.copy(finished = true)) }
                browse(dir)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The job is already cancelled: without NonCancellable the request would never be sent and the
                // partial upload would stay on the computer.
                withContext(NonCancellable) { runCatching { api.cancelUpload(uploadId) } }
                throw e
            } catch (e: Exception) {
                runCatching { api.cancelUpload(uploadId) }
                _state.update { it.copy(transfer = it.transfer?.copy(error = (e as? ApiException)?.body?.message ?: ConnectionManager.describe(e))) }
            }
        }
    }

    fun cancelTransfer() {
        uploadJob?.cancel()
        _state.update { it.copy(transfer = it.transfer?.copy(error = "cancelled")) }
    }

    fun dismissTransfer() = _state.update { it.copy(transfer = null) }

    /**
     * Explicit download into the public Downloads collection (`Downloads/SwitchTether`, MediaStore: no storage
     * permission needed). Recorded in the download index so it can be opened and managed later; unlike a
     * preview it is never evicted automatically.
     */
    fun download(entry: FsEntry) {
        val store = store ?: return
        uploadJob = viewModelScope.launch {
            _state.update { it.copy(transfer = TransferState(entry.name, 0, entry.size.takeIf { s -> s > 0 })) }
            try {
                val record = store.download(entry.path, entry.name, entry.size.takeIf { it > 0 }, entry.modifiedMs) { n, total -> _state.update { it.copy(transfer = it.transfer?.copy(done = n, total = total)) } }
                _state.update { it.copy(transfer = it.transfer?.copy(finished = true, downloaded = record)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(transfer = it.transfer?.copy(error = "cancelled")) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(transfer = it.transfer?.copy(error = (e as? ApiException)?.body?.message ?: ConnectionManager.describe(e))) }
            }
        }
    }

    // ---- git ------------------------------------------------------------------------------------------

    fun setRepo(path: String) {
        _state.update { it.copy(repo = path, git = null, gitLog = null, gitDiff = null, gitShow = null) }
        loadGit()
    }

    fun loadGit() {
        val api = api() ?: return
        val repo = _state.value.repo ?: return
        _state.update { it.copy(git = Loadable.Loading) }
        viewModelScope.launch {
            val st = loadable { api.gitStatus(repo) }
            val log = loadable { api.gitLog(repo).commits }
            _state.update { it.copy(git = st, gitLog = log) }
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
            val r = loadable { api.gitShow(repo, rev, path).text ?: "(binary)" }
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
