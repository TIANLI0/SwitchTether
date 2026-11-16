# Vendored: coui17 (Compose UI library, ColorOS 17 style)

| Item | Value |
| :--- | :--- |
| Upstream repository | https://github.com/Costben/coui17 (fork of `suqi8/coui`, itself a fork of `compose-miuix-ui/miuix`) |
| Pinned commit | `0fc9b44aadf01632dd2e43ae3db6bcf10621bc8d` (2026-11-16, latest upstream `main`) — also in `UPSTREAM_SHA` |
| License | Apache-2.0 (`LICENSE`, `NOTICE`); 68 files under `coui-ui/src/androidMain/kotlin/io/github/suqi8/coui/kmp/kit/**` are MPL-2.0 (headers kept) |
| Why vendored | The fork is not on Maven Central/JitPack; its GitHub Packages channel is a rolling, token-protected SNAPSHOT. Source vendoring pins exact bytes and keeps our AGP/Kotlin choices independent. |

## What was copied

Only `src/commonMain` and `src/androidMain` of these modules, nothing else (no build logic,
tests, samples, docs, baseline-profile generator):

`coui-shader`, `coui-blur`, `coui-squircle`, `coui-core`, `coui-ui`, `coui-preference`, `coui-icons`

plus the upstream top-level `LICENSE`, `NOTICE` and `THIRD-PARTY-LICENSES.md`.

Each module has **our own** `build.gradle.kts`: a Kotlin Multiplatform library with **only the
Android target** (`com.android.kotlin.multiplatform.library` + `org.jetbrains.kotlin.multiplatform`
+ Compose compiler + `org.jetbrains.compose`), compileSdk 37.0, minSdk 23 (as upstream), JDK 21.
Dependencies mirror upstream `gradle/libs.versions.toml` at the pinned commit (Compose MP 1.11.1,
navigationevent-compose 1.1.2, material3-window-size-class 1.9.0, material-color-utilities 5.0.0,
dynamicanimation 1.0.0) — **without** `org.lsposed.hiddenapibypass` (see `PATCHES.md`).
`coui-squircle/build.gradle.kts` carries upstream's SDF bake task verbatim (it generates sources).

The app additionally sets `LocalPlatformAppBarMaterialEnabled` / `LocalPlatformBottomBarMaterialEnabled`
to `false` (see `:core:ui` `SwitchTetherTheme`) so the library never reflects into private
`com.oplus.*` framework classes.

## Re-syncing to another commit

```bash
# from the repo root; the clone is only read with `git archive`
python -I android/third_party/coui17/sync_coui17.py --repo <path-to-coui17-clone> --sha <full-40-char-sha>
```

The script replaces `<module>/src/{commonMain,androidMain}` and the license files, forces LF line
endings, re-applies every `patches/*.patch` (fails loudly if one no longer applies) and writes
`UPSTREAM_SHA`. Afterwards: update the SHA in this file, check upstream `gradle/libs.versions.toml`
and module build files for dependency changes, review the diff (look for new `com.oplus`
reflection, hidden-API use, network or file access), rebuild and run the tests.

## MPL-2.0 source availability

The MPL-2.0 files shipped in the APK are available at:
https://github.com/Costben/coui17/tree/0fc9b44aadf01632dd2e43ae3db6bcf10621bc8d/coui-ui/src/androidMain/kotlin/io/github/suqi8/coui/kmp/kit
(and in this directory). This link is also shown on the in-app "Open source licenses" screen.
