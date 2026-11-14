package io.github.tianli0.switchtether.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.tianli0.switchtether.BuildConfig
import io.github.tianli0.switchtether.remote.update.GithubReleases
import io.github.tianli0.switchtether.remote.update.ReleaseInfo
import io.github.tianli0.switchtether.remote.update.SemVer
import io.github.tianli0.switchtether.remote.update.Sha256Sums
import io.github.tianli0.switchtether.remote.update.chooseUpdate
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the update UI shows. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: String?) : UpdateState
    /** The repository has no published release (yet), or it is not reachable as public. */
    data object NoReleases : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val done: Long, val total: Long) : UpdateState
    data class Verifying(val release: ReleaseInfo) : UpdateState
    /** Checks passed; installation needs the "install unknown apps" permission first. */
    data class NeedsInstallPermission(val release: ReleaseInfo, val apk: File) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val apk: File) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Failed(val reason: Reason, val detail: String? = null, val release: ReleaseInfo? = null) : UpdateState

    enum class Reason { NETWORK, NO_APK_ASSET, NO_CHECKSUM, CHECKSUM_MISMATCH, SIGNATURE_MISMATCH, NOT_AN_APK, INSTALL_FAILED }
}

/**
 * Update check + self-update from the public GitHub releases (no auth, ETag-cached).
 *
 * A downloaded APK is installed only if BOTH hold: its SHA-256 equals the line for it in the
 * release's `SHA256SUMS.txt`, and its signing certificate(s) equal the installed app's. Anything
 * else is deleted and reported; it is never handed to the installer.
 */
class UpdateManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val github = GithubReleases(
        client,
        repo = BuildConfig.UPDATE_REPO,
        baseUrl = BuildConfig.UPDATE_API_BASE,
        userAgent = "SwitchTether-Android/${BuildConfig.VERSION_NAME}",
    )
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: SemVer = SemVer.parse(BuildConfig.VERSION_NAME) ?: SemVer(0, 0, 0)

    var includePrerelease: Boolean
        get() = prefs.getBoolean(KEY_BETA, false)
        set(v) {
            // The cached response belongs to the other endpoint (/latest vs /releases).
            prefs.edit().putBoolean(KEY_BETA, v).remove(KEY_ETAG).remove(KEY_BODY).apply()
        }

    val lastCheckMs: Long get() = prefs.getLong(KEY_LAST_CHECK, 0)

    /** Background checks run at most every [BACKGROUND_INTERVAL_MS]. */
    fun backgroundCheckDue(now: Long = System.currentTimeMillis()): Boolean = now - lastCheckMs >= BACKGROUND_INTERVAL_MS

    /** Version already announced by notification (each version is announced once). */
    var notifiedVersion: String?
        get() = prefs.getString(KEY_NOTIFIED, null)
        set(v) = prefs.edit().putString(KEY_NOTIFIED, v).apply()

    suspend fun check(): UpdateState {
        _state.value = UpdateState.Checking
        val result = withContext(Dispatchers.IO) {
            try {
                val beta = includePrerelease
                val etag = prefs.getString(KEY_ETAG, null)?.takeIf { prefs.contains(KEY_BODY) }
                val r = github.fetch(beta, etag)
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                val body = when (r.code) {
                    200 -> r.body.also { prefs.edit().putString(KEY_ETAG, r.etag).putString(KEY_BODY, it).apply() }
                    304 -> prefs.getString(KEY_BODY, null)
                    404 -> return@withContext UpdateState.NoReleases
                    else -> return@withContext UpdateState.Failed(UpdateState.Reason.NETWORK, "HTTP ${r.code}")
                } ?: return@withContext UpdateState.Failed(UpdateState.Reason.NETWORK, "empty response")
                val releases = ReleaseInfo.parseAll(body)
                if (releases.isEmpty()) return@withContext UpdateState.NoReleases
                chooseUpdate(releases, currentVersion, beta)?.let { UpdateState.Available(it) }
                    ?: UpdateState.UpToDate(releases.filter { beta || !it.prerelease }.maxByOrNull { it.version }?.version?.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(UpdateState.Reason.NETWORK, e.message ?: e.javaClass.simpleName)
            }
        }
        _state.value = result
        return result
    }

    /** Downloads, then verifies checksum and signature. Ends in ReadyToInstall / NeedsInstallPermission / Failed. */
    suspend fun download(release: ReleaseInfo) {
        val apkAsset = release.apk ?: return fail(UpdateState.Reason.NO_APK_ASSET, null, release)
        val sumsAsset = release.checksums ?: return fail(UpdateState.Reason.NO_CHECKSUM, null, release)
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val apk = File(dir, apkAsset.name)
        try {
            val expected = withContext(Dispatchers.IO) {
                val text = get(sumsAsset.url) { it.body.string() }
                Sha256Sums.parse(text)[apkAsset.name]
            } ?: return fail(UpdateState.Reason.NO_CHECKSUM, apkAsset.name, release)
            _state.value = UpdateState.Downloading(release, 0, apkAsset.size)
            withContext(Dispatchers.IO) {
                get(apkAsset.url) { resp ->
                    val total = resp.body.contentLength().takeIf { it > 0 } ?: apkAsset.size
                    resp.body.byteStream().use { input ->
                        apk.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastEmit = 0L
                            while (true) {
                                if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (done - lastEmit > 256 * 1024) {
                                    lastEmit = done
                                    _state.value = UpdateState.Downloading(release, done, total)
                                }
                            }
                        }
                    }
                }
            }
            _state.value = UpdateState.Verifying(release)
            val failure = withContext(Dispatchers.Default) { verify(apk, expected) }
            if (failure != null) {
                apk.delete()
                return fail(failure.first, failure.second, release)
            }
            _state.value = if (canInstall()) UpdateState.ReadyToInstall(release, apk) else UpdateState.NeedsInstallPermission(release, apk)
        } catch (e: CancellationException) {
            apk.delete()
            _state.value = UpdateState.Available(release)
            throw e
        } catch (e: Exception) {
            apk.delete()
            fail(UpdateState.Reason.NETWORK, e.message ?: e.javaClass.simpleName, release)
        }
    }

    /** @return null when both checks pass, else the reason (+ detail). */
    internal fun verify(apk: File, expectedSha256: String): Pair<UpdateState.Reason, String?>? {
        val actual = Sha256Sums.of(apk)
        if (!actual.equals(expectedSha256, ignoreCase = true)) return UpdateState.Reason.CHECKSUM_MISMATCH to "expected $expectedSha256, got $actual"
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return UpdateState.Reason.NOT_AN_APK to null
        if (archive.packageName != context.packageName) return UpdateState.Reason.SIGNATURE_MISMATCH to "package ${archive.packageName}"
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val installedCurrent = signerDigests(installed)
        val archiveCurrent = signerDigests(archive)
        // Same signer, or a legitimately rotated key whose lineage includes ours (what Android itself accepts).
        val ok = installedCurrent.isNotEmpty() &&
            (archiveCurrent == installedCurrent || signerDigests(archive, history = true).containsAll(installedCurrent))
        if (!ok) return UpdateState.Reason.SIGNATURE_MISMATCH to null
        return null
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Settings page where the user allows this app to install updates. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Re-evaluates the permission after the user returns from system settings. */
    fun refreshPermission() {
        val s = _state.value
        if (s is UpdateState.NeedsInstallPermission && canInstall()) _state.value = UpdateState.ReadyToInstall(s.release, s.apk)
    }

    /** Hands a verified APK to PackageInstaller (the system asks the user to confirm). */
    fun install(release: ReleaseInfo, apk: File) {
        if (!canInstall()) {
            _state.value = UpdateState.NeedsInstallPermission(release, apk)
            return
        }
        _state.value = UpdateState.Installing(release)
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pi.intentSender)
            }
        } catch (e: IOException) {
            fail(UpdateState.Reason.INSTALL_FAILED, e.message, release)
        } catch (e: SecurityException) {
            fail(UpdateState.Reason.INSTALL_FAILED, e.message, release)
        }
    }

    /** Called by [InstallResultReceiver] with the session's final status. */
    internal fun onInstallResult(status: Int, message: String?) {
        val s = _state.value
        val release = (s as? UpdateState.Installing)?.release
        if (status != PackageInstaller.STATUS_SUCCESS) {
            _state.value = UpdateState.Failed(UpdateState.Reason.INSTALL_FAILED, message ?: "status $status", release)
        }
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    private fun fail(reason: UpdateState.Reason, detail: String?, release: ReleaseInfo?) {
        _state.value = UpdateState.Failed(reason, detail, release)
    }

    private inline fun <T> get(url: String, read: (okhttp3.Response) -> T): T {
        client.newCall(Request.Builder().url(url).header("User-Agent", "SwitchTether-Android/${BuildConfig.VERSION_NAME}").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for ${Uri.parse(url).lastPathSegment}")
            return read(r)
        }
    }

    companion object {
        const val BACKGROUND_INTERVAL_MS = 12 * 60 * 60 * 1000L
        private const val KEY_BETA = "include_prerelease"
        private const val KEY_ETAG = "etag"
        private const val KEY_BODY = "body"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_NOTIFIED = "notified_version"

        /** SHA-256 of the current signing certificate(s); with [history], of the whole rotation lineage. */
        fun signerDigests(info: PackageInfo, history: Boolean = false): Set<String> {
            val si = info.signingInfo ?: return emptySet()
            val certs = when {
                si.hasMultipleSigners() -> si.apkContentsSigners
                history -> si.signingCertificateHistory
                else -> si.signingCertificateHistory?.takeLast(1)?.toTypedArray()
            }
            return certs.orEmpty().map { sig ->
                MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
    }
}
