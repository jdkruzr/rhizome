// Command example-server-go is a runnable RhizomeSync relay for the toy `note`+`tag` schema
// (examples/toy-schema). It wires the library's pieces end to end — registry → in-memory relay
// store → sync service → HTTP handler with single-account Basic auth — and is the server half of
// examples/run-demo. It is intentionally ~100 lines: standing up a relay is meant to be this small.
package main

import (
	"flag"
	"log"
	"net/http"
	"time"

	"github.com/jdkruzr/rhizome/server-go/auth"
	"github.com/jdkruzr/rhizome/server-go/registry"
	"github.com/jdkruzr/rhizome/server-go/synchttp"
	"github.com/jdkruzr/rhizome/server-go/syncstore"
	"github.com/jdkruzr/rhizome/server-go/syncsvc"
)

// toyRegistry declares the shared two-table demo shape (examples/toy-schema/README.md). Its
// SchemaHash is guarded against the documented value in main_test.go.
func toyRegistry() registry.Registry {
	ts := func(name string, nullable bool) registry.Column {
		return registry.Column{Name: name, Type: registry.Timestamp, Nullable: nullable}
	}
	return registry.Registry{Tables: []registry.Table{
		{
			Name: "note", PK: "id", Tombstone: "deleted_at",
			Columns: []registry.Column{
				{Name: "title", Type: registry.Text},
				{Name: "body", Type: registry.Text},
				ts("created_at", false),
				ts("deleted_at", true),
			},
		},
		{
			Name: "tag", PK: "id", Tombstone: "deleted_at",
			Columns: []registry.Column{
				{Name: "note_id", Type: registry.Text},
				{Name: "label", Type: registry.Text},
				ts("created_at", false),
				ts("deleted_at", true),
			},
		},
	}}
}

func main() {
	addr := flag.String("addr", ":8080", "listen address")
	user := flag.String("user", "demo", "Basic-auth username")
	pass := flag.String("pass", "demo", "Basic-auth password")
	flag.Parse()

	reg := toyRegistry()
	store := syncstore.NewStore(reg.KnownCols())
	svc := syncsvc.New(store, []string{reg.SchemaHash()}, 0)
	handler := synchttp.New(svc, auth.NewBasic(*user, *pass))

	mux := http.NewServeMux()
	mux.Handle("/sync/v1", logging(handler))
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("ok\n")) })

	log.Printf("rhizome toy relay: listening on %s", *addr)
	log.Printf("  schema_hash = %s", reg.SchemaHash())
	log.Printf("  auth        = Basic %s:%s", *user, *pass)
	if err := http.ListenAndServe(*addr, mux); err != nil {
		log.Fatalf("server error: %v", err)
	}
}

// logging is a tiny middleware so the demo's output shows each sync round arriving at the relay.
func logging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(sw, r)
		log.Printf("%s %s -> %d (%s)", r.Method, r.URL.Path, sw.status, time.Since(start).Round(time.Millisecond))
	})
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (s *statusWriter) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}
