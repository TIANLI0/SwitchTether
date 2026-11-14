import { invoke } from "@tauri-apps/api/core";

/** SwitchTether Remote Core (desktop side). See docs/PROTOCOL.md. */
export type NetworkMode = "lan" | "tailscale_only" | "localhost_only" | "any";

export interface RemoteConfig {
  enabled: boolean;
  port: number;
  networkMode: NetworkMode;
  serverName?: string | null;
  /** Optional relay (docs/RELAY.md). */
  relayEnabled?: boolean;
  relayUrl?: string | null;
}

export interface RelayStatus {
  state: "off" | "connecting" | "connected" | "error";
  error?: string | null;
  tunnels: number;
}

export interface HostCandidate {
  address: string;
  interface: string;
  kind: string;
}

export interface RemoteConnection {
  connId: number;
  deviceId?: string | null;
  peer: string;
  connectedAtMs: number;
}

export interface RemoteStatus {
  config: RemoteConfig;
  running: boolean;
  port?: number | null;
  error?: string | null;
  fingerprint: string;
  fingerprintDisplay: string;
  serverName: string;
  secretBackend: "os_keychain" | "file" | "memory";
  hosts: HostCandidate[];
  connections: RemoteConnection[];
  pairedDevices: number;
  terminals: number;
  managedSessions: number;
  relay?: RelayStatus;
  /** A relay key is stored (the key itself is never returned). */
  relayKeySet?: boolean;
}

export interface PairingOffer {
  pairingId: string;
  uri: string;
  expiresAt: string;
  qrSvg: string;
  hosts: string[];
  port: number;
  fingerprintDisplay: string;
}

export interface RemoteDevice {
  id: string;
  name: string;
  platform: string;
  osVersion?: string | null;
  appVersion?: string | null;
  certFingerprint: string;
  fingerprintShort: string;
  stepUpEnabled: boolean;
  createdAtMs: number;
  lastSeenAtMs?: number | null;
  lastIp?: string | null;
  revokedAtMs?: number | null;
  online: boolean;
}

export interface RemoteTerminal {
  id: string;
  shell: string;
  cwd: string;
  title: string;
  cols: number;
  rows: number;
  createdAtMs: number;
  ownerDeviceId?: string | null;
  exited: boolean;
  exitCode?: number | null;
  /** Mirrored from the user's own terminal window (st-claude / st-codex). */
  external?: boolean;
}

export type TakeoverHook = "vscode" | "codex_desktop" | "cli";

export interface TakeoverStatus {
  shimAvailable: boolean;
  vscode: {
    available: boolean;
    enabled: boolean;
    editors: {
      editor: string;
      enabled: boolean;
      foreignWrapper?: string | null;
    }[];
  };
  codexDesktop: {
    available: boolean;
    enabled: boolean;
    foreignValue?: string | null;
  };
  cli: {
    available: boolean;
    enabled: boolean;
    binDir: string;
    pathAdded: boolean;
  };
}

export interface AuditEntry {
  id: number;
  tsMs: number;
  deviceId?: string | null;
  action: string;
  target?: string | null;
  outcome: string;
  detail?: string | null;
}

export interface RemoteUiEvent {
  topic: string;
  data: Record<string, unknown>;
}

export const REMOTE_UI_EVENT = "switchtether-remote-event";

export const remoteApi = {
  status: () => invoke<RemoteStatus>("remote_status"),
  setConfig: (config: RemoteConfig) =>
    invoke<RemoteStatus>("remote_set_config", { config }),
  createPairing: () => invoke<PairingOffer>("remote_create_pairing"),
  cancelPairing: (pairingId: string) =>
    invoke<void>("remote_cancel_pairing", { pairingId }),
  listDevices: () => invoke<RemoteDevice[]>("remote_list_devices"),
  revokeDevice: (deviceId: string) =>
    invoke<boolean>("remote_revoke_device", { deviceId }),
  forgetDevice: (deviceId: string) =>
    invoke<boolean>("remote_forget_device", { deviceId }),
  renameDevice: (deviceId: string, name: string) =>
    invoke<void>("remote_rename_device", { deviceId, name }),
  listTerminals: () => invoke<RemoteTerminal[]>("remote_list_terminals"),
  killTerminal: (id: string) => invoke<boolean>("remote_kill_terminal", { id }),
  auditLog: (limit = 50) => invoke<AuditEntry[]>("remote_audit_log", { limit }),
  setRelayKey: (key: string) =>
    invoke<RemoteStatus>("remote_set_relay_key", { key }),
  resetRelayToken: () => invoke<RemoteStatus>("remote_reset_relay_token"),
  takeoverStatus: () => invoke<TakeoverStatus>("remote_takeover_status"),
  setTakeover: (hook: TakeoverHook, enabled: boolean) =>
    invoke<{ report: Record<string, unknown>[]; status: TakeoverStatus }>(
      "remote_set_takeover",
      { hook, enabled },
    ),
};
