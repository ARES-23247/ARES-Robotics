# Vision candidate selection and decision attribution audit - pass 211

Scope: FTC camera candidate ranking, the selected observation's actual Store/EKF outcome,
immutable dispatch result ownership, replay compatibility and vision-buffer ambiguity rules.
This follows the [recovery quality audit](vision-recovery-quality-audit.md). Recovery consensus
arithmetic, complete estimator history/time behavior and physical lifecycle remain open.
All changes and validation remain local in the isolated audit branch.

## Confirmed issues and fixes

FTC selected the lowest-ambiguity camera observation, then judged it using the final EKF
outcome for the whole batch. A later accepted camera could hide the selected observation's
Mahalanobis rejection; a later rejected camera could trigger recovery for an accepted selected
observation. Both directions were reproduced with an initialized estimator history and two
fresh independent camera sources. The tests assert the resulting recovery behavior as well
as the status, observation counts and unchanged raw batch order.

`VisionMeasurementsReceived.diagnosticMeasurementIndex` requests a dedicated result for one
original input index. Store processing carries that index through prefiltering and captures
its actual estimator acceptance/rejection. Three immutable primitive/reference fields on
VisionState publish the index, acceptance and reason. The existing aggregate diagnostics and
last-NIS association retain their behavior. Empty batches, invalid/unrequested indices and
pose resets clear the selected result. External-estimator routing reports the selected
prefilter result without adding a local EKF correction.

The whole camera batch retains its original order and still reaches the Store once. No camera
is discarded merely to make the final batch diagnostic match the selected observation. Tests
compare estimator snapshots, covariance and aggregate diagnostics with and without selection;
duplicate packet identities cannot confuse positional association.

`Store.dispatchAndGetState` returns the immutable snapshot committed by that dispatch. The
existing Unit-returning dispatch delegates to the same implementation. Subscriber callbacks
retain their ordering and failure semantics, but a callback's later dispatch cannot replace
the snapshot returned to the tracker. The existing Store failure suite was rerun, and a
dedicated regression verifies a subscriber that overwrites the live vision result.

Malformed or unavailable ambiguity and nonfinite poses could also hide a usable camera
candidate. Ranking now first prefers physically valid normal solves, then valid independent
recovery-only solves. It compares each solve's own ambiguity when available, prefers valid
known ambiguity over unavailable ambiguity, and uses distance to resolve ties. Unknown
ambiguity sentinels do not participate in ordering. Exact ties retain input order. When every
candidate is invalid, the first supplies its rejection diagnostic and all observations still
reach the Store. Physically invalid normal poses are no longer published as the last valid
camera pose used by telemetry and calibration.

Squared distance overflow and underflow made widely different candidates compare equal.
Ranking now uses hypot. If both distances exceed Double.MAX_VALUE, it compares distances
scaled by one quarter before subtraction, which keeps even opposite-sign finite endpoints
representable. Tiny ordinary distances keep the unscaled path. Tests cover squared overflow,
squared underflow and distances beyond the finite double range.

The vision reducer separately applied a strict hard-coded ambiguity limit of 0.2. It could
omit observations accepted by the actual EKF, including unavailable ambiguity, equality at
the configured limit and valid observations under a higher configured limit. The reducer
now uses the configured inclusive limit and availability flag. Direct pure-reducer calls
still reject malformed available ambiguity and invalid filter configuration before snapshot
publication. The rolling buffer and ownership behavior are preserved.

## Efficiency and compatibility

Selected-result tracking uses existing diagnostic objects with scalar fields; it creates no
per-observation outcome objects or identity maps. Selection uses one indexed pass and retains
the selected physical-validity results for later tracker checks. Fusion is not repeated to
obtain a second decision. No additional hardware reads were introduced. Existing zero-GC
regressions passed, but they do not measure the complete tracker/Store pipeline, SDK calls,
physical loop deadlines or an end-to-end speedup. Existing immutable action/snapshot
allocations remain outside any zero-allocation claim for the complete tracker.

The API snapshot adds dispatchAndGetState and the optional selector/result properties.
Appending action/state fields changes JVM constructor and copy descriptors, so binary
consumers must be rebuilt against the new version. Existing dispatch signatures remain.
Old schema-1 action logs lacking the selector decode it as -1; explicit null, fractional,
overflowing or string selector values remain invalid. Current logs round-trip the selected
index and reproduce the same estimator and diagnostic state.

## Validation

Nine selection regressions failed against the original production files, and two additional
buffer regressions failed before the reducer correction. The first association fixture lacked
estimator history; that fixture was corrected and the original production code rerun to
confirm the specific wrong-camera decisions. Both original and corrected evidence are retained.
The final pass adds 30 tests: sixteen core and fourteen FTC tests.

Source commit: `06c60d28dbe92cc65e61ee8fcb2056b3794c0071`.
Library tree: `8800d379ac34dd1027880104c102ab62882a10be`.
Candidate: `17.0.18-rc.8800d379ac34`.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,432 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,020 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |

The suites account for 5,241 passing results, zero failures/errors and six
existing Studio skips. The skips cover three opt-in starter integration scenarios, the native
file chooser, the dashboard performance baseline and physical dashboard validation. FTC and
its starter also passed generated-project verification and APK assembly; FRC and its starter
passed generated-project verification. All 410 candidate publication files were hashed before
consumer validation and reverified afterward. Four rebuilt starter archives differ only in
release version properties. Monorepo policy passed, including source/version/archive identity,
shared guidance and links in 372 current documents; 38 historical records were explicitly skipped.

Evidence directory: `ARESLib-Kotlin/build/audit-pass211-verified-evidence/`, including baseline
failures, focused/full XML, source/staging whitelists, candidate identities/hashes, archive
comparisons and the final summary. Gradle cache/up-to-date outputs are included as validated
suite results; focused runs are not counted twice.

## Remaining scope and coverage

FTC/FRC recovery sums, counters and threshold conversion remain unclosed, along with FTC
initialization state after a recovery snap. FRC estimator-time fallback/history failures,
simulation recovery and the full disabled/moving policy require further work. Broader Store
configuration collection ownership, camera close ownership and native NT clock coherence are
also separate scopes. This pass does not claim complete tracker or monorepo coverage.

The ledger accounts for 2,932 tracked files: 1,147 fully reviewed, 164 partially reviewed
and 1,621 pending, with zero stale or orphaned records. This pass adds two fully reviewed
regression test files and this report. The concrete tracker, broad action/state definitions
and vision processor retain partial records with their unclosed boundaries stated explicitly.

No physical robot, target MicroPython runtime, rendered Studio window, remote workflow,
deployment or public release was exercised. These findings are in ARES code; this pass
does not identify a WPILib defect.
