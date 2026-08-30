# ARES Maintainability Refactor Program

## Purpose

Reduce concentrated code-generation, Studio, persistence, and season-root coupling without changing
the canonical `.ares` ownership model, Redux flow, fail-closed behavior, generated-source ownership,
or FTC/FRC/mock/simulator parity. This is an incremental refactor, not a replacement architecture.

Every goal must leave the monorepo buildable. Behavior-changing improvements require explicit tests.
Structural moves must retain deterministic output, but pre-release schemas and APIs have no backward-
compatibility requirement: migrate every in-monorepo producer and consumer together, then delete the
superseded contract. Do not add aliases, dual readers/writers, deprecated forwarding methods, or
migration-only branches. Platform, filesystem, Git, database, and process adapters remain explicit
architectural boundaries rather than compatibility layers.

## Goal 1 — Deterministic code-generation foundation

- Characterize the complete FTC and FRC subsystem-template artifact manifests.
- Centralize Kotlin literal, identifier, and numeric rendering.
- Split subsystem artifact models, registry rendering, domain/control rendering, FTC IO, FRC IO,
  mock IO, and generated verification behind one stable `SubsystemKotlinGenerator` facade.
- Compile and behaviorally execute generated controller sources for every selectable strategy.

**Exit criteria:** full `:codegen:test`, ARESLib `test apiCheck`, unchanged characterization hashes for
structural moves, and explicit hash updates only for reviewed generator corrections.

## Goal 2 — Project-schema responsibilities

- Separate subsystem document model, validation, codec, units, identifiers, and path policy.
- Keep one authoritative schema version and reject unsupported documents explicitly.
- Preserve project-model/compiler/codegen dependency direction.

**Exit criteria:** schema/API tests, malformed-document corpus, round-trip tests, API check, and all
existing canonical `.aressubsystem` documents validate without fallback interpretation.

## Goal 3 — Subsystem Builder application boundaries

- Extract a pure descriptor editor with undo/redo and reference-safe mutations.
- Separate persistence/revision, removal/recovery, generation, and AI proposal coordinators.
- Keep the ViewModel responsible only for UI intent orchestration and state projection.

**Exit criteria:** pure editor tests, recovery tests, AI proposals cannot write files, visible Builder
journey, generated diff review, save, regenerate, remove, restore, and verification report.

## Goal 4 — Studio composition and rate domains

- Introduce typed feature scopes and an application route host.
- Move high-rate gamepad and telemetry collection into visible consumers.
- Retain one composition root while preventing features from pulling arbitrary services.

**Exit criteria:** no root collection of 20–50 Hz controller/telemetry state, navigation/state tests,
two verified desktop launches and graceful shutdowns, and no regression in simulator control leases.

## Goal 5 — External service boundaries

- Split process supervision from Gradle planning, builds, deployment, simulation, ADB, and logcat.
- Split local Git, GitHub authentication/remote backup, recovery, archive, and autosync.
- Separate Google Drive synchronization from Gemini/forensics/chat/SQL assistance.
- Split match-log persistence into domain repositories over one DuckDB transaction coordinator.

**Completed boundaries:** project builds and robot deployment; GitHub App authentication; automatic
GitHub backup preference, scheduling, retry state, and worker lifetime; reviewed GitHub restore and
local safety-point recovery; GitHub destination selection and backup synchronization.
`ProjectVersionControlService` now receives one explicit history-change event and contains no
authentication, remote mutation, background automation, restore, recovery, or archive policy.
Archive export is isolated and reuses the one canonical project-root contract. Google Drive/AI
separation is complete: `SyncEngineService` owns only immutable Drive session/profile
synchronization; `GenerativeAiService` owns provider authentication and transport;
`RobotDesignAssistantService` owns reviewed Builder proposals; and `AiDiagnosticsService` owns
forensics, coaching, and guarded telemetry-query interpretation. All callers use the new services
directly; the removed `SyncEngineService` AI methods have no compatibility forwarding layer.
DuckDB now has one `DatabaseTransactionCoordinator` for connection selection, serialized writes,
concurrent reads, metrics, and checkpoint boundaries. `SessionMetadataRepository` owns session
identity/import state, summaries, annotations, match metadata, alerts, topology, and console
evidence. `TelemetryRepository`, `RobotActionRepository`, and `RunEvidenceRepository` separately own
time-series data, action history, and analysis/import evidence. `ReadOnlyQueryRepository` owns bounded
diagnostic SQL. `DatabaseService` composes those current contracts; `MatchLogRepository` was deleted
instead of retained as a forwarding facade.

**Exit criteria:** current boundary contract tests, cancellation/shutdown tests, offline behavior, database
transaction/recovery tests, Git backup/restore fixture, and cloud/AI failures remain isolated.

## Goal 6 — Reuse and runtime ownership

- Consolidate module-scoped digest and atomic-write policies; do not create a generic utility dump.
- Remove unused thread-per-sensor wrappers or move supported devices onto bus-scoped polling.
- Replace process-global hardware ownership with an injectable per-robot registry where practical.

The first runtime-ownership slice removed the unused `ThreadedColorSensor`,
`ThreadedDistanceSensor`, and `ThreadedMultizoneDistanceSensor` APIs together with the unused
FTC-specific `FtcColorSensor`, `FtcDistanceSensor`, `FtcRevColorSensorV3`, and `FtcVL53L5CX`
implementations. Their tests only exercised the obsolete wrappers; no robot, starter, generator, or
simulator used them. They were deleted rather than retained behind compatibility aliases. Supported
hardware continues toward the centralized `SyncPolledDevice`/bus-scoped path, where polling and
lifecycle ownership can be attached to one robot instance instead of one daemon thread per sensor.

The second slice replaced the process-global `HardwareRegistry` singleton with one registry owned by
each `AresRobot`/platform composition root. Power managers, telemetry, simulator publishing, generated
subsystem factories, FTC/FRC season robots, and both starters now receive that exact instance.
Generated and vendor IO constructors no longer self-register. The old static API was removed rather
than forwarded, and every in-monorepo consumer migrated to the current contract. Isolation tests prove
that refreshing, safing, closing, and polling one robot cannot mutate another robot's devices.

**Exit criteria:** no orphan production APIs, multiple simulated robot instances remain isolated,
hardware reads remain once per loop, and zero-allocation regression tests remain green.

## Goal 7 — Curriculum and FRC season composition

- Move Academy lesson content into versioned, schema-validated resources.
- Retain Compose renderers as presentation rather than curriculum storage.
- Extract FRC real/sim hardware factories and mechanism commissioning from `TimedRobot` lifecycle.

**Exit criteria:** catalog validation, lesson navigation and progress tests, FRC unit/simulation tests,
and explicit lifecycle methods remain readable and fail closed.

## Goal 8 — Integrated acceptance

- Run ARESLib tests/API checks and isolated candidate publication.
- Validate FTC, FRC, both starters, simulators, and Studio against the same candidate artifacts.
- Perform visible Studio robot creation, verification, simulator driving, telemetry import/replay, and
  clean shutdown journeys.
- Record physical commissioning as not tested until actual hardware is available.

**Exit criteria:** all automated and visible acceptance evidence is linked from the release record;
no simulator result is described as physical validation.

## Guardrails

- No file-size-only refactors: split by ownership and external boundary.
- No arbitrary Kotlin reverse engineering or replacement of user-owned source.
- No cloud calls from robot code.
- No `System` clock in runtime/library code; use `RobotClock`.
- No direct hardware reads outside cached input refresh.
- No new hot-path allocations.
- No mutable or ambient Maven artifact used as release evidence.
