# ARES Cross-Product Fresh Audit & Remediation Report (2026-11-20)

November 20, 2026 · ARESLib `20.0.7` (isolated candidate `20.0.7-rc.audit`) · Studio `7.0.81` · Kotlin `2.4.21` · Windows, JDK 21

## Audit Scope & Baseline

- **Baseline Commit:** Clean `origin/main` at `34b7b605c` (post-PRs `#130` and `#133`), audited and remediated in isolated worktree `agy/fresh-audit-20261120` without touching unrelated working trees or running processes.
- **Products Covered:** `ARESLib-Kotlin` (14 JVM/Android modules + `ares-micro` MicroPython runtime), `ARES-FTC` (Lightbot & BioBuzz), `ARES-FTC-Starter`, `ARES-FRC`, `ARES-FRC-Starter`, `ARES-XRP-Starter`, `ARES-Analytics` (`app`, `shared`, `gateway`, `mcp` Wave 1), build logic, scripts, and `.github/workflows/*`.
- **Separated Feature PR Tracks:** Wave 2 MCP (`PR #122`, `agy/ares-robot-code-mcp-wave2`) and Physical XRP Driver Station (`PR #123`, `agy/xrp-physical-driver-station`) were audited in parallel and remediated on their respective rebased PR branches to preserve clean review separation.
- **Validation Limits (Explicit Disclosure):** All checks in this campaign were executed against JVM unit/integration suites, simulated physics/mock IO, Python unit suites, static policy verifiers, and two isolated Windows Compose Desktop launch/capture/shutdown cycles. **Not physically validated on physical FTC Control Hub, FRC roboRIO, or physical SparkFun XRP hardware.** Live cloud OAuth/Gemini/Vertex endpoints and protected release promotion/signing were not executed.

---

## Release Version Alignment & Consolidated Dependabot Cohorts

Because `ARESLib-Kotlin` source changed, a new source-tree identity and immutable release pins were assigned across the monorepo (matching all 5 rebuilt starter/example archives):

| Artifact | Previous Pin (`origin/main`) | New Pin (`agy/fresh-audit-20261120`) |
|---|---|---|
| `aresVersion` | `20.0.6` | `20.0.7` |
| `studioVersion` | `7.0.80` | `7.0.81` |
| `ftcStarterVersion` | `20.0.6` | `20.0.7` |
| `frcStarterVersion` | `20.0.6` | `20.0.7` |
| `xrpStarterVersion` | `3.0.70` | `3.0.71` |
| `lightbotExampleVersion` | `3.0.75` | `3.0.76` |
| `biobuzzExampleVersion` | `1.1.13` | `1.1.14` |

Compatible open Dependabot cohorts were consolidated and verified together (superseding fragmented open Dependabot PRs `#124`–`#129`, `#131`–`#132`):

- `org.jetbrains.compose`: bumped to `1.12.2`
- `org.jetbrains.kotlinx.kover` / `kover-gradle-plugin`: aligned across all 5 Gradle products at `0.9.11`
- `org.jetbrains.kotlinx:kotlinx-serialization-json`: aligned across all 5 declarations at `1.13.0`
- `io.ktor:*` + `io.ktor.plugin`: aligned across all 29 declarations at `3.6.1`
- `.github/dependabot.yml`: added explicit `ktor` and `kover` dependency groups so future Dependabot PRs never split Ktor/Kover cohorts.

---

## Remediated Findings Matrix (P0 & P1)

### 1. `ARESLib-Kotlin` (Core, Codegen, Project Model, Hardware, Simulator, `ares-micro`)

| Severity | Area | Summary of Defect & Fix |
|---|---|---|
| **P0** | `ftc-hardware` (`CachedHardware.kt`) | `CachedDcMotorEx` delegated `setVelocity`/`setPowerFloat`/`setMotorDisable` without invalidating `hasPowerCommand`, silently dropping subsequent `setPower(0.0)` stops. Overrode all velocity/float/disable/enable methods to invalidate cached power commands and mark write uncertainty on failure. |
| **P1** | `core` (`VisionMeasurementController.kt`) | MegaTag2 translation-only vision path bypassed 2-DOF solver through `Store` (omitted `observeHeading = false` and finite `stdDevHeading`), perturbing heading via $P_{x\theta}, P_{y\theta}$. Wired `observeHeading = !translationOnly` and `Double.POSITIVE_INFINITY` heading std-dev. |
| **P1** | `core` (`VisionAlignController.kt`) | Selected the *oldest* snapshot inside the 250 ms window instead of the newest; switched to reverse traversal from newest snapshot. |
| **P1** | `core` (`SuperstructureRuntime.kt`) | Executed exit/entry lifecycle actions while disabled and cleared `isDisabled` on fault under `RETAIN_LOGICAL_STATE_WITH_NEUTRAL_OUTPUT`. Hardened disabled guard to suppress lifecycle ticks and target mutations while disabled. |
| **P1** | `core` (`FollowPathTask.kt`, `PathfindToPoseTask.kt`, `PathPlannerAutoParser.kt`, `Costmap.kt`, `ForesightLookaheadEstimator.kt`) | Fixed FRC corner-origin alliance mirroring, replaced hardcoded `16.0 x 8.0 m` costmap bounds with `resolvedWidthMeters`/`resolvedHeightMeters` + `NaN` guards, prevented premature `predictivePathSkip` on U-turns, guarded `chordScale` for large spins, and reset foresight when disabled. |
| **P1** | `core` (`OdometryFusionController.kt`, `NT4Server.kt`, `TrajectoryPlanning.kt`, `DriveReducer.kt`) | Hardened non-finite kinematics/pose updates, NT4 disconnect/publish bounds, trajectory parameter checks, and reducer safety invariants. |
| **P1** | `codegen` (`SubsystemControllerRenderer.kt`, `ControlCodegenValidator.kt`, `AresKotlinProjectGenerator.kt`, `SubsystemFtcIoRenderer.kt`, `SubsystemIoRenderingSupport.kt`, `SubsystemMockIoRenderer.kt`) | Fixed `REVERSE_BRIEFLY` jam-recovery retry counter resetting immediately on safe neutral cut; validated analog binding `[outputMin, outputMax]` against target bounds and `INT` targets; hardened generated IO/mock null/close safety. |
| **P1** | `project-model` (`RobotProjectModel.kt`, `RobotProjectTuningValidation.kt`) | Validated conflicting XRP hardware port families, tuning parameter declarations/ranges, and project descriptor integrity. |
| **P1** | `ftc-hardware` (`FtcBaseRobot.kt`, `FtcLimelightIO.kt`, `FtcRevHubIO.kt`, `RevI2CSensorManager.kt`, `SrsHubIO.kt`, `SrsHubAdapters.kt`, `FtcIndicatorLightIO.kt`, `FtcPrismDriverI2cIO.kt`, `FtcTeleOpDSL.kt`) | Executed registered/generated subsystems inside `FtcBaseRobot.update()` before telemetry flush and 20 ms sleep; guarded `FtcLimelightIO.setOrientation()` against close/exceptions; wired `refresh()` on analog encoders; fixed `SrsHubDriver` byte-24 overlap, VL53L5CX ports, and I2C writes outside lock; prevented `NaN` servo writes & post-close writes; preserved Blue alliance in `FtcTeleOpBase.start()`. |
| **P1** | `frc-hardware` (`FrcBaseRobot.kt`, `FrcLimelightIO.kt`, `FRCTelemetry.kt`, `FrcTelemetryManager.kt`) | Isolated per-resource `close()` with terminal `closed` flags; fixed `dtSeconds` zero/rewind handling and unswallowed `safeHardware()` errors; stopped mutating/unrotated swerve telemetry states and direct CAN reads in telemetry publish. |
| **P1** | `simulator` & `ftc-mocks` (`DesktopSimLauncher.kt`, `MecanumRobotDouble.kt`, `GoBildaPinpointDriver.kt`) | Passed field-frame velocities to Pinpoint/OTOS mocks rotated to sensor reset frames; exposed non-throwing physics encoder accessors so motor fault injection cannot crash simulator ticks. |
| **P1** | `ares-micro` (`project_runtime.py`, `drive.py`, `telemetry.py`, `hardware.py`, `subsystems.py`) | Aligned XRP autonomous start-pose handling between physical and simulator runtimes, validated port conflicts and differential inversion flags, and hardened telemetry/control safety guards (136/136 tests passing). |

### 2. Robot Consumer Products (`ARES-FTC`, `ARES-FTC-Starter`, `ARES-FRC`, `ARES-FRC-Starter`, `ARES-XRP-Starter`)

- Removed duplicate post-`base.update()` subsystem ticks in `ARES-FTC` and `ARES-FTC-Starter` `AresRobot.kt` now owned by `FtcBaseRobot.update()`.
- Added `coreLibraryDesugaringEnabled true` and `com.android.tools:desugar_jdk_libs_nio:2.1.5` to `ARES-FTC/biobuzz/TeamCode/build.gradle` for Control Hub API 25 compatibility.
- Preserved the single-inversion driver-perspective contract across `ARES-FRC` (`FrcDashboardDriveInput.kt`, `FRCTeleOpDriveController.kt`) and `ARES-FRC-Starter` (`FrcStudioDriveFrames.kt`, `StarterGeneratedCapabilities`) with regression coverage on both Blue and Red alliances (309/309 `ARES-FRC` tests passing).
- Updated `ARES-XRP-Starter` project validator/codegen/deploy preflight (128/128 `python tools/ares_project.py test` passing).
- Rebuilt all 5 bundled starter/example archives (`ARES-FTC-Starter-20.0.7.zip`, `ARES-FRC-Starter-20.0.7.zip`, `ARES-XRP-Starter-3.0.71.zip`, `ARES-Lightbot-Example-3.0.76.zip`, `ARES-BIOBUZZ-Example-1.1.14.zip`) with Unix `0755` executable mode on `ares` as well as `gradlew`/`*.sh`.

### 3. `ARES-Analytics` (Studio Desktop, Backend Services, UI, Gateway, Shared, MCP Wave 1)

| Severity | Area | Summary of Defect & Fix |
|---|---|---|
| **P0** | `AutoImportService.kt` | Quoted FTC `adb shell` directory/paths with `shellQuote` and `--`, rejecting path separators/`..`/control chars in remote filenames to prevent shell injection on Control Hub. |
| **P0** | `Nt4ClientService.kt`, `Nt4OutboundPublisher.kt`, `Nt4ConnectionMetrics.kt`, `FieldEditorViewModel.kt` | Enforced `isLoopbackSession` per attached WebSocket session inside `driveFramePublishMutex` and `sendBinaryUpdate`; restricted simulator-only fixed pubuids (`1011..1014`, `1020`); added `fieldConfig`, `obstacles`, and `MatchTime` to simulator-only allowlist with `trimStart('/')` normalization; preserved pre-connect frame contract validation. |
| **P0** | `FieldCanvasGestures.kt`, `FieldCanvasItemGestures.kt`, `FieldCanvas.kt` | Replaced Frame-0 captured gesture values with live `State<T>` / holder references so tool switches, waypoint edits, zoom/pan/rotation hit-testing, and Shift modifiers work reliably. |
| **P0** | `TelemetryChartPanel.kt` | Restricted the 100 ms wall-clock ticker strictly to live mode so replay playhead scrubbing no longer snaps back to `t = 0`. |
| **P0** | `FtcDriverStationWidget.kt` | Bounded `ARES/DriverStation/Telemetry/<idx>` indices to `0 until 256` to prevent EDT `IndexOutOfBoundsException` crashes and unbounded allocation OOMs. |
| **P0** | `gateway/Dockerfile`, `gateway/cloudbuild.yaml`, `.github/workflows/deploy-gateway.yml` | Switched Cloud Build context to monorepo root so `build-logic/` and `release/` resolve cleanly; migrated Dockerfile to JDK 17 Gradle wrapper (`8.14.5`) with non-root `ares` runtime user. |
| **P0** | `mcp` Wave 1 (`KotlinInvariantAuditor.kt`, `JGitSnapshotSafety.kt`, `HeadlessProjectMutators.kt`) | Replaced `String.startsWith` path check with `Path.startsWith` on real paths; blocked symlink escapes in `safeResolveChild`; enforced Kotlin package regex, slug regex, and source-root allowlist in `scaffoldKotlin` and `mutateTuning`/`mutateVersionedDoc`. |
| **P1** | Desktop Lifecycle (`Main.kt`, `ServiceRegistry.kt`, `DesktopShutdownCoordinator.kt`, `DesktopWindowPort.kt`, `DesktopWindowPresentationController.kt`, `DesktopStartupMachine.kt`, `McpBridgeServer.kt`) | Routed AWT crash handler through coordinated shutdown; isolated every `disposeAndJoin()` cleanup step under single-entry atomic disposal; added real `clearAlwaysOnTop()` recovery on `DesktopWindowPort`; serialized `McpBridgeServer` publish/close file IO under lock; converted `DesktopStartupMachine` transitions to atomic CAS; made `prepareForMainScreen()` cooperatively cancellable. |
| **P1** | Studio Services & Domain (`EventApiService.kt`, `EnvironmentService.kt`, `GenerativeAiService.kt`, `Nt4ClientService.kt`, `HardwareSetupService.kt`, `EngineeringNotebookCaptureService.kt`, `IntegrationRepository.kt`, `IntegrationCenterService.kt`, `BatteryFleetAnalyzer.kt`, `AlertEngineService.kt`, `ProjectArchiveExporter.kt`, `FieldEditorTransforms.kt`, etc.) | Prevented FIRST `username:token` leakage to TOA + validated event keys + installed HTTP timeouts; encrypted workspace API keys via `PlatformSecretStore`; validated Vertex `token_uri`/host/location/model; fixed XRP telemetry session recording & standalone pending-frame flusher; split XRP hardware port namespaces & normalized FRC `rio` CAN bus; isolated Engineering Notebook entries/revisions/LIMITs by workspace; moved `AlertEngineService.engineLifecycleLock` initialization before `init`; fixed XRP center-origin obstacle reflection with `-0.0` normalization; hardened Windows transient `AccessDeniedException` retry on `ProjectArchiveExporter.extract`. |
| **P1** | Studio UI & Gateway (`LocalSimulatorSessionController.kt`, `XrpSimulatorControlBar.kt`, `DesktopDriveKeyDispatcher.kt`, `ProfileMcpAssistantSection.kt`, `GatewayRoutes.kt`, `Shared` models) | Enforced loopback host gating in simulator control bars; guarded text-input focus and modifiers in `DesktopDriveKeyDispatcher`; offloaded MCP bridge probe onto `Dispatchers.IO`; hardened gateway validation/rate-limiting and shared model invariants. |
| **P1** | `mcp` Wave 1 (`HeadlessProjectEngine.kt`, `StudioBridgeClient.kt`, `AresMcpResources.kt`, `GcAndClockRules.kt`, `ProjectCodegenConventions.kt`, `DescriptorMutationTools.kt`, `McpSchemaHelpers.kt`) | Prevented dead/offline Studio bridge from overriding `lastInspectedRoot`; fixed multi-line hot method brace tracking; included subsystem tuning declarations during project init; made `mutateRoutine` + `autonomous-catalog.json` writes atomic; hardened JGit ref uniqueness/exactRef/worktree support; rethrew `CancellationException` in `guardedTool`. |

### 4. Build, CI Workflows & Scripts

- Added BioBuzz (`biobuzzExampleVersion` / `biobuzzExampleSha256` / `BIOBUZZ_EXAMPLE_URL` / `BIOBUZZ_EXAMPLE_SHA256`) to `verifyReleaseVersionAlignment` (`ARES-Analytics/build.gradle.kts`) and `.github/workflows/build-distributions.yml`.
- Added `:mcp:test` / `:mcp:classes` coverage to `monorepo-ci.yml`, `codeql.yml`, `build.ps1`, and `build-distributions.yml` report uploads; scoped CodeQL `security-events: write` per job.
- Fixed retired `com.areslib.frc.*` test package names in `verify-autos.sh`.
- Fixed `scripts/build-starter-archives.ps1` executable bit for `ares`, `scripts/verify_windows_mcp_launcher.py` timeout `server.kill()`, `scripts/publish-immutable-maven-repository.ps1` fetch error handling, `scripts/generate_codebase_ledger.py` UTF-8 subprocess encoding, `scripts/verify-doc-links.ps1` root containment, `scripts/verify-monorepo-policy.ps1` nested `.git` check for `ARES-XRP-Starter`, and `promote-release-candidate.yml` basename `.sha256` generation.

---

## Verification Evidence

| Suite / Gate | Exact Result |
|---|---|
| `ARESLib-Kotlin` (14 JVM/Android modules, `20.0.7-rc.audit`) | **PASSED** (`test`, `apiCheck`, `verifyAresLibSourceFileSizes`, `ZeroGcRegressionTest`, `publishReleaseValidation` to isolated local Maven repo) |
| `ARESLib-Kotlin/ares-micro` | **PASSED** (`136/136` Python unit tests via `python -m unittest discover -s tests -p "test_*.py" -v`) |
| `ARES-XRP-Starter` | **PASSED** (`128/128` via `python tools/ares_project.py test`) |
| `ARES-FTC` (Lightbot) | **PASSED** (`:TeamCode:testDebugUnitTest :simulator:test` against `20.0.7-rc.audit`) |
| `ARES-FTC-Starter` | **PASSED** (`:TeamCode:testDebugUnitTest :simulator:test` against `20.0.7-rc.audit`) |
| `ARES-FRC` | **PASSED** (`309/309` `test` against `20.0.7-rc.audit`) |
| `ARES-FRC-Starter` | **PASSED** (`test` against `20.0.7-rc.audit`) |
| `ARES-Analytics` full suite (`-ParesVersion=20.0.7-rc.audit`) | **PASSED** (`:verifyAresReleaseManifest`, `:verifyReleaseVersionAlignment`, `:shared:check`, `:gateway:check`, `:mcp:check`, `:verifyProductionKotlinFileSizes`, `:app:test` 2,982 tests / 0 failures / 6 skipped, `:app:dashboardSmoke`, `:app:dashboardPerformanceBaseline`, `:app:consumerRoundtripTest` 4/4, `:app:createDistributable`, `:app:verifyDistributableMcpSmoke`, `:app:verifyDistributableProjectLoading`, `:app:koverVerify`, `studioReleaseVerification`) |
| Rendered Windows Studio Desktop Verification (2 isolated cycles) | **PASSED** (`alwaysOnTop=false, focused=true, active=true, showing=true`, `1424x861`, clean `WM_CLOSE` disposal & zero leftover app PIDs): [Cycle 1](evidence/fresh-audit-2026-11-20/studio-7-0-81-cycle1.png) · [Cycle 2](evidence/fresh-audit-2026-11-20/studio-7-0-81-cycle2.png) |
| Monorepo Scripts & Policy Checks | **PASSED** (`scripts/tests` 119/119, `verify_agent_guidance.py`, `verify_kotlin_toolchain.py` 24/24 pins at `2.4.21`, `generate_codebase_ledger.py --verify` 1,255 files / 0 violations, `verify-doc-links.ps1`, `verify-imported-histories.ps1`, `export-starter-mirrors.ps1 -Check` all 5 archives, `verify-monorepo-policy.ps1`) |
