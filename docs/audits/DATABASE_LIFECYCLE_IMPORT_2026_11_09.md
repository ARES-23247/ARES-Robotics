# Startup/shutdown database and import responsiveness checkpoint — 2026-11-09

## Source and scope

This follows the two open items in the [previous checkpoint](FRAMEWORK_LIFECYCLE_SKILLS_2026_11_09.md)
and [published acceptance report](PUBLISHED_7_0_65_ACCEPTANCE.md). Base `7dd12c892` on
`codex/framework-lifecycle-audit-20261109`; all work remains in that isolated worktree. The original
checkout's extensive pending changes were preserved and are outside this validation.

Studio remains `7.0.65`, with the existing binary candidate `19.1.5-rc.acceptance.20260925.1` supplied
through the original checkout's isolated `ARESLib-Kotlin/build/release-repository` using an absolute
file URI. The canonical library pin is `19.1.5`. No library source, versions, starter archive bytes,
hardware enable/lease policy, public telemetry schema, or published artifact changed.

## Reproduction and corrections

### Database closure raced the window lifetime

The retained installed-app crash names `SessionMetadataRepository.getSessionsForWorkspace` and
`SQLException: Connection was closed` on `AWT-EventQueue-0`. The original coordinator disposed
services before calling `exitApplication`, leaving window effects/scopes able to use closed storage.

A regression using the actual Compose application loop and real database reproduced that error
during window-owned cancellation cleanup on unchanged production. The other three baseline
regressions reproduced metadata reads, live reads, and live ingestion timing out behind historical
connection locks. All four failed at their intended assertions or two-second lock-wait deadlines.

The desktop host now owns the registry outside `application(exitProcessOnExit = false)`. A close
request disarms inputs and starts stopping owned simulator/build/deployment processes independently
of UI cancellation, then closes Compose. Only after the application loop has joined window effects,
workspace scopes, and bootstrap IO does the host dispose services and the database. The watchdog
bounds both phases under the existing 15-second graceful deadline plus three-second hard-exit grace.

Bootstrap propagates cancellation and rejects initialization after shutdown begins. A controlled
blocking-bootstrap test proves that its IO can finish using the database before disposal closes it.
Cleanup failures now return failure rather than being counted as successful disposal. Existing
persistence checks, process ownership, instance locks, and offline operation remain enforced.

This fixes the reproduced database lifetime violation. The origin of the old installed app's initial
window-close event was not established; no claim of eliminating every possible close trigger is made.

### Historical work blocked metadata and live telemetry

Historical queries and live reads previously shared one read mutex; native imports and live
ingestion shared one write mutex. DuckDB's duplicated connections could not provide independent
progress under those locks.

The service now owns five connection/lock pairs: persistent writer, historical reader, metadata
reader, ephemeral writer, and ephemeral reader. Sessions, summaries, annotations, topology and
import evidence use the metadata reader. Telemetry, analysis, replay and parameterized live queries
select the correct session's reader. Live append/prune/delete operations use the ephemeral writer.
Mixed recording-boundary batches flush live rows before waiting for the persistent writer.

Shutdown drains all five connections in a consistent writer-before-reader order. Cancellation while
acquiring locks releases those already acquired; connection-close failures do not skip later
connections. Regressions cover mixed-batch persistence, cancellation of a partial drain, closing
all five connections, late bootstrap rejection, and watchdog coverage of stalled UI cancellation.

### Full-suite validation exposed an existing starter-catalog mismatch

The first full run found one failure: each immutable archive pins ARES `19.1.4`, while the official
catalog incorrectly used Studio's newly advanced `19.1.5` pin. An independent probe against the
preserved baseline runtime reproduced the mismatch for all five archives.

`generateBuildConfig` now reads each archive's own canonical release manifest at build time,
tracks those archives as task inputs, and emits per-template dependency constants. The catalog uses
those constants. Runtime manifest-version validation and archive hashes remain strict and unchanged.
An independent fixed-runtime probe validates all five manifests with zero mismatches. The two
metadata tests that assumed all archive dependencies equal Studio's dependency now compare against
the actual archive manifest; the existing full project-creation regression remains intact.

The build/release skill reference records this independent-version boundary. Archives and release
manifests were not rewritten or republished.

## Measured responsiveness

Both runs used fresh copies of the retained 298,594,304-byte profile, the same 1,000,000-row CSV
(29,587,518 bytes), fresh JDK 17 JVMs with `-Xmx2g`, DuckDB `512MB` and four worker threads, one warmup
historical query, six timed historical LAG/aggregation queries, and 25 probes per foreground path.
The import, historical queries, metadata probes and live probes ran concurrently. The baseline
runtime was snapshotted before production edits. Each run verified all imported rows, the unchanged
workspace session set, and every appended live value.

| Measurement | Baseline | Corrected |
|---|---:|---:|
| Workspace metadata p95 | 866.929 ms | 7.047 ms |
| Workspace metadata maximum | 1,183.896 ms | 7.683 ms |
| Live append + read p95 | 869.036 ms | 12.529 ms |
| Live append + read maximum | 1,183.490 ms | 33.317 ms |
| Complete native CSV import | 841.014 ms | 1,107.224 ms |
| Six historical queries | 5,542.610 ms | 5,983.374 ms |

The selected foreground target was p95 below 250 ms and maximum below one second during this
workload; both corrected paths meet it. Background throughput is slightly lower while foreground
operations proceed. These are bounded backend measurements, not rendered frame rate, arbitrary
database-size guarantees, or physical controller latency. No timing thresholds were relaxed.

## Validation and evidence

**Final full Studio app suite: 2,784 tests, zero failures/errors, 6 opt-in skips (2,778 passed).**

Repository source/release policy, guidance/link validation, all six agent-guidance tests, and the
build/release skill validator pass. The source-size ratchet remains at 1,114 production Kotlin files
with zero violations and unchanged limits. `git diff --check` passes.

The normal command is `:app:test --offline --no-parallel`, with the candidate version and absolute
repository above. The initial integrated attempt rejected two new test methods because Kotlin had
inferred non-void return types; explicit `Unit` signatures corrected that fixture error. Their
five-test bootstrap/registry rerun passes. The first complete run executed 2,784 tests with the one
baseline catalog failure and six skips. The catalog-focused run then exposed two stale global-version
assertions; those now enforce the independent archive-manifest contract instead.

The six opt-in skips concern generic/official/representative consumer builds, the native chooser,
dashboard performance-baseline opt-in, and a physical target. No new generator, robot source or
archive content was introduced; existing immutable-archive consumer evidence remains applicable.
No new physical, Android/roboRIO deployment, or release certification is claimed.

Two native cycles used an isolated copy of the large BioBuzz profile and project, including its
77 local log entries. Exact dashboard captures were inspected; Run History was opened during close
testing and rendered fully on reopen. Owned PIDs `46440` and `7452` exited normally, both runtime
snapshots were removed, and ports `49340`/`49341` rejected new connections. There were no new crash
files, `Connection was closed` events, critical faults, or watchdog halts. Expected offline NT4
connection refusals were retained. These cycles validate the lifecycle/database correction; the
later catalog correction is covered separately by project-creation and full-suite checks.

Detailed baseline/fixed XML, workload harness and snapshots, native captures/process checks, and
logs remain under `build/database-followup/` and `build-database-*.log` in this worktree. The
[evidence summary](DATABASE_LIFECYCLE_IMPORT_2026_11_09_EVIDENCE.json) binds source fingerprints,
results, skips, and workload measurements. Review depth and passing checks remain separate in the
shared file-review ledger. The selected database lifetime and foreground lock-contention defects
are corrected; no all-files audit, external publication, or hardware claim is implied.
