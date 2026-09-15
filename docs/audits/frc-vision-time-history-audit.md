# FRC vision timestamp and history audit

Pass 213 reviews estimator-time conversion/fallback and historical-pose scratch ownership
in FrcVisionTracker. It follows the separate recovery-consensus scope in pass 212. Changes
and validation artifacts remain local.

## Confirmed issues and corrections

- A nonfinite fallback estimator time reached history sampling and the fusion callback.
  The concrete CTRE bridge already rejects nonfinite timestamps, but the tracker performed
  avoidable calls and exception/logging work. The tracker now rejects the observation before
  either call and reports `REJECTED_TIMESTAMP` when no other observation succeeds.
- A throwing fallback clock aborted the whole tracker update, preventing a later observation
  with a usable native capture timestamp from being processed. Clock failure now makes only
  the fallback time unavailable. Fresh observations still reach Redux diagnostics without
  being fused a second time by the ARES estimator.
- Each fallback observation independently read the current estimator clock. A batch could
  receive inconsistent clock offsets and repeated SDK reads. One lazily sampled result,
  including an unavailable result, now serves the whole update. The next update can retry.
  A usable converted native capture does not read the fallback clock or subtract latency again.
- A history implementation returning true after writing only part of the reusable array
  could reuse prior coordinates and authorize the wrong residual decision. Each query now
  invalidates all three components and requires three finite outputs before using history.
  Failed, absent or incomplete history follows the existing fallback to the cached drive
  estimate. The concrete CTRE adapter already writes all three finite values; the partial-write
  regression exercises the tracker contract with an alternate IO, not a vendor defect.
- Calibration with fusion disabled still converted time and queried history despite publishing
  only observations. Those calls and the redundant tracker filter are now skipped. Redux still
  receives the fresh batch and performs its diagnostic filtering. A missing drivetrain also
  avoids vendor clock reads. Frame freshness remains enforced across calibration transitions.

The new state consists of a primitive time value and sampled flag. History continues using
the same three-element array. Tests show two fallback frames require one clock call, and
calibration requires zero clock/conversion/history calls. Existing zero-GC regressions pass;
this is not a full tracker, SDK or physical-loop allocation/latency measurement. Public API
descriptors remain unchanged; KDoc imports and timestamp/status behavior are documented.

## Validation

Eight regression methods failed against the original production code. The final test file
contains seventeen methods, including those failures plus native fractional seconds, conversion
failure, no double latency subtraction, failed/partial history, wrapped historical heading,
scratch reuse, duplicate suppression, empty/stale/future frames and calibration transitions.
Fourteen fallback-age cases compare against decimal arithmetic, including inclusive freshness
limits and values near Long.MAX_VALUE. Finite zero/negative estimator epochs remain supported.

Source commit: `60328197952cccaa7486341f16ad36eddce96d23`.
Library tree: `1fba72d3665993919cfeca77f9462cc497ad0887`.
Candidate: `17.0.20-rc.1fba72d36659`.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,475 | 0 |
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

The suites account for 5,284 passing results, zero failures/errors and six
existing Studio skips. The skips cover three opt-in starter integration scenarios, the native
file chooser, the dashboard performance baseline and physical dashboard validation. FTC and
its starter also passed generated-project verification and APK assembly; FRC and its starter
passed generated-project verification. All 410 candidate publication files were hashed and
reverified after consumer validation. Four rebuilt starter archives differ only in
release version properties. Monorepo policy passed, including source/version/archive identity,
shared guidance and links in 374 current documents; 38 historical records were explicitly skipped.

Evidence directory: `ARESLib-Kotlin/build/audit-pass213-verified-evidence/`, containing baseline
failures, focused/full XML, candidate identities/hashes, archive comparisons and the final
summary. Gradle cache/up-to-date results count as validated suite results; focused tests are
not counted twice in the aggregate.

## Remaining scope and coverage

Native NetworkTables/FPGA/RobotClock coherence, clock pairing across real polling delays and
the consistency of native versus normalized timestamps remain unclosed. This pass does not
prove their physical alignment. Simulation recovery and the complete disabled/moving policy,
pose changes within multi-camera recovery batches, and full camera/robot close ownership also
remain separate scopes. The tracker retains partial coverage.

The ledger accounts for 2,939 tracked files: 1,154 fully reviewed, 165 partially reviewed
and 1,620 pending, with zero stale or orphaned records. This pass adds the fully reviewed
regression file and this report. The concrete tracker retains partial coverage for its
remaining physical clock, mode/recovery and lifecycle boundaries.

No physical robot, target MicroPython runtime, rendered Studio window, remote workflow,
deployment or public release was exercised. These findings are in ARES code; this pass does
not identify a WPILib or CTRE defect.
