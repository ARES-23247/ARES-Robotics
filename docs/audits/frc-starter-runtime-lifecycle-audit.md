# FRC starter runtime fault, initialization, and loop timing audit

Pass 265, 2026-09-14. This follows the [drive lease audit](frc-starter-drive-lease-audit.md).
It reviews the runtime host inside
[AresStarterRobot.kt](../../ARES-FRC-Starter/src/main/kotlin/org/aresfirst/starter/frc/AresStarterRobot.kt),
its telemetry/tuning ownership, and the shared starter failure-retention helper. Work remains local.

## Confirmed failures and changes

A sensor, raw-device refresh, actuator write, or telemetry exception escaped the runtime update
without neutralizing the other owners. Earlier mechanisms could retain nonzero outputs, and
catching the exception allowed a later update to resume normally. The runtime now attempts neutral
on every subsystem and every registered raw device before rethrowing the first failure. It latches
that failure for the lifetime of the instance; subsequent operations attempt neutral again and
cannot resume sensor/output processing automatically. Recovery requires a new runtime instance.

Explicit subsystem neutral failures were silently discarded. They now remain observable and latch
the runtime, while later neutral attempts still run. Registered raw devices retain the existing
HardwareRegistry contract: ordinary exceptions in its best-effort safe pass are suppressed.
Consequently this change cannot guarantee that a failing physical actuator actually stopped.

The runtime previously allowed updates, tuning polls, and topology publication after close.
Those operations now reject before reentering owned resources. Repeated close and post-close
`safeHardware()` do not operate hardware again. Close attempts subsystem neutral first, closes
subsystems in reverse order, then the hardware registry, tuning manager, and telemetry. The
subsystem collection is cleared. Failure aggregation preserves first identity, unique additional
failures, and direct interruption through the reused
[cleanup helper](../../ARES-FRC-Starter/src/main/kotlin/org/aresfirst/starter/frc/StarterResourceCleanup.kt).

The suspected repeated-identical-close-exception defect was **ruled out**: that regression already
passed on the original runtime. The cleanup change is justified by observable neutral failures,
interruption preservation, consistent aggregation, and ownership of the tuning manager; it is not
presented as fixing a skipped-resource failure disproven by the baseline.

Registering the same subsystem instance twice previously duplicated reads, writes, and close calls.
Identity duplicates are now rejected before ownership changes. Once a new subsystem enters the
runtime's list, canonical tuning or metadata-publication failure latches and neutralizes the
runtime; the runtime retains that subsystem for eventual cleanup.

TuningManager publishes consumer-support metadata during its own construction. For additional
declared parameters, the old callback accessed the not-yet-initialized subsystem list and threw a
NullPointerException. Callback state now exists before manager construction. Required UID binding,
generated runtime construction, Redux/publisher setup, and metadata publication are enclosed in
initialization cleanup, so failure releases the transferred telemetry owner and any completed
manager. An optional typed-runtime argument permits validation with additional or missing bindings;
normal production construction still uses the generated configuration.

Loop timing was reported as an invented 20 ms first sample, used timestamp zero as an initialization
sentinel, and clipped every long measured period to 50 ms. This hid stalls from profiling. A separate
initialization flag now distinguishes the first observation; measured positive periods are reported
without a physics-style cap. First, repeated, reversed, and nonrepresentable elapsed intervals report
unknown timing through the publisher's existing NaN contract. A measured 250 ms interval now reports
250 ms and 4 Hz. This changes diagnostics, not the simulator's bounded physics timestep.

## Validation

The original runtime baseline had **12 tests, 11 failures**. Two later initialization cases, with
only a typed-runtime injection seam added, both failed: additional declarations hit the constructor
NullPointerException, and failed initial publication left telemetry unclosed. A further early-binding
case failed before the initial cleanup region was widened. Baseline runs are recorded separately;
they are not added to the final suite count. An initially incorrect topic literal in the additional
declaration fixture was corrected before final validation; its baseline failed during construction,
before reaching that assertion.

Final `verifyAresProject test`: **113 passing tests, zero failures, errors, or skips**.
All 94 previous invocations remain. The 19 new methods cover update/neutral/close failures,
initialization ownership, duplicate registration, disabled-loop read counts and neutral scale,
post-close rejection, timing boundaries, interruption and repeated diagnostic identity, additional
tuning declarations, and unique-versus-ambiguous compiled tuning consumers. Existing guided tuning,
native bridge, numerical simulation, and cleanup fixtures also pass.

Healthy disabled-loop tests verify one raw refresh and one read per registered subsystem per loop.
No new whole-loop timing or allocation benchmark is claimed: the fault-injection telemetry backend
is designed to observe behavior, not emulate production transport cost.

Tests use HAL simulation with in-memory telemetry and fake subsystem/raw-device owners. They restore
Driver Station and RobotClock state, and interruption fixtures restore the prior interrupt flag.
They do not launch a full TimedRobot process, external NT server, Studio window, or physical robot.

ARESLib remains at source tree `f9e7569ea873a08df0477fad1008b6f9ef4575e3`; all 410 files of candidate
`17.0.44-rc.f9e7569ea873` were verified unchanged. No library version change or consumer rebuild matrix
was needed. Shared guidance and documentation links passed. XML, baseline logs, hashes, and inventory
are retained locally under `ARESLib-Kotlin/build/audit-pass265-verified-evidence/`.

## Remaining coverage

The enclosing source file remains partial. The outer robot startup, mode and close paths, generated
batch registration ownership, live tuning callback failures, and possible conflicts with builtin
tuning UID ownership need further audit. Passing runtime tests does not close those boundaries.

Studio's release-alignment gate and bundled archive/reference migration remain pending. Automatic
approval review previously rejected that migration as outside the audit's no-release authorization.
The old proposal must be refreshed after approval to include subsequent source fixes. No archive,
release reference, protected check, or remote branch was changed or bypassed here.
