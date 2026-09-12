# Explicit local authorship, independent of sync opt-in

The experimental ForestRead host requires final row versions before a user enables sync.
`SqliteStorageAdapter` now offers `bindLocalAuthor(siteId)` and `captureAuthored(table, pk, siteId)`.
Existing `capture` behavior is unchanged: it does nothing while sync is dormant.

Local authorship uses the **same** HLC, per-author operation sequence, wire encoding and
`rhizome_row_meta` as enabled capture. Full operations are retained in `rhizome_outbox` as a local
journal. `siteId()` stays null; `pendingOps`, `pendingPage` and `hasPending` expose no outgoing work
until explicit `enableSync` with that author. ACK APIs refuse to prune this dormant history.
None of these APIs creates a transport, scheduler, permission or network request.

Host requirements:

- Use one adapter/clock and a real reentrant transaction implementation on the shared DB writer.
  Include the domain write and `captureAuthored` in the same outer transaction. Local commands
  supply their own idempotency; repeating a capture is a new operation.
- On bootstrap, reuse `localAuthorId()` (or an already-enabled `siteId`) before minting an identity.
  Binding is durable and cannot change. An eventual join must use it, not mint a replacement.
  A copied database is not automatically an independently authored replica; backup/clone/site-reset
  policies still require explicit host design and qualification.
- Keep every journal operation until acknowledged, including superseded row values. The server's
  contiguous ACK cannot pass a missing sequence. A failed transaction does not consume a sequence.
  This trades storage growth for correct initial replay; do not add ad hoc coalescing/compaction.
- Enabling sync exposes the original journal without scanning, copying or restamping it.
  `backfillUntracked` skips both these locally authored rows and received rows. Full `backfill`
  rejects bound local-author libraries because restamping can reorder semantic property winners.
- Pull-first join still uses generic LWW against final local provenance, and absorbs remote clocks.
  Genuine newer remote changes may win; mere opt-in/backfill must not change a row version.
- Unversioned legacy domain rows have no recoverable chronology. The host must qualify recovery
  or import; an arbitrary table/ID scan cannot manufacture historical order. ForestRead refuses
  to open such experimental reader data and preserves its database for explicit recovery.

No registry hash or wire protocol changes. These client sources are tested through ForestNote's
headless Stage 2 runner, including offline history uploaded to the actual disposable UB HTTP
handlers in two-operation pages, contiguous acknowledgements, and exact provenance on a second
replica. Production artifact publication, Android adoption, cursor/ingress integration, and
server-reset/schema-reset recovery are not activated by this API.
