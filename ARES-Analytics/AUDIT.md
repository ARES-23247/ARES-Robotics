# ARES Analytics — historical codebase audit

> [!IMPORTANT]
> This file is a dated evidence snapshot, not the current defect backlog. A later remediation pass changed NT4 handling, replay lifecycle, imports, SQL boundaries, gateway scope/security, unit conversion, and several mathematical services. Some findings below are therefore fixed or no longer describe the current architecture. Verify every item against current source and tests before treating it as open. Current behavior is documented in [ARCHITECTURE.md](ARCHITECTURE.md), [docs/TELEMETRY_CONTRACT.md](docs/TELEMETRY_CONTRACT.md), and [docs/OPERATIONS.md](docs/OPERATIONS.md).

**Scope:** Whole codebase (`app/`, `shared/`, `gateway/`, config, infra, rules)
**Mode at capture time:** Report only — no code was modified or committed.
**Method:** Parallel static analysis across four module-focused passes.

> **Platform note:** Despite "Analytics" tooling assumptions, `app/` is a **Compose Desktop (JVM)** application (not Android) — `kotlin("jvm")` + `compose.desktop`, no `AndroidManifest.xml`. This affects the threat model (single-user desktop, plaintext credential files vs. Android Keystore).

---

## Executive summary

| Severity | Count |
|---|---|
| CRITICAL | 8 |
| HIGH | 17 |
| MEDIUM | 24 |
| LOW | 21 |

**Top themes:**
1. **Credentials in source & on disk** — a real OAuth client secret is in git (reversed-string "obfuscation"); long-lived refresh tokens are stored in plaintext JSON.
2. **Tenant isolation holes** — Storage/Firestore rules and gateway session-keying let any authenticated user reach other teams' data.
3. **LLM → raw SQL execution** — model output is run with `Statement.execute()`, enabling destructive queries via prompt injection.
4. **Concurrency & resource leaks** — replay engine mutates a `HashMap` from multiple threads, leaks a `DatagramSocket`, and spawns orphan coroutines at 50 Hz.
5. **Data-loss paths** — auto-import deletes the only copy of log files (including on the robot) before confirming cloud sync.

---

# CRITICAL

### C1. Hardcoded Google OAuth Client Secret (committed, "reversed" is not encryption)
- **File:** `app/src/main/kotlin/com/ares/analytics/service/GoogleDriveService.kt:52-57`
- **Category:** Security
- A now-redacted reversed string decoded to a Google OAuth **Client Secret**. String reversal is plaintext, not encryption; the historical credential was removed and must remain revoked.
- **Fix:** Remove the literal; load only from config/env. **Rotate the secret** — it's in git history regardless.

### C2. Hardcoded Google OAuth Client ID in multiple places
- **Files:** `GoogleDriveService.kt:52`, `app/.../viewmodel/OnboardingViewModel.kt:29`
- **Category:** Security
- Client ID `205869391101-…apps.googleusercontent.com` is the default fallback in two locations. Together with C1 the full credential pair is reconstructable.
- **Fix:** Delete defaults; load from config/env; centralize in one place.

### C3. Prompt-injection → arbitrary SQL execution
- **File:** `app/src/main/kotlin/com/ares/analytics/service/SyncEngineService.kt:544-668` (esp. 657-668)
- **Category:** Security
- `requestSqlAnalysis()` asks the LLM to produce SQL, then executes the model's output **verbatim** via `databaseService.executeQueryRaw(sqlQuery)` using `st.execute(sql)` — which accepts DDL/DML (`DROP TABLE`, `DELETE`, `ATTACH`, `INSTALL`). Session data fed into the prompt (annotations, tags, alert payloads) is user-controlled → direct injection vector.
- **Fix:** Use a read-only DuckDB connection (`BEGIN TRANSACTION READ ONLY`), reject any query not starting with `SELECT`/`WITH` after normalization, and never `execute()` raw model output.

### C4. Gemini API key leaked via URL query string
- **Files:** `SyncEngineService.kt:392, 489, 605, 687`
- **Category:** Security
- Key sent as `?key=$apiKey`. Query-string creds leak into access/proxy/referrer logs and error bodies. The failure handler (line 410) echoes `bodyAsText()` which can include the URL.
- **Fix:** Send the key via the `x-goog-api-key` header.

### C5. Source log files deleted before cloud sync is confirmed (data loss)
- **Files:** `app/src/main/kotlin/com/ares/analytics/service/AutoImportService.kt:154` (local), `:203` (FTC robot), `:257` (FRC RoboRIO)
- **Category:** Bug (data loss)
- After `parseLogFile()` returns, the original file is `file.delete()`-d — including the file on the physical robot. Parsing only persists locally to DuckDB; cloud upload is a separate async step that may never run. If the DB corrupts or migration fails, the canonical log is gone forever. The code already creates `importedDir` (line 126) but never uses it.
- **Fix:** Move files to an `imported/` archive and only purge after confirmed cloud upload. Never auto-delete the robot-side file.

### C6. `MOCK_AUTH` authentication-bypass backdoor ships in production image
- **File:** `gateway/src/main/kotlin/com/ares/analytics/gateway/auth/FirebaseAuth.kt:38-48`
- **Category:** Security
- When `MOCK_AUTH=true`, any token of the form `mock-token:<uid>:<email>:<name>:<teamId>` is accepted with **zero verification**, and the attacker fully controls `uid` and `teamId` → defeats every `withTeamContext` check and grants admin. The code lives in `main` (not `test`), so it ships in the Docker image. One Cloud Run env-var mistake away from total auth bypass.
- **Fix:** Move to a test-only source set / a provider never registered in `Application.kt`. Hard-fail startup if `MOCK_AUTH=true` in a non-dev runtime.

### C7. Cross-tenant session deletion/overwrite (IDOR) — sessions keyed globally by `sessionId`
- **Files:** `gateway/src/main/kotlin/com/ares/analytics/gateway/routes/ArchiveRoutes.kt:129-163` (delete @145), `:65-95` (upload-url @77)
- **Category:** Security / Bug
- `withTeamContext()` only verifies `req.teamId == caller.teamId`; it never verifies the target `sessionId` belongs to that team. Summary docs live at global key `summaries/{sessionId}` with no team scope. An admin of team A can delete/overwrite team B's session metadata by passing any `sessionId`. If session IDs are enumerable (timestamps), this is cross-tenant data destruction.
- **Fix:** Before write/delete, fetch the doc and assert `doc.data["teamId"] == caller.teamId`. Better: scope as `teams/{teamId}/summaries/{sessionId}` and let rules enforce it.

### C8. Storage Rules — any authenticated user reads/writes any team's telemetry
- **File:** `storage.rules:12-16`
- **Category:** Security
- `match /telemetry/{sessionId}` checks `isAuth() && request.auth.token.team_id != null` — i.e. the caller has *some* `team_id`, but **never that it matches the object's team**. There's no team identifier in the path. The file's own comment (line 11) states the intent ("metadata contains teamId … matching user claims") but the rule doesn't implement it. Any logged-in user can read/overwrite/delete any team's telemetry parquet.
- **Fix:** Encode team in the path (`/telemetry/{teamId}/{sessionId}`) and enforce `request.auth.token.team_id == teamId`, and/or validate `request.resource.metadata.teamId == request.auth.token.team_id` on reads & writes.

---

# HIGH

### H1. OAuth flows omit the `state` parameter (CSRF)
- **File:** `app/.../service/OAuthService.kt:209-258` (Google), `:328-359` (GitHub)
- **Category:** Security
- Neither flow generates/validates `state`; the loopback server (line 374-438) accepts any `/callback?code=…`. A malicious local page (DNS rebinding) or process on `localhost:5805` can inject an authorization code and link their identity to the victim's session.
- **Fix:** Generate a random `state` per request, embed in the auth URL, reject non-matching callbacks.

### H2. Long-lived auth tokens stored in plaintext
- **Files:** `app/.../service/FirebaseClientService.kt:164, 200, 275, 314`; model `shared/.../WorkspaceModels.kt:11-36`; written by `app/.../EnvironmentService.kt:44,60,73`
- **Category:** Security
- `~/.ares-analytics/auth.json` stores the Firebase refresh token (+ optional Google access/refresh tokens) as plain, world-readable JSON. `WorkspaceConfig` likewise persists `googleClientSecret`, `geminiApiKey`, `toaApiKey`, etc. as plaintext JSON. Any co-located process can impersonate the user indefinitely.
- **Fix:** Restrict file perms to owner-only; prefer OS-native secret storage (DPAPI/Keychain); never persist an OAuth client secret.

### H3. `owlet` subprocess deadlocks — stdout/stderr pipes never drained
- **File:** `app/.../service/log/HootDecoderService.kt:143-161`
- **Category:** Bug
- `ProcessBuilder(...).start()` is followed by `waitFor(30s)` without reading the streams. Once `owlet` fills the 4–64 KB pipe buffer it blocks forever → `waitFor` times out → `destroyForcibly` → import throws "owlet CLI timed out" even though owlet was working. No error output captured.
- **Fix:** Drain `inputStream`/`errorStream` on background threads (or `redirectErrorStream(true)` + reader coroutine) before `waitFor`.

### H4. Q/E keyboard input never reaches intake/flywheel bindings
- **Files:** `app/.../ui/screens/MainScreen.kt:349-350` vs `app/.../Main.kt:113-114` (consumed at `MainScreen.kt:167-168, 177-178`)
- **Category:** Bug
- `MainScreen` installs `onPreviewKeyEvent` (runs first, returns `true`) mapping `Q → ks.isLeftPressed`, `E → ks.isRightPressed`. `Main.kt` installs a regular `onKeyEvent` mapping `Q → state.isQPressed`, `E → state.isEPressed`. Preview wins, so `isQPressed`/`isEPressed` are **permanently false** → the 50 Hz drive loop never triggers `ARES/Input/isIntaking` or `ARES/Input/isFlywheelOn` from the keyboard.
- **Fix:** Decide one owner for Q/E. Likely keep `isQPressed/isEPressed` (intake/flywheel) and remove the conflicting `isLeftPressed/isRightPressed` mapping.

### H5. `updateSessionLogFilePath` is a silent no-op — session↔log linkage broken
- **Files:** `app/.../service/db/MatchLogRepository.kt:567-569` (no-op) called from `app/.../service/Nt4ClientService.kt:602-604`
- **Category:** Bug
- On `ARES/Session/LogFilePath` the dashboard calls `updateSessionLogFilePath(...)`, which does nothing (schema dropped the column). The architecture doc (§3.1) describes this linkage as the core mechanism for cloud sync + local cleanup. With it broken, the sync engine cannot locate canonical logs by session and the auto-purge invariant is unenforceable.
- **Fix:** Re-add the column + implement the update, or remove the call site and document the linkage is no longer tracked.

### H6. `ReplayEngineService` leaks `DatagramSocket` + unparented coroutine scopes
- **Files:** `app/.../service/ReplayEngineService.kt:107` (socket), `:160`, `:364` (orphan scopes)
- **Category:** Bug (resource leak / concurrency)
- `datagramSocket = DatagramSocket()` opened in the constructor, never closed (leaks an FD for the JVM lifetime). `play()` and `updateFrameAtPlayhead()` (called ~50×/s) each create a fresh `CoroutineScope(Dispatchers.Default).launch { … }` — dozens of orphan coroutines/sec, none cancelled by service teardown.
- **Fix:** One class-level `serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())`; cancel in `stop()`/new `dispose()`; close the socket in `dispose()`.

### H7. `ReplayEngineService.valuesMap` mutated from multiple threads without synchronization
- **File:** `app/.../service/ReplayEngineService.kt:105, 276-353`
- **Category:** Bug (race)
- Plain `mutableMapOf` read/written from the `Dispatchers.Default` playback loop **and** directly from UI handlers (`scrubTo`, `stepForward`, `stepBackward`). Concurrent `put`/iterate/`clear` on a `HashMap` is undefined behavior — can corrupt internal state or spin (infinite loop on JDK 8+).
- **Fix:** `Mutex`, single-dispatcher confinement, or `ConcurrentHashMap`.

### H8. `Nt4ClientService` console-message matching is far too broad
- **File:** `app/.../service/Nt4ClientService.kt:625-648`
- **Category:** Bug
- `if (lowerName.contains("console") || lowerName.contains("log") || lowerName.contains("print"))` treats any telemetry topic containing the substring `log` or `print` as console output → pushes to `_consoleFlow` and inserts into `console_messages`. Misclassifies large swaths of ordinary telemetry (e.g. `Drive/Logging/Position`, `PathLog`).
- **Fix:** Match exact topic names (`ARES/Console`, `Robot/Console`, `System/Print`), not bare substrings.

### H9. `VACUUM telemetry_frames` runs every second during live streaming
- **Files:** `app/.../service/db/MatchLogRepository.kt:497-510` (VACUUM in `deleteTelemetryFrames` & `pruneTelemetryFrames`), called from `Nt4ClientService.kt:152` every 1 s
- **Category:** Bug (performance)
- `flushPendingFrames()` runs on a 1 s loop and unconditionally `pruneTelemetryFrames` → `VACUUM telemetry_frames`. DuckDB VACUUM rewrites the whole table while holding the global `dbMutex`, stalling every other DB op (replay, summaries, UI) for potentially seconds each second.
- **Fix:** Drop VACUUM from the prune hot path; run at most once on session stop / shutdown.

### H10. `WpiLogDecoder` assumes `InputStream.read(buf)` fills the buffer
- **File:** `app/.../service/log/WpiLogDecoder.kt:42, 60, 68`
- **Category:** Bug
- `fis.read(buf)` is checked `!= expectedSize` and on partial read the decoder `break`s out — silently truncating the import and discarding the rest of the log with no warning. WPI logs are routinely tens of MB; this triggers intermittently.
- **Fix:** Use `readNBytes(len)` (Java 9+) which blocks until `len` bytes or EOF, or loop manually.

### H11. Firestore rules `isAdmin()` `get()` references `users/{uid}` with no matching rule
- **Files:** `firestore.rules:11-14, 19`
- **Category:** Security
- `isAdmin()` calls `get(/databases/$(database)/documents/users/$(request.auth.uid))…`, but there is no `match /users/{userId}` block. Rules `get()` respects read perms — if no rule allows reading `users/{uid}`, the `get()` fails and `isAdmin()` is **always false** → no client can ever write robot rosters or update/delete summaries (rules 19, 26 deny). The gateway bypasses rules via its service account, so admin ops from the client are effectively dead.
- **Fix:** Add `match /users/{userId} { allow read: if request.auth != null && request.auth.uid == userId }` and verify end-to-end.

### H12. `BuildConfig.VERSION` is stale — update checker always misreports
- **Files:** `app/.../BuildConfig.kt:11` (`"1.0.0"`) vs `app/build.gradle.kts:79` (`packageVersion = "1.0.3"`)
- **Category:** Bug
- Hand-maintained constant lags the Gradle version. `UpdateCheckerService.isNewerVersion(BuildConfig.VERSION, …)` compares against stale `"1.0.0"`, so 1.0.3 users are told 1.0.1 is an update, and an update to 1.0.3 is silently ignored.
- **Fix:** Generate `BuildConfig` from the Gradle version.

### H13. `SysIdService.analyzeMotorData` loads the entire session into memory 3×
- **File:** `app/.../service/SysIdService.kt:63-65`
- **Category:** Bug (memory/perf)
- `getTelemetryRange(sessionId, 0L, Long.MAX_VALUE)` called three separate times (voltage, velocity, acceleration), then `.filter { it.key == … }` in Kotlin. For sessions with millions of frames this triples heap pressure. `getTelemetryForKey` already exists for server-side filtering.
- **Fix:** Use `getTelemetryForKey` per key, or fetch once and partition in memory.

### H14. `Nt4ClientService.stop()` closes HTTP clients before pending coroutines finish
- **File:** `app/.../service/Nt4ClientService.kt:417-430`
- **Category:** Bug (race)
- `stop()` calls `clientJob?.cancel()` (non-suspending, doesn't wait) then synchronously `localClient?.close()` / `remoteClient?.close()`. A just-launched `flushPendingFrames()` (line 421) and the WebSocket reader may still be using those clients → `HttpClientClosedException` / use-after-close.
- **Fix:** `clientJob?.cancelAndJoin()` (make `stop` suspend) before closing clients.

### H15. GitHub-supplied token not bound to Firebase identity → role escalation
- **File:** `gateway/.../routes/AuthRoutes.kt:79-160` (decision 128-140, write 143-155)
- **Category:** Security
- The endpoint authenticates via Firebase (`principal`) but uses a separately client-supplied `req.githubToken` to decide `role = ADMIN` (line 137) with no check that the GitHub identity belongs to `principal`. A user can supply another person's mentor GitHub PAT and get `role = ADMIN` written to **their own** `users/{uid}` doc.
- **Fix:** Verify the GitHub user's verified email matches `principal.email`, or obtain the GitHub credential from Firebase-linked providers; reject mismatches with 403.

### H16. Internal exception messages leaked to clients
- **Files:** `ArchiveRoutes.kt:93,124,160,185,208,231,249,278`; `AuthRoutes.kt:158`; `DiagnosticsRoutes.kt:80`; `FirebaseAuth.kt:61`
- **Category:** Security (info disclosure)
- Every route's `catch (e: Exception)` responds `"... ${e.message}"`, leaking SDK internals, Firestore/GCS error text, and existence/oracle info. The global `StatusPages` handler correctly hides details but is pre-empted by these route-level catches.
- **Fix:** Log full exception server-side; return a generic message; map known errors to proper status codes.

### H17. Broad `catch (e: Exception)` swallows `CancellationException`
- **Files:** every route try/catch in `ArchiveRoutes.kt`, `AuthRoutes.kt`, `DiagnosticsRoutes.kt`
- **Category:** Bug (coroutine misuse)
- `CancellationException` is an `Exception`; catching it breaks structured concurrency — on client disconnect, the cancellation is swallowed and the code then tries `call.respond(...)` on a cancelled call (`DoubleResponseException`), polluting logs and keeping Firestore/GCS work running after the client is gone.
- **Fix:** `catch (e: CancellationException) { throw e }` first, or narrow catches; let `StatusPages` handle the rest.

---

# MEDIUM

| ID | File | Category | Summary |
|---|---|---|---|
| M1 | `UnitConversion.kt:86-87` | Bug | `convert()` silently returns input on category mismatch → wrong-unit numbers flow through dashboards with no indication. |
| M2 | `UnitConversion.kt:114-136` | Bug | `detectUnitFromKey()` classifies linear velocity as `METER` (a length unit); no linear-velocity unit exists in the enum. |
| M3 | `PathPlannerModels.kt:50-64` | Bug | KDoc says radians; defaults `maxAngularVelocity=540.0`/`maxAngularAcceleration=720.0` are clearly deg/s. Docs or defaults are wrong. |
| M4 | `SessionTelemetryModels.kt:108-114` | Bug | `ControllerBinding` is the only model missing `@Serializable` → runtime crash if ever serialized/exported. |
| M5 | `SummaryEngineService.kt:227` | Bug | Tag-update condition compares `.size` not contents — same-count tag replacement is silently lost. |
| M6 | `MatchLogRepository.kt:563-565` | Bug | `associateSessionWithMatch` silently drops `opponentTeams` param. |
| M7 | `DatabaseService.kt:56,64` | Bug | JDBC URL interpolates unescaped home-dir path; username with an apostrophe (`O'Brien`) breaks connection. |
| M8 | `DatabaseBackupExporter.kt:58`, `HootDecoderService.kt:199,261`, `SchemaMigrationManager.kt:49` | Security | SQL-injection-via-filename: file paths string-interpolated into DuckDB SQL without escaping (export escapes, imports don't). |
| M9 | `GoogleDriveService.kt:66-69,108,129` | Security | Drive query-language injection via unescaped folder/file names. |
| M10 | `ProcessManagerService.kt:138-217` | Bug (ops risk) | `killOrphanedSimulators` parses `netstat`/`lsof` and `destroyForcibly()` any PID on ports 5810/1735 — including other apps' processes; JPS fallback kills any JVM whose main class contains "sim". |
| M11 | `AutoImportService.kt:334-346` | Bug | `isFileInUseOnFtcRobot` lsof logic inverted (`\|\| output.isNotBlank()` short-circuits) → files almost always considered "in use" and skipped. |
| M12 | `AutoImportService.kt:473-480` | Bug | `isFileInUseLocally` opens files in `"rw"` to test liveness — unreliable, mutates mtime. |
| M13 | `DatabaseService.kt:118-123` | Bug | `close()` uses `runBlocking { dbMutex.withLock }` — deadlocks if called from a coroutine already holding `dbMutex`; also blocks UI thread. |
| M14 | `Main.kt:84-92` | Quality | `System.exit(0)` after `exitApplication()` skips shutdown hooks (file-lock release) and `use{}` finalizers. |
| M15 | `UpdateCheckerService.kt:139-153` | Bug | `isNewerVersion` mishandles pre-release tags (`"1.0.3-beta"` → `[1,0]`). |
| M16 | `VideoSyncService.kt:134-150` | Quality | `seekVideo` is a stub — body is all comments; video and telemetry drift after manual seek. |
| M17 | `ParquetLogDecoder.kt:30-33` | Quality | `parseParquetLog` is a `// TODO` stub; caller silently gets nothing. |
| M18 | `SummaryEngineService.kt:49-122` | Quality (perf) | Summary SQL uses unindexable `LOWER(key) LIKE '%…%'` full scans; holds global `dbMutex`. |
| M19 | `HootDecoderService.kt:460-560` | Quality (perf) | `runDiagnostics` issues N+1 queries (one `getTelemetryForKey` per key). |
| M20 | `AutoImportService.kt:354-437` | Security | SSH `StrictHostKeyChecking=no` + `UserKnownHostsFile=/dev/null` for all RoboRIO pulls → MITM on shared networks. |
| M21 | `ArchiveRoutes.kt:306-326` | Bug | `SessionSummary.toMap()` omits `avgCrossTrackError`, `avgBatteryResistance`, `maxMotorTemps`, `avgVisionLatencyMs` → lossy Firestore round-trip (read back as `0.0`). |
| M22 | `Application.kt:64-72` (gateway) | Security | CORS `anyHost()`. Low risk (Bearer auth) but unintentional policy; restrict to known origins. |
| M23 | `DiagnosticsRoutes.kt:19-22,62` (gateway) | Bug/Quality | Vertex AI client is an eager global singleton, never closed; `generateContent` has no timeout (Cloud Run kills at minutes). |
| M24 | `.github/workflows/build-distributions.yml:72` | Security (supply chain) | `softprops/action-gh-release@v2` pinned to mutable tag, not SHA; workflow has `contents: write`. |

---

# LOW (summary list)

- **L1** Pervasive `println`/`e.printStackTrace()` instead of SLF4J (100+ sites). Logback is already on the classpath.
- **L2** Many empty/swallowed exception handlers (e.g. `Nt4ClientService.kt:535-537`, `DashboardViewModel.kt:326-328`).
- **L3** Duplicated mock-token string with typo (`FirebaseClientService.kt:230`, `SyncEngineService.kt:75`, `TeamApiService.kt:45`).
- **L4** Dead variables (`Nt4ClientService.kt:194,188`; `SummaryEngineService.cleanKeyToDeviceName`).
- **L5** `DashboardScreen.formatTime` is a no-op wrapper.
- **L6** Magic number: motor resistance `0.05` Ω (`HootDecoderService.kt:486`).
- **L7** No explicit charset in `String(...)` / `InputStreamReader(...)` → platform-default (windows-1252 on Windows) mojibake on UTF-8 robot logs.
- **L8** `tempCsv.deleteOnExit()` redundant (`HootDecoderService.kt:142` then manual delete @191).
- **L9** FRC team-number host logic assumes ≤ 4 digits; 5-digit team numbers (91329) get mangled into wrong IPs.
- **L10** `ReplayEngineService.loadSession` loads every frame into memory.
- **L11** `ReplayEngineService` dead branch for negative speed (`:297`).
- **L12** Redundant nested try in schema migration (`SchemaMigrationManager.kt:62-67`).
- **L13** Fire-and-forget nested `scope.launch` (`DashboardViewModel.kt:220-223`) breaks intent ordering.
- **L14** `GamepadService.kt:94` swallows `Throwable` (includes `OutOfMemoryError`).
- **L15** `DashboardScreen` mutates `Nt4ClientService.isReplayActive` directly (encapsulation).
- **L16** `AppJson` lacks `coerceInputValues`/`explicitNulls=false` → legacy JSON with explicit nulls throws on decode.
- **L17** `AlertRecord.durationMs` denormalized with no invariant vs trigger/resolve timestamps.
- **L18** `FieldImageConfig` crop values unvalidated (inverted crop → NaN width).
- **L19** Magic color `"#E53935"` duplicated across `Obstacle` subtypes.
- **L20** Unreachable `else` branches in temperature conversion.
- **L21** Hardcoded GitHub repo path (`UpdateCheckerService.kt:104`).

---

# Areas needing deeper investigation

1. **`service/calibration/`** (`CameraCalibrationSolver`, `OdometryCalibrationSolver`) — Levenberg-Marquardt over EJML; common source of convergence/singular-matrix crashes. Worth a focused math review.
2. **`viewmodel/CloudViewModel.kt`** — 13 `e.printStackTrace()` sites; review for swallowed user-facing cloud failures.
3. **`viewmodel/pathing/*` + `TrajectoryEstimator.kt`** — trapezoidal profiler + curvature-limit math; verify trajectory durations and Windows ADB-push error handling.
4. **`AlertEngineService` concurrency** — read/decide/insert/update sequence isn't atomic across the evaluate cycle; duplicate alerts possible under high telemetry rates. Stress-test.
5. **`Nt4ClientService.telemetryHistory`** — `getOrPut` not atomic w.r.t. `synchronized(history)`; verify under contention.
6. **`DatabaseService` singleton** — single `conn` + `dbMutex`; any long query stalls the entire UI. Profile realistic workloads.
7. **`teamId` claim lifecycle** — gateway trusts `decodedToken.claims["teamId"]` but doesn't mint it. Which service sets it? Can a user pick their own team at signup (→ tenant bypass independent of gateway)? Are claims revoked on team changes?
8. **Rate-limit keying behind Cloud Run LB** — `RateLimit` keys on remote address (may be the LB IP). Verify `X-Forwarded-For` handling; consider keying on `principal.uid`.
9. **Test coverage gaps** — no tests for `SyncEngineService`, `GoogleDriveService`, `OAuthService`, `FirebaseClientService`, `AutoImportService`, `ReplayEngineService` (integration), `ProcessManagerService`, `DatabaseService`, or any ViewModel. The highest-risk security/concurrency paths are the least tested. No `UnitConversion` tests (the module's only real logic).

---

# Housekeeping (stray tracked files)

These one-off scratch/test files are tracked in git and not wired into any Gradle source set:

`test.kt`, `test_coords.kt`, `test_jamepad.gradle.kts` (the two `.kt`/`.gradle.kts` files are UTF-16 encoded), `refactor.py`, `fix_errors.py`, `check_kdoc.py`, `clean_kdocs.py`, `kdoc_adder.py`, and `scratch/TestRightClick.kt`.

Also: `build.gradle.kts:134-137` (root `run` task) hardcodes a personal scratch path (`C:\Users\david\.gemini\…`) that won't exist on any other machine; `settings.gradle.kts:1-47` writes to the **global** `~/.gradle/gradle.properties` (surprising machine-global mutation).

**Suggested action:** remove the stray files from version control; move useful scripts under `scripts/`/`tools/`; fix the `run` task to use `layout.buildDirectory.dir("run-logs")`; make `settings.gradle.kts` fail-fast with instructions instead of mutating global Gradle state.

---

## Credential rotation checklist

Given C1/C2, the following should be treated as compromised (they are in git history) and rotated regardless of any source fix:
- [ ] Google OAuth Client Secret (`GOCSPX-…`)
- [ ] Google OAuth Client ID (review usage; restrict to authorized origins/redirect URIs)
- [ ] Gemini API key (C4 — also logged in URLs)
- [ ] Historical Firebase web API key — the obsolete client has been removed; verify the key is rotated or tightly restricted in Google Cloud, then resolve the secret-scanning alert without retaining the literal in documentation.
- [ ] Audit Firebase auth logs and Cloud Run access for abuse once `MOCK_AUTH`/IDOR issues are addressed.
