package main

import "testing"

// The toy registry MUST reproduce the schema hash documented in examples/toy-schema/README.md.
// Equal hashes here and in the Kotlin client's guard test are what prove the two independently
// written implementations agree on the synced shape (a mismatch would make the server 409).
const toySchemaHash = "099b9cbab8ce15f934ccf27e7638af84a84cd13d2c9cdf8df840cf98307b4ff9"

func TestToyRegistryReproducesDocumentedHash(t *testing.T) {
	got := toyRegistry().SchemaHash()
	if got != toySchemaHash {
		t.Fatalf("toy schema hash = %s, want %s (see examples/toy-schema/README.md)", got, toySchemaHash)
	}
}

func TestToyRegistryCanonicalString(t *testing.T) {
	want := "note:body,created_at,deleted_at,title;tag:created_at,deleted_at,label,note_id"
	if got := toyRegistry().Canonical(); got != want {
		t.Fatalf("canonical = %q, want %q", got, want)
	}
}
