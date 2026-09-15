# Project history and recovery integrity

Pass 198, 2026-09-12. This pass follows the immutable-publication boundary into
Studio project histories and multi-document recovery. ARESLib source, release pins,
and the existing isolated candidate remain unchanged.

## Confirmed fixes

The project writer's `replaceExisting = false` path previously used `ATOMIC_MOVE`,
which replaced an existing target on this Windows filesystem. Project history,
recovery moves and hardware evidence now share an exclusive publisher. It creates
a hard link to prepared bytes where supported, otherwise writes through
`CREATE_NEW`; neither path opens an existing destination for replacement. Mutable
current-file replacement retains its existing contract. Prepared file bytes are
force-flushed, and directory flushing remains best effort. The
[Java 17 Files API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/Files.html)
documents why `ATOMIC_MOVE` alone cannot guarantee no replacement.

Versioned and singleton stores now validate an existing checkpoint against the
full decoded-content hash before accepting a no-op or advancing current content.
A missing checkpoint for the current revision is reconstructed before replacement.
A corrupt checkpoint stops the save and remains available for diagnosis.

History scans validate revision and hash filenames. Versioned history must also
declare the requested document ID: previously, a document B checkpoint placed in
A's history could make restoring A overwrite current B. Filename validation accepts
either the canonical decoded hash or the original serialized hash, including CRLF
normalization, to preserve older codec-normalized checkpoints. Logically identical
revision/hash entries are deduplicated without rewriting their original bytes.

Restoration retains the decoded document selected by its full hash. It no longer
builds and sorts summary objects just to reread one file, removing redundant work
and the opportunity for a changed second read to substitute different contents.
Saves reuse the already-computed current hash. These are structural reductions in
work; this pass makes no measured latency or robot-loop timing claim.

Recovery moves publish the destination before deleting the source name. Existing
destinations preserve both files. Interruption between publication and deletion can
retain both names, so this operation is explicitly not described as one atomic move.

The shared publisher's no-hard-link fallback can expose an incomplete new file.
That exposed a transaction integration gap: the old manifest reader accepted any
available scope/file lines as a complete rollback plan. New manifests start with a
version header and end with a checksum over the complete body. Recovery validates
the version, checksum, record types and baseline scope membership before changing
files. Scope/filename delimiters are rejected before mutation. Legacy unversioned
manifests remain readable; incomplete new manifests cannot fall back to that reader.

Transaction scope checks resolve existing links even when the final file or directory
is absent. The same helper now serves hardware evidence storage. An explicitly
declared directory alias inside the project remains valid; a linked descendant
outside all declared real scopes is rejected before backup or mutation. This also
checks every declared scope against the real project root. The checks run again
before recovery.

## Validation evidence

Eight initial history regressions produced six failures: immutable-target
replacement, accepted corrupt checkpoints, missing parent history, cross-document
restoration, substituted contents after a second read, and mismatched history
filenames. The selected-content test was refined from requiring rejection to
requiring restoration of the captured original document; it still prevents the
substitution observed in the baseline. All 12 final history cases pass, including
recovery moves, mutable binary writes, legacy normalization and ordinary/no-op saves.

The initial transaction suite retained its three original tests and added seven cases.
Before the reader change, six cases failed: incomplete plans, checksum mismatch,
unsupported records, out-of-scope baselines, delimiter acceptance and the absence
of a complete versioned manifest before mutation. Successful rollback and legacy
recovery were retained. Final tests preserve current files and backups on rejection,
exercise multiple truncation points, and check valid new-format recovery and commit.
Final review added three cases: declared directory aliases, linked descendants and
recovery of a deleted current directory. On the first two, the declared alias already
passed on Windows; the linked descendant test failed because a mutation was allowed
outside its real scope. The final transaction suite contains 13 tests.

The initial focused history run passed 42 tests. An intermediate focused recovery run
passed 35 tests (12 history, 10 transaction, five evidence-store and eight evidence
integration cases). The 17 repository integration tests were included in the first
focused run and the full suite. The intermediate selector named those repository
tests under the wrong package, so they are not counted among the 35. The final
corrected selector includes them with the linked-scope regressions.

All 55 final focused tests passed: 12 history, 13 transaction, 17 repository, five
evidence-store and eight evidence integration cases. The first full run also passed
(1,910 tests, six skips); final full validation was repeated after the linked-scope
correction, rather than treating that earlier run as evidence for the final source.

Final full Studio validation passed: 1,913 tests, 1,907 successful executions,
zero failures/errors and six opt-in/environment skips (three generated-project
integrations, native file chooser, performance baseline and physical dashboard
validation). Repository policy passed, including links in 359 current
documents and 38 excluded historical records.

Logs, baseline XML and copied result XML are in
`ARESLib-Kotlin/build/audit-pass198-verified-evidence/`. Validation uses the unchanged
`17.0.10-rc.271e2518a412` library candidate from the isolated local repository.

## Scope and limitations

The shared project store, extracted publisher and path resolver, hardware evidence
store, transaction implementation and both modified/new test files were read
completely. Other project writer callers were inspected at their persistence
boundaries; their unrelated
behavior remains open in the ledger.

Hard-link fallback tests run through the existing hardware-store suite. They inject
unsupported publication against local files; they are not physical filesystem or
power-loss tests. Directory-link cases used Windows junctions; the Unix symlink
branch was not executed. Fallback publication may retain incomplete output. New
transaction manifests reject it, and document/evidence decoders reject malformed
records. Legacy
manifests have no completeness checksum and cannot offer the same guarantee.

These checks do not authenticate a hostile writer capable of replacing both content
and checksum. Parent-path replacement races, external concurrent project writers,
backup-content integrity, backup durability across power loss, recovery ordering
between multiple interrupted transactions, and failure during multi-file rollback
remain open. Locks are process-local. Restoring a captured historical snapshot does
not itself make the entire read-current/save operation a cross-process transaction.

Current-file replacement falls back to an ordinary replacement move if atomic moves
are unsupported. No visible Studio window, physical hardware, release or robot
runtime was exercised. The repository-wide audit remains incomplete.

The refreshed ledger accounts for 2,886 tracked files: 1,059 reviewed, 149 partial
and 1,678 pending, with no stale or orphaned records. Passing Studio tests does not
close the unreviewed files or the remaining partial scopes.
