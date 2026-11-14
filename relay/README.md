# SwitchTether Relay

[中文](README.zh-CN.md)

An optional, self-hosted relay that lets the SwitchTether Android app reach its desktop when
the two are not on the same network and no VPN (e.g. Tailscale) is available. It is a dumb
byte pipe: the phone ↔ desktop **mutual-TLS session runs end-to-end through it**, with the
same certificate pinning as a direct connection.

```
phone ──TCP/WS──► relay ◄──TCP/WS── desktop (control connection, always open)
   └───── TLS 1.3 + client cert, pinned (end-to-end, unchanged) ─────┘
```

The protocol is specified in [docs/RELAY.md](../docs/RELAY.md). This directory contains the
reference implementation (Go, one dependency: `github.com/coder/websocket`), a Docker image,
and a Cloudflare Containers deployment.

## Security model

- **No plaintext.** The relay only splices bytes. It never terminates the inner TLS, holds no
  keys, has no accounts and stores nothing.
- **Desktops prove their identity.** A desktop registers under its certificate fingerprint and
  must sign a fresh challenge with that certificate's ECDSA P-256 key, so nobody else can
  register under its name.
- **The desktop decides.** Each phone connection is announced to the desktop with the relay
  access token the phone presented (from the pairing QR); the desktop rejects wrong tokens.
  Even with a valid token, the phone still needs a paired client certificate for the TLS
  handshake.
- **What the operator sees:** desktop fingerprints, IP addresses, timing and volume, and the
  relay access token (also visible on the path when you use `tcp://` or `ws://` rather than
  `wss://`). Not: any request, response, terminal byte, file or key. Logs never contain
  tokens; fingerprints are shortened to 8 hex characters.
- **No other endpoints.** HTTP serves only `/v1/tunnel` (WebSocket) and `GET /healthz`; there
  are no stats or listings.

See [docs/RELAY.md §6](../docs/RELAY.md#6-security-notes).

## Run with Docker

```bash
docker build -t switchtether-relay relay/
docker run -d --name switchtether-relay --restart unless-stopped \
  -p 47700:47700 -p 8080:8080 switchtether-relay
curl http://localhost:8080/healthz   # ok
```

Or with Compose (both ports, limits spelled out, read-only, no capabilities):

```bash
docker compose -f relay/docker-compose.yml up -d --build
```

The image is a static binary on `gcr.io/distroless/static-debian13:nonroot` (runs as uid
65532). Its `HEALTHCHECK` runs `/switchtether-relay -healthcheck`.

**TLS for WebSocket (`wss://`).** Either terminate TLS in a reverse proxy in front of port 8080
(Caddy, nginx, Traefik; it must pass WebSocket upgrades on `/v1/tunnel`), or let the relay do
it: mount a certificate and set `SWITCHTETHER_RELAY_TLS_CERT` / `SWITCHTETHER_RELAY_TLS_KEY`.
The raw TCP transport has no relay-level TLS; it does not need it for confidentiality (the
inner TLS is end-to-end) but then the relay token is visible on the path.

## Run from source

```bash
cd relay
go build -o switchtether-relay ./cmd/switchtether-relay
./switchtether-relay -log-level debug
```

`SIGINT`/`SIGTERM` stop it gracefully: listeners close, desktop control connections are closed
at once (desktops reconnect), and running tunnels get `-shutdown-grace` to finish.

## Deploy on Cloudflare

Cloudflare can only carry the **WebSocket** transport. `relay/cloudflare/` runs the **same Go
relay** (built from `relay/Dockerfile`) as a [Cloudflare Container](https://developers.cloudflare.com/containers/),
behind a tiny Worker that forwards `/v1/tunnel` and `/healthz` to one container instance
(desktops and phones must meet in the same process).

Requirements: a Cloudflare account on the Workers Paid plan (Containers are not on the free
plan), Node.js 20+, and a running Docker (wrangler builds the image locally and pushes it to
Cloudflare's registry).

```bash
cd relay/cloudflare
npm ci                    # exact versions from package-lock.json
npx wrangler login
npm run typecheck         # optional: generates worker-configuration.d.ts and runs tsc
npm run deploy            # = wrangler deploy: builds ../Dockerfile, pushes, deploys
npx wrangler containers list   # the first deployment takes a few minutes to provision
curl https://switchtether-relay.<your-subdomain>.workers.dev/healthz   # ok
```

Desktop relay URL: `wss://switchtether-relay.<your-subdomain>.workers.dev/v1/tunnel` (or your
custom domain / route). Notes:

- The container stops after 15 minutes without any connection (`sleepAfter` in
  `src/index.ts`) and starts on the next request; an open desktop control connection keeps it
  running. A restart or redeploy drops all connections; desktops reconnect by themselves.
- `max_instances` is 1 on purpose. Do not scale it out: a phone and its desktop must reach the
  same instance.
- Limits are set through `envVars` in `src/index.ts` (same variables as the table below).

## Point the desktop at it

Desktop → Settings → Remote → Relay: enter the relay URL and switch it on.

| Transport | URL |
|---|---|
| Raw TCP | `tcp://relay.example.com:47700` |
| WebSocket over TLS | `wss://relay.example.com/v1/tunnel` |
| WebSocket, local tests only | `ws://192.168.1.10:8080/v1/tunnel` |

The desktop generates the relay access token and puts the relay URL and token into the
pairing QR code; phones try direct paths first and fall back to the relay.

## Configuration

Every flag has an environment variable; flags win over the environment.

| Flag | Environment variable | Default | Meaning |
|---|---|---|---|
| `-tcp-listen` | `SWITCHTETHER_RELAY_TCP_LISTEN` | `:47700` | raw TCP transport (empty disables) |
| `-http-listen` | `SWITCHTETHER_RELAY_HTTP_LISTEN` | `:8080` | WebSocket `/v1/tunnel` + `/healthz` (empty disables) |
| `-tls-cert` | `SWITCHTETHER_RELAY_TLS_CERT` | — | PEM certificate: serve HTTPS/`wss://` |
| `-tls-key` | `SWITCHTETHER_RELAY_TLS_KEY` | — | PEM private key for `-tls-cert` |
| `-log-level` | `SWITCHTETHER_RELAY_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `-log-format` | `SWITCHTETHER_RELAY_LOG_FORMAT` | `text` | `text` or `json` |
| `-max-line-bytes` | `SWITCHTETHER_RELAY_MAX_LINE_BYTES` | `8192` | max handshake/control line |
| `-handshake-timeout` | `SWITCHTETHER_RELAY_HANDSHAKE_TIMEOUT` | `10s` | time for the handshake lines |
| `-accept-timeout` | `SWITCHTETHER_RELAY_ACCEPT_TIMEOUT` | `10s` | phone's wait for the desktop's ACCEPT |
| `-max-pending-per-desktop` | `SWITCHTETHER_RELAY_MAX_PENDING_PER_DESKTOP` | `16` | phones waiting for ACCEPT per desktop |
| `-max-tunnels-per-desktop` | `SWITCHTETHER_RELAY_MAX_TUNNELS_PER_DESKTOP` | `32` | concurrent tunnels per desktop |
| `-max-desktops` | `SWITCHTETHER_RELAY_MAX_DESKTOPS` | `10000` | registered desktops per relay |
| `-idle-timeout` | `SWITCHTETHER_RELAY_IDLE_TIMEOUT` | `10m` | close a tunnel with no bytes either way |
| `-bandwidth` | `SWITCHTETHER_RELAY_BANDWIDTH` | `0` | bytes/s per tunnel and direction (0 = unlimited) |
| `-ping-interval` | `SWITCHTETHER_RELAY_PING_INTERVAL` | `30s` | PING on desktop control connections |
| `-desktop-timeout` | `SWITCHTETHER_RELAY_DESKTOP_TIMEOUT` | `90s` | drop a silent desktop |
| `-write-timeout` | `SWITCHTETHER_RELAY_WRITE_TIMEOUT` | `10s` | max time to write one protocol line |
| `-shutdown-grace` | `SWITCHTETHER_RELAY_SHUTDOWN_GRACE` | `10s` | drain time for tunnels on shutdown |
| `-healthcheck` | — | — | probe the local `/healthz` (or the TCP port) and exit 0/1 |
| `-version` | — | — | print the version |

Durations use Go syntax (`90s`, `10m`, `1h30m`).

## Limits

| Limit | Default | When exceeded |
|---|---|---|
| Line length / handshake time | 8 KiB / 10 s | `ERR bad_request` / connection closed |
| Pending phones per desktop | 16 | phone gets `ERR busy` |
| Tunnels per desktop | 32 | phone gets `ERR busy` |
| Registered desktops | 10 000 | new desktop gets `ERR busy` (a desktop re-registering is always allowed) |
| Idle tunnel | 10 min | tunnel closed |
| Bandwidth | unlimited | throttled |

## Development

```bash
cd relay
go vet ./...
go test -race ./...    # unit tests + end-to-end mutual TLS through the relay (TCP, WebSocket, mixed)
```

`internal/relay/testdata/auth_vector.json` is a public test vector for the AUTH signature that
other implementations (the desktop) can check against. CI runs vet, the race tests, the Docker
build and a container smoke test (`.github/workflows/ci.yml`, job `relay`).
