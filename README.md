# RhizomeSync

A reusable, schema-driven, **multi-master** device↔server sync library for single-user /
multi-device apps. One user, several devices, a server as the shared substrate; every device
ends up holding the same data.

> A *rhizome* is an underground stem that spreads horizontally and sends up connected shoots
> from one shared root system — exactly multi-master peers sharing one relay.

## What it is

- **Append-only, full-row-snapshot ops.** Every change is a self-contained snapshot of one row,
  stamped `(table, pk, site_id, op_seq, op_ts, cols)`. Never a diff.
- **Row-level Last-Writer-Wins**, deterministic and arrival-order-independent, keyed on
  `(op_ts, op_seq, site_id)`. `op_ts` is a **Hybrid Logical Clock** so causality never inverts.
- **A deliberately dumb relay server.** It *sequences* the global op log and relays ops between
  devices; it never *adjudicates* conflicts (every replica runs the identical merge).
- **Schema-driven.** You declare your tables/columns in a registry; the wire codec, merge
  `knownCols`, capture, apply, backfill, and the schema-hash safety gate are all derived from it.
- **A schema-hash safety gate.** Replicas refuse to sync against a server that doesn't recognize
  their data shape, rather than risk corruption.

Two implementations, one frozen contract:

- `client-kotlin/` — pure-Kotlin/JVM client (engine + registry + SQLite adapter + HTTP transport).
- `server-go/` — Go relay server.
- `conformance/` — language-neutral JSON vectors; the executable definition of the contract.

## Quickstart

Watch two clients converge through a relay — create, a conflicting edit, and a delete:

```sh
cd examples && ./run-demo
```

Prerequisites: **Go ≥ 1.25** on your `PATH` and a **JDK** (the client builds via the Gradle
wrapper). No network access needed — the example server resolves the library through a local
`replace` directive and the Kotlin client is a subproject of `client-kotlin`. See
[`examples/README.md`](examples/README.md) for the step-by-step walkthrough and how to drive the
pieces by hand.

To integrate RhizomeSync into your own app, follow the [integration guide](spec/integrating.md).

## Documentation

- **[Integration guide](spec/integrating.md)** — from nothing to two converging devices: declare a
  registry, stand up a relay, wire a client, trigger sync.
- **[Architecture & rationale](spec/architecture.md)** — the design choices, where this sits vs.
  PowerSync / CRDTs / Couchbase, and the two owned trade-offs.
- **The contract** (`spec/`): [protocol](spec/protocol.md) · [merge (LWW)](spec/merge.md) ·
  [HLC](spec/hlc.md) · [schema registry](spec/schema-registry.md) ·
  [compaction](spec/compaction.md) · [schema evolution](spec/schema-evolution.md) ·
  [conformance policy](spec/conformance.md)
- **[Conformance vectors](conformance/README.md)** — the vector format and how each side consumes
  the single source of truth.
- **[Proposed assets-v1 contract](spec/assets-v1.md)** — Stage 1 specification for immutable binary
  transfer and bounded row I/O; **not shipped or part of current conformance results**.
  The [Stage 2A/B/C headless slices](../ForestNote/docs/test-plans/forestread-stage-2/README.md)
  implement chunk storage/transfer, negotiation, bounded rows, paged reference discovery and durable
  fair scheduling. Reader registries, host lifecycle/UI wiring and production capability activation
  remain pending; the legacy engine is not switched over.

## Not in scope (by design)

Sync **buckets** / partial replication, **PATCH** (partial-column) ops, and **multi-tenancy**.
Every device holds everything; one user per server instance. (These are documented upgrade paths,
not present in v1 — see the [architecture doc](spec/architecture.md).)

## Status

Pre-release. Extracted and generalized from the ForestNote↔UltraBridge sync engine. The library,
the Go relay, the HLC, server-side compaction, and the runnable examples are built and pass a
dual-language conformance suite; adopting it back into ForestNote + UltraBridge is the remaining
step. See `spec/` for the contract.

## Layout

```
spec/            the frozen contract (prose)
conformance/     language-neutral test vectors (single source of truth) + loader contract
client-kotlin/   rhizome-core, rhizome-sqlite, rhizome-http
server-go/       syncstore, syncsvc, synchttp, hlc, compaction, registry, auth
examples/        a toy schema + runnable client and server + run-demo
```

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
