package assets

import (
	"net/http/httptest"
	"strings"
	"testing"
)

func TestMalformedDescriptorNeverReachesStore(t *testing.T) {
	id := Digest(nil)
	body := `{"asset_id":"` + id + `","byte_length":"0","chunk_bytes":262144}`
	for _, tc := range []struct {
		name, body string
		status     int
	}{
		{"second_json", body + `{}`, 400},
		{"oversize_whitespace_suffix", body + strings.Repeat(" ", 4096), 413},
		{"oversize_body", strings.Repeat(" ", 4097), 413},
		{"noncanonical_length", strings.Replace(body, `"0"`, `"+0"`, 1), 400},
		{"int64_overflow", strings.Replace(body, `"0"`, `"9223372036854775808"`, 1), 400},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest("PUT", "/sync/assets/v1/"+id, strings.NewReader(tc.body))
			w := httptest.NewRecorder()
			// A nil store would panic if any rejected request reached storage.
			NewHandler(nil).ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Fatalf("got %d, want %d: %s", w.Code, tc.status, w.Body.String())
			}
		})
	}
}
