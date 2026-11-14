<p align="center">
  <img src="src/assets/icons/switchtether-logo.svg" width="96" alt="SwitchTether logo" />
</p>

# SwitchTether

English | [简体中文](README_ZH.md) | [Upstream changelog](CHANGELOG.md)

SwitchTether is a native Android companion and remote-control system built on
[CC Switch](https://github.com/farion1231/cc-switch). It lets you monitor, manage and
interact with the AI coding agents (Claude Code, Codex) running on your own computer —
without changing how you start them — and turns your phone into a full CC Switch
management client.

> **Project status: pre-release (0.1.0).** Read [docs/DEVELOPMENT_STATUS.md](docs/DEVELOPMENT_STATUS.md)
> and [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) before relying on it.

## What it does

- **Secure pairing, no cloud.** Scan a QR code once. Phone and computer talk directly over
  your LAN, your own Tailscale tailnet or a manual address, with TLS 1.3, mutual certificate
  authentication and certificate pinning. Keys live in the Android Keystore and the desktop
  OS keychain. Any phone can be revoked instantly.
- **Agent sessions.** See live and past Claude Code / Codex sessions with a native renderer
  for messages, Markdown, code, thinking, tool calls, diffs, plans, questions and approvals.
- **Full control where it is legitimately possible.** Start a new SwitchTether-managed
  Claude Code or Codex session from the phone (using your normal settings, plugins, skills,
  MCP servers and hooks) and send messages, approve tool calls, answer questions and interrupt.
- **Honest compatibility.** Sessions you started yourself in a terminal, VS Code or Codex
  Desktop are shown read-only: the upstream tools offer no supported way for another program
  to control a running session. See [docs/AGENT_COMPATIBILITY.md](docs/AGENT_COMPATIBILITY.md).
- **Remote terminals.** Open PowerShell, CMD, bash or zsh on the computer from the phone
  (biometric confirmation required). Shells keep running when the phone disconnects.
- **Files and Git.** Browse, search, view and edit files with conflict detection (never
  silently overwrites what your IDE saved), upload/download, Git status, diff and history.
- **CC Switch management.** Providers (switch, test, write-only key updates), usage and
  costs, subscription quota, MCP servers, skills, prompts, proxy status.

## Compatibility at a glance

| Client | Observe (history + live) | Control a session it started | Control via a new managed session |
|---|---|---|---|
| Claude Code CLI | Yes | Blocked upstream | Yes (verified) |
| Claude Code for VS Code | Yes | Blocked upstream | Yes (verified) |
| Codex CLI | Yes (rollouts) | Blocked upstream | Implemented, partially verified |
| Codex Desktop | Yes (rollouts) | Blocked upstream | Implemented, partially verified |

## Installation

### Desktop (Windows, macOS, Linux)

Download the installer from the [Releases](https://github.com/TIANLI0/SwitchTether/releases)
page or build it yourself ([docs/BUILD.md](docs/BUILD.md)). On first launch SwitchTether
copies existing CC Switch data from `~/.cc-switch` into its own `~/.switchtether` directory;
your CC Switch installation is never modified. Use one of the two apps to manage providers
— they write the same agent configuration files.

### Android (10+)

Install the APK from the Releases page (or `android/app/build/outputs/apk/`). No Google Play
services are required.

## Pairing and use

1. Desktop: **Settings → Remote (phone)** → enable remote access → choose the allowed
   networks (LAN + Tailscale by default) → **Show QR code**.
2. Phone: open SwitchTether → **Pair a computer** → scan the code (or paste the link).
   Compare the 6-digit verification code on both screens.
3. Browse sessions, start a managed session, open a terminal or manage providers. The
   desktop's **Paired phones** list shows every device; **Revoke** disconnects it immediately.

Quitting SwitchTether ends the remote terminals and managed sessions it owns (you are asked
first); closing the window keeps it in the tray. Agents you started yourself are never touched.

## Security

See [docs/SECURITY.md](docs/SECURITY.md) for the threat model, pairing, step-up
authentication, revocation, redaction and the security test inventory. A remote shell has
your full user privileges — protect your phone accordingly.

## Documentation

| Document | Contents |
|---|---|
| [ARCHITECTURE](docs/ARCHITECTURE.md) | components, data flow, decisions |
| [PROTOCOL](docs/PROTOCOL.md) | Remote API v1, events, agent event schema |
| [AGENT_COMPATIBILITY](docs/AGENT_COMPATIBILITY.md) | verified / partial / blocked capabilities with evidence |
| [ANDROID_UI](docs/ANDROID_UI.md) | Android app architecture and renderer coverage |
| [BUILD](docs/BUILD.md) / [TESTING](docs/TESTING.md) | building, signing, test suites |
| [UPSTREAM_SYNC](docs/UPSTREAM_SYNC.md) | how CC Switch releases are merged |
| [KNOWN_LIMITATIONS](docs/KNOWN_LIMITATIONS.md) | real constraints |
| [research/](docs/research) | evidence for the agent-runtime findings |

## Acknowledgements and license

SwitchTether is MIT-licensed ([LICENSE](LICENSE)). It is a fork of
[CC Switch](https://github.com/farion1231/cc-switch) by Jason Young (MIT); the original README
is kept as [README_UPSTREAM.md](README_UPSTREAM.md). The Android UI uses
[coui17](https://github.com/Costben/coui17) (Apache-2.0, with MPL-2.0 files), vendored at a
pinned commit under `android/third_party/coui17`. SwitchTether is not affiliated with
Anthropic, OpenAI, OPPO or the CC Switch authors.
