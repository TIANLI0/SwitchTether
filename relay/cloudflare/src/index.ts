// SwitchTether relay on Cloudflare: this Worker only routes. The relay itself is the same
// Go binary as the Docker image (relay/Dockerfile), running as a Cloudflare Container.
// Cloudflare offers only the WebSocket transport (wss://<worker host>/v1/tunnel).
import { Container, getContainer } from "@cloudflare/containers";

export class RelayContainer extends Container<Env> {
  defaultPort = 8080;
  // Open WebSockets count as in-flight requests and keep the container awake; with no
  // desktop connected it stops after this long and starts again on the next request.
  sleepAfter = "15m";
  pingEndpoint = "localhost/healthz";
  envVars = {
    // Raw TCP cannot reach a container through Cloudflare; serve only HTTP/WebSocket.
    SWITCHTETHER_RELAY_TCP_LISTEN: "",
    SWITCHTETHER_RELAY_HTTP_LISTEN: ":8080",
    SWITCHTETHER_RELAY_LOG_LEVEL: "info",
    // Worker secret (`wrangler secret put SWITCHTETHER_RELAY_KEYS`). Without it the relay
    // refuses to start; it never runs open on Cloudflare.
    SWITCHTETHER_RELAY_KEYS: this.env.SWITCHTETHER_RELAY_KEYS,
  };
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(request.url);
    if (pathname === "/v1/tunnel" || pathname === "/healthz") {
      // A single relay instance: a desktop and its phones must meet in the same process.
      return getContainer(env.RELAY).fetch(request);
    }
    return new Response("Not found\n", { status: 404 });
  },
} satisfies ExportedHandler<Env>;
