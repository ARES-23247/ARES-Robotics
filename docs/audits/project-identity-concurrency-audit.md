# Project identity concurrency audit — pass 202

Scope: identity-editor load/save ordering, immutable session revision binding,
draft preservation, cancellation and creation-time identifier locking. This closes
specific coherence and lifecycle gaps recorded in pass 201; it does not repeat
the metadata shape or retired-format audit. Changes remain local.

## Confirmed defects and fixes

The editor first inspected a valid metadata document and then independently loaded
a ProjectSession revision. If the file changed during session assembly, the editor
could show an older document while retaining the newer revision. Session saving
then derived its expected metadata hash from the newer session snapshot, allowing
an old reviewed draft to overwrite changes the editor had never shown. Inspection
now uses both the valid document and revision from the returned session snapshot.
A snapshot whose metadata became invalid is rejected; the earlier valid document
cannot be reused with its revision.

After saving, the editor read the shared session's current revision when its
coroutine resumed. Another editor could advance that session before the resume,
again pairing old displayed metadata with a newer revision. Save results now carry
the exact returned revision to the UI. The post-save snapshot must still describe
the saved metadata. If another writer changes that identity during refresh, the
editor reports that the write happened and requires reload instead of presenting
the stale saved document with the new revision. Creation also refreshes and binds
the selected project, rather than inheriting another open project's session token.

Both save success and save failure previously restored a state captured before the
I/O operation. Editable fields remained enabled, so changes made while saving
could disappear. Completion now preserves the latest draft. Success updates the
canonical document/hash/revision, validates the retained draft and marks newer
edits as unsaved. Failure preserves the latest draft and validation errors. A
second apply while saving is rejected before dispatch; duplicate work can no
longer turn a successful save into a stale-write error and restore old UI state.

Stable project/team/season/robot IDs lock when creation starts, in both the
viewmodel and screen. This prevents a later unsaved draft from changing identifiers
that become permanent at the first commit. Display name, geometry and runtime
options remain editable during the write and are preserved for another review.

Queued cancellation previously left load state busy or presented cancellation as
a write failure. Load/save coroutines now establish cancellation handling before
dispatch, release busy state, and propagate cancellation. An interrupted save
requires reload to inspect actual disk state: cancellation after the write is not
described as rollback. Existing generation guards continue to keep old operations
from replacing a newly selected editor. Operations do not restart on an inactive
owner scope.

The save path also consolidates repeated session-result handling and builds the
replacement draft once. Rejecting duplicate applies eliminates the redundant
second write attempt. No robot loop, timing benchmark or measured latency claim
is part of this desktop workflow audit.

## Validation evidence

Nine baseline tests ran against unchanged production source: eight failed and the
existing successful-save/project-switch behavior passed. The failures reproduced
both document/revision mismatches, invalid snapshot reuse, successful and failed
save draft loss, duplicate-apply state corruption, cancelled-save error reporting
and cancelled-load busy state.

The final test file has 14 cases. It uses actual temporary project files and the
real repositories/session, with a delegated ProjectDocumentGateway that changes
files at a chosen assembly boundary. A queued I/O dispatcher separates file work
from owner-coroutine completion without sleeps, latches or timing races. Each
fixture cancels its owned job, drains the bounded queues and asserts completion
before TemporaryFolder cleanup.

Additional cases cover creation with a previously different session selection,
stable-ID locking while retaining a new display-name draft, another file edit
during post-save refresh, cancellation before initial dispatch and after commit,
and a superseded queued load. Assertions check disk bytes/documents, drafts,
validation state, exact revisions, selection, busy/error state and reload needs.

All 63 focused tests passed with no skips, failures or errors. They include the
14 concurrency cases plus existing identity, presentation, metadata and session
regressions.
The full Studio app suite passed: 1,976 tests total, 1,970 successful executions,
zero failures/errors and six opt-in/environment skips (three generated-project
integrations, native file chooser, performance baseline and physical dashboard).
Shared agent guidance and monorepo policy checks passed. Local links were checked
in 363 current documents; 38 explicitly historical records were skipped.

Evidence is under `ARESLib-Kotlin/build/audit-pass202-verified-evidence/`, including
the baseline and copied final XML. Gradle used the unchanged local ARES candidate
`17.0.10-rc.271e2518a412`. No library source/pins changed, and no visible-window,
physical robot, release, deployment or remote publication was exercised.

## Coverage boundaries

The full identity viewmodel and new test file were read; only the stable-ID enable
conditions of the screen changed. Existing ProjectSession tests were read further
to cover their full source, but their unbounded concurrent fixture waits and
process-service ownership still warrant separate review before closing that record.
The session's wider transaction, fingerprint and execution behavior is still partial.

The editor is used from one serialized UI owner; arbitrary concurrent calls from
multiple threads are not tested or newly supported. The metadata inspection and
session assembly still perform separate reads, although only a coherent returned
session document/revision can authorize an editor save. Cross-process replacement,
transaction durability, remaining raw-text ambiguities, broader screen interaction
and exhaustive platform-specific draft validation remain outside this pass. The
repository-wide audit goal remains incomplete.

The ledger records 2,897 tracked files: 1,074 reviewed, 157 partial and 1,666
pending, with no stale fingerprints or orphaned records. These are file-review
counts, not line or branch coverage.
