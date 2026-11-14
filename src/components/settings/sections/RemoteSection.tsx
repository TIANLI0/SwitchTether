import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { listen } from "@tauri-apps/api/event";
import { Copy, QrCode, RefreshCw, ShieldCheck, Smartphone, SquareTerminal } from "lucide-react";
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
    const tick = () => setLeft(Math.max(0, Math.round((end - Date.now()) / 1000)));
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
  const [loadError, setLoadError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [portDraft, setPortDraft] = useState("");
  const [nameDraft, setNameDraft] = useState("");
  const [pairing, setPairing] = useState<PairingOffer | null>(null);
  const [paired, setPaired] = useState<{ name: string; code: string } | null>(null);
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
          <Button variant="neutral" size="compact" onClick={() => void refresh()}>
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
            help={{ title: t("remote.network.label"), body: t("remote.network.help") }}
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
            <div className="px-4 py-2 text-caption text-danger">{t("remote.network.anyWarning")}</div>
          )}
          <SettingsRow
            label={t("remote.port")}
            control={
              <div className="flex items-center gap-2">
                <Input
                  className="h-control-sm w-24"
                  inputMode="numeric"
                  value={portDraft}
                  onChange={(e) => setPortDraft(e.target.value.replace(/[^\d]/g, ""))}
                  aria-label={t("remote.port")}
                />
                <Button
                  variant="neutral"
                  size="compact"
                  disabled={busy || Number(portDraft) === status.config.port || Number(portDraft) < 1024 || Number(portDraft) > 65535}
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
                  disabled={busy || !nameDraft.trim() || nameDraft === status.serverName}
                  onClick={() => void save({ serverName: nameDraft.trim() })}
                >
                  {t("remote.apply")}
                </Button>
              </div>
            }
          />
          <SettingsRow
            label={t("remote.fingerprint")}
            help={{ title: t("remote.fingerprint"), body: t("remote.fingerprintHelp") }}
            description={<span className="font-mono">{status.fingerprintDisplay}</span>}
          />
          <SettingsRow
            label={t("remote.keyStorage")}
            description={t(`remote.secretBackend.${status.secretBackend}`)}
          />
          <SettingsRow
            label={t("remote.addresses")}
            description={
              status.hosts.length
                ? status.hosts.map((h) => `${h.address} (${h.kind})`).join(" · ")
                : t("remote.noAddresses")
            }
          />
          <SettingsRow
            label={t("remote.pair.title")}
            description={t("remote.pair.description")}
            control={
              <Button variant="solid" size="compact" disabled={!status.running} onClick={() => void startPairing()}>
                <QrCode className="mr-1.5 h-3.5 w-3.5" />
                {t("remote.pair.button")}
              </Button>
            }
          />
        </SettingsCard>
      </SettingsBlock>

      <SettingsBlock title={t("remote.devices.title")}>
        <SettingsCard>
          {active.length === 0 && (
            <div className="px-4 py-4 text-body text-fg-2">{t("remote.devices.empty")}</div>
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
                    aria-label={d.online ? t("remote.devices.online") : t("remote.devices.offline")}
                  />
                  {d.stepUpEnabled && (
                    <ShieldCheck className="h-3.5 w-3.5 text-fg-2" aria-label={t("remote.devices.stepUp")} />
                  )}
                </span>
              }
              description={
                <>
                  {[d.platform, d.osVersion, d.appVersion && `v${d.appVersion}`].filter(Boolean).join(" · ")}
                  {" · "}
                  {t("remote.devices.lastSeen", { time: formatTime(d.lastSeenAtMs) })}
                  {" · "}
                  <span className="font-mono">{d.fingerprintShort}</span>
                </>
              }
              control={
                <Button variant="neutral" size="compact" onClick={() => setRevokeTarget(d)}>
                  {t("remote.devices.revoke")}
                </Button>
              }
            />
          ))}
          {revoked.map((d) => (
            <SettingsRow
              key={d.id}
              label={<span className="text-fg-3 line-through">{d.name}</span>}
              description={t("remote.devices.revokedAt", { time: formatTime(d.revokedAtMs) })}
              control={
                <Button
                  variant="ghost"
                  size="compact"
                  onClick={() => void remoteApi.forgetDevice(d.id).then(refresh)}
                >
                  {t("remote.devices.forget")}
                </Button>
              }
            />
          ))}
        </SettingsCard>
      </SettingsBlock>

      <SettingsBlock title={t("remote.terminals.title")} help={{ title: t("remote.terminals.title"), body: t("remote.terminals.help") }}>
        <SettingsCard>
          {terminals.length === 0 && (
            <div className="px-4 py-4 text-body text-fg-2">{t("remote.terminals.empty")}</div>
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
                <Button
                  variant="neutral"
                  size="compact"
                  onClick={() => void remoteApi.killTerminal(term.id).then(refresh)}
                >
                  {t("remote.terminals.end")}
                </Button>
              }
            />
          ))}
        </SettingsCard>
      </SettingsBlock>

      <SettingsBlock title={t("remote.audit.title")} help={{ title: t("remote.audit.title"), body: t("remote.audit.help") }}>
        <div className="max-h-72 overflow-auto rounded-panel border border-border bg-surface">
          {audit.length === 0 ? (
            <div className="px-4 py-4 text-body text-fg-2">{t("remote.audit.empty")}</div>
          ) : (
            <table className="w-full text-caption">
              <tbody>
                {audit.map((a) => (
                  <tr key={a.id} className="border-b border-border last:border-0">
                    <td className="whitespace-nowrap px-3 py-1.5 text-fg-3">{formatTime(a.tsMs)}</td>
                    <td className="px-3 py-1.5 font-mono text-fg-1">{a.action}</td>
                    <td className="max-w-[260px] truncate px-3 py-1.5 text-fg-2" title={a.target ?? ""}>
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

      <Dialog open={!!pairing || !!paired} onOpenChange={(open) => !open && (closePairing(), setPaired(null))}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{paired ? t("remote.pair.success") : t("remote.pair.title")}</DialogTitle>
          </DialogHeader>
          {pairing && (
            <div className="space-y-3">
              <p className="text-body text-fg-2">{t("remote.pair.scan")}</p>
              {/* QR is generated locally by the desktop backend (qrcode crate). */}
              <div
                className="mx-auto w-fit rounded-panel bg-white p-2"
                dangerouslySetInnerHTML={{ __html: pairing.qrSvg }}
              />
              <div className="text-center text-caption text-fg-2">
                {secondsLeft > 0
                  ? t("remote.pair.expiresIn", { seconds: secondsLeft })
                  : t("remote.pair.expired")}
              </div>
              <div className="text-caption text-fg-3">
                {t("remote.fingerprint")}: <span className="font-mono">{pairing.fingerprintDisplay}</span>
              </div>
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
              <p className="text-caption text-fg-3">{t("remote.pair.privacy")}</p>
            </div>
          )}
          {paired && (
            <div className="space-y-2 text-body text-fg-1">
              <p>{t("remote.pair.pairedWith", { name: paired.name })}</p>
              <p>
                {t("remote.pair.verify")}{" "}
                <span className="font-mono text-section tracking-widest">{paired.code}</span>
              </p>
            </div>
          )}
          <DialogFooter>
            {pairing && secondsLeft === 0 && (
              <Button variant="solid" size="compact" onClick={() => void startPairing()}>
                {t("remote.pair.newCode")}
              </Button>
            )}
            <Button variant="neutral" size="compact" onClick={() => (closePairing(), setPaired(null))}>
              {t("remote.close")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!revokeTarget} onOpenChange={(open) => !open && setRevokeTarget(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{t("remote.devices.revokeTitle", { name: revokeTarget?.name ?? "" })}</DialogTitle>
          </DialogHeader>
          <p className="text-body text-fg-2">{t("remote.devices.revokeBody")}</p>
          <DialogFooter>
            <Button variant="neutral" size="compact" onClick={() => setRevokeTarget(null)}>
              {t("remote.cancel")}
            </Button>
            <Button variant="destructive" size="compact" onClick={() => void confirmRevoke()}>
              {t("remote.devices.revoke")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
