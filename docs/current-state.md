# Rhizome handoff

Context split: 2026-09-27 UTC. Root `/home/jtd/rhizome`, branch
`integration/forestread`. Implementation checkpoint before this documentation-only
commit: `df45a06dace1cedbeffa32b82a7339a8ba61b36d`; worktree was clean.

Forward checklist: [remaining steps](remaining-steps.md), distinguishing generic
library qualification/publication from the paused host integration.

## Scope and checkpoint

Rhizome owns the generic Kotlin/Go sync machinery: full-row operations, HLC,
deterministic LWW, schema registry/hash gates, bounded exchange, atomic receipt,
offline authorship and immutable asset-transfer interfaces. Domain interpretation
belongs to the host, not a second sync engine inside each app.

Recent integration commits added shared-library assets/recovery, scoped native
connection routing and host-transaction admission for asset mutations. These
changes exist in this integration branch; they are not evidence that every
downstream release or the cloud-native Server has adopted them.

The original README retains early extraction/adoption status and links to
ForestNote. For current Alexandria integration evidence, follow this handoff and
the newer client plans rather than treating those historical statements as current.

## Instructions and contracts

- [Protocol](../spec/protocol.md), [merge](../spec/merge.md), [HLC](../spec/hlc.md),
  [schema registry](../spec/schema-registry.md), [schema evolution](../spec/schema-evolution.md).
- [Atomic incoming policies](../client-kotlin/INCOMING_POLICIES.md): preparation
  outside the transaction; rows, clocks, ACK and cursor committed together on
  the host's serialized writer. Deferred rows retain their original provenance.
- [Offline authorship](../client-kotlin/OFFLINE_AUTHORING.md): capture before sync
  opt-in using one author/clock; no restamping/backfill of already-versioned rows.
- [Conformance](../conformance/README.md) and [pending policy](../conformance/pending/README.md).
  Do not count pending catalogs as executed behavioral acceptance.
- [Shared project map](../../AragoniteAlexandriaServer/docs/project-map.md) and
  [client current state](../../AragoniteAlexandria/docs/current-state.md).

## Dependency pin and clean-tree requirement

Alexandria's `settings.gradle.kts` checks both this checkout's HEAD and a completely
clean `git status`; its exact pin is in
`../AragoniteAlexandria/gradle/rhizome-integration-revision.txt`. This handoff is
committed with project instructions, and that pin is advanced to the documentation
commit without changing sync implementation. Future docs commits need the same
coordination; leaving untracked handoffs here would otherwise break Android builds.

Legacy `../ForestNote` also checks a pin, but still names `07c7368...` at this
split and already differs from the active checkout. Do not silently update that
legacy application or downgrade this checkout. Use an appropriate separate
checkout/worktree and explicit configuration when doing legacy maintenance.

## Test entry points

```sh
# From server-go:
GOWORK=off go test -race -count=1 ./...

# From client-kotlin:
./gradlew test --rerun-tasks
```

These are runnable entry points, not new passes from the documentation split.
The [Alexandria Stage 2 harness](../../AragoniteAlexandria/docs/test-plans/forestread-stage-2/README.md)
combines these with domain parity, actual disposable UltraBridge HTTP, assets and
process-restart checks. Review its options and fixture paths before executing.
Kotlin and Go must agree; changing one implementation alone is not a finished
protocol change. A schema/contract modification needs coordinated host adapters
and tests, not merely a matching hash string.

## Next action and pause

**The Alexandria/Rhizome cloud port/deployment is paused at the user's request.**
Generic library work can be selected separately, but do not publish artifacts,
deploy a relay or enroll devices just to migrate session context. When porting
eventually resumes, distinguish the existing SQLite UltraBridge implementation
from the cloud Server's PostgreSQL runtime and preserve shared admission/restore
boundaries without duplicating the protocol.

Suggested opening prompt: “Read AGENTS.md and docs/current-state.md. Check the
current branch and Alexandria dependency pin; summarize the generic sync boundary.
Keep cloud porting/deployment paused and wait for my chosen Rhizome task.”
