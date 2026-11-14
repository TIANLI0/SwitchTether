# Upstream sync (CC Switch → SwitchTether)

SwitchTether is a long-lived fork of [farion1231/cc-switch](https://github.com/farion1231/cc-switch)
(MIT). Every upstream **stable release** should be merged, but never released without passing
the full regression suite.

## Remotes and branches

| Ref | Meaning |
|---|---|
| `upstream` remote | `https://github.com/farion1231/cc-switch.git`, fetches `main` + tags only |
| `upstream-base` | the upstream tag currently merged (v4.0.31 at the time of writing) |
| `main` | SwitchTether |
| `upstream-sync/vX.Y.Z` | merge branch for a new upstream release (created by CI or by hand) |

`UPSTREAM_VERSION` at the repository root records the merged tag; CI compares it with the
latest upstream release.

## Automated flow (`.github/workflows/upstream-sync.yml`)

1. Daily: read the latest *published, non-prerelease* upstream release.
2. If it differs from `UPSTREAM_VERSION`: create `upstream-sync/<tag>` from `main`, run
   `git merge --no-ff <tag>`.
3. On conflicts: push the branch with the conflict list in the PR body (merge is left
   for a human); otherwise update `UPSTREAM_VERSION` and push.
4. Open a pull request. The normal CI (Rust tests + clippy, Remote Core e2e, frontend typecheck
   and tests, Android build/tests, schema-version check) runs on it.
5. A maintainer reviews the checklist below and merges. **Releases are only built from tags on
   `main`**, never from sync branches.

## Manual flow

```bash
git fetch upstream --tags
git switch -c upstream-sync/v4.0.32 main
git merge --no-ff v4.0.32
# resolve conflicts (see hot spots), then:
echo v4.0.32 > UPSTREAM_VERSION
cd src-tauri && cargo test --workspace && cd ..
pnpm typecheck && pnpm test:unit
cd android && ./gradlew testDebugUnitTest assembleDebug
git branch -f upstream-base v4.0.32
```

## Conflict hot spots

| File | SwitchTether change | Resolution rule |
|---|---|---|
| `src-tauri/tauri.conf.json`, `tauri.windows.conf.json` | product name, identifier, binary, deep link, updater | keep SwitchTether identity/updater; take upstream's other changes |
| `src-tauri/Cargo.toml` | package name, `[workspace]`, extra deps | keep ours + take upstream dependency bumps; Cargo `version` = new upstream version |
| `package.json` | name/version/description | keep ours; take dependency changes |
| `src-tauri/src/lib.rs` | `mod switchtether`, setup hooks, quit/exit hooks, command list | re-apply the small hook blocks marked `SwitchTether` |
| `src-tauri/src/tray.rs` | quit → `switchtether::request_quit` | re-apply |
| `src-tauri/src/config.rs`, `settings.rs`, `live/engine.rs`, `panic_hook.rs`, `services/backup_storage.rs`, `services/env_manager.rs`, `mode/controller.rs` | `~/.cc-switch` → `get_app_home_dir()` | after merging, `grep -rn '".cc-switch"' src-tauri/src` — every **non-test** new occurrence must use `crate::config::get_app_home_dir()` |
| `src/lib/navigation.ts`, `Sidebar.tsx`, `SettingsPage.tsx` | `remote` settings section | re-apply |
| `src/i18n/locales/*.json` | `remote.*` keys | keep both |
| `.github/workflows/*` | SwitchTether workflows | keep ours; review upstream changes for useful CI improvements |
| `README*.md` | SwitchTether README | keep ours; upstream README kept as `README_UPSTREAM.md` (refresh it) |

## Regression checklist before merging a sync PR

- [ ] `cargo test --workspace` (upstream tests + Remote Core unit/e2e)
- [ ] `cargo clippy --workspace --all-targets -- -D warnings` on Linux/macOS/Windows CI
- [ ] `pnpm typecheck`, `pnpm test:unit`
- [ ] Database: upstream `SCHEMA_VERSION` change reviewed; import of a CC Switch DB with the new
      schema still works (`switchtether::upstream_import` tests); the import refuses newer schemas
- [ ] New `.cc-switch` path literals routed through `get_app_home_dir()`
- [ ] Remote API compatibility: `protocol/fixtures` tests pass on both Rust and Android
- [ ] Android unit tests + `assembleDebug`
- [ ] Manual smoke on one desktop OS: provider switch from phone reflects in desktop UI/tray
- [ ] Release notes mention the upstream version

## Data compatibility

SwitchTether never writes `~/.cc-switch`. A new upstream schema version only affects
SwitchTether's own copy in `~/.switchtether` (migrated by upstream code at startup, with
upstream's own backups). If a user's CC Switch is newer than SwitchTether's base, the first-run
import is skipped (receipt records `skippedSourceSchemaTooNew`) and SwitchTether starts fresh;
updating SwitchTether and deleting `~/.switchtether/upstream-import.json` allows a retry.

## Trial merge record

2026-11-14: `upstream/main` (6 commits after v4.0.31, `8c76bfe1`) was trial-merged into
SwitchTether `main` (`40d09bb6`) in a throwaway worktree. Conflicts: `src-tauri/src/lib.rs`
(module list — keep both `mod switchtether;` and upstream's new `mod sync_utils;`),
`.github/workflows/ci.yml` (keep both appended jobs), `.github/CODEOWNERS` (deleted in
SwitchTether — keep deleted), `src-tauri/Cargo.lock` (take upstream, let cargo re-resolve).
After resolution `cargo check --workspace` succeeded. All conflicts were at the documented
hot spots above.
