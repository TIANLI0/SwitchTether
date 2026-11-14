// Command switchtether-relay runs the SwitchTether relay (docs/RELAY.md): a self-hosted,
// account-less byte pipe between phones and desktops. It never sees plaintext.
package main

import (
	"context"
	"crypto/tls"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/TIANLI0/SwitchTether/relay/internal/relay"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "dev"

const envPrefix = "SWITCHTETHER_RELAY_"

type options struct {
	tcpListen     string
	httpListen    string
	tlsCert       string
	tlsKey        string
	logLevel      string
	logFormat     string
	shutdownGrace time.Duration
	cfg           relay.Config
}

func main() {
	os.Exit(run(os.Args[1:], os.Stderr))
}

func run(args []string, stderr io.Writer) int {
	fs := flag.NewFlagSet("switchtether-relay", flag.ContinueOnError)
	fs.SetOutput(stderr)
	opts, healthcheck, showVersion, err := parseFlags(fs, args, os.LookupEnv)
	if err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		fmt.Fprintln(stderr, "switchtether-relay:", err)
		return 2
	}
	if showVersion {
		fmt.Fprintln(stderr, "switchtether-relay", version)
		return 0
	}
	if healthcheck {
		return runHealthcheck(opts, stderr)
	}

	log, err := newLogger(stderr, opts.logLevel, opts.logFormat)
	if err != nil {
		fmt.Fprintln(stderr, "switchtether-relay:", err)
		return 2
	}
	if err := serve(opts, log); err != nil {
		log.Error("relay stopped", "err", err)
		return 1
	}
	return 0
}

// parseFlags reads flags, falling back to SWITCHTETHER_RELAY_* environment variables and
// then to the defaults. Flags win over the environment.
func parseFlags(fs *flag.FlagSet, args []string, lookup func(string) (string, bool)) (opts options, healthcheck, showVersion bool, err error) {
	def := relay.DefaultConfig()
	var envErr error
	str := func(name, def string) string {
		if v, ok := lookup(envName(name)); ok {
			return v
		}
		return def
	}
	dur := func(name string, def time.Duration) time.Duration {
		if v, ok := lookup(envName(name)); ok {
			d, err := time.ParseDuration(v)
			if err != nil && envErr == nil {
				envErr = fmt.Errorf("%s: %w", envName(name), err)
			}
			return d
		}
		return def
	}
	num := func(name string, def int64) int64 {
		if v, ok := lookup(envName(name)); ok {
			n, err := strconv.ParseInt(v, 10, 64)
			if err != nil && envErr == nil {
				envErr = fmt.Errorf("%s: %w", envName(name), err)
			}
			return n
		}
		return def
	}

	fs.StringVar(&opts.tcpListen, "tcp-listen", str("tcp-listen", ":47700"), "raw TCP listen address (empty disables)")
	fs.StringVar(&opts.httpListen, "http-listen", str("http-listen", ":8080"), "HTTP listen address for WebSocket /v1/tunnel and /healthz (empty disables)")
	fs.StringVar(&opts.tlsCert, "tls-cert", str("tls-cert", ""), "PEM certificate for the HTTP listener (enables HTTPS/wss)")
	fs.StringVar(&opts.tlsKey, "tls-key", str("tls-key", ""), "PEM private key for -tls-cert")
	fs.StringVar(&opts.logLevel, "log-level", str("log-level", "info"), "debug, info, warn or error")
	fs.StringVar(&opts.logFormat, "log-format", str("log-format", "text"), "text or json")
	fs.DurationVar(&opts.shutdownGrace, "shutdown-grace", dur("shutdown-grace", 10*time.Second), "how long running tunnels may drain on SIGINT/SIGTERM")

	var maxLine, maxPending, maxTunnels, maxDesktops int64
	c := &opts.cfg
	fs.Int64Var(&maxLine, "max-line-bytes", num("max-line-bytes", int64(def.MaxLineBytes)), "max handshake/control line length")
	fs.DurationVar(&c.HandshakeTimeout, "handshake-timeout", dur("handshake-timeout", def.HandshakeTimeout), "max time for the handshake lines")
	fs.DurationVar(&c.AcceptTimeout, "accept-timeout", dur("accept-timeout", def.AcceptTimeout), "how long a phone waits for the desktop's ACCEPT")
	fs.Int64Var(&maxPending, "max-pending-per-desktop", num("max-pending-per-desktop", int64(def.MaxPendingPerDesktop)), "phone connections waiting for ACCEPT per desktop")
	fs.Int64Var(&maxTunnels, "max-tunnels-per-desktop", num("max-tunnels-per-desktop", int64(def.MaxTunnelsPerDesktop)), "concurrent tunnels per desktop")
	fs.Int64Var(&maxDesktops, "max-desktops", num("max-desktops", int64(def.MaxDesktops)), "registered desktops per relay")
	fs.DurationVar(&c.IdleTimeout, "idle-timeout", dur("idle-timeout", def.IdleTimeout), "close a tunnel after this long without bytes in either direction")
	fs.Int64Var(&c.BandwidthBytesPerSec, "bandwidth", num("bandwidth", def.BandwidthBytesPerSec), "per-tunnel, per-direction limit in bytes/s (0 = unlimited)")
	fs.DurationVar(&c.PingInterval, "ping-interval", dur("ping-interval", def.PingInterval), "PING interval on desktop control connections")
	fs.DurationVar(&c.DesktopTimeout, "desktop-timeout", dur("desktop-timeout", def.DesktopTimeout), "drop a silent desktop control connection after this long")
	fs.DurationVar(&c.WriteTimeout, "write-timeout", dur("write-timeout", def.WriteTimeout), "max time for writing one protocol line")
	fs.BoolVar(&healthcheck, "healthcheck", false, "probe the local /healthz and exit 0 if healthy (for container HEALTHCHECK)")
	fs.BoolVar(&showVersion, "version", false, "print the version and exit")

	if envErr != nil {
		return opts, false, false, envErr
	}
	if err := fs.Parse(args); err != nil {
		return opts, false, false, err
	}
	if fs.NArg() > 0 {
		return opts, false, false, fmt.Errorf("unexpected arguments: %v", fs.Args())
	}
	for _, v := range []int64{maxLine, maxPending, maxTunnels, maxDesktops} {
		if v < 0 || v > 1<<31-1 {
			return opts, false, false, fmt.Errorf("limit out of range: %d", v)
		}
	}
	c.MaxLineBytes = int(maxLine)
	c.MaxPendingPerDesktop = int(maxPending)
	c.MaxTunnelsPerDesktop = int(maxTunnels)
	c.MaxDesktops = int(maxDesktops)

	if healthcheck || showVersion {
		return opts, healthcheck, showVersion, nil
	}
	if err := c.Validate(); err != nil {
		return opts, false, false, err
	}
	if opts.tcpListen == "" && opts.httpListen == "" {
		return opts, false, false, errors.New("both listeners are disabled")
	}
	if (opts.tlsCert == "") != (opts.tlsKey == "") {
		return opts, false, false, errors.New("-tls-cert and -tls-key must be given together")
	}
	if opts.shutdownGrace < 0 {
		return opts, false, false, errors.New("-shutdown-grace must not be negative")
	}
	return opts, false, false, nil
}

func envName(flagName string) string {
	return envPrefix + strings.ToUpper(strings.ReplaceAll(flagName, "-", "_"))
}

func newLogger(w io.Writer, level, format string) (*slog.Logger, error) {
	var lv slog.Level
	if err := lv.UnmarshalText([]byte(level)); err != nil {
		return nil, fmt.Errorf("invalid log level %q", level)
	}
	ho := &slog.HandlerOptions{Level: lv}
	switch format {
	case "text":
		return slog.New(slog.NewTextHandler(w, ho)), nil
	case "json":
		return slog.New(slog.NewJSONHandler(w, ho)), nil
	}
	return nil, fmt.Errorf("invalid log format %q", format)
}

func serve(opts options, log *slog.Logger) error {
	srv, err := relay.NewServer(opts.cfg, log)
	if err != nil {
		return err
	}

	var (
		tcpLn   net.Listener
		httpSrv *http.Server
		wg      sync.WaitGroup
		errCh   = make(chan error, 2)
	)
	if opts.tcpListen != "" {
		tcpLn, err = net.Listen("tcp", opts.tcpListen)
		if err != nil {
			return fmt.Errorf("tcp listener: %w", err)
		}
		log.Info("raw TCP transport listening", "addr", tcpLn.Addr().String())
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := srv.Serve(tcpLn); err != nil {
				errCh <- fmt.Errorf("tcp listener: %w", err)
			}
		}()
	}
	if opts.httpListen != "" {
		ln, err := net.Listen("tcp", opts.httpListen)
		if err != nil {
			if tcpLn != nil {
				_ = tcpLn.Close()
			}
			return fmt.Errorf("http listener: %w", err)
		}
		httpSrv = &http.Server{
			Handler:           srv.HTTPHandler(),
			ReadHeaderTimeout: 10 * time.Second,
			IdleTimeout:       60 * time.Second,
			MaxHeaderBytes:    16 << 10,
			ErrorLog:          slog.NewLogLogger(log.Handler(), slog.LevelDebug),
		}
		scheme := "ws"
		if opts.tlsCert != "" {
			scheme = "wss"
			httpSrv.TLSConfig = &tls.Config{MinVersion: tls.VersionTLS12}
		}
		log.Info("WebSocket transport listening", "addr", ln.Addr().String(), "scheme", scheme, "path", relay.TunnelPath)
		wg.Add(1)
		go func() {
			defer wg.Done()
			var err error
			if opts.tlsCert != "" {
				err = httpSrv.ServeTLS(ln, opts.tlsCert, opts.tlsKey)
			} else {
				err = httpSrv.Serve(ln)
			}
			if err != nil && !errors.Is(err, http.ErrServerClosed) {
				errCh <- fmt.Errorf("http listener: %w", err)
			}
		}()
	}

	sigCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	var runErr error
	select {
	case <-sigCtx.Done():
		log.Info("shutting down", "grace", opts.shutdownGrace)
	case runErr = <-errCh:
	}
	stop()

	if tcpLn != nil {
		_ = tcpLn.Close()
	}
	ctx, cancel := context.WithTimeout(context.Background(), opts.shutdownGrace)
	defer cancel()
	if httpSrv != nil {
		// Stops the listener and plain HTTP requests; hijacked WebSockets are handled below.
		_ = httpSrv.Shutdown(ctx)
	}
	if err := srv.Shutdown(ctx); err != nil {
		log.Info("grace period over; remaining tunnels were closed")
	}
	wg.Wait()
	log.Info("stopped")
	return runErr
}

// runHealthcheck probes the local HTTP listener. It needs no shell or curl, so it works in
// a distroless/scratch image.
func runHealthcheck(opts options, stderr io.Writer) int {
	if opts.httpListen == "" {
		// Only the TCP transport is enabled: check that it accepts connections.
		addr := localAddr(opts.tcpListen)
		c, err := net.DialTimeout("tcp", addr, 3*time.Second)
		if err != nil {
			fmt.Fprintln(stderr, "unhealthy:", err)
			return 1
		}
		_ = c.Close()
		return 0
	}
	scheme := "http"
	client := &http.Client{Timeout: 3 * time.Second}
	if opts.tlsCert != "" {
		scheme = "https"
		// Loopback probe of our own listener; the certificate is issued for a public name.
		client.Transport = &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}} //nolint:gosec
	}
	resp, err := client.Get(scheme + "://" + localAddr(opts.httpListen) + relay.HealthPath)
	if err != nil {
		fmt.Fprintln(stderr, "unhealthy:", err)
		return 1
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		fmt.Fprintln(stderr, "unhealthy: status", resp.StatusCode)
		return 1
	}
	return 0
}

// localAddr turns a listen address such as ":8080" or "0.0.0.0:8080" into a dialable
// loopback address.
func localAddr(listen string) string {
	host, port, err := net.SplitHostPort(listen)
	if err != nil {
		return listen
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = "127.0.0.1"
	}
	return net.JoinHostPort(host, port)
}
