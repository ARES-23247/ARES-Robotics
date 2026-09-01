# Automated Dashboard Validation

ARES Robotics Studio includes repeatable smoke and soak profiles for the complete dashboard data path. Validation combines the in-process NT4 server/client test with deterministic telemetry ingestion, indexed DuckDB queries, CSV and Parquet export, lossless Parquet restore, replay load and scrubbing, alert regression checks, memory measurement, and performance budgets.

## Local commands

Run the PR-sized smoke profile:

```powershell
.\gradlew.bat :app:dashboardSmoke
```

Run smoke plus the checked-in performance-regression baseline (the CI gate):

```powershell
.\gradlew.bat :app:dashboardPerformanceBaseline
```

Run the 30-minute-equivalent soak profile:

```powershell
.\gradlew.bat :app:dashboardSoak
```

Validate a physical robot or separately running simulator:

```powershell
.\gradlew.bat :app:dashboardHardware `
  "-Pvalidation.hardwareHost=192.168.43.1" `
  "-Pvalidation.hardwarePort=5810" `
  "-Pvalidation.hardwareRequiredKeys=Robot/BatteryVoltage,Drive/Pose_X"
```

On macOS or Linux, replace `.\gradlew.bat` with `./gradlew`.

Neither profile waits for the simulated session duration in wall-clock time. Smoke generates 10 seconds at 100 Hz. Soak generates the sample volume of a 30-minute session at 20 Hz, then exercises queries, round-trip persistence, and replay against that dataset.

Reports are written to:

```text
app/build/reports/dashboard-validation/dashboard-validation-<profile>.md
app/build/reports/dashboard-validation/dashboard-validation-<profile>.json
```

The JSON report is suitable for trend ingestion. The Markdown report summarizes configuration, measured results, and budget violations.
CI retains both formats for 90 days and publishes the Markdown report in the GitHub Actions job summary. The baseline gate reads `config/dashboard-performance-baseline.json`; update it only after reviewing an intentional performance change on comparable hardware.

## What is validated

| Area | Automated check |
|---|---|
| Live transport | In-process NT4 server and `Nt4ClientService` connection, topic flow, pose state, and motor telemetry |
| Persistence | Batched multi-topic ingestion, exact frame counts, microsecond ordering, and zero dropped samples |
| Analytics queries | Exact-key, key-pattern, distinct-key, and bounded-range DuckDB queries |
| Export | CSV table generation and full-session Parquet generation |
| Restore | Parquet import restores the exact frame count |
| Replay | Atomic current-frame creation, stable source ordering, bounded baseline restoration, rapid-seek cancellation, and end-to-end scrub latency |
| Run comparison | Workspace isolation, shared-anchor discovery, exact-timestamp/unit-safe overlays, bounded uniform sampling, deterministic findings/reports, and exact replay evidence targets |
| Guided tuning | Snapshot/hash stability, one-factor bounds, typed units, new same-workspace simulation candidates, threshold-aware outcomes, acknowledgement/rejection, rollback, and deterministic reports |
| Alerts | Threshold and composite alert regression suites in the smoke task |
| Resources | Ingestion throughput, p95 query latency, replay timing, Parquet timing, and heap growth |

Headless CI does not replace Compose verification. Declarative behavior is covered through the same
database and telemetry services used by the UI; the release workflow also drives a real visible
Compose window to select a recording, play, pause, step, seek, change speed/loop state, navigate
away and back, and capture source/missing-data labels. Physical Wi-Fi remains an optional pit check.

For a real Compose import journey, launch with the existing loopback desktop-test control and an
explicit, non-sensitive fixture selection:

```powershell
$env:ARES_ANALYTICS_TEST_CONTROL_PORT = "49321"
$env:ARES_ANALYTICS_TEST_CAPTURE_DIR = "$PWD/build/diagnostics/import-e2e"
$env:ARES_ANALYTICS_TEST_LOG_SELECTION = "$PWD/build/fixtures/golden-run.csv"
.\gradlew.bat :app:run "-ParesIsolatedDesktopHome=build/import-e2e-home"
```

`ARES_ANALYTICS_TEST_LOG_SELECTION` is ignored unless the loopback control port is also enabled;
normal developer and installed launches always open the native chooser. Drive the real window via
`CLICK`, verify with `CAPTURE`, repeat the selection to prove idempotency, open Guided Run Review,
play the exact timeline, restart, and capture the persisted run. Use a second isolated launch with
a corrupt fixture to verify Quarantine and actionable recovery text.

The repository helper sends only explicit loopback actions and Base64-encodes text and paths:

```powershell
& "$PWD/../.agents/skills/compose-desktop-tester/scripts/send_test_control.ps1" `
  -Port 49321 -Capture
```

When the journey must exercise the real native file or folder chooser instead of the test fixture
override, open the modal chooser in one PowerShell job and send `-ChoosePath` from a second
connection. The path must be absolute and already exist. This keeps the product dialog real while
avoiding unrestricted filesystem commands or brittle cross-process keystrokes.

The visible run-comparison acceptance journey uses an isolated desktop home and a fresh robot
workspace. Import the two Academy practice runs, select both, exercise recording-start,
autonomous-start, and shared-event alignment, inspect trajectory/telemetry and missing-evidence
labels, open a finding at its exact original replay timestamp, export a report into the project,
navigate away/back, resize to 1440x900 and 1100x700, restart, and verify persistence. Capture the
exact Compose window at each boundary and close it gracefully. This is desktop/synthetic-log
evidence; it is not a physical-robot validation claim.

Focused deterministic and performance gates are:

```powershell
.\gradlew.bat :app:test --tests "com.ares.analytics.service.RunComparisonServiceTest" `
  --tests "com.ares.analytics.service.RunComparisonPerformanceTest" `
  --tests "com.ares.analytics.viewmodel.runanalysis.GuidedRunAnalysisViewModelTest" `
  --tests "com.ares.analytics.ui.input.DesktopDriveInputPublisherTest"
```

The visible guided-tuning journey continues from the paired Academy runs: open a finding, create a
controlled question/prediction, verify held constants, threshold, safety notes, and mentor-review
state, snapshot one typed proposal, apply it only to loopback Local Sim, record the repeated run,
compare it, and record a decision plus next test. Reload the saved experiment after a graceful
restart, export the mentor/student report, and verify the form at 1440×900 and 1100×700. A runtime
acknowledgement proves only that the simulator consumer mapped the typed value; it is not physical
hardware evidence.

## Performance budgets

Defaults are deliberately stable across developer machines and GitHub runners:

| Budget | Smoke default | Soak default |
|---|---:|---:|
| Minimum ingestion | 1,000 frames/s | 1,000 frames/s |
| Query p95 | 1,000 ms | 2,000 ms |
| Replay load | 5,000 ms | 15,000 ms |
| Replay scrub p95 | 2,000 ms | 2,000 ms |
| Parquet import/export | 15,000 ms | 30,000 ms |
| Heap growth | 256 MiB | 512 MiB |
| Drop rate | 0 | 0 |

Override workload or budget values with Gradle properties. For example:

```powershell
.\gradlew.bat :app:dashboardSoak `
  "-Pvalidation.simulatedSeconds=3600" `
  "-Pvalidation.sampleRateHz=50" `
  "-Pvalidation.maxQueryP95Ms=2500"
```

Supported properties are:

- `validation.simulatedSeconds`
- `validation.sampleRateHz`
- `validation.topicCount`
- `validation.batchSize`
- `validation.queryIterations`
- `validation.minIngestionFramesPerSecond`
- `validation.maxQueryP95Ms`
- `validation.maxReplayLoadMs`
- `validation.maxReplayScrubP95Ms`
- `validation.maxParquetOperationMs`
- `validation.maxHeapGrowthMb`
- `validation.maxDropRate`
- `validation.hardwareHost`
- `validation.hardwarePort`
- `validation.hardwareObservationSeconds`
- `validation.hardwareConnectTimeoutSeconds`
- `validation.hardwareMinFrames`
- `validation.hardwareMinTopics`
- `validation.hardwareRequiredKeys`

## GitHub Actions

The root `.github/workflows/analytics-validation.yml` runs:

- `dashboardPerformanceBaseline` (which runs `dashboardSmoke` first) for relevant pull requests and pushes to `main`.
- `dashboardSoak` nightly and when manually selected through `workflow_dispatch`.
- Report and JUnit artifact upload even when a budget fails.

The workflow uses the ARESLib sources imported in the authoritative monorepo. Published-binary
validation still requires one explicit candidate version and isolated Maven repository; sibling
source substitution is opt-in and is not release evidence.

`.github/workflows/build-distributions.yml` first publishes one unique ARES release candidate to an
isolated repository. Before any installer build, `studioReleaseVerification` runs all `app`,
`shared`, and `gateway` tests; Kover line-coverage floors (38%, 52%, and 52%); the 500-line production
source ratchet; release-version alignment; and dashboard performance budgets against that exact
candidate. The workflow also configures representative products with an isolated Gradle user home
and rejects any build that writes user-global `gradle.properties`.

The workflow separately gates every installer on official-template acceptance. It creates fresh FTC
and FRC projects from the same hash-pinned archives used by onboarding, personalizes their canonical
ARES identities, and then generates, verifies, tests, and packages both projects through their
normal immutable dependency repositories. The FTC project also runs the headless drivetrain
verifier, which must demonstrate translation, field-centric control, and rotation before an
installer can be produced. This is simulator evidence, not physical-hardware validation.

On Windows, the package job then selects the newest earlier stable GitHub release, installs its MSI
on the clean runner, upgrades it with the candidate MSI, verifies that no side-by-side older product
remains, runs an explicit repair transaction, and uninstalls the test product. The job rejects a
version that already has a GitHub Release so different package bytes cannot reuse one public version.

Fresh-project acceptance and native Windows/macOS packaging run concurrently to shorten release
latency. The publication job still depends on both lanes, so no package can be released unless the
zero-code consumer matrix and every native package check have completed successfully.

## Optional physical hardware check

The hosted pipeline cannot reproduce radio congestion, Control Hub storage pressure, RoboRIO CPU contention, or field-network policies. Run `dashboardHardware` manually from the driver-station laptop while it is connected to the robot network. The task observes live NT4 traffic for 30 seconds by default, checks frame/topic minimums and required keys, persists the received data, and writes its report locally. No self-hosted GitHub runner is required.
