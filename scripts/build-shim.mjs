// Builds switchtether-shim and places it where Tauri expects a sidecar
// (`src-tauri/binaries/switchtether-shim-<target-triple>[.exe]`).
// Usage: node scripts/build-shim.mjs [--release] [--target <triple>]
import { execFileSync } from "node:child_process";
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const tauriDir = join(root, "src-tauri");
const args = process.argv.slice(2);
const release = args.includes("--release");
const ti = args.indexOf("--target");
const target =
  (ti >= 0 && args[ti + 1]) ||
  process.env.TAURI_ENV_TARGET_TRIPLE ||
  /host: (\S+)/.exec(execFileSync("rustc", ["-vV"], { encoding: "utf8" }))[1];

const cargoArgs = ["build", "-p", "switchtether-shim"];
if (release) cargoArgs.push("--release");
if (ti >= 0 || process.env.TAURI_ENV_TARGET_TRIPLE) cargoArgs.push("--target", target);
execFileSync("cargo", cargoArgs, { cwd: tauriDir, stdio: "inherit" });

const ext = target.includes("windows") ? ".exe" : "";
const targetDir = process.env.CARGO_TARGET_DIR || join(tauriDir, "target");
const profile = release ? "release" : "debug";
const built =
  ti >= 0 || process.env.TAURI_ENV_TARGET_TRIPLE
    ? join(targetDir, target, profile, `switchtether-shim${ext}`)
    : join(targetDir, profile, `switchtether-shim${ext}`);
const out = join(tauriDir, "binaries", `switchtether-shim-${target}${ext}`);
mkdirSync(dirname(out), { recursive: true });
copyFileSync(built, out);
console.log(`switchtether-shim -> ${out}`);
