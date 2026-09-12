package bounded

import (
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestPageCountsActualEscapedUTF8Envelope(t *testing.T) {
	l := Limits{500, 300, 2000, 2500}
	p, err := NewPage(42, 1, nil, l)
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := json.Marshal(map[string]string{"text": strings.Repeat("🙂<&>漢字", 50)})
	ok, err := p.Add(43, "B", 1, int64(len(raw)), raw)
	if err != nil || !ok {
		t.Fatal(ok, err)
	}
	ok, err = p.Add(44, "B", 2, 2, json.RawMessage(`{}`))
	if err != nil || ok {
		t.Fatal(ok, err)
	}
	b, err := p.Encode()
	if err != nil || len(b) > l.MaxBodyBytes || len(b) <= l.TargetPageBytes {
		t.Fatal(len(b), err)
	}
	if p.Response.Cursor != 43 || !p.Response.HasMore {
		t.Fatal(p.Response)
	}
}

func TestImpossibleBudgetsAndOversizedRequestAreRejected(t *testing.T) {
	for _, header := range []string{"0", "-1", "255", "abc", "9223372036854775808"} {
		r := httptest.NewRequest("POST", "/sync/v1", strings.NewReader(`{}`))
		r.Header.Set(Header, "1")
		r.Header.Set("X-Rhizome-Max-Response-Bytes", header)
		if _, _, err := ReadRequest(httptest.NewRecorder(), r, Defaults()); err == nil {
			t.Fatalf("accepted %q", header)
		}
	}
	l := Limits{500, 256, 256, 256}
	r := httptest.NewRequest("POST", "/sync/v1", strings.NewReader(strings.Repeat(" ", 257)))
	r.Header.Set(Header, "1")
	_, _, err := ReadRequest(httptest.NewRecorder(), r, l)
	if e, ok := err.(*Error); !ok || e.Status != 413 {
		t.Fatal(err)
	}
}

func TestFirstOversizedRowIsActionableAndDoesNotAdvancePage(t *testing.T) {
	p, _ := NewPage(42, 7, nil, Limits{500, 256, 256, 512})
	_, err := p.Add(43, "B", 9, 9000000, nil)
	e, ok := err.(*Error)
	if !ok || e.Code != "oversized_op" || e.OpSeq != 9 {
		t.Fatal(err)
	}
	if p.Response.Cursor != 42 || len(p.Response.Ops) != 0 {
		t.Fatal(p.Response)
	}
}
