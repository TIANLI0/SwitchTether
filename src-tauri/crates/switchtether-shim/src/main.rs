//! switchtether-shim — opt-in process wrapper for SwitchTether takeover.
//!
//! Invocation forms (all explicitly enabled by the user, all reversible):
//!
//! 1. VS Code Claude Code extension, setting `claudeCode.claudeProcessWrapper`:
//!    `switchtether-shim <path-to-claude> <claude args…>` — stream-json proxy.
//! 2. Codex Desktop, user environment variable `CODEX_CLI_PATH`:
//!    `switchtether-shim app-server …` — JSON-RPC proxy in front of Desktop's
//!    own bundled `codex` (other subcommands are passed straight through).
//! 3. Terminal: `switchtether-shim tty <claude|codex> [args…]` (via the
//!    `st-claude` / `st-codex` launchers) — PTY passthrough + mirror.
//!
//! Safety: if SwitchTether is not running (no attach file, connection refused,
//! bad token) the shim behaves exactly like the wrapped program. A broker
//! failure mid-session never interrupts the session.

mod broker;
mod resolve;
mod router;
mod stream;
mod tty;

use std::ffi::OsString;
use std::path::PathBuf;
use std::process::{Command, ExitCode, Stdio};

use router::Dialect;

pub struct Target {
    pub program: PathBuf,
    pub args: Vec<OsString>,
    pub agent: &'static str,
    pub origin: &'static str,
}

fn passthrough(program: &PathBuf, args: &[OsString]) -> ExitCode {
    match Command::new(program)
        .args(args)
        .stdin(Stdio::inherit())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit())
        .status()
    {
        Ok(s) => ExitCode::from(s.code().unwrap_or(1).clamp(0, 255) as u8),
        Err(e) => {
            eprintln!("switchtether-shim: cannot start {}: {e}", program.display());
            ExitCode::from(127)
        }
    }
}

fn is_claude_binary(p: &std::path::Path) -> bool {
    p.is_file()
        && p.file_stem()
            .map(|s| s.to_string_lossy().to_ascii_lowercase().contains("claude"))
            .unwrap_or(false)
}

fn main() -> ExitCode {
    let mut argv: Vec<OsString> = std::env::args_os().collect();
    argv.remove(0);

    // 3. terminal mode
    if argv.first().map(|a| a == "tty").unwrap_or(false) {
        argv.remove(0);
        return tty::run(argv);
    }

    // 1. VS Code wrapper: first argument is the extension's claude binary.
    if let Some(first) = argv.first() {
        let p = PathBuf::from(first);
        if is_claude_binary(&p) {
            let args: Vec<OsString> = argv[1..].to_vec();
            let stream_json = args
                .windows(2)
                .any(|w| w[0] == "--input-format" && w[1] == "stream-json")
                || args.iter().any(|a| a == "--input-format=stream-json");
            if !stream_json {
                return passthrough(&p, &args);
            }
            return stream::run(
                Target {
                    program: p,
                    args,
                    agent: "claude_code",
                    origin: "vscode",
                },
                Dialect::ClaudeStream,
            );
        }
    }

    // 2. Codex Desktop via CODEX_CLI_PATH.
    let Some(codex) = resolve::real_codex() else {
        eprintln!("switchtether-shim: could not locate the real codex executable (set SWITCHTETHER_REAL_CODEX)");
        return ExitCode::from(127);
    };
    let is_app_server = argv.first().map(|a| a == "app-server").unwrap_or(false)
        || argv.iter().any(|a| a == "app-server");
    let has_listen = argv
        .iter()
        .any(|a| a.to_string_lossy().starts_with("--listen"));
    if is_app_server && !has_listen {
        return stream::run(
            Target {
                program: codex,
                args: argv,
                agent: "codex",
                origin: "desktop_app",
            },
            Dialect::JsonRpc,
        );
    }
    passthrough(&codex, &argv)
}
