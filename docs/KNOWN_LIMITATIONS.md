# Known limitations

These are real constraints of the current implementation or of upstream software — not a
TODO list. Each item says what the user experiences.

## Agent control

1. **Sessions you started yourself are controllable only with the opt-in takeover hooks.**
   Without them there is no supported interface for another program to send input, answer a
   permission prompt or interrupt an existing session, and the phone shows them read-only.
   With a hook on (Settings → Remote, docs/AGENT_COMPATIBILITY.md §6b) only sessions started
   *after* turning it on are controllable; already running VS Code panels, Codex Desktop
   windows and terminals stay observe-only. Codex Desktop takeover is Windows-only. Terminal
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
4. **Codex managed sessions** were verified only up to the model call (isolated home without
   credentials). Approvals/interrupt for Codex are implemented against the published app-server
   schema but not yet exercised live. The installed Codex CLI version determines behaviour;
   very old/new CLIs may differ.
5. **A managed session cannot be shown on the desktop's Claude Code/Codex UI.** It is visible
   in SwitchTether (phone) and its transcript appears in the normal history afterwards.
6. Continuing a Codex thread that might be open elsewhere is refused by design (single writer).
7. Third-party extension/plugin private UI state (e.g. VS Code editor tabs, extension webviews)
   is not available through any interface and is not rendered.

## Network and background

8. No public relay: the phone must reach the desktop over LAN, Tailscale (your own Tailnet; it
   still uses Tailscale's coordination servers) or a manually configured address.
8a. No mDNS discovery yet: the QR code carries the candidate addresses (LAN, Tailscale). If
    the computer's LAN address changes, the phone tries all stored candidates; otherwise
    re-pair or enter the address manually. Tailscale addresses are stable.
9. When the desktop sleeps the phone shows "offline"; SwitchTether does not change sleep
   settings. Wake-on-LAN is not implemented.
10. Android background delivery is best effort: without FCM, notifications depend on the
    optional foreground connection service and vendor battery policies; Doze can delay them.
    Missed events are fetched after reconnecting (event replay) or by refreshing (resync).

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
    sensitive content and keys are hardware-bound.
19. The file browser deny-list reduces accidental exposure but is not a sandbox (the shell can
    read anything the user can).
20. On Linux without a Secret Service and on headless systems the desktop key falls back to an
    owner-only file (shown in Settings → Remote → Private key storage).

## Testing gaps (see docs/TESTING.md)

21. macOS and Linux desktop builds are configured in CI but were not built on the development
    machine (Windows).
22. Android instrumented tests on real devices from several vendors (battery optimisation,
    biometric variants) have not been run.
23. Upstream CC Switch tests: 2 locale-sensitive chart-formatting tests and one 10 s integration
    timeout fail on the development machine's Node 24/ICU and also fail on the pristine upstream
    baseline; two upstream clippy warnings exist in upstream test files on Windows.
