# Third-party software in the SwitchTether Android app

Every dependency is pinned in `gradle/libs.versions.toml` (no SNAPSHOT or dynamic versions).
Only permissive licenses are used (Apache-2.0), plus MPL-2.0 *files* inside the vendored coui17
sources (file-level copyleft; source location below). The same list is shown in the app under
Settings → About → Open source licenses (`LicensesScreen`).

## Runtime (shipped in the APK)

| Component | Version | License | Notes |
| :--- | :--- | :--- | :--- |
| coui17 (`Costben/coui17`) modules coui-ui, coui-preference, coui-icons, coui-core, coui-blur, coui-shader, coui-squircle | source @ `a51f957` | Apache-2.0; 68 files MPL-2.0 | Vendored in `third_party/coui17` (LICENSE, NOTICE kept). MPL sources: https://github.com/Costben/coui17/tree/a51f95746292b3b06993b15fe46be1e2b9178421/coui-ui/src/androidMain/kotlin/io/github/suqi8/coui/kmp/kit |
| ↳ upstreams: COUI (`suqi8/coui`), Miuix (`compose-miuix-ui/miuix`) | — | Apache-2.0 | Credited in coui17 `NOTICE` |
| ↳ COUI-Kit (`ItosEO/COUI-Kit`) ported files | — | MPL-2.0 | `kit/**` files keep their MPL-2.0 headers |
| Compose Multiplatform foundation (`org.jetbrains.compose.foundation`) | 1.11.1 | Apache-2.0 | resolves to androidx.compose 1.11.2 |
| AndroidX Compose UI / foundation / runtime | 1.11.2 | Apache-2.0 | |
| JetBrains `material3-window-size-class` | 1.9.0 | Apache-2.0 | coui-ui dependency |
| `com.materialkolor:material-color-utilities` | 5.0.0 | Apache-2.0 | coui-ui dependency |
| `androidx.navigationevent:navigationevent-compose` | 1.1.2 | Apache-2.0 | coui-ui dependency |
| `androidx.dynamicanimation:dynamicanimation` | 1.0.0 | Apache-2.0 | coui-ui dependency |
| `androidx.activity:activity-compose` | 1.13.0 | Apache-2.0 | |
| `androidx.core:core-ktx` | 1.17.0 | Apache-2.0 | |
| `androidx.fragment:fragment` | 1.8.9 | Apache-2.0 | FragmentActivity for BiometricPrompt |
| `androidx.lifecycle` (runtime-compose, viewmodel-compose, process) | 2.10.0 | Apache-2.0 | |
| `androidx.datastore:datastore-preferences` | 1.2.1 | Apache-2.0 | non-secret pairing metadata + settings |
| `androidx.biometric:biometric` | 1.1.0 | Apache-2.0 | step-up confirmation |
| `androidx.camera` (core, camera2, lifecycle, view) | 1.6.2 | Apache-2.0 | QR scanning preview |
| `com.google.zxing:core` | 3.5.4 | Apache-2.0 | QR decoding (no Play Services / ML Kit) |
| `com.squareup.okhttp3:okhttp` (+ okio) | 5.5.1 | Apache-2.0 | HTTPS + WebSocket |
| Kotlin stdlib | 2.4.10 | Apache-2.0 | |
| `kotlinx-coroutines` | 1.11.0 | Apache-2.0 | |
| `kotlinx-serialization-json` | 1.11.0 | Apache-2.0 | |
| Phosphor Icons, Compose port `com.adamglin:phosphor-icon` | 1.0.0 | MIT | App icon set (Regular / Fill); icons © Phosphor Icons (phosphoricons.com, MIT); unused icons removed by R8 in release |
| LobeHub Icons (`@lobehub/icons-static-svg`): Claude + OpenAI marks | 1.102.0 | MIT | Converted to VectorDrawables; see `third_party/brand-icons/NOTICE.md` |

Transitive dependencies of the above (AndroidX annotation/collection/arch-core, Guava
`listenablefuture` stub from CameraX, JSpecify, etc.) are Apache-2.0.

## Build / test only (not shipped)

| Component | Version | License |
| :--- | :--- | :--- |
| Gradle wrapper (official, sha256-verified) | 9.8.2 | Apache-2.0 |
| Android Gradle Plugin | 9.3.1 | Apache-2.0 |
| Kotlin Gradle plugin, Compose compiler, serialization plugin | 2.4.10 | Apache-2.0 |
| JetBrains Compose Gradle plugin | 1.11.1 | Apache-2.0 |
| JUnit 4 | 4.13.2 | EPL-1.0 (test only, not distributed) |
| Robolectric | 4.18 | MIT |
| AndroidX Test (core, runner, ext-junit), Compose ui-test | 1.7.0 / 1.3.0 / 1.11.2 | Apache-2.0 |
| OkHttp mockwebserver3, okhttp-tls | 5.5.1 | Apache-2.0 |
| kotlinx-coroutines-test | 1.11.0 | Apache-2.0 |

## Deliberately NOT used

- `org.lsposed.hiddenapibypass` (coui17 upstream dependency) — patched out, see `third_party/coui17/PATCHES.md`.
- Google Play Services / ML Kit, Firebase, analytics/crash SDKs, WebView.
