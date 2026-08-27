# Subsystem generator and hand-authoring guide

ARES subsystems preserve explicit domain, control, hardware, simulation, lifecycle, and
verification boundaries. The generator exists to make those boundaries easier to follow; reducing
the number of filenames is not a design goal.

The runtime flow is:

```text
Input -> Redux action/reducer -> immutable state -> controller -> IO contract
                                                               |-> FTC adapter
                                                               `-> simulated adapter
```

The FTC and simulated adapters implement the same contract. The lifecycle coordinates cached
observations and outputs but does not bypass Redux or hide mechanism policy inside hardware code.

## Start with a capability, not a file-count profile

Choose the narrowest template that expresses the mechanism's real safety needs:

| Template | Use it for | Required design decisions |
|---|---|---|
| Simple actuator | A motor or servo with no closed-loop feedback | Neutral output, finite command bounds, output-write failure behavior |
| Position-controlled mechanism | An arm, turret, elevator, or servo mechanism | Position units, feedback validity/staleness, soft limits, controller calibration |
| Velocity-controlled mechanism | A flywheel or regulated roller | Velocity units, feedback validity/staleness, current monitoring, effort limiting |
| Sensor-only subsystem | A beam break, range sensor, or environmental input | Validity, stale timeout, filtering, absent-device behavior |
| Homed mechanism | A mechanism whose coordinates require a limit/index operation | Homing direction/output, timeout, travel limit, calibration persistence, re-home rules |
| Composite mechanism | Coordinated actuators/sensors with shared interlocks | Component ownership, ordering, atomic state transitions, combined safe state |
| Advanced/custom | A mechanism that genuinely does not fit the above | An explicit hazard analysis and equivalent lifecycle/test coverage |

Do not select a simpler template merely to produce fewer files. Before applying a generated starter,
the definition must describe each applicable item: motors, servos, sensors, cached input snapshots,
supported control modes, homing/calibration, soft limits, current validity/monitoring, safe neutral,
configuration health, fault latching and explicit neutral recovery, telemetry, and autonomous
resources/actions. Missing safety configuration is an authoring error or an explicit warning, never
an implicit permissive default.

## Artifact ownership

Every Kotlin output begins with one of these ownership headers:

```kotlin
// ARES OWNERSHIP: USER-OWNED
// Hand-authored source. Code generation must never replace this file.

// ARES OWNERSHIP: GENERATED STARTER
// Customize this starter. Regeneration requires a reviewed diff and explicit confirmation.

// ARES OWNERSHIP: GENERATED - DO NOT EDIT
// Deterministic build output; edit the subsystem definition instead.
```

Headers do not replace normal documentation. Generated classes and public members include KDoc for
their responsibility, physical units, cached-read rule, safe neutral, fault/recovery behavior, and
the definition field that controls them. A starter containing only unexplained placeholders is not
ready to apply.

Robot Builder keeps descriptor-authored policy explicit while moving registration and contract-test
assembly into mechanical generated-source directories. Teams that outgrow generated behavior may
still adopt a generated starter as user-owned code, but doing so is an explicit ownership decision
and no longer the beginner path.

| Group | Artifact | Normal owner | Project/module | Destination | Why it exists |
|---|---|---|---|---|---|
| Domain | State/action/reducer | Generated starter, then user | `ARES-FTC :TeamCode` | `TeamCode/src/main/java/.../subsystems/<name>/` | Defines immutable intent and observations plus pure transitions. |
| Control | Controller | Generated starter, then user | `ARES-FTC :TeamCode` | `TeamCode/src/main/java/.../subsystems/<name>/` | Converts immutable state and cached feedback into bounded commands. |
| Control | Subsystem lifecycle | Generated starter, then user | `ARES-FTC :TeamCode` | `TeamCode/src/main/java/.../subsystems/<name>/` | Dispatches observations, enforces faults/interlocks, applies scale, and closes safely. |
| Hardware | IO contract | Generated starter, then user | `ARES-FTC :TeamCode` | `TeamCode/src/main/java/.../subsystems/<name>/` | Defines units, validity, refresh, output, safe, and close semantics. |
| Hardware | FTC IO adapter | Generated starter, then user | `ARES-FTC :TeamCode` | `TeamCode/src/main/java/.../subsystems/<name>/` | Resolves configured devices, snapshots reads once, and performs fail-safe writes. |
| Simulation | Mock/simulator adapter | Generated starter, then user | `ARES-FTC :TeamCode` + `:simulator` | `TeamCode/src/main/java/.../subsystems/<name>/` | Models the same cached contract deterministically without FTC hardware. |
| Generated Plumbing | Definition/registration metadata | Generated; do not edit | `ARES-FTC :TeamCode` + `:simulator` | `TeamCode/build/generated/ares/main/kotlin/` | Connects declarative capabilities to runtime discovery without hiding policy. |
| Verification | Generated contract test | Generated; do not edit | `ARES-FTC :TeamCode` | `TeamCode/build/generated/ares/test/kotlin/` | Applies the common lifecycle and FTC/mock parity contract to the declared capability. |

The generated directories are compiled by both TeamCode and the desktop simulator. They are deleted
by `clean` and recreated deterministically from the subsystem definitions. Never copy them into
`src/main` or commit them.

## Preview, apply, and regenerate

Definitions live with the offline ARES project under `.ares/subsystems/`. The Analytics authoring UI
and the command-line tasks use the same schema and generator.

```powershell
# Show categorized creates, unchanged files, safety warnings, and starter diffs. Writes nothing.
.\gradlew.bat :TeamCode:previewSubsystemChanges

# Create missing editable starters and deterministically recreate mechanical build output.
.\gradlew.bat :TeamCode:generateSubsystemStarters

# Verify definitions, ownership, generated output, and declared contract coverage.
.\gradlew.bat :TeamCode:generateAresProject
.\gradlew.bat :TeamCode:verifyAresProject
```

Generation is deterministic: the same normalized definitions and generator version produce the same
bytes and stable ordering. A normal apply may create a missing `GENERATED STARTER`, but it may not
replace an existing starter or any `USER-OWNED` file. When a newer template differs, preview prints a
structured, per-file diff. Replacement requires `replaceSubsystemStarters` and the one-time
`-Pares.subsystemReplacementToken=...` printed by preview; review the diff and commit or discard the
change normally. A user-owned file is never an eligible replacement target.

The old Android Studio `Scanner` generator was removed because it wrote directly into source,
silently replaced same-named files, had no project model, and could not prove simulation or safety
parity.

## Creating a subsystem by hand

Hand-authored code is fully supported. Add the `USER-OWNED` header and preserve the same
responsibilities. Existing `IntakeSubsystem`/`FtcIntakeIO` and
`FlywheelSubsystem`/`FtcFlywheelIO` are production examples; they are more useful than a minimal
motor demo because they include invalid-current/velocity handling and neutral-first recovery.

The official beginner example is
[`GUI-owned Lightbot lighting`](examples/GUI_OWNED_LIGHTING.md): two independent indicators and a
Prism are editable in Robot Studio, simulated, and mechanically verified without source editing.
[`Historical hand-authored lighting`](examples/HAND_AUTHORED_LIGHTING.md) remains only as an
advanced migration comparison. Generic intake and flywheel templates remain available in the
Builder for other robots.

Start by copying this design worksheet into the pull-request description or a short design note.
It is the hand-authoring equivalent of the capability template; do not start from the FTC motor API
and attempt to add safety after the controller works.

```text
Subsystem / owner:
Capability template:
Physical units and positive direction:

Hardware
- motors (name, inversion, mode, neutral):
- servos (name, range, neutral):
- sensors (name, unit, valid range, optional/required):

Cached input snapshot
- fields and validity flags:
- refresh owner and maximum age:

Control
- open-loop / position / velocity modes:
- command bounds and soft limits:
- homing and calibration prerequisite:

Safety
- configuration-health permit:
- current validity, limit, and dwell:
- failed-write latch behavior:
- explicit neutral recovery condition:
- disabled / stop / close behavior:

Integration
- Redux state, actions, reducer composition:
- controller and lifecycle registration:
- telemetry topics:
- autonomous action and resource key:
- simulated failure controls:

Verification
- startup, stale/invalid feedback, write failure, homing, recovery, parity, close:
- zero-allocation measurement method (if periodic):
- restrained hardware-test plan:
```

Use one Kotlin file for each responsibility listed in the artifact table unless the responsibility is
absent (for example, sensor-only code has no output controller). Keeping the files separate makes the
following review order useful: state first, IO contract second, controller/lifecycle third, adapters
fourth, and tests last. Copying an existing file is acceptable only after removing its season-specific
hardware names, units, and fault thresholds.

### 1. Domain

- Use an immutable `data class` for mechanism state.
- State units in property names or KDoc (`positionRadians`, `velocityRpm`, `currentAmps`).
- Separate requested intent from observed feedback and validity.
- Make actions explicit, timestamp observations with `RobotClock`, and keep the reducer pure.
- Compose the mechanism reducer with the season/root reducer; never maintain a second mutable state.

### 2. IO contract

- Extend `SubsystemIO`; add `AutoCloseable` when the adapter owns a resource.
- Expose cached values and a separate validity flag for every fallible observation.
- Document whether a missing sensor is invalid, optional, or replaced by another source.
- Give output methods physical units. Do not expose an ambiguous `set(value: Double)`.
- Require `safe()` to command the declared neutral output and `close()` to be idempotent.

### 3. FTC adapter

- Resolve required devices in the constructor and fail with the exact hardware-map name.
- Register every output-owning adapter with `HardwareRegistry`.
- Read each sensor once in `refresh()` after REV bulk-cache clearing; getters return fields only.
- Reject non-finite commands and clamp to physical/configured limits.
- On a failed output write, latch the fault and attempt neutral immediately.
- A later nonzero command must not clear a fault. Require a distinct successful neutral command and
  any declared healthy-feedback/configuration condition before re-arming.
- `safe()` and `close()` must attempt neutral even when a previous write failed.

### 4. Simulated adapter

- Implement the identical IO contract and units.
- Keep time deterministic by accepting a step duration or using `RobotClock`.
- Model limit switches, homing, invalid/stale observations, and failed writes when declared.
- Make safe/close and fault recovery observable to tests.
- Do not put a more permissive safety policy in the mock than on hardware.

### 5. Controller and lifecycle

- The controller consumes immutable state/cached inputs and emits bounded IO commands.
- Periodic methods must avoid arrays, collections, iterators, reflection, and temporary geometry.
- `readSensors()` dispatches observations only; `writeOutputs()` does not read hardware.
- Treat a non-finite/negative power scale as zero and preserve mechanism-specific setpoints only when
  doing so is safe (for example, flywheel target RPM with bounded effort).
- Register the lifecycle once with `base.registerSubsystem(...)`.
- Keep interlocks at both the Redux policy layer and the final output boundary when bypass would be
  hazardous.

### 6. Verification

Whether generated or hand-written, cover:

- safe startup before the first valid sample;
- disabled, OpMode stop, exception, and `close()` behavior;
- invalid and stale position/velocity/current feedback;
- failed motion writes and failed neutral writes;
- homing/calibration prerequisites, timeout, and soft-limit enforcement where applicable;
- fault latching plus explicit neutral-first recovery;
- current-reading validity independently of a numeric zero;
- identical FTC/mock observable behavior for refresh, bounds, faults, neutral, and close;
- idempotent resource cleanup;
- allocation regression for 50-100 Hz paths when the mechanism participates in them;
- telemetry and autonomous action/resource declarations when enabled.

Run both target environments after any mechanism change:

```powershell
.\gradlew.bat :TeamCode:testDebugUnitTest :TeamCode:assembleDebug
.\gradlew.bat :simulator:test :simulator:compileKotlin
```

Simulation cannot verify wiring, motor polarity, current-sensor accuracy, or mechanical travel.
Follow it with a restrained hardware test: wheels/mechanism clear, reduced output, known stop path,
and a second person ready to disable.

## Review checklist

Before merging, a reviewer should be able to answer yes to each question:

1. Are domain, control, hardware, simulation, lifecycle, and verification still independently testable?
2. Are all editable files obvious, documented, and protected from silent regeneration?
3. Does every output device have a safe neutral reachable through both normal close and emergency stop?
4. Are sensor reads cached once, with freshness/validity represented separately from the value?
5. Do output failures latch, stop, and require an explicit neutral recovery?
6. Does the mock enforce the same behavioral contract as FTC hardware?
7. Are hot periodic paths allocation-free where required?
8. Does preview show every source/module destination and any unresolved safety warning?
