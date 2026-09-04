# ARES Robotics Studio

[ARES 23247 team website](https://aresfirst.org/) · [ARES GitHub organization](https://github.com/ARES-23247)

ARES Robotics Studio is the local-first desktop environment for designing, simulating, operating,
teaching, tuning, and analyzing FTC and FRC robots. Students can create a verified robot project,
configure its drivebase and mechanisms through guided builders, author controls and autonomous
routines, run the real robot program in simulation, inspect live NT4 telemetry, and review imported
logs in DuckDB. Optional Google Drive and GitHub integrations never replace the local workspace.

The product was previously named **ARES Analytics**. Existing settings, credentials, projects, and
installer upgrades retain their established internal identities; the public rename does not move or
rewrite team data. See [Branding, upgrades, and repair](docs/BRANDING_AND_UPGRADES.md).

The robot remains offline-first: robot code never sends data directly to a cloud service. The desktop app owns every cloud interaction.

The routine and controller editors are offline project tools as well: they operate on the selected
repository's `.ares` documents and never require a connected robot. See the [student routines and
controller guide](docs/ROUTINES_AND_CONTROLS.md).

Robot mechanisms use the same offline workflow. The [subsystem authoring guide](docs/SUBSYSTEM_BUILDER.md)
covers the visual builder, readable DSL, capability templates, generated ownership boundaries,
safety expectations, and the equivalent hand-authored IO/Redux workflow.

## Repository layout

| Module | Responsibility |
| --- | --- |
| `app` | Compose Desktop UI, NT4 client, DuckDB persistence, log import, replay, analytics, simulation controls, and Google Drive synchronization |
| `shared` | Serializable models and unit-conversion helpers shared by the desktop app and gateway |
| `gateway` | Small Ktor service exposing authenticated Vertex AI pit forensics and the narrowly scoped Google OAuth token broker |

The application consumes the versioned `org.aresfirst.ares:ares-bom` plus `core` and `codegen` artifacts. Normal builds resolve the pinned release from the ARES GitHub Maven repository, with Maven Central retained as an optional secondary channel; library developers can opt into the sibling checkout with `-ParesUseSiblingLib=true`.

## Requirements

- JDK 17
- PowerShell on Windows, or a POSIX shell on macOS/Linux
- A sibling `ARESLib-Kotlin` checkout for normal workspace development
- Optional tools by workflow:
  - Android SDK platform tools (`adb`) for FTC log pulling and deployment
  - SSH/SCP for RoboRIO log pulling
  - CTRE `owlet` for `.hoot` conversion
  - Google application-default credentials for running the cloud gateway

## Quick start

Normal builds resolve the pinned ARESLib version from the ARES GitHub Maven repository. To test an unpublished library change through its exact binaries:

```powershell
$candidate = "8.0.0-rc.<areslib-commit>"
cd ARESLib-Kotlin
.\gradlew.bat apiCheck publishReleaseValidation "-ParesVersion=$candidate"
cd ..\ARES-Analytics
$repository = ([Uri](Resolve-Path ..\ARESLib-Kotlin\build\release-repository)).AbsoluteUri
.\gradlew.bat :shared:test :app:test "-ParesVersion=$candidate" "-ParesRepository=$repository"
```

Then build and run the desktop application:

```powershell
cd ..\ARES-Analytics
.\gradlew.bat :app:run
```

To run the desktop application and local gateway together:

```powershell
.\gradlew.bat run
```

The root `run` task writes subprocess output under `build/run-logs/`. The gateway listens on port `8080` unless `PORT` overrides it.

## Verification

Run all module tests:

```powershell
.\gradlew.bat :shared:test :gateway:test :app:test
```

Useful narrower checks:

```powershell
.\gradlew.bat :app:test --tests com.ares.analytics.service.Nt4ClientServiceTest
.\gradlew.bat :app:test --tests com.ares.analytics.service.ReplayEngineServiceTest
.\gradlew.bat :app:test --tests com.ares.analytics.service.log.WpiLogDecoderTest
.\gradlew.bat :app:test --tests com.ares.analytics.service.ParquetExporterServiceTest
```

Run the automated dashboard smoke or 30-minute-equivalent soak profile:

```powershell
.\gradlew.bat :app:dashboardSmoke
.\gradlew.bat :app:dashboardSoak
```

Both tasks enforce performance budgets and write JSON plus Markdown reports under `app/build/reports/dashboard-validation/`. See [Automated dashboard validation](docs/VALIDATION.md) for workload settings, budget overrides, CI behavior, and hardware-test boundaries.

Dashboard extensions are registered through a single typed definition that declares their picker
metadata, profile defaults, configuration, service needs, and renderer. See
[Adding a dashboard widget](docs/DASHBOARD_WIDGET_EXTENSIONS.md) before changing dashboard composition.

## Runtime data flow

```text
robot or simulator
    |  NT4 WebSocket :5810
    v
Nt4ClientService
    |-- current values and bounded live history -> Compose UI
    |-- ordered frame batches -> DuckDB
    `-- dashboard input publications -> simulator/robot input topics

robot log files
    |-- LogManagerServer HTTP :5002, ADB, SCP, or local files
    v
log decoder -> FrameBatcher -> DuckDB -> summaries / SysId / replay

desktop app -> Google Drive or authenticated gateway
```

For Google sign-in, the desktop uses Authorization Code + PKCE and a loopback callback. Official
installers contain the public OAuth client ID and an HTTPS broker URL, never a client secret. The
broker adds its protected secret only during code/refresh exchange and does not persist tokens or
handle Drive data; the desktop stores tokens and calls Drive directly within the selected workspace
destination. See [OAuth and Drive architecture](docs/GOOGLE_DRIVE_ARCHITECTURE.md).

Important invariants:

- Topic names are stored without leading `/`; the wire client accepts either form.
- Internal angles are radians and counter-clockwise positive.
- Live telemetry uses the reserved session ID `live-telemetry`; recordings use persistent session IDs.
- A replay seek must restore the last value at or before the seek time, not only values inside the visible window.
- Replay widgets consume one immutable historical snapshot and never mix it into the live NT4 store.
- Numeric and string telemetry are both first-class data. Do not coerce strings to numeric zero.
- Log import is streaming and bounded. A malformed or truncated input must fail visibly rather than silently produce a partial “successful” session.

## Connecting to a target

Common NT4 targets are:

| Target | Default address |
| --- | --- |
| FTC Control Hub | `192.168.43.1:5810` |
| FRC RoboRIO | `10.TE.AM.2:5810` |
| Desktop simulator | `127.0.0.1:5810` |

`Nt4ClientService` constructs a unique `/nt/ARES-Analytics-{timestamp}` client path; normally enter only the host in the UI. Switching targets clears live state and pending frames so values from two robots cannot be mixed.

## Log formats

The importer supports ARES JSONL/CSV, WPILib `.wpilog`, CTRE `.hoot`, DS logs/events, REV logs, Road Runner logs, RLOG, and Parquet. Format-specific decoders live under `app/src/main/kotlin/com/ares/analytics/service/log/`.

Parquet export is performed through the narrow `DatabaseService.exportSessionToParquet` API. General raw-SQL execution is deliberately read-only and must not be repurposed for export.

## Gateway configuration

The gateway exposes:

- `GET /health`
- authenticated pit-forensics routes under the diagnostics router
- `POST /api/oauth/google/token` and `POST /api/oauth/google/refresh` for the managed desktop token exchange

Relevant environment variables:

| Variable | Meaning | Default |
| --- | --- | --- |
| `PORT` | HTTP listen port | `8080` |
| `GOOGLE_OIDC_CLIENT_ID` | accepted Google ID-token audience | configured production audience |
| `GOOGLE_CLOUD_PROJECT` | Vertex AI project | `ares-analytics` |
| `GOOGLE_CLOUD_LOCATION` | Vertex AI region | `us-central1` |
| `CORS_ALLOWED_HOSTS` | comma-separated HTTPS browser origins | none |
| `ARES_GOOGLE_OAUTH_CLIENT_ID` | Desktop OAuth application identity used by the broker | none |
| `ARES_GOOGLE_OAUTH_CLIENT_SECRET` | matching secret, injected from protected secret storage | none |

The Compose client is not subject to browser CORS. Browser access must be explicitly allowlisted.
Requests are limited to 1 MiB; forensics requests are rate-limited per authenticated subject and
OAuth exchanges use a separate limit. Never enable request-body logging for OAuth routes or expose
the secret to a desktop build.

## Documentation

- [Documentation index](docs/INDEX.md) - novice-first map of live robot, simulator, replay, cloud, and task guides
- [First launch](docs/start/FIRST_LAUNCH.md) - create a local robot workspace and optionally verify JDK 17 or 21 for build/simulation
- [Project Backup](docs/start/PROJECT_BACKUP.md) - save reviewed local versions and optionally connect an approved personal or team GitHub repository without installing Git
- [App tour](docs/start/APP_TOUR.md) - find screens, targets, status language, and contextual help
- [Connect the simulator](docs/start/CONNECT_SIMULATOR.md) - launch Local Sim, confirm live telemetry, and recover safely
- [Bring in a run](docs/operate/BRING_IN_A_RUN.md) - collect, verify, quarantine, and replay completed logs
- [Deterministic replay](docs/DETERMINISTIC_REPLAY.md) - clock, ordering, atomic dashboard snapshots, missing-data semantics, and recovery
- [Run comparison and guided diagnosis](docs/RUN_COMPARISON_AND_GUIDED_DIAGNOSIS.md) - shared event alignment, unit-safe overlays, exact replay evidence, and mentor/student export
- [Glossary](docs/learn/GLOSSARY.md) - student definitions with precise ARES meanings
- [Accessibility and contrast](docs/learn/ACCESSIBILITY_AND_CONTRAST.md) - readable palettes, status cues, text scaling, and touch targets
- [Teaching with ARES](docs/mentor/TEACHING_WITH_ARES.md) - mentor-led simulator-first lesson and physical robot safety gate
- [Automated dashboard validation](docs/VALIDATION.md) - smoke/soak profiles, performance budgets, reports, and CI
- [Desktop UI regression capture](docs/UI_REGRESSION.md) - exact-window captures for 1080p, default, and narrow-card layouts
- [Student routines and controller bindings](docs/ROUTINES_AND_CONTROLS.md) - offline authoring, visual controls, generation, selection, and troubleshooting
- [Subsystem Builder](docs/SUBSYSTEM_BUILDER.md) - visual mechanism authoring, generated ownership, safety, and manual Redux/IO equivalent
- [Architecture](ARCHITECTURE.md) — modules, service lifecycles, persistence, replay, and extension points
- [Telemetry contract](docs/TELEMETRY_CONTRACT.md) — canonical topics, types, coordinate conventions, and NT4 behavior
- [Operations guide](docs/OPERATIONS.md) — setup, connections, import/replay workflows, and troubleshooting

## Where to start in the code

- `app/src/main/kotlin/com/ares/analytics/di/ServiceRegistry.kt` — service ownership and shutdown order
- `app/src/main/kotlin/com/ares/analytics/service/Nt4ClientService.kt` — live telemetry boundary
- `app/src/main/kotlin/com/ares/analytics/service/DatabaseService.kt` — database facade
- `app/src/main/kotlin/com/ares/analytics/service/log/LogParserService.kt` — import routing
- `app/src/main/kotlin/com/ares/analytics/service/ReplayEngineService.kt` — replay state reconstruction
- `gateway/src/main/kotlin/com/ares/analytics/gateway/Application.kt` — gateway security and routing

## Contribution rules

1. Keep composables declarative; side effects belong in view models or services.
2. Give every long-lived service one owned coroutine scope and an explicit shutdown method.
3. Hold the database mutex only around the JDBC operation itself.
4. Bound untrusted payload lengths before allocating memory.
5. Use parameterized SQL where DuckDB permits it; otherwise isolate and escape the smallest possible internal API.
6. Add a regression test for protocol, parser, replay, or mathematical changes.
7. When changing an NT4 topic, update the producer, consumer, and `docs/TELEMETRY_CONTRACT.md` together.

## License

ARES-authored Analytics code is available under [GNU AGPL version 3 or later](LICENSE). Separate commercial terms may be available by written agreement; see [COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md) and [LICENSE_POLICY.md](LICENSE_POLICY.md). Third-party components retain their respective licenses, and the software license does not grant rights to ARES names or logos.
