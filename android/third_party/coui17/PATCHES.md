# Local patches to vendored coui17

All patches live in `patches/` and are re-applied by `sync_coui17.py` in file-name order.

## 0001-remove-hiddenapibypass.patch

- **File:** `coui-ui/src/androidMain/kotlin/io/github/suqi8/coui/kmp/basic/BottomBarBackdropRenderer.android.kt`
- **Upstream behaviour:** on Android 16 (API 36, `BAKLAVA`) devices *without* the Oplus framework,
  the native bottom-bar material called the hidden `View.setBackdropRenderEffect(RenderEffect)`
  through `org.lsposed.hiddenapibypass.HiddenApiBypass` (non-SDK interface access).
- **Why removed:** bypassing Android's non-SDK-interface restrictions is a security/Play-policy
  smell and can break on future Android releases.
- **Change (smallest possible):** removed the `HiddenApiBypass` import, the private
  `PlatformBottomBarBackdropApi` class, and the `.recoverCatching { … PlatformBottomBarBackdropApi() }`
  fallback. The `org.lsposed.hiddenapibypass` dependency is not declared in our build.
- **Resulting behaviour:** on ColorOS devices the Oplus framework path is unchanged (and is
  disabled by default in SwitchTether anyway via `LocalPlatformBottomBarMaterialEnabled = false`).
  On every other device `rememberPlatformBottomBarBackdropRenderer()` returns `null`, so the bottom
  bar is drawn by coui's portable Compose blur engine — exactly what already happened on API < 36.
- **Verified:** `:coui-ui:compileAndroidMain` and the app build succeed; no `hiddenapibypass`
  reference remains (`grep -r hiddenapibypass third_party/coui17 --include=*.kt` only finds the
  explanatory comment).

## 0002-localizable-sheet-close-label.patch

- **File:** `coui-ui/src/commonMain/kotlin/io/github/suqi8/coui/kmp/layout/BottomSheetContentLayout.kt`
- **Upstream behaviour:** the default close capsule of `WindowBottomSheet` (`SheetCloseCapsule`)
  had its label hard-coded as `"关闭"`, so an English UI showed Chinese and apps could not localize it.
- **Change (smallest possible):** added a public `LocalSheetCloseLabel` composition local (default
  `"关闭"`, so upstream behaviour is unchanged for apps that don't provide it) and made it the default
  for `SheetCloseCapsule(text = …)`.
- **Resulting behaviour:** SwitchTether provides `stringResource(R.string.action_close)` at the root
  (`SwitchTetherRoot`), so every sheet's close capsule follows the app locale ("Close" / "关闭").
