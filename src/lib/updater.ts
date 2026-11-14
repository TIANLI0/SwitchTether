import { getVersion } from "@tauri-apps/api/app";

export type UpdateChannel = "stable" | "beta";

export interface UpdateInfo {
  currentVersion: string;
  availableVersion: string;
  notes?: string;
  pubDate?: string;
}

export interface CheckOptions {
  timeout?: number;
  channel?: UpdateChannel;
}

export async function getCurrentVersion(): Promise<string> {
  try {
    return await getVersion();
  } catch {
    return "";
  }
}

interface InstallerCheck {
  supported: boolean;
  available?: boolean;
  installedVersion?: string | null;
  latestVersion?: string | null;
  notes?: string | null;
  error?: string;
}

async function checkWithInstaller(): Promise<
  | { status: "up-to-date" }
  | { status: "available"; info: UpdateInfo }
  | null
> {
  let r: InstallerCheck;
  try {
    const { invoke } = await import("@tauri-apps/api/core");
    r = await invoke<InstallerCheck>("st_update_check");
  } catch {
    return null;
  }
  if (!r.supported) return null;
  if (r.error) throw new Error(r.error);
  if (!r.available) return { status: "up-to-date" };
  return {
    status: "available",
    info: {
      currentVersion: r.installedVersion ?? (await getCurrentVersion()),
      availableVersion: r.latestVersion ?? "",
      notes: r.notes ?? undefined,
    },
  };
}

export async function checkForUpdate(
  opts: CheckOptions = {},
): Promise<
  { status: "up-to-date" } | { status: "available"; info: UpdateInfo }
> {
  // SwitchTether: installs made by FluentSetup update through its uninstall.exe.
  const st = await checkWithInstaller();
  if (st) return st;

  // 动态引入，避免在未安装插件时导致打包期问题
  const { check } = await import("@tauri-apps/plugin-updater");

  const currentVersion = await getCurrentVersion();
  const update = await check({ timeout: opts.timeout ?? 30000 } as any);

  if (!update) {
    return { status: "up-to-date" };
  }

  const info: UpdateInfo = {
    currentVersion,
    availableVersion: (update as any).version ?? "",
    notes: (update as any).notes,
    pubDate: (update as any).date,
  };

  return { status: "available", info };
}
