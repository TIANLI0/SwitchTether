package relay

import (
	"net/http"

	"github.com/coder/websocket"
)

// TunnelPath is the WebSocket endpoint (docs/RELAY.md §1).
const TunnelPath = "/v1/tunnel"

// HealthPath answers 200 while the relay accepts connections and 503 while shutting down.
const HealthPath = "/healthz"

// HTTPHandler serves the WebSocket transport and the health check. Nothing else is exposed:
// no statistics, no listing of desktops.
func (s *Server) HTTPHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET "+HealthPath, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		if s.Closing() {
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte("shutting down\n"))
			return
		}
		_, _ = w.Write([]byte("ok\n"))
	})
	mux.HandleFunc("GET "+TunnelPath, s.serveWebSocket)
	return mux
}

func (s *Server) serveWebSocket(w http.ResponseWriter, r *http.Request) {
	if s.Closing() {
		http.Error(w, "shutting down", http.StatusServiceUnavailable)
		return
	}
	ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		// The relay has no cookies or ambient authority, so a cross-origin page gains nothing
		// a direct client could not do; native clients may send arbitrary Origin headers.
		InsecureSkipVerify: true,
		// The payload is TLS ciphertext: compression would only cost CPU.
		CompressionMode: websocket.CompressionDisabled,
	})
	if err != nil {
		return // Accept already answered the request
	}
	// Binary messages carry the byte stream; a text message closes the connection.
	nc := websocket.NetConn(s.baseCtx, ws, websocket.MessageBinary)
	s.ServeConn(nc, r.RemoteAddr)
}
