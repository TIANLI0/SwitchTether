// SwitchTether bridge for DeepSeek Harness (opt-in takeover, docs/AGENT_COMPATIBILITY.md §6b).
//
// Installed into the user's `web` profile only while the user keeps the switch on in
// SwitchTether → Settings → Remote, and removed exactly when it is turned off. It connects to
// SwitchTether's attach broker on 127.0.0.1 (port and token from SwitchTether's owner-only
// attach file) and:
//   - reports which sessions this dsh host is running (`api-session/added|activity|removed`),
//   - offers each approval request to the phone while the normal dsh UI still asks too;
//     the first answer wins and the other side is withdrawn,
//   - submits messages (`sessionController.prompt`, queue mode) and cancels turns from the phone.
// When SwitchTether is not running it does nothing and retries quietly.
// No dependencies besides Node built-ins.

import net from "node:net";
import fs from "node:fs";
import crypto from "node:crypto";

const ATTACH_FILE = __ATTACH_FILE__;
const VERSION = __VERSION__;

export const name = "switchtether-bridge";
export const inject = ["sessionController"];

export function apply(ctx) {
  let socket = null;
  let ready = false;
  let buffer = "";
  let closed = false;
  let retry = null;
  const live = new Set();
  // requestId -> { resolve, abort }
  const approvals = new Map();

  const send = (frame) => {
    if (!ready || socket === null) return false;
    try {
      socket.write(JSON.stringify(frame) + "\n");
      return true;
    } catch {
      return false;
    }
  };

  const schedule = () => {
    if (closed || retry !== null) return;
    retry = setTimeout(() => {
      retry = null;
      connect();
    }, 5000);
    retry.unref?.();
  };

  const onFrame = async (f) => {
    if (f.t === "welcome") {
      ready = true;
      for (const id of live) send({ t: "session", sessionId: id });
      return;
    }
    if (f.t === "prompt") {
      try {
        await ctx.sessionController.prompt(
          {
            requestId: crypto.randomUUID(),
            sessionId: f.sessionId,
            mode: "queue",
            content: [{ type: "text", text: String(f.text ?? "") }],
          },
          AbortSignal.timeout(60000),
        );
        send({ t: "result", id: f.id, ok: true });
      } catch (e) {
        send({ t: "result", id: f.id, ok: false, error: String(e?.message ?? e) });
      }
      return;
    }
    if (f.t === "cancel") {
      try {
        ctx.sessionController.cancel({ sessionId: f.sessionId });
        send({ t: "result", id: f.id, ok: true });
      } catch (e) {
        send({ t: "result", id: f.id, ok: false, error: String(e?.message ?? e) });
      }
      return;
    }
    if (f.t === "approve") {
      const pending = approvals.get(f.requestId);
      if (pending !== undefined) pending.resolve(f.outcome);
    }
  };

  const connect = () => {
    if (closed) return;
    let info;
    try {
      info = JSON.parse(fs.readFileSync(ATTACH_FILE, "utf8"));
    } catch {
      schedule(); // SwitchTether (or its remote server) is not running
      return;
    }
    const s = net.connect({ host: "127.0.0.1", port: info.port });
    socket = s;
    buffer = "";
    s.setNoDelay(true);
    s.on("connect", () => {
      s.write(
        JSON.stringify({ t: "hello", v: 1, token: info.token, kind: "plugin", dialect: "dsh", pid: process.pid, version: VERSION }) + "\n",
      );
    });
    s.on("data", (chunk) => {
      buffer += chunk.toString("utf8");
      let nl;
      while ((nl = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, nl);
        buffer = buffer.slice(nl + 1);
        if (!line.trim()) continue;
        let f;
        try {
          f = JSON.parse(line);
        } catch {
          continue;
        }
        onFrame(f).catch(() => {});
      }
    });
    const down = () => {
      if (socket === s) {
        socket = null;
        ready = false;
        // Phone answers can no longer arrive: leave every pending approval to the dsh UI.
        for (const p of approvals.values()) p.resolve(undefined);
        schedule();
      }
    };
    s.on("error", down);
    s.on("close", down);
  };

  ctx.on("api-session/added", (summary) => {
    const id = summary?.sessionId ?? summary?.id;
    if (typeof id !== "string") return;
    live.add(id);
    send({ t: "session", sessionId: id });
  });
  ctx.on("api-session/activity", (sessionId) => {
    if (typeof sessionId !== "string") return;
    if (!live.has(sessionId)) {
      live.add(sessionId);
      send({ t: "session", sessionId });
    } else {
      send({ t: "activity", sessionId });
    }
  });
  ctx.on("api-session/removed", (sessionId) => {
    if (typeof sessionId !== "string") return;
    live.delete(sessionId);
    send({ t: "session-closed", sessionId });
  });

  // Approvals: ask the phone and the dsh UI at the same time; the first answer wins.
  ctx.on("approval/request", (request, next) => {
    const sessionId = request?.agent?.session?.id;
    if (!ready || typeof sessionId !== "string") return next();
    const requestId = "dsh-ap-" + crypto.randomUUID();
    const uiAbort = new AbortController();
    const signals = [uiAbort.signal];
    if (request.signal) signals.push(request.signal);
    let fromUi;
    try {
      // Hand the UI a request whose signal also fires when the phone answered first.
      fromUi = Promise.resolve(next({ ...request, signal: AbortSignal.any(signals) }));
    } catch (e) {
      fromUi = Promise.reject(e);
    }
    const fromPhone = new Promise((resolve) => {
      approvals.set(requestId, { resolve });
    });
    send({
      t: "approval",
      requestId,
      sessionId,
      toolName: request.toolName ?? request.tool ?? null,
      reason: typeof request.reason === "string" ? request.reason : null,
      callId: request.callId ?? null,
    });
    const settle = (outcome, by) => {
      approvals.delete(requestId);
      if (by === "phone") uiAbort.abort();
      else send({ t: "approval-done", requestId, outcome, by });
      return outcome;
    };
    return Promise.race([
      fromUi.then((o) => settle(o, "desktop"), () => settle("cancelled", "desktop")),
      fromPhone.then((o) => (o === undefined ? fromUi : settle(o, "phone"))),
    ]);
  });

  connect();
  ctx.on("dispose", () => {
    closed = true;
    if (retry !== null) clearTimeout(retry);
    try {
      socket?.destroy();
    } catch {}
    for (const p of approvals.values()) p.resolve(undefined);
  });
}
