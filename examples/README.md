# RhizomeSync examples

A runnable end-to-end demo: a Go relay server and a Kotlin CLI client, both syncing the same
two-table [toy schema](toy-schema/), proving that two independent clients converge through a dumb
relay. This is the "anyone could use it" proof and a living quickstart for integrating the library.

```
examples/
  toy-schema/            # the shared note+tag schema (spec + canonical string + schema hash)
  example-server-go/     # a ~100-line relay: registry → store → service → HTTP + Basic auth
  example-client-kotlin/ # a CLI: registry + SqliteStorageAdapter + HttpUrlTransport + SyncEngine
  run-demo               # boots the server and drives two clients through create/conflict/delete
```

## Quick start

```sh
./run-demo
```

Prerequisites: **Go ≥ 1.25** on your `PATH` and a **JDK** (the client builds via the Gradle
wrapper). No network access is needed — the example server module resolves the library through a
local `replace` directive, and the Kotlin client is a subproject of `client-kotlin`. Pass
`--port N` to change the port, or `--skip-build` to reuse an existing build.

The script walks through five steps and prints what each client sees:

1. **Alice creates** a note and syncs (push).
2. **Bob syncs** and lists it — the create propagated through the relay.
3. **Conflict:** Alice and Bob both edit the *same* note (Bob writes last).
4. **Both converge** on the later write — row-level Last-Writer-Wins, resolved identically on
   every replica (the relay only sequences; it never adjudicates).
5. **Alice deletes** the note; Bob syncs and the **tombstone** propagates — both end empty.

## Running the pieces by hand

Start the relay (logs each sync round, prints its schema hash and demo credentials):

```sh
cd example-server-go && go run .            # listens on :8080, Basic demo:demo
```

Drive a client (each `--db` file is one independent device; site id is minted once and persisted):

```sh
cd ../client-kotlin
./gradlew :example-client-kotlin:installDist
CLI=../examples/example-client-kotlin/build/install/example-client-kotlin/bin/example-client-kotlin

$CLI --db /tmp/alice.db create --title "Groceries" --body "milk, eggs"
$CLI --db /tmp/bob.db   sync
$CLI --db /tmp/bob.db   list
```

Commands: `create --title T [--body B]`, `edit --id ID [--title T] [--body B]`,
`delete --id ID`, `tag --note ID --label L`, `sync`, `list`, `ids`. Global options:
`--db <path>` (required), `--server <url>` (default `http://localhost:8080`), `--user`/`--pass`
(default `demo`/`demo`). Sync/HTTP logs go to stderr; command output to stdout.

## What the demo demonstrates about the contract

- **One declarative registry per side** drives capture, the wire codec, apply, and the schema
  hash. The Go and Kotlin registries are written independently yet produce the **same schema
  hash** (`examples/toy-schema/README.md`) — a guard test on each side asserts it. A mismatch is
  exactly what the server's `409` gate would catch.
- **The server is a dumb relay**: it sequences ops into a global log and serves each device the
  ops it hasn't seen. Conflict resolution is the deterministic merge run by every replica, which
  is why convergence does not depend on the server.
- **Deletes are tombstones**, not row removals — they propagate like any other op.
