package relay

import (
	"bufio"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"strings"
)

// ProtoVersion is the first token of every first line (docs/RELAY.md §2).
const ProtoVersion = "ST-RELAY/1"

const protoPrefix = "ST-RELAY/"

// Error codes (docs/RELAY.md §3).
const (
	CodeBadRequest  = "bad_request"
	CodeAuthFailed  = "auth_failed"
	CodeOffline     = "offline"
	CodeRejected    = "rejected"
	CodeTimeout     = "timeout"
	CodeBusy        = "busy"
	CodeUnknownConn = "unknown_conn"
	CodeVersion     = "version"
)

// NoToken is what a phone sends (and the relay forwards) when it has no relay token.
const NoToken = "-"

// maxTokenLen bounds the opaque phone token. The desktop generates 32 random bytes
// (43 base64url chars); the extra room keeps the relay from dictating the token format.
const maxTokenLen = 128

// connIDLen is the base64url (no padding) length of a 16-byte connection id.
const connIDLen = 22

// ProtoError is a protocol failure that is reported to the peer as `ERR <code> <text>`.
type ProtoError struct {
	Code string
	Text string
}

func (e *ProtoError) Error() string {
	if e.Text == "" {
		return e.Code
	}
	return e.Code + ": " + e.Text
}

// Line renders the ERR line (without the trailing newline).
func (e *ProtoError) Line() string {
	if e.Text == "" {
		return "ERR " + e.Code
	}
	return "ERR " + e.Code + " " + e.Text
}

func protoErr(code, text string) *ProtoError { return &ProtoError{Code: code, Text: text} }

// ErrLineTooLong is returned by ReadLine when a line exceeds the configured limit.
var ErrLineTooLong = errors.New("handshake line too long")

// ReadLine reads one `\n`-terminated line from br. The line (without `\n`, and without an
// optional preceding `\r`) must fit into br's buffer; br must therefore be created with
// bufio.NewReaderSize(conn, maxLine+1). Only printable ASCII is accepted.
func ReadLine(br *bufio.Reader) (string, error) {
	b, err := br.ReadSlice('\n')
	if err != nil {
		if errors.Is(err, bufio.ErrBufferFull) {
			return "", ErrLineTooLong
		}
		if errors.Is(err, io.EOF) && len(b) > 0 {
			return "", io.ErrUnexpectedEOF
		}
		return "", err
	}
	b = b[:len(b)-1]
	if n := len(b); n > 0 && b[n-1] == '\r' {
		b = b[:n-1]
	}
	for _, c := range b {
		if c < 0x20 || c > 0x7e {
			return "", protoErr(CodeBadRequest, "non-ASCII byte in line")
		}
	}
	return string(b), nil
}

// Hello kinds.
const (
	KindListen  = "LISTEN"
	KindAccept  = "ACCEPT"
	KindConnect = "CONNECT"
)

// Hello is the parsed first line of a connection.
type Hello struct {
	Kind  string // KindListen, KindAccept or KindConnect
	FP    string // desktop fingerprint, 64 lowercase hex
	Conn  string // ACCEPT only
	Token string // CONNECT only; NoToken when absent
}

// ParseHello parses the first line of a connection:
//
//	ST-RELAY/1 LISTEN <fp>
//	ST-RELAY/1 ACCEPT <fp> <conn>
//	ST-RELAY/1 CONNECT <fp> [<token>]
func ParseHello(line string) (Hello, error) {
	f := strings.Split(line, " ")
	if len(f) == 0 || !strings.HasPrefix(f[0], protoPrefix) {
		return Hello{}, protoErr(CodeBadRequest, "not a relay handshake")
	}
	if f[0] != ProtoVersion {
		return Hello{}, protoErr(CodeVersion, "this relay speaks "+ProtoVersion)
	}
	if len(f) < 3 {
		return Hello{}, protoErr(CodeBadRequest, "missing arguments")
	}
	h := Hello{Kind: f[1], FP: f[2]}
	if !ValidFingerprint(h.FP) {
		return Hello{}, protoErr(CodeBadRequest, "malformed fingerprint")
	}
	switch h.Kind {
	case KindListen:
		if len(f) != 3 {
			return Hello{}, protoErr(CodeBadRequest, "LISTEN takes one argument")
		}
	case KindAccept:
		if len(f) != 4 {
			return Hello{}, protoErr(CodeBadRequest, "ACCEPT takes two arguments")
		}
		h.Conn = f[3]
		if !ValidConnID(h.Conn) {
			return Hello{}, protoErr(CodeBadRequest, "malformed connection id")
		}
	case KindConnect:
		switch len(f) {
		case 3:
			h.Token = NoToken
		case 4:
			h.Token = f[3]
		default:
			return Hello{}, protoErr(CodeBadRequest, "CONNECT takes at most two arguments")
		}
		if !ValidToken(h.Token) {
			return Hello{}, protoErr(CodeBadRequest, "malformed token")
		}
	default:
		return Hello{}, protoErr(CodeBadRequest, "unknown command")
	}
	return h, nil
}

// ParseAuth parses `AUTH <cert> <sig>` and returns the decoded certificate DER and signature.
func ParseAuth(line string) (certDER, sig []byte, err error) {
	f := strings.Split(line, " ")
	if len(f) != 3 || f[0] != "AUTH" {
		return nil, nil, protoErr(CodeBadRequest, "expected AUTH <cert> <sig>")
	}
	certDER, err = decodeB64URL(f[1])
	if err != nil || len(certDER) == 0 {
		return nil, nil, protoErr(CodeBadRequest, "malformed certificate encoding")
	}
	sig, err = decodeB64URL(f[2])
	if err != nil || len(sig) == 0 {
		return nil, nil, protoErr(CodeBadRequest, "malformed signature encoding")
	}
	return certDER, sig, nil
}

// Control-line kinds sent by the desktop after OK.
const (
	CtlPing   = "PING"
	CtlPong   = "PONG"
	CtlReject = "REJECT"
)

// ControlLine is a parsed line received on an authenticated control connection.
type ControlLine struct {
	Kind string
	Conn string // REJECT only
}

// ParseControlLine parses PING, PONG and REJECT <conn>.
func ParseControlLine(line string) (ControlLine, error) {
	f := strings.Split(line, " ")
	switch {
	case len(f) == 1 && (f[0] == CtlPing || f[0] == CtlPong):
		return ControlLine{Kind: f[0]}, nil
	case len(f) == 2 && f[0] == CtlReject:
		if !ValidConnID(f[1]) {
			return ControlLine{}, protoErr(CodeBadRequest, "malformed connection id")
		}
		return ControlLine{Kind: CtlReject, Conn: f[1]}, nil
	}
	return ControlLine{}, protoErr(CodeBadRequest, "unknown control line")
}

// ValidFingerprint reports whether s is 64 lowercase hex characters.
func ValidFingerprint(s string) bool {
	if len(s) != 64 {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}

// ValidConnID reports whether s is a base64url (no padding) encoding of 16 bytes.
func ValidConnID(s string) bool {
	if len(s) != connIDLen {
		return false
	}
	b, err := base64.RawURLEncoding.Strict().DecodeString(s)
	return err == nil && len(b) == 16
}

// ValidToken reports whether s is NoToken or 1..128 base64url alphabet characters.
func ValidToken(s string) bool {
	if s == NoToken {
		return true
	}
	if len(s) == 0 || len(s) > maxTokenLen {
		return false
	}
	for i := 0; i < len(s); i++ {
		if !isB64URL(s[i]) {
			return false
		}
	}
	return true
}

func isB64URL(c byte) bool {
	return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_'
}

// decodeB64URL decodes base64url, tolerating (but not requiring) `=` padding.
func decodeB64URL(s string) ([]byte, error) {
	return base64.RawURLEncoding.DecodeString(strings.TrimRight(s, "="))
}

// ShortFP returns the first 8 hex characters of a fingerprint, the only form that is logged.
func ShortFP(fp string) string {
	if len(fp) > 8 {
		return fp[:8]
	}
	return fp
}

// shortID shortens a connection id for logs.
func shortID(id string) string {
	if len(id) > 6 {
		return id[:6]
	}
	return id
}

func incomingLine(conn, token string) string { return fmt.Sprintf("INCOMING %s %s", conn, token) }
