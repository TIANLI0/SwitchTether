// Builds switchtether-shim and places it where Tauri expects a sidecar
// (`src-tauri/binaries/switchtether-shim-<target-triple>[.exe]`).
// Usage: node scripts/build-shim.mjs [--release] [--target <triple>]
// `universal-apple-darwin` builds both macOS architectures and joins them with lipo.
import { execFileSync } from "node:child_process";
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const tauriDir = join(root, "src-tauri");
const args = process.argv.slice(2);
const release = args.includes("--release");
const ti = args.indexOf("--target");
const host = /host: (\S+)/.exec(execFileSync("rustc", ["-vV"], { encoding: "utf8" }))[1];
const explicit = (ti >= 0 && args[ti + 1]) || process.env.TAURI_ENV_TARGET_TRIPLE || "";
const target = explicit || host;
const profile = release ? "release" : "debug";
const targetDir = process.env.CARGO_TARGET_DIR || join(tauriDir, "target");

function build(triple, crossCompile) {
  const cargoArgs = ["build", "-p", "switchtether-shim"];
  if (release) cargoArgs.push("--release");
  if (crossCompile) cargoArgs.push("--target", triple);
  execFileSync("cargo", cargoArgs, { cwd: tauriDir, stdio: "inherit" });
  const ext = triple.includes("windows") ? ".exe" : "";
  return crossCompile
    ? join(targetDir, triple, profile, `switchtether-shim${ext}`)
    : join(targetDir, profile, `switchtether-shim${ext}`);
}

mkdirSync(join(tauriDir, "binaries"), { recursive: true });
const ext = target.includes("windows") ? ".exe" : "";
const out = join(tauriDir, "binaries", `switchtether-shim-${target}${ext}`);
if (target === "universal-apple-darwin") {
  const parts = ["aarch64-apple-darwin", "x86_64-apple-darwin"].map((t) => build(t, true));
  execFileSync("lipo", ["-create", "-output", out, ...parts], { stdio: "inherit" });
} else {
  copyFileSync(build(target, Boolean(explicit) && explicit !== host), out);
}
console.log(`switchtether-shim -> ${out}`);
