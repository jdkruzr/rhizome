# Rhizome — remaining steps

Updated 2026-09-27 UTC. Read [current state](current-state.md) for the implemented
integration checkpoint and [the project map](../../AragoniteAlexandriaServer/docs/project-map.md)
for ownership. This is primarily a contract/qualification/publication checklist;
it is not a request to build a replacement sync engine. Cloud porting/deployment
is paused until the user explicitly resumes it.

## R1. Reconcile integration status and executable acceptance

- [ ] Inventory implemented assets/bounded exchange/offline-authoring/admission
  behavior against `spec/` and the pending acceptance catalog. Mark each item as
  executed, host-qualified, intentionally deferred or unimplemented, with evidence.
- [ ] Connect required pending acceptance cases to executable assertions before
  promoting them to release conformance. A structurally valid catalog is not a pass.
- [ ] Update stale README/adoption statements to distinguish the integration
  branch from published releases and host activation, without changing frozen
  protocol meaning to fit implementation accidentally.
- [ ] Run fresh Kotlin and Go conformance suites, then the owning application's
  required actual-HTTP/process-loss/asset tests for the selected release revision.

The [conformance policy](../conformance/README.md),
[pending policy](../conformance/pending/README.md),
[atomic receipt](../client-kotlin/INCOMING_POLICIES.md) and
[offline authorship](../client-kotlin/OFFLINE_AUTHORING.md) define the existing rules.

## R2. Cloud-host integration support — PAUSED

Depends on [Server S2](../../AragoniteAlexandriaServer/docs/remaining-steps.md).

- [ ] Identify actual generic adapter/contract gaps encountered by the PostgreSQL
  and S3 host port before adding library APIs. Server-specific SQL/storage and
  reader projection remain outside Rhizome; reuse the proven wire contract.
- [ ] Qualify host transaction admission, bounded rows/assets, ACK/cursor/clock
  atomicity, cancellation/retry and offline provenance through the new adapters.
- [ ] Verify ordinary merge versus authoritative restore generation fencing with
  the host's identity/adoption layer, including stale offline writers and workers.
- [ ] Run Kotlin/Go parity and actual two-device acceptance with the owning hosts;
  record source pins and negotiated capability/schema versions. Local library
  tests alone do not establish cloud or tablet acceptance.

## R3. Dependency/release coordination

- [ ] Select the qualified integration revision and compatible host versions.
  Coordinate Alexandria's exact source pin and Server's Go dependency strategy;
  no hidden reliance on a developer's dirty sibling checkout.
- [ ] If published artifacts are required for the agreed release, choose a new
  version, publish matching Kotlin/Go artifacts and verify clean consumers against
  them. Never overwrite a published Maven version with unpublished integration code.
- [ ] Document API/contract compatibility, schema/capability negotiation and
  upgrade/recovery requirements. Preserve original authorship on backfill/replay.
- [ ] Review legacy ForestNote separately if maintenance there is requested.
  Its older pin is not permission to downgrade this checkout or silently upgrade it.

Even documentation commits require a coordinated client pin update while Android
enforces exact HEAD plus a clean worktree. That housekeeping is not a sync feature.

## Not Rhizome work

Billing, tenant directories, multi-user collaboration, reader layout/anchors/HWR,
cloud operator credential migration and UI recovery wizards belong elsewhere or
are out of scope. Do not add partial replication, patch operations, a second
durable queue or a second conflict engine merely to finish this checklist.

Start with R1 when the user chooses library work. Do not auto-start R2 from an old
“next” note, publish artifacts, enroll devices or deploy a relay during documentation.
