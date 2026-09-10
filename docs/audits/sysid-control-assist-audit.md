# SysId and control-assist audit

Pass 79: local source fixes validated with focused tests; frozen candidate validation pending.

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
polling is excluded from that thread-specific assertion. The FRC sibling-source rerun also passed all 10 tests. Candidate matrix results are pending. Focused evidence is copied under
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
Complete the frozen library/API publication and dependency-ordered consumer validation, then update
the file ledger with exact evidence and remaining scope. All changes remain local.

