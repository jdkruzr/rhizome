# Rhizome project instructions

A little less formality and a little more humor are welcome; sync guarantees are still serious.

- Read `docs/current-state.md` for the current integration branch and test pointers. For cross-project work consult `../AragoniteAlexandriaServer/docs/project-map.md`.
- Rhizome is generic single-author/multi-device sync. Keep reader/writer semantics, hosting subscriptions and collaborative multi-user policy out of this library.
- The user paused the Alexandria/Rhizome cloud port/deployment. Do not resume it from historical “next” instructions.
- Preserve full-row operations, HLC/LWW ordering, schema gates and original provenance. Received data must not be restamped as newly authored edits.
- Keep Kotlin/Go contracts aligned using `spec/` and executable `conformance/vectors/`. A pending acceptance catalog is not executable conformance.
- Follow `client-kotlin/INCOMING_POLICIES.md` for atomic response receipt and `client-kotlin/OFFLINE_AUTHORING.md` for dormant local authorship. Hosts own real transaction serialization and lifecycle.
- Alexandria requires this checkout to be clean and exactly pinned. Coordinate even documentation commits with its `gradle/rhizome-integration-revision.txt`; never reset work or disable the guard to fix a build.
- Do not overwrite a published Maven version with unpublished integration code. Use the existing verified source substitution.
- Run both language suites for protocol changes and the owning application's actual HTTP integration tests where relevant. A clean unit suite does not activate a production capability.
- Preserve dirty work and synthetic test isolation. Keep updated state in the handoff, not a transcript in this file. This file is intentionally tracked despite the inherited ignore rule.
