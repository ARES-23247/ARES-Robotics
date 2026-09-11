# FRC safe-zero failure audit

Pass 103 examines failure paths in operator-confirmed safe-zero recovery. Three cases failed
against the previous commissioning controller, using the real robot store and Marvin reducer:

1. A throwing `homeAtKnownZero()` left the controller's previous healthy permission and fault
   state unchanged.
2. That exception prevented the button-edge state from updating, allowing another home attempt
   without releasing the buttons.
3. A false home result set temporary homing validity false, but the subsequent safety-policy refresh
   replaced it with an old true device flag and could permit hardware again.

## Change

The controller consumes the button edge before any hardware work, revokes its cached homing
permission, dispatches inhibition and neutralizes hardware before calling the devices. A failed
return or unhealthy final validation latches a fault. Recovery exceptions also revoke permission,
latch a fault and report the error. Only a fully successful validation can clear the persistent fault.
A new attempt requires release and another press while Disabled. An ordinary false result still
allows the remaining device home requests to run, but cannot clear the overall failure.

The attempt remains a rare operator action; no arrays, retries, polling or additional work were
added to the periodic health loop. A failure in neutralization or in publishing the safety action
itself may still propagate; comprehensive infrastructure-failure handling is outside this pass.
No physical zero was changed during host validation.

## Regression coverage

The initial six-method test run had three failures, preserved as XML. The corrected focused run
passed all six. Two further methods cover throwing configuration/homing getters, inhibition observed
at the home-write boundary, successful recovery after a new press, and mixed device success.
The boundary assertion is made outside the controller's catch block so it cannot be swallowed.
All eight controller tests use host HAL and real Redux state; each fixture closes its robot.

## Remaining scope

Commissioning remains partial for failures of safety-action dispatch, neutralization and lifecycle
cleanup, plus periodic getter exceptions at the enclosing robot callback. `ARESRobot.kt` shutdown
sequencing and broader lifecycle integration remain open. No HIL, actual motor neutralization,
calibration accuracy or measured roboRIO loop-time result is claimed.

The source change is confined to FRC season code. The unchanged shared library candidate
`17.0.3-rc.de2cb9c407a0` is reused; no unrelated consumer matrix or library publication is required.

## Final evidence

Full FRC validation passed 161 tests, including all eight methods in `FrcMechanismCommissioningControllerTest`, with zero failures, errors or skips. Generated-project and namespace verification and monorepo policy passed. Gradle reused valid unchanged outputs.

Copied FRC JUnit XML and successful logs, with verified SHA-256 hashes, are recorded in `ARESLib-Kotlin/build/audit-pass103-verified-evidence/summary.json`. Initial failure XML is preserved in `ARESLib-Kotlin/build/audit-pass103-before-evidence/`.
