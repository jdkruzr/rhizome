# Immutable assets and bounded row transport — Stage 1 contract

Status: **specified, not shipped** (2026-09-07). The [Stage 2A/B/C slices](../../ForestNote/docs/test-plans/forestread-stage-2/README.md)
implement headless storage/transfer, bounded rows, negotiation and durable client scheduling as of
2026-09-08. The 2026-09-09 consumer Stage 2D8 slice adds candidate Kotlin/Go reader contract parity
in ForestNote/UltraBridge, not domain rules in Rhizome. Reader HTTP/storage activation, host
lifecycle/UI wiring and production capability activation remain pending.
This additive proposal does not change
the currently shipped `POST /sync/v1`, release version, registry or conformance results.
Generic contract owner: Rhizome. Consumer behavior is in the
[FN contract](../../ForestNote/docs/design-plans/2026-09-07-forestread-stage-1.md).
[Pending cases](../conformance/pending/assets-v1.json) are not loaded by existing vector runners.

## 1. Capability boundary

Row LWW, HLC, provenance, contiguous acknowledgements and schema reconciliation remain unchanged.
Assets are immutable content addressed by lowercase whole-byte SHA-256. They are not row ops,
base64 fields, a replacement for the op log, or a new conflict adjudication service. The host
supplies the set of asset references; Rhizome knows nothing about books, annotations or backlinks.

All endpoints use the existing sync authorization/account scope, HTTPS deployment policy and
origin. Asset knowledge is not authorization: a guessed hash never bypasses auth. Do not fetch
arbitrary URLs from a descriptor or accept user-provided disk paths. Transfer progress is local
state, not a new site or a source of HLC row updates.

`GET /sync/capabilities` returns authenticated JSON, for example:

```json
{
  "capabilities_version": 1,
  "features": ["assets-v1", "bounded-rows-v1"],
  "accepted_schema_hashes": ["<legacy-hash>", "<reader-hash>"],
  "assets": {"chunk_bytes": 262144, "manifest_page_entries": 256},
  "rows": {"max_ops": 500, "target_page_bytes": 4194304,
           "max_row_bytes": 8388608, "max_body_bytes": 16777216}
}
```

Numbers above are initial defaults. Chunk size is fixed for assets-v1; page/row limits may be
lowered by deployment and clients use the minimum of client and server limits. A single valid
row larger than the soft target is sent alone, within the hard row/body limits. A manifest page
contains at most 256 entries. Bound error bodies to 64 KiB. Lengths for entire assets use decimal
strings (`byte_length`, 1..9223372036854775807); chunk indices fit JSON's exact-integer range.
An empty generic asset is also valid (`byte_length:"0"`, zero chunks, SHA-256 of empty bytes).

404/405 on capability discovery means legacy server; 401/403 means authentication failure,
not legacy. Unknown versions/features do not imply support. Cache capabilities only for the
current server/account; revalidate on auth/server changes and unsupported-operation responses.
A consumer requiring assets must verify both features and its registry hash before posting new
schema ops. Never filter an already-sequenced outbox into a fake legacy schema.

## 2. Endpoints and representations

An asset descriptor is `{asset_id, byte_length, chunk_bytes:262144}`; `asset_id` is 64 lowercase
hex characters. Chunk count is ceil(length/chunk_bytes). The final chunk has the exact remainder
(or 262144 for an exact multiple). The descriptor cannot change under an existing hash.
Original media type/name are application metadata, not part of generic asset identity.

| Request | Success | Durable meaning |
|---|---|---|
| `PUT /sync/assets/v1/{hash}` with descriptor JSON | 201 new / 200 existing | Descriptor staged; response `{state:"staging"|"verifying"|"ready"|"invalid"}`. Conflicting descriptor: 409. |
| `GET /sync/assets/v1/{hash}` | 200 descriptor plus `state` | 404 if not known. `ready` is durable whole-object verification, not merely presence of chunk rows. |
| `GET /sync/assets/v1/{hash}/chunks?start=0&limit=256` | 200 `{entries,next_start}` | Entries cover consecutive requested indices: `{index,sha256,byte_length}` for a present verified chunk; `sha256:null` for missing. `next_start:null` at end. Descriptor defines total count. |
| `PUT /sync/assets/v1/{hash}/chunks/{index}` | 204 | Raw `application/octet-stream`, exact Content-Length, `X-Rhizome-Chunk-SHA256` lowercase digest. Verify hash/length/index before committing bytes. Duplicate identical PUT is idempotent. |
| `GET /sync/assets/v1/{hash}/chunks/{index}` | 200 raw bytes plus length/digest header | Only ready objects may be downloaded; incomplete object: 409 `asset_not_ready`; unknown chunk/object: 404. |
| `POST /sync/assets/v1/{hash}/complete` | 200 `{state:"ready"}` | Stream every staged chunk in order, verify total bytes and whole-object hash, then durably mark ready. Incomplete: 409 `missing_chunks`; mismatch: 422 `asset_hash_mismatch`, mark invalid. |
| `POST /sync/assets/v1/{hash}/reset-invalid` | 204 | Explicitly discard chunks ONLY for an invalid, never-ready staging object. Keep immutable descriptor; return to staging. Ready object: 409. |

Completion may return 202 `{state:"verifying"}` when verification is scheduled off the HTTP
request. Poll descriptor state with host backoff. While verifying, reject conflicting chunk writes;
same-content retries may return 204. Crash during verification resumes/restarts streaming hash,
never publishes ready by assumption. A ready descriptor/complete request is idempotent and never
downgrades. A different digest for an occupied staged chunk returns 409 `chunk_conflict`; do not
replace verified bytes arbitrarily. Repair an invalid object only through reset-invalid.

Manifest GET may report staging chunk presence to authorized uploaders for resume. Downloaders
must wait for ready, then independently verify every chunk and the final asset ID. Neither a
ready descriptor alone nor the chunk hash header is trusted as proof of local content integrity.

Metadata JSON errors use `{error:{code,message}}`. Malformed hash/index/length: 400;
bad chunk digest/content: 422; size exceeds declared or negotiated limit: 413; conflicting state:
409; auth: 401/403; storage quota/full: 507; transient storage/service: 503. No error advances
row cursors/acks or marks a chunk verified. Retry transport/5xx failures with host scheduling;
auth pauses until corrected; 400/413/422 and conflicts surface actionable status, not tight loops.
Manifest start must be in `0..chunk_count`, inclusive; start at count returns an empty final page.
Limit must be `1..256` and no greater than the advertised limit; invalid query values return 400.

For one chunk PUT, crash atomicity is bytes + verified-chunk marker in the same local transaction
(or equivalent durable host-store commit). ACK is emitted only after that boundary. Final ready
is persisted only after the streamed root digest succeeds. A lost response is resolved by querying
the descriptor/page and retrying idempotently. Quotas are host-enforced before durable acceptance.
No public delete/GC endpoint in assets-v1.

## 3. Storage, scheduling and API additions

Language-neutral interface sketches (all I/O suspend/asynchronous; original Stage 1 proposal):

```text
AssetId = canonical SHA-256 string
AssetDescriptor = (id, byteLength:int64, chunkBytes:int32)
AssetState = Missing | Staging | Verifying | Ready | Invalid
ChunkPage = (bounded entries, nextStart?)
AssetStore:
  describe(id); stage(descriptor); listChunks(id,start,limit)
  readChunk(id,index); writeVerifiedChunk(id,index,bytes,digest)
  verifyComplete(id); resetInvalid(id)
AssetTransport:
  capabilities(); describe/stage/list/read/write/complete/reset methods above
AssetReferenceProvider:
  pageRequiredAssets(after,limit) -> (descriptors, next)
AssetTransferState:
  id, direction, verifiedBytes, totalBytes, state, error?
SyncLocalStore addition:
  pendingPage(maxOps,targetBytes,maxRowBytes) -> ordered prefix or OversizedOp(identity,size)
```

The FN adapter stores original bytes as bounded SQLite chunks in its library. Other consumers may
use another durable store. Host app owns executors, scheduling/backoff and network policy. Chunk
reads/writes take short DB work units; no network operation or full-object verification holds the
DB writer. Pages contain bounded rows, not a lazily wrapped list that already loaded all payloads.

At most one asset chunk request is in flight per client initially. Round-robin pending assets and
upload/download directions; after each chunk, service a pending ordinary metadata page before the
next chunk. A large asset cannot hold the row-sync session until completion. Host current-book
priority may reorder assets without starving background work. Retry state survives process death.
Memory is proportional to bounded row pages/manifests/chunks, not total outbox or asset length.

Stage 2C implementation: Kotlin `SharedLibrarySync` is explicitly host-driven one work unit at a
time, with `SqliteTransferQueue` local-only durable state and `SqliteAssetReferences` over a host
relation. Scope binds a stable server/account identity, never credentials; changing that identity
requires a new scope and transports. The host owns one coordinator per scope, dispatcher binding,
lifecycle/network policy and honoring returned wake/retry deadlines. Metadata-change signals and
pulled rows wake discovery. Ready observations are revalidated after restart and periodically;
checkpoints never bypass destination/root verification. Current-book priority and actual application
reference projections remain host integration work. No server-side scheduler or new Go wire contract
is needed for this client-side slice.

### Bounded rows capability

Keep `/sync/v1`'s existing operation/response shapes. A new client opts into negotiated limits with
`X-Rhizome-Bounded-Rows: 1`. Both sides enforce capability-advertised hard limits and paginate to
the soft byte target and count cap. Count UTF-8 encoded JSON bytes including envelope overhead;
never estimate from decoded object count alone. Preserve contiguous op order and existing
accepted-through/cursor rules. A page with no outbound ops must still pull normally.
Clients with smaller receive budgets also send `X-Rhizome-Max-Response-Bytes` and
`X-Rhizome-Max-Row-Bytes` as positive decimal integers; the server uses the minimum of these and
its advertised limits. Invalid/impossible budgets return 400 before applying the request. If the
next pulled row exceeds the effective hard row/body limit, return 413 without applying outbound
ops or advancing acknowledgements/cursors; do not return a truncated successful page.

Never `pendingOps().take(n)` after materializing the whole outbox. Stage 2 adds bounded queries and
bounded HTTP response/error reads. A single oversized op returns an actionable error identifying
the op; leave it queued and preserve the cursor. Do not silently skip it, acknowledge it, or retry
forever. The host can export/reduce the offending content through an explicit later repair flow.
Legacy requests without the header retain their existing semantics; these new resource guarantees
apply to opted-in clients, not retroactively to old binaries.

## 4. Independent completion and retention

Rows can reference an asset before upload completion. Consumers retain those rows and expose
pending availability. Define three independent facts: metadata replicated; server asset ready;
local asset ready. A transfer's verified-byte counter is not a sync cursor or proof that metadata
has been captured/acknowledged. A "backed up" projection requires server-ready content AND all
required metadata ops acknowledged. An older client's cursor says nothing about asset receipt.

All synced devices eventually fetch all required assets. "Remove local download" is not part of
this initial full-replication contract. No automatic irreversible asset GC. Application-retained
trash counts as an asset reference. The host must include asset storage in backup/restore; the row
log alone no longer reconstructs original files. Restoring only metadata exposes pending/missing
content honestly and requests re-upload from a verified replica; never reports a complete backup.

Application semantic lifecycle/session records use `TableDef.tombstone = null` where retaining a
cancel/delete decision is necessary to interpret live dependents. Keep those rows in compaction.
Do not infer dependency-aware asset reclamation from generic tombstone watermarks or old clients'
schema acknowledgements. A future GC protocol needs its own explicit proof/retirement contract.

## 5. Conformance and release gates

Pending fixtures are in `conformance/pending/`, OUTSIDE the active `vectors/*.vector.json` glob.
Fixture validation checks shape/digests, not this unimplemented transport. Add real Kotlin/Go
adapters in Stage 2; only then report behavior as passed. Required cases cover chunk retries,
corruption, root verification, restart, auth, quota, compatibility, independent acknowledgements,
fair scheduling, bounded memory and no asset reclamation from metadata progress.

Implement both Kotlin/Go capability and row-limit behavior before a consuming client advertises
support. Run existing conformance unchanged. Do not replace the schema hash with a capability
version; negotiated transport support and accepted application registry are separate gates.
