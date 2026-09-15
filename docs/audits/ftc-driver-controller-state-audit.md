# FTC driver controller state and debounce audit - pass 167

Read both season drive/alliance controllers, their complete test files, relevant OpMode
callers, InputMath contracts, RobotClock millisecond semantics and FtcMecanumRobot drive
delegation. Six initial regressions failed before the corrections.

Invalid drive input previously became a zero shaping target while the EMA retained old
nonzero commands. One invalid axis could also coexist with active commands on other axes.
The controller now clears all three smoothing accumulators for a nonfinite input frame.
Subsequent zero input stays zero. This guarantees zero commands at this helper boundary;
downstream heading control, IO slew limits and physical motor-stop timing are separate.

Shaping previously read Redux state three times, with two more reads for field-relative
alliance mirroring. One immutable state snapshot now supplies the exponent and alliance
for the complete input frame. Robot-relative calls similarly reduce three reads to one.
Tests change mocked state between accesses and assert one state read plus consistent
output. Infinite tuning exponents now use the documented fallback, like other invalid
exponents, instead of passing infinity into InputMath and producing an unintended zero
curve. The primitive smoothing path adds no per-frame container or boxed timestamp.
No loop-time or allocation profiler measurement is claimed. Fixed-alpha EMA remains
loop-frequency dependent by design.

Alliance debounce now tracks whether a first event exists separately from timestamp zero.
The first explicit request is accepted at zero/negative time, the 200-ms boundary is
inclusive, and a rewound timeline accepts the next request and starts a new debounce
window. A very large forward elapsed interval cannot wrap into suppression. RobotClock
is restored after each test; actions still dispatch through the real root reducer.

Additional tests cover both gamepad frames on blue alliance, all three axis signs,
heading-lock option forwarding and alignment/pose delegation. An old test named as a
closed-loop PID test was renamed to describe its actual shaped-command assertion.
The new pose delegation fixture initially used the wrong Pose2d/Rotation2d construction
API; two test compilation failures were corrected using the actual local Rotation2d
constructor before execution. Those were test-authoring errors, not production findings.

Both controllers and test files are reviewed within their single-robot, single-loop
ownership contract. No raw hardware reads were added. Caller freshness/enable leases,
downstream heading-controller behavior, actual motor neutralization and device timing
are not certified by mocked command assertions. FTC simulator unit tests and debug APK
assembly are distinct from physical robot validation.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass167-verified-evidence/`.
The unchanged library candidate remains `17.0.3-rc.100852e472fb`; no push, release or
physical robot operation occurred.

Validation: all 21 focused controller tests pass. Full FTC validation reports 113 TeamCode tests and 6 simulator tests, all passing with no skips; debug APK assembly succeeds. Unchanged library, Studio and FRC suites were not rerun. Monorepo policy, documentation links and staged whitespace checks pass.
