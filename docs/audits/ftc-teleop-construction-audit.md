# FTC TeleOp construction rollback audit - pass 172

The season AresTeleOpBase constructed AresRobot before generated-runtime setup and
controls-source telemetry. If either later step threw, the shared lifecycle had not yet
received the robot and could not close it. Two fault-injection tests reproduced the
missing robot.close call before the fix.

The adapter now clears its generated-runtime reference and closes the already-created
robot on failure before rethrowing the original error. A distinct cleanup error is
suppressed onto the original; identical errors are not self-suppressed. Successful setup
still transfers ownership to the shared lifecycle and performs no premature close.
The extra error-handling work is construction-only, outside the periodic loop.

Three tests intercept constructors so no hardware is created: generated-runtime
construction failure, post-construction telemetry failure with a separate close failure,
and successful setup/ownership transfer. The runtime-construction mock infrastructure
wraps its injected constructor error; the test verifies cleanup rather than claiming the
wrapper is the exact real-world exception type. Telemetry failure identity and suppressed
cleanup diagnostics are asserted directly.

Combined with passes170-171, the season adapter is reviewed for its normal single-OpMode
ownership contract: options/delegation, frame reuse and sampling, drive opt-in, heading
forwarding, cancellation, reference cleanup and construction rollback. The shared SDK
lifecycle's handling of repeated or out-of-order callbacks and exceptions after ownership
transfer remains outside this adapter's evidence. No physical motor neutralization,
hardware initialization or timing measurement is claimed.

The ARESLib candidate remains `17.0.3-rc.100852e472fb`; library source is unchanged.
Local evidence: `ARESLib-Kotlin/build/audit-pass172-verified-evidence/`.
No release or push occurred.

Validation: three focused tests and all 130 TeamCode plus six simulator tests pass without
skips. Debug APK assembly, monorepo policy, documentation links and staged whitespace
checks pass. Unchanged library and other product suites were not rerun.
