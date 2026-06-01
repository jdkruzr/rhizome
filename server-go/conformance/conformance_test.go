// Package conformance runs the shared, language-neutral vectors in /conformance/vectors against
// the Go implementation. It is the Go half of the dual-language contract (the Kotlin client runs
// the same vectors). See /conformance/README.md for the format and the loader contract.
//
// Phase 0: this harness DISCOVERS and STRUCTURALLY VALIDATES every vector and dispatches on
// category. The actual `merge` assertion is skipped until syncstore.Merge lands (Phase 3); the
// skip is loud (t.Skip with a reason), never a silent pass.
package conformance

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// vectorsDir walks up from the test's working directory to find /conformance/vectors.
func vectorsDir(t *testing.T) string {
	t.Helper()
	dir, err := os.Getwd()
	if err != nil {
		t.Fatalf("getwd: %v", err)
	}
	for {
		cand := filepath.Join(dir, "conformance", "vectors")
		if fi, err := os.Stat(cand); err == nil && fi.IsDir() {
			return cand
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatalf("could not locate conformance/vectors above %q", dir)
		}
		dir = parent
	}
}

type wireOp struct {
	Table  string                     `json:"table"`
	PK     string                     `json:"pk"`
	SiteID string                     `json:"site_id"`
	OpSeq  int64                      `json:"op_seq"`
	OpTs   int64                      `json:"op_ts"`
	Cols   map[string]json.RawMessage `json:"cols"`
}

type vector struct {
	Category      string              `json:"category"`
	Name          string              `json:"name"`
	Description   string              `json:"description"`
	Ops           []wireOp            `json:"ops"`
	ExpectedState map[string][]wireOp `json:"expected_state"`
}

func loadVectors(t *testing.T) []struct {
	file string
	v    vector
} {
	t.Helper()
	dir := vectorsDir(t)
	matches, err := filepath.Glob(filepath.Join(dir, "*.vector.json"))
	if err != nil {
		t.Fatalf("glob: %v", err)
	}
	if len(matches) == 0 {
		t.Fatalf("no vectors found in %q", dir)
	}
	out := make([]struct {
		file string
		v    vector
	}, 0, len(matches))
	for _, m := range matches {
		b, err := os.ReadFile(m)
		if err != nil {
			t.Fatalf("read %s: %v", m, err)
		}
		var v vector
		if err := json.Unmarshal(b, &v); err != nil {
			t.Fatalf("parse %s: %v", filepath.Base(m), err)
		}
		out = append(out, struct {
			file string
			v    vector
		}{filepath.Base(m), v})
	}
	return out
}

// TestVectorsParseAndDispatch is the Phase-0 "running harness": every vector parses, has the
// fields its category needs, and dispatches. Real assertions arrive with the implementations.
func TestVectorsParseAndDispatch(t *testing.T) {
	for _, entry := range loadVectors(t) {
		entry := entry
		t.Run(entry.v.Name, func(t *testing.T) {
			if entry.v.Name == "" {
				t.Fatalf("%s: missing name", entry.file)
			}
			switch entry.v.Category {
			case "merge":
				if len(entry.v.Ops) == 0 {
					t.Fatalf("%s: merge vector has no ops", entry.file)
				}
				if entry.v.ExpectedState == nil {
					t.Fatalf("%s: merge vector has no expected_state", entry.file)
				}
				t.Skip("merge assertion pending syncstore.Merge (Phase 3)")
			case "":
				t.Fatalf("%s: missing category", entry.file)
			default:
				t.Skipf("category %q not yet handled by the Go runner", entry.v.Category)
			}
		})
	}
}
