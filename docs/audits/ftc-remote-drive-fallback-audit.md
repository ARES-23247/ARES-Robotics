# FTC remote-drive fallback audit - pass 175

Continued the explicit gap from pass174: failure inside the catch path. Two additional
real-loop regressions failed before correction. If error telemetry itself threw, the
shared loop never reached robot.update to apply the dispatched zero intent. If zero
dispatch threw, the mode propagated failure without attempting robot cleanup.

Error reporting is now best-effort after successful zero dispatch, allowing the normal
robot update to proceed. If zero dispatch fails, the mode latches a terminal failure,
closes the robot and rethrows the original error. Distinct neutral-dispatch and close
errors are retained as suppressed diagnostics without self-suppression. Subsequent loop
calls on the failed instance rethrow before network access or dispatch; successful
reconstruction is required. No periodic allocation was introduced on the normal path.

The tests observe zero dispatch followed by robot.update despite a second telemetry
error; cleanup despite dispatch failure; preservation of the primary/cleanup errors;
and no later dispatch or second close after the terminal latch. The fixture snapshots
dispatch attempts before injecting failure, so an observed zero attempt is not falsely
reported as a successful Redux transition. Existing pass174 gate/loop tests remain.

Traced AresRobot.close: it is idempotent, attempts calibration disable, registered output
safety, subsystem close and shared robot close while retaining errors. These tests mock
that boundary; they prove its invocation, not physical motor neutralization. SDK
scheduling, actual device failures, transport operation and hardware stop timing remain
external. The complete remote-drive source is reviewed for its single-OpMode loop,
protocol/delegation and error-recovery contract. This does not close the shared SDK or
hardware implementation files by inference.

The unchanged library candidate is `17.0.3-rc.100852e472fb`.
Evidence: `ARESLib-Kotlin/build/audit-pass175-verified-evidence/`.
No hardware/socket startup, release or push occurred.

Validation: 15 focused tests and all 141 TeamCode plus six simulator tests pass without
skips. Debug APK assembly, monorepo policy, documentation links and staged whitespace
checks pass. Unchanged library and other product suites were not rerun.
