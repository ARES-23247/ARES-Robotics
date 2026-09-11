# FTC OpMode startup audit - pass 170

Read the complete competition and tuning OpModes, then traced their shared DSL INIT and
START lifecycle. The existing AutoToTeleOpTransitionTest copied restoration conditionals
inside tests and only asserted PoseStorage assignments. It could pass while production
restoration was broken. Replaced those three tests with actual FtcTeleOpBase init/start
calls using a mocked robot, and added two real season OpMode startup checks.

The replacement tests verify that a valid pose/alliance is restored at Start, not INIT;
invalid storage discards a stale blue alliance and pose; red alliance is dispatched
before the fallback reset; and Start reads current storage rather than an INIT snapshot.
The two season tests execute real definitions and init/init_loop/start, stubbing only
robot construction. Tuning does not enable calibration during INIT and enables it once
at Start. Competition startup never enables it. Both apply the configured slew limit.

Tests restore pose storage, active estimator tags, status and RobotClock. Initial harness
runs omitted SDK-provided gamepads and then compared timestamped actions against a moving
wall clock. Those fixture failures were corrected with mock SDK fields and fixed clock;
they are not production findings. No production source changed in this pass.

Competition/tuning files remain partially reviewed: active-loop generated/manual command
arbitration, button callbacks and stop/fault behavior need dedicated integration tests.
The startup fixture intentionally replaces robot construction, so it does not certify
device discovery, generated-runtime construction, transport activation or physical
calibration safety. Shared lifecycle tests do not close the whole shared lifecycle file.

Evidence: `ARESLib-Kotlin/build/audit-pass170-verified-evidence/`. The unchanged candidate
is `17.0.3-rc.100852e472fb`. No hardware operation, release or push occurred.

Validation: five focused tests and 122 TeamCode plus six simulator tests pass with no
skips. Monorepo policy, documentation links and staged whitespace checks pass. No APK
rebuild or unchanged other-product suites were needed for this test-only change.
