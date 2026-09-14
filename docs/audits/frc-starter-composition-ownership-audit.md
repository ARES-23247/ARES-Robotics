# FRC starter composition, registration ownership, and simulation timing audit

Pass 266, 2026-09-14. This completes the enclosing source review left open by the
[runtime lifecycle audit](frc-starter-runtime-lifecycle-audit.md). Changes remain local to the
FRC starter and its audit records; no library candidate, release reference, or archive changed.

## Confirmed defects and fixes

The outer robot only cleaned up startup failures during generated subsystem installation. A later
failure, such as autonomous catalog publication, left the runtime and bridge open. Calling
`robotInit()` twice could replace an owned runtime, and initialization after close could create an
instance that the idempotent close path would never release. The whole initialization sequence is
now covered by cleanup, repeated initialization is rejected, and all implemented periodic/mode
callbacks require a successfully initialized, open robot. An escaping callback failure closes the
composition before rethrowing the original failure. Cleanup attempts later owners despite errors,
retains unique additional failure identities, and preserves direct interruption. The public no-arg
constructor remains; an internal runtime factory allows startup failure tests.

Generated factories return all their subsystems before registration begins. If canonical tuning
failed partway through registration, later-created objects were never transferred or closed.
Both registration helpers now transfer the completed list to one batch owner. On failure, the
runtime neutralizes its owned prefix, finds distinct untransferred members by identity, attempts
all their neutral writes, and closes them in reverse order. Duplicate references cannot cause a
second close. The prefix stays runtime-owned for eventual close; a failed batch latches the runtime.
A completed batch offered to a closed runtime is also released. Factories remain responsible for
objects they fail to return; shared generated registry support already cleans up its partially
constructed list, and that library code was not changed.

Builtin tuning UIDs previously bypassed the subsystem consumer search. A subsystem could claim a
builtin UID, receive its canonical value, and then retain that old value while a later proposal was
acknowledged solely through Redux. One shared ownership lookup now counts both builtin and
subsystem consumers. Missing or ambiguous ownership rejects the proposal and advertises unsupported
consumption. All eight builtin mappings were independently verified against their Redux coefficients.
The proposed live-callback exception gap was ruled out: existing TuningManager rollback and failure
acknowledgement rethrow the callback exception, which the pass-265 runtime already latches.

The outer simulation converted absolute millisecond timestamps to floating-point seconds before
subtracting. At a large clock origin, a real 20 ms interval disappeared. It now subtracts integer
timestamps first and converts only a positive, representable elapsed interval, bounded to 50 ms for
physics. Missing, repeated, rewound, overflowing, and signed-wrap intervals integrate no motion;
subsequent valid samples recover. Timestamp zero is valid. This physics bound does not change the
uncapped measured loop telemetry fixed in pass 265.

Disabling the bridge did not clear Redux drive intent before the simulator consumed it. A disabled
simulation advanced 0.02 m on an old 1 m/s command, and `disabledInit()` alone retained the command.
The outer robot now clears drive intent through a Redux action at mode transitions, on close, and
before disabled periodic/simulation work. Bridge changes take effect in the same simulation tick.
The neutral action is retained and no action is dispatched when all three commanded velocities are
already zero. The callback guard is inline; failure-path identity bookkeeping occurs only during
batch setup failure. No whole-loop allocation or robot deadline benchmark is claimed.

## Evidence

The first native lifecycle baseline had **4 tests, 3 failures** with only a runtime-factory seam.
The registration/tuning baseline had **6 tests, 4 failures**. After the first ownership/lifecycle
fixes, the clock-origin case failed in a nine-test lifecycle run; an expanded eleven-test run then
confirmed both disabled-drive failures and repeated the timing failure. These are separate baseline
runs, not additional final passing cases. The clock test was subsequently refined to explicitly
enable the simulated Driver Station while exercising motion.

Final `verifyAresProject test`: **137 tests passed, zero failures, errors, or skips**. All 113 prior
test cases remain, plus 15 native robot lifecycle tests and 9 registration/tuning tests. Tests cover
early and late startup failure, repeat/post-close/pre-init guards, normal callbacks, mode/periodic
failure, complete cleanup, all eight tuning mappings, ambiguous ownership, live callback rollback
and latch, successful and failed batches, interruption, duplicate identity, disabled motion,
clock boundaries, alliance changes, and explicit no-motion autonomous completion.

The native fixture constructs real `TimedRobot`/HAL objects and invokes callbacks directly in the
Gradle test JVM. Its default NetworkTables instance starts on loopback with both listening protocol
ports configured as zero before RobotBase construction. It does not start a competition loop, attach
a network peer, or launch a simulator GUI. Owned runtimes/publishers are closed; the server is stopped
and field, clock, and Driver Station state restored. This is native simulation evidence, not physical
validation or proof of robot loop deadlines. Other native suites still use their isolated instances.

XML, baseline logs, bytecode inspection, source hashes, coverage inventory, and verification summaries
are retained locally under `ARESLib-Kotlin/build/audit-pass266-verified-evidence/`. All 410 files in the
existing `17.0.44-rc.f9e7569ea873` candidate repository are checked against the frozen manifest.

## Coverage and limits

[AresStarterRobot.kt](../../ARES-FRC-Starter/src/main/kotlin/org/aresfirst/starter/frc/AresStarterRobot.kt)
is now reviewed as a whole, with this pass covering outer composition, batch registration, complete
tuning dispatch, and simulation timing/disable boundaries and prior passes covering runtime faults,
initialization, loop measurement, neutralization, and close. Tests cover the generic starter's
simulation implementation; the physical adapter is deliberately absent and remains blocked.

The autonomous controller and marker algorithms in their separate source file remain the next
runtime audit area. No claim is made that a no-motion entry validates those algorithms, that neutral
attempts guarantee physical stop, or that native callbacks prove a running competition process.
Studio's existing release-alignment gate remains unresolved; the previously rejected archive and
reference migration still needs approval and a refreshed proposal. No gate was bypassed here.
