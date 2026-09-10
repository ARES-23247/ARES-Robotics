# SysId and control-assist audit

Pass 79 complete. Changes and validation artifacts remain local; the broader audit is active.

## Confirmed corrections

- Missing, non-finite or negative current no longer masquerades as zero or clears the stall
  watchdog. FTC/FRC executors supply checked cached current; FTC drive limits apply per motor.
- Invalid limit configuration, timestamp overflow/rollback, negative-epoch stall timing and
  non-representable calculated statistics stop characterization before another nonzero command.
- Duplicate timestamps retain the preceding numerical sample. A delayed first velocity reading
  establishes the baseline without inventing displacement or acceleration before observation.
- FTC drivetrain characterization now uses measured motion, projects field velocity onto robot
  forward using the same odometry observation heading, and requires valid feedback at most 100 ms old.
- FTC samples retain signed integrated displacement and capture timestamp, voltage, velocity and
  acceleration together during output processing. Telemetry reuses that sample without invoking
  the custom velocity provider again or assigning a later timestamp.
- Invalid/nonpositive supply, requested voltage above supply, and global motor derating stop
  characterization. The global power guard covers the flywheel as well as the drivetrain.
- Unsupported mechanism commands cannot fall through to an attached flywheel. Every command
  transition neutralizes previously owned outputs; switching drive to flywheel previously left
  drive motors energized.
- Linear travel safety avoids a square root. FTC output processing and FRC pose access use
  scalar estimator fields instead of allocating convenience pose objects.

## Evidence

Twenty-eight distinct regression methods failed before their corresponding fixes: eight original
core boundary/current methods, two FRC current methods, thirteen FTC current/motion/supply/mechanism
methods, four additional FTC voltage/transition methods, and one core first-sample method.
Before-fix logs and XML are retained under `ARESLib-Kotlin/build/audit-pass79-*-before.*`.

The final core/FTC focused run passed 27 core and 21 FTC tests with zero failures, errors or skips.
It includes a 10,000-sample SysId allocation budget check and existing zero-allocation regressions.
FTC tests exercise the real NT4 command/token/lease handshake and verify that output processing
performs no additional motor current polling on the output thread. Legitimate background registry
polling is excluded from that thread-specific assertion. The FRC sibling-source rerun also passed all 10 tests. Final candidate results follow below. Focused evidence is copied under
`ARESLib-Kotlin/build/audit-pass79-final-focused-evidence/`.

## Numerical and physical limits

Position remains a signed right-endpoint velocity integral, and acceleration a backward difference,
starting at the first measured velocity. These are discrete approximations, not exact continuous
motion reconstruction. Absolute wrapped heading travel remains a separate safety bound. FTC's
100 ms feedback age checks observation freshness, not the accuracy of a physical sensor.

Voltage samples describe requested characterization voltage after software supply/derating guards;
there is no claim of independently measured terminal voltage. The flywheel current/velocity freshness
contracts depend on the concrete IO implementation. Tests use mocks/JVM execution, not physical
hardware motion, actuator timing, or oscilloscope measurements. The allocation result covers the
core sampling path, not NT4 serialization or the entire robot loop.

## Remaining coverage

The broader goal remains active. This batch closes SysId numerical/current/measurement/transition
work; adjacent ShotSetup/coefficient behavior is reserved for a subsequent pass. FTC empirical
calibration branches still need their own measurement-validity, scale and timing boundary review.
FRC output-sample rejection and power-range boundaries remain follow-up scope. Those executors
retain partial file status rather than gaining complete review credit from focused test success.
The file ledger records exact evidence and those remaining scopes. All changes remain local.

## Validation infrastructure correction

The initial full suite exposed a fixed 100 ms sleep in the existing throwing-device polling test.
It now waits up to two seconds for an observed healthy poll after a failing poll, with unconditional
registry teardown. All 14 HardwareRegistry tests passed after this test-only correction. Production
registry code was unchanged. This timing failure is recorded separately from the 28 SysId
regression methods. The library candidate identity is regenerated to include the corrected test.

## Frozen candidate validation

Source checkpoints: `96de760b` (SysId), `4bb63ea4` (polling test), and `381c11bd` (private JVM constant).
Library tree: `64ab06568983512c8249f6a849b79a9f27f05d04`.
Candidate: `17.0.3-rc.64ab06568983`, published only to the isolated local validation repository.

| Scope | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 1,860 | 0 |
| FTC and simulator | 111 | 0 |
| FRC | 136 | 0 |
| FTC starter and simulator | 14 | 0 |
| FRC starter | 34 | 0 |
| Studio shared/gateway/app | 1,790 | 6 |

All groups have zero failures/errors. Library API checks, Kover generation, local candidate
publication, consumer generated-project verification, FTC assembly, Studio Kover gate/version/file
size checks, and monorepo source policy passed. Gradle reused valid unchanged task outputs; this is
not a claim that every task was forced to execute again. No separate dashboard performance rerun
was needed for this batch, and no new whole-loop performance measurement is claimed.

The six Studio skips are conditional template, native chooser, hardware dashboard and performance
baseline checks. Source policy verified 240 current documents and 38 historical exemptions.
Copied test XML, manifests, logs, coverage reports, candidate BOM and identity checks are under
`ARESLib-Kotlin/build/audit-pass79-verified-evidence/summary.json` and its sibling files.
These build artifacts are local; tracked source tests and this report preserve the reproducible
scope. The 72 focused checks include the separate 14-test polling suite.

The first broad attempt exposed the polling test's timing assumption; the next API check caught a
new threshold field being public at the JVM boundary despite a private companion. The threshold
is now explicitly private and the API check passes without expanding the public contract. Future
batches should run focused API checks before freezing their candidate identity.
