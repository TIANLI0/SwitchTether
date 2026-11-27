import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RemoteStatusButton } from "@/components/remote/RemoteStatusButton";

const mocks = vi.hoisted(() => ({ status: vi.fn() }));

vi.mock("@/lib/api/remote", () => ({
  REMOTE_UI_EVENT: "switchtether-remote-event",
  remoteApi: { status: mocks.status },
}));
vi.mock("@/hooks/useTauriEvent", () => ({ useTauriEvent: () => {} }));

const status = (running: boolean, enabled = running) => ({
  config: { enabled, port: 47321, networkMode: "lan" },
  running,
  port: running ? 47321 : null,
  error: null,
  pairedDevices: 2,
  connections: [],
});

function renderButton(onOpen = vi.fn()) {
  const client = new QueryClient();
  render(
    <QueryClientProvider client={client}>
      <RemoteStatusButton onOpen={onOpen} />
    </QueryClientProvider>,
  );
  return onOpen;
}

describe("RemoteStatusButton", () => {
  beforeEach(() => mocks.status.mockReset());

  it("shows the paired-device count while running and opens Remote settings", async () => {
    mocks.status.mockResolvedValue(status(true));
    const onOpen = renderButton();
    const button = await screen.findByRole("button", {
      name: /remote\.home\.label.*remote\.home\.running/,
    });
    await userEvent.click(button);
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it("shows off when the service is turned off", async () => {
    mocks.status.mockResolvedValue(status(false));
    renderButton();
    expect(
      await screen.findByRole("button", { name: /remote\.home\.off/ }),
    ).toBeInTheDocument();
  });
});
