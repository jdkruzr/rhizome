# Pending capability acceptance definitions

`assets-v1.json` specifies the future [assets-v1 contract](../../spec/assets-v1.md).
**No behavioral case here has been implemented/executed by the current conformance runners.**
The filename deliberately does not match `vectors/*.vector.json`; don't depend on an unknown
category being silently skipped and counted as passing coverage.

The cross-repo Stage 1 validator and execution vocabulary are in
[ForestNote's acceptance directory](../../../ForestNote/docs/test-plans/forestread-stage-1/README.md).
From the standard sibling checkout layout:

```sh
node ../ForestNote/docs/test-plans/forestread-stage-1/validate.mjs --self-test
```

This checks structure, required coverage, links and deterministic payload hashes ONLY. Stage 2
adds Kotlin/Go interface and UB real-host adapters that fail on unknown actions/assertions and
report real per-case results. Keep pending cases visible until those results exist. Do not edit
an implementation-status label to manufacture conformance evidence. Existing vectors and their
runtime conformance semantics are unchanged by this document.
