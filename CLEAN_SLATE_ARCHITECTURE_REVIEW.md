# ARES Clean-Slate Architecture Review

> **Implementation status (2026-08-27):** The architectural boundary program described here is
> implemented in the ARES-Robotics source monorepo. See
> [CLEAN_SLATE_ARCHITECTURE_COMPLETION.md](CLEAN_SLATE_ARCHITECTURE_COMPLETION.md) for the ownership,
> rollback, verification, migration, and deliberately deferred-work record. This review remains the
> design rationale; it is not a claim of physical robot validation.

## Executive recommendation

ARES should be **restructured, not rewritten**. The core architectural ideas are appropriate for a
safe, zero-code robotics platform:

- Canonical `.ares` documents as the editable source of truth
- Immutable Redux state and pure reducers
- Separate domain, controller, IO, hardware, simulation, and verification responsibilities
- Hardware reads cached once per loop and fail-closed IO boundaries
- Deterministic generation into Gradle generated-source directories
- Simulation parity and generated safety tests
- Offline-first robot operation and laptop-owned cloud synchronization

The most costly debt is at the boundaries around those ideas. Project loading, validation,
capability derivation, code generation, platform hosting, and dependency selection are currently
distributed across too many modules and repositories. This creates several ways to obtain subtly
different views of the same robot project.

If ARES were starting from scratch, it should use one source monorepo containing multiple isolated
Gradle builds, one typed project-model/compiler pipeline, explicit league runtimes, explicit
controller targets, league-specific simulator products, and generated public starter mirrors.
FTC/FRC, Control Hub/roboRIO/Systemcore, and physical/simulated hardware are independent dimensions;
they must not be collapsed into one platform enum or one universal runtime. Existing repositories
can migrate toward that architecture without discarding the working control, safety, simulation,
analytics, or UI behavior.

## Verified debt

### 1. `core` and `codegen` are not real physical module boundaries

The `codegen` Gradle project compiles selected files from `../core/src/main/kotlin`, while `core`
excludes those same files. Tests use the same include/exclude technique. Consequences include:

- A source file's path does not identify its owning artifact.
- A focused `:core:test --tests ...` can appear successful while the named codegen test is excluded.
- IDE ownership, dependency analysis, and refactoring are harder than necessary.
- `SubsystemKotlinGenerator.kt` has grown beyond 3,000 lines because schema, planning, rendering,
  platform details, and test generation meet in one object.

### 2. Studio does not have one complete project model

`AresProjectDocuments` builds an effective capability catalog by merging subsystem-derived actions,
but individual screens can still instantiate repositories directly. The Autonomous Builder defect
was caused by exactly this split: Controller Bindings used the effective catalog while the routine
editor loaded the raw catalog.

The aggregate also does not yet include every canonical input: drivetrain, field, AprilTag,
tuning, verification evidence, and some deployment configuration travel through separate paths.
Its package under `viewmodel.project` makes persistence and cross-document validation look like UI
concerns even though services and packaged validators also consume it.

### 3. Generated-project platform hosts are duplicated

FTC, FTC Starter, FRC, and FRC Starter contain large, closely related runtime adapters. Examples
include `FtcGeneratedProjectRuntime.kt`, `AresAutoDSL.kt`, and
`FrcGeneratedControlsRuntime.kt`. These are mostly ARES platform behavior rather than team-owned
robot behavior. Keeping copies in consumer repositories makes fixes require synchronized PRs and
allows starter and example behavior to drift.

### 4. Dependency identity is repeated and occasionally contradictory

The current release pin is declared in several `gradle.properties` and build files. Some fallback
values still name older releases, while local validation rules are separately implemented in
consumer builds. ARES-FRC still includes `mavenLocal()` in normal repository configuration.

The safeguards are valuable, but the policy should be implemented once. The desired rule is:

1. One immutable pinned release for ordinary builds.
2. One explicit candidate version plus one isolated repository for cross-project validation.
3. No ambient `mavenLocal()` provenance.
4. One generated dependency manifest carried into Studio, starters, CI, and packaged diagnostics.

### 5. Several files combine too many reasons to change

Representative concentrations include:

- `SubsystemDocument.kt`: schema, hardware taxonomy, units, safety types, and extensive validation
- `SubsystemKotlinGenerator.kt`: artifact planning and every Kotlin renderer
- `AresKotlinProjectGenerator.kt`: project API, routines, controls, and orchestration rendering
- `SubsystemGeneratorViewModel.kt`: draft editing, validation, file reconciliation, AI proposals,
  preview/diff, and persistence
- `MainScreen.kt`: navigation and construction of many long-lived feature models
- `MatchLogRepository.kt`: ingestion persistence and many query shapes

Large files are not automatically wrong, but these files span independently testable ownership
boundaries and make changes collide.

### 6. UI components depend directly on concrete view models

Many Compose sections accept a `*ViewModel` rather than immutable UI state and event callbacks.
That makes isolated previews, component testing, feature extraction, and alternate workflows harder.
It also encourages file and repository operations to remain inside view models.

## Clean-slate target architecture

```text
Canonical .ares documents
          |
          v
ares-project-schema
  codecs, stable typed IDs, units, schema migrations
          |
          v
ares-project-model
  complete immutable RobotProjectSnapshot
  capability derivation + cross-document validation
          |
          v
ares-project-compiler
  validated typed IR
      |            |              |
      v            v              v
 generated Kotlin  generated tests  verification manifest
      |
      v
generated platform-neutral robot behavior
      |
      +---------------- league lifecycle ----------------+
      |                                                  |
      v                                                  v
ares-league-ftc                                   ares-league-frc
FTC OpModes, field/rules, DS                      FRC lifecycle, field/FMS
      |                                                  |
      +--------------- controller target ----------------+
      |                         |                        |
      v                         v                        v
Control Hub FTC       Systemcore FTC targets     roboRIO/Systemcore FRC
                      Motioncore/Expansion Hub
      |                         |                        |
      +---------------- typed device adapters -----------+
                                |
                                v
                     physical hardware or the
                  matching league/device simulator

ARES Robotics Studio
  ProjectSession -> feature use cases -> immutable UI state -> Compose UI
```

This is a compatibility matrix, not a single inheritance tree. A target is assembled from a league
lifecycle, controller/deployment provider, hardware-binding profile, device drivers, and simulator
profile. Sharing is permitted only where the live implementations prove the same stable contract.

### Systemcore design correction

Systemcore is shared controller hardware and WPILib infrastructure, not evidence that FTC and FRC
will expose identical application libraries or robot behavior. Current public testing already shows
FTC-specific OpModes, Expansion Hub packages, Motioncore/A301 topology, and hybrid configurations,
while FRC retains its own lifecycle, FMS, power, vendor CAN, and drivetrain ecosystems. The relevant
public sources are the [WPILib Systemcore testing repository](https://github.com/wpilibsuite/SystemcoreTesting),
the [FTC alpha instructions](https://github.com/REVrobotics/SystemCoreTesting/blob/main/FTC-Testing.md),
and the [WPILib OpMode documentation](https://docs.wpilib.org/en/latest/docs/software/basic-programming/opmodes.html).

Do not create one large `platform-systemcore` runtime. Prefer concrete targets such as:

```text
target-ftc-control-hub
target-ftc-systemcore-motioncore
target-ftc-systemcore-expansion-hub
target-frc-roborio
target-frc-systemcore
```

A small `systemcore-common` module may be extracted later for proven common controller discovery,
deployment, filesystem, logging, networking, or onboard-I/O behavior. It should emerge from two
working target implementations rather than being designed speculatively from the controller name.

### Source repository topology

Use one source monorepo for ARES-owned implementation, with isolated Gradle builds where Android,
GradleRIO, Compose, or toolchain constraints require them. A Git monorepo does not require one
Gradle root.

Recommended top-level layout:

```text
platform/
  project/
    schema/
    model/
    compiler/
  runtime/
    core/
    league-ftc/
    league-frc/
  targets/
    ftc-control-hub/
    ftc-systemcore-motioncore/
    ftc-systemcore-expansion-hub/
    frc-roborio/
    frc-systemcore/
  devices/
    rev-hub/
    motioncore-a301/
    ctre-phoenix/
    revlib/
    generic-io/
  simulation/
    foundation/
    ftc/
    frc/
    device-models/
studio/
robots/
  lightbot/               # canonical GUI-authored example
templates/
  ftc/
  frc/
build-logic/
```

The public FTC/FRC Starter repositories can remain easy-to-download GitHub repositories, but they
should be release-generated mirrors. Their editable canonical template sources belong in one place;
manual changes to mirrors should be rejected by CI.

This layout is a dependency map, not a demand for dozens of immediately published artifacts. Start
with clear packages and Gradle source ownership; introduce a separate artifact only where toolchains,
dependency direction, deployment, binary size, or release cadence justify it.

### Project model

Create one `RobotProjectSnapshot` that contains every canonical authoring input and a separate
`EffectiveRobotProject` produced only after migrations, derivation, and validation. Make raw and
effective catalog types distinct so a routine editor cannot accidentally accept a raw catalog.

Represent deployment as orthogonal typed selections rather than one `platform` value:

```text
league: FTC | FRC
controllerTarget: FTC_CONTROL_HUB | FTC_SYSTEMCORE_MOTIONCORE | ...
hardwareBindingProfile: physical device IDs, ports, buses, direction, topology
simulationProfile: FTC/FRC physics plus matching device models
deploymentProvider: Android/ADB, roboRIO, Systemcore FTC, Systemcore FRC
```

Canonical robot intent remains portable: logical devices, required control modes, canonical units,
safety contracts, actions, and routines. Physical identity belongs in the hardware-binding profile.
Changing controller targets creates a reviewed migration proposal; it must never silently carry
forward wiring, direction, homing, or physical-validation evidence.

All Studio builders, readiness checks, codegen, simulation launch, and packaged validation must use
the same assembler. Repository objects handle bytes and revisions only; they do not decide runtime
meaning. Cross-document rules live in the project-model module.

Use typed ID wrappers (`SubsystemId`, `ActionKey`, `PortId`, `RoutineId`) in memory while retaining
stable string serialization. Avoid generic maps and reflection in generated periodic runtime paths.

### Compiler and generation

Replace direct, interleaved string construction with these stages:

1. Decode and migrate documents.
2. Build and validate a typed project model.
3. Lower it to a platform-neutral compiler IR.
4. Plan artifacts and ownership.
5. Render each responsibility with a small dedicated renderer.
6. Compile generated main and test source in contract fixtures.
7. Emit a content-hash-bound verification manifest.

Keep the existing conceptual artifacts. Reducing file count is not a goal. Mechanical registration,
lifecycle, catalog bindings, and generated tests belong in generated-source directories. Explicit
hardware extension points remain discoverable and protected from regeneration.

### Platform runtime hosts

Separate three concerns that are currently entangled:

1. League runtimes own FTC OpMode or FRC/FMS lifecycle semantics, alliance behavior, legal-state
   transitions, and competition-mode orchestration.
2. Controller targets own startup, deployment, controller health, filesystem/log access, networking,
   and controller-specific HAL integration.
3. Device adapters own cached reads, fail-closed writes, health/freshness, units, and vendor features.

Generated code supplies registries, descriptors, typed callbacks, and required capabilities.
Consumer projects supply canonical robot documents plus explicit team hardware extensions that ARES
cannot safely generate. The compiler rejects a binding when the selected target or device adapter
does not provide a required capability.

This should eliminate copied 400-800 line runtime adapters from examples and starters while
preserving separate FTC/FRC lifecycle semantics and separate Systemcore target implementations.

Do not reduce every motor or sensor to the lowest common denominator. Shared ARES contracts define
cached canonical observations and safe commands. Typed capability metadata preserves target-specific
features such as Motioncore bus topology, Control Hub bulk caching, TalonFX controller modes, or an
onboard IMU without leaking vendor calls into reducers and controllers.

### Simulation architecture

Do not build one universal physics simulator. Build separate FTC and FRC simulator products over a
small deterministic foundation:

```text
simulation-foundation
  RobotClock, deterministic scheduling, telemetry/replay, fault injection,
  geometry primitives, verification evidence, device-model contracts

ftc-simulator
  FTC OpMode/Driver Station behavior, FTC fields and scale, mecanum/tank models,
  Control Hub/Expansion Hub/Motioncore/A301 models, FTC battery and device rules

frc-simulator
  FRC lifecycle/FMS behavior, FRC fields and scale, swerve/tank models,
  roboRIO/Systemcore, CTRE/REV, power-distribution and brownout models
```

Studio may provide one consistent **Launch simulation** workflow, but it selects the simulator and
device-model set from the effective project target. Shared renderers may display poses, paths,
telemetry, and device state; they must not imply that FTC and FRC use identical physics or hardware.

Simulator parity means that the same controller decisions, safety contracts, actions, units, and
observable IO behavior are exercised through target-appropriate adapters. It does not mean one
device model should pretend to be every motor controller or robot platform.

### Studio application structure

Introduce a long-lived `ProjectSession` with one current immutable project snapshot and explicit
commands for save, remove, generate, verify, simulate, and deploy. Feature use cases subscribe to
the session instead of reopening files independently.

Organize Studio by feature, with each feature owning:

- Domain-facing use cases
- UI state and intents
- Stateless/pure Compose sections receiving state and callbacks
- Focused tests and visible journeys

Move filesystem writes, external processes, and database operations behind services. Split
`MatchLogRepository` into ingestion transactions, run queries, comparison queries, and export
queries while retaining DuckDB as the analytical engine unless profiling shows a concrete reason
to replace it.

### Verification architecture

Use four explicit layers:

1. Schema/model tests: migrations, validation, typed references, unit compatibility
2. Compiler tests: IR and compile-tested generated source
3. Target contract tests: each league/controller/device combination, safe disable, health, and
   target-specific simulator parity
4. Visible product journeys: create/open project, edit, generate, verify, simulate, analyze

Generated robot tests remain descriptor-owned. Platform and Studio tests remain hand-authored.
Every screen that consumes project capabilities must share one golden project fixture and prove the
same effective action keys are visible.

Use a deliberate compatibility matrix rather than attempting every theoretical combination. Initial
reference targets should be Lightbot on FTC Control Hub, a minimal FRC roboRIO robot, an FTC
Systemcore/Motioncore fixture when the APIs stabilize, and an FRC Systemcore fixture. Every target
must compile generated code, run lifecycle/safety contracts, and run its matching simulator tests.

## Migration plan

### Phase 0 — Guard the current contracts

- Add architecture tests that prevent UI features from directly loading raw capability catalogs.
- Add a golden cross-screen project test for Controller Bindings, Autonomous Builder,
  Superstructure Studio, verification, and codegen.
- Record current generated output and runtime behavior for Lightbot FTC and a minimal FRC starter.

### Phase 1 — Create real schema and project-model modules

- Move descriptor types/codecs into `project-schema`.
- Move derivation and cross-document validation into `project-model`.
- Define complete raw/effective project snapshot types.
- Make Studio and codegen consume the same assembler.

No robot control behavior should change in this phase.

### Phase 2 — Build a typed compiler pipeline

- Physically move codegen files and tests into the codegen project.
- Split generators by artifact responsibility.
- Add typed IR and compile every representative generated project.
- Remove source-set include/exclude tricks.

### Phase 3 — Consolidate platform hosts

- Separate league lifecycle contracts from controller targets and device adapters.
- Extract genuinely generic generated FTC/FRC host behavior into ARESLib runtime modules.
- Keep distinct Systemcore FTC and Systemcore FRC targets; extract common Systemcore code only after
  both implementations prove a stable common seam.
- Reduce starters and examples to canonical documents plus explicit hardware extensions.
- Generate starter mirrors and prove they contain no manually duplicated ARES implementation.

### Phase 4 — Split and harden simulator products

- Extract only deterministic clock, scheduling, telemetry/replay, fault injection, geometry, and
  device-model contracts into `simulation-foundation`.
- Make FTC and FRC simulator entry points, lifecycle models, field models, power models, and hardware
  catalogs explicit.
- Add target-aware simulator selection to the effective project model and Studio launcher.
- Prove that FTC-specific and FRC-specific device behavior is not silently substituted by a generic
  model.

### Phase 5 — Restructure Studio by feature

- Add `ProjectSession` and command/use-case boundaries.
- Move persistence out of `viewmodel` packages.
- Split large view models and screens by independent workflow.
- Convert reusable Compose sections to state + callback APIs.

### Phase 6 — Consolidate source and release orchestration

- Move ARES-owned source into a monorepo while preserving isolated toolchain builds.
- Generate public starter repositories from signed release inputs.
- Replace duplicated version rules with convention plugins and one dependency manifest.
- Remove `mavenLocal()` from normal builds.

## What should not be changed merely for cleanliness

- Do not collapse subsystem state, control, IO, hardware, simulation, and verification boundaries.
- Do not replace immutable Redux state with shared mutable UI/robot state.
- Do not merge FTC and FRC lifecycle hosts into one platform-agnostic class.
- Do not merge FTC and FRC simulators into one universal physics implementation.
- Do not assume Systemcore FTC and Systemcore FRC use identical libraries, device topology, or
  deployment merely because they share controller hardware and WPILib foundations.
- Do not hide valuable hardware-specific features behind an underpowered universal device API.
- Do not replace DuckDB without measurements showing a database limitation.
- Do not remove generated safety tests or simulator parity checks.
- Do not claim physical validation from configuration, compilation, or simulation evidence.

## Definition of success

- One project produces one effective model everywhere.
- A catalog-derived action cannot appear in one builder and disappear in another.
- Every tracked source file has one obvious owning module.
- Starters contain no copied ARES implementation.
- Ordinary and local-validation builds have unambiguous dependency provenance.
- Generated code is deterministic, compile-tested, and absent from editable source directories.
- FTC and FRC select independent lifecycle, controller, device, and simulation targets.
- Adding a controller family does not require changing project semantics, reducers, or controllers.
- Every supported target names its real simulator and physical-validation boundary.
- A beginner can create, verify, and simulate a robot without seeing infrastructure complexity.
- Advanced teams retain explicit, documented platform extension points.
