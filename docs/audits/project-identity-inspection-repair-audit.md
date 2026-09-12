# Project identity inspection and repair audit — pass 201

Scope: Studio metadata inspection, schema classification and reviewed repair across
the repository, project-session entry point, identity editor and warning card.
This extends pass 200's creation/history audit without repeating its completed
catalog and routine review. Changes remain local to the isolated audit branch.

## Confirmed defects and changes

The identity editor inferred a retired format from any decode error mentioning
`authoringModel`. A schema-5 file with that field missing, null or damaged could
therefore be denied explicit repair. Conversely, an unsupported schema with a
different missing field could be offered repair because field checks ran before
version checks. The repository itself accepted a reviewed repair for any decode
failure, allowing callers, including ProjectSession, to replace a retired project
with schema 5 despite the editor's preservation policy.

Unsupported schemas now produce a typed exception based on the explicit version
before current-schema field checks. Repository repair rejects that exception
before creating recovery files or replacing metadata. Invalid current-format or
unidentifiable content still requires a reviewed raw-byte hash and exact recovery
preservation. A missing or invalid version is not evidence that a project is old;
this retains the existing session contract for repairing such corruption.

The editor carries an explicit unsupported schema version into the screen. Both
the editor and warning card have stopped interpreting `authoringModel` in error
text as a format classifier. Older formats are described as retired; future
formats are described as newer. Repairable current-format errors no longer render
a contradictory retired-format warning.

Inspection previously loaded/decode-checked the file and then read it again to
calculate the repair hash. An intervening replacement could bind the original
diagnostic to different bytes. The new inspection captures one byte array and
derives both its decode result and raw hash from that capture. I/O failures yield
no repair token. The raw hash remains distinct from a normalized document hash.
This removes a redundant disk read on the corrupt-file path; no latency or
throughput improvement is inferred from test duration.

Schema numeric checks now use exact decimal-to-Int conversion, consistent with the
library codec: integral JSON representations such as `5.0` and `5e0` mean version
5, and `3.0` cannot evade unsupported-format protection. Fractional values,
out-of-range integers and quoted versions remain invalid. No library code or
release version changed.

## Evidence

Five new repair regressions were run against unchanged production source; all five
failed. They reproduced current-authoring corruption denied repair, unknown-format
corruption mislabeled as retired, an unsupported damaged schema offered editor
repair, direct repository migration through repair, and the same migration through
ProjectSession. Matrix cases stop at their first failure in the baseline; the
report does not claim every matrix row was independently red before the fix.

The final repair tests exercise the editor with and without ProjectSession, actual
temporary files, a controlled coroutine test dispatcher, read-only preview,
canonical replacement and exact recovery bytes. Unsupported versions 1, 3, 4 and
6 are preserved, including incomplete documents. Existing session diagnostics now
assert the actual unsupported schema rather than a missing current-schema field.

Inspection tests check retained snapshots after file replacement, rejection of a
changed repair target, exact hashes of noncanonical bytes, missing/empty/directory
outcomes, equivalent integral schema notation, and rejection of fractional or
overflowing versions. Replacement is deterministic after capture; no probabilistic
filesystem race or cross-process atomic read is claimed.

All 69 focused tests passed with zero skips, failures or errors. This includes
11 cases in the two new test files and two additional presentation cases, plus
existing editor, session, repository and footprint regressions.
The full Studio app suite passed: 1,962 tests total, 1,956 successful executions,
zero failures/errors and six opt-in/environment skips (three generated-project
integrations, native file chooser, performance baseline and physical dashboard).
Shared agent guidance and monorepo policy checks passed. Local links were checked
in 362 current documents; 38 explicitly historical records were skipped.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass201-verified-evidence/`,
including the baseline and final XML. Builds use the unchanged local
`17.0.10-rc.271e2518a412` ARES candidate. No visible-window or physical hardware
validation, publication, release or deployment was performed.

## Coverage and remaining boundaries

The metadata repository and identity viewmodel were read fully. The new test files,
existing identity editor tests and presentation tests were reviewed and executed.
Only the warning-card/inspection boundary of the larger screen and the identity
repair/diagnostic portions of ProjectSession and its tests are accounted for here.
Their unrelated behavior retains pending or partial coverage.

Raw-text ambiguity (including duplicate keys and malformed UTF-8), external path
replacement, interrupted publication/recovery and cross-process writes remain
open. Capturing the inspection bytes does not provide a transaction spanning the
separate project-session snapshot. Editor reload/apply concurrency and lifecycle
behavior also remain open. In particular, a valid editor document can be read
before its separately loaded session revision; that relationship needs a distinct
coherence audit rather than being inferred from this pass's successful repair
tests. The repository-wide audit goal remains incomplete.

The ledger accounts for 2,895 tracked files: 1,072 reviewed, 157 partial and 1,666
pending, with no stale fingerprints or orphaned records. These are file-review
counts, not line or branch coverage.
