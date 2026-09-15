# FRC simulator telemetry audit - pass 114

## Scope

Fully reviewed `Dyn4jSimTelemetryPublisher.kt`, all eleven methods in the existing
`Dyn4jSimulationTest.kt`, and six new methods in `SimTelemetryPublisherAuditTest.kt`.
Traced the publisher through the `ITelemetry` array-ownership contract, typed game-piece
frame encoder, Redux pose fields, and simulation entry point. Those larger dependency
files are not newly claimed as fully reviewed by this pass.

## Findings and changes

- Mechanism translation computed the same heading sine and cosine three times each.
  Cache each once per frame, reducing those six calls to two. Quaternion half-angle
  calculations remain separate and correct.
- The legacy fuel buffer cleared its entire unused capacity every frame. It now clears
  only records removed since the previous frame. New storage is zero-initialized and
  active records are fully overwritten. An unchanged empty frame avoids 700 redundant
  writes at initial capacity; two unchanged pieces avoid 686. These are source-level
  operation counts, not measured wall-clock speedups.
- The existing telemetry mock retained caller arrays, violating `ITelemetry`. It now
  snapshots them. Production reuse of one mechanism buffer across three synchronous
  publications is valid; there was no confirmed production aliasing defect.
- Nine existing tests omitted closing their simulation instance. All eleven successfully
  constructed instances now register for JUnit cleanup, including the two-instance test.
  Cleanup attempts every registered resource even if one close fails. The field-document
  publisher also closes if resetting its topic fails, and its simulation construction is
  now inside the publisher's cleanup scope.

## Mathematics and protocol checks

Known 90-degree yaw/pitch cases verify translated mechanism offsets and `(w,x,y,z)`
quaternion composition, including degree input for intake/cowl and radian flywheel input.
The existing yaw-times-pitch formula is correct. Tests keep truth, EKF, and odometry
distinct using nine nonidentical values and check scalar/atomic agreement. No truth is
substituted for an estimator output.

Mixed ground/flying records preserve identity, dimensions, planar rotation and quaternion
ordering. A count sequence crossing initial capacity and its growth allowance, with
repeated shrink/regrow/empty frames, checks zero inactive legacy entries and exact typed
frame size. Both sequences wrap after the largest exactly representable double integer.
The typed frame still reallocates when count changes because its wire contract requires
exact size; this pass does not introduce padded frames or speculative caching.

The eleven existing simulation tests exercise detector/inventory handoff, shooting with
direct and production feeder voltage, projectile motion, scoring/identity ejection,
landing, packaging, cowl units, live field rebuilding, intake geometry, and field-relative
drive. The test named `testHighCapacityInventoryLimit` checks detector handoff near capacity;
it does not independently prove the reducer's inventory limit.

## Validation and limits

Full FRC validation passed 218 tests, including six new methods and the eleven existing
simulation methods reviewed here, with zero failures, errors or skips. Five core zero-GC
methods passed. Generated-project/namespace verification and monorepo policy passed.
Gradle reused valid unchanged outputs; this does not mean every test task executed anew.

The publisher allocated 0 bytes over 100,000 warmed-up constant-count frames using a
synchronous sink. XML, logs and source hashes are retained in
`ARESLib-Kotlin/build/audit-pass114-verified-evidence/summary.json`. All four validation
processes reached terminal exit zero. No failure-before runtime regression is claimed.

No new numerical runtime error was confirmed in this scope. The changes remove redundant
work and correct test ownership/cleanup. Allocation measurement excludes serialization,
transport and count-changing frames; it does not establish robot deadlines or whole-loop
zero allocation. Physical mechanism orientation/calibration and hardware behavior were not
tested. The unchanged library candidate is `17.0.3-rc.100852e472fb`, tree
`100852e472fbeeba64fdf799665f51b4687f7f1b`. No push, merge, release or hardware run occurred.
