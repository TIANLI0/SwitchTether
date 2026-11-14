#!/usr/bin/env python3
"""Re-vendor the Costben/coui17 Compose UI library at a pinned commit.

Copies ONLY ``src/commonMain`` and ``src/androidMain`` of the modules listed in
MODULES (plus the upstream LICENSE / NOTICE / THIRD-PARTY-LICENSES.md) from a
local clone of https://github.com/Costben/coui17 into this directory, then
re-applies our local patches from ``patches/*.patch`` (see PATCHES.md).

Our own ``<module>/build.gradle.kts`` files are never touched.

Usage (from anywhere):

    python -I android/third_party/coui17/sync_coui17.py --repo <path-to-coui17-clone> --sha <full-sha>

The clone is only read with ``git archive``; its working tree is not modified.
After syncing: update the SHA in UPSTREAM.md, review ``git diff``, rebuild.
"""

from __future__ import annotations

import argparse
import io
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

MODULES = [
    "coui-shader",
    "coui-blur",
    "coui-squircle",
    "coui-core",
    "coui-ui",
    "coui-preference",
    "coui-icons",
]
SOURCE_SETS = ["commonMain", "androidMain"]
TOP_LEVEL_FILES = ["LICENSE", "NOTICE", "THIRD-PARTY-LICENSES.md"]

HERE = Path(__file__).resolve().parent


def run(cmd: list[str], cwd: Path | None = None, capture: bool = True) -> bytes:
    result = subprocess.run(cmd, cwd=cwd, capture_output=capture, check=False)
    if result.returncode != 0:
        sys.stderr.write(result.stderr.decode(errors="replace") if capture else "")
        raise SystemExit(f"command failed ({result.returncode}): {' '.join(cmd)}")
    return result.stdout if capture else b""


def tree_has(repo: Path, sha: str, path: str) -> bool:
    out = run(["git", "-C", str(repo), "ls-tree", "--name-only", sha, path])
    return bool(out.strip())


def extract(repo: Path, sha: str, paths: list[str], dest: Path) -> None:
    # core.autocrlf=false + core.eol=lf: keep upstream LF line endings regardless of local git config.
    data = run(["git", "-c", "core.autocrlf=false", "-c", "core.eol=lf", "-C", str(repo), "archive", "--format=tar", sha, *paths])
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        for member in tar.getmembers():
            target = (dest / member.name).resolve()
            if dest.resolve() not in target.parents and target != dest.resolve():
                raise SystemExit(f"refusing to extract outside destination: {member.name}")
            if member.issym() or member.islnk():
                raise SystemExit(f"refusing to extract link: {member.name}")
        tar.extractall(dest, filter="data")


def apply_patches(dest: Path) -> None:
    patches = sorted((dest / "patches").glob("*.patch"))
    if not patches:
        return
    prefix = subprocess.run(
        ["git", "-C", str(dest), "rev-parse", "--show-prefix"], capture_output=True, check=False
    )
    toplevel = subprocess.run(
        ["git", "-C", str(dest), "rev-parse", "--show-toplevel"], capture_output=True, check=False
    )
    for patch in patches:
        if prefix.returncode == 0 and toplevel.returncode == 0:
            directory = prefix.stdout.decode().strip().rstrip("/")
            cmd = ["git", "apply", "--whitespace=nowarn"]
            if directory:
                cmd.append(f"--directory={directory}")
            cmd.append(str(patch))
            run(cmd, cwd=Path(toplevel.stdout.decode().strip()))
        else:
            run(["git", "apply", "--whitespace=nowarn", str(patch)], cwd=dest)
        print(f"applied {patch.name}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", required=True, type=Path, help="local clone of Costben/coui17")
    parser.add_argument("--sha", required=True, help="full commit SHA to vendor")
    parser.add_argument("--dest", type=Path, default=HERE, help="vendor directory (default: script dir)")
    parser.add_argument("--no-patches", action="store_true", help="do not re-apply patches/*.patch")
    args = parser.parse_args()

    repo: Path = args.repo.resolve()
    dest: Path = args.dest.resolve()
    sha = run(["git", "-C", str(repo), "rev-parse", "--verify", f"{args.sha}^{{commit}}"]).decode().strip()
    if len(args.sha) < 40:
        raise SystemExit("pass the full 40-char SHA so UPSTREAM.md stays unambiguous")

    staging = dest / ".sync-staging"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    try:
        paths = [f for f in TOP_LEVEL_FILES if tree_has(repo, sha, f)]
        for module in MODULES:
            for source_set in SOURCE_SETS:
                p = f"{module}/src/{source_set}"
                if tree_has(repo, sha, p):
                    paths.append(p)
        extract(repo, sha, paths, staging)

        for module in MODULES:
            for source_set in SOURCE_SETS:
                target = dest / module / "src" / source_set
                if target.exists():
                    shutil.rmtree(target)
                src = staging / module / "src" / source_set
                if src.exists():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.move(str(src), str(target))
        for f in TOP_LEVEL_FILES:
            if (staging / f).exists():
                shutil.copy2(staging / f, dest / f)
    finally:
        shutil.rmtree(staging, ignore_errors=True)

    if not args.no_patches:
        apply_patches(dest)
    (dest / "UPSTREAM_SHA").write_text(sha + "\n", encoding="utf-8")
    print(f"vendored coui17 @ {sha} into {dest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
