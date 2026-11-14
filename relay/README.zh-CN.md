# SwitchTether 中继

[English](README.md)

可选、自托管的中继服务：当手机和电脑不在同一网络、又没有 VPN（如 Tailscale）时，让
SwitchTether 安卓 App 仍能连上电脑。它只是一根“傻管道”：手机 ↔ 电脑的**双向 TLS 会话端到端
穿过中继**，证书固定（pinning）与直连完全相同。

```
手机 ──TCP/WS──► 中继 ◄──TCP/WS── 电脑（控制连接，常驻）
   └───── TLS 1.3 + 客户端证书，固定校验（端到端，不变） ─────┘
```

协议规范见 [docs/RELAY.md](../docs/RELAY.md)。本目录是参考实现（Go，唯一依赖
`github.com/coder/websocket`）、Docker 镜像和 Cloudflare Containers 部署。

## 安全模型

- **看不到明文。** 中继只拼接字节流，从不终止内层 TLS，不持有密钥、没有账号、不存储任何数据。
- **电脑要证明身份。** 电脑以证书指纹注册，并且必须用该证书的 ECDSA P-256 私钥签名一次性
  挑战，别人无法冒用它的指纹注册。
- **由电脑决定是否接受。** 每个手机连接都会连同手机出示的中继访问令牌（来自配对二维码）
  通知电脑，令牌不对电脑就拒绝。即使令牌正确，手机仍需已配对的客户端证书才能完成 TLS 握手。
- **运营者能看到：** 电脑指纹、IP 地址、时间与流量大小，以及中继访问令牌（使用 `tcp://`
  或 `ws://` 而不是 `wss://` 时，链路上的第三方也能看到）。看不到：任何请求、响应、终端
  字节、文件或密钥。日志从不记录令牌，指纹只记录前 8 位十六进制。
- **没有其他接口。** HTTP 只提供 `/v1/tunnel`（WebSocket）和 `GET /healthz`，没有统计或列表。

详见 [docs/RELAY.md §6](../docs/RELAY.md#6-security-notes)。

## 用 Docker 运行

```bash
docker build -t switchtether-relay relay/
docker run -d --name switchtether-relay --restart unless-stopped \
  -p 47700:47700 -p 8080:8080 switchtether-relay
curl http://localhost:8080/healthz   # ok
```

或用 Compose（两个端口、写明各项限制、只读文件系统、去掉所有 capability）：

```bash
docker compose -f relay/docker-compose.yml up -d --build
```

镜像是 `gcr.io/distroless/static-debian13:nonroot` 上的静态二进制（以 uid 65532 运行），
`HEALTHCHECK` 调用 `/switchtether-relay -healthcheck`。

**WebSocket 的 TLS（`wss://`）。** 可以在 8080 端口前放反向代理终止 TLS（Caddy、nginx、
Traefik，需放行 `/v1/tunnel` 的 WebSocket 升级），也可以让中继自己处理：挂载证书并设置
`SWITCHTETHER_RELAY_TLS_CERT` / `SWITCHTETHER_RELAY_TLS_KEY`。原始 TCP 传输没有中继层 TLS；
机密性不依赖它（内层 TLS 是端到端的），但这时中继令牌在链路上可见。

## 从源码运行

```bash
cd relay
go build -o switchtether-relay ./cmd/switchtether-relay
./switchtether-relay -log-level debug
```

`SIGINT`/`SIGTERM` 优雅退出：关闭监听，立即关闭电脑控制连接（电脑会自动重连），正在传输的
隧道有 `-shutdown-grace` 的时间收尾。

## 部署到 Cloudflare

Cloudflare 只能承载 **WebSocket** 传输。`relay/cloudflare/` 把**同一个 Go 中继**（由
`relay/Dockerfile` 构建）作为 [Cloudflare Container](https://developers.cloudflare.com/containers/)
运行，前面是一个很小的 Worker，把 `/v1/tunnel` 和 `/healthz` 转发到唯一的容器实例（电脑和
手机必须在同一个进程里会合）。

前提：Cloudflare 账号开通 Workers 付费计划（免费计划没有 Containers）、Node.js 20+、本机
Docker 正在运行（wrangler 在本地构建镜像并推送到 Cloudflare 镜像仓库）。

```bash
cd relay/cloudflare
npm ci                    # 按 package-lock.json 安装固定版本
npx wrangler login
npm run typecheck         # 可选：生成 worker-configuration.d.ts 并运行 tsc
npm run deploy            # 即 wrangler deploy：构建 ../Dockerfile、推送、部署
npx wrangler containers list   # 首次部署需要几分钟完成容器配置
curl https://switchtether-relay.<你的子域>.workers.dev/healthz   # ok
```

电脑端中继地址：`wss://switchtether-relay.<你的子域>.workers.dev/v1/tunnel`（或自定义域名 /
路由）。注意：

- 没有任何连接 15 分钟后容器会休眠（`src/index.ts` 中的 `sleepAfter`），下次请求时自动启动；
  只要有电脑的控制连接在线，容器就保持运行。重启或重新部署会断开所有连接，电脑会自动重连。
- `max_instances` 刻意设为 1，不要扩容：手机和它的电脑必须连到同一个实例。
- 限制参数通过 `src/index.ts` 里的 `envVars` 设置（变量同下表）。

## 让电脑使用中继

电脑端 → 设置 → 远程 → 中继：填入中继地址并开启。

| 传输 | 地址 |
|---|---|
| 原始 TCP | `tcp://relay.example.com:47700` |
| WebSocket over TLS | `wss://relay.example.com/v1/tunnel` |
| WebSocket（仅限本地测试） | `ws://192.168.1.10:8080/v1/tunnel` |

中继访问令牌由电脑生成，中继地址和令牌会写入配对二维码；手机优先尝试直连，失败后才走中继。

## 配置

每个命令行参数都有对应的环境变量；命令行参数优先于环境变量。

| 参数 | 环境变量 | 默认值 | 含义 |
|---|---|---|---|
| `-tcp-listen` | `SWITCHTETHER_RELAY_TCP_LISTEN` | `:47700` | 原始 TCP 传输（留空则关闭） |
| `-http-listen` | `SWITCHTETHER_RELAY_HTTP_LISTEN` | `:8080` | WebSocket `/v1/tunnel` + `/healthz`（留空则关闭） |
| `-tls-cert` | `SWITCHTETHER_RELAY_TLS_CERT` | — | PEM 证书：提供 HTTPS/`wss://` |
| `-tls-key` | `SWITCHTETHER_RELAY_TLS_KEY` | — | `-tls-cert` 对应的 PEM 私钥 |
| `-log-level` | `SWITCHTETHER_RELAY_LOG_LEVEL` | `info` | `debug`、`info`、`warn`、`error` |
| `-log-format` | `SWITCHTETHER_RELAY_LOG_FORMAT` | `text` | `text` 或 `json` |
| `-max-line-bytes` | `SWITCHTETHER_RELAY_MAX_LINE_BYTES` | `8192` | 握手/控制行最大长度 |
| `-handshake-timeout` | `SWITCHTETHER_RELAY_HANDSHAKE_TIMEOUT` | `10s` | 握手行的时间上限 |
| `-accept-timeout` | `SWITCHTETHER_RELAY_ACCEPT_TIMEOUT` | `10s` | 手机等待电脑 ACCEPT 的时间 |
| `-max-pending-per-desktop` | `SWITCHTETHER_RELAY_MAX_PENDING_PER_DESKTOP` | `16` | 每台电脑等待 ACCEPT 的手机数 |
| `-max-tunnels-per-desktop` | `SWITCHTETHER_RELAY_MAX_TUNNELS_PER_DESKTOP` | `32` | 每台电脑的并发隧道数 |
| `-max-desktops` | `SWITCHTETHER_RELAY_MAX_DESKTOPS` | `10000` | 中继可注册的电脑数 |
| `-idle-timeout` | `SWITCHTETHER_RELAY_IDLE_TIMEOUT` | `10m` | 双向都无数据时关闭隧道 |
| `-bandwidth` | `SWITCHTETHER_RELAY_BANDWIDTH` | `0` | 每条隧道每个方向的字节/秒（0 为不限） |
| `-ping-interval` | `SWITCHTETHER_RELAY_PING_INTERVAL` | `30s` | 控制连接上发送 PING 的间隔 |
| `-desktop-timeout` | `SWITCHTETHER_RELAY_DESKTOP_TIMEOUT` | `90s` | 电脑无响应多久后断开 |
| `-write-timeout` | `SWITCHTETHER_RELAY_WRITE_TIMEOUT` | `10s` | 写一行协议的时间上限 |
| `-shutdown-grace` | `SWITCHTETHER_RELAY_SHUTDOWN_GRACE` | `10s` | 退出时给隧道收尾的时间 |
| `-healthcheck` | — | — | 探测本机 `/healthz`（或 TCP 端口），健康则退出码 0 |
| `-version` | — | — | 打印版本 |

时长使用 Go 写法（`90s`、`10m`、`1h30m`）。

## 限制

| 限制 | 默认 | 超出时 |
|---|---|---|
| 行长度 / 握手时间 | 8 KiB / 10 秒 | `ERR bad_request` / 断开连接 |
| 每台电脑等待中的手机 | 16 | 手机收到 `ERR busy` |
| 每台电脑的隧道 | 32 | 手机收到 `ERR busy` |
| 已注册的电脑 | 10 000 | 新电脑收到 `ERR busy`（已注册的电脑重新注册不受限） |
| 空闲隧道 | 10 分钟 | 关闭隧道 |
| 带宽 | 不限 | 限速 |

## 开发

```bash
cd relay
go vet ./...
go test -race ./...    # 单元测试 + 穿过中继的端到端双向 TLS（TCP、WebSocket、混合）
```

`internal/relay/testdata/auth_vector.json` 是 AUTH 签名的公开测试向量，其他实现（电脑端）
可以用它自测。CI（`.github/workflows/ci.yml` 的 `relay` 任务）会运行 vet、race 测试、
Docker 构建和容器冒烟测试。
