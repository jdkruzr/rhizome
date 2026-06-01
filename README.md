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

## Not in scope (by design)

Sync **buckets** / partial replication, **PATCH** (partial-column) ops, and **multi-tenancy**.
Every device holds everything; one user per server instance. (These are documented upgrade paths,
not present in v1 — see `spec/` Appendix discussion.)

## Status

Pre-release. Extracted and generalized from the ForestNote↔UltraBridge sync engine. See
`spec/` for the contract and the repo's implementation plan for phase status.

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
