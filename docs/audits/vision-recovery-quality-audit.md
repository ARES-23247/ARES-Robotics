# Vision recovery quality and measured feedback audit - pass 210

Scope: physical observation validity during FTC initialization and FTC/FRC recovery,
cached drive feedback, stationarity thresholds, camera orientation hints and shared
range/geometry work. This follows the [freshness audit](vision-frame-freshness-audit.md).
The tracker files remain partially reviewed: selection, consensus arithmetic, estimator
time/history and complete lifecycle behavior are separate boundaries. All work is local.

## Confirmed issues and fixes

FTC treated finite zero fallback motion values as valid stationary feedback even when
`measuredMotionValid` or `imuMeasurementsValid` was false. That could authorize an initial
pose snap after a failed observation. Both trackers now validate the producer-owned flags
and finite cached estimator position/heading, measured velocities, pitch/roll and acceleration
before sending orientation hints, fusing observations or accumulating recovery. A camera is
still polled to establish connection state. No extra hardware reads or universal feedback age
cutoff were introduced; freshness remains the observation producer's responsibility.

Both trackers could send a nonfinite component to camera orientation hints despite true
validity flags. They now also validate the derived degree rate and planar speed, since finite
inputs can overflow those calculations. Heading is wrapped before conversion to degrees.
Invalid feedback clears recovery state; FTC also clears the displayed last camera pose.

FTC tested each velocity component separately against its stationary limit. At 0.08 m/s
on both axes, the actual speed is about 0.113 m/s and exceeds a 0.1 m/s limit. Stationarity
now uses the planar speed norm. Both trackers require finite positive stationarity thresholds
for the policy that uses them. FRC's separate disabled-recovery policy remains unchanged.

FTC initialization and independent-pose recovery omitted physical checks used by the core
filter, including tag count, negative ambiguity, tag allowlists and geometry/latency metadata.
Independent-yaw divergence could bypass shared tag or collision gates. FRC's separate recovery
checks also accepted negative independent ambiguity and malformed shared metadata before the
normal filter rejected the observation. These are ARES tracker defects; this pass makes no
claim of a WPILib defect.

`VisionOutlierFilter.isValidForRecovery` now shares the physical predicate with normal
filtering: valid configuration, finite unit pose, available nonnegative ambiguity, shared
metadata, allowlist, camera-reported range, rotated footprint/3D bounds, angular rate and shock.
Recovery omits estimate-distance and yaw-innovation gates so a displaced robot can realign.
It still respects the configured camera-reported maximum range. Normal and independent solves
use their own pose and ambiguity: a bad normal solve does not invalidate a good independent
solve when shared metadata and motion are valid. Tests exercise that positive recovery case.

## Efficiency and compatibility

FRC computes its observation range once for recovery and normal fusion, returning immediately
when reported average tag distance is available. The fallback uses a stable full 3D norm;
the existing 6 m platform boundary and minimum fallback range are preserved. Both trackers
reuse the measured planar speed for hints and stationarity. FTC reuses the selected reference
pose and the normal 2D pose when no independent solve exists. It skips repeated detailed
geometry classification for physically valid observations. Core normal filtering shares
physical validation and computes the selected yaw once.

The public API adds `isValidForRecovery` and `isDriveObservationValid`, with the former's Kotlin
default bridge. Existing public signatures remain. Unavailable ambiguity and finite negative
unknown-geometry sentinels retain their established meanings. Invalid scalar configuration,
negative latency, zero tags and malformed available metadata cannot authorize recovery.

The retained core test XML records 200 bytes over 10,000 warmed
checks, at 159.36 ns/check on the desktop JVM. The test allows at most 4,096 bytes over that
batch and uses preallocated observations/feedback. This is not a zero-allocation measurement
or a full-loop timing result. It excludes Store actions, tracker snapshots, SDK/network calls,
Android/roboRIO execution and physical loop deadlines. No end-to-end speedup is claimed.

## Validation

All 15 initial regression methods failed against the unchanged production code: eleven FTC
and four FRC methods. The completed pass adds 33 methods: nine core, fifteen FTC and nine FRC.
They cover physical gates and independent-solve ownership, malformed metadata and feedback,
finite-input overflow in hints, diagonal speed, nonfinite stationarity thresholds, stable
shock norms, range fallback and positive recovery compatibility. Existing FTC healthy-state
fixtures now explicitly set both feedback-valid flags. Separate tests confirm that actual
reducer fallback values retain false flags and cannot authorize initialization.

Source commit: `bc50f06d59cb03d34ef098ed139678a381cff0f0`.
Library tree: `fb069fce9d5b2e13e2c47a218d1a34c5961c6a41`.
Candidate: `17.0.17-rc.fb069fce9d5b`.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,402 | 0 |
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

The suites account for 5,211 passing results, zero failures/errors and six
existing Studio skips. The skips cover three opt-in starter integration scenarios, the native
file chooser, the dashboard performance baseline and physical dashboard validation. FTC and
its starter also passed generated-project verification and APK assembly; FRC and its starter
passed generated-project verification. All 410 candidate publication files were hashed before
consumer validation and reverified afterward. Four rebuilt starter archives differ only in
release version properties. Monorepo policy passed, including source/version/archive identity,
shared guidance and links in 371 current documents; 38 historical records were explicitly skipped.

Evidence is retained in `ARESLib-Kotlin/build/audit-pass210-verified-evidence/`: baseline
failures, focused/full XML and logs, allocation output, frozen candidate identities/hashes,
archive content comparisons and the final summary. Gradle cache/up-to-date results are
included as validated suite results; focused runs are not counted a second time. The first
tooling run was blocked by Windows sandbox access to temporary Git fixtures; the rerun with
the required access passed. No product change was made to accommodate that environment issue.

## Remaining scope and coverage

FTC candidate selection is still separate from the Store's final batch acceptance diagnostic.
Unknown or malformed candidate ordering, recovery means/counters and threshold conversion,
and initialization state after recovery remain open. FRC estimator-time fallback/history
failure behavior, simulation recovery and the full disabled/moving policy also remain open.
Camera/robot close ownership and native NT clock/frame coherence require their own review.
The broader Store configuration ownership, spline approximation and lighting metadata scopes
remain partial. This pass does not claim complete tracker or monorepo coverage.

The ledger accounts for 2,929 tracked files: 1,144 fully reviewed, 164 partially reviewed
and 1,621 pending, with zero stale or orphaned records. This pass adds three fully reviewed
regression test files and this report. The core filter retains its complete review; both
production trackers and the existing FTC tracker test file retain their partial status.

No physical robot, target MicroPython runtime, rendered Studio window, remote workflow,
deployment or public release was exercised.
