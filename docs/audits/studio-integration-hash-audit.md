# Studio integration hash audit - pass 145

Reviewed all declarations, event routing branches, notebook hash material and digest
formatting in `IntegrationModels.kt`, plus its complete existing test file.

The digest formatter previously called `String.format` once for each of the 32 SHA-256
bytes. Replaced these formatter calls and intermediate formatted strings with a fixed
hex alphabet and one character array. This removes redundant formatting work; no
robot loop timing or measured speedup is claimed. The old formatter was correct.

Preserved UTF-8 encoding, SHA-256, serialization configuration and exact hash material.
Event hashes include the complete serialized event. Notebook hashes include content,
revision, workspace, visibility, author and AI provenance, while excluding review
state, reviewer, timestamps and the stored hash itself. Repository consumers compare
these hashes when enforcing event ID consistency and validating notebook content.

Added coverage for all eight event variants through the real serializer, event type
and aggregate ID routing. Session, issue, entry and test IDs are deliberately distinct.
Digest output is compared with independently rendered unsigned hexadecimal, including
Unicode payloads. Existing tests retain the pinned website-ingest hash fixture and
verify that notebook review metadata changes preserve the content hash.

This closes the shared model and its test file review. It does not certify all producer
validation, repository transactions, live external ingest or publication behavior.
No external service was contacted and no robot was operated.

Validation evidence is retained in
`ARESLib-Kotlin/build/audit-pass145-verified-evidence/`. The library candidate remains
`17.0.3-rc.100852e472fb`; all changes are local.

All 28 shared and 18 gateway tests pass. The app suite reports 1,762 tests: 1,756
passed and six opt-in skips. Total: 1,802 passed, six skipped, no failures or errors.
Monorepo policy, documentation links and whitespace checks pass.
