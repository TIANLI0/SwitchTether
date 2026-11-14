package relay

import (
	"bufio"
	"errors"
	"io"
	"strings"
	"testing"
)

const testFP = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

func TestParseHello(t *testing.T) {
	conn := "AAAAAAAAAAAAAAAAAAAAAA" // 16 zero bytes
	tests := []struct {
		line string
		want Hello
		code string
	}{
		{line: "ST-RELAY/1 LISTEN " + testFP, want: Hello{Kind: KindListen, FP: testFP}},
		{line: "ST-RELAY/1 ACCEPT " + testFP + " " + conn, want: Hello{Kind: KindAccept, FP: testFP, Conn: conn}},
		{line: "ST-RELAY/1 CONNECT " + testFP + " tok_en-123", want: Hello{Kind: KindConnect, FP: testFP, Token: "tok_en-123"}},
		{line: "ST-RELAY/1 CONNECT " + testFP + " -", want: Hello{Kind: KindConnect, FP: testFP, Token: NoToken}},
		{line: "ST-RELAY/1 CONNECT " + testFP, want: Hello{Kind: KindConnect, FP: testFP, Token: NoToken}},

		{line: "ST-RELAY/2 LISTEN " + testFP, code: CodeVersion},
		{line: "ST-RELAY/10 CONNECT " + testFP, code: CodeVersion},
		{line: "GET / HTTP/1.1", code: CodeBadRequest},
		{line: "", code: CodeBadRequest},
		{line: "ST-RELAY/1", code: CodeBadRequest},
		{line: "ST-RELAY/1 LISTEN", code: CodeBadRequest},
		{line: "ST-RELAY/1 HELLO " + testFP, code: CodeBadRequest},
		{line: "ST-RELAY/1 LISTEN " + strings.ToUpper(testFP), code: CodeBadRequest},
		{line: "ST-RELAY/1 LISTEN " + testFP[:63], code: CodeBadRequest},
		{line: "ST-RELAY/1 LISTEN " + testFP + "0", code: CodeBadRequest},
		{line: "ST-RELAY/1 LISTEN " + testFP[:63] + "g", code: CodeBadRequest},
		{line: "ST-RELAY/1 LISTEN " + testFP + " extra", code: CodeBadRequest},
		{line: "ST-RELAY/1  LISTEN " + testFP, code: CodeBadRequest},
		{line: "ST-RELAY/1 ACCEPT " + testFP, code: CodeBadRequest},
		{line: "ST-RELAY/1 ACCEPT " + testFP + " short", code: CodeBadRequest},
		{line: "ST-RELAY/1 ACCEPT " + testFP + " AAAAAAAAAAAAAAAAAAAAAB", code: CodeBadRequest}, // non-canonical padding bits
		{line: "ST-RELAY/1 ACCEPT " + testFP + " AAAAAAAAAAAAAAAAAAAA+A", code: CodeBadRequest},
		{line: "ST-RELAY/1 CONNECT " + testFP + " tok en", code: CodeBadRequest},
		{line: "ST-RELAY/1 CONNECT " + testFP + " tok=en", code: CodeBadRequest},
		{line: "ST-RELAY/1 CONNECT " + testFP + " " + strings.Repeat("a", 129), code: CodeBadRequest},
	}
	for _, tc := range tests {
		got, err := ParseHello(tc.line)
		if tc.code == "" {
			if err != nil {
				t.Errorf("ParseHello(%q) error: %v", tc.line, err)
			} else if got != tc.want {
				t.Errorf("ParseHello(%q) = %+v, want %+v", tc.line, got, tc.want)
			}
			continue
		}
		var pe *ProtoError
		if !errors.As(err, &pe) || pe.Code != tc.code {
			t.Errorf("ParseHello(%q) error = %v, want code %s", tc.line, err, tc.code)
		}
	}
}

func TestParseControlLine(t *testing.T) {
	conn := "AAAAAAAAAAAAAAAAAAAAAA"
	for _, ok := range []string{"PING", "PONG", "REJECT " + conn} {
		if _, err := ParseControlLine(ok); err != nil {
			t.Errorf("ParseControlLine(%q): %v", ok, err)
		}
	}
	cl, _ := ParseControlLine("REJECT " + conn)
	if cl.Kind != CtlReject || cl.Conn != conn {
		t.Errorf("REJECT parsed as %+v", cl)
	}
	for _, bad := range []string{"", "ping", "PING x", "REJECT", "REJECT abc", "ACCEPT " + conn, "INCOMING " + conn + " -"} {
		if _, err := ParseControlLine(bad); err == nil {
			t.Errorf("ParseControlLine(%q) accepted", bad)
		}
	}
}

func TestParseAuth(t *testing.T) {
	cert, sig, err := ParseAuth("AUTH AQID BAUG")
	if err != nil || string(cert) != "\x01\x02\x03" || string(sig) != "\x04\x05\x06" {
		t.Fatalf("ParseAuth = %x %x %v", cert, sig, err)
	}
	if _, _, err := ParseAuth("AUTH AQI= BAU="); err != nil {
		t.Errorf("padded base64url rejected: %v", err)
	}
	for _, bad := range []string{"AUTH", "AUTH AQID", "AUTH AQID BAUG x", "auth AQID BAUG", "AUTH AQ+D BAUG", "AUTH AQID  BAUG", "AUTH  BAUG"} {
		if _, _, err := ParseAuth(bad); err == nil {
			t.Errorf("ParseAuth(%q) accepted", bad)
		}
	}
}

func TestReadLine(t *testing.T) {
	br := bufio.NewReaderSize(strings.NewReader("one\ntwo\r\n\nthree"), 17)
	for _, want := range []string{"one", "two", ""} {
		got, err := ReadLine(br)
		if err != nil || got != want {
			t.Fatalf("ReadLine = %q, %v; want %q", got, err, want)
		}
	}
	if _, err := ReadLine(br); !errors.Is(err, io.ErrUnexpectedEOF) {
		t.Fatalf("unterminated last line: err = %v", err)
	}
	if _, err := ReadLine(br); !errors.Is(err, io.EOF) {
		t.Fatalf("after EOF: err = %v", err)
	}

	// Limit: a reader of size max+1 accepts exactly max bytes before the newline.
	const max = 16
	ok := strings.Repeat("x", max) + "\n"
	if got, err := ReadLine(bufio.NewReaderSize(strings.NewReader(ok), max+1)); err != nil || len(got) != max {
		t.Fatalf("line of max length: %q %v", got, err)
	}
	long := strings.Repeat("x", max+1) + "\n"
	if _, err := ReadLine(bufio.NewReaderSize(strings.NewReader(long), max+1)); !errors.Is(err, ErrLineTooLong) {
		t.Fatalf("line over max: err = %v", err)
	}

	if _, err := ReadLine(bufio.NewReader(strings.NewReader("a\x00b\n"))); err == nil {
		t.Fatal("NUL byte accepted")
	}
	if _, err := ReadLine(bufio.NewReader(strings.NewReader("caf\xc3\xa9\n"))); err == nil {
		t.Fatal("non-ASCII accepted")
	}
}

func TestProtoErrorLine(t *testing.T) {
	if got := protoErr(CodeOffline, "").Line(); got != "ERR offline" {
		t.Errorf("got %q", got)
	}
	if got := protoErr(CodeBusy, "too many desktops").Line(); got != "ERR busy too many desktops" {
		t.Errorf("got %q", got)
	}
}

func TestShortFP(t *testing.T) {
	if ShortFP(testFP) != "01234567" {
		t.Fatal(ShortFP(testFP))
	}
}
