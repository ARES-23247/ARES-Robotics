# Subsystem DSL and visual builder

ARES supports three deliberate learning levels. They use the same lifecycle and state contracts,
so students can move between them without rewriting the robot architecture.

## Level 1: visual builder

Open **Robot -> Subsystem Builder** in ARES Analytics. Select the robot project, add hardware,
state values, and control rules, then choose **Save & Generate**.

Adding hardware creates explicit normal state rather than hiding inferred behavior. A motor adds
cached position, velocity, and current plus its target/controller; a servo adds its normal command
state; a sensor adds its typed reading. These fields remain ordinary editable descriptor entries,
and teams can add mechanism-specific status/configuration values beside them.

The editor stores a versioned `.ares/subsystems/<id>.aressubsystem` document and generates:

- a readable `subsystem { ... }` definition;
- immutable typed state;
- cached `SubsystemIO` plus FTC or FRC hardware wiring;
- a low-allocation controller and lifecycle host;
- mock IO and a starter test;
- a generated registry that installs the mechanism into the robot lifecycle.

Each target state also derives one typed action key, such as
`subsystem.elevator.set.targetMeters`. Generated project code merges those actions with the manual
capability catalog, so controller bindings and routines can use a new subsystem without handwritten
adapter methods. Derived keys may not be shadowed by a different manual action.

The normal robot build verifies that managed Kotlin matches the document. A stale or invalid
document fails the build instead of silently deploying different behavior.

## Level 2: hand-authored DSL

The generated definition is ordinary Kotlin and is intended to teach the vocabulary:

```kotlin
val elevator = subsystem("elevator", "Elevator", SubsystemPlatform.FTC) {
    description = "Moves the carriage"

    val target = state.double(
        "targetMeters", "Target", SubsystemFieldRole.TARGET, default = 0.0, unit = "m"
    )
    val position = state.double(
        "positionMeters", "Position", SubsystemFieldRole.MEASUREMENT, default = 0.0, unit = "m"
    )
    val leader = hardware.motor("leader", "Leader motor") {
        hardwareMapName = "elevator"
        measurement(
            position,
            SubsystemMeasurementSource.MOTOR_POSITION_NATIVE,
            scale = 0.0005, // Measured meters per FTC encoder tick for this mechanism.
        )
    }
    control.positionPid("position", "Position", leader, target, position) {
        kP = 8.0
        feedforward.kind = SubsystemFeedforwardKind.ELEVATOR
        feedforward.kS = 0.2
        feedforward.kV = 1.1
        feedforward.kG = 0.35
        feedforward.velocityField = target
        derivativeFilterTimeConstantSeconds = 0.02
        minimumOutput = -4.0
        maximumOutput = 10.0
    }
}
```

Follower devices share a leader command and do not own a second control loop:

```kotlin
val leader = hardware.motor("leader", "Leader") { hardwareMapName = "leftFlywheel" }
hardware.motor("follower", "Follower") {
    hardwareMapName = "rightFlywheel"
    follow(leader, SubsystemFollowerTransform.INVERTED)
}
```

For positional servos, use `MIRRORED_POSITION` to apply `1.0 - leaderPosition`. Motor and
continuous-servo followers support `SAME_DIRECTION` and `INVERTED`. The generated physical and mock
adapters command the entire group inside the same guarded write and safe the group on failure.
Set a device's `inverted = true` separately when its physical mounting is reversed. ARES applies the
follower relationship first and the device mounting inversion second, so selecting both is an
intentional double reversal rather than an ambiguous alias.

Continuous position mechanisms use the same position or profiled-position PID DSL with an explicit
2π-radian input range:

```kotlin
control.positionPid("azimuth", "Azimuth", motor, targetAngle, measuredAngle) {
    kP = 4.0
    continuousInputEnabled = true
    continuousInputMinimum = -Math.PI
    continuousInputMaximum = Math.PI
}
```

Both fields must use `rad`. Generated code wraps the profile's remaining distance, PID error, and
derivative delta to the shortest signed path. Do not enable this for a hard-limited arm whose two
ends cannot move through the declared boundary.

On/off control has two thresholds. `tolerance` stops the output; `hysteresis` is the additional
error required before a stopped controller restarts. A sign reversal neutralizes for one periodic
tick before applying the opposite bounded output.

Homing is a generated state machine rather than an adapter side effect. The DSL supports digital
sensor, current-stall, velocity-stall, combined current-and-velocity stall, and custom cached
evidence. Every active method declares a bounded search output, evidence dwell, attempt timeout, and
assigned zero. Combined stall evidence is preferred for sensorless homing because high current alone
can also mean mechanism drag, while low velocity alone can mean a disconnected encoder.

Students may use the same DSL in handwritten utilities, tests, and custom generators. Managed files
carry a content hash and are overwritten on generation, so they must not be edited in place. A
student who takes ownership keeps the `.aressubsystem` document but changes its implementation kind
to `HAND_AUTHORED`. The document becomes the GUI-facing contract; the Kotlin files remain the
implementation source of truth and are never overwritten.

## Level 3: custom Kotlin

Advanced students can implement `SubsystemIO` and `Subsystem` directly, write a custom reducer, or
compose season state by hand. The non-negotiable contracts remain:

- hardware reads happen once in `refresh()`/`readSensors()` and getters return cached values;
- output commands fail closed on non-finite values and respect the supplied power scale;
- states are immutable and updates are dispatched through Redux;
- named generated states use `RobotAction.UpdateNamedSubsystemState`, so one mechanism cannot
  replace another mechanism or the season-specific superstructure state;
- robot time comes from `RobotClock`;
- `safe()` and `close()` leave every continuous actuator at zero effort.

Register custom Kotlin explicitly rather than asking ARES to scan source code. A hand-authored
descriptor records:

- `implementation.kind = HAND_AUTHORED` and `ownership = USER_OWNED`;
- the owning Gradle module and normalized project-relative source files;
- the subsystem, IO contract, and hardware-adapter class names;
- mock/simulator availability and its adapter class when present;
- teaching level, concepts, and an optional documentation path;
- catalog action keys implemented by the subsystem.

The descriptor does not grant the generator permission to edit those files. Hand-authored
descriptors must set `generateMockIo` and `generateTest` to `false`; verification for them belongs in
ordinary user-owned tests. Catalog merge fails if a declared action key is missing, so Controller
Bindings cannot silently advertise an action that has no project capability. ARES does not parse
Kotlin imports, reflection metadata, or class bodies to discover any of this information.

Example JSON shape:

```json
{
  "schemaVersion": 11,
  "documentId": "prism",
  "displayName": "Prism lights",
  "kotlinTypeName": "Prism",
  "platform": "FTC",
  "implementation": {
    "kind": "HAND_AUTHORED",
    "ownership": "USER_OWNED",
    "modulePath": ":TeamCode",
    "sourceFiles": ["TeamCode/src/main/java/org/example/PrismSubsystem.kt"],
    "subsystemClassName": "org.example.PrismSubsystem",
    "ioContractClassName": "org.example.PrismIO",
    "hardwareAdapterClassName": "org.example.FtcPrismIO",
    "simulation": { "support": "HAND_AUTHORED_MOCK", "adapterClassName": "org.example.MockPrismIO" },
    "teaching": {
      "level": "BEGINNER",
      "summary": "A small output-only subsystem.",
      "documentationPath": "docs/examples/prism.md",
      "concepts": ["safe neutral", "vendor adapter"]
    }
  },
  "capabilityActionKeys": ["prism.setEffect", "prism.off"],
  "tuningParameters": [],
  "generateMockIo": false,
  "generateTest": false
}
```

The complete document also contains its hardware, state, control, and safety contract; those fields
were omitted from this example only to keep the ownership metadata readable.

Generated PID loops reject non-finite sensor/target data, filter derivative noise, and use
conditional integration to prevent windup while saturated. Target limits are enforced both in the
catalog arguments and at the controller boundary. Feedforward is typed as `NONE`, `SIMPLE_MOTOR`,
`ELEVATOR`, or `ARM`; it supports `kS`, `kV`, `kA`, and `kG` plus explicit desired velocity,
acceleration, and arm-angle fields. Keep target, measurement, and feedforward field units coherent.
The codegen test suite compiles and executes every strategy exposed by the visual picker; source
substring checks are not accepted as the only controller-behavior evidence.

Hardware signals are never relabeled implicitly. Select native position, native velocity, current
amps, digital state, analog volts, or color ARGB explicitly, then provide scale and offset when
converting to mechanism units. FTC motor native units are encoder ticks (and ticks/second); Phoenix
6 FRC motor native units are rotations (and rotations/second). The cached value is
`raw * scale + offset`.

## Platform addressing

FTC devices use hardware-map names. FRC TalonFX motors use CAN ID/bus, while PWM, digital, and analog
devices use channels. The editor validates these rules before it generates source. FRC color-sensor
wiring is intentionally rejected until a concrete I2C implementation is selected; the builder never
guesses a hardware API.
