# SwitchTether security model

SwitchTether gives a phone real control over a computer (agents, shells, files, CC Switch
configuration). This document describes what protects that channel, what it does **not**
protect against, and how each requirement is tested. Report vulnerabilities privately via
GitHub Security Advisories on the repository; do not open public issues.

## 1. Assets and threat model

| Asset | Where it lives |
|---|---|
| Desktop TLS identity private key | OS keychain (Windows Credential Manager / macOS Keychain / Secret Service); owner-only file fallback when no keychain exists. Never exported. |
| Phone identity + step-up keys | Android Keystore (non-exportable). A fresh identity per pairing. |
| Paired device records, audit log, command log | `~/.switchtether/remote/remote.db` |
| Provider API keys / OAuth tokens | CC Switch database and agent config files — **never** sent to the phone |
| User files, shells, agent sessions | the desktop |

Attackers considered: other devices on the LAN/Tailnet, a network attacker (MITM), a
stolen or lost phone, someone who briefly sees the pairing QR code, a malicious repository
opened by an agent, buggy clients replaying requests.

Out of scope: an attacker who already runs code as the desktop user (they can read
everything SwitchTether can), a compromised Android OS, physical access to an unlocked
desktop.

## 2. Transport

- **TLS 1.3 only** (rustls, `ring` provider). TLS 1.2 is disabled on both sides; session
  tickets are disabled so every connection re-proves the client key.
- **Server authentication by pinning.** The desktop presents a self-signed ECDSA P-256
  certificate. Its SHA-256 fingerprint is delivered out of band in the pairing QR code; the
  phone accepts exactly that certificate (constant-time comparison). Hostname verification is
  intentionally not used: hosts are IPs/MagicDNS names that change, and the pin is a stronger
  anchor than a hostname.
- **Mutual TLS on every connection.** The phone presents a self-signed certificate whose key
  lives in Android Keystore. rustls verifies the CertificateVerify signature (proof of key
  possession); the API then authorizes by SHA-256 of that certificate. Clients without a
  certificate never get an HTTP response (`e2e: client_without_certificate_is_rejected`).
- **Network exposure.** The listener binds all interfaces so Wi-Fi/Tailscale changes need
  no rebind, but the accept loop drops peers whose source address is not allowed *before* TLS:
  default `lan` = RFC1918, link-local, loopback, CGNAT/Tailscale, IPv6 ULA; `tailscale_only`;
  `localhost_only`; `any` must be selected explicitly and shows a warning. Being on the LAN or
  Tailnet never grants access by itself.
- The Remote API is a separate listener from CC Switch's model proxy (`127.0.0.1:15721`).

## 3. Pairing

1. The desktop user clicks "Show QR code" (remote access must already be enabled — it is off
   by default).
2. The QR contains host candidates, port, server fingerprint, a pairing id and a 256-bit
   one-time secret. TTL 5 minutes, single use, at most 4 active offers.
3. The phone connects (pinned TLS + its client certificate) and sends the secret. The device
   identity is bound to the **TLS client certificate of that connection**; the secret only
   proves the phone saw the desktop screen.
4. Protection: constant-time comparison; 5 wrong secrets destroy the offer; 20 attempts per
   minute per source IP; concurrent use of one secret succeeds at most once
   (`e2e: concurrent_pairing_race_only_one_wins`).
5. Both screens show a 6-digit verification code derived from both fingerprints for visual
   comparison.

MITM: an attacker cannot impersonate the desktop (pin) and cannot replay the secret against
the real desktop without seeing the screen; even then the attacker would only pair *its own*
certificate, visible in the desktop's device list and revocable.

## 4. Authorization

- Every endpoint except `GET /v1/hello` and `POST /v1/pair` requires an active paired device.
- The API is an explicit whitelist (`src-tauri/crates/switchtether-core/src/api/*`); Tauri IPC
  commands are not exposed.
- **Step-up (biometric) scopes** — `terminal`, `files_write`, `config_write`, `port_forward`, `git_write` — require a grant
  obtained by signing a server challenge with a second Keystore key that is usable only after
  BiometricPrompt / device-credential authentication. The server verifies the ECDSA signature
  over `switchtether-stepup-v1\n{deviceId}\n{challengeId}\n{nonce}\n{scope}`; challenges are
  single use (60 s), grants last 15 min, are per device and scope, and are dropped on
  revocation. Terminal input frames re-check the grant. The server never trusts a client flag.
- Per-device rate limit (30 req/s, burst 120); JSON bodies ≤ 1 MiB; upload chunks ≤ 8 MiB;
  WebSocket frames bounded; at most 64 concurrent connections.

## 5. Revocation

Revoking a device (desktop UI, or the phone unpairing itself):

1. adds its certificate fingerprint to the TLS deny-list (new handshakes fail);
2. removes it from the active registry (in-flight requests get `403 device_revoked`);
3. cancels every connection of that certificate — WebSockets close with code 4403
   (`e2e: revocation_closes_event_stream_immediately`);
4. deletes its step-up key, grants and pending command records, kills terminals it opened;
5. writes an audit record. The deny-list survives restarts (`e2e: server_restart_keeps_devices`).

Data already stored on a lost phone cannot be wiped remotely. The app stores no transcripts or
credentials persistently: the offline snapshot keeps conversations in memory only and persists
just the session list metadata (titles, folders, state), sealed with a non-exportable Keystore key
that is destroyed on unpair / revoke (docs/ANDROID_UI.md §33). Previewed and downloaded files are
the exception the user chose (ANDROID_UI.md §13.13). Keys are hardware-bound, so a revoked phone
holds nothing that grants further access.

## 6. Secrets never leave the desktop

- Provider views replace secret values with `{configured, hint: "…last4"}` (structural
  redaction using CC Switch's own sensitive-key classifier plus SwitchTether's, then a second
  JSON-wide pass in the API layer). Credential updates are write-only and limited to existing
  secret fields of that provider; the value is never logged or audited.
- MCP server env/headers, settings (whitelisted subset) and proxy status are redacted.
- Error messages from backend services are passed through a text redactor; internal errors
  return a generic message and are logged locally only.
- The file browser **denies** credential stores and key material (`~/.ssh`, `~/.gnupg`,
  `~/.aws`, `~/.kube`, `~/.docker`, `~/.config/gh`, `~/.claude/.credentials.json`,
  `~/.codex/auth.json`, `~/.switchtether`, `~/.cc-switch`, keychains, browser profiles,
  `/etc/shadow`, `id_*` private keys…) after canonicalization (symlinks cannot bypass it), and
  marks `.env`, `*.pem`, `*.key`… as **sensitive** (explicit confirmation to open, cannot be
  downloaded, every read audited).

## 7. Remote shell

A terminal opened from the phone runs with the full privileges of the logged-in desktop user.
SwitchTether does **not** pretend to filter dangerous commands (string filtering cannot
restrict a shell). Protections are: biometric step-up, per-device ownership, audit of
open/close and input volume (never input content), kill on revocation and on quit.

### 7a0. Git writes

Stage, discard, commit, push, pull, fetch and branch switches from the phone need step-up `git_write` and run the
user's own `git` in the repository they name, with the read path's hardening (no fsmonitor, no external diff /
textconv) and no way to prompt on the desktop (terminal prompts, Git Credential Manager dialogs and askpass are off).
They use the credentials and hooks the user already configured, exactly as a terminal would; SwitchTether never
stores or returns them. Paths are repository-relative and literal; discarding an untracked file moves it to the OS
trash (the same deny-list as `fs/delete` applies) and never deletes. git's output is redacted before it is returned
and is not stored beyond the command record; the audit log keeps only the operation, repository, path count or
branch and the outcome.

### 7a. Port forwarding

A forward (PROTOCOL.md §7.11) lets the phone reach one TCP port on the desktop's loopback.

- Starting one needs step-up `port_forward`; the lease is bound to the device, the port and an
  expiry (2 h default, 12 h max), kept in memory only, and ends on revocation, on unpair, when
  stopped on either side (Settings → Remote → Port forwarding lists every lease with a stop
  button) or when the desktop app exits.
- The desktop connects only to `127.0.0.1` / `::1` at the leased port; there is no host
  parameter, so a lease cannot be turned into access to other machines on the desktop's
  network. The Remote server's own port is refused.
- Opening, renewing, stopping and each tunnel (connection and byte counts, never content)
  are written to the audit log.
- Per tunnel: at most 128 streams, 256 KiB unacknowledged data per stream and direction,
  32 KiB frames; protocol violations close the tunnel.
- **On the phone the forward listens on `127.0.0.1` only, but every app on the phone can
  connect to it while it runs** (Android has no per-app loopback isolation). The forwarding
  screen says so; the ongoing notification names the forwarded ports and has a stop action.
  Compared with the remote shell this exposes less (one port, not a shell), which is why it is
  a separate, narrower scope.
- **DeepSeek Harness Web** (PROTOCOL.md §7.14) is a forward to the `dsh web` port with the same
  step-up and lease. Starting / stopping `dsh web` needs the same `port_forward` grant; only an
  instance SwitchTether started can be stopped. The `open` answer carries dsh's per-process launch
  token to that confirmed device so its WebView can sign in once (dsh prints the same link on the
  user's own terminal); it is not part of the status, events, the audit log or the desktop log, and
  the phone does not store it (after dsh's redirect the page address has no token; "copy address"
  strips it). The token grants the dsh web UI, which runs agent tools on the desktop: comparable to
  the remote shell, which the same paired device can open with the same kind of confirmation. Other
  apps on the phone can reach the forwarded port while it runs (see above) but get dsh's `401`: the
  sign-in cookie is `HttpOnly`, bound to the `localhost:<port>` authority and kept in this app's
  WebView cookie store. The phone's WebView is limited to
  `http://localhost:<forward port>` (other links go to the browser), with no file / content access,
  no extra windows and camera / microphone / location requests denied; cleartext is allowed only to
  `localhost` (network_security_config).

### 7b. Push wake

Push wake (PROTOCOL.md §7.12) is opt-in on the phone and uses the user's own UnifiedPush
distributor and push server.

- **What the push server learns:** that this desktop wanted to wake this phone, and when
  (timing, frequency, the desktop's IP address). Every message is RFC 8291-encrypted to keys only
  the phone holds and its plaintext is the constant `{"v":1}`: no session id, title, text, count
  or agent name. Push servers see the same message length every time.
- **What a forged message can do:** nothing but make the phone connect early. The phone drops
  any message that does not decrypt with its receiver keys and never acts on message content;
  everything it shows afterwards comes over the pinned mTLS connection.
- The endpoint is a capability URL: it is stored in `remote.db`, never returned to anyone, and
  only its host is written to the audit log. It is removed when the device is revoked or the
  push server answers 404 / 410.
- The desktop posts only to the endpoint the paired phone registered (http/https, no
  credentials in the URL, ≤ 1000 bytes), with a fixed body, and nothing it reads back is used
  except the status code. A paired phone could point it at a LAN address; that address then
  receives an encrypted ~130-byte POST at most every 10 s — no amplification and no data.
- The receiver private key lives in the app's private storage (not the Keystore): it only
  decrypts wake messages, which carry no information.
- The VAPID key (RFC 8292) only authenticates the desktop to push servers that ask for it; it
  protects no data and sits in `remote.db`.

The Android receiver for UnifiedPush intents is exported (the distributor is another app). It
acts only on intents with the random connection token of the current registration.

## 8. Replay and duplicate operations

TLS prevents network replay. Application-level duplicates (client retries) are handled by
mandatory `commandId`s: a completed command returns its stored result instead of executing
again; a command still running or with unknown outcome after a crash returns 409 and the
client must query `GET /v1/commands/{id}` and let the user decide
(`e2e: idempotent_commands_and_unknown_outcome`). The Android client disables OkHttp's silent
retries.

## 9. Agent safety boundaries

- SwitchTether never edits Claude Code / Codex configuration, hooks, plugins, MCP settings or
  launch commands to gain control; externally started sessions are observe-only — except
  through the opt-in takeover hooks the user turns on (AGENT_COMPATIBILITY §6b), which back up
  first, never replace a value the user set and are removed exactly.
- Attach broker (takeover): listens on 127.0.0.1 only, random port; a 256-bit random token in
  `~/.switchtether/remote/attach.json` (owner-only file) must be presented in the first frame
  (constant-time compare, 5 s deadline, 32 MiB frame limit). It never kills or restarts the
  user's processes. Without the broker the shim degrades to a plain pass-through.
- Terminal-mirrored sessions: messages from the phone that start with `!` (Claude Code's
  shell escape) are refused; shell access stays behind the step-up gated terminal view.
- Managed sessions are explicit, labelled, and run with the user's normal settings.
- Codex threads open elsewhere are never resumed (single-writer locks are respected and never
  probed).
- `git` is invoked with `core.fsmonitor=false`, no external diff, no textconv, no pager and no
  credential prompts, so read-only Git queries cannot run repository-provided programs.

## 10. Logging and audit

Audit records (90-day retention): pairing (incl. failures), revocation, step-up grants and
denials, provider switches, credential updates (field name only), MCP/skill/prompt toggles,
session create/message (length only)/approve/interrupt/stop, terminal create/kill/session,
file write/rename/delete/upload/download and sensitive reads, push subscription changes
(`push.register` with the endpoint host only, `push.unregister`, `push.expired`). Targets/details
pass through the redactor. Session content searches are not logged (they are reads).

## 11. Security test inventory

| Requirement | Test |
|---|---|
| Unpaired client | `e2e: unpaired_clients_only_reach_pairing` |
| Wrong certificate / wrong pin | `e2e: wrong_server_pin_is_rejected_by_client`, `client_without_certificate_is_rejected` |
| Revoked certificate | `e2e: pairing_then_revocation`, `registry::tests::register_lookup_revoke` |
| Replay / duplicates | `e2e: idempotent_commands_and_unknown_outcome`, `stepup::tests::full_flow` (challenge replay) |
| Privilege escalation (no step-up) | `e2e: terminal_requires_step_up_and_streams_output`, `file_write_conflict_over_api`, `port_forward_tunnel_carries_http_over_mtls`, `git_writes_need_step_up_and_replay_by_command_id` |
| Git path escape / option injection | `git::tests::rejects_paths_that_leave_the_repository`, e2e `../x` refused |
| Port forward isolation (other device, stop, window abuse, refused port) | `e2e: port_forward_tunnel_carries_http_over_mtls`, `forward::tests::*`; Android `PortForwardTest` |
| Concurrent operations | `e2e: concurrent_pairing_race_only_one_wins`; live managed-session second answer → 409 |
| Path traversal / symlinks / denied paths | `files::policy::tests::*`, `files::tests::list_hides_denied_and_hidden`, e2e traversal |
| Oversized messages | `e2e: oversized_body_is_rejected` |
| Unexpected disconnect / crash recovery | `registry::tests::pending_commands_become_unknown_after_restart`, event replay/resync tests |
| Secret redaction | `redact::tests::*`, `switchtether::bridge::tests::secret_fields_are_pointers_with_hints` |

## 12. Updates

SwitchTether uses its own updater endpoint and key. The committed updater public key is a
placeholder that fails signature verification, so no update can be installed until a
maintainer configures a real key (docs/BUILD.md). The upstream CC Switch key and
`dl.ccswitch.io` are not trusted.
