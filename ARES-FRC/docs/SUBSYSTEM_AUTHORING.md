# Subsystem generator and hand-authoring guide

ARES subsystems preserve separate domain, control, hardware, simulation, lifecycle, and
verification boundaries. The generator exists to make those boundaries safer and easier to
discover; reducing the number of files is not a design goal.

```text
Input -> Redux action/reducer -> immutable state -> controller -> IO contract
                                                               |-> FRC adapter
                                                               `-> simulated adapter
```

The physical and simulated adapters implement the same cached IO contract. Hardware code does not
own mechanism policy, and subsystem code does not bypass `MarvinReducer` or the shared root reducer.

## Choose a capability template

Choose the narrowest template that describes the real mechanism and its hazards:

| Template | Typical use | Decisions that must be explicit |
|---|---|---|
| Simple actuator | Roller, solenoid, or servo without closed-loop feedback | Safe neutral, bounds, failed-write behavior |
| Position-controlled mechanism | Arm, turret, elevator, or hood | Position units, feedback freshness, soft limits, configuration health |
| Velocity-controlled mechanism | Flywheel or regulated roller | Velocity units, stale feedback, current validity/monitoring, effort limit |
| Sensor-only subsystem | Beam break, range sensor, or environmental input | Valid range, freshness, optional/required behavior |
| Homed mechanism | Mechanism whose coordinate frame requires an index or limit | Homing direction/output, timeout, calibration, travel limits, re-home rules |
| Composite mechanism | Coordinated devices with shared interlocks | Ownership, ordering, atomic state changes, combined safe state |
| Advanced/custom | A mechanism that genuinely does not fit the templates | Written hazard analysis and equivalent lifecycle/test coverage |

Do not select a simpler template to produce fewer files. Applicable definitions must declare motors,
servos, sensors, cached input snapshots, supported modes, homing/calibration, soft limits, current
validity/monitoring, safe neutral, configuration health, fault latching and neutral recovery,
telemetry, and autonomous resource/action keys. Missing safety configuration is a warning or error,
never an implicit permit to move.

## Artifact ownership and destinations

Every output has an ownership header:

```kotlin
// ARES OWNERSHIP: USER-OWNED
// Hand-authored source. Code generation must never replace this file.

// ARES OWNERSHIP: GENERATED STARTER
// Customize this starter. Regeneration requires a reviewed diff and explicit confirmation.

// ARES OWNERSHIP: GENERATED - DO NOT EDIT
// Deterministic build output; edit the subsystem definition instead.
```

Generated classes and public members also document units, cache semantics, neutral behavior, fault
recovery, and the definition field that controls them.

| Group | Artifact | Owner | Destination | Responsibility |
|---|---|---|---|---|
| Domain | State/action/reducer | Generated starter, then user | `src/main/kotlin/com/areslib/frc/generated/subsystems/<name>/` | Immutable intent, observations, and pure state transitions |
| Control | Controller | Generated starter, then user | Same source directory | Bounded commands from immutable state and cached feedback |
| Control | Subsystem lifecycle | Generated starter, then user | Same source directory | Observation dispatch, interlocks, scaling, stop, and cleanup |
| Hardware | IO contract | Generated starter, then user | Same source directory | Units, validity, refresh, output, neutral, and close contract |
| Hardware | FRC IO adapter | Generated starter, then user | Same source directory | CTRE/WPILib configuration, cached reads, and fail-safe writes |
| Simulation | Simulated adapter | Generated starter, then user | Same source directory | Deterministic parity model and failure controls |
| Generated Plumbing | Definition and registry | Generated; do not edit | `build/generated/ares/main/kotlin/` | Declarative metadata and season composition |
| Verification | Contract test | Generated; do not edit | `build/generated/ares/test/kotlin/` | Reusable safety and physical/simulation parity checks |

The `build/generated` directories are source sets, are removed by `clean`, are recreated
deterministically from `.ares/subsystems/*.aressubsystem`, and must not be committed.

## Preview, create, and intentionally replace

The Analytics authoring UI and these Gradle tasks call the same shared generator:

```powershell
# Writes nothing; prints categorized changes, warnings, per-file diffs, and a confirmation token.
.\gradlew.bat previewSubsystemChanges

# Creates missing starters and refreshes mechanical build output. Never replaces a starter.
.\gradlew.bat generateSubsystemStarters

# Recreate plumbing and verify project Kotlin plus subsystem ownership/content.
.\gradlew.bat generateAresProject
.\gradlew.bat verifyAresProject
```

The same normalized definition produces byte-identical, stably ordered output. A normal apply can
create a missing `GENERATED STARTER`, but cannot replace an existing starter or any `USER-OWNED`
file. If a newer template differs, review the structured preview first, then use its exact token:

```powershell
.\gradlew.bat replaceSubsystemStarters -Pares.subsystemReplacementToken=<exact-token-from-preview>
```

Changing the first header to `USER-OWNED` permanently removes that file from replacement eligibility.
The historical `generated.subsystems` package contains these editable starters, but the ownership
header—not the package name—is authoritative. Only `build/generated` is disposable.

## Creating a subsystem by hand

Hand-authored subsystems are fully supported. Start with this worksheet in a design note or pull
request rather than starting from a vendor motor API and attempting to add safety later:

```text
Subsystem / owner:
Capability template:
Physical units and positive direction:

Hardware
- motors/servos (CAN/PWM ID, inversion, neutral, configuration health):
- sensors (source, unit, valid range, required/optional):

Cached input snapshot
- fields, independent validity flags, timestamp:
- refresh owner and maximum age:

Control
- open-loop / position / velocity modes:
- command bounds and soft limits:
- homing and calibration prerequisites:

Safety
- current validity, limit, and dwell:
- failed-write latch and failed-neutral behavior:
- explicit neutral recovery conditions:
- disabled, stop, exception, and close behavior:

Integration
- state, actions, reducer composition:
- lifecycle registration and HardwareRegistry registration:
- telemetry topics and autonomous resource/action keys:
- simulated failure controls:

Verification
- startup, stale/invalid feedback, writes, homing, recovery, parity, close:
- zero-allocation measurement method for periodic paths:
- restrained physical test plan:
```

Keep each conceptual responsibility separate unless it is genuinely absent, such as the output
controller in a sensor-only subsystem. Marvin's checked-in mechanism IO and controllers are useful
production references, but copy their safety pattern only after replacing season-specific IDs,
units, limits, and fault thresholds.

### Domain and reducer

- Use immutable data classes and name or document every physical unit.
- Separate requested intent from observed feedback and its validity/freshness.
- Use `RobotClock`; reducers remain pure and compose through `MarvinReducer` and `rootReducer`.
- Do not introduce a second mutable state store.

### IO contract and FRC adapter

- Extend `SubsystemIO`; use `AutoCloseable` when an adapter owns a resource.
- Read each CAN/sensor value once in `refresh()` and expose only cached fields to getters.
- Represent validity separately from numeric zero and keep a timestamp for fallible feedback.
- Check CTRE/WPILib configuration status and make unhealthy configuration fail closed.
- Reject non-finite commands and clamp to configured/physical limits.
- Register output-owning IO with `HardwareRegistry`; register its lifecycle separately with the robot.
- A failed write latches a fault and attempts neutral immediately. Nonzero commands never clear it;
  recovery requires a distinct successful neutral command plus declared healthy conditions.
- `safe()` and idempotent `close()` attempt neutral even after an earlier failure.

### Simulation, controller, and lifecycle

- The simulator uses identical units, bounds, freshness, homing, fault, recovery, and close behavior.
- Make stale reads and failed motion/neutral writes controllable by tests.
- `readSensors()` dispatches cached observations; `writeOutputs()` never reads hardware.
- Treat non-finite or negative power scaling as zero.
- Avoid arrays, collections, iterators, reflection, geometry temporaries, and vendor object creation in
  the 50 Hz periodic path.
- Keep hazardous interlocks at both state-policy and final-output boundaries where bypass matters.

### Required verification

Generated and hand-written subsystems cover safe startup; disabled/stop/exception/close; invalid or
stale feedback; failed motion and neutral writes; homing/calibration and soft limits; fault latching
and explicit neutral recovery; current validity independent of zero; FRC/simulation observable
parity; idempotent cleanup; telemetry/autonomous declarations; and zero-allocation periodic paths
where applicable.

Run both environments:

```powershell
.\gradlew.bat test
.\gradlew.bat simulateJava
```

Simulation cannot prove CAN IDs, inversion, sensor phase, current accuracy, or mechanical travel.
Follow it with a restrained physical test when a robot is available: mechanism clear, reduced output,
known stop path, and a second person ready to disable.
