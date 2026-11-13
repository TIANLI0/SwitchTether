//! SwitchTether-specific desktop integration.
//!
//! Everything that is not part of upstream CC Switch lives under this module
//! (and in the `switchtether-core` crate) so that upstream syncs only touch a
//! handful of well-known hook points in `lib.rs`. See docs/UPSTREAM_SYNC.md.

pub mod upstream_import;

/// Called at the very start of Tauri `setup`, after logging is initialised and
/// before the database or settings are opened.
pub fn on_setup_before_database() {
    if crate::app_store::get_app_config_dir_override().is_some() {
        // The user pointed the data dir somewhere explicit; never import into it.
        return;
    }
    let source = crate::config::get_upstream_app_home_dir();
    let target = crate::config::get_app_home_dir();
    if source == target {
        return;
    }
    if let Some(receipt) = upstream_import::import_if_first_run(&source, &target) {
        log::info!(
            "[switchtether] CC Switch data import: {:?} (schema {:?}, copied {:?}){}",
            receipt.outcome,
            receipt.source_schema_version,
            receipt.copied,
            receipt
                .error
                .as_deref()
                .map(|e| format!(", error: {e}"))
                .unwrap_or_default()
        );
    }
}

#[cfg(test)]
mod path_tests {
    use serial_test::serial;

    /// Production data dir name must be `.switchtether`, never upstream's.
    #[test]
    #[serial]
    fn production_dir_name_is_isolated_from_upstream() {
        assert_eq!(crate::config::APP_DIR_NAME, ".switchtether");
        assert_ne!(
            crate::config::APP_DIR_NAME,
            crate::config::UPSTREAM_APP_DIR_NAME
        );
        let tmp = tempfile::tempdir().unwrap();
        let prev_home = std::env::var_os("CC_SWITCH_TEST_HOME");
        let prev_flag = std::env::var_os("SWITCHTETHER_TEST_APP_DIR");
        std::env::set_var("CC_SWITCH_TEST_HOME", tmp.path());
        std::env::set_var("SWITCHTETHER_TEST_APP_DIR", "1");
        let dir = crate::config::get_app_home_dir();
        match prev_home {
            Some(v) => std::env::set_var("CC_SWITCH_TEST_HOME", v),
            None => std::env::remove_var("CC_SWITCH_TEST_HOME"),
        }
        match prev_flag {
            Some(v) => std::env::set_var("SWITCHTETHER_TEST_APP_DIR", v),
            None => std::env::remove_var("SWITCHTETHER_TEST_APP_DIR"),
        }
        assert_eq!(dir, tmp.path().join(".switchtether"));
    }
}
