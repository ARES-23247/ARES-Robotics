# Routines, controls, and generated robot code

ARES uses one trigger-neutral **routine** model for autonomous motion, controller macros, reusable
mechanism sequences, and test procedures. A routine describes what the robot should do; the place
that invokes it supplies when and why it runs.

This separation removes the old path-versus-auto split without coupling reusable behavior to a
match mode:

- a `DRIVE_TO` step owns its field target and motion settings;
- an autonomous catalog entry adds a starting pose, alliance metadata, ordering, and enabled state;
- a controller binding can start, queue, restart, toggle, or cancel the same routine;
- a `CALL` step composes a smaller routine into a larger one.

The shared schema supports actions, drive goals, waits, conditions, parallel groups, first-to-finish
groups, deadlines, routine calls, repeats, and branches. Validation rejects missing capabilities,
recursive calls, invalid timing, and incompatible exclusive resource use before a robot starts.

## Canonical project files

Robot repositories keep human-edited inputs under `.ares/`:

| Path | Purpose |
| --- | --- |
| `.ares/project.json` | League, coordinate convention, field dimensions, and robot footprint |
| `.ares/action-catalog.json` | Stable action and condition keys, parameters, contexts, and resource claims |
| `.ares/routines/<id>.aresroutine` | Versioned, trigger-neutral routine |
| `.ares/autonomous-catalog.json` | Autonomous entry points, starting poses, default choice, and alliance policy |
| `.ares/controllers/<id>.arescontroller` | Controller drawing, named controls, and per-platform raw HID mappings |
| `.ares/controls/<id>.arescontrols` | Bindings from controller events to actions or routines |
| `.ares/history/...` | Immutable revisions written by Analytics for local recovery |

These files are portable JSON and require neither a connected robot nor internet access. The
project metadata is mandatory: generation and field-boundary validation fail closed rather than
guessing a season, origin, or robot size. The retired `.aresauto` format is unsupported; routines
use `.aresroutine` plus the autonomous catalog exclusively.

The action catalog is authoritative. Analytics automatically loads it when a project is selected,
so students do not edit action names by hand or rely on Kotlin source scanning. Adding a new robot
capability still requires its runtime implementation and matching catalog descriptor to be changed
together. Generated-code and runtime contract tests catch drift.

## Controller model

A controller profile gives physical controls stable names such as `a`, `right_trigger`, or
`rear_m4`. Mappings are platform-specific because desktop GLFW, FTC, and FRC can report the same
device with different raw indexes. Never copy a desktop raw index into FTC or FRC without using the
editor's learn/verification flow on that platform.

Each logical controller assignment also declares its zero-based Driver Station/HID `devicePort`.
Labels such as `driver` and `operator` explain responsibility; they never imply wiring. Validation
rejects missing or duplicate ports. Code generation additionally enforces FTC ports 0–1 and FRC
ports 0–5. The current robot runtime installs exactly one checked-in competition scheme so it can
never guess which of several schemes should control enabled hardware.

Control schemes support:

- press, release, held, hold-after-delay, and repeat events;
- press/release debounce, cooldown, and maximum-active safety limits;
- chords that can suppress their constituent single-button bindings;
- continuous analog values with deadband, exponent, inversion, slew rate, and change thresholds;
- analog thresholds and zones with hysteresis;
- action targets and routine start/queue/parallel/toggle/cancel policies.

The runtime snapshots input into preallocated `InputFrame` buffers. It uses `RobotClock`, cancels
active bindings on disconnect or time rewind, and avoids reflection or hardware reads in binding
evaluation. Season controllers run first and generated bindings run second during active TeleOp,
so an explicit generated binding is authoritative for that frame. Generated bindings are cancelled
on disable, stop, and every mode transition; they do not run during INIT or autonomous. Analog
bindings that create discrete subsystem tasks must use on-change emission with a positive epsilon
so ordinary joystick noise cannot flood a task queue.

## Deterministic Kotlin generation

`AresProjectCodegenCli` validates all canonical documents and writes a deterministic Kotlin source
file containing the catalog contract, routines, autonomous entries, controller profiles, and
binding factories. Generated Kotlin is intentionally checked in: FTC and FRC builds stay hermetic
at an event, and reviewers can see exactly what will be compiled onto the robot.

Each season repository exposes two Gradle tasks:

```powershell
# Regenerate disposable output after a GUI/document edit
.\gradlew.bat generateAresProject

# Fail if canonical input is invalid or disposable output does not regenerate deterministically
.\gradlew.bat verifyAresProject
```

Robot compilation depends on `verifyAresProject`. Do not hand-edit `GeneratedAresProject.kt`; edit
the `.ares` documents and regenerate it. Generation is not a deployment step and does not need the
robot, Driver Station, Google Drive, or GitHub.

## Library ownership

- `com.areslib.catalog` owns capability descriptors and typed arguments.
- `com.areslib.routine` owns documents, codecs, validation, compilation, lifecycle state, and the
  novice Kotlin routine DSL.
- `com.areslib.controls` owns project documents, validation, controller profiles, and the novice
  controls DSL.
- `com.areslib.input` owns allocation-conscious runtime evaluation.
- `com.areslib.codegen` owns deterministic project generation and its CLI.
- `ftc-hardware` and `frc-hardware` adapt platform gamepads to the shared input frame.

Season repositories remain responsible for turning a stable action key into Redux-backed robot
behavior and for configuring drive-step trajectory factories.
