# Project Round-Trip, Safe Extraction, and Generation Recovery Review

Date: 2026-09-17. Scope: Studio project export, safe extraction, session reopening, generation determinism, USER-OWNED source preservation, cancellation handling, and multi-document transaction recovery. Strictly local; no remote push, publish, release, or merge.

## Baseline & Context

- **Local Parent Branch:** `codex/project-roundtrip-audit` at commit `d512d64fe8cc955003ccd82b06c455132f32e1a5`.
- **Active Working Branch:** `codex/project-roundtrip-integration`.
- **Target Products:** `ARES-Analytics` (Studio project session, export/extract, build service), `ARESLib-Kotlin` (codegen CLI, project compiler, multi-document transaction persistence), and `ARES-FTC` (BioBuzz reference project).

---

## 1. Boundary & Scenarios Under Audit

1. **Safe Project Archive Extraction (`ProjectArchiveExporter.extract`):**
   - Studio previously implemented project export (`export(projectPath, destinationArchive)`) but lacked a safe, symmetric `extract(archivePath, destinationPath)` service method.
   - Archives could be vulnerable to Zip Slip path traversal attacks, zip bombs (infinite recursion or uncompressed bloat), Windows reserved device collisions (`CON`, `PRN`, `AUX`, `NUL`, `COM1..9`, `LPT1..9`), and unowned/malformed archives missing canonical project identity.
   - Implemented production-grade extraction in `ProjectArchiveExporter.kt` enforcing:
     - Absolute destination directory confinement (`toPath().startsWith(destination.toPath())`).
     - Backslash and relative traversal segment (`..`, `.`) rejection.
     - Windows reserved device name validation on entry components.
     - Single-file (100MB max), entry count (50,000 max), and total project extraction (1GB max) quotas.
     - Canonical `.ares/project.json` presence requirement.
     - Automatic POSIX line-ending (`\n`) and executable bit normalization for `gradlew`.

2. **Project Generation Cancellation Handling (`ProjectBuildService`):**
   - Active generation jobs canceling via coroutine cancellation (`CancellationException`) previously left observable state unfinalized or in stale running state for concurrent UI observers.
   - Updated `executeAresGeneration` and `executeSubsystemStarterGeneration` in `ProjectBuildService.kt` to catch `CancellationException`, update `_aresGenerationState` to `AresGenerationPhase.FAILED` with explicit diagnostics ("Project generation was canceled."), and rethrow `cancelled` to honour coroutine structured concurrency.

3. **End-to-End Round-Trip on Real BioBuzz Project (`ProjectRoundtripIntegrationTest`):**
   - Extracted bundled BioBuzz reference project (`ARES-BIOBUZZ-Example-1.1.5`).
   - Verified initial settings retain exact intended meaning:
     - Identity: `projectId = "biobuzz-reference"`, `displayName = "BIOBUZZ Bot"`, `teamId = "23247"`, `robotId = "BIOBUZZ"`, `league = FTC`.
     - Subsystems: `biobuzz-intake`, `biobuzz-shooter`.
     - Drivetrain: `starter-mecanum`.
     - Controllers: `ftc-driver`.
     - Controls: `driver`.
     - Field: `3.6576m x 3.6576m` FTC field boundaries.
     - Tuning: canonical checked-in profile `simulation`.
   - Introduced genuine `USER-OWNED` extensions:
     - `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/extensions/TeamRobotExtensions.kt` with custom team methods.
     - `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/CustomUserSubsystem.kt` with `// ARES OWNERSHIP: USER-OWNED`.
   - Exported project to `.aresproject.zip` archive and extracted to a fresh clean directory.
   - Verified extracted project opens in a fresh `ProjectSession` with byte-identical composite canonical SHA-256 fingerprint (`canonicalContentSha256`).
   - Verified all `USER-OWNED` extensions match byte-for-byte upon extraction.
   - Ran `AresProjectCodegenCli.run`:
     - Generated runtime `GeneratedAresProject.kt`, drivebase configuration, and subsystem plumbing.
     - Verified `USER-OWNED` source was never touched or modified.
     - Verified check-only verification mode (`--check`) passed.
     - Verified repeated generation is 100% deterministic (byte-identical source and content hash).

4. **Partial Failure & Verification Gate Recovery:**
   - Injected intermediate failure where runtime code was updated on disk but the build was interrupted before `ares-project-verification.json` could be written.
   - Verified check-only verification (`--check`) fails closed immediately with stale verification manifest diagnostics.
   - Verified canonical `.ares` documents remained intact and uncorrupted.
   - Re-running generation cleanly restored verification state.

5. **Multi-Document Transaction Rollback (`ProjectMutationTransaction.recover`):**
   - Simulated an interrupted transaction in `.ares/recovery/transactions/interrupted-tx-12345` with scope `.ares/subsystems`.
   - Injected intermediate partial writes: modified `biobuzz-intake.aressubsystem` with corrupt trailing data and created an uncommitted file `uncommitted-new.aressubsystem`.
   - Loaded project in `ProjectSession`: `loadStable()` automatically detected the missing `COMMITTED` marker, invoked `ProjectMutationTransaction.recover()`, purged the uncommitted file, restored the intake subsystem to its baseline content, and cleaned up the transaction recovery folder.

---

## 2. Findings & Fixes

1. **Missing `extract` Method in `ProjectArchiveExporter`:**
   - *Finding:* Studio had `export` functionality creating `.aresproject.zip` archives, but no built-in safe extraction method. Users importing archives would rely on external unzip tools which do not sanitize line endings on `gradlew`, validate project presence, or protect against path traversal attacks.
   - *Fix:* Implemented `suspend fun extract(archivePath: String, destinationPath: String): File` with comprehensive security bounds, Zip Slip rejection, device name checks, size limits, and `gradlew` normalization.

2. **Coroutine Cancellation in `ProjectBuildService`:**
   - *Finding:* When coroutines running `executeAresGeneration` or `executeSubsystemStarterGeneration` were cancelled, the phase state was not explicitly marked as `FAILED` before rethrowing, potentially leaving client UI observing stale or indefinitely pending generation states.
   - *Fix:* Added `catch (cancelled: CancellationException)` blocks updating generation state to `AresGenerationPhase.FAILED` with descriptive diagnostic messages before rethrowing.

3. **Transaction Recovery Scope Semantics:**
   - *Finding:* In multi-document transaction manifests (`V\t2\n`), declaring a directory scope like `S\t.ares/subsystems\n` causes `ProjectMutationTransaction.restore` to delete any file currently in that scope that was not present in the baseline records (`F\t...`). A test or caller simulating transactions must register all pre-existing files in the scope to ensure uncommitted new files are purged without removing unmodified baseline siblings.
   - *Fix:* Formulated the integration test transaction scenario to register all pre-existing `.ares/subsystems` files in the baseline, validating that rollback precisely purges uncommitted files while restoring modified ones.

---

## 3. Validation Evidence

All tests executed with `./gradlew.bat` using local sibling ARESLib (`-ParesUseSiblingLib=true`):

1. **`ProjectArchiveExporterTest` (8/8 passed):**
   - `export excludes sensitive and transient project artifacts`
   - `export preserves canonical project and tuning files`
   - `repeated export produces byte identical zip archives`
   - `safe extraction restores exported project files and normalizes gradlew`
   - `extraction rejects missing canonical project identity`
   - `extraction rejects path traversal entries`
   - `extraction rejects non-empty destination`
   - `extraction rejects reserved device names`

2. **`ProjectRoundtripIntegrationTest` (4/4 passed):**
   - `biobuzz project round-trip preserves settings, user-owned extensions, and deterministic generation` (1.359s)
   - `intermediate write failure triggers fail-closed verification gate and clean recovery` (1.467s)
   - `interrupted multi-document transaction recovers baseline on next project session load` (1.699s)
   - `stopping active generation transitions observable state to FAILED and clears running` (0.194s)

3. **Scoped Regression Suites:**
   - `com.ares.analytics.service.project.ProjectSessionTest`: PASSED (28s)
   - `com.ares.analytics.service.ProjectBuildServiceTest`: PASSED (37s)

4. **Maintainability Ledger & Monorepo Policy:**
   - `python scripts/generate_codebase_ledger.py`: 1,111 production files, 0 violations, maintainability ratchet PASS.
   - `pwsh -File scripts/verify-monorepo-policy.ps1`:
     - Shared agent guidance verified (tracked files, ignore rules, adapters, size, and links).
     - Verified local Markdown links in 188 documents (54 historical records skipped).
     - Ledger verified: 1,111 files, 0 violations, ratchet PASS.
     - Monorepo policy verified: ARES 19.1.3, Studio 7.0.63.

---

## 4. File Size Invariant Compliance

All modified and new files remain well below the 750-line ceiling:
- `ARES-Analytics/app/src/main/kotlin/com/ares/analytics/service/ProjectBuildService.kt`: 634 lines
- `ARES-Analytics/app/src/main/kotlin/com/ares/analytics/service/versioncontrol/ProjectArchiveExporter.kt`: 190 lines
- `ARES-Analytics/app/src/test/kotlin/com/ares/analytics/service/versioncontrol/ProjectArchiveExporterTest.kt`: 176 lines
- `ARES-Analytics/app/src/test/kotlin/com/ares/analytics/service/project/ProjectRoundtripIntegrationTest.kt`: 308 lines

---

## 5. Remaining Limitations

- **Physical Hardware:** Physical REV Control Hub and Limelight hardware are not connected; tests were executed using Studio headless JVM test runners, simulated project environments, and mock/declarative IO.
- **Local Scope Only:** Work is entirely local on branch `codex/project-roundtrip-integration`. No changes have been pushed to GitHub, published to Maven repositories, or merged into `main`.
