# Building SwitchTether

## Prerequisites

| Component | Version used during development |
|---|---|
| Rust | pinned by `rust-toolchain.toml` (1.95) with rustfmt + clippy |
| Node.js / pnpm | Node 22+ (24 tested), pnpm 10 |
| Desktop OS deps | Tauri 2 prerequisites: WebView2 (Windows), Xcode CLT (macOS), `libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev patchelf` (Linux) |
| JDK | 21 (for Android) |
| Android SDK | platform `android-37.0`, build-tools 37.0.0 (coui17 requires `compileSdk 37`) |
| Gradle | via the committed wrapper (official distribution, SHA-256 pinned) |

## Desktop

```bash
pnpm install --frozen-lockfile
pnpm tauri dev                       # development
pnpm tauri build                     # installers in src-tauri/target/release/bundle/
```

Useful environment variables (development only):

| Variable | Effect |
|---|---|
| `CC_SWITCH_TEST_HOME=<dir>` + `SWITCHTETHER_TEST_APP_DIR=1` | run with a sandboxed home (no access to your real `~/.switchtether`, agent configs written under `<dir>`) |
| `SWITCHTETHER_SKIP_UPSTREAM_IMPORT=1` | skip the first-run copy of `~/.cc-switch` |
| `SWITCHTETHER_SECRET_BACKEND=file` | keep the remote identity key in an owner-only file instead of the OS keychain |
| `SWITCHTETHER_DEV_LOG_PAIRING=1` | (debug builds) log a pairing link at startup |

Headless Remote Core for testing (no CC Switch backend):

```bash
cd src-tauri
cargo run -p switchtether-core --example dev_server -- --port 47615 --data-dir /tmp/st-dev
cargo run -p switchtether-core --example dev_client -- pair '<switchtether://pair?...>'
cargo run -p switchtether-core --example dev_client -- get /v1/sessions
```

## Android and relay

The Android app and the relay have their own repositories (build instructions in their READMEs):

| Repository | Contents | Published as |
|---|---|---|
| [SwitchTether-Android](https://github.com/TIANLI0/SwitchTether-Android) | native app (COUI17 as a submodule) | `SwitchTether-<version>-android.apk` on **this** repository's releases |
| [SwitchTether-Relay](https://github.com/TIANLI0/SwitchTether-Relay) | optional self-hosted relay (Go) | `ghcr.io/tianli0/switchtether-relay`, binaries on its own releases |

This repository stays the owner of the wire format (`docs/PROTOCOL.md`, `docs/RELAY.md`,
`protocol/fixtures/`) and the release hub.

### Release signing (Android)

The release key is a secret of this repository (`ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`); `release.yml` checks out
SwitchTether-Android at the release tag, builds `assembleRelease` with them and verifies the
certificate digest. Locally the app reads `ST_KEYSTORE_FILE`, `ST_KEYSTORE_PASSWORD`, `ST_KEY_ALIAS`,
`ST_KEY_PASSWORD` or `keystore.properties` (git-ignored). No signing material is committed.

## Desktop updater signing

The committed updater `pubkey` in `src-tauri/tauri.conf.json` is a placeholder that fails
verification, so the in-app updater refuses every update until you configure your own key:

```bash
pnpm tauri signer generate -w ~/.switchtether-release/updater.key   # keep offline/secret
# put the printed public key into src-tauri/tauri.conf.json → plugins.updater.pubkey
# add GitHub secrets: TAURI_SIGNING_PRIVATE_KEY, TAURI_SIGNING_PRIVATE_KEY_PASSWORD
```

The release workflow enables `createUpdaterArtifacts` only when those secrets exist.
macOS notarization and Windows code signing need your own certificates (secrets listed in
`.github/workflows/release.yml`); unsigned artifacts are produced otherwise and are labelled as
such in the release notes.

## CI

| Workflow | Purpose |
|---|---|
| `ci.yml` | Rust fmt/clippy/tests (workspace, 3 OSes), frontend typecheck + unit tests + build (Android and relay CI run in their repositories) |
| `release.yml` | tag `v*` (or `workflow_dispatch` with `release_tag`, which SwitchTether-Android's tag workflow sends) → draft GitHub release with the platforms whose own version equals the tag's: desktop bundles (Windows/macOS/Linux) when `package.json` carries it, the Android APK when SwitchTether-Android has the same tag (built from that tag, versionName checked). An Android-only release is a tag in SwitchTether-Android alone. A second run for the same tag adds to the draft and merges `SHA256SUMS.txt`. |
| `upstream-sync.yml` | daily check of new CC Switch releases → sync branch + PR |
