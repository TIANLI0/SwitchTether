"""Synthetic Claude Code sandbox for the :baselineprofile journeys (android/DEV_NOTES.md "Performance").

Usage: python -I gen_sessions.py <sandbox_claude_config_dir> <pid> [<pid> ...]

Writes, inside the sandbox only, sessions/<pid>.json registry entries (one live session per pid;
the pids must be alive, e.g. idle `sleep` processes) and a transcript per session under
projects/. The first session is a 120-turn conversation titled "Long benchmark conversation".
Run dev_server with CLAUDE_CONFIG_DIR=<that dir> (and an empty CODEX_HOME) so it serves only
these sessions. Never point it at a real ~/.claude.
"""
import json
import os
import sys
import time
import uuid

root = sys.argv[1]
pids = [int(p) for p in sys.argv[2:]]
os.makedirs(os.path.join(root, "sessions"), exist_ok=True)
for f in os.listdir(os.path.join(root, "sessions")):
    os.remove(os.path.join(root, "sessions", f))

# Project directories (session cwds) next to the sandbox config dir.
base = os.path.join(os.path.dirname(os.path.abspath(root)), "proj")
now = time.time()


def ts(i):
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(now - 3600 + i)) + "Z"


MD = """Here is what I changed in `{f}`:

1. Moved the parsing into a **pure function** so it can be tested.
2. Added a guard for empty input.

```kotlin
fun parse(raw: String): Result<Item> {{
    if (raw.isBlank()) return Result.failure(IllegalArgumentException("empty"))
    val parts = raw.split(':', limit = 2)
    return Result.success(Item(parts[0].trim(), parts.getOrNull(1)?.trim().orEmpty()))
}}
```

| case | before | after |
|---|---|---|
| empty | crash | error |
| `a:b` | ok | ok |

Run `./gradlew test` to confirm."""


def transcript(sid, cwd, turns, title):
    lines = [{"type": "custom-title", "customTitle": title, "sessionId": sid}]
    i = 0
    for t in range(turns):
        f = f"src/module{t % 7}/File{t}.kt"
        lines.append({"type": "user", "uuid": str(uuid.uuid4()), "timestamp": ts(i), "sessionId": sid, "cwd": cwd,
                      "message": {"role": "user", "content": f"Turn {t}: please refactor {f} and explain the change. " + "Context line. " * (t % 5)}})
        i += 1
        mid = f"m{t}"
        lines.append({"type": "assistant", "uuid": str(uuid.uuid4()), "timestamp": ts(i),
                      "message": {"id": mid, "model": "claude-sonnet-4-5", "content": [{"type": "thinking", "thinking": "Let me look at the file first. " * 4}]}})
        i += 1
        tid = f"t{t}a"
        lines.append({"type": "assistant", "uuid": str(uuid.uuid4()), "timestamp": ts(i),
                      "message": {"id": mid, "content": [{"type": "tool_use", "id": tid, "name": "Read", "input": {"file_path": cwd + "/" + f}}]}})
        i += 1
        lines.append({"type": "user", "uuid": str(uuid.uuid4()), "timestamp": ts(i),
                      "message": {"role": "user", "content": [{"type": "tool_result", "tool_use_id": tid, "content": "\n".join(f"{n}\tline {n} of {f}" for n in range(1, 30))}]}})
        i += 1
        if t % 2 == 0:
            tid = f"t{t}b"
            lines.append({"type": "assistant", "uuid": str(uuid.uuid4()), "timestamp": ts(i),
                          "message": {"id": mid, "content": [{"type": "tool_use", "id": tid, "name": "Edit", "input": {"file_path": cwd + "/" + f, "old_string": "val a = 1", "new_string": "val a = 2"}}]}})
            i += 1
            lines.append({"type": "user", "uuid": str(uuid.uuid4()), "timestamp": ts(i),
                          "message": {"role": "user", "content": [{"type": "tool_result", "tool_use_id": tid, "content": "ok"}]},
                          "toolUseResult": {"filePath": cwd + "/" + f, "structuredPatch": [{"oldStart": 3, "oldLines": 2, "newStart": 3, "newLines": 2, "lines": [" context", "-val a = 1", "+val a = 2", " context"]}]}})
            i += 1
        else:
            tid = f"t{t}c"
            lines.append({"type": "assistant", "uuid": str(uuid.uuid4()), "timestamp": ts(i),
                          "message": {"id": mid, "content": [{"type": "tool_use", "id": tid, "name": "Bash", "input": {"command": "./gradlew test --console=plain", "description": "Run tests"}}]}})
            i += 1
            lines.append({"type": "user", "uuid": str(uuid.uuid4()), "timestamp": ts(i),
                          "message": {"role": "user", "content": [{"type": "tool_result", "tool_use_id": tid, "content": "BUILD SUCCESSFUL in 12s\n42 tests completed"}]}})
            i += 1
        lines.append({"type": "assistant", "uuid": str(uuid.uuid4()), "timestamp": ts(i),
                      "message": {"id": mid + "x", "content": [{"type": "text", "text": MD.format(f=f)}], "stop_reason": "end_turn",
                                  "usage": {"input_tokens": 1200 + t, "output_tokens": 300 + t}}})
        i += 1
    return lines


for n, pid in enumerate(pids):
    sid = str(uuid.UUID(int=0x5E55_0000_0000_4000_8000_0000_0000_0000 + n))
    cwd = os.path.join(base, f"project{n:02d}")
    title = "Long benchmark conversation" if n == 0 else f"Benchmark session {n:02d}"
    turns = 120 if n == 0 else 3
    enc = "".join(c if c.isascii() and c.isalnum() else "-" for c in cwd)
    pdir = os.path.join(root, "projects", enc)
    os.makedirs(pdir, exist_ok=True)
    os.makedirs(cwd, exist_ok=True)
    with open(os.path.join(pdir, sid + ".jsonl"), "w", encoding="utf-8") as fh:
        for line in transcript(sid, cwd, turns, title):
            fh.write(json.dumps(line) + "\n")
    with open(os.path.join(root, "sessions", f"{pid}.json"), "w", encoding="utf-8") as fh:
        json.dump({"pid": pid, "sessionId": sid, "cwd": cwd, "startedAt": int((now - n * 600) * 1000),
                   "kind": "interactive", "entrypoint": "cli", "status": "idle", "name": title}, fh)
print(f"wrote {len(pids)} sessions under {root}")
