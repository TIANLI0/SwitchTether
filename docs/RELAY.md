# SwitchTether Relay — protocol v1

The relay lets a phone reach its desktop when they are not on the same network and no VPN
(Tailscale) is available. It is **optional, off by default and self-hosted** (Docker on your
own server, or Cloudflare). It is a dumb byte pipe:

- The phone ↔ desktop **mutual-TLS session runs end-to-end through the relay**, with the same
  certificate pinning as a direct connection. The relay sees only TLS ciphertext; it holds no
  keys, no accounts and no data.
- A desktop proves to the relay that it owns its fingerprint (signature with its TLS identity
  key), so nobody else can register under its name.
- A per-desktop **relay access token** (random, in the pairing QR) is checked by the desktop
  before it accepts a tunnelled connection, so the relay cannot be used to scan desktops.

```
phone ──TCP/WS──► relay ◄──TCP/WS── desktop (control connection, always open)
   └───── TLS 1.3 + client cert, pinned (end-to-end, unchanged) ─────┘
```

## 1. Transports

The protocol is a byte stream. Two transports carry it; a relay may offer both:

| Transport | Relay URL | Framing |
|---|---|---|
| Raw TCP | `tcp://host:port` (default port 47700) | the bytes themselves |
| WebSocket | `wss://host/v1/tunnel` (or `ws://` for local tests) | each binary message carries the next bytes of the stream; text messages are invalid |

Cloudflare deployments offer only WebSocket. The relay's own TLS (`wss`) is recommended but
not required for confidentiality (the inner TLS is end-to-end).

Message boundaries carry no meaning (a line may be split across messages or share one with
stream bytes); keep messages ≤ 64 KiB. A WebSocket has no half-close: closing it ends both
directions. The relay maps a TCP half-close (EOF) on one side of a tunnel to a full close of
a WebSocket peer, and to a write shutdown of a TCP peer. The relay serves nothing on HTTP
except `/v1/tunnel` and `GET /healthz`.

## 2. Handshake lines

Every connection starts with printable-ASCII lines terminated by `\n` (max 8 KiB per line,
excluding the `\n`; a `\r` before it is ignored), sent by the client, answered by the relay.
Fields are separated by exactly one space. After the final `OK`, the connection carries raw
bytes (data connections) or more lines (control connection). `<fp>` is the desktop's server
certificate fingerprint: lowercase hex SHA-256 of the certificate DER (64 chars), the same
value as `fp` in the pairing link. Bytes a client sends right after its last line (before
`OK` arrives) are not lost: they are delivered once the tunnel is spliced.

### 2.1 Desktop control connection

```
D: ST-RELAY/1 LISTEN <fp>
R: CHALLENGE <nonce>                      # 32 random bytes, base64url without padding
D: AUTH <cert> <sig>                      # cert = certificate DER, base64url
                                          # sig  = ECDSA P-256 / SHA-256, ASN.1 DER, base64url, over the bytes
                                          #        "switchtether-relay-v1\n" + nonce + "\n" + fp
R: OK                                     # or: ERR <code> <text>
```

`nonce` in the signed message is the base64url **text** exactly as received in `CHALLENGE`
(not the decoded bytes), and `fp` is the lowercase hex fingerprint; the message has no
trailing newline. A public test vector is in `relay/internal/relay/testdata/auth_vector.json`.

The relay checks `sha256(cert) == fp`, that the certificate key is ECDSA P-256 and the
signature (only the SubjectPublicKeyInfo is used; validity dates and extensions are ignored).
Every failure is `ERR auth_failed`. A new LISTEN for the same `fp` replaces the old control
connection (the old one is closed without a message; phones already announced to it can still
be accepted). When the relay already has its maximum of desktops, a LISTEN for a new `fp` gets
`ERR busy`. A desktop reconnects with backoff (two instances sharing one identity would
otherwise keep replacing each other). Then, on the control connection:

| Direction | Line | Meaning |
|---|---|---|
| R → D | `INCOMING <conn> <token>` | a phone wants to connect; `<conn>` = 16 random bytes base64url; `<token>` = what the phone sent (`-` if none) |
| D → R | `REJECT <conn>` | refuse (bad token, limits); the phone gets `ERR rejected` |
| R → D / D → R | `PING` / `PONG` | keepalive, every 30 s from the relay; a desktop silent for 90 s is dropped |

`PING` is answered with `PONG` in both directions. A `REJECT` for an unknown or expired
`<conn>` is ignored. Any other line from the desktop is `ERR bad_request` and closes the
control connection. When a control connection ends without being replaced, the desktop is
offline and its waiting phones get `ERR offline`. To accept, the desktop opens a **data
connection** (2.2).

### 2.2 Desktop data connection

```
D: ST-RELAY/1 ACCEPT <fp> <conn>
R: OK                                     # then raw bytes, spliced to the phone
```

Only valid for a pending `<conn>` of that `fp` (the id is known only to the control
connection), and only once; otherwise `ERR unknown_conn`. If the desktop's tunnel limit was
reached in the meantime, both sides get `ERR busy`. The desktop feeds the stream into its
normal TLS server, exactly like a TCP connection accepted on its own port.

### 2.3 Phone connection

```
P: ST-RELAY/1 CONNECT <fp> <token>
R: OK                                     # after the desktop accepted; then raw bytes (TLS ClientHello …)
   ERR offline | ERR rejected | ERR timeout | ERR busy
```

`<token>` is the relay access token (base64url alphabet, at most 128 characters) or `-`;
omitting it is the same as `-`. The relay forwards it to the desktop and never interprets it.
The relay waits at most 10 s for the desktop's ACCEPT.

## 3. Errors

`ERR <code> <optional text>` then close. Codes: `bad_request`, `auth_failed`, `offline`,
`rejected`, `timeout`, `busy` (limits), `unknown_conn`, `version` (first token is
`ST-RELAY/<n>` with another `<n>`). Clients match on the code; the text is informational.

## 4. Limits (defaults, configurable)

| Limit | Default |
|---|---|
| Handshake line length / handshake time | 8 KiB / 10 s |
| Pending phone connections per desktop | 16 |
| Concurrent tunnels per desktop | 32 |
| Registered desktops per relay | 10 000 |
| Idle tunnel (no bytes either way) | 10 min |
| Max bandwidth | unlimited (operator may set per-tunnel bytes/s, applied to each direction) |

A tunnel counts against the limit until both of its directions have ended. The handshake time
covers the lines up to `OK`; a phone's wait for `ACCEPT` is the separate 10 s of §2.3.
The relay implementation is in `relay/` (Go; Docker and Cloudflare deployment).

## 5. Desktop and phone configuration

- Desktop (Settings → Remote → Relay): relay URL, on/off. The access token is generated by the
  desktop (32 random bytes, base64url) and rotated with "Reset relay token" (phones must be
  re-paired or receive the new token over an authenticated connection: `GET /v1/relay`).
- Pairing link: when the relay is on, it adds `&r=<url-encoded relay URL>&rt=<token>`.
- Phone: tries direct hosts first (paired hosts, LAN discovery, Tailscale), then the relay.
  The TLS session, pinning, client certificate and step-up are identical on every path.

## 6. Security notes

- The relay operator can see: desktop fingerprints, phone and desktop IP addresses, timing and
  volume. Not: any request, response, terminal byte, file, key or token of the Remote API.
- The relay access token is visible to the relay (it forwards it in `INCOMING`), and to anyone
  on the path when the relay is reached over `tcp://` or `ws://`. It only grants what the next
  point describes. The relay never logs tokens; fingerprints and connection ids appear only
  shortened (8 and 6 characters).
- A stolen relay token lets someone reach the desktop's TLS port through the relay — the same
  exposure as a port on the internet, still requiring a paired client certificate. Reset the
  token if it leaks.
- The relay never terminates the inner TLS and never needs updating for Remote API changes.
