# Shared atomic incoming-response policies

`SqliteStorageAdapter(..., incomingPolicies = listOf(...))` lets hosts extend the existing
bounded response commit without implementing another sync engine or copying cursor/ACK logic.
There is no ForestNote, notebook, reader or handwriting knowledge in Rhizome's hook.

An `IncomingRowPolicy` declares its registered tables. Duplicate claims and unknown table names
fail at construction. Unclaimed tables continue through ordinary registry-driven LWW. For claimed
tables, every received operation reaches the policy, including older versions from the same page;
Rhizome does not collapse their history before the domain can validate it.

1. `prepare(ops)` runs before opening the response transaction. It may suspend/dispatch CPU work,
   but must not mutate storage, change sync state, or perform external effects.
2. Its `PreparedIncomingRows.commit(db)` runs synchronously on the host's DB-writer thread, using
   the exact handle owned by the adapter, inside the same transaction as every other policy,
   ordinary-row merge, HLC persistence, acknowledged-outbox pruning and authoritative cursor update.
3. The policy must durably retain every supplied operation (applied, pending or quarantined), or
   throw. If any commit step fails, the whole response rolls back. Preparation cancellation/failure
   never starts the transaction. No policy owns or separately updates the sync cursor/ACK.

Receipt advances the shared causal clock even if a policy defers application. A subsequent local
edit therefore sorts after received pending work. Later domain draining uses the original operation
versions with `applyRelayed`, not `capture`, so it does not create a new author or outbox operation.

The host must supply real reentrant transactions and serialize exchanges through one storage owner;
do not hold a transaction around `prepare`, run multiple active adapters over one library, or execute
network/render work in `commit`. The hook is a trusted host extension, not a sandbox: a callback that
silently drops operations or commits through another connection violates its contract.

Only `acceptResponse`, used by `BoundedSyncSession`, routes through these policies. Raw `applyRelayed`
remains the low-level LWW primitive for already-validated/deferred work and existing legacy users.
Legacy `SyncEngine` calls the separate apply/ACK/cursor methods and MUST NOT be used as the ingress
path for a policy-enabled domain. No server rejection is implied by local quarantine.

Tests cover two independent domain policies plus ordinary rows, preparation cancellation, late policy
failure, cursor-write rollback, clock persistence, and actual UB HTTP retry after the server accepted
an upload but the client's response commit failed. No protocol/hash change or production activation.
