# Subsystem authoring

The Subsystem Builder is an offline, project-backed editor under **Robot → Subsystem Builder**. It
creates the same canonical `.ares/subsystems/*.aressubsystem` documents consumed by Gradle and the
hand-authored DSL. A robot connection and cloud account are never involved.

The builder deliberately preserves separate domain, control, hardware, simulation, lifecycle, and
verification responsibilities. File count is not a design goal. The artifact plan groups the output
into **Domain**, **Control**, **Hardware**, **Simulation**, **Generated Plumbing**, and
**Verification**, and explains both the owner and destination of every file.

## Ownership labels

- **USER-OWNED** is normal source code. Generation never replaces it.
- **GENERATED STARTER** is an initial, documented customization point. If a starter already exists
  and differs, the builder shows a line-oriented diff and requires explicit confirmation before it
  can be replaced.
- **GENERATED — DO NOT EDIT** is deterministic registration, lifecycle, or DSL plumbing written to a
  Gradle generated-source directory. Change the canonical subsystem document or generator instead.

Generated plumbing is collapsed by default in the UI. This keeps attention on the files a robot
team is expected to understand and customize without hiding the runtime wiring.

## Runtime contract

Every generated or hand-authored subsystem follows one flow:

```text
Input → Redux action/reducer → immutable state → controller → IO contract → FTC or simulated adapter
```

Reducers are pure. Controllers decide outputs from immutable state and a cached input snapshot.
The platform adapter performs all hardware reads once during its refresh/read phase, stores the
results in the supplied snapshot, and writes only the already-decided outputs. The mock adapter must
implement the same contract and fault behavior as the FTC adapter.

Every target state becomes a typed capability action automatically (for example,
`subsystem.elevator.set.targetMeters`). The controls editor and routine builder discover these
derived actions; do not duplicate them in `action-catalog.json` or add handwritten glue methods.

## Capability templates

Templates select behavior and safety capabilities; they are not “fewer files” profiles.

| Template | Start here when… | Safety emphasis |
|---|---|---|
| Simple actuator | A motor or servo only needs bounded open-loop output. | Neutral output, output-write faults, current validity. |
| Position-controlled mechanism | A mechanism tracks a position measurement. | Soft limits, stale feedback, bounded position control. |
| Velocity-controlled mechanism | A flywheel or conveyor tracks speed. | Stale feedback, current monitoring, safe spin-down. |
| Sensor-only subsystem | The subsystem observes without commanding an actuator. | Cached snapshots, signal validity, close behavior. |
| Homed mechanism | Motion is unsafe until a home reference is established. | Homing gate, calibration status, soft limits, fault recovery. |
| Composite mechanism | Multiple coordinated devices form one mechanism. | Atomic snapshots, coordinated neutral, partial-failure handling. |
| Advanced/custom | The standard templates do not express the mechanism. | Every safety choice must be completed explicitly. |

The picker also offers concrete teaching starters for elevators, pivoting arms, flywheels,
intakes/conveyors, leader/follower motors, positional and continuous servos, current- or
velocity-homed mechanisms, and two-joint arms. These are still the same explicit eight
responsibilities; the template only supplies a safe, reviewable first draft.

Applicable templates declare motors, servos, sensors, cached inputs, supported control modes,
homing/calibration, soft limits, current monitoring and validity, safe neutral output, configuration
health, fault latching with explicit neutral recovery, telemetry, and autonomous actions/resources.
The builder reports missing safety decisions before generation rather than inventing permissive
defaults.

## Hardware devices

Choose the device that matches the electrical interface, not merely the mechanism's nickname. ARES
uses this choice to generate physical wiring, cached reads, canonical units, mock behavior, and safe
output handling.

| Device | FTC generated adapter | FRC generated adapter | Natural immutable state |
|---|---|---|---|
| Motor | `DcMotorEx` by Robot Controller name | Talon FX by CAN ID and bus | target voltage, position, velocity, current |
| Positional / continuous servo | Robot Controller name | roboRIO PWM `Servo` / `PWMSparkMax` | requested position or power |
| Limit / beam-break input | digital channel by Robot Controller name | roboRIO DIO | Boolean active state |
| Potentiometer / analog input | analog channel by Robot Controller name | roboRIO analog input | voltage, with explicit scale/offset |
| Analog absolute encoder | analog voltage normalized to one turn | duty-cycle encoder | angle in radians |
| Quadrature encoder | motor-port encoder | two reviewed DIO channels | position in radians and velocity in rad/s |
| Distance sensor | FTC `DistanceSensor` | analog distance sensor with metres-per-volt conversion | distance in metres |
| IMU / gyroscope | FTC IMU plus declared Control Hub logo/USB mounting | roboRIO onboard SPI gyro | CCW-positive yaw and yaw rate in radians |
| Color sensor | FTC color sensor | project-specific starter where supported | cached ARGB value |
| Pneumatic solenoid | not offered | REV PH or CTRE PCM module and channel | requested off/on state with declared neutral |
| Indicator / Prism driver | PWM device by Robot Controller name | roboRIO PWM `Servo` | normalized color or pulse-width pattern |

Unsupported platform/device combinations are removed from the picker rather than emitted as code
that merely looks complete. Generated adapters are intentionally conservative; a vendor-specific
device that needs more configuration should remain a user-owned adapter behind the same IO contract.

## Hardware connections

An FTC hardware-map name must match the Driver Station / Robot Controller configuration exactly,
including capitalization. FRC devices use the reviewed CAN bus, CAN ID, DIO/analog channel, onboard
SPI connection, or pneumatics module and channel shown by the form. The Hardware Setup page turns
the saved descriptor into a copyable wiring/configuration checklist. That checklist includes the
declared follower relationship, encoder resolution, analog distance calibration, safe neutral,
current limit, and IMU mounting details where they apply.

For an FTC IMU, also describe how the Control Hub is physically mounted: the direction the REV logo
faces and the direction the USB ports face. Those axes must be perpendicular. Generated code passes
that orientation to the FTC SDK so yaw remains in ARES's CCW-positive convention.

## Cached inputs

Every declared measurement is read exactly once during the adapter's refresh step. The adapter
converts it to the canonical state unit, validates finite/range requirements, and commits the new
snapshot only after all required reads succeed. Controllers and telemetry read the cache; they never
perform a second SDK/vendor call. This makes simulator, replay, and physical behavior comparable and
prevents one reducer tick from mixing sensor samples from different times.

### Motor-to-mechanism conversion

FTC motors report encoder counts and counts per second; FRC Talon FX starters report rotor turns
and turns per second. Those are not automatically metres, radians, or mechanism rotations. In the
motor inspector, **Mechanism conversion** asks for the encoder's native units per motor revolution,
the gear ratio in motor revolutions per mechanism revolution, and the mechanism travel/angle per
revolution. Applying it writes one explicit scale to both cached position and velocity signals.

ARES warns when a native motor signal is labeled with a physical unit while its scale remains 1:1.
That warning is not a tuning suggestion: confirm the encoder specification, gearing, and spool or
linkage geometry before simulation conclusions or physical operation. For a continuously rotating
axis, position and target must both use canonical radians before **shortest-path angle wrapping** can
be enabled. The declared input range must span exactly 2π radians. Leave wrapping off for a
hard-limited arm whose two ends are not physically adjacent.

## State roles

- **Target** is requested intent from controller bindings, autonomous routines, or a higher-level
  superstructure. A target never proves that hardware moved.
- **Measurement** is observed hardware state from the cached input snapshot.
- **Status** is a derived or categorical fact such as readiness or possession.
- **Configuration** is reviewed setup data that does not change as ordinary operator intent.

Use the natural state created with each hardware device first. Add another value only when it has a
clear mechanism-level meaning; do not duplicate a motor's position/current or a sensor's reading
under a second name.

## Control strategies

- **Direct bounded output** is for reviewed voltage, power, PWM, or binary commands without feedback.
- **Position PID** corrects measured position error. Continuous-angle mode wraps position and
  derivative error across a declared 2π-radian boundary.
- **Profiled position PID** first limits setpoint velocity/acceleration, then applies position PID.
  Continuous-angle mode also makes the profile choose the shortest path across the boundary.
- **Velocity PID** corrects measured speed and commonly pairs with simple-motor feedforward.
- **Hysteretic on/off (bang-bang)** stops inside a tolerance and requires error to exceed an
  additional hysteresis distance before restarting. A requested reversal passes through neutral
  for one controller tick.
- **Positional servo** maps a normalized target to a servo and applies its declared safe position.

The Builder offers only strategies generated and behaviorally tested by the current runtime. More
advanced algorithms are not useful menu items unless their plant model, units, tuning workflow,
safety gates, simulation, and generated verification are equally complete.

Each controller card includes **Commission this controller safely**. This contextual sandbox runs
the selected strategy—not a generic PID stand-in—against a deterministic teaching mechanism. It
can inject a load, stale or invalid cached feedback, and a signed-angle boundary crossing when the
controller supports those cases. It reports output saturation, final error, tolerance entry, and
whether a feedback fault produced neutral output. Slider changes remain a private preview until the
student chooses **Apply reviewed settings to draft**; normal structured review and Save are still
required afterward. The sandbox never commands hardware and its simple plant is not a digital twin.

ARES does not currently offer a generic cascaded position/velocity checkbox. A safe cascade needs
two independently typed and fresh measurements, separate outer velocity and inner voltage limits,
two sets of gains, saturation propagation, anti-windup across both loops, and a matching mock plant.
Pretending that the existing one-measurement position controller has those semantics would weaken
the zero-code contract. Use profiled position PID plus typed feedforward for ordinary mechanisms;
keep a genuinely cascaded controller hand-authored until that complete descriptor and runtime
contract exists.

## Builder workflow

The current builder groups the full contract into four guided stages so a student can see the
mechanism from intent through verification without losing half the screen to navigation. You can
move backward at any time; advanced settings remain collapsed until you need them or a validation
problem points to them.

1. **Purpose & Template** — choose a capability template, name the subsystem, and explain what it
   should do.
2. **Hardware & IO** — add motors, servos, and sensors using the exact Robot Controller configuration
   names. Each declared measurement is cached once per robot loop. Adding hardware also adds its
   normal explicit state: motor position/velocity/current, servo command/position, or the sensor's
   typed reading. Add extra mechanism state only when it has meaning beyond those signals.
3. **Stateflow & Control** — distinguish observed status from requested targets, connect bounded
   controller rules to actuators, then review feedback, homing, current, configuration-health,
   neutral-output, and fault-recovery requirements. The same stage exposes the typed driver and
   autonomous actions derived from writable targets.
4. **Tuning & Review** — inspect the template's recommended typed parameters, edit their
   units/bounds/defaults and apply policy, choose mock/test generation, exercise the hardware-free
   safety preview, resolve validation findings, and inspect file ownership and destinations.

Save creates the canonical document revision. Review any starter replacement diff and confirm only
when discarding the existing customization is intentional. Generate, then run the generated
contract tests and the project test suite.

Saving creates immutable history under `.ares/history/subsystems`. **Save & Generate** invokes the
selected repository's Gradle wrapper. Generated output is deterministic: unchanged input produces
byte-for-byte identical output, user-owned files are protected, and starter replacement is never
silent.

Removing a subsystem moves only its canonical descriptor into `.ares/recovery/subsystems`, refreshes
generated registry plumbing, and leaves Kotlin starters and `USER-OWNED` source untouched. The
success banner provides **Restore subsystem** for the exact reviewed descriptor. Restoration checks
the original content hash and refuses to overwrite a replacement descriptor, so recovery is both
one-click and fail-closed.

Every major editor card has a keyboard-focusable help button and hover explanation. Longer concepts
link to this guide. The homing and feedforward sections include small interactive labs; the control
card adds a contextual commissioning sandbox. These tools explain or preview configured math and
never connect to or command robot hardware.

## Learn the workflow in Robot Academy

For a guided first build, open **Help & Learn → Robot builder → Build a homed position mechanism**.
The slide-out coach follows the real Subsystem Builder rather than using a second teaching-only
form. It can observe only narrow project facts: the selected mechanism pattern, explicit natural
motor state, the locally validated safety declaration, mock/test selection, artifact-review screen,
and saved descriptor revision.

The mission then links the hardware-free homing and state-flow labs to the same concepts. Those
interactive cards are simplified teaching models; they do not execute the newly saved subsystem.
After the lesson, generation, project compilation, generated tests, consumer simulation, and a
supervised physical procedure remain independent evidence gates.

## Homing

Homing establishes where a mechanism is physically located before normal motion is allowed. ARES
supports several explicit evidence sources:

- **Digital sensor** — a limit switch, beam break, or other Boolean home signal.
- **Current stall** — current remains above a threshold while moving with a small bounded output.
- **Velocity stall** — measured speed remains near zero while a bounded homing output is applied.
- **Current and velocity stall** — recommended sensorless method: require both high current and low
  velocity, so ordinary drag or an encoder glitch is less likely to be mistaken for the hard stop.
- **Custom measurement** — an advanced combination of cached typed signals.

Sensorless homing is not “drive until something happens.” The generated controller requires an
explicit homing request, fresh and valid cached measurements, a limited search output, continuous
evidence for the configured dwell, and a hard attempt timeout. It neutralizes before assigning the
home position. Timeout, reset, or output-write failure latches a fault; a successful neutral cancel
is required before retrying. Teams must choose a homing voltage low enough not to damage the
mechanism and validate it on the real robot when hardware becomes available.

## Feedforward

Feedback and feedforward solve different problems:

- **PID feedback** observes target error and corrects it.
- **Feedforward** predicts the output required for the requested motion before error develops.

The editor offers **simple motor** (`kS`, `kV`, `kA`), **elevator** (motor terms plus constant `kG`),
and **arm** (motor terms plus `kG × cos(angle)`) models. The interactive preview shows the predicted
voltage for velocity, acceleration, and angle; PID correction is added afterward. Units matter:
`kV` and `kA` must match the units of the selected desired-velocity and acceleration fields, while
arm angle is radians. Start with SysId data when possible and validate all gains in simulation before
careful hardware testing.

Position mechanisms may use ordinary position PID or **profiled position PID**. The latter moves an
internal setpoint toward the requested goal using declared maximum velocity and acceleration,
then supplies that velocity/acceleration to feedforward. This prevents a large goal change from
becoming an instantaneous control step while retaining the same stale-feedback and neutral gates.

For a serial two-joint arm, choose **2-DOF arm** on each joint controller and select the joint it
owns. The linkage contract records both independent actuators, both cached joint-angle measurements,
link lengths, masses, centers of mass, limits, output torque per volt, and damping. The generated
controller computes coupled gravity torque; `kG` is expressed in volts per newton-metre. The mock
adapter runs the same deterministic rigid-body plant used by the interactive linkage lab, including
gravity, Coriolis coupling, damping, accepted output voltage, and joint limits. Four-bar generation
is intentionally blocked until a constrained four-bar plant and hardware contract exist.

## Cross-mechanism interlocks and jam recovery

An interlock references a stable state field on another generated subsystem. The builder offers
only real subsystem/field choices and type-compatible comparisons. Project generation validates all
references together, and the generated controller permits non-neutral output only when every
interlock passes. Missing, renamed, or incompatible references fail generation rather than becoming
an always-true condition.

Automatic jam recovery is a bounded state machine, not a timer attached to target intent. Choose an
independent actuator and its cached current measurement, then configure detection dwell, reverse or
neutral action, recovery duration, retry count, and latch behavior. The generated IO reports
accepted output and current validity. Failed recovery writes or exhausted retries latch the normal
output fault; only the explicit neutral-recovery capability can clear it.

## Field interaction simulation

Intake and launcher interactions select the actuator whose **accepted simulated output** activates
the interaction. The field simulator never trusts requested Redux intent as proof that a mechanism
moved. Collection uses the configured capture geometry and capacity. Launching assigns a velocity
in metres per second, including robot motion, rather than mixing velocity with a physics impulse.
The field editor's persisted game-piece catalog supplies stable type IDs, collision shape, size,
mass, friction, restitution, and accessible display color for every placed piece.

## Typed tuning parameters

Schema-11 subsystem documents may declare `tuningParameters`. A declaration is not a loose mutable
constant: it gives the value a stable UID, a project-wide key, a component owner, a novice-facing
name and explanation, a type, optional units/bounds/options, a default, and an apply policy. Named
robot profiles own authoritative values; the subsystem only owns their meaning and constraints.

The Builder provides a type-specific default editor for double, integer, Boolean, text, and enum
parameters. Numeric values may have finite minimum/maximum bounds. Enum values require non-empty,
unique options and a default selected from those options. Duplicate UIDs or keys, unknown component
owners, invalid bounds, and mismatched defaults block saving with a link back to the parameter.

Apply policies are intentionally explicit:

- **Live safe** still requires an explicitly armed tuning session and should be rare.
- **Disabled only** requires an armed session and a disabled robot; it is the default for ordinary
  controller gains.
- **Restart required** and **Rebuild required** never mutate the running value.
- **Calibration only** requires an authorized calibration session for that parameter.
- **Read-only vendor** documents vendor-owned values but never lets ARES change them.

Applicable capability templates begin with documented, bounded **Disabled only** declarations for
the controller values their generated runtime actually consumes: PID gains, the selected
feedforward terms, and profiled velocity/acceleration limits. Direct-output, sensor-only, and servo
templates do not pretend to have controller gains. The starting values are educational defaults,
not robot-validated tuning; test them in simulation and commission the physical mechanism before
normal use.

Optional PID, feedforward, and motion-profile presets remain available when a compatible controller
exists and declarations are missing. They copy the controller's current values, use the controller's
stable UID as owner, and default to **Disabled only**. Presets are idempotent and never replace an
existing declaration because it may already contain a reviewed team value. Parameters can be
reordered or deleted without changing the stable identity used by profiles and generated runtime
metadata.

Hand-authored subsystems use this same form. Their declarations become part of the project-wide
generated tuning catalog while their `USER-OWNED` Kotlin files, classes, module, simulation source,
and ownership metadata remain protected. AI form proposals are also prevented from rewriting those
protected fields or the stable identity/owner of an existing tuning declaration.

## Leader and follower actuators

Use **Command source → Follow …** when two motors or servos should always receive one command. This
is also called master/slave control in older documentation. A follower cannot own a second controller
rule, preventing two policies from fighting the same mechanism.

- Motors and continuous servos may follow in the same or inverted direction.
- Positional servos may follow the same position or mirror it around the 0–1 range.
- Physical FTC/FRC adapters and mock IO use the same transform.
- Neutral output, output-fault latching, cleanup, and verification cover the full group. A failed
  follower write safes the group rather than allowing asymmetric continued motion.
- **Reverse hardware direction** is a separate per-device setting for reversed physical mounting.
  The follower transform is applied first and mounting reversal second; using both deliberately
  reverses twice.

## AI-assisted form filling

The **Help me design this** card sends the current subsystem form and a student's plain-language
request to the Gemini provider configured in Profile. It does not send Kotlin source, robot logs,
network telemetry, or credentials. Gemini returns a complete form proposal—not repository writes.

A useful request describes the physical parts and the safe behavior, for example:

> Add a second motor that follows the lift motor in the opposite direction. Home downward using
> fresh current above 7 A and low velocity for 250 ms, stop the attempt after 3 seconds, and use
> elevator feedforward with position feedback.

You do not need to know the descriptor field names. The assistant should translate the physical
description into the form; hover or press the help icon beside any proposed field to learn what it
means. If important information is unknown—such as a safe current threshold—leave it unresolved
and verify it with your team rather than accepting a guess.
Students see plain-language reasoning, local validation results, and a structured before/after diff
before choosing **Apply to form** or **Discard proposal**. Applying creates one normal Undo step;
Save and Generate remain separate explicit actions. Protected platform, revision, source ownership,
hand-authored class metadata, and catalog action keys are restored locally even if an untrusted model
tries to change them. Accepted changes must still pass deterministic local validation, safety review,
ownership checks, and starter replacement confirmation. AI will never
silence safety warnings, invent a successful hardware test, generate around invalid data, or
overwrite USER-OWNED Kotlin. This proposal boundary also allows the form to remain usable offline
when Gemini is not configured.

Before applying, check the proposal in this order:

1. **Hardware:** device types, wiring names/IDs, physical reversal, and follower relationships.
2. **Safety:** neutral output, feedback freshness, current validity, limits, homing dwell, and timeout.
3. **Control:** measurement/target units, output bounds, feedback gains, and feedforward terms.
4. **Simulation:** mock support and failure cases that can be tested without a robot.

Gemini is a teaching and form-filling aid, not evidence that a mechanism is safe on hardware.

## Registering a subsystem that is already written by hand

ARES does not scan Kotlin and guess which classes form a subsystem. Imports, factories, aliases,
and conditional hardware construction make that unreliable. Instead, create a hand-authored
`.aressubsystem` descriptor and explicitly identify:

- the owning Gradle module and USER-OWNED source files;
- subsystem, IO-contract, hardware-adapter, and optional simulator class names;
- simulation support and teaching level;
- the existing action-catalog keys that drivers and autonomous routines may invoke; and
- the same hardware, state, and safety responsibilities documented by generated subsystems.

Hand-authored registration never emits or replaces Kotlin starters. Generated plumbing includes a
registration reminder while the season composition root remains responsible for constructing the
implementation. Catalog validation fails when a declared action key is missing, so the GUI cannot
silently advertise a behavior the robot does not implement.

## Writing a subsystem by hand

Hand authoring uses the same boundaries as the generator. A good implementation contains explicit
customization points for domain state/reducer behavior, controller policy, the IO contract, the FTC
adapter, and the simulated adapter. Mechanical registration and lifecycle integration should remain
generated when possible.

### Domain

- Define immutable state with safe defaults. Disabled or not-yet-configured state must imply neutral
  output.
- Define typed actions and a pure reducer. Do not access hardware, time, or mutable global state from
  the reducer.
- Use stable action keys and typed arguments so autonomous routines and controls can validate them.

### Controller

- Consume immutable state plus one cached input snapshot.
- Gate closed-loop output on configuration health, fresh/valid feedback, required homing, and the
  absence of a latched fault.
- Clamp commands to soft limits and declared output bounds.
- A failed output write must latch a fault. Recovery requires an explicit neutral command followed
  by the documented reset action; a non-neutral command must never clear the latch.
- Keep the periodic path allocation-free. Preallocate buffers and avoid collections, iterators,
  reflection, temporary arrays, and freshly constructed geometry values in the loop.

### IO contract and adapters

- Expose a mutable, reusable input snapshot owned by the subsystem. `refresh(inputs)` updates every
  field once per loop; getters must not trigger hardware reads.
- Expose explicit neutral and close/resource-cleanup behavior.
- Return or record write success so the controller can latch failed writes.
- Distinguish “zero amps” from “current reading unavailable.” Treat validity as data.
- The simulated adapter must match FTC behavior for clamping, invalid/stale feedback, faults,
  homing, neutral recovery, and close semantics—not merely nominal motion.
- Use `RobotClock` for freshness and timeouts; never call system wall/monotonic clocks directly in
  reusable robot code.

### Verification checklist

At minimum, cover:

- safe startup and neutral default output;
- disabled and stop behavior;
- invalid and stale feedback;
- failed output writes and fault latching;
- homing/calibration requirements;
- explicit neutral recovery;
- current-reading validity and monitoring;
- FTC/mock behavioral parity;
- idempotent close/resource cleanup; and
- zero-allocation periodic paths where applicable.

Prefer one contract-test suite that is run against both the FTC test adapter and mock adapter. Add
mechanism-specific tests beside it instead of weakening or replacing the shared safety contract.

### What students see

Robot Studio combines these generated checks with independent ARES tests on the **Verification**
page. The normal view explains what passed and why without showing test filenames. **Advanced
details** reveals the generated JUnit identity, result XML, and build evidence when a mentor needs
to diagnose a failure.

The report keeps five evidence claims separate: **Configuration reviewed**, **Compiled
successfully**, **Simulation verified**, **Ready for physical validation**, and **Physically
validated**. A simulator or mock result never completes the supervised physical checklist.

## Build integration

Canonical documents are the source of truth. Gradle owns generated-source and generated-test
directories and verifies that generated output is current before compilation. During focused local
development, use the explicit sibling composite build; release validation uses one isolated
candidate repository and version across every consumer. Never shadow a published version through
an ambient local Maven artifact.

See `ARESLib-Kotlin/docs/subsystem-dsl.md` for the shared DSL and code examples. The generated source
also contains ownership headers, KDoc on customization points, safety invariants, and links back to
the canonical document so it can serve as an executable example for hand-authored subsystems.

See [Hand-authored subsystem prototype](SUBSYSTEM_HAND_AUTHORED_PROTOTYPE.md) for the measured
Indicator/Prism comparison and the evidence gate used before considering Intake or Flywheel.
