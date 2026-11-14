package main

import (
	"bytes"
	"flag"
	"io"
	"strings"
	"testing"
	"time"
)

func parse(t *testing.T, args []string, env map[string]string) (options, error) {
	t.Helper()
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
	if code := run([]string{"-version"}, &out); code != 0 || !strings.Contains(out.String(), "switchtether-relay") {
		t.Fatalf("-version: code %d, output %q", code, out.String())
	}
	out.Reset()
	if code := run([]string{"-log-level", "loud"}, &out); code != 2 {
		t.Fatalf("bad log level: code %d", code)
	}
}
