package main

import (
	"bytes"
	"encoding/base64"
	"flag"
	"io"
	"strings"
	"testing"
	"time"
)

// testKeyB64 is a syntactically valid relay key used where a test is not about keys.
var testKeyB64 = base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{7}, 32))

// parse runs parseFlags with env as the environment. Unless env mentions
// SWITCHTETHER_RELAY_KEYS, a valid key is provided so the test can focus on other options.
func parse(t *testing.T, args []string, env map[string]string) (options, error) {
	t.Helper()
	e := map[string]string{"SWITCHTETHER_RELAY_KEYS": testKeyB64}
	for k, v := range env {
		e[k] = v
	}
	return parseRaw(args, e)
}

func parseRaw(args []string, env map[string]string) (options, error) {
	fs := flag.NewFlagSet("test", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	opts, _, _, err := parseFlags(fs, args, func(k string) (string, bool) {
		v, ok := env[k]
		return v, ok
	})
	return opts, err
}

func TestDefaults(t *testing.T) {
	o, err := parse(t, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	c := o.cfg
	if o.tcpListen != ":47700" || o.httpListen != ":8080" || o.logLevel != "info" ||
		c.MaxLineBytes != 8192 || c.HandshakeTimeout != 10*time.Second || c.AcceptTimeout != 10*time.Second ||
		c.MaxPendingPerDesktop != 16 || c.MaxTunnelsPerDesktop != 32 || c.MaxDesktops != 10000 ||
		c.IdleTimeout != 10*time.Minute || c.BandwidthBytesPerSec != 0 ||
		c.PingInterval != 30*time.Second || c.DesktopTimeout != 90*time.Second {
		t.Fatalf("unexpected defaults: %+v", o)
	}
}

func TestEnvAndFlagPrecedence(t *testing.T) {
	env := map[string]string{
		"SWITCHTETHER_RELAY_TCP_LISTEN":              "",
		"SWITCHTETHER_RELAY_HTTP_LISTEN":             "127.0.0.1:9000",
		"SWITCHTETHER_RELAY_IDLE_TIMEOUT":            "2m",
		"SWITCHTETHER_RELAY_MAX_TUNNELS_PER_DESKTOP": "4",
		"SWITCHTETHER_RELAY_LOG_LEVEL":               "debug",
	}
	o, err := parse(t, []string{"-max-tunnels-per-desktop", "8", "-bandwidth", "1000"}, env)
	if err != nil {
		t.Fatal(err)
	}
	if o.tcpListen != "" || o.httpListen != "127.0.0.1:9000" || o.cfg.IdleTimeout != 2*time.Minute ||
		o.cfg.MaxTunnelsPerDesktop != 8 || o.cfg.BandwidthBytesPerSec != 1000 || o.logLevel != "debug" {
		t.Fatalf("unexpected options: %+v", o)
	}
}

func TestInvalidConfiguration(t *testing.T) {
	cases := []struct {
		args []string
		env  map[string]string
		want string
	}{
		{env: map[string]string{"SWITCHTETHER_RELAY_IDLE_TIMEOUT": "soon"}, want: "SWITCHTETHER_RELAY_IDLE_TIMEOUT"},
		{env: map[string]string{"SWITCHTETHER_RELAY_MAX_DESKTOPS": "many"}, want: "SWITCHTETHER_RELAY_MAX_DESKTOPS"},
		{args: []string{"-tcp-listen", "", "-http-listen", ""}, want: "both listeners"},
		{args: []string{"-tls-cert", "cert.pem"}, want: "together"},
		{args: []string{"-max-desktops", "0"}, want: "at least 1"},
		{args: []string{"-ping-interval", "2m"}, want: "longer than the ping interval"},
		{args: []string{"-max-line-bytes", "10"}, want: "max line bytes"},
		{args: []string{"stray"}, want: "unexpected arguments"},
	}
	for _, tc := range cases {
		if _, err := parse(t, tc.args, tc.env); err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("args %v env %v: err = %v, want it to mention %q", tc.args, tc.env, err, tc.want)
		}
	}
}

func TestLocalAddr(t *testing.T) {
	for in, want := range map[string]string{
		":8080":          "127.0.0.1:8080",
		"0.0.0.0:8080":   "127.0.0.1:8080",
		"[::]:8080":      "127.0.0.1:8080",
		"10.0.0.5:47700": "10.0.0.5:47700",
	} {
		if got := localAddr(in); got != want {
			t.Errorf("localAddr(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestVersionAndBadLogLevel(t *testing.T) {
	var out bytes.Buffer
	if code := run([]string{"-version"}, io.Discard, &out); code != 0 || !strings.Contains(out.String(), "switchtether-relay") {
		t.Fatalf("-version: code %d, output %q", code, out.String())
	}
	out.Reset()
	if code := run([]string{"-log-level", "loud", "-keys", testKeyB64}, io.Discard, &out); code != 2 {
		t.Fatalf("bad log level: code %d", code)
	}
}
