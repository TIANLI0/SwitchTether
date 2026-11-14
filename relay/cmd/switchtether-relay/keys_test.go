package main

import (
	"bytes"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/TIANLI0/SwitchTether/relay/internal/relay"
)

func TestGenkey(t *testing.T) {
	var out, errOut bytes.Buffer
	seen := map[string]bool{}
	for range 3 {
		out.Reset()
		if code := run([]string{"genkey"}, &out, &errOut); code != 0 {
			t.Fatalf("genkey exit %d: %s", code, errOut.String())
		}
		k := strings.TrimSpace(out.String())
		if _, err := relay.ParseKey(k); err != nil || seen[k] {
			t.Fatalf("genkey printed an invalid or repeated key (%v)", err)
		}
		seen[k] = true
	}
	if code := run([]string{"genkey", "extra"}, io.Discard, io.Discard); code != 2 {
		t.Fatalf("genkey with arguments: exit %d", code)
	}
}

func TestKeysRequired(t *testing.T) {
	// No key anywhere: refuse to start.
	if _, err := parseRaw(nil, nil); !errors.Is(err, relay.ErrNoKeys) {
		t.Fatalf("without keys: %v", err)
	}
	var errOut bytes.Buffer
	if code := run([]string{"-tcp-listen", "127.0.0.1:0", "-http-listen", ""}, io.Discard, &errOut); code != 2 ||
		!strings.Contains(errOut.String(), "no relay key configured") {
		t.Fatalf("run without keys: exit %d, %q", code, errOut.String())
	}
	// -insecure-open (flag or env) allows it.
	o, err := parseRaw([]string{"-insecure-open"}, nil)
	if err != nil || !o.cfg.InsecureOpen || len(o.cfg.Keys) != 0 {
		t.Fatalf("-insecure-open: %v %v", o.cfg.InsecureOpen, err)
	}
	if o, err := parseRaw(nil, map[string]string{"SWITCHTETHER_RELAY_INSECURE_OPEN": "true"}); err != nil || !o.cfg.InsecureOpen {
		t.Fatalf("SWITCHTETHER_RELAY_INSECURE_OPEN: %v", err)
	}
	if _, err := parseRaw(nil, map[string]string{"SWITCHTETHER_RELAY_INSECURE_OPEN": "maybe"}); err == nil {
		t.Fatal("bad boolean accepted")
	}
}

func TestKeysFlagsAndFile(t *testing.T) {
	k1, _ := relay.GenerateKey()
	k2, _ := relay.GenerateKey()
	k3, _ := relay.GenerateKey()
	dir := t.TempDir()
	file := filepath.Join(dir, "keys")
	content := "# relay keys\r\n" + k2 + "   # alice\r\n\n  " + k3 + "\n# old: retired\n"
	if err := os.WriteFile(file, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	o, err := parseRaw([]string{"-keys", " " + k1 + " ,", "-keys-file", file}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(o.cfg.Keys) != 3 {
		t.Fatalf("loaded %d keys, want 3", len(o.cfg.Keys))
	}
	// Environment variables work too.
	o, err = parseRaw(nil, map[string]string{"SWITCHTETHER_RELAY_KEYS": k1 + "," + k2, "SWITCHTETHER_RELAY_KEYS_FILE": file})
	if err != nil || len(o.cfg.Keys) != 4 {
		t.Fatalf("env: %d keys, %v", len(o.cfg.Keys), err)
	}

	// Bad keys are reported by position, never echoed.
	bad := k1[:40]
	if _, err := parseRaw([]string{"-keys", k1 + "," + bad}, nil); err == nil ||
		!strings.Contains(err.Error(), "key #2") || strings.Contains(err.Error(), bad) {
		t.Fatalf("bad -keys entry: %v", err)
	}
	badFile := filepath.Join(dir, "bad")
	_ = os.WriteFile(badFile, []byte(k1+"\n"+bad+"\n"), 0o600)
	if _, err := parseRaw([]string{"-keys-file", badFile}, nil); err == nil ||
		!strings.Contains(err.Error(), "line 2") || strings.Contains(err.Error(), bad) {
		t.Fatalf("bad keys file: %v", err)
	}
	if _, err := parseRaw([]string{"-keys-file", filepath.Join(dir, "missing")}, nil); err == nil {
		t.Fatal("missing keys file accepted")
	}
	// A keys file with only comments means no keys.
	empty := filepath.Join(dir, "empty")
	_ = os.WriteFile(empty, []byte("# nothing yet\n"), 0o600)
	if _, err := parseRaw([]string{"-keys-file", empty}, nil); !errors.Is(err, relay.ErrNoKeys) {
		t.Fatalf("comment-only keys file: %v", err)
	}
}

func TestReloadKeys(t *testing.T) {
	k1, _ := relay.GenerateKey()
	k2, _ := relay.GenerateKey()
	file := filepath.Join(t.TempDir(), "keys")
	_ = os.WriteFile(file, []byte(k1+"\n"), 0o600)
	o, err := parseRaw([]string{"-keys-file", file}, nil)
	if err != nil {
		t.Fatal(err)
	}
	var logs bytes.Buffer
	log, _ := newLogger(&logs, "info", "text")
	srv, err := relay.NewServer(o.cfg, log)
	if err != nil {
		t.Fatal(err)
	}
	_ = os.WriteFile(file, []byte(k1+"\n"+k2+"\n"), 0o600)
	reloadKeys(srv, o, log)
	if !strings.Contains(logs.String(), "relay keys reloaded") || !strings.Contains(logs.String(), "count=2") {
		t.Fatalf("reload log: %s", logs.String())
	}
	// A broken file or an empty one keeps the old keys.
	_ = os.WriteFile(file, []byte("garbage\n"), 0o600)
	reloadKeys(srv, o, log)
	_ = os.WriteFile(file, []byte("# empty\n"), 0o600)
	reloadKeys(srv, o, log)
	if n := strings.Count(logs.String(), "keeping the previous keys"); n != 2 {
		t.Fatalf("expected two refused reloads, got %d: %s", n, logs.String())
	}
	for _, k := range []string{k1, k2} {
		if strings.Contains(logs.String(), k) {
			t.Fatal("logs contain a relay key")
		}
	}
}
