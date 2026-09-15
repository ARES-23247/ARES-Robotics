# FTC fallback odometry boundary audit

Pass 215 reviews the complete software behavior of MecanumFallbackOdometry and
FtcOdometrySourceArbiter, plus their existing and new tests. Changes and evidence remain local.

## Confirmed issues and corrections

- Converting cumulative encoder positions before differencing mixed old and new scales.
  Changing ticks per meter from 100 to 200 with stationary 100-tick encoders reported a
  spurious -0.5 meter translation. Baselines now retain raw ticks and apply the selected current
  scale only to their difference. Differencing first also preserves small changes on large
  cumulative counts; the regression includes one tick beyond a 32-bit-sized baseline.
- Invalid encoders or scale values could poison the stored baselines and pose. Nonfinite raw
  headings were silently converted to zero by the legacy angle wrapper. Raw measurements are
  now validated before wrapping or mutation. A positive finite primary scale is used when
  available; otherwise a positive finite fallback is required. Invalid reset coordinates,
  Rotation2d raw radians and raw IMU heading are rejected without changing prior state.
- Duplicate timestamps consumed wheel/heading changes even though the Redux estimator rejects
  duplicate observations. They now return the last pose with unavailable motion and leave all
  baselines intact. Rewinding or overflowing elapsed time is rejected before mutation. Explicit
  reset permits a new epoch, including zero or negative host/replay timestamps.
- Wheel sums, displacement conversion, pose accumulation or velocity division could produce
  infinities and commit corrupt state. Scaling wheel terms before summing avoids a needless
  intermediate overflow. A rare raw tick subtraction overflow uses differences converted with
  the same current scale. Unrepresentable results fail before committing any sample.
- Adding a small alignment offset directly to an enormous raw heading lost the offset.
  Normalizing the raw heading first preserves alignment; reset uses the same ordering.
- Invalid optional angular velocity left the action's motion-valid flag true. The helper now
  reports unavailable motion with a neutral angular rate while retaining finite pose geometry.
  The existing reducer already independently rejected nonfinite motion measurements; this
  corrects the producer's validity contract.
- The KDoc claimed zero allocation despite constructing a PoseUpdate action on each call.
  That action retains independent ownership, as required by Redux consumers that retain or
  enrich it. The documentation now distinguishes primitive integration from action allocation.

The source arbiter required no implementation change. Its immediate failover, consecutive
recovery, absence handling, reset and forced fallback behavior agrees with an independent
countdown model for 46,656 sequences of five events across six recovery thresholds. Thresholds
include negative/zero values, one, two, five and Int.MAX_VALUE. The recovery counter cannot
overflow: it resets when reaching the positive clamped threshold, which is at most Int.MAX_VALUE.

## Validation

Twenty-three new test methods cover 21 odometry cases and two arbiter checks. Sixteen failed
against unchanged production; seven passed as compatibility controls. All 49 focused results
pass after correction, including existing odometry/source-switch tests, FTC frame lifecycle
tests and core zero-GC checks.

An independent SE(2) exponential checks 240 combinations of translation, lateral motion,
heading, wrapped turns and tiny angular increments. Additional cases cover scale changes,
single-tick precision, finite-overflow boundaries, duplicate/rewound timestamps, failed-reset
atomicity and retained-action ownership. The normal arc integration formula itself already
agreed with the independent reference.

The focused desktop JVM measurement reports 1,360,000 bytes for 10,000 odometry updates,
exactly matching construction of 10,000 independently retained PoseUpdate actions. The source
arbiter measured 192 bytes total across 10,000 transitions, within the bounded host-runtime
allocation allowance. These measurements do not establish physical robot loop deadlines.

Source commit: `f60bd3a131d0bcf777a84cc4537962eca6ab3404`.
Library tree: `f02981fd655c4e5c14bae236d768b1446f3ec0dd`.
Candidate: `17.0.22-rc.f02981fd655c`.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,510 | 0 |
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

The suites account for 5,319 passing results, zero failures/errors and six existing Studio
skips. These cover three opt-in starter integration scenarios, native file chooser, dashboard
performance baseline and physical dashboard validation. FTC/starter generated-project checks
and APK assembly passed; FRC/starter generated-project checks passed. All 410 candidate files
were hashed and reverified after consumer validation. Monorepo policy passed, including
source/version/archive identity, shared guidance and links in 376 current documents, with
38 explicitly historical records skipped. Four rebuilt archives differ only in version properties.

Evidence directory: `ARESLib-Kotlin/build/audit-pass215-verified-evidence/`, including original
failures, focused/full XML, candidate identities/hashes, archive comparisons and final summary.
Gradle cache/up-to-date results count as validated suite results; focused tests are not counted
twice in the aggregate.

## Scope and remaining coverage

The software contract assumes continuous encoder counts and less than half a turn between
heading samples. Encoder resets/rollover and epoch changes require explicit reseeding.
Wheel slip, calibration accuracy, sensor freshness and real device source-switch continuity
still require adapter/system or physical validation. The helper's validation errors propagate
through the existing FTC fatal-loop handler, which neutralizes outputs and latches the fault.
A separate caller may catch an invalid sample without corrupting this helper's stored state.

The ledger accounts for 2,945 tracked files: 1,164 fully reviewed, 166 partially reviewed
and 1,615 pending, with zero stale or orphaned records. This pass adds two reviewed test files
and this report, and closes four previously pending files: both software classes and their
existing tests. Hardware and system limits remain explicit rather than implied by unit coverage.

No physical robot, target MicroPython runtime, rendered Studio window, remote workflow,
deployment or public release was exercised. This pass found ARES implementation issues,
not a WPILib or device-vendor defect.
