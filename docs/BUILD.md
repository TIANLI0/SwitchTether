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

## Android

```bash
cd android
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest           # JVM + Robolectric tests
./gradlew :app:assembleRelease        # requires signing config, see below
```

The UI library is the [COUI17](https://github.com/TIANLI0/COUI17) repository as a git submodule at
`android/third_party/coui17`, built from source at the pinned commit; no GitHub token or SNAPSHOT
repository is needed. After cloning run `git submodule update --init` (or clone with
`--recurse-submodules`). To take a newer version: `git -C android/third_party/coui17 pull origin main`,
rebuild, run the tests and commit the submodule pointer.

### Release signing (Android)

Release builds read signing data from environment variables or `android/keystore.properties`
(git-ignored): `ST_KEYSTORE_FILE`, `ST_KEYSTORE_PASSWORD`, `ST_KEY_ALIAS`, `ST_KEY_PASSWORD`.
Without them `assembleRelease` produces an unsigned APK. No signing material is committed.

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
| `ci.yml` | Rust fmt/clippy/tests (workspace, 3 OSes), frontend typecheck + unit tests + build, Android unit tests + debug APK |
| `release.yml` | tag `v*` → draft GitHub release with the platforms whose own version equals the tag's: desktop bundles (Windows/macOS/Linux) when `package.json` carries it, the Android APK when `android/app/build.gradle.kts` does. A release that bumps only the Android version builds only the APK. |
| `upstream-sync.yml` | daily check of new CC Switch releases → sync branch + PR |
