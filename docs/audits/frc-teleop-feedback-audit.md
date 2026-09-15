# FRC teleop feedback audit - pass 119

## Scope

Reviewed the complete `FRCTeleOpDriveController.kt`: cached inputs and deadbands, alliance
translation, drivetrain assist ownership, driver/copilot shot priority, change-only setpoints,
slamtake edges, unjam/manual intake arbitration, climber controls, traction feedback and fault
handling. Read all thirteen existing controller tests, strengthened their fixture and assertions,
and added three control-priority methods plus nine feedback methods. Shot computation and drive
hardware transforms are delegated to previously reviewed components rather than recomputed here.

Also reviewed the complete `Main.kt` entry point and its build-file main-class references.
It delegates construction and scheduling to WPILib; no hardware/services initialize in the entry
point. Compilation validates linkage. This pass does not launch the indefinite WPILib robot loop.

## Findings and fixes

- After a traction-loss rumble expired, every subsequent beached frame wrote zero to both HIDs
  and reset the cooldown timestamp. Recovery reset it again even when the pulse was already off.
  Feedback now tracks whether a pulse is active and sends outputs only at transitions. The
  two-second cooldown begins at the actual stop, and a fresh traction-loss edge can trigger
  another pulse once that interval has elapsed.
- Zero-initialized timestamps suppressed the first event near clock origin. The first traction
  loss now notifies immediately. Pulses stop at one second, on recovery, or on clock rewind.
  Elapsed checks handle signed subtraction overflow; a rewound cooldown rebases to the new time.
  A suppressed edge does not retrigger while continuously held; a new traction-loss edge is required.
- Teleop reinitialization did not clear active rumble or stale drivetrain-assist ownership.
  It now clears both and restarts notification state. Controller faults attempt both rumble-off
  writes even when one throws. Diagnostic reporting is best effort so it cannot skip the direct
  hardware stop. The existing persistent mechanism fault latch remains in place.
- The existing test fixture did not close its `FrcSwerveRobot`. It now closes after every method,
  including failures. HAL initialization uses a JUnit assertion. The repeated-drive-command test
  now compares the actual commands instead of merely checking both were nonzero.

The feedback body was first extracted unchanged into an internal method to supply deterministic
traction observations without pretending the desktop model reports physical beach detection.
All seven initial feedback tests failed against that unchanged body. Nine feedback methods now
cover first event, exact pulse/cooldown boundaries, held input, recovery, clock rewind/overflow,
teleop restart, independent HID failure handling and fault latching. Recording Xbox controllers
exercise the production notification output calls; no physical controller was used.

The three additional control methods cover unjam overriding slamtake and both manual feeds,
manual-feed release with retained pivot selection, and climber priority/release with rotation
unchanged across alliances. Existing tests cover deadband, translation, X-lock release handling,
shot presets, slamtake edges, persistent fault suppression, dashboard leases and the five-minute
simulated input soak. All sixteen existing-class methods and nine feedback methods passed.

## Validation and limits

Full FRC verification passed 271 tests, with zero failures, errors or skips. Five core allocation
regression methods passed. Generated-project/namespace verification, monorepo policy and current
documentation links passed. Gradle reused valid unchanged outputs. Before XML, final XML/logs
and normalized source hashes are retained under `ARESLib-Kotlin/build/audit-pass119-verified-evidence/`.

Removing repeated HID calls is supported by exact output-sequence assertions; no wall-clock
speedup, whole-loop zero-allocation guarantee or physical traction-detector certification is
claimed. The error collector allocates on notification transitions, not on unchanged frames.
The notification policy and manual command branches are tested on desktop; actual USB HID,
CAN hardware and robot timing still require physical validation.

ARESLib remains unchanged at candidate `17.0.3-rc.100852e472fb`, tree
`100852e472fbeeba64fdf799665f51b4687f7f1b`. Changes remain local: no push, merge or release.

## Follow-up outside this scope

The initial hardware-helper scan found that all six Talon mechanism refresh paths call a vararg
reset helper. Review that allocation boundary and the surrounding vendor refresh batching in a
separate hardware pass; neither those adapters nor their hardware behavior are closed by this report.
