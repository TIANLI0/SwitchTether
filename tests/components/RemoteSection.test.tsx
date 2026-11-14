import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import "@testing-library/jest-dom";

const { listeners, api, toastError } = vi.hoisted(() => {
  const status = {
    config: {
      enabled: true,
      port: 47615,
      networkMode: "lan",
      serverName: "Desk",
    },
    running: true,
    port: 47615,
    error: null,
    fingerprint: "ab".repeat(32),
    fingerprintDisplay: "abab abab",
    serverName: "Desk",
    secretBackend: "os_keychain",
    hosts: [{ address: "192.168.1.5", interface: "Wi-Fi", kind: "lan" }],
    connections: [],
    pairedDevices: 1,
    terminals: 1,
    managedSessions: 0,
  };
  const api = {
    status: vi.fn(async () => status),
    listDevices: vi.fn(async () => [
      {
        id: "d1",
        name: "Pixel 9",
        platform: "android",
        osVersion: "15",
        appVersion: "0.1.0",
        certFingerprint: "cd".repeat(32),
        fingerprintShort: "cdcdcdcdcdcdcdcd",
        stepUpEnabled: true,
        createdAtMs: 1,
        lastSeenAtMs: 2,
        online: true,
      },
    ]),
    listTerminals: vi.fn(async () => [
      {
        id: "t1",
        shell: "pwsh",
        cwd: "C:\proj",
        title: "build",
        cols: 80,
        rows: 24,
        createdAtMs: 1,
        exited: false,
      },
      {
        id: "t2",
        shell: "cmd",
        cwd: "C:\\",
        title: "done",
        cols: 80,
        rows: 24,
        createdAtMs: 1,
        exited: true,
      },
    ]),
    auditLog: vi.fn(async () => [
      { id: 1, tsMs: 1, action: "pair", target: "192.168.1.20", outcome: "ok" },
    ]),
    setConfig: vi.fn(async (c: unknown) => ({ ...status, config: c })),
    createPairing: vi.fn(async () => ({
      pairingId: "p1",
      uri: "switchtether://pair?v=1",
      expiresAt: new Date(Date.now() + 300_000).toISOString(),
      qrSvg: '<svg data-testid="qr"></svg>',
      hosts: ["192.168.1.5"],
      port: 47615,
      fingerprintDisplay: "abab abab",
    })),
    cancelPairing: vi.fn(async () => {}),
    revokeDevice: vi.fn(async () => true),
    forgetDevice: vi.fn(async () => true),
    renameDevice: vi.fn(async () => {}),
    killTerminal: vi.fn(async () => true),
  };
  return {
    listeners: [] as Array<(e: { payload: unknown }) => void>,
    api,
    toastError: vi.fn(),
  };
});
vi.mock("@tauri-apps/api/event", () => ({
  listen: vi.fn(
    async (_name: string, cb: (e: { payload: unknown }) => void) => {
      listeners.push(cb);
      return () => {};
    },
  ),
}));

vi.mock("react-i18next", () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      opts ? `${key}:${JSON.stringify(opts)}` : key,
  }),
}));

vi.mock("@/lib/toast", () => ({
  toast: { success: vi.fn(), error: (...a: unknown[]) => toastError(...a) },
}));

vi.mock("@/lib/api/remote", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/remote")>(
      "@/lib/api/remote",
    );
  return { ...actual, remoteApi: api };
});

import { RemoteSection } from "@/components/settings/sections/RemoteSection";

describe("RemoteSection", () => {
  beforeEach(() => {
    listeners.length = 0;
    Object.values(api).forEach((f) => f.mockClear());
  });

  it("shows server status, paired phones, running terminals and the audit log", async () => {
    render(<RemoteSection />);
    expect(await screen.findByText("Pixel 9")).toBeInTheDocument();
    expect(screen.getByText(/remote.runningOn/)).toHaveTextContent("47615");
    expect(screen.getByText("abab abab")).toBeInTheDocument();
    expect(screen.getByText("build")).toBeInTheDocument();
    // exited terminals are not listed
    expect(screen.queryByText("done")).not.toBeInTheDocument();
    expect(screen.getByText("pair")).toBeInTheDocument();
    expect(
      screen.getByText("remote.secretBackend.os_keychain"),
    ).toBeInTheDocument();
  });

  it("revokes a phone only after confirmation", async () => {
    render(<RemoteSection />);
    await screen.findByText("Pixel 9");
    fireEvent.click(screen.getAllByText("remote.devices.revoke")[0]);
    expect(api.revokeDevice).not.toHaveBeenCalled();
    const buttons = await screen.findAllByText("remote.devices.revoke");
    fireEvent.click(buttons[buttons.length - 1]);
    await waitFor(() => expect(api.revokeDevice).toHaveBeenCalledWith("d1"));
  });

  it("shows a QR code and the verification code after the phone pairs", async () => {
    render(<RemoteSection />);
    await screen.findByText("Pixel 9");
    fireEvent.click(screen.getByText("remote.pair.button"));
    expect(await screen.findByTestId("qr")).toBeInTheDocument();
    listeners.forEach((cb) =>
      cb({
        payload: {
          topic: "device.paired",
          data: { name: "Pixel 9", verificationCode: "123456" },
        },
      }),
    );
    expect(await screen.findByText("123456")).toBeInTheDocument();
  });

  it("disables remote access through the backend", async () => {
    render(<RemoteSection />);
    await screen.findByText("Pixel 9");
    fireEvent.click(screen.getByRole("switch", { name: "remote.enable" }));
    await waitFor(() =>
      expect(api.setConfig).toHaveBeenCalledWith(
        expect.objectContaining({ enabled: false }),
      ),
    );
  });

  it("ends a remote terminal", async () => {
    render(<RemoteSection />);
    await screen.findByText("build");
    fireEvent.click(screen.getByText("remote.terminals.end"));
    await waitFor(() => expect(api.killTerminal).toHaveBeenCalledWith("t1"));
  });
});
