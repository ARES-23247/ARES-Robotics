# ARES Robotics Studio architecture

This document describes the implemented architecture of ARES Robotics Studio. It is intended for maintainers changing telemetry ingestion, persistence, replay, analytics, UI state, or the cloud gateway.

## 1. System boundary

ARES Robotics Studio is the laptop-side member of the ARES robotics workspace:

```text
ARESLib-Kotlin                     ARES-FTC / ARES-FRC
  math, state, telemetry             season robot code
          |                                |
          +------------- NT4 --------------+
                         |
                         v
                 ARES Robotics Studio app
                    |           |
               local DuckDB   local files
                    |           |
                    +----- laptop-owned cloud sync
                              |
                       Google Drive / gateway
```

Robots do not make cloud requests. They publish live telemetry and expose or store log files. The laptop pulls the data and decides whether to retain, replay, export, or synchronize it.

## 2. Gradle modules

### `shared`

Contains serialization-safe models used across process boundaries, including sessions, summaries, telemetry frames, field geometry, paths, topology, and diagnostics requests. It must not depend on Compose UI or desktop-only services.

### `app`

The Compose Desktop process. It owns:

- target discovery and NT4 connections;
- live dashboard input/output;
- DuckDB connections and schema migrations;
- log decoding and frame batching;
- deterministic, read-only replay snapshots and dashboard timelines;
- summaries, SysId, calibration, driver analysis, and alerts;
- simulator and build-process management;
- Google OAuth/Drive synchronization;
- all screen/view-model state.

Simulator launch is target-derived rather than a UI league guess. The validated effective project
selects either `ftc.desktop-opmode` or `frc.wpilib-desktop` and reports the drivetrain and subsystem
capabilities it requires. `ProjectBuildService` receives that concrete product and maps it to the
FTC `:TeamCode:runSim` or FRC `simulateJava` task. Unsupported, unavailable, or cross-league device
models remain visible in Robot Studio and fail before a process starts; Studio never substitutes a
generic drivetrain. The two products retain independent OpMode/TimedRobot lifecycles, fields,
power behavior, vendor APIs, and physics implementations.

### `gateway`

A small Ktor/Netty service for authenticated pit-forensics requests and the ARES-managed Google
OAuth token exchange. Its OAuth broker adds a protected client secret to code/refresh exchanges so
the secret is never shipped in the desktop installer. It does not persist OAuth tokens, call Drive,
store telemetry, manage session archives, or replace the local database. Drive synchronization and
token storage are desktop-owned.

## 3. Application composition and ownership

`ServiceRegistry` is the composition root. Services are lazy so startup does not initialize databases, native libraries, gamepads, cloud clients, or background scanners until a feature needs them.

The dependency tiers are:

```text
Tier 0
  DatabaseService, EnvironmentService, ProjectBuildService,
  TargetScannerService, preferences

Tier 1
  Nt4ClientService, LogParserService, ReplayEngineService,
  SysIdService, CalibrationService, OAuthService, export services

Tier 2
  AlertEngineService, DriverAnalysisService, SummaryEngineService,
  HootDecoderService, GoogleDriveService, SyncEngineService,
  Phoenix diagnostics and FTC dashboard adapters
```

Any service that owns a coroutine scope, socket, native device, process, or HTTP client must expose explicit cleanup. `ServiceRegistry.dispose()` shuts services down before closing `DatabaseService`.

Do not create an anonymous `CoroutineScope` per update. A long-lived service should have one parent `SupervisorJob`; child jobs must be cancelled or joined during target changes and shutdown.

## 4. UI architecture

The app uses an MVI-style boundary:

```text
Compose event -> ViewModel intent -> service call -> immutable/state-flow update -> Compose render
```

- Composables render state and emit events.
- View models translate events into service operations.
- Services own networking, persistence, process, and analysis side effects.
- Background work uses structured coroutines.
- A screen must not mutate another service's internal collection directly.

The main window owns keyboard state and passes it to the dashboard drive loop. Q/E are reserved for intake/flywheel actions; directional keys must not consume them first.

Dashboard widgets use one typed registry for identity, picker metadata, default layout placement,
configuration, service capabilities, and rendering. New widgets must follow the declarative
[dashboard widget extension contract](docs/DASHBOARD_WIDGET_EXTENSIONS.md) rather than adding
parallel conditionals to the picker, layout service, and dashboard screen.

## 5. Live NT4 pipeline

`Nt4ClientService` is both an NT4 subscriber and a publisher of dashboard inputs.

### Connection lifecycle

1. `start(host)` fully stops the previous connection.
2. Live database rows, pending writes, topic metadata, latest values, and history are reset.
3. The WebSocket connects with the NT4 subprotocol.
4. Dashboard input topics are announced.
5. Canonical topic prefixes are subscribed with prefix matching enabled.
6. JSON control messages update topic metadata; binary MessagePack frames update values.
7. `stop()` cancels and joins the client job before closing HTTP/WebSocket clients.

This ordering prevents data from a previous target being inserted or displayed after switching robots.

### Topic identity

Topic names are normalized by removing every leading `/`. `Drive/Pose_X` is the stored identity even if a standards-compliant NT4 peer announces `/Drive/Pose_X`.

Wire type is part of the topic contract. Boolean values remain boolean at the wire boundary; persistence converts them to `1.0` or `0.0` only where a numeric frame is required. String values use `TelemetryFrame.stringValue` and are not represented as numeric zero.

### Backpressure and persistence

Live events feed two concerns:

- current state and bounded history for the UI;
- an ordered persistence channel drained in chunks.

Recording and live frames must be partitioned by session before insertion. A batch is never routed based only on its first frame. Database failures retain/retry the uncommitted portion rather than silently acknowledging a gap.

The live session ID is `live-telemetry`; it is stored in the ephemeral database. Recorded and imported sessions use the persistent database.

## 6. DuckDB storage

`DatabaseService` owns three connections:

| Connection | Purpose |
| --- | --- |
| persistent writer | sessions, recordings, imports, summaries, alerts, actions, exports |
| duplicated read connection | read queries that should not share writer statement state |
| in-memory ephemeral connection | live telemetry rows |

Access is serialized with coroutine mutexes at the repository boundary. Keep transactions and locks narrow. Long analysis should fetch its required data and release the connection before performing numerical work.

### Core tables

- `sessions`: identity, team/season/robot, duration, match metadata, tags
- `session_summaries`: derived health/performance metrics
- `telemetry_frames`: append-only numeric and optional string samples ordered by `timestamp_us` and
  `sample_order`; new stores intentionally have no ART indexes
- `analysis_diagnostics`: replaceable analyzer-owned results keyed by session and metric; never raw timeline samples
- `session_import_reports`: decoder provenance and accepted/rejected-record evidence retained with the session across cloud round trips
- `robot_actions`: timestamped Redux/action records
- `alerts`: trigger/resolution and triage state
- `session_annotations`: human notes
- `console_messages`: exact recognized console topics
- `cached_topologies`: robot hardware topology JSON

### SQL boundaries

`executeQueryRaw` is read-only. Do not weaken its restrictions to support a write workflow. Operations such as Parquet `COPY`, where DuckDB cannot parameterize the relevant file position, belong in a narrow internal method that canonicalizes and SQL-escapes the path.

Schema migrations run before repositories are used. Legacy SQLite attach/import uses a separate schema name and explicit destination columns so adding a DuckDB column does not break positional migration.

The measured cold-start failure mode and the staged move toward immutable per-session Parquet are
documented in [Telemetry storage architecture](docs/DATABASE_STORAGE_ARCHITECTURE.md). Do not add a
primary key or secondary index to the raw telemetry fact table without a production-sized WAL
recovery benchmark. Existing installations drop the three historical secondary indexes but retain
their legacy primary-key index until an explicit, progress-reporting Parquet migration; silently
rewriting tens of millions of rows during startup is prohibited.

## 7. Log import

`LogParserService` selects a decoder by file type. Decoders emit frames incrementally through `FrameBatcher`; they must not accumulate an entire multi-gigabyte log in memory.

Every ordinary import has a durable two-state owner. `IMPORTING` owns all rows written in bounded
batches but is excluded from application queries. One final transaction upserts the session as
`COMPLETE` and records every source report. Startup recovery deletes an interrupted owner and all
of its dependent rows. This gives large imports crash consistency without holding one enormous
DuckDB transaction open for the entire decode.

Manual selection first copies through a same-directory `.partial` file, verifies byte count and
SHA-256, and atomically renames the archive copy. Originals are never moved. Exact source-digest
sets are idempotent within one team/season/robot identity and intentionally distinct across
workspaces.

Supported families include:

- ARES JSONL and CSV;
- WPILib DataLog (`.wpilog`);
- CTRE HOOT;
- Driver Station logs/events;
- REV logs;
- Road Runner and RLOG;
- Parquet.

### Decoder requirements

Every decoder must:

1. validate magic/version fields where the format supplies them;
2. use exact-length reads for fixed structures;
3. reject negative or excessive lengths before allocation;
4. make progress on every loop iteration;
5. preserve numeric and string values;
6. surface corruption or truncation rather than silently claiming success;
7. flush the final batch only after successful decoding.

The WPILib decoder follows the DataLog variable-width record header: entry ID, payload size, and timestamp widths are encoded independently. Start records contain little-endian length-prefixed strings, not null-terminated strings.

CSV import expands `_ExtraFieldsJson`, which is emitted when ARESDataLogger discovers keys after the stable CSV header. The synthetic container field must not hide those late keys from replay and analysis.

### Automatic import identity

Local, FTC, and FRC scanners maintain a durable content identity for each source file. A file that remains on a robot must not create a new session every scan. Active or changing files are deferred until stable, and a failed archive move is not treated as completed import. FRC Driver Station `.dslog` and `.dsevents` companions are stabilized, copied, archived, quarantined, and fingerprinted as one evidence set.

The Cloud screen's HTTP pull uses the same durability rules: remote basenames and declared sizes are validated, the `file` query is encoded, streaming is capped at the exact advertised byte count, and raw files are retained under the active workspace's `logs/imported/` directory before parsing. A run-level content manifest makes a cloud-upload retry reuse the local session instead of importing duplicate rows. Robot deletion is a separate authenticated action and is never part of import success.

## 8. Replay model

Replay reconstructs latched topic state, not just events in the visible query window. Persisted
`timestamp_us` is the source instant and `sample_order` is the stable tie-breaker. Playback uses a
monotonic elapsed clock; wall-clock time never reorders historical evidence.

When loading or seeking:

1. clear all state and cache bounds from the previous session;
2. query the active window;
3. query the latest frame for each key at or before the window start;
4. seed the state map with that baseline;
5. apply in-window updates in timestamp order;
6. atomically publish one immutable `ReplayFrame` for every dashboard consumer.

Without step 3, a topic last updated at the start of a match disappears when seeking later.

Replay supports numeric and string state. It does not write replay values into the live NT4 store
or broadcast them as robot telemetry. Actions, annotations, and alerts are markers only: they do
not stretch telemetry bounds or synthesize sensor/pose values. `stop()` returns to the first sample
without leaving the selected historical source; `dispose()` cancels all playback, seek, and
prefetch jobs. See [Deterministic replay and dashboard evidence](docs/DETERMINISTIC_REPLAY.md).

## 9. Analysis services

### Summary engine

Summary metrics use explicit topic families:

- minimum battery voltage comes from battery-voltage topics, not arbitrary motor voltage;
- EKF drift uses drift/pose-error topics and absolute magnitudes;
- loop statistics use loop-time topics, not generic “period” keys;
- motor current device names include the parent path of `CurrentAmps`;
- missing vision acceptance data reports no observations rather than a fabricated 100%.

Analyzer-generated diagnostics are atomically replaced in `analysis_diagnostics`. They may be projected into the diagnostics UI, but they are not appended to `telemetry_frames`, do not alter the source timestamp range, and cannot feed back into the next summary calculation.

Core summary values (minimums, averages, percentiles, counts, and grouped current values) are
computed as exact scalar/grouped SQL aggregates. Secondary algorithms that require time-series
objects receive a deterministic, ordered, per-topic sample with hard total and per-topic bounds;
the first and last ordinary sample are retained. Timestamp-gap counts remain scalar SQL and never
materialize an entire timestamp list in the JVM. DuckDB may spill working data to its configured
temporary directory and does not preserve insertion order during bulk relational operations.

### Cloud session bundles

New Google Drive uploads are immutable versioned `.ares-session.zip` objects. Each bundle contains `telemetry.parquet` plus a bounded `manifest.json` carrying the session/summary, Redux actions, annotations, alerts, console messages, analysis diagnostics, and associated import reports. The outer Drive object and inner telemetry entry both have exact size and SHA-256 checks. The manifest carries a stable league/team/season/robot workspace key, and download fails closed on an identity mismatch.

Bundle import replaces the complete session atomically across telemetry and ancillary tables. A failure at telemetry, summary, session, or ancillary insertion rolls the entire transaction back. Legacy telemetry-only `.parquet` Drive objects remain readable, but all new uploads use the complete bundle format.

### SysId

Voltage, velocity, and acceleration are independently sampled. Analysis aligns channels by bounded nearest timestamp before fitting `V = kS sign(v) + kV v + kA a`. Non-finite and near-zero samples are excluded from the regression, while zero-velocity step samples remain available for transient classification.

FFT analysis requires approximately uniform timestamps, removes the mean, applies a Hann window, and bounds input size. Reported amplitudes are window-normalized.

### Driver analysis

Driver oscillation detection searches the configured 8–12 Hz band in both relevant axes and applies amplitude/SNR requirements. The strongest global FFT peak may be intentional low-frequency driving and is not automatically “jitter.” Profiles are persisted using a mutex and atomic replacement.

### Calibration and trajectory estimation

Camera extrinsics use the standard rigid transform and actual tag field coordinates. Internal translations are meters and rotations are radians. Trajectory duration integrates segment constraints; angular constraints must not be mixed with linear limits or degrees.

### Guided tuning experiments

Guided tuning composes existing services rather than creating a second tuning runtime. A selected
run-comparison finding supplies the baseline session, evidence timestamp, topics, units, and claim
class. `GuidedTuningExperimentRepository` persists a local experiment containing a typed one-factor
proposal plus hashes of the resolved profile and canonical `.ares` configuration. The existing
tuning transaction remains the only apply boundary: Local Sim selection, loopback NT4, declared
apply policy, monotonic request nonce, and explicit consumer acknowledgement all remain required.

Candidate evidence is a distinct, later session from the same team/season/robot and must carry the
Studio-authored `simulation` tag. Evaluation reuses deterministic run comparison and classifies the
declared metric as improved, regressed, or inconclusive against the threshold chosen before the
run. Accepting records evidence only; canonical promotion remains the separate structured-diff
workflow. Reject and rollback remove the staged proposal without rewriting source or canonical
configuration.

## 10. Gateway

`gateway/Application.kt` installs:

- JSON content negotiation;
- an opt-in HTTPS CORS allowlist;
- generic exception responses with server-side detailed logging;
- Google OIDC authentication;
- per-authenticated-subject rate limiting;
- a separately rate-limited Google OAuth code/refresh broker;
- a 1 MiB request body limit;
- diagnostics request validation.

The service exposes `GET /health` without authentication and mounts diagnostics routes behind
Google authentication. The OAuth broker exposes only `POST /api/oauth/google/token` and
`POST /api/oauth/google/refresh`. It validates the fixed `http://127.0.0.1:5805/callback` redirect,
PKCE verifier, and request sizes before contacting Google. It must not log or persist authorization
codes, verifiers, refresh tokens, access tokens, client credentials, or Google's token body.

Official installers contain the public Google Desktop client ID and HTTPS broker URL. The matching
client secret exists only in protected gateway configuration. The desktop receives and securely
stores tokens, then calls Drive directly within the selected workspace root; the gateway never
receives Drive IDs or file contents. A custom OAuth client is valid only with the matching
administrator-operated HTTPS broker.

Never key rate limiting on Ktor's default `Unit` key; that creates one global bucket for all users. Never return raw exception messages to clients.

## 11. Coordinate and unit contracts

- Field frame: `+X`, `+Y`, counter-clockwise-positive heading.
- Internal angle: radians.
- Display angle: degrees only at the formatting boundary.
- Field-to-canvas transform: `canvasX = -fieldY`, `canvasY = -fieldX`.
- The robot artwork points right at zero image rotation, so the path renderer retains its `-90°` icon offset.
- Topic unit inference must distinguish position, linear velocity, angular velocity, voltage, current, and temperature.

The simulator's `ARES/EstimatedPose/*` ground truth and the robot's `Drive/Pose_*` estimate can both drive widgets. If both are connected, they must use the same coordinate convention.

## 12. Extending the system

### Add a telemetry topic

1. Define the producer and exact NT4 type.
2. Choose an existing subscribed prefix or add a deliberate prefix.
3. Normalize the name without leading `/` in storage/UI code.
4. Update `docs/TELEMETRY_CONTRACT.md`.
5. Add a wire or dispatch regression.

### Add a log decoder

1. Implement streaming decode with allocation bounds.
2. Register the extension in `LogParserService`.
3. Preserve `stringValue` where appropriate.
4. Add valid, truncated, and hostile-length fixtures.
5. Verify no partial session is reported as a successful import.

### Add a service

1. Place it in the lowest valid `ServiceRegistry` tier.
2. Inject dependencies rather than accessing global state.
3. Own one structured coroutine scope if needed.
4. Provide explicit disposal for resources.
5. Add focused tests before wiring UI.

### Change persistence

1. Update both persistent and ephemeral schemas when applicable.
2. Use explicit column lists in migrations/imports.
3. Preserve numeric and string values.
4. Test apostrophes and non-ASCII characters in IDs and paths.
5. Verify export/import round trips.

## 13. Verification strategy

The minimum cross-module check is:

```powershell
.\gradlew.bat :shared:test :gateway:test :app:test
```

Protocol/parser changes should also have focused tests that reproduce the original defect. Hardware/network behavior still benefits from an integration run against:

- the custom ARESLib NT4 server;
- a standards-compliant WPILib NT4 server;
- the desktop simulator;
- a real Control Hub or RoboRIO under log and network backpressure.
