# Project document path ownership and shared locks

Pass 199, 2026-09-12. This pass closes stable-filesystem ownership gaps left open
by pass 198 in Studio document repositories and the project snapshot read boundary.
No ARESLib source, candidate, release pin or robot-loop implementation changed.

## Confirmed corrections

`File.canonicalFile` did not provide the required containment on the tested Windows
links. Several repositories also resolved only a parent directory before opening a
child file. Valid bytes from outside the selected project could therefore be loaded
as current documents or accepted as immutable history. Directory junctions could
redirect publication and recovery outside the selected project.

`ProjectPathOwnership` captures the selected root's real identity once per operation
and checks each target, including listed leaf files and not-yet-created descendants.
Both logical containment and existing-link resolution must remain inside that root.
The repository keeps its logical file paths for callers that derive project-relative
change lists; opening the whole project through a directory alias remains supported.
Versioned/singleton stores, superstructures, field current files and field checkpoints
now use the same ownership check. Invalid individual listing entries produce a
diagnostic while unrelated valid documents remain available.

The project snapshot loader had independent field, drivetrain and tuning reads,
which could bypass repository fixes. Those reads now check both directories and
leaf files before decoding. Outside content is excluded from the assembled project
and produces an ownership diagnostic. Existing codec and project-model validation
still run for owned inputs.

Write locks now use the resolved filesystem path, including missing suffixes. Two
aliases of the same missing file previously acquired separate locks simultaneously;
they now serialize. The lock map remains process-local and retains its entries;
this pass does not claim bounded lifetime memory usage or cross-process exclusion.

Superstructure history now shares the generic checkpoint validator. A corrupt
existing checkpoint blocks both unchanged and changed saves. A missing checkpoint
for the previous current document is reconstructed before a changed save replaces
it. Full normalized content hashes, rather than name existence, establish whether
an immutable checkpoint matches.

Root identity is reused across a repository operation instead of resolving the root
again for each subpath. Singleton diagnostics also decode the already-resolved file
without another load/path-resolution call. These remove redundant work, but ownership
checks necessarily add filesystem checks for each leaf. No timing benchmark or
robot control-loop improvement is claimed.

## Evidence

The first new suite contained 15 cases and produced 14 failures. The existing
project-alias compatibility case already passed. Failures included outside current
reads/saves, listing acceptance, history acceptance, a missing-descendant path
through a junction, redirected routine publication/removal/restoration, field paths,
superstructure history and simultaneous alias-lock acquisition. These are failing
cases, not a claim of 14 independent root causes. Baseline loops stop at their first
failing assertion; final passing loops exercise both generic stores and every league.

A separate snapshot regression then failed against the unmodified snapshot loader.
Its final version covers field/drivetrain/tuning-component/tuning-profile files and
directory links. The field fixture is a valid field document; the other three use
malformed payloads and require an ownership diagnostic before codec errors, proving
that rejection is performed at the path boundary. An additional superstructure
checkpoint-link regression completes the final 17-case new suite.

Final focused validation passed all 94 cases: the 17 new ownership cases, prior
history/recovery regressions, repository/superstructure tests, field document tests
and project-model architecture tests. The real Windows tests created directory
junctions and file symbolic links. Each link is removed before its owned temporary
tree is cleaned up. The junction subprocess and lock-test executor have bounded
waits and owned cleanup. The alias-lock test first observes a competing worker's
attempt, checks exclusion while the first lock is held, then verifies completion
after release.

Full Studio validation passed: 1,930 tests, 1,924 successful executions, zero
failures/errors and six opt-in/environment skips (three generated-project
integrations, native file chooser, performance baseline and physical dashboard).
Repository policy passed, including links in 360 current documents and 38 excluded
historical records.

Logs, baseline XML and copied final result XML are under
`ARESLib-Kotlin/build/audit-pass199-verified-evidence/`. All Gradle validation uses
the unchanged local `17.0.10-rc.271e2518a412` candidate. The prior pass was concrete
progress, committed locally as `7c7678f8`; this pass started from that clean tree.

## Coverage and limits

The shared project store, field store, superstructure repository, project snapshot
loader and new tests were read fully. The metadata repository was also read fully
while tracing the shared resolver; its own unreviewed-save and JSON-shape policy
still need dedicated coverage. Small repository facades, project-layout utilities
and the editor/session call sites were checked where they connect to these changes.
This does not close unrelated behavior in those callers.

Ownership is checked at particular points in time. It does not provide a directory-
handle transaction against a process continuously replacing links between a check
and an OS operation. Lock identity can likewise change if an external process moves
or replaces files or their ancestors. Backup durability, backup-content integrity,
multi-transaction recovery ordering and interruption during rollback remain open
from pass 198. File permissions, power loss and the Unix symlink test branch were
not independently exercised. No visible Studio window or physical robot was tested.

The file ledger retains partial statuses for these broader unverified behaviors;
a passing Studio suite is not universal file, branch or hardware coverage.

The refreshed ledger accounts for 2,888 tracked files: 1,061 reviewed, 152 partial
and 1,675 pending, with no stale or orphaned records.
