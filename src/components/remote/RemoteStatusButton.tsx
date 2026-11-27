import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Smartphone } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { HoverTip } from "@/components/ui/hover-tip";
import { useTauriEvent } from "@/hooks/useTauriEvent";
import { remoteApi, REMOTE_UI_EVENT } from "@/lib/api/remote";
import { cn } from "@/lib/utils";

const REMOTE_STATUS_KEY = ["switchtether", "remote", "status"] as const;

/**
 * SwitchTether: compact Remote entry for the home page header. Shows whether the
 * Remote service is running and how many phones are paired; opens Settings → Remote.
 */
export function RemoteStatusButton({ onOpen }: { onOpen: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: status, isError } = useQuery({
    queryKey: REMOTE_STATUS_KEY,
    queryFn: () => remoteApi.status(),
    // The service may still be initialising right after launch.
    retry: 10,
    retryDelay: 2000,
    refetchInterval: 15_000,
  });
  useTauriEvent(REMOTE_UI_EVENT, () => {
    void queryClient.invalidateQueries({ queryKey: REMOTE_STATUS_KEY });
  });

  const state = !status
    ? isError
      ? "unavailable"
      : "starting"
    : status.running
      ? "running"
      : status.config.enabled && status.error
        ? "error"
        : "off";

  const label =
    state === "running"
      ? t("remote.home.running", { count: status?.pairedDevices ?? 0 })
      : t(`remote.home.${state}`);
  const tip =
    state === "running"
      ? t("remote.home.tipRunning", {
          port: status?.port ?? "",
          count: status?.pairedDevices ?? 0,
          connections: status?.connections.length ?? 0,
        })
      : state === "error"
        ? t("remote.failed", { error: status?.error ?? "" })
        : t(
            state === "off"
              ? "remote.home.tipOff"
              : state === "starting"
                ? "remote.home.tipStarting"
                : "remote.home.tipUnavailable",
          );

  return (
    <HoverTip content={tip}>
      <Button
        variant="quiet"
        size="regular"
        aria-label={`${t("remote.home.label")}: ${label}`}
        onClick={onOpen}
      >
        <span className="relative flex">
          <Smartphone className="h-4 w-4" />
          <span
            aria-hidden
            className={cn(
              "absolute -end-0.5 -top-0.5 h-1.5 w-1.5 rounded-full",
              state === "running"
                ? "bg-success"
                : state === "error"
                  ? "bg-danger"
                  : "bg-fg-3",
            )}
          />
        </span>
        {label}
      </Button>
    </HoverTip>
  );
}
