# Integrating your app

This guide takes you from nothing to two devices converging through a relay. It is the prose
version of the runnable `examples/` — every snippet below is lifted from the toy demo, so if a step
seems unclear, the working code is in `examples/example-client-kotlin` (Kotlin client) and
`examples/example-server-go` (Go relay). Run `examples/run-demo` to watch the whole thing happen.

There are four steps:

1. **Declare a registry** — one description of your synced shape, written once per language.
2. **Stand up a relay** — a Go server configured with that registry's schema hash.
3. **Wire a client** — a SQLite adapter + HTTP transport + the sync engine.
4. **Trigger sync** — call `syncOnce()` from your own scheduling layer.

The rule that ties it together: **both sides must produce the same `schema_hash`.** That agreement
is the whole contract; a mismatch is a `409` the server returns rather than risk corrupting data.

---

## 1. Declare your registry (both languages)

The registry is the single source of truth (see [schema-registry.md](schema-registry.md)). You
write it twice — once in Kotlin, once in Go — and a guard test on each side asserts they hash to the
same value. The two declarations describe the *same* tables and columns; order doesn't matter
(canonicalization sorts), and the column *types* must match (a `Text` here must be a `Text` there).

**Kotlin** (`io.rhizome.core`):

```kotlin
fun toyRegistry(): Registry = Registry(
    listOf(
        TableDef(
            name = "note", pk = "id", tombstone = "deleted_at",
            columns = listOf(
                ColumnDef("title", ColumnType.Text),
                ColumnDef("body", ColumnType.Text),
                ColumnDef("created_at", ColumnType.Timestamp),
                ColumnDef("deleted_at", ColumnType.Timestamp, nullable = true),
            ),
        ),
        // … one TableDef per synced table
    ),
)
```

**Go** (`server-go/registry`):

```go
func toyRegistry() registry.Registry {
    return registry.Registry{Tables: []registry.Table{
        {
            Name: "note", PK: "id", Tombstone: "deleted_at",
            Columns: []registry.Column{
                {Name: "title", Type: registry.Text},
                {Name: "body", Type: registry.Text},
                {Name: "created_at", Type: registry.Timestamp},
                {Name: "deleted_at", Type: registry.Timestamp, Nullable: true},
            },
        },
        // … same tables, same columns, same types
    }}
}
```

**Assert the agreement.** Add a guard test on each side so a future edit that silently moves the
hash fails loudly before it reaches a device:

```kotlin
assertEquals("099b9cba…", toyRegistry().schemaHash())   // Kotlin
```
```go
if got := toyRegistry().SchemaHash(); got != "099b9cba…" { t.Fatalf("hash drift: %s", got) }
```

Notes on the descriptor:
- `pk` is your row's primary-key column — a **client-minted ULID** (`TEXT`). It rides in the op's
  `pk` field, never in `cols`.
- `tombstone` names a nullable `Timestamp` column; a non-null value means "soft-deleted". Set it to
  `null` only if a table is never deleted from.
- `serverAuthoredOnly = true` (Kotlin) marks a table clients decode/apply but never *capture* — use
  it for server-generated rows (e.g. OCR output) to keep a structural single-writer guarantee.
- The data tables themselves are **yours** — the library never creates them. Create them however you
  normally do (the example just runs `CREATE TABLE IF NOT EXISTS …`). The adapter owns only its own
  `rhizome_*` bookkeeping tables.

---

## 2. Stand up a relay (Go)

The server is a dumb relay: registry → in-memory store → sync service → HTTP handler with auth.
That's the whole thing (the example `main.go` is ~100 lines including request logging):

```go
reg := toyRegistry()
store := syncstore.NewStore(reg.KnownCols())
svc := syncsvc.New(store, []string{reg.SchemaHash()}, 0)   // accepted hash set; 0 = default page limit
handler := synchttp.New(svc, auth.NewBasic(user, pass))    // single-account Basic auth

mux := http.NewServeMux()
mux.Handle("/sync/v1", handler)
http.ListenAndServe(":8080", mux)
```

- `syncsvc.New(store, hashes, limit)` takes the **set of accepted schema hashes** — pass just the
  current one normally, or `{current, predecessor}` during an upgrade grace window (see
  [schema-evolution.md](schema-evolution.md)).
- `auth.NewBasic(user, pass)` is single-account HTTP Basic. There is no tenancy: one server instance
  serves one user's devices. (`auth.AllowAll{}` permits every request — local demos/tests only.)
- The reference store is **in-memory** — fine for the example and tests. A durable relay is the
  server's responsibility; UltraBridge supplies a SQLite-backed one. (See [protocol.md](protocol.md)
  §server for the log/sequencing/`OpsSince` contract a durable store must honor.)

---

## 3. Wire a client (Kotlin)

The client has three injectable pieces. From the example's `main()`:

```kotlin
// (a) Bind the SQLite seam to your database, then build the adapter over your registry.
val handle: SqliteHandle = JdbcSqliteHandle.open(dbPath)   // your binding (see below)
val adapter = SqliteStorageAdapter(handle, registry)

// (b) Mint and persist a stable site id the first time; reuse it forever after.
val site = adapter.siteId() ?: Ulid.mint().also { adapter.enableSync(it) }

// (c) Build the transport and engine.
val config = SyncConfig.from(serverUrl, user, pass)!!      // → "<serverUrl>/sync/v1" + Basic header
val transport = HttpUrlTransport(config)
val engine = SyncEngine(adapter, transport, registry.schemaHash())
```

### The SqliteHandle binding

`rhizome-sqlite` codes against a tiny [`SqliteHandle`](../client-kotlin/rhizome-sqlite/src/main/kotlin/io/rhizome/sqlite/SqliteHandle.kt)
seam (`execute` / `query` / `transaction`), *not* a concrete driver — that's what keeps the module
pure-JVM and testable. You supply the binding:

- **On Android**, bind it to your `SupportSQLiteDatabase` (what ForestNote does at cutover).
- **On the JVM / in tests**, bind it to JDBC — the example's `JdbcSqliteHandle` is a complete,
  copy-pasteable reference.

`SqliteHandle` need not be thread-safe: per [`SyncLocalStore`](../client-kotlin/rhizome-core/src/main/kotlin/io/rhizome/core/SyncLocalStore.kt),
every call already lands on your app's single DB-writer thread.

### Capturing local changes

After you write a row to your own data table, tell the adapter to capture it into the outbox:

```kotlin
handle.execute("INSERT INTO note (id, title, body, created_at, deleted_at) VALUES (?,?,?,?,NULL)",
    listOf(id, title, body, System.currentTimeMillis()))
adapter.capture("note", id)          // reads the row, encodes it, enqueues an op (stamps the HLC)
```

A **delete** is just a capture after setting the tombstone column:

```kotlin
handle.execute("UPDATE note SET deleted_at = ? WHERE id = ?", listOf(System.currentTimeMillis(), id))
adapter.capture("note", id)          // the tombstone propagates like any other op
```

To enroll **pre-existing** rows the first time a device turns on sync, call `adapter.backfill()`
once — it enqueues an op per existing PK across all capturable tables. (Gate it so it runs once per
schema generation; ForestNote reuses its `SYNC_BACKFILL_VERSION` mechanism.)

### Running a sync

```kotlin
when (val result = engine.syncOnce()) {
    SyncResult.Success      -> { /* drained: pushed pending ops, applied & merged relayed ops */ }
    SyncResult.NotEnabled   -> { /* enableSync was never called on this device */ }
    SyncResult.AuthRequired -> { /* 401 — prompt for credentials, stop looping */ }
    SyncResult.SchemaMismatch -> { /* 409 — your hash isn't accepted; coordinate a bump */ }
    is SyncResult.Retryable -> { /* 5xx/transport — retry later with your own backoff */ }
    is SyncResult.Failed    -> { /* 400/413 — surface; do not loop */ }
}
```

`syncOnce()` is one full session: it loops `POST /sync/v1` internally while the server reports
`has_more`, draining the backlog, then returns. Relayed ops are merged into your data tables
transactionally under row-level LWW — you read current state straight from your own tables
(filtering `deleted_at IS NULL` for live rows). See [protocol.md](protocol.md) for the per-round
handling.

---

## 4. Triggering — your job, not the library's

**The engine has no timers and no backoff.** `SyncEngine` is pure orchestration; *when* to sync is
your app's decision, and `SyncResult` tells you how to react (back off on `Retryable`, prompt on
`AuthRequired`, stop on `Failed`). Typical triggers: on app foreground, after a local edit settles,
on a network-available callback, on a manual "sync now" button. ForestNote drives it from a
`SyncController` + a network monitor + an outbox drainer — that orchestration layer stays in the app;
it is not part of the library.

---

## Checklist

- [ ] Registry declared in both languages; a guard test pins the **same** `schema_hash` on each side.
- [ ] Data tables created by your app (the adapter owns only `rhizome_*` tables).
- [ ] `SqliteHandle` bound to your database (`SupportSQLiteDatabase` on Android, JDBC on the JVM).
- [ ] `enableSync(ulid)` called once with a persisted, stable per-install site id.
- [ ] Every local write followed by `adapter.capture(table, pk)`; deletes set the tombstone first.
- [ ] One-shot `backfill()` to enroll pre-existing rows.
- [ ] Server `syncsvc.New` configured with your accepted hash set; auth wired.
- [ ] A trigger layer that calls `syncOnce()` and reacts to `SyncResult`.

Following these steps reproduces `examples/run-demo`: two clients, one relay, converging through a
create, a conflicting edit (later write wins), and a delete (tombstone propagates).
