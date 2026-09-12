package syncstore

import (
	"encoding/json"
	"github.com/jdkruzr/rhizome/server-go/bounded"
	"strings"
	"testing"
)

func TestBoundedRefusalUndoesOnlyTheNewRelaySuffix(t *testing.T) {
	s := NewStore(knownCols)
	a, b := pad26("A"), pad26("B")
	large := strokeOp(t, b, pad26("ST"), 1, 1)
	large.Cols["points"] = json.RawMessage(`"` + strings.Repeat("A", 2048) + `"`)
	s.ApplyBatch(b, []Op{large})
	push := strokeOp(t, a, pad26("ST2"), 1, 2)
	_, err := s.ExchangeBounded(a, 0, []Op{push}, bounded.Limits{500, 1024, 1024, 2048})
	if e, ok := err.(*bounded.Error); !ok || e.Status != 413 {
		t.Fatal(err)
	}
	if s.LastSeq() != 1 || len(s.seen) != 1 || s.acked[a] != 0 {
		t.Fatal("refusal mutated relay")
	}
	if got := s.ApplyBatch(a, []Op{push}); got.AcceptedThrough != 1 {
		t.Fatal(got)
	}
	if s.LastSeq() != 2 {
		t.Fatal("retry did not append")
	}
}
