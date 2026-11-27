# Known limitations

These are real constraints of the current implementation or of upstream software — not a
TODO list. Each item says what the user experiences.

## Agent control

1. **Sessions you started yourself are controllable only with the takeover hooks.**
   Without them there is no supported interface for another program to send input, answer a
   permission prompt or interrupt an existing session, and the phone shows them read-only.
   With a hook on (Settings → Remote, docs/AGENT_COMPATIBILITY.md §6b) only sessions started
   *after* turning it on are controllable; already running VS Code panels, Codex Desktop
   windows and terminals stay observe-only. Codex Desktop takeover is Windows-only. DeepSeek
   Harness: the bridge plugin covers `dsh web` (restart it after turning the switch on), not the
   DeepSeek Harness desktop app; managed dsh sessions use the `acp` profile. Terminal
   sessions (`st-claude` / `st-codex`) answer prompts in the mirrored terminal view, not with
   approval cards. Turns sent from the phone into a VS Code panel run in that panel's process,
   but whether the extension's chat view redraws them is up to the extension (UNTESTED).
   Evidence: docs/research/CLAUDE_CODE_RUNTIME.md, docs/research/CODEX_RUNTIME.md.
2. **Live view of external sessions is near-real-time, not streaming.** Transcripts are tailed
   (~0.8 s) and contain completed records only. A pending permission dialog in a terminal
   session shows as `status: waiting` (from `claude agents --json`); for VS Code panel
   sessions this signal is unverified. Codex rollouts do not record pending approvals at all.
3. **Transcript formats are internal to Claude Code and Codex** and may change in any release;
   the parsers are defensive (unknown records are ignored) but rendering may degrade until
   SwitchTether is updated.
4. **Codex managed sessions** are verified live with the codex 0.171 bundled with Codex Desktop
   (messages, approvals, interrupt, permission modes, `/compact`, `/review`, resume). `auto`
   (auto review) and `readOnly` modes, `/goal` and skills are covered by unit tests only. Very
   old Codex CLIs may ignore newer request fields (collaboration mode, approvals reviewer).
4a. **`bypassPermissions` / Codex full access** runs everything without asking; choosing it from
   the phone needs the terminal fingerprint step-up. In a VS Code panel taken over with the hook,
   Claude Code accepts it mid-session only if the extension started the process with "allow
   dangerously skip permissions". When SwitchTether runs as root (Linux), managed Claude Code
   sessions can use it only from the start.
5. **A managed session cannot be shown on the desktop's Claude Code/Codex UI.** It is visible
   in SwitchTether (phone) and its transcript appears in the normal history afterwards.
6. Codex allows one writer per thread: while a thread is open elsewhere (Codex Desktop, another
   app-server) Codex itself refuses to resume it and the phone shows the 409. Once it is closed
   there, SwitchTether can continue it (docs/AGENT_COMPATIBILITY.md §6a).
7. Third-party extension/plugin private UI state (e.g. VS Code editor tabs, extension webviews)
   is not available through any interface and is not rendered.

## Network and background

8. No hosted relay: away from the LAN the phone reaches the desktop over Tailscale (your own
   Tailnet; it still uses Tailscale's coordination servers), a manually entered address, or an
   optional **self-hosted** relay (docs/RELAY.md, off by default) that only forwards the
   end-to-end TLS stream.
8a. LAN discovery (mDNS `_switchtether._tcp`) needs multicast on the network. Where it is
    blocked (guest Wi-Fi, client isolation, some routers, the Android emulator) "附近的电脑" stays
    empty: pair by QR code or by typing the address plus the pairing code. A changed address
    heals on its own as long as any path works once, because the phone replaces its stored
    addresses with the desktop's current list (`/v1/status` → `hosts`) on every connection.
8b. Pairing without the QR code (nearby computer or typed address + 6-digit code) cannot check
    the desktop's certificate in advance; the verification code compared on both screens after
    pairing is the protection against a machine-in-the-middle, and the app says so.
9. When the desktop sleeps the phone shows "offline"; SwitchTether does not change sleep
   settings. "唤醒并重试" sends a Wake-on-LAN magic packet, which only helps while the phone is
   on the computer's LAN and the computer's adapter / firmware already allow Wake-on-LAN
   (SwitchTether never changes those). A sleeping computer cannot be woken from outside its
   LAN (Tailscale, relay). Not yet verified against a real sleeping computer. While offline the
   phone shows an offline snapshot ("离线快照 · 10:42", ANDROID_UI.md §33): the session list
   survives an app restart, conversation contents only while the app process lives (opened since
   the last start, in memory); everything that needs the computer is disabled.
10. Android background delivery is best effort: there is no push channel (FCM or UnifiedPush
    would need a third-party app or a hosted backend, which the phone app does not use).
    Notifications depend on the optional foreground connection service and vendor battery
    policies; Doze can delay them. Missed events are fetched after reconnecting (event replay) or
    by refreshing (resync).
    Conversation notifications follow the same delivery. An inline reply starts the connection
    for up to 9 s and is sent once; a send the computer wants step-up for is not sent (the
    notification asks to open the app). Not yet verified on a device.
    About → 诊断记录 keeps the last 50 reasons the app process ended (and whether the connection
    service ran) to study vendor kills; it observes, it does not prevent them.
10f. Session content search reads the last 64 MiB of each transcript within 3 s, newest sessions
    first; on very large histories older sessions may be reported as not searched (`partial`).
    It searches message text only, not tool output, and sessions of other CC Switch providers
    (history only) are not searched.
10g. Large uploads and downloads run as Android 14+ user-initiated data transfer jobs, but a
    transfer cut by the app process ending (killed, crashed, updated) is not resumed: it is reported
    once and has to be started again; the partial upload is discarded on the computer at the next
    connection. A transfer started while the app was not visible (rare) runs without the job and its
    notification. Not yet verified on a device.

## Port forwarding

10a. A forward reaches only ports that listen on the computer's **loopback** (or on all
     interfaces). A server bound to one LAN address only is not reachable through it.
10b. While a forward runs, other apps on the phone can connect to `localhost:<port>` too.
10c. Forwards on the phone end when the app process ends. The lease stays valid on the computer
     until it expires, and the forwarding screen offers to resume it without a new confirmation.
10d. Plain TCP only (HTTP, WebSocket, most dev tools). UDP (e.g. HTTP/3-only servers) is not
     forwarded. Through the relay, throughput and latency are those of the relay.
10e. The list of listening ports shows process names on Windows and macOS (macOS: `lsof`, the
     user's own processes only); on Linux it has no process names (any port can still be entered
     by hand).

## Desktop

11. **Quitting SwitchTether ends the remote terminals and managed agent processes it owns.**
    There is no separate daemon in v1; PTYs cannot be re-attached after the process exits.
12. On macOS, ⌘Q/logout cannot be intercepted for the confirmation dialog (cleanup still runs).
13. Running SwitchTether and upstream CC Switch at the same time is not coordinated: both manage
    the same Claude Code/Codex/Gemini config files. SwitchTether warns at startup.
14. OAuth logins managed by CC Switch (Codex/Claude/Antigravity account switching) are not copied
    during import; sign in again inside SwitchTether.
15. The upstream What's-New dialogs are keyed to CC Switch versions and stay silent in
    SwitchTether (independent version numbers).
16. The committed updater public key is a placeholder: in-app updates fail closed until a
    maintainer configures a signing key (docs/BUILD.md).

## Security

17. A remote shell has the full privileges of the desktop user; SwitchTether cannot make a shell
    "safe" and does not filter commands.
18. Revocation cannot erase data on a phone that is offline or lost; the app avoids persisting
    sensitive content (no transcripts on disk; the offline session list is Keystore-sealed and
    crypto-erased on unpair / revoke) and keys are hardware-bound.
19. The file browser deny-list reduces accidental exposure but is not a sandbox (the shell can
    read anything the user can).
20. On Linux without a Secret Service and on headless systems the desktop key falls back to an
    owner-only file (shown in Settings → Remote → Private key storage).

## Testing gaps (see docs/TESTING.md)

21. macOS and Linux desktop builds are configured in CI but were not built on the development
    machine (Windows). The macOS-specific Remote code (login-shell PATH, `lsof` port list, login
    shell terminals, App Nap activity, ad-hoc signed dmg) is compiled and unit-tested by CI only;
    it has not run on a real Mac.
22a. Codex Desktop takeover (§6b of AGENT_COMPATIBILITY) is Windows-only; on macOS it reports
    "only available on Windows". The in-app desktop update (FluentSetup) is Windows-only as well:
    macOS users download the new dmg from the release page.
22. Android instrumented tests on real devices from several vendors (battery optimisation,
    biometric variants) have not been run.
23. Upstream CC Switch tests: 2 locale-sensitive chart-formatting tests and one 10 s integration
    timeout fail on the development machine's Node 24/ICU and also fail on the pristine upstream
    baseline; two upstream clippy warnings exist in upstream test files on Windows.
