# ARES Robotics Studio operations guide

This guide covers local development, pit use, target connections, log handling, replay, and recovery. It assumes the four ARES repositories are sibling directories.

Task-focused guides:

- [First launch](start/FIRST_LAUNCH.md) — create and verify a robot workspace.
- [Connect the simulator](start/CONNECT_SIMULATOR.md) — novice-safe live telemetry and recovery.
- [Bring in a run](operate/BRING_IN_A_RUN.md) — completed-log collection, import evidence, and replay.
- [Google Drive setup](start/GOOGLE_DRIVE_SETUP.md) — optional one-click sign-in, destination selection, and recovery.
- [OAuth and Drive architecture](GOOGLE_DRIVE_ARCHITECTURE.md) — multi-team isolation and administrator policy.

## 1. Build environment

Use JDK 17 for the Analytics Gradle build.

```powershell
java -version
.\gradlew.bat --version
```

Normal builds consume the pinned ARESLib release from the ARES GitHub Maven repository, with Maven Central as an optional secondary channel. To validate an unpublished library change:

```powershell
$candidate = "8.0.0-rc.<areslib-commit>"
cd ..\ARESLib-Kotlin
.\gradlew.bat apiCheck publishReleaseValidation "-ParesVersion=$candidate"
cd ..\ARES-Analytics
$repository = ([Uri](Resolve-Path ..\ARESLib-Kotlin\build\release-repository)).AbsoluteUri
.\gradlew.bat :shared:test :app:test "-ParesVersion=$candidate" "-ParesRepository=$repository"
```

The sibling directory is not substituted automatically. Use `-ParesUseSiblingLib=true` only for focused library development; binary validation should use the isolated repository above.

For a local GUI refinement cycle that also creates or builds robot projects, use the guarded launcher:

```powershell
.\scripts\run-local-ares.ps1
# Before a larger checkpoint:
.\scripts\run-local-ares.ps1 -FullValidation
# Keep exploratory UI state out of the normal user profile:
.\scripts\run-local-ares.ps1 -IsolatedDesktopHome build/weekend-readiness-home
```

It assigns a unique local candidate version, publishes ARESLib to the isolated validation repository,
and forwards that exact version and repository to every child Gradle build started by Robotics Studio.
This prevents the desktop UI from accepting a newer document schema while a generated FTC or FRC
project silently resolves an older artifact with the same version. A direct `:app:run`
with `-ParesUseSiblingLib=true` now fails early unless an explicit validation repository is also supplied.

## 2. Run modes

Desktop application only:

```powershell
.\gradlew.bat :app:run
```

Gateway only:

```powershell
.\gradlew.bat :gateway:run
```

Desktop and gateway together:

```powershell
.\gradlew.bat run
```

The combined task records child-process output in `build/run-logs/app.log` and `build/run-logs/gateway.log`.

To prevent the Gradle task from stopping an earlier Analytics JVM during investigation, add `-PskipKill=true`.

### Student-facing project verification

Robot Studio and the execution toolbar **Verify & build** action are compile-only. They run
deterministic project generation first, then generated-project verification, project tests,
simulator tests where available, and normal packaging. They do not call ADB, install an FTC APK,
deploy FRC code, start a simulator, or command hardware.

The result is retained only as evidence for the matching project path and league. **Passed** means that invocation exited successfully; rebuild after changing project files. **Failed** and **Canceled** remain visible with recovery text.

**Deploy to robot** is a separate supervised workflow. It displays the selected repository and physical target, repeats generation/verification/tests/package creation, and requires an explicit second confirmation. For FTC, all ADB operations are target-scoped to `192.168.43.1:5555`, the device identity is checked before install, and the Robot Controller package path is checked afterward. For FRC, the normal Gradle deploy task is invoked only after verification. A successful command is not a claim that wiring, mechanisms, radio conditions, or emergency-stop readiness were physically tested.

## 3. Pre-pit checklist

- [ ] Launch the application once before leaving an internet connection so Gradle/native dependencies are cached.
- [ ] Confirm the selected team, league, season, and robot workspace.
- [ ] Confirm the laptop can reach the target NT4 port `5810`.
- [ ] Confirm the laptop can reach the robot log server on `5002` if using HTTP pull.
- [ ] For FTC, verify `adb devices` sees the Control Hub if using ADB import/deploy.
- [ ] For FRC, verify SSH/SCP access if using RoboRIO file pull.
- [ ] Finish a short robot/simulator log, confirm its automatic import, and replay it before the match.
- [ ] Verify battery, loop-time, pose, and key mechanism topics are updating.
- [ ] Verify the target alliance and field-centric settings before enabling simulator control.

## 4. Connection diagnostics

### Simulator

Expected target: `127.0.0.1:5810`.

If the UI connects but pose or controls do not move:

1. Confirm the simulator and Analytics did not each start a conflicting server on port `5810`.
2. Check that `ARES/EstimatedPose` and `Drive/Pose_X` appear in active topics.
3. Verify dashboard inputs appear under `ARES/Input/*`.
4. Check that all simulator inputs are read from the custom ARESLib NT4 server.
5. If alliance changes do not take effect, inspect the atomic v2 `ARES/Input/driveFrame` array. Its
   flags field is element 7; red alliance is bit 5 (`1 << 5`, value `32`). Alliance is not sent on
   a separate scalar topic.

### Runtime control soak

The synthetic `dashboardSoak` test is useful for UI/service profiling, but it does not prove the
real NT4 control path. The opt-in `simulatorControlSoak` task connects the actual Analytics client to
a separately running FTC simulator, publishes the leased v2 drive frame at 50 Hz, and checks input
scheduling gaps, atomic pose-frame cadence, motion authority, and EKF error.

Terminal 1:

```powershell
cd ..\ARES-FTC
$env:ARES_LOG_RETENTION_ENABLED = "false" # preserve an existing development archive during the test
.\gradlew.bat :TeamCode:runSim "-ParesUseSiblingLib=true"
```

Terminal 2:

```powershell
cd ..\ARES-Analytics
.\gradlew.bat :app:simulatorControlSoak "-PsimSoak.seconds=3600" "-ParesUseSiblingLib=true"
```

Use the sibling flag only while changing ARESLib source. Release validation must repeat against an
isolated published candidate repository. The task fails if the simulator is absent; the ordinary
unit suite skips this external dependency. Its JFR is written to
`app/build/reports/simulator-control-soak/analytics-control-soak.jfr`. Clear the retention
environment variable after the run. A one-hour pass requires zero control publications lost, no
control scheduling gap over 100 ms, no pose stall over one second, at most one pose gap over 250 ms
per hour, bounded EKF error, and two distinct packed poses reconstructed at the 25% and 75% rewind
points.

### FTC Control Hub

Common address: `192.168.43.1:5810` while connected to the Control Hub network.

```powershell
Test-NetConnection 192.168.43.1 -Port 5810
adb devices
```

If ADB is missing, configure `ANDROID_HOME` or `ANDROID_SDK_ROOT`, or put platform-tools on `PATH`.

### FRC RoboRIO

The mDNS or team address depends on team configuration. The conventional team address is `10.TE.AM.2`.

```powershell
Test-NetConnection 10.TE.AM.2 -Port 5810
ssh lvuser@10.TE.AM.2 true
```

Automatic SCP import requires the RoboRIO host key in the user's normal `known_hosts` file. Verify
the fingerprint on the first interactive SSH connection; Analytics deliberately refuses unknown or
changed host keys instead of bypassing SSH identity checks.

Do not diagnose a disconnected robot from stale dashboard values. Target changes clear topic metadata, latest values, live history, and pending database frames by design.

## 5. Live data and persistent runs

Live telemetry is stored under the reserved session ID `live-telemetry` in the in-memory database. It supports the live dashboard and live rewind, but it is not automatically a durable practice/match run.

Live rewind has an explicit commit boundary. Loading `live-telemetry` first drains the lossless NT4
persistence queue, then reads DuckDB. The durable live timeline uses monotonic laptop receipt time;
robot timestamps remain available to the live UI but cannot introduce a pre-connection gap when a
simulator or robot supplies an old retained value. While replay is active, packed live pose frames
continue to be recorded but cannot overwrite the replayed field pose. Leaving replay resets the
pose-frame accumulator before live packed frames regain display ownership. The live buffer retains
the most recent five minutes.

The current UI does not expose a general start/stop recording control. A persistent run is created when Analytics imports a completed robot or simulator log. The producing logger owns the start/stop boundary; Analytics waits for the file to stop changing, archives it, imports it into DuckDB, and writes an import report. Follow [Bring in a run](operate/BRING_IN_A_RUN.md) for the student workflow.

If live charts update but no run appears:

1. Treat that as expected until the robot/simulator logger has closed a file.
2. Stop or finish the OpMode/routine cleanly so the source log is no longer being written.
3. Verify the selected Analytics workspace matches the producer's team, season, robot, league, and project path.
4. Check **Data → Log Imports** for a successful or quarantined report.
5. Verify the target timestamps increase and inspect application/import errors if no stable file is discovered.
6. Preserve the source until the imported session appears in **Recorded Sessions** and replays successfully.

## 6. Log collection

For a task-level walkthrough and success criteria, see [Bring in a run](operate/BRING_IN_A_RUN.md).

### HTTP pull

ARESLib's `LogManagerServer` listens on port `5002` and exposes:

```text
GET  /api/logs
GET  /api/download?file={name}
POST /api/delete
```

The desktop downloads files first. Deletion is a separate explicit operation after successful local handling. Robot code never uploads to cloud storage.

### FTC ADB locations

The automatic importer checks configured locations such as:

```text
/sdcard/FIRST/telemetry_logs/
/sdcard/ctre-logs/
/sdcard/FIRST/ctre-logs/
```

### FRC locations

The automatic importer checks configured RoboRIO/USB locations such as:

```text
/home/lvuser/logs/
/media/sda1/logs/
```

Imported local files are archived under the workspace's `logs/imported/` directory. A file is identified durably; leaving it on the robot must not create duplicate sessions every scan.

## 7. Import troubleshooting

| Symptom | Likely cause | Action |
| --- | --- | --- |
| Import never completes and CPU is high | malformed parser loop or enormous declaration | capture the file; run the format-specific decoder test; verify every decoder loop consumes input |
| Import succeeds but data is missing | truncated/corrupt input was tolerated | inspect logs; decoders should now throw on structural truncation |
| CSV has `_ExtraFieldsJson` only | older importer or malformed JSON extras | use the current CSV decoder, which expands each late key |
| `.wpilog` ends early | invalid record widths/length or damaged file | run `WpiLogDecoderTest`; verify exact-length reads and record header widths |
| `.hoot` conversion times out | `owlet` unavailable or blocked | verify `owlet` is on `PATH`; inspect conversion exit status |
| `.revlog` conversion fails | converter missing or returned nonzero | inspect the reported converter failure; commands are launched as direct argv, not through a shell |
| Same remote log appears repeatedly | import identity database/config was reset | preserve the application data directory; verify source identity metadata |
| `.csv.gz` is rejected | old Analytics build or decompression exceeded the 512 MiB safety limit | update Analytics; validate the producer and do not raise the expansion limit for an untrusted file |
| Large WPILOG reaches the local memory budget | an older build materialized high-rate diagnostic frames after decoding | update Analytics; core summary aggregates remain exact while secondary diagnostics now use a deterministic bounded sample; retry the preserved Quarantine copy |
| The same file creates two runs | workspace identity or source digest evidence changed | compare team/season/robot and the SHA-256 in Log Imports; identical bytes in one workspace must reuse one session |
| A run disappears after switching robots | expected workspace isolation | switch back to the run's team, season, and robot; ARES does not list another workspace's sessions |

Untrusted log lengths are bounded before memory allocation. Do not raise limits merely to make a corrupt file import; first validate the format and expected maximum.

## 8. Replay troubleshooting

Replay is stateful. A topic last changed at `t=0` should still be present after seeking to `t=30s`.

If a seek loses values:

1. Verify the session contains the topic before the seek time.
2. Confirm `getLatestTelemetryBefore` returns one baseline per key.
3. Confirm loading a new session cleared old cache bounds and indices.
4. Confirm string telemetry is read from `string_value`.

If replay contaminates live UI:

1. Confirm every replay-driven widget receives `ReplayEngineService.currentFrame` rather than the live telemetry store.
2. Confirm selecting a historical run sets the visible source to **Replay**, even while paused or stopped.
3. Stop replay and confirm the first historical sample remains selected without being inserted into live NT4 state.
4. Leave the run and confirm live widgets resume only from newly observed live frames.

If the timeline says **No telemetry samples**, do not treat actions or annotations as sensor data.
Review the import report and source log. If a widget says its topics are missing, choose another
recorded signal or correct the robot logger for the next run; missing is not zero. See
[Deterministic replay and dashboard evidence](DETERMINISTIC_REPLAY.md).

For run comparison problems:

| Symptom | Meaning and recovery |
| --- | --- |
| A run is absent from the selector | It belongs to another team/season/robot workspace, has no durable telemetry, or has not finished importing. Select the correct workspace and inspect Log Imports; do not weaken identity filtering. |
| Autonomous, event, or annotation alignment is absent | Every selected run must contain the same marker inside its recorded timeline. Use recording start or add a shared in-run marker to future logs. |
| A metric or trajectory says evidence is missing | The selected runs lack a compatible unit/source pair or exact shared sample timestamps. Missing evidence is not zero; inspect the listed source topics and sampling limitation. |
| **Open replay at evidence** opens the wrong place | Verify that the finding's session ID and original timestamp match the replay header. Aligned time is only a viewing coordinate and must never replace the persisted timestamp. |
| Report export is not where expected | The chooser opens at the active robot project when available, but the selected path is authoritative. The report is local and is not uploaded automatically. |

## 9. DuckDB and export recovery

The default persistent database is under:

```text
~/.ares-analytics/telemetry.duckdb
```

Before manual repair, close every Analytics process and copy the database file.

If `telemetry.duckdb.wal` exists, copy it together with `telemetry.duckdb`. The WAL may contain the
latest committed imports. Never delete or move it by itself as a startup workaround. New releases
remove redundant telemetry indexes and checkpoint at completed-import and clean-shutdown boundaries;
see [Telemetry storage architecture](DATABASE_STORAGE_ARCHITECTURE.md) for the measured cold-start
cause and recovery-safe migration plan.

The DuckDB file is one local application database containing identity-scoped sessions; raw source
copies and sidecar reports live under each robot project's `logs/imported/` or `logs/quarantine/`.
Do not infer that a global database file makes sessions globally visible: student run lists and
cloud views filter by exact team, season, and robot identity.

On startup, ARES removes only sessions whose durable import state is still `IMPORTING`, together
with their owned telemetry, summaries, reports, actions, alerts, annotations, diagnostics, and
console rows. Completed sessions are not touched. A manual restore should copy the DuckDB file and
the corresponding workspace log archive together so provenance remains inspectable.

Do not run ad hoc write SQL through `executeQueryRaw`; it is intentionally read-only. Use repository methods or a dedicated migration/export API.

Parquet round-trip verification:

```powershell
.\gradlew.bat :app:test --tests com.ares.analytics.service.ParquetExporterServiceTest
```

The test covers numeric/string restoration and paths/session IDs containing apostrophes.

## 10. Gateway operations

Minimum local environment:

```powershell
$env:GOOGLE_CLOUD_PROJECT = "ares-analytics"
$env:GOOGLE_CLOUD_LOCATION = "us-central1"
$env:GOOGLE_OIDC_CLIENT_ID = "your-client-id.apps.googleusercontent.com"
$env:ARES_GOOGLE_OAUTH_CLIENT_ID = "your-desktop-client-id.apps.googleusercontent.com"
# Inject ARES_GOOGLE_OAUTH_CLIENT_SECRET from a local secret manager or protected shell.
.\gradlew.bat :gateway:run
```

`GOOGLE_OIDC_CLIENT_ID` is the accepted audience for authenticated diagnostics. The two
`ARES_GOOGLE_OAUTH_*` values configure the separate desktop token broker. Never commit the secret,
pass it as a Gradle property, include it in a command example, or enable body logging while testing.
Production Cloud Run must inject it from the protected secret manager.

Health check:

```powershell
Invoke-WebRequest http://localhost:8080/health
```

Browser CORS is disabled unless `CORS_ALLOWED_HOSTS` contains comma-separated HTTPS hosts. The desktop client does not require CORS.

The diagnostics endpoint requires a Google ID token with the configured audience. Rate limits are
per authenticated subject. Payloads larger than 1 MiB or beyond configured alert/topology limits
are rejected.

The broker accepts `POST /api/oauth/google/token` and `POST /api/oauth/google/refresh`. It is
separately rate-limited and rejects an unexpected redirect URI, malformed PKCE verifier, missing
configuration, and excessive input before calling Google. A `broker_unavailable` error means the
gateway's protected OAuth configuration is incomplete; do not ask a student for a secret. The
broker is not a Drive proxy and must never receive workspace data.

For a local desktop build that deliberately targets a broker, set the non-secret values before the
build:

```powershell
$env:ARES_GOOGLE_OAUTH_CLIENT_ID = "your-desktop-client-id.apps.googleusercontent.com"
$env:ARES_GOOGLE_OAUTH_BROKER_URL = "https://your-broker.example.org"
.\gradlew.bat :app:run
```

Team-owned public application identity is also recorded in `gradle.properties` under the
`aresPublic*` keys, so a normal source checkout can run and package ARES without copying command-line
flags. These values are public identifiers, not credentials. Explicit `-PgoogleOAuthClientId`,
`-PgoogleOAuthBrokerUrl`, `-PgithubAppClientId`, and `-PgithubAppSlug` values take precedence for an
administrator testing a replacement configuration. Protected GitHub Actions package jobs must still
provide all four `ARES_*` environment variables; the build rejects a release job that merely falls
back to the checked-in development defaults. Never add a Google client secret, GitHub App private
key, or user token to `gradle.properties`.

Clear the variables after the run. Custom clients entered in the app require the same pair: public
Desktop client ID plus an administrator-operated HTTPS broker URL. A client secret never belongs in
the app configuration.

## 11. Shutdown and recovery

Normal shutdown should:

1. stop scanners and update checks;
2. cancel and join NT4 work;
3. stop replay and close UDP resources;
4. close cloud and event clients;
5. checkpoint and close DuckDB;
6. release gamepad/native resources.

Avoid terminating with `System.exit`, because it bypasses structured cleanup and can abandon file locks or pending work.

Guided tuning records live under the active project at `.ares/local/tuning/experiments`. If a record
is corrupt, Studio reports it rather than silently skipping or replacing it. Recover the exact file
from project backup or move it aside only after preserving a copy. A stale staged candidate can be
removed with **Reject candidate** or **Roll back proposal**; neither action rewrites canonical
`.arestuning` files. Candidate runs are normal local database sessions and remain available for
replay after the proposal is removed.

If a previous Analytics JVM is still running, the root Gradle task checks Java process identity using `jps`. It does not kill arbitrary processes merely because they listen on a common robotics port.

## 12. Release checklist

- [ ] Pin a published ARESLib version, or validate the matching isolated release repository.
- [ ] Run `:shared:test :gateway:test :app:test`.
- [ ] Test a live custom ARESLib NT4 server.
- [ ] Pass the one-hour real FTC `:app:simulatorControlSoak` gate and archive its JFR/result.
- [ ] Test a standards-compliant WPILib NT4 server.
- [ ] Import at least one JSONL/CSV and one binary log.
- [ ] Import and rewind one gzip-compressed simulator CSV (`.csv.gz`).
- [ ] Rewind `live-telemetry` and confirm the packed pose sequence differs at the 25% and 75% playheads.
- [ ] Seek replay beyond a sparse topic's last update.
- [ ] Export/import a Parquet session containing string telemetry.
- [ ] Verify target switching clears old robot state.
- [ ] Verify gateway health, authentication, request limits, and rate limiting.
- [ ] Confirm the protected repository variables `ARES_GOOGLE_OAUTH_CLIENT_ID` and `ARES_GOOGLE_OAUTH_BROKER_URL` point to the active production Desktop client and stable HTTPS broker.
- [ ] Confirm `ARES_GITHUB_APP_CLIENT_ID` and `ARES_GITHUB_APP_SLUG` identify the public ARES GitHub App; Device Flow and expiring user tokens are enabled; repository Contents is read/write; and no client secret or App private key is bundled.
- [ ] Install the GitHub App on a personal account and a test organization using selected private repositories. Verify destination discovery, stable installation/repository identity, permission removal, account switching, refresh-token rotation, sign-out, and destination changes.
- [ ] Confirm the broker receives the matching `ARES_GOOGLE_OAUTH_CLIENT_ID` and `ARES_GOOGLE_OAUTH_CLIENT_SECRET` from protected Cloud Run/secret-manager configuration, and that neither secret nor token bodies appear in logs.
- [ ] Verify focused code-exchange, refresh, invalid-input, missing-configuration, error-redaction, and rate-limit tests.
- [ ] Verify one-click PKCE sign-in, destination creation/selection, a small upload/download, sign-out, refresh, and reconnect against the active production client and broker on a clean installed profile. Do not publish an installer before this passes.
- [ ] Verify a second workspace cannot list or synchronize the first workspace's root, and that removed folder permissions fail visibly.
- [ ] Exercise personal folders, created team folders, existing shared folders, and Shared Drive folders when the test account supports them; verify account switching, revoked refresh tokens, deleted clients, offline startup, and concurrent sync.
- [ ] Build the same native package used by the release workflow. Set `ARES_GOOGLE_OAUTH_CLIENT_ID`, `ARES_GOOGLE_OAUTH_BROKER_URL`, `ARES_GITHUB_APP_CLIENT_ID`, and `ARES_GITHUB_APP_SLUG` from protected repository variables in the build environment (do not paste them into command arguments), then run `.\gradlew.bat :app:packageMsi "-ParesAnalyticsVersion=1.2.3" --no-daemon` and clear the environment values afterward.
- [ ] Confirm `:app:verifyDistributableProjectLoading` passes. Native package tasks depend on this guard, which loads metadata, routines, subsystems, capabilities, and autonomous choices through the trimmed jlink runtime rather than the development JDK.

The installer is not approved for publication until the production OAuth and Drive round trip above
has actually passed. Successful automated tests, CodeQL, dashboard validation, and MSI packaging do
not substitute for that external end-to-end verification.

# Local workspace profiles and automated desktop tests

ARES Robotics Studio stores normal user state under `~/.ares-analytics`. The robot selector is an
index of local workspaces; removing an entry from Studio does not delete the referenced robot
project, imported run database, Git history, or cloud files.

Automated visible-app journeys must never use that normal directory. Launch them with an isolated
data root:

```powershell
.\scripts\run-isolated-desktop.ps1 -Fresh
```

To capture the settled window and close it through the native window lifecycle:

```powershell
.\scripts\run-isolated-desktop.ps1 -Fresh `
  -CaptureFile .\build\diagnostics\isolated-first-run.png
```

The script sets `ARES_ANALYTICS_DATA_DIR` only for the launched process. Source-based harnesses may
instead set that environment variable or the `ares.analytics.dataDir` JVM property directly. Never
point an automated acceptance journey at a student's real `~/.ares-analytics` directory.

## Opening an older telemetry database

Studio validates the current telemetry schema and rejects missing required columns. It does not
silently discard historical rows or invent missing timestamps. Before upgrading, close Studio and
back up the database together with any adjacent `.wal` file; keep that pair from the same closed
session. Preserve the original robot log files and exported session bundles independently.

If startup reports an incompatible schema, keep the original database/WAL pair untouched. Use the
previous compatible Studio release on a copy to export sessions, then launch the current
release with `ARES_ANALYTICS_DATA_DIR` set to a new, empty directory and re-import the original logs
or supported session exports. This creates a separate Studio profile, including its database. Do not delete a WAL
or rename a database while Studio is using it. When the earlier app is unavailable, preserve the
copy for an explicit migration; the current application has no general legacy-schema converter.

XRP control requires matching project ID and canonical project fingerprint. A mismatched or older
peer remains disconnected and reports that the current project must be verified and deployed.
Project identity alone is not treated as proof that the board is running the open project revision.
