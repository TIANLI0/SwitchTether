# Research: `Costben/coui17` as the UI library for the SwitchTether Android app

- Date of research: 2026-11-14
- Target app: Kotlin + Jetpack Compose, minSdk 29, compileSdk 36 (planned), AGP 8.x/9.x, JDK 21
- Local toolchain used: JDK 21.0.8 (`C:\Program Files\Java\jdk-21`), Android SDK `D:\AndroidSDK`
  (platforms 31/34/35/36/37.0, build-tools up to 37.0.0)
- Scratch clone (not inside this repo):
  `C:\Users\tian5\AppData\Local\Temp\claude\ffd30bb9fd79d184\87d5c375-0f35-41ed-ba14-2bf5700b8a9c\scratchpad\research\coui17`

## TL;DR

| Question | Answer |
| :--- | :--- |
| Compose or View-based? | **Pure Compose (Compose Multiplatform).** Not View/XML based. No `AndroidView` bridge needed. It is built on `compose-foundation` only, with no Material3 dependency except `material3-window-size-class`. |
| Usable from Jetpack Compose on Android? | **Yes.** On Android, the CMP artifacts resolve to `androidx.compose.*` 1.11.x. A probe consumer app built OK (see section 6). |
| Distribution of *this fork* (`coui17`, v1.2.0) | **Not on Maven Central or JitPack.** You can get it only from GitHub Packages as a rolling `1.2.0-SNAPSHOT` (needs a token) or by building it from source. |
| Distribution of the *upstream* release | `io.github.suqi8.coui.kmp:*:1.1.0` on **Maven Central**. This is the `suqi8/coui` release, which is also tag `v1.1.0` / commit `e4e915ca` in the coui17 history. |
| Hard consumer constraints | **compileSdk ≥ 37** (every AAR declares `minCompileSdk=37`). Kotlin ≥ 2.3 (stdlib 2.4.10 is pulled in). Compose 1.11.x is forced. |
| License | Apache-2.0 (LICENSE file is the standard Apache 2.0 text). **The coui17 fork also contains 68 MPL-2.0 files** in `coui-ui/src/androidMain/.../kit/**`. |
| Bundled OPPO binaries/fonts/resources? | **None.** The library modules hold only `.kt` sources, and git history has no `.aar`/`.jar`/`.so`/fonts apart from `gradle-wrapper.jar`. However, the fork **reflects into OPPO private framework classes** (`com.oplus.*`), **uses `org.lsposed.hiddenapibypass`**, and documents constants "transcribed from decompiled" ColorOS code. |
| Builds locally? | **Yes, after two workarounds.** The fork's sample app `:example:android:assembleDebug` built in 3m46s. The workarounds were: (1) the wrapper URL points at a Tencent mirror that serves a bot-challenge page, and (2) `gradle/gradle-daemon-jvm.properties` forces a JetBrains Runtime 21 download. |
| **Recommendation** | Consume **upstream `io.github.suqi8.coui.kmp:coui-ui:1.1.0`** (plus `coui-preference`/`coui-icons` 1.1.0) from Maven Central, pinned with Gradle dependency verification, and set the app's **compileSdk to 37**. Do not depend on the coui17 fork's SNAPSHOT channel or use `includeBuild`. If ColorOS 17-only components become necessary later, see section 7, option B. |

---

## 1. Repository facts (evidence)

Commands run (from the scratch dir):

```text
git clone https://github.com/Costben/coui17 coui17
git log -1 --format='%H%n%ci%n%an <%ae>%n%s'
  ecdde6b55b9db646402fb0d8cc33964f5559bed3
  2026-11-13 21:47:36 +0800
  Costben <28681450+Costben@users.noreply.github.com>
  feat(coui-ui): 定型双行顶栏的模糊高度与渐隐形式
git log --oneline | wc -l            -> 1619 commits
git rev-list --count e4e915ca..HEAD   -> 397 commits since the fork point
git rev-list --count --since=2026-10-14 HEAD -> 232 (last 30 days); --since=2026-11-07 -> 53
git shortlog -sn e4e915ca..HEAD      -> 397  Costben   (single maintainer)
```

`gh release list -R Costben/coui17` → **empty (no GitHub Releases).**

Tags (both are inherited from upstream `suqi8/coui`. The fork has no tags of its own):

| Tag | Commit | Date |
| :--- | :--- | :--- |
| `v1.0.0` (annotated) | `b2b6180397760f13e5a24bb731f2ec70cb6cf29f` | 2026-07-27 |
| `v1.1.0` | `e4e915ca67ea0f2689c746822dcc5fda1f84e817` (= fork point) | 2026-08-04 |

`gh api repos/Costben/coui17`:

```json
{"full_name":"Costben/coui17","description":"A UI library for Compose MultiPlatform",
 "license":{"spdx_id":"Apache-2.0"},"stars":6,"forks":0,"open_issues_count":0,
 "created_at":"2026-10-06T06:29:07Z","pushed_at":"2026-11-13T13:47:41Z",
 "updated_at":"2026-11-13T18:48:26Z","default_branch":"main","archived":false,
 "fork":false,"homepage":"https://costben.github.io/coui17/","language":"Kotlin"}
```

On GitHub the repo is not flagged as a fork (`fork:false`), but `README.md`/`UPSTREAM.md` call it a **downstream fork**. The lineage is:

1. `compose-miuix-ui/miuix`: Apache-2.0, 1622 stars, actively maintained. It is the origin of the history (first commit `f754f1e9`, 2024-08-06).
2. `suqi8/coui`: Apache-2.0, 66 stars. It re-themed Miuix to ColorOS 16, renamed it to `io.github.suqi8.coui.kmp`, and published 1.0.0/1.1.0 to Maven Central. Its last push was 2026-08-04.
3. `Costben/coui17` (this repo) forked `suqi8/coui` at `e4e915ca` (1.1.0) and moves it toward ColorOS 17.
4. In addition, the fork ported code from `ItosEO/COUI-Kit` (MPL-2.0, 98 stars).

CI status (`gh run list -R Costben/coui17`): on HEAD `ecdde6b5`, the **"CI" workflow failed**, but only at
`:example:shared:spotlessKotlinCheck` (formatting in the demo). The Android compile/test, desktop, web, native and
docs jobs all passed. The **last fully green CI commit** is `a51f95746292b3b06993b15fe46be1e2b9178421`
(2026-11-13 17:21 +0800). CI on `main` often goes red. Of the last 15 CI runs, 7 failed and 1 was cancelled.

Maturity signals: the README itself says *"This library is experimental. APIs may change without notice."* The fork
changes very fast (about 8 commits/day). `UPSTREAM.md` is already stale: it says "roughly 158 commits" on top of the
fork point, while the actual count is 397.

## 2. Technology and components

**It is Jetpack Compose / Compose Multiplatform, not Views.**

- Plugins: `org.jetbrains.kotlin.multiplatform`, `com.android.kotlin.multiplatform.library` (AGP 9 KMP plugin),
  `org.jetbrains.compose` 1.11.1, and the Compose compiler from Kotlin 2.4.10.
- Targets: Android, desktop JVM, iosArm64/iosSimulatorArm64, macosArm64, wasmJs and js.
- Library modules contain **only Kotlin sources**. `git ls-files 'coui-*' | grep -v '\.kt$'` shows nothing except
  `build.gradle.kts`, API dumps and `baseline-prof.txt`. There are no XML layouts, drawables or fonts.
- Dependencies of `coui-ui` (from the published 1.1.0 `.module`, android runtime variant): `coui-core`,
  `coui-squircle`, `org.jetbrains.compose.foundation:foundation:1.11.1`, `kotlin-stdlib:2.4.10`,
  `androidx.navigationevent:navigationevent-compose:1.1.2`,
  `org.jetbrains.compose.material3:material3-window-size-class:1.9.0` and
  `com.materialkolor:material-color-utilities:5.0.0`.
  - The coui17 fork additionally adds on Android `org.lsposed.hiddenapibypass:hiddenapibypass:6.1` and
    `androidx.dynamicanimation:dynamicanimation:1.0.0`.
- An `AndroidView` bridge is **not needed**. The cost question for that bridge doesn't apply.
- Since it is not Material3, it replaces the Material3 component set. It does not decorate it. Our screens would use
  `COUITheme` and `io.github.suqi8.coui.kmp.basic.*` instead of `MaterialTheme`/`androidx.compose.material3.*`.
  Mixing the two is possible but would look inconsistent.

### Modules (group `io.github.suqi8.coui.kmp`, one shared version)

| Module | Purpose | Android source sets |
| :--- | :--- | :--- |
| `coui-ui` | All components, theme, dialogs, popups (417 `.kt` files in the fork) | common + android |
| `coui-preference` | Settings rows (48 files) | common + android |
| `coui-icons` | Extended icon set (156 files) | common |
| `coui-core` | Shared primitives | common + android |
| `coui-blur`, `coui-shader`, `coui-squircle` | `RuntimeShader` blur (API 33+, no-op below), squircle shapes | common + android |
| `coui-nav`, `coui-navigation3-ui` | Own navigation runtime / Navigation3 integration | common + android |

### Usable components (from `coui-ui/src/commonMain/.../{basic,window,overlay,theme}`)

| Need | Components |
| :--- | :--- |
| Buttons | `Button`, `TextButton`, `IconButton`, `HighlightedIconButton`, `FloatingActionButton`, `FloatingButton`, `LoadingButton`, `LoadProgressButton` |
| Toggles / selection | `Switch` (with `isLoading`), `Checkbox`, `RadioButton`, `Slider`/`RangeSlider`/`VerticalSlider`, `Stepper`, `Chip`, `ChipTab`/`ChipTabGroup`, `SegmentCapsule` |
| Lists / settings rows | `Card` (+ `CardListPosition`), `BasicComponent`, `SmallTitle`, `ExpandableList`, `SlideView` (swipe-to-delete, fork-only, experimental). `coui-preference` adds `SwitchPreference`, `CheckboxPreference`, `RadioButtonPreference`, `ArrowPreference`, `ListPreference`, `EditTextPreference`, `SliderPreference`, `StepperPreference`, `Window/OverlayDropdownPreference`, `Window/OverlaySpinnerPreference`, and about 20 more. |
| App bars | `TopAppBar` (large collapsing title), `SmallTopAppBar`, `PinnedTopAppBar`, `TwoRowTopAppBar` / `SearchFilterTopAppBar` (fork-only), `AppBarBlurOverlay`, `SearchBar`, `TopAppBarSearchBar` |
| Bottom navigation | `NavigationBar`, `FloatingNavigationBar`, `BottomFloatingNavigationBar`, `BottomFloatingAccessoryNavigationBar`, `NavigationRail`, `SideNavigationBar`, `BottomFloatingToolbar` |
| Tabs | `TabRow`, `TabRowWithContour`, `ChipTabGroup`, `PageIndicator` |
| Dialogs / sheets | `WindowAlertDialog`, `WindowDialog`, `WindowBottomSheet`, `WindowLoadingDialog`, `WindowProgressDialog`, `WindowListPopup`. Plus `Overlay*` variants (`OverlayAlertDialog`, `OverlayBottomSheet`, `OverlayListPopup`, and others) that render inside `Scaffold`'s popup host. |
| Snackbar / tips | `Snackbar` + `SnackbarHost`, `NotificationSnackBar`, `TopTips`, `Tooltip`, `Banner`, `Badge` |
| Inputs | `TextField`, `InputView`, `CodeTextField`, `NumberPicker`, `DatePicker`, `TimePicker`, `ColorPicker` |
| Progress / states | `CircularProgressIndicator`, `LinearProgressIndicator`, `InfiniteProgressIndicator`, `RotatingProgressIndicator`, `PullToRefresh`, `EmptyState`, `CompleteState` |
| Layout | `Scaffold` (topBar/bottomBar/FAB/snackbar/popup host, `edgeToEdge`), `Surface`, `HorizontalDivider`/`VerticalDivider`, `SidePaneLayout`, `ListDetailView`, `FlowLayout` |
| Theme | `COUITheme`, `ThemeController`, `ColorSchemeMode`, `lightColorScheme()`/`darkColorScheme()`, `TextStyles`, `Dimens` |
| Icons | `Icon` plus the built-in basic icons in `coui-ui`, and `coui-icons` (156 vector icons, defined in Kotlin code) |

## 3. Distribution

| Channel | Status | Evidence |
| :--- | :--- | :--- |
| **Maven Central** | Only the **upstream** `io.github.suqi8.coui.kmp:*` **1.0.0 and 1.1.0** (published by suqi8 on 2026-08-04). **No 1.2.0 and none of the coui17 fork's code.** | `curl https://repo1.maven.org/maven2/io/github/suqi8/coui/kmp/coui-ui/maven-metadata.xml` → `<release>1.1.0</release>`, versions `1.0.0`, `1.1.0`, lastUpdated `20260804162604` (same for `coui-preference`, `coui-core`, `coui-icons`, `coui-blur`) |
| JitPack | No builds | `GET https://jitpack.io/api/builds/com.github.Costben/coui17` → `{"com.github.Costben":{"coui17":{}}}` |
| **GitHub Packages** | `https://maven.pkg.github.com/Costben/coui17`, version **`1.2.0-SNAPSHOT`** (rolling: *"each push replaces what that coordinate resolves to"*). Anonymous access fails. You need a PAT with `read:packages`. | Anonymous GET of `.../coui-ui/maven-metadata.xml` → HTTP **401**. README/UPSTREAM.md describe `publishAllToGithubPackages -ProllingSnapshot`. |
| Build from source | `./gradlew publishAllToLocalRepository -Prelease` → `build/repository/local`, version `1.2.0`. Without `-Prelease` the version is `1.2.0-<shortsha>-SNAPSHOT`. | `build-plugins/src/main/kotlin/BuildConfig.kt` (`LIBRARY_VERSION = "1.2.0"`, `releaseVersionSuffix()`) |

Concerns about the fork's coordinates:

- The fork publishes under the upstream's group and version line (`io.github.suqi8.coui.kmp`, `1.2.0`). `UPSTREAM.md`
  states that the coordinates belong to `suqi8/coui`.
- If upstream ever publishes its own `1.2.0` to Maven Central, a locally built fork `1.2.0` would collide with it.
  The content would differ under identical coordinates.
- Any consumption of the fork must therefore use `exclusiveContent` for the group, as the fork's own
  `verification/android-api23-consumer` does.

**SNAPSHOT dependencies inside the build:** none for the libraries. `gradle/libs.versions.toml` pins everything to
release versions. The only pre-release is `androidx.baselineprofile` `1.5.0-alpha07`, a build plugin only.

### Build toolchain of the fork (HEAD `ecdde6b5`)

| Item | Value | Source |
| :--- | :--- | :--- |
| Gradle wrapper | **9.8.2**, `distributionUrl=https://mirrors.cloud.tencent.com/gradle/gradle-9.8.2-bin.zip`, **no `distributionSha256Sum`** | `gradle/wrapper/gradle-wrapper.properties` |
| Daemon JVM | `toolchainVendor=JETBRAINS`, `toolchainVersion=21` (auto-downloads JBR from foojay) | `gradle/gradle-daemon-jvm.properties` |
| AGP | **9.3.1** | `libs.versions.toml` |
| Kotlin | **2.4.10** | `libs.versions.toml` |
| Compose Multiplatform | 1.11.1 (→ androidx.compose 1.11.2 on Android) | `libs.versions.toml`, probe dependency report |
| JDK | 21 (`jvmToolchain(21)`) | `BuildConfig.JDK_VERSION` |
| compileSdk / targetSdk | **37** (minor 0) / 37, build-tools 37.0.0 | `BuildConfig.kt` |
| minSdk | **23** in all modules of the fork. For **upstream 1.1.0**: 23, except `coui-blur-android-1.1.0.aar`, whose manifest says `minSdkVersion="33"`. | `BuildConfig.kt`, AAR manifests |
| `gradle-wrapper.jar` | sha256 `497c8c2a…a9c7` = official Gradle 9.5.0/9.6.1 wrapper jar (legit, just not regenerated for 9.8.x) | `services.gradle.org/.../gradle-9.5.0-wrapper.jar.sha256` |

### Consumer-side constraints, verified on the published 1.1.0 AARs

```text
unzip -p coui-ui-android-1.1.0.aar META-INF/com/android/build/gradle/aar-metadata.properties
  aarFormatVersion=1.0
  aarMetadataVersion=1.0
  minCompileSdk=37            <-- consumer app must use compileSdk >= 37
  minCompileMinorSdk=0
  minCompileSdkExtension=0
  minAndroidGradlePluginVersion=1.0.0
  coreLibraryDesugaringEnabled=false
```

`com.materialkolor:material-color-utilities-android:5.0.0` also requires compileSdk 37. **This means our planned
compileSdk 36 does not work with either upstream 1.1.0 or the fork.** The probe in section 6 confirms it.
`android-37.0` is already installed locally.

### Can it be consumed as source / submodule / `includeBuild` pinned to a commit?

Technically yes, since the full source is there. Each way has its own cost:

- **`includeBuild("third_party/coui17")`:**
  - AGP refuses mixed plugin versions in one composite build, so our app would be forced onto **AGP 9.3.1 and
    Kotlin 2.4.10** exactly.
  - Every sync would configure the fork's whole KMP graph: iOS/macOS/wasm/js targets, the docs demo, the example apps
    and baseline-profile.
  - It also inherits the fork's `gradle-daemon-jvm.properties` and foojay toolchain resolver.
  - **Not recommended.**
- **git submodule pinned to a SHA + publish to a file-based Maven repo + `exclusiveContent`:** this works. The
  publish attempt is in section 6. It decouples our AGP/Kotlin versions from theirs. It costs a JDK 21 / Gradle 9.8
  pre-build step, plus a Windows workaround for the wrapper mirror and the JBR download.
- **Copy (vendor) selected source files into our own module:** feasible because everything is plain Kotlin. It
  requires keeping the Apache NOTICE and, for `kit/**` files, the MPL-2.0 headers. It also becomes a maintenance fork.

## 4. License and IP / trademark review

- `LICENSE` contains the standard **Apache License 2.0** text (202 lines, md5 `b5ddd7f4d4feef8e1b62107b5e250eb4`).
  The appendix boilerplate is filled in with *"Copyright 2025-2026, compose-coui-ui contributors"*.
- `NOTICE` credits Miuix (Apache-2.0), COUI (`suqi8/coui`, Apache-2.0) and **COUI-Kit (`ItosEO/COUI-Kit`, MPL-2.0)**.
  For COUI-Kit it says: *"Those files live in `kit` packages, carry the MPL-2.0 header and remain under MPL-2.0 …
  COUI-Kit's third-party resources (ColorOS images and icons) are not included."*
- SPDX headers in library `.kt` files: `680 × Apache-2.0`, **`68 × MPL-2.0`**. All the MPL-2.0 files are in
  `coui-ui/src/androidMain/kotlin/io/github/suqi8/coui/kmp/kit/**`: backdrop, effect, input, motion, search,
  segmentbutton, shape, slider, text, theme and topappbar. They were added in Nov 2026 (first was `9f4d1d24`,
  2026-11-10).
  - MPL-2.0 is file-level copyleft. It does not affect our MIT code.
  - But if we ship an APK containing those classes, we must tell recipients where to get the source of those files,
    for example a link to the pinned upstream commit in our OSS notices.
  - **Upstream 1.1.0 contains no `kit` package and no MPL code.** I checked this by unzipping
    `coui-ui-android-1.1.0.aar` → `classes.jar` and found 0 `kmp/kit` classes.
- **No proprietary binaries are bundled.**
  - `git rev-list --objects --all` over the whole history finds no `.aar/.jar/.apk/.dex/.so/.ttf/.otf/.woff`. The
    only match is `gradle/wrapper/gradle-wrapper.jar`.
  - Images exist only in the example/docs apps. `coui17_wallpaper.jpg` is byte-identical to `blur_test_bg.jpg`,
    inherited from Miuix (#278).
  - No fonts are loaded. `NumericKeyboard` only *mentions* `/system/fonts/SysSans-En-Regular.ttf` in a comment.
- **OPPO-specific runtime behaviour in the coui17 fork (not in 1.1.0):**
  - Reflection into OPPO private framework classes: `Class.forName("com.oplus.graphics.OplusRenderEffect")`,
    `com.oplus.view.OplusViewBackgroundRenderEffect`, `com.oplus.view.material.OplusMaterialUtil`,
    `com.oplus.os.LinearmotorVibrator`, `com.oplus.content.OplusFeatureConfigManager`, and others.
    - There are 16 sites in 5 files under `coui-ui/src/androidMain`, all wrapped in `runCatching` fallbacks.
    - They also read the system property `persist.sys.oplus.anim_level`.
    - On ColorOS devices, `LocalPlatformBottomBarMaterialEnabled` and `LocalPlatformAppBarMaterialEnabled` default
      to `true`, so these paths run unless disabled.
  - **`org.lsposed.hiddenapibypass`** is used in `BottomBarBackdropRenderer.android.kt` to call the hidden
    `View.setBackdropRenderEffect` on API 36 (`BAKLAVA`).
    - This bypasses Android's non-SDK-interface restrictions. It is a policy and compatibility risk, for example for
      Google Play review or future Android releases.
  - Upstream 1.1.0 has neither: 0 `com.oplus` references and 0 `hiddenapibypass` references in `classes.jar`, and
    `hiddenapibypass` is not in the published dependencies.
- **Provenance:** the code is original Kotlin, but many KDoc comments and docs say values were taken from decompiled
  ColorOS code. Examples:
  - `IntentSeekBar.kt:679` "the decompiled source does not name"
  - `Chip.kt:520` "decompiled build ships no strings resources"
  - `docs/guide/specification.md:379` "only decompiled source evidence"
  - many `com.coui.appcompat.*` class and resource names (116 hits in library sources)
  - comments citing line numbers of decompiled OPPO classes

  Design values (dp, colours, easing constants) are generally facts, not copyrightable expression. Still, the
  derivation from decompiled proprietary code is a residual IP risk. It increases in the fork and is lower in 1.1.0.
- **Trademark:** "COUI" (ColorOS UI) and "ColorOS" are OPPO names. We would ship them only as Java package names
  (`io.github.suqi8.coui.kmp.*`) inside our APK. We should not use "COUI"/"ColorOS" in our app's branding or store
  listing.
- **What we would redistribute** (APK) with 1.1.0:
  - Apache-2.0 code from Miuix and COUI, plus their transitive Apache-2.0 deps (Compose, AndroidX, materialkolor).
  - We must ship the Apache `LICENSE` + `NOTICE` text (an OSS licenses screen is enough).
  - With the fork we would additionally ship MPL-2.0 files and the `hiddenapibypass` library (Apache-2.0).
- Compatibility with SwitchTether's MIT license: Apache-2.0 and MPL-2.0 (file-level) can both be combined with MIT
  in a distributed binary, given the notices above.

## 5. Theming, dark mode, performance, sample

- **Theme entry point:**

  ```kotlin
  val controller = remember {
      ThemeController(
          colorSchemeMode = ColorSchemeMode.System,      // System | Light | Dark | MonetSystem | MonetLight | MonetDark
          lightColors = lightColorScheme(primary = Color(0xFF0080FF)),
          darkColors  = darkColorScheme(primary = Color(0xFF3482FF)),
          keyColor = null,       // seed colour for Monet modes; null = wallpaper (Material You) colours
          isDark = null,         // null follows the system
      )
  }
  COUITheme(controller = controller) { /* app */ }
  ```

  You can also call `COUITheme(colors = lightColorScheme(...)/darkColorScheme(...))` directly.
- **Primary / accent colours:** `lightColorScheme()`/`darkColorScheme()` take about 60 named slots, for example
  `primary` (default `#0080FF`, the COUI blue), `onPrimary`, `primaryContainer`, `secondary*`, `tertiaryContainer`,
  `error*`, `surface*`, `onSurface*`, `dividerLine`, `focusOutline`. `ThemeController.keyColor` with
  `ColorSchemeMode.Monet*` derives a full scheme from one seed (material-color-utilities). It supports
  `ThemePaletteStyle` (TonalSpot/Vibrant/…) and `ThemeColorSpec` (Spec2021/2025).
- **Typography / metrics:** `TextStyles` and `Dimens` are passed via `COUITheme(textStyles=…, dimens=…)`.
  `ProvideCOUIDimens` overrides metrics for a subtree.
- **Dark mode:** full support. `ColorSchemeMode.System`/`MonetSystem` follow `isSystemInDarkTheme()`, and each
  component has dark-specific tokens. The sample app ships `values-night` window themes for the activity background.
- **Performance notes (from docs and code):**
  - Blur uses `RuntimeShader` + `RenderEffect` on API 33+ and is skipped below that. With minSdk 29, devices on
    API 29–32 get flat fills.
  - `LocalBlurEnabled` (coui-blur) turns blur off globally. `layerBackdrop` stops recording when nothing samples it.
  - Progressive blur adds a full-resolution pass per frame (the docs warn about this).
  - The fork's platform bottom-bar material on ColorOS costs "0.7–1.5 ms more per frame" (`docs/guide/specification.md`).
  - Library AARs ship `baseline-prof.txt`, and the fork has a `baselineprofile` generator module.
  - Spring and over-scroll physics replace Compose defaults via `LocalOverscrollFactory` (`COUIOverscrollFactory`).
- **Sample app:** `example/android` (app id `io.github.suqi8.coui17`) plus the gallery in
  `example/shared/src/commonMain/kotlin/coui17/`. There is also a web demo at https://costben.github.io/coui17/example/
  and docs at https://costben.github.io/coui17/ (about 134 component pages, EN + zh_CN).

## 6. Build attempts (local toolchain, JDK 21 set per command only)

All builds ran in the scratch dir. I did not change any global git, Gradle or system config. The user-level
`~/.gradle/gradle.properties` already sets `org.gradle.java.home=C:/Program Files/Java/jdk-21` and an HTTP proxy
`127.0.0.1:7890`; I only read it and left it unchanged.

### 6.1 Fork sample app: `:example:android:assembleDebug` at `ecdde6b5`

| Attempt | Result |
| :--- | :--- |
| 1. `./gradlew --console=plain :example:android:assembleDebug` (as cloned) | **Failed in 20 s.** The wrapper download from `mirrors.cloud.tencent.com` returned a 985-byte EdgeOne bot-challenge HTML page (`<script>…EO_Bot_Ssid…`) instead of the zip. Errors: `zip END header not found`, and `.sha256` → HTTP 404. I removed the junk dist dir afterwards. |
| 2. Scratch-only edit: `distributionUrl=https://services.gradle.org/distributions/gradle-9.8.2-bin.zip` + `distributionSha256Sum=21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607` (official checksum) | The distribution downloaded OK. The client then hung at "Starting a Gradle Daemon" with no daemon process. **Cause:** `gradle/gradle-daemon-jvm.properties` requires a `JETBRAINS` 21 daemon JVM, so Gradle silently began downloading `jbrsdk_jcef-21-JetBrains-21.0.11-windows-x64-b1163.116.tar.gz` from foojay. It had reached 120 MB when I stopped it and deleted the partial file. |
| 3. Scratch-only: removed `gradle/gradle-daemon-jvm.properties` so the daemon uses `JAVA_HOME` = JDK 21 | **BUILD SUCCESSFUL in 3m 46s**, 152 tasks. Output: `example/android/build/outputs/apk/debug/COUI-v1.0.9(1619)-debug.apk` (16.4 MB). Only deprecation warnings (`w:` lines in build scripts, `FlowLayout.kt`). |

### 6.2 Consumer probe: an app shaped like SwitchTether, consuming upstream 1.1.0 from Maven Central

Project: `scratchpad/research/consumer-probe`.

- Setup: AGP **8.13.2**, Kotlin **2.3.21** (`kotlin-android` + compose plugin), Gradle 9.1.0, minSdk **29**,
  targetSdk 36, JDK 21.
- Dependencies: `coui-ui:1.1.0`, `coui-preference:1.1.0`, `activity-compose:1.13.0`.
- `MainActivity` uses `COUITheme(ThemeController(System))`, `Scaffold`, `SmallTopAppBar`, `Card`, `Switch`,
  `SwitchPreference` and `Button`.

| Attempt | Result |
| :--- | :--- |
| compileSdk **36** | **FAILED** `:app:checkDebugAarMetadata`: *"6 issues were found … Dependency 'io.github.suqi8.coui.kmp:coui-preference-android:1.1.0' requires libraries and applications that depend on it to compile against version 37 or later of the Android APIs. :app is currently compiled against android-36. Also, the maximum recommended compile SDK version for Android Gradle plugin 8.13.2 is 36."* The same message appears for coui-ui, coui-core, coui-squircle, coui-shader and `material-color-utilities-android:5.0.0`. |
| compileSdk **37** + `-Pandroid.suppressUnsupportedCompileSdk=37,37.0` (JVM target 21) | **BUILD SUCCESSFUL in 32 s** → `app-debug.apk` (27.7 MB, debug, unminified). Manifest merge with minSdk 29 was fine (`coui-blur` is not a transitive dep of `coui-ui` 1.1.0). AGP 8.13.2 warns *"tested up to compile SDK version 36.1"*. Resolved: `androidx.compose.foundation/ui/runtime 1.11.2`; `kotlin-stdlib 2.3.21 -> 2.4.10` compiled fine with Kotlin 2.3.21. |

### 6.3 Publishing the fork's Android artifacts to a local file repository

This tests the feasibility of option B (section 7). I ran only a dry run (`-m`), because the time box was already used up:

```text
./gradlew --no-daemon -Prelease -m -x dokkaGenerate \
  :{coui-core,coui-shader,coui-squircle,coui-blur,coui-ui,coui-preference,coui-icons}:publishKotlinMultiplatformPublicationToLocalRepository \
  :{...same modules...}:publishAndroidPublicationToLocalRepository
```

Result: **BUILD SUCCESSFUL in 7m 48s**. The task graph resolves, with 578 tasks listed and no signing tasks for the
`local` repository. `javadocJar`/Dokka can be skipped with `-x dokkaGenerate`. Two things to note:

- Changing `-Prelease` invalidated the configuration cache. Configuring the whole multiplatform project alone took
  most of those ~8 minutes.
- The KMP root ("metadata") publication also schedules `compileDarwinMainKotlinMetadata`/`compileIosMainKotlinMetadata`
  for the shared Apple source sets.

So a real publish is feasible, but it is a slow (~10 min cold) pre-build step on Windows. It is **not verified
end-to-end** here.

Scratch-clone modifications made for these builds (not pushed anywhere):
`gradle/wrapper/gradle-wrapper.properties` was changed to the official URL + sha256, and
`gradle/gradle-daemon-jvm.properties` was removed (copy saved as `scratchpad/research/gradle-daemon-jvm.properties.orig`).

## 7. Recommendation

### Primary: upstream `io.github.suqi8.coui.kmp:*:1.1.0` from Maven Central

```kotlin
// settings.gradle.kts (android/)
dependencyResolutionManagement {
    repositories {
        google()
        exclusiveContent {                       // only Maven Central may serve this group
            forRepository { mavenCentral() }
            filter { includeGroup("io.github.suqi8.coui.kmp") }
        }
        mavenCentral()
    }
}

// gradle/libs.versions.toml
// coui = "1.1.0"
// coui-ui         = { module = "io.github.suqi8.coui.kmp:coui-ui",         version.ref = "coui" }
// coui-preference = { module = "io.github.suqi8.coui.kmp:coui-preference", version.ref = "coui" }
// coui-icons      = { module = "io.github.suqi8.coui.kmp:coui-icons",      version.ref = "coui" }   // optional

// app/build.gradle.kts
android {
    compileSdk = 37            // REQUIRED: AAR metadata minCompileSdk=37
    defaultConfig { minSdk = 29; targetSdk = 36 }  // targetSdk can stay 36
}
```

Why this option:

- **Reproducible:** Maven Central releases are immutable and signed. Pin them further with
  `gradle/verification-metadata.xml` (`./gradlew --write-verification-metadata sha256 help`). No token or private
  registry is needed. CI and other machines resolve the same bytes.
- **Clean licence:** 1.1.0 is Apache-2.0 only.
  - It has no MPL files, no `com.oplus` reflection and no `hiddenapibypass`. I verified this on the AAR classes.
  - Obligations: include the Apache LICENSE plus the COUI/Miuix NOTICE in an OSS-licences screen. Don't use the
    "COUI"/"ColorOS" names in our branding.
- **Proven with our constraints:** minSdk 29 + JDK 21 + AGP 8.13.2 + Kotlin 2.3.21 builds, provided compileSdk is 37.
  - Choose **AGP 9.x** (9.3.1 is already in the local Gradle cache) to avoid the "compileSdk 37 untested" warning on
    AGP 8.13. Otherwise keep AGP 8.13.2 with `android.suppressUnsupportedCompileSdk=37,37.0`.
  - Use Kotlin ≥ 2.3; 2.4.x is recommended to match the stdlib that gets pulled in.
  - Compose resolves to 1.11.x, so don't pin a Compose BOM older than that.
- **Avoid `coui-blur:1.1.0`.** Its manifest says `minSdkVersion=33`, so it would need
  `tools:overrideLibrary("io.github.suqi8.coui.kmp.blur")`. `coui-ui` 1.1.0 does not need it.

Trade-off: 1.1.0 is the ColorOS **16** look. It lacks the fork's ColorOS 17 recalibration and new components:
`TwoRowTopAppBar`, `SearchFilterTopAppBar`, `SlideView`, `SearchHistoryChip`, `PageIndicator` rework, floating
toolbars, and others. It is also frozen: upstream's last push was 2026-08-04.

### Rejected options

- **GitHub Packages `1.2.0-SNAPSHOT`:**
  - It is a rolling snapshot: the same coordinate changes on every push, which is non-reproducible by design.
  - It needs a PAT with `read:packages` on every machine and in CI.
  - It uses upstream's coordinates.
- **JitPack:** no builds exist. A build would need JDK 21, Gradle 9.8, AGP 9.3 and compileSdk 37 in JitPack's
  environment.
- **`includeBuild`:**
  - It forces our build onto AGP 9.3.1 / Kotlin 2.4.10 (AGP rejects mixed versions in a composite).
  - It configures the fork's full multiplatform graph (iOS/wasm/js/docs/example) on every sync.
  - It inherits the Tencent wrapper mirror and the JBR daemon criteria.

### Option B (only if ColorOS 17 visuals or fork-only components are required)

- **Pin** coui17 at a fully green-CI commit as a git submodule under `android/third_party/coui17`. The current
  candidate is `a51f95746292b3b06993b15fe46be1e2b9178421` (2026-11-13). HEAD `ecdde6b5` also builds but failed CI
  formatting.
- In a scripted pre-build step, publish only the Android + KMP metadata publications of the modules we use into an
  in-repo or CI-cached file repository. Consume them via `exclusiveContent` for `io.github.suqi8.coui.kmp`, the way
  the fork's own `verification/android-api23-consumer` does.
- Keep **our own** pinned build settings for that step:
  - official `services.gradle.org` URL + `distributionSha256Sum`
  - either delete `gradle-daemon-jvm.properties` or accept the JBR download
  - JDK 21
- Additional obligations:
  - Ship an MPL-2.0 source-availability notice for the 68 `kit/**` files, for example a link to the pinned commit.
  - Decide on `hiddenapibypass`. Consider providing `LocalPlatformBottomBarMaterialEnabled provides false` and
    `LocalPlatformAppBarMaterialEnabled provides false` at the root so the OPPO-private and hidden-API paths are not
    exercised.
  - Accept that the fork is experimental, single-maintainer and changes very fast, so each bump needs a re-review.

### Not recommended: re-implementing in Compose from design tokens only

The library is already Compose. Re-implementing would be justified only if we reject both the licence/IP profile
and the compileSdk 37 requirement.
