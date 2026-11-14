import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { listen } from "@tauri-apps/api/event";
import {
  Copy,
  QrCode,
  RefreshCw,
  ShieldCheck,
  Smartphone,
  SquareTerminal,
} from "lucide-react";
import { toast } from "@/lib/toast";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { SegmentedControl } from "@/components/ui/segmented-control";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  SettingsBlock,
  SettingsCard,
  SettingsRow,
  SettingsSwitchRow,
} from "@/components/settings/SettingsLayout";
import {
  REMOTE_UI_EVENT,
  remoteApi,
  type AuditEntry,
  type NetworkMode,
  type PairingOffer,
  type RemoteDevice,
  type RemoteStatus,
  type RemoteTerminal,
  type RemoteUiEvent,
  type TakeoverHook,
  type TakeoverStatus,
} from "@/lib/api/remote";

function formatTime(ms?: number | null): string {
  if (!ms) return "—";
  return new Date(ms).toLocaleString();
}

function useCountdown(expiresAt?: string): number {
  const [left, setLeft] = useState(0);
  useEffect(() => {
    if (!expiresAt) return;
    const end = new Date(expiresAt).getTime();
    const tick = () =>
      setLeft(Math.max(0, Math.round((end - Date.now()) / 1000)));
    tick();
    const id = window.setInterval(tick, 1000);
    return () => window.clearInterval(id);
  }, [expiresAt]);
  return left;
}

export function RemoteSection() {
  const { t } = useTranslation();
  const [status, setStatus] = useState<RemoteStatus | null>(null);
  const [devices, setDevices] = useState<RemoteDevice[]>([]);
  const [terminals, setTerminals] = useState<RemoteTerminal[]>([]);
  const [audit, setAudit] = useState<AuditEntry[]>([]);
  const [takeover, setTakeoverState] = useState<TakeoverStatus | null>(null);
  const [takeoverBusy, setTakeoverBusy] = useState<TakeoverHook | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [portDraft, setPortDraft] = useState("");
  const [nameDraft, setNameDraft] = useState("");
  const [relayDraft, setRelayDraft] = useState<string | null>(null);
  const [relayKeyDraft, setRelayKeyDraft] = useState("");
  const [pairing, setPairing] = useState<PairingOffer | null>(null);
  const [paired, setPaired] = useState<{ name: string; code: string } | null>(
    null,
  );
  const [revokeTarget, setRevokeTarget] = useState<RemoteDevice | null>(null);
  const pairingRef = useRef<PairingOffer | null>(null);
  pairingRef.current = pairing;
  const secondsLeft = useCountdown(pairing?.expiresAt);

  const refresh = useCallback(async () => {
    try {
      const [s, d, term, a] = await Promise.all([
        remoteApi.status(),
        remoteApi.listDevices(),
        remoteApi.listTerminals(),
        remoteApi.auditLog(40),
      ]);
      setStatus(s);
      setDevices(d);
      setTerminals(term.filter((x) => !x.exited));
      setAudit(a);
      setLoadError(null);
      remoteApi
        .takeoverStatus()
        .then(setTakeoverState)
        .catch(() => setTakeoverState(null));
      setPortDraft((p) => p || String(s.config.port));
      setNameDraft((n) => n || s.config.serverName || s.serverName);
    } catch (e) {
      setLoadError(String(e));
    }
  }, []);

  useEffect(() => {
    void refresh();
    const id = window.setInterval(() => void refresh(), 5000);
    let unlisten: (() => void) | undefined;
    void listen<RemoteUiEvent>(REMOTE_UI_EVENT, (event) => {
      const { topic, data } = event.payload;
      if (topic === "device.paired" && pairingRef.current) {
        setPaired({
          name: String(data.name ?? ""),
          code: String(data.verificationCode ?? ""),
        });
        setPairing(null);
      }
      void refresh();
    }).then((u) => {
      unlisten = u;
    });
    return () => {
      window.clearInterval(id);
      unlisten?.();
    };
  }, [refresh]);

  const save = async (patch: Partial<RemoteStatus["config"]>) => {
    if (!status) return;
    setBusy(true);
    try {
      const next = await remoteApi.setConfig({ ...status.config, ...patch });
      setStatus(next);
      if (next.error) toast.error(next.error);
    } catch (e) {
      toast.error(String(e));
    } finally {
      setBusy(false);
    }
  };

  const toggleTakeover = async (hook: TakeoverHook, enabled: boolean) => {
    setTakeoverBusy(hook);
    try {
      const res = await remoteApi.setTakeover(hook, enabled);
      setTakeoverState(res.status);
      const skipped = res.report.filter((r) => r.result === "skipped");
      if (skipped.length > 0) {
        toast.warning(
          t("remote.takeover.skipped", {
            editors: skipped.map((r) => String(r.editor)).join(", "),
          }),
        );
      }
      toast.success(
        enabled
          ? t(`remote.takeover.${hook}.enabledToast`)
          : t("remote.takeover.disabledToast"),
      );
    } catch (e) {
      toast.error(String(e));
    } finally {
      setTakeoverBusy(null);
    }
  };

  const startPairing = async () => {
    try {
      setPaired(null);
      setPairing(await remoteApi.createPairing());
    } catch (e) {
      toast.error(String(e));
    }
  };

  const closePairing = () => {
    if (pairing) void remoteApi.cancelPairing(pairing.pairingId);
    setPairing(null);
  };

  const confirmRevoke = async () => {
    if (!revokeTarget) return;
    try {
      await remoteApi.revokeDevice(revokeTarget.id);
      toast.success(t("remote.devices.revoked", { name: revokeTarget.name }));
    } catch (e) {
      toast.error(String(e));
    }
    setRevokeTarget(null);
    void refresh();
  };

  if (loadError && !status) {
    return (
      <SettingsBlock title={t("remote.title")}>
        <div className="rounded-panel border border-border bg-surface p-5 text-body text-fg-2">
          {t("remote.initializing")}
          <div className="mt-2 text-caption text-fg-3">{loadError}</div>
        </div>
      </SettingsBlock>
    );
  }
  if (!status) return null;

  const modes: { value: NetworkMode; label: string }[] = [
    { value: "lan", label: t("remote.network.lan") },
    { value: "tailscale_only", label: t("remote.network.tailscale") },
    { value: "localhost_only", label: t("remote.network.localhost") },
    { value: "any", label: t("remote.network.any") },
  ];
  const active = devices.filter((d) => !d.revokedAtMs);
  const revoked = devices.filter((d) => d.revokedAtMs);

  return (
    <>
      <SettingsBlock
        title={t("remote.title")}
        help={{ title: t("remote.title"), body: t("remote.help") }}
        actions={
          <Button
            variant="neutral"
            size="compact"
            onClick={() => void refresh()}
          >
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            {t("remote.refresh")}
          </Button>
        }
      >
        <SettingsCard>
          <SettingsSwitchRow
            label={t("remote.enable")}
            description={
              status.running
                ? t("remote.runningOn", { port: status.port })
                : status.error
                  ? t("remote.failed", { error: status.error })
                  : t("remote.stopped")
            }
            checked={status.config.enabled}
            disabled={busy}
            onCheckedChange={(enabled) => void save({ enabled })}
          />
          <SettingsRow
            label={t("remote.network.label")}
            help={{
              title: t("remote.network.label"),
              body: t("remote.network.help"),
            }}
            control={
              <SegmentedControl
                size="sm"
                aria-label={t("remote.network.label")}
                items={modes}
                value={status.config.networkMode}
                onValueChange={(networkMode) => void save({ networkMode })}
              />
            }
          />
          {status.config.networkMode === "any" && (
            <div className="px-4 py-2 text-caption text-danger">
              {t("remote.network.anyWarning")}
            </div>
          )}
          <SettingsRow
            label={t("remote.port")}
            control={
              <div className="flex items-center gap-2">
                <Input
                  className="h-control-sm w-24"
                  inputMode="numeric"
                  value={portDraft}
                  onChange={(e) =>
                    setPortDraft(e.target.value.replace(/[^\d]/g, ""))
                  }
                  aria-label={t("remote.port")}
                />
                <Button
                  variant="neutral"
                  size="compact"
                  disabled={
                    busy ||
                    Number(portDraft) === status.config.port ||
                    Number(portDraft) < 1024 ||
                    Number(portDraft) > 65535
                  }
                  onClick={() => void save({ port: Number(portDraft) })}
                >
                  {t("remote.apply")}
                </Button>
              </div>
            }
          />
          <SettingsRow
            label={t("remote.desktopName")}
            control={
              <div className="flex items-center gap-2">
                <Input
                  className="h-control-sm w-48"
                  value={nameDraft}
                  maxLength={64}
                  onChange={(e) => setNameDraft(e.target.value)}
                  aria-label={t("remote.desktopName")}
                />
                <Button
                  variant="neutral"
                  size="compact"
                  disabled={
                    busy || !nameDraft.trim() || nameDraft === status.serverName
                  }
                  onClick={() => void save({ serverName: nameDraft.trim() })}
                >
                  {t("remote.apply")}
                </Button>
              </div>
            }
          />
          <SettingsRow
            label={t("remote.fingerprint")}
            help={{
              title: t("remote.fingerprint"),
              body: t("remote.fingerprintHelp"),
            }}
            description={
              <span className="font-mono">{status.fingerprintDisplay}</span>
            }
          />
          <SettingsRow
            label={t("remote.keyStorage")}
            description={t(`remote.secretBackend.${status.secretBackend}`)}
          />
          <SettingsRow
            label={t("remote.addresses")}
            description={
              status.hosts.length
                ? status.hosts
                    .map((h) => `${h.address} (${h.kind})`)
                    .join(" · ")
                : t("remote.noAddresses")
            }
          />
          <SettingsRow
            label={t("remote.pair.title")}
            description={t("remote.pair.description")}
            control={
              <Button
                variant="solid"
                size="compact"
                disabled={!status.running}
                onClick={() => void startPairing()}
              >
                <QrCode className="mr-1.5 h-3.5 w-3.5" />
                {t("remote.pair.button")}
              </Button>
            }
          />
        </SettingsCard>
      </SettingsBlock>

      {status && (
        <SettingsBlock
          title={t("remote.relay.title")}
          help={{
            title: t("remote.relay.title"),
            body: t("remote.relay.help"),
          }}
        >
          <SettingsCard>
            <SettingsRow
              label={t("remote.relay.url")}
              description={t("remote.relay.urlHint")}
              control={
                <div className="flex items-center gap-2">
                  <Input
                    className="h-control-sm w-64 font-mono"
                    placeholder="wss://relay.example.com/v1/tunnel"
                    value={relayDraft ?? status.config.relayUrl ?? ""}
                    onChange={(e) => setRelayDraft(e.target.value)}
                    aria-label={t("remote.relay.url")}
                  />
                  <Button
                    variant="neutral"
                    size="compact"
                    disabled={
                      busy ||
                      relayDraft === null ||
                      relayDraft.trim() === (status.config.relayUrl ?? "")
                    }
                    onClick={() =>
                      void save({ relayUrl: relayDraft?.trim() || null }).then(
                        () => setRelayDraft(null),
                      )
                    }
                  >
                    {t("remote.apply")}
                  </Button>
                </div>
              }
            />
            <SettingsRow
              label={t("remote.relay.key")}
              description={
                status.relayKeySet
                  ? t("remote.relay.keySet")
                  : t("remote.relay.keyHint")
              }
              control={
                <div className="flex items-center gap-2">
                  <Input
                    type="password"
                    autoComplete="off"
                    className="h-control-sm w-48 font-mono"
                    placeholder={
                      status.relayKeySet
                        ? "••••••••"
                        : t("remote.relay.keyPlaceholder")
                    }
                    value={relayKeyDraft}
                    onChange={(e) => setRelayKeyDraft(e.target.value)}
                    aria-label={t("remote.relay.key")}
                  />
                  <Button
                    variant="neutral"
                    size="compact"
                    disabled={busy || !relayKeyDraft.trim()}
                    onClick={() =>
                      void remoteApi
                        .setRelayKey(relayKeyDraft)
                        .then((s) => {
                          setStatus(s);
                          setRelayKeyDraft("");
                          toast.success(t("remote.relay.keySaved"));
                        })
                        .catch((e) => toast.error(String(e)))
                    }
                  >
                    {t("remote.apply")}
                  </Button>
                </div>
              }
            />
            <SettingsSwitchRow
              label={t("remote.relay.enable")}
              description={
                status.relay && status.config.relayEnabled
                  ? t(`remote.relay.state.${status.relay.state}`, {
                      error: status.relay.error ?? "",
                    })
                  : t("remote.relay.enableHint")
              }
              checked={Boolean(status.config.relayEnabled)}
              disabled={
                busy ||
                !status.config.relayUrl ||
                (!status.relayKeySet && !status.config.relayEnabled)
              }
              onCheckedChange={(v) => void save({ relayEnabled: v })}
            />
            {status.config.relayEnabled && (
              <SettingsRow
                label={t("remote.relay.token")}
                description={t("remote.relay.tokenHint")}
                control={
                  <Button
                    variant="neutral"
                    size="compact"
                    disabled={busy}
                    onClick={() =>
                      void remoteApi
                        .resetRelayToken()
                        .then((s) => {
                          setStatus(s);
                          toast.success(t("remote.relay.tokenReset"));
                        })
                        .catch((e) => toast.error(String(e)))
                    }
                  >
                    {t("remote.relay.resetToken")}
                  </Button>
                }
              />
            )}
          </SettingsCard>
        </SettingsBlock>
      )}

      <SettingsBlock title={t("remote.devices.title")}>
        <SettingsCard>
          {active.length === 0 && (
            <div className="px-4 py-4 text-body text-fg-2">
              {t("remote.devices.empty")}
            </div>
          )}
          {active.map((d) => (
            <SettingsRow
              key={d.id}
              label={
                <span className="flex items-center gap-2">
                  <Smartphone className="h-4 w-4 text-fg-2" />
                  {d.name}
                  <span
                    className={`inline-block h-2 w-2 rounded-full ${d.online ? "bg-[var(--color-success,#22c55e)]" : "bg-fg-3"}`}
                    aria-label={
                      d.online
                        ? t("remote.devices.online")
                        : t("remote.devices.offline")
                    }
                  />
                  {d.stepUpEnabled && (
                    <ShieldCheck
                      className="h-3.5 w-3.5 text-fg-2"
                      aria-label={t("remote.devices.stepUp")}
                    />
                  )}
                </span>
              }
              description={
                <>
                  {[d.platform, d.osVersion, d.appVersion && `v${d.appVersion}`]
                    .filter(Boolean)
                    .join(" · ")}
                  {" · "}
                  {t("remote.devices.lastSeen", {
                    time: formatTime(d.lastSeenAtMs),
                  })}
                  {" · "}
                  <span className="font-mono">{d.fingerprintShort}</span>
                </>
              }
              control={
                <Button
                  variant="neutral"
                  size="compact"
                  onClick={() => setRevokeTarget(d)}
                >
                  {t("remote.devices.revoke")}
                </Button>
              }
            />
          ))}
          {revoked.map((d) => (
            <SettingsRow
              key={d.id}
              label={<span className="text-fg-3 line-through">{d.name}</span>}
              description={t("remote.devices.revokedAt", {
                time: formatTime(d.revokedAtMs),
              })}
              control={
                <Button
                  variant="ghost"
                  size="compact"
                  onClick={() =>
                    void remoteApi.forgetDevice(d.id).then(refresh)
                  }
                >
                  {t("remote.devices.forget")}
                </Button>
              }
            />
          ))}
        </SettingsCard>
      </SettingsBlock>

      {takeover && (
        <SettingsBlock
          title={t("remote.takeover.title")}
          help={{
            title: t("remote.takeover.title"),
            body: t("remote.takeover.help"),
          }}
        >
          <SettingsCard>
            {!takeover.shimAvailable && (
              <div className="px-4 py-3 text-body text-fg-2">
                {t("remote.takeover.noShim")}
              </div>
            )}
            <SettingsSwitchRow
              label={t("remote.takeover.vscode.label")}
              description={
                takeover.vscode.available
                  ? t("remote.takeover.vscode.description", {
                      editors: takeover.vscode.editors
                        .map((e) => e.editor)
                        .join(", "),
                    })
                  : t("remote.takeover.vscode.unavailable")
              }
              checked={takeover.vscode.enabled}
              disabled={
                !takeover.vscode.available ||
                (!takeover.shimAvailable && !takeover.vscode.enabled) ||
                takeoverBusy !== null
              }
              onCheckedChange={(v) => void toggleTakeover("vscode", v)}
            />
            <SettingsSwitchRow
              label={t("remote.takeover.codex_desktop.label")}
              description={
                takeover.codexDesktop.foreignValue
                  ? t("remote.takeover.codex_desktop.foreign", {
                      value: takeover.codexDesktop.foreignValue,
                    })
                  : takeover.codexDesktop.available
                    ? t("remote.takeover.codex_desktop.description")
                    : t("remote.takeover.codex_desktop.unavailable")
              }
              checked={takeover.codexDesktop.enabled}
              disabled={
                !takeover.codexDesktop.available ||
                Boolean(takeover.codexDesktop.foreignValue) ||
                (!takeover.shimAvailable && !takeover.codexDesktop.enabled) ||
                takeoverBusy !== null
              }
              onCheckedChange={(v) => void toggleTakeover("codex_desktop", v)}
            />
            <SettingsSwitchRow
              label={t("remote.takeover.cli.label")}
              description={t("remote.takeover.cli.description", {
                dir: takeover.cli.binDir,
              })}
              checked={takeover.cli.enabled}
              disabled={
                (!takeover.shimAvailable && !takeover.cli.enabled) ||
                takeoverBusy !== null
              }
              onCheckedChange={(v) => void toggleTakeover("cli", v)}
            />
          </SettingsCard>
        </SettingsBlock>
      )}

      <SettingsBlock
        title={t("remote.terminals.title")}
        help={{
          title: t("remote.terminals.title"),
          body: t("remote.terminals.help"),
        }}
      >
        <SettingsCard>
          {terminals.length === 0 && (
            <div className="px-4 py-4 text-body text-fg-2">
              {t("remote.terminals.empty")}
            </div>
          )}
          {terminals.map((term) => (
            <SettingsRow
              key={term.id}
              label={
                <span className="flex items-center gap-2">
                  <SquareTerminal className="h-4 w-4 text-fg-2" />
                  {term.title}
                </span>
              }
              description={`${term.shell} · ${term.cwd} · ${formatTime(term.createdAtMs)}`}
              control={
                term.external ? (
                  <span className="text-caption text-fg-2">
                    {t("remote.terminals.external")}
                  </span>
                ) : (
                  <Button
                    variant="neutral"
                    size="compact"
                    onClick={() =>
                      void remoteApi.killTerminal(term.id).then(refresh)
                    }
                  >
                    {t("remote.terminals.end")}
                  </Button>
                )
              }
            />
          ))}
        </SettingsCard>
      </SettingsBlock>

      <SettingsBlock
        title={t("remote.audit.title")}
        help={{ title: t("remote.audit.title"), body: t("remote.audit.help") }}
      >
        <div className="max-h-72 overflow-auto rounded-panel border border-border bg-surface">
          {audit.length === 0 ? (
            <div className="px-4 py-4 text-body text-fg-2">
              {t("remote.audit.empty")}
            </div>
          ) : (
            <table className="w-full text-caption">
              <tbody>
                {audit.map((a) => (
                  <tr
                    key={a.id}
                    className="border-b border-border last:border-0"
                  >
                    <td className="whitespace-nowrap px-3 py-1.5 text-fg-3">
                      {formatTime(a.tsMs)}
                    </td>
                    <td className="px-3 py-1.5 font-mono text-fg-1">
                      {a.action}
                    </td>
                    <td
                      className="max-w-[260px] truncate px-3 py-1.5 text-fg-2"
                      title={a.target ?? ""}
                    >
                      {a.target}
                    </td>
                    <td className="px-3 py-1.5 text-fg-2">{a.outcome}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </SettingsBlock>

      <Dialog
        open={!!pairing || !!paired}
        onOpenChange={(open) => !open && (closePairing(), setPaired(null))}
      >
        <DialogContent className={paired ? "max-w-md" : "max-w-2xl"}>
          <DialogHeader>
            <DialogTitle>
              {paired ? t("remote.pair.success") : t("remote.pair.title")}
            </DialogTitle>
          </DialogHeader>
          {pairing && (
            <div className="flex flex-col gap-5 sm:flex-row sm:items-start">
              <div className="flex shrink-0 flex-col items-center gap-2">
                <div className="relative h-[232px] w-[232px] overflow-hidden rounded-panel border bg-white p-3 shadow-sm">
                  {/* QR is generated locally by the desktop backend (qrcode crate). */}
                  <div
                    data-testid="pair-qr"
                    className={`h-full w-full [&>svg]:h-full [&>svg]:w-full ${
                      secondsLeft > 0 ? "" : "opacity-15 blur-[2px]"
                    }`}
                    dangerouslySetInnerHTML={{ __html: pairing.qrSvg }}
                  />
                  {secondsLeft === 0 && (
                    <div className="absolute inset-0 flex flex-col items-center justify-center gap-2">
                      <span className="text-body font-medium text-neutral-800">
                        {t("remote.pair.expired")}
                      </span>
                      <Button
                        variant="solid"
                        size="compact"
                        onClick={() => void startPairing()}
                      >
                        <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
                        {t("remote.pair.newCode")}
                      </Button>
                    </div>
                  )}
                </div>
                <div className="h-1 w-[232px] overflow-hidden rounded-full bg-selected">
                  <div
                    className="h-full rounded-full bg-action transition-[width] duration-1000 ease-linear"
                    style={{
                      width: `${Math.max(0, Math.min(100, (secondsLeft / 300) * 100))}%`,
                    }}
                  />
                </div>
                <div className="text-caption tabular-nums text-fg-2">
                  {secondsLeft > 0
                    ? t("remote.pair.expiresIn", { seconds: secondsLeft })
                    : t("remote.pair.expired")}
                </div>
              </div>

              <div className="min-w-0 flex-1 space-y-4">
                <ol className="space-y-2.5">
                  {(["step1", "step2", "step3"] as const).map((k, i) => (
                    <li key={k} className="flex items-start gap-2.5">
                      <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-action-soft text-caption font-semibold text-action-text">
                        {i + 1}
                      </span>
                      <span className="text-body text-fg-1">
                        {t(`remote.pair.${k}`)}
                      </span>
                    </li>
                  ))}
                </ol>

                <dl className="space-y-2 rounded-panel bg-subtle px-3 py-2.5 text-caption">
                  <div className="flex gap-3">
                    <dt className="w-16 shrink-0 text-fg-3">
                      {t("remote.pair.computer")}
                    </dt>
                    <dd className="min-w-0 truncate text-fg-1">
                      {status?.config.serverName || status?.serverName}
                    </dd>
                  </div>
                  <div className="flex gap-3">
                    <dt className="w-16 shrink-0 text-fg-3">
                      {t("remote.pair.addresses")}
                    </dt>
                    <dd className="flex min-w-0 flex-wrap gap-1">
                      {pairing.hosts.map((h) => (
                        <span
                          key={h}
                          className="rounded bg-selected px-1.5 py-0.5 font-mono text-fg-2"
                        >
                          {h}:{pairing.port}
                        </span>
                      ))}
                    </dd>
                  </div>
                  <div className="flex gap-3">
                    <dt className="w-16 shrink-0 text-fg-3">
                      {t("remote.fingerprint")}
                    </dt>
                    <dd className="min-w-0 break-all font-mono text-fg-2">
                      {pairing.fingerprintDisplay}
                    </dd>
                  </div>
                </dl>

                <Button
                  variant="neutral"
                  size="compact"
                  onClick={() => {
                    void navigator.clipboard.writeText(pairing.uri);
                    toast.success(t("remote.pair.copied"));
                  }}
                >
                  <Copy className="mr-1.5 h-3.5 w-3.5" />
                  {t("remote.pair.copyLink")}
                </Button>
                <p className="flex items-start gap-1.5 text-caption text-fg-3">
                  <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                  {t("remote.pair.privacy")}
                </p>
              </div>
            </div>
          )}
          {paired && (
            <div className="flex flex-col items-center gap-3 py-2 text-center">
              <span className="flex h-12 w-12 items-center justify-center rounded-full bg-success-soft text-success-text">
                <ShieldCheck className="h-6 w-6" />
              </span>
              <p className="text-body text-fg-1">
                {t("remote.pair.pairedWith", { name: paired.name })}
              </p>
              <p className="text-caption text-fg-2">
                {t("remote.pair.verify")}
              </p>
              <div className="flex gap-1.5" data-testid="pair-code">
                {paired.code.split("").map((c, i) => (
                  <span
                    key={i}
                    className="flex h-11 w-9 items-center justify-center rounded-panel border bg-subtle font-mono text-section font-semibold text-fg-1"
                  >
                    {c}
                  </span>
                ))}
              </div>
            </div>
          )}
          <DialogFooter>
            <Button
              variant={paired ? "solid" : "neutral"}
              size="compact"
              onClick={() => (closePairing(), setPaired(null))}
            >
              {paired ? t("remote.pair.done") : t("remote.close")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={!!revokeTarget}
        onOpenChange={(open) => !open && setRevokeTarget(null)}
      >
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>
              {t("remote.devices.revokeTitle", {
                name: revokeTarget?.name ?? "",
              })}
            </DialogTitle>
          </DialogHeader>
          <p className="text-body text-fg-2">
            {t("remote.devices.revokeBody")}
          </p>
          <DialogFooter>
            <Button
              variant="neutral"
              size="compact"
              onClick={() => setRevokeTarget(null)}
            >
              {t("remote.cancel")}
            </Button>
            <Button
              variant="destructive"
              size="compact"
              onClick={() => void confirmRevoke()}
            >
              {t("remote.devices.revoke")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
