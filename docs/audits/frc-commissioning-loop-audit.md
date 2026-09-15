# FRC commissioning loop audit

Pass 102 reviews commissioning health checks and their periodic/lifecycle call sites. It removes
confirmed redundant work and adds controller-level safety coverage. No new mathematical or behavioral
defect is claimed in this pass.

## Confirmed allocation sites

`requirePeriodicHealth()` passed its retained configuration-device array through a Kotlin vararg
spread. Compiled bytecode contained `java.util.Arrays.copyOf` on each successful periodic check.
The same pattern appeared at four homing/configuration call sites during recovery and initialization.
The two internal health helpers now accept arrays directly; all five call sites pass retained arrays.
Their loops and early-failure semantics are unchanged. Existing helper tests were adapted to the
internal signature, without changing their assertions.

Before/after `javap -c -p` evidence confirms five array-copy sites became zero in the commissioning
controller. One eliminated site belonged to the 20 ms periodic path. This is compiler-level evidence;
it does not measure JIT escape analysis, physical loop duration or an allocation rate on the roboRIO.

## Safety review and coverage

Three new tests construct the real `FrcSwerveRobot`, commissioning controller and Marvin reducer
under host HAL simulation, then close each robot. They exercise:

- One cached configuration/homing read per healthy periodic check and lost-reference rejection.
- Persistent fault latching across healthy transitions, rejection outside Disabled, a required fresh
  button edge for recovery, successful safe-zero recovery and disable inhibition.
- Configuration reset and failed live tuning revoking hardware permission.

The lifecycle trace covers the scheduled robot update, health check, calibration cache record and
SysId gate; mode-transition safety calls; and cleanup order. These reads do not certify all device IO
or exception behavior. The commissioning controller remains partial pending injected homing/health
getter exceptions, unsuccessful individual home operations and cleanup-failure integration coverage.
The remainder of `ARESRobot.kt` initialization, simulation and lifecycle integration is also partial.
The allocation fix does not change fault/recovery policy or hardware contracts.

## Validation scope

This is FRC season code only. The shared library source and candidate `17.0.3-rc.de2cb9c407a0`
remain unchanged. Full FRC tests, generated-project verification and repository policy are sufficient
for this change; unaffected consumers are not rerun. Host HAL tests do not establish physical motor
neutralization, real hardware timing or an interactive simulator result.

## Final evidence

Full FRC validation passed 156 tests, including three new controller-level tests and the 13 existing methods in `ARESRobotSafetyBoundaryTest`, with zero failures, errors or skips. Generated-project and namespace verification and monorepo policy passed. Gradle reused valid unchanged outputs.

Copied FRC JUnit XML and successful logs, with verified SHA-256 hashes, are recorded in `ARESLib-Kotlin/build/audit-pass102-verified-evidence/summary.json`. No failure-before behavioral defect is claimed; the before/after bytecode demonstrates the redundant array copies.
