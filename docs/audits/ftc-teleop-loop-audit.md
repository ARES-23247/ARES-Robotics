# FTC TeleOp loop audit - pass 171

Added five tests exercising actual competition/tuning definitions and shared loop calls.
Only robot construction and the generated-runtime boundary are mocked; the real season
input adapters, reusable frames, button handling and everyLoop callbacks execute.
Reflection in the test installs the private runtime normally constructed with hardware;
production code has no new injection API or reflection.

Tuning emits manual drive only while calibration is unarmed and resumes after disarm.
It never requests generated drive emission, even when the runtime reports drive bindings.
Competition emits manual fallback only without generated bindings and always opts into
the runtime's generated drive path. Each tested loop performs one generated update before
one robot update. Both driver/operator frames retain identity across three frames and
share the explicit RobotClock timestamp; the two port frames are distinct objects.

Heading-lock toggles once per button edge, forwards the change to the generated runtime,
and updates the manual fallback flag. Alliance and pose-reset buttons execute once while
held, in both modes; alliance toggling precedes pose reset. A separate cleanup test checks
task cancellation delegation and clearing of runtime/input-adapter references even when
robot.close throws. No production error or redundant hot-loop work was confirmed here.

Together with pass170 startup tests, both small season OpMode files are reviewed within
their callback/delegation contract. This does not certify generated control arithmetic,
calibration leases, physical motor authority, SDK scheduling, or measured loop timing.
The AresTeleOpBase adapter remains partial: construction rollback and shared SDK failure
or repeated-lifecycle integration still need dedicated evidence. Its normal input update
and direct cancellation/close hooks are now exercised.

Tests restore modified global clock, pose-validity, estimator tags and active OpMode state.
No robot hardware or Photon transport is constructed or enabled. No source change, APK
rebuild, release or push occurred. The unchanged library candidate remains
`17.0.3-rc.100852e472fb`. Evidence is retained under
`ARESLib-Kotlin/build/audit-pass171-verified-evidence/`.

Validation: five focused tests and all 127 TeamCode plus six simulator tests pass with
no skips. Monorepo policy, documentation links and staged whitespace checks pass.
Unchanged other-product suites were not rerun.
