# SwitchTether Relay

[中文](README.zh-CN.md)

An optional, self-hosted relay that lets the SwitchTether Android app reach its desktop when
the two are not on the same network and no VPN (e.g. Tailscale) is available. It is a dumb
byte pipe: the phone ↔ desktop **mutual-TLS session runs end-to-end through it**, with the
same certificate pinning as a direct connection. Only people you give a **relay key** can use
it.

```
phone ──TCP/WS──► relay ◄──TCP/WS── desktop (control connection, always open)
   └───── TLS 1.3 + client cert, pinned (end-to-end, unchanged) ─────┘
```

The protocol is specified in [docs/RELAY.md](../docs/RELAY.md). This directory contains the
reference implementation (Go, one dependency: `github.com/coder/websocket`), a Docker image,
and a Cloudflare Containers deployment.

## Quick start

```bash
docker build -t switchtether-relay relay/
KEY=$(docker run --rm switchtether-relay genkey)     # 32 random bytes, base64url
echo "$KEY"                                          # keep it: the desktop needs it
docker run -d --name switchtether-relay --restart unless-stopped \
  -e SWITCHTETHER_RELAY_KEYS="$KEY" -p 47700:47700 -p 8080:8080 switchtether-relay
curl http://localhost:8080/healthz                   # ok
```

Then on the desktop: Settings → Remote → Relay → enter the relay URL (e.g.
`tcp://relay.example.com:47700`) and the relay key, and switch it on. Phones get both through
pairing.

## Security model

Three gates, from outside in:

1. **Relay key — who may use this relay at all.** After `LISTEN` or `CONNECT` the relay sends
   a fresh challenge; the client answers with an HMAC-SHA256 of it under a relay key. The key
   itself never crosses the network and a recorded handshake cannot be replayed. Without a
   valid MAC a phone is refused before the desktop hears about it, and learns nothing (not even
   whether the desktop is online).
2. **Relay access token — who may reach this desktop.** Each authenticated phone connection is
   announced to the desktop with the token the phone presented (from the pairing QR); the
   desktop rejects wrong tokens. A desktop must also sign a challenge with its TLS identity key
   (ECDSA P-256), so nobody else can register under its fingerprint.
3. **End-to-end mutual TLS with certificate pinning — the only gate that protects content.**
   The relay never terminates it, holds no TLS keys, has no accounts and stores nothing.

- **What the operator sees:** desktop fingerprints, IP addresses, timing and volume, and the
  relay access token (also visible on the path when you use `tcp://` or `ws://` rather than
  `wss://`). Not: any request, response, terminal byte, file or TLS key. Logs never contain
  relay keys, MACs or tokens; fingerprints are shortened to 8 hex characters.
- **No other endpoints.** HTTP serves only `/v1/tunnel` (WebSocket) and `GET /healthz`; there
  are no stats or listings.

See [docs/RELAY.md §2.0 and §6](../docs/RELAY.md#20-relay-key-who-may-use-the-relay).

## Relay keys

```bash
switchtether-relay genkey                 # or: docker run --rm switchtether-relay genkey
```

- Configure one or more keys with `-keys k1,k2` / `SWITCHTETHER_RELAY_KEYS`, and/or a file with
  `-keys-file` / `SWITCHTETHER_RELAY_KEYS_FILE` (one key per line, `#` starts a comment). Keys
  from both are accepted.
- Several keys allow **rotation** (add the new key, move desktops over, remove the old one) or
  one key per person (remove a key to lock that person out).
- The keys file is re-read on `SIGHUP` (Linux/macOS; `docker kill -s HUP <container>`). If the
  new file is invalid or empty, the old keys stay active. Connections that are already
  established are not cut by a key change.
- **The relay refuses to start without a key.** `-insecure-open` overrides that for local
  testing only: anyone can then use the relay (a loud warning is logged). Never expose such a
  relay.
- Never commit keys: `relay/.gitignore` ignores `.env`, `relay-keys.txt` and `*.keys`.

## Run with Docker

See the quick start above. With Compose (both ports, limits spelled out, read-only, no
capabilities):

```bash
docker build -t switchtether-relay:local relay/
echo "SWITCHTETHER_RELAY_KEYS=$(docker run --rm switchtether-relay:local genkey)" > relay/.env
docker compose -f relay/docker-compose.yml up -d
```

`relay/docker-compose.yml` also shows the keys-file variant (a Compose secret mounted at
`/run/secrets/relay_keys`; the file must be readable by uid 65532).

The image is a static binary on `gcr.io/distroless/static-debian13:nonroot` (runs as uid
65532). Its `HEALTHCHECK` runs `/switchtether-relay -healthcheck`.

**TLS for WebSocket (`wss://`).** Either terminate TLS in a reverse proxy in front of port 8080
(Caddy, nginx, Traefik; it must pass WebSocket upgrades on `/v1/tunnel`), or let the relay do
it: mount a certificate and set `SWITCHTETHER_RELAY_TLS_CERT` / `SWITCHTETHER_RELAY_TLS_KEY`.
The raw TCP transport has no relay-level TLS; it does not need it for confidentiality (the
inner TLS is end-to-end, and the relay key is never sent) but then the relay access token is
visible on the path.

## Run from source

```bash
cd relay
go build -o switchtether-relay ./cmd/switchtether-relay
./switchtether-relay genkey > relay.keys
./switchtether-relay -keys-file relay.keys -log-level debug
```

`SIGINT`/`SIGTERM` stop it gracefully: listeners close, desktop control connections are closed
at once (desktops reconnect), and running tunnels get `-shutdown-grace` to finish.

## Deploy on Cloudflare

Cloudflare can only carry the **WebSocket** transport. `relay/cloudflare/` runs the **same Go
relay** (built from `relay/Dockerfile`) as a [Cloudflare Container](https://developers.cloudflare.com/containers/),
behind a tiny Worker that forwards `/v1/tunnel` and `/healthz` to one container instance
(desktops and phones must meet in the same process). The relay keys are a Worker secret that
the Worker passes to the container as `SWITCHTETHER_RELAY_KEYS`.

Requirements: a Cloudflare account on the Workers Paid plan (Containers are not on the free
plan), Node.js 20+, Go (only for `genkey`; any other source of 32 random bytes in base64url
works), and a running Docker (wrangler builds the image locally and pushes it to Cloudflare's
registry).

```bash
cd relay/cloudflare
npm ci                    # exact versions from package-lock.json
npx wrangler login
npm run typecheck         # optional: generates worker-configuration.d.ts and runs tsc
npm run deploy            # = wrangler deploy: builds ../Dockerfile, pushes, deploys
(cd .. && go run ./cmd/switchtether-relay genkey)       # copy the key
npx wrangler secret put SWITCHTETHER_RELAY_KEYS         # paste it (comma-separate several)
npx wrangler containers list   # the first deployment takes a few minutes to provision
curl https://switchtether-relay.<your-subdomain>.workers.dev/healthz   # ok
```

Desktop relay URL: `wss://switchtether-relay.<your-subdomain>.workers.dev/v1/tunnel` (or your
custom domain / route). Notes:

- Without the secret the container refuses to start (`/healthz` then fails); the relay never
  runs open on Cloudflare. After changing the secret, restart the container (redeploy) so it
  picks up the new keys; `SIGHUP` reloading does not apply here.
- The container stops after 15 minutes without any connection (`sleepAfter` in
  `src/index.ts`) and starts on the next request; an open desktop control connection keeps it
  running. A restart or redeploy drops all connections; desktops reconnect by themselves.
- `max_instances` is 1 on purpose. Do not scale it out: a phone and its desktop must reach the
  same instance.
- Limits are set through `envVars` in `src/index.ts` (same variables as the table below).

## Point the desktop at it

Desktop → Settings → Remote → Relay: enter the relay URL and the relay key, and switch it on.

| Transport | URL |
|---|---|
| Raw TCP | `tcp://relay.example.com:47700` |
| WebSocket over TLS | `wss://relay.example.com/v1/tunnel` |
| WebSocket, local tests only | `ws://192.168.1.10:8080/v1/tunnel` |

The desktop keeps the relay key in the OS keychain, generates the relay access token, and puts
the relay URL, token and key into the pairing QR code; phones try direct paths first and fall
back to the relay.

## Configuration

Every flag has an environment variable; flags win over the environment.

| Flag | Environment variable | Default | Meaning |
|---|---|---|---|
| `-keys` | `SWITCHTETHER_RELAY_KEYS` | — | comma-separated relay keys (32 bytes base64url each) |
| `-keys-file` | `SWITCHTETHER_RELAY_KEYS_FILE` | — | one key per line, `#` comments; re-read on `SIGHUP` |
| `-insecure-open` | `SWITCHTETHER_RELAY_INSECURE_OPEN` | `false` | run without a key: **anyone** can use the relay (testing only) |
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
| `genkey` (subcommand) | — | — | print a new relay key |

Durations use Go syntax (`90s`, `10m`, `1h30m`). Exit code 2 means a configuration error
(for example no relay key).

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

Public test vectors for other implementations (desktop, Android):
`internal/relay/testdata/auth_vector.json` (desktop AUTH signature) and
`internal/relay/testdata/relay_key_vector.json` (relay-key MAC for both roles; the key in it is
a throwaway test value). CI runs gofmt, vet, the race tests, the Docker build and a container
smoke test (`.github/workflows/ci.yml`, job `relay`).
