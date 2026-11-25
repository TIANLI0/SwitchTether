package io.github.tianli0.switchtether.remote.protocol

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------------------------
// §7.14 DeepSeek Harness Web (feature `dsh_web`)
// ---------------------------------------------------------------------------------------------

/** States of [DshWebStatus.state]. */
object DshWebStates {
    const val NOT_INSTALLED = "not_installed"
    const val STOPPED = "stopped"
    const val STARTING = "starting"
    const val RUNNING = "running"
}

/**
 * `GET /v1/dsh-web` (fixture `dsh_web_status.json`): whether `dsh web` runs on the computer.
 * [owned] = SwitchTether started it (and may stop it); false for one the user started.
 */
@Serializable
data class DshWebStatus(
    val state: String = DshWebStates.STOPPED,
    val port: Int? = null,
    val owned: Boolean = false,
    val startedAtMs: Long? = null,
    /** Why the last start failed (state `stopped`). */
    val error: String? = null,
) {
    val running: Boolean get() = state == DshWebStates.RUNNING && port != null
}

@Serializable
data class DshWebOpenBody(val start: Boolean = true, val ttlMinutes: Int? = null)

/**
 * `POST /v1/dsh-web/open` (fixture `dsh_web_open.json`). [forward] (a normal §7.11 lease to the
 * `dsh web` port) only once it runs; [launchPath] (`/?token=…`, dsh's per-process sign-in link) only
 * for an instance SwitchTether started. Never store or log [launchPath].
 */
@Serializable
data class DshWebOpen(
    val status: DshWebStatus = DshWebStatus(),
    val forward: ForwardView? = null,
    val launchPath: String? = null,
)

@Serializable
data class DshWebStop(val stopped: Boolean = false, val status: DshWebStatus = DshWebStatus())
