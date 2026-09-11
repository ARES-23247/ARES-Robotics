# FTC calibration supervisor audit - pass 178

Reviewed the complete calibration supervisor and corrected resource ownership beginning
with client creation. Previously, connection setup, publisher/subscriber creation, INIT,
START and arming all preceded the inner cleanup block. Individual close failures could
also skip the remaining resources, and only the Driver Station STOP was attempted.

An internal resource scope now owns every acquired handle immediately. On success or
failure it attempts Driver Station STOP, calibration STOP and flush, then closes acquired
handles in reverse order with the NT instance last. Every cleanup step is attempted;
distinct errors are retained, and repeated close is harmless. Kotlin use preserves a
verification failure as primary when cleanup also fails. Three deterministic tests cover
partial acquisition, stop/close failures, ordering, idempotence and diagnostic suppression.
Those tests use fake resources, not native handles.

Connection, arming, status, completion and lease-refresh intervals now use elapsed
TimeSource.Monotonic time. Wall-clock adjustments cannot extend these waits. Existing
fixed INIT/START delays and routine status/data requirements are unchanged; this remains
a smoke runner, not a measurement of calibration parameter accuracy.

The first actual headless run completed Pinpoint spin, track-width spin and linear drive,
then failed vision calibration with zero samples. Inspection confirmed the fixture
constructed FtcMecanumRobot without a Limelight name. The fixture now binds the simulated
limelight device; no estimator truth substitution or weaker sample threshold was added.
The failed run exited and released the listening ports. Its log is retained separately.

Evidence: `ARESLib-Kotlin/build/audit-pass178-verified-evidence/`.
The library candidate remains `17.0.3-rc.100852e472fb`. No physical robot, release or push
was involved. Headless loop/wire observations are distinct from physical calibration.

A second run with the camera configured still lacked vision samples after the movement
routines. Running vision first from the initial pose produced 71 fresh updates, confirming
that the fixture must check its initial camera view before changing pose. The same
status, completion and minimum-sample assertions remain in effect.

The next run passed eight routines and rejected flywheel SysId because the synthetic
FlywheelIO inherited an unknown current reading. The fixture now caches a defined
winding-current magnitude, abs(applied voltage minus RPM/420 back EMF)/0.5 ohm, during
refresh. This is an explicit synthetic electrical model, not a fabricated sensor reading
or a bypass of the current-validity guard. A deterministic test covers both directions,
cache-only reads, transient current, steady-state decay and zero-voltage braking.

Final validation: all ten headless calibration/SysId status, sample and completion checks
passed in one run, which exited successfully. Both listening ports were released afterward
(only a client TIME_WAIT entry remained). All 13 simulator unit tests pass without skips.
Policy, documentation links and staged whitespace checks pass. Occasional Pinpoint stale
fallback messages occurred in the simulator log; these results do not certify estimator
accuracy, hardware timing or fitted calibration constants. Android/library/other product
suites were not rerun for these simulator-only changes.
