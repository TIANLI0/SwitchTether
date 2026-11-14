// Windows release package with FluentSetup (packaging/windows/fluentsetup.json).
//
//   node scripts/package-windows.mjs [--skip-build]
//
// 1. `tauri build --no-bundle` (its beforeBuildCommand also builds switchtether-shim)
// 2. stage switchtether.exe + switchtether-shim.exe in target-win/app
// 3. `fluentsetup build` -> target-win/dist/SwitchTether-<version>-Setup.exe
// 4. SHA256SUMS.txt next to it (FluentSetup's updater verifies against it)
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const tauriDir = join(root, "src-tauri");
const skipBuild = process.argv.includes("--skip-build");
const conf = JSON.parse(readFileSync(join(tauriDir, "tauri.conf.json"), "utf8"));
const version = conf.version;
const run = (cmd, args, opts = {}) =>
  execFileSync(cmd, args, { stdio: "inherit", shell: process.platform === "win32", ...opts });

if (!skipBuild) {
  run("pnpm", ["tauri", "build", "--no-bundle"], { cwd: root });
}

const targetDir = process.env.CARGO_TARGET_DIR || join(tauriDir, "target");
const release = join(targetDir, "release");
const stage = join(root, "target-win", "app");
rmSync(stage, { recursive: true, force: true });
mkdirSync(stage, { recursive: true });
copyFileSync(join(release, "switchtether.exe"), join(stage, "switchtether.exe"));
const shim = readdirSync(join(tauriDir, "binaries")).find((f) => /^switchtether-shim-.*windows.*\.exe$/.test(f));
if (!shim) throw new Error("switchtether-shim sidecar missing (node scripts/build-shim.mjs --release)");
copyFileSync(join(tauriDir, "binaries", shim), join(stage, "switchtether-shim.exe"));

const dist = join(root, "target-win", "dist");
mkdirSync(dist, { recursive: true });
run("fluentsetup", ["validate", join("packaging", "windows", "fluentsetup.json")], { cwd: root });
run("fluentsetup", ["build", join("packaging", "windows", "fluentsetup.json"), "-D", `Version=${version}`], { cwd: root });

const setup = join(dist, `SwitchTether-${version}-Setup.exe`);
if (!existsSync(setup)) throw new Error(`expected ${setup}`);
run("fluentsetup", ["inspect", setup, "--verify"], { cwd: root });
const hash = createHash("sha256").update(readFileSync(setup)).digest("hex");
writeFileSync(join(dist, "SHA256SUMS.txt"), `${hash}  SwitchTether-${version}-Setup.exe\n`);
console.log(`\n${setup}\nsha256 ${hash}`);
