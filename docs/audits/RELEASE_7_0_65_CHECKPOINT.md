# Studio 7.0.65 release checkpoint — 2026-09-23

The user authorized release preparation and publication after the completed
[maintenance checkpoint](STUDIO_MAINTENANCE_CHECKPOINT.md). This supersedes the
earlier campaign's local-only publication constraint for this release; it does not
restart the audit. Source reviewed here is `c8c077222aa91ad564a18fb4e5e31f83b32e0784`,
compared with protected main `9426ee3c86f331910b2ce1656e33c774506a44e1`. The commit
containing this report adds release metadata and the regenerated Lightbot archive.

## Accepted scope

- Preserve the completed campaign's editor ownership, dirty-draft recovery,
  reviewed-save, live-tuning freshness, and SysId lifecycle fixes, with their
  bounded review records and regressions. All 26 changed production Kotlin source
  fingerprints still match their accepted ledger entries. This binding is not a
  new whole-file audit or a claim that every file is defect-free.
- Include the FTC calibration-neutral-hold fix and its operator regressions.
  Lightbot advances from 3.0.66 to 3.0.67 so new standalone imports receive it.
- Advance Studio from 7.0.64 to 7.0.65. ARESLib remains 19.1.4: its source tree did
  not change. FTC/FRC starters remain 19.1.5, XRP remains 3.0.64, and BioBuzz remains
  1.1.6. No CI gate or release protection is weakened.
- Rebuild archives with the canonical deterministic exporter. All four unchanged
  archives are byte-identical to their existing resources. The new Lightbot ZIP
  changes exactly `AresRobot.kt` and `AresTuningOperatorAuditTest.kt`, excluding its
  versioned enclosing directory. Its SHA-256 is
  `3b30bb1e7151b55a59d188c8f5ddac32b720ae691bb551dba952b4df3b234350`.

## Local validation

Windows, JDK 17, released ARESLib 19.1.4, isolated release worktree:

```powershell
./scripts/build-starter-archives.ps1 -OutputDirectory build/release-7-0-65/archives
./ARES-Analytics/gradlew.bat -p ARES-Analytics studioReleaseVerification :app:consumerRoundtripTest --no-parallel --console=plain
./scripts/verify-monorepo-policy.ps1
```

The full release gate passed in 14m 20s, including all three coverage gates,
release alignment, source-size ratchet, and dashboard performance budgets.

| Scope | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: |
| Studio app | 2,764 | 6 | 0 |
| Shared | 37 | 0 | 0 |
| Gateway | 18 | 0 | 0 |
| Generated consumer scenarios | 4 | 0 | 0 |
| Dedicated dashboard smoke | 59 | 0 | 0 |
| Actual Lightbot archive: robot | 178 | 0 | 0 |
| Actual Lightbot archive: simulator | 13 | 0 | 0 |

Scopes overlap; these are not summed as unique tests. Opt-in app tests retain
their normal skips; generated-consumer and dashboard tasks explicitly ran their
selected checks. Generated BioBuzz and generic projects ran actual code generation,
robot/simulator tests and Android packaging. The two recovery scenarios exercised
partial generation, rejection of incomplete outputs, process cancellation and a
successful clean retry, preserving canonical documents and USER-OWNED code.
The new Lightbot archive was extracted and ran `:TeamCode:testDebugUnitTest
:simulator:test` successfully.

## Native Studio smoke test

The actual 7.0.65 Studio window rendered at 1440 x 900 (1424 x 861 client capture),
HWND 922506, PID 45052. It used a disposable home and projects extracted from the
real Lightbot 3.0.67 and BioBuzz 1.1.6 resources. Compilation was already complete;
the launch compilation tasks were up-to-date. No other task's app was terminated.

- Switched Lightbot to BioBuzz and inspected their distinct fields and canonical
  project identities. Edited BioBuzz's display name, reviewed the exact structured
  diff, and saved it through the UI. Reloaded, switched to Lightbot and back, and
  observed the saved value. Hash comparison confirms only the intended BioBuzz
  `.ares/project.json` changed among the original authored documents.
- Ran BioBuzz's **Verify & launch** flow through the UI. The generated project
  completed verification, tests and packaging, and launched its NT4 simulator.
  Started TeleOp, observed live health/field telemetry, and sent/released a brief
  W drive command; the displayed robot moved.
- Expanded the field panel, inspected the BioBuzz field, and returned it to the
  dashboard. Stop returned TeleOp to its waiting state with control disarmed.
- Posted native close to this window. Studio and its owned simulator processes
  (45052, 10536, 32312) exited; ports 49328 and 5810 had no listeners. Both isolated
  runtime snapshots were removed, and the app Gradle task exited successfully.

## Desktop performance evidence

The smoke workload used 12 topics at 100 Hz for 10 simulated seconds: all 12,000
frames persisted and restored, with zero drops. The dedicated baseline comparison
passed against the existing checked-in budgets:

| Metric | Observed | Effective gate |
| --- | ---: | ---: |
| Ingestion | 233,861 frames/s | at least 25,500 frames/s |
| Query p95 | 12.65 ms | at most 100.05 ms |
| Replay load | 17.16 ms | at most 500.08 ms |
| Replay scrub p95 | 24.14 ms | at most 100 ms |
| Heap growth | 5.44 MB | at most 64 MB |

Insert-batch p95 was 20.82 ms and rapid seek was 4.95 ms. These are desktop
dashboard measurements. The GUI's 20 ms simulator loop display is its configured
50 Hz cadence, not a computation-time benchmark. Heap growth is not a per-loop
allocation profile. No target-controller timing, hardware IO, physical mechanisms,
or target sensor-to-output latency was measured. Hardware acceptance remains a
separate checkpoint when controllers are available.

## Evidence and publication boundary

Raw evidence is retained in coordinator
`.codex-validation/agy-audit-coordination/build/release-7-0-65/`: archive/build logs,
source binding, ZIP delta, JUnit summary, and `smoke/` captures 001–023, before/after
document identities, app log and owned-process shutdown results. Detailed generated
consumer evidence is under `ARES-Analytics/app/build/consumer-roundtrip-evidence/`;
the measured performance JSON is under `ARES-Analytics/app/build/reports/dashboard-validation/`.

This report records completed local evidence, not future CI results. Publish only
after the protected PR checks pass and merge, using the attested desktop candidate
whose full Git tree equals protected main. Promote those verified bytes through
the existing release workflow without rebuilding after approval. Preserve unrelated
branches, worktrees and processes. No further broad audit is part of this release.
