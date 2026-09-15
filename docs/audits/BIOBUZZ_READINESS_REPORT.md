# BioBuzz desktop readiness checkpoint

Date: 2026-09-15. Branch: `codex/biobuzz-readiness`, based on `c52d4301`.

The integrated BioBuzz demo passes the selected desktop functional checks. Its real-time
50 Hz pacing does **not** meet the intended 20 ms wall-clock period on this Windows PC.
This is a bounded follow-up to [desktop readiness](DESKTOP_READINESS_REPORT.md), not a new
repository-wide audit or a hardware readiness claim.

## Preserved source and dependency identity

- Imported the frozen BioBuzz feature from `codex/biobuzz-field` at `2014037c` plus its staged
  feature changes, relative to `5fc749c6`. Source patch SHA-256:
  `9f7e7e38b45429dc5c69fa397c8dc7dbadb6562450ccbd68fdc5b02fc6914b97`.
- Kept the audited telemetry subscriber's target epochs, overflow recovery, disconnect
  quarantine, packed poses, and replay precedence when integrating BioBuzz snapshots.
- ARESLib source is unchanged: tree `6d49e143e3a919569c5b3d933b8a8200adeb9a36`.
  All Gradle checks and app launches used immutable candidate `19.0.1-rc.6d49e143e3a9`
  from the prior audit worktree's local release repository. No library republish occurred.
- Corrected bundled BioBuzz archive: `ARES-BIOBUZZ-Example-1.0.2.zip`, SHA-256
  `a46f29f90792fe145f8c5e3510a9830062f92604ab5045e4066078fd254d27b8`.
  The original 1.0.0 and intermediate 1.0.1 bytes remain in local evidence. No version was
  reused for different bytes. The other four starter archives reproduced their existing hashes.
- The original BioBuzz worktree, main checkout's staged guidance changes, and prior audit
  checkpoint were preserved. No push, merge, release, new task, or subagent was used.

## Findings and fixes

| Impact | Finding | Resolution and provenance |
| --- | --- | --- |
| High impact | BioBuzz's simulation tuning still used `kV=12.0`, while the current drive runtime expects normalized duty per m/s. This can saturate outputs at small requested speeds. | Changed the canonical profile to `kV=1.0`, matching its declared 1 m/s maximum. The imported feature carried the old value; the existing independent feedforward-units regression exposed it. The first integrated project failed that check; the corrected fresh export passed. |
| Regression coverage | BioBuzz telemetry must survive dashboard recreation and clear on malformed data, disconnect, replay, and target changes. | Added real NT4/store consumer tests, including overflow/recreation after 5,000 unrelated updates. Audited subscriber behavior remains intact. |
| Regression coverage | Exporter fixtures and source policy did not cover the new fifth template; ordinary FTC CI did not build the BioBuzz overlay. | Updated fixture/export boundary checks, enforce the BioBuzz archive pin, and build the exported generated robot, season simulator, and APK in the existing FTC job. |
| Performance | Simulator sleeps a full 20 ms **after** completing work. Windows wakeups were approximately 31 ms even in an empty loop. | Measured and documented below. Pacing is unchanged in this checkpoint; a library change needs its own versioned candidate and consumer validation. |

## Observed app and robot behavior

Used the actual extracted 1.0.2 example, its generated FTC code, simulated motor IO,
the normal Studio Verify & launch flow, and keyboard input delivered to the rendered app.
An independent NT4 subscriber observed state; it never published controls or staged balls.

- Two native Studio launches rendered usable windows and reopened the selected BioBuzz project.
  Robot Builder recognized the drivetrain, intake, shooter, and TeleOp control documents.
- Verify & launch regenerated, verified, tested, and packaged the robot, then started the
  season simulator. Start driving explicitly started and armed TeleOp.
- Normal translation, rotation, release, and the Space slow-drive modifier worked.
  Simultaneous slow drive and intake input moved the robot while retaining its full inventory.
- Flywheel-only input retained inventory. Holding flywheel plus feeder fired one ball per
  press. Three subsequent deliberate shots reached the red hive through generated motor outputs
  and ballistic simulation. The earlier aiming shot missed and remained a real field ball.
- Three pollen plus three nectar remained below the tip threshold: independently,
  `3*0.055 + 3*0.091 = 0.438 lb`, below `8*0.055 = 0.440 lb`. The hive correctly did not tip.
- Red and blue human-player releases each decreased the corresponding reserve from five to four.
- After Stop, holding drive, intake, flywheel, and feeder keys left the complete game state
  unchanged. Applied intake/flywheel/feeder outputs were zero. Restart restored the initial
  pose, four-ball inventory, flowers, hives, and both five-ball reserves.
- Field Editor loaded the canonical BioBuzz geometry and Push to Sim received an applied
  receipt for `ftc-2026-2027-biobuzz`: six obstacles, 56 game pieces, zero AprilTags.
- Robot/controller jars rebuilt successfully while the same simulator PID remained alive,
  exercising the isolated runtime classpath. Both app windows and the owned simulator exited
  through normal app close; ports 5810, 49623, and 49624 were released.

The nine season tests additionally cover conservation/reset, enable and feeder edges, four-ball
capacity, flower pollen retrieval and nectar blocking, all six minimum mixed tip loads and their
below-threshold neighbors, completed tipping/spills and the opposite cell, swept receiver-height
crossing, ownership of world stepping, invalid controls, expired leases, and idempotent close.
Those tests are distinct from the live keyboard observations. This checkpoint did not repeat
the original feature branch's entire flower-scoring/tipping UI journey or validate a match auto.

## Validation

| Check | Observed result |
| --- | --- |
| Fresh generated BioBuzz project | Generation, verification, 34 TeamCode tests, nine season tests, APK packaging passed. |
| Studio focused integration set | 129 tests passed. |
| Full Studio/shared/gateway suites | App: 2,619 passed, six opt-in skips; shared: 31 passed; gateway: 18 passed. Zero failures/errors. |
| Export, source-policy, CI-scope and result-gate tests | 46 passed before the final policy extension; the final policy suite passed all 14 tests, including the additional BioBuzz pin-corruption regression. |
| Runtime snapshot check | Both controller and TeamCode runtime jars rebuilt with `--rerun-tasks`; running simulator remained alive. |
| App lifecycle | Two visible launches and graceful closes passed. |
| Source policy | Agent guidance, 449 current Markdown documents, source identity, and all five archive pins passed. |

The skipped full-suite tests require explicit packaged/runtime/release fixtures. They are not
counted as passes. This checkpoint ran source Gradle Studio, not a freshly packaged native installer.
Initial tooling runs failed on sandboxed temporary Git fixtures; elevated isolated fixture runs
passed. The first standalone jar-rebuild command omitted `ANDROID_HOME`; the corrected invocation
passed. These environmental attempts are retained alongside the real feedforward failure.

CI now routes `ARES-FTC/biobuzz/**` to the FTC and Studio consumers. Changes to the generic FTC
starter also trigger the FTC job because BioBuzz overlays that starter. Shared library, runtime,
schema, generator, and release inputs retain the full affected-consumer matrix. No CI job was
executed remotely and no unrelated workflow redesign was made.

## Timing evidence and interpretation

Intended interactive simulator budget: one 20 ms physics/control step, nominally 50 Hz in real
time. BioBuzz publishes its complete game snapshot every five simulation steps, nominally 100 ms.
`DesktopSimLauncher.paceFrame()` advances the mock clock by 20 ms, then calls `Thread.sleep(20)`
after work. Consequently, displayed robot loop metrics of 20 ms describe simulation time;
they do not establish a 20 ms wall-clock period or measure CPU execution cost.

| Windows desktop measurement | Mean | p50 | p95 | p99 | Worst |
| --- | --- | --- | --- | --- | --- |
| Active BioBuzz pose arrivals, 12,791 intervals | 30.95 ms | 30.96 ms | 62.11 ms | 62.97 ms | 64.65 ms |
| Complete BioBuzz game snapshots, 5,435 intervals | 154.80 ms | 154.79 ms | 157.16 ms | 158.20 ms | 326.09 ms |

The read-only capture spans about 841 seconds. Active pose statistics exclude deliberate stopped
epochs, retain all intervals within each enabled epoch, and have zero missing pose sequence
numbers. Arrival batching contributes to the ~62 ms tail; these are end-to-end telemetry arrivals,
not robot loop execution measurements. Of the active arrival intervals, 9,777 exceeded 20 ms;
that count is **not** a count of missed controller deadlines.

An independent empty-loop probe isolated timer behavior on this PC:

- Java 17: fixed 20 ms sleep averaged 30.88 ms; sleeping to an absolute deadline averaged 20.01 ms.
- Java 21: fixed 20 ms sleep averaged 31.08 ms; deadline sleep averaged 20.13 ms.
- Deadline scheduling improved average pacing but retained roughly 32–35 ms worst wakeups in the
  small 100-iteration samples. It is evidence for a focused change, not a production performance guarantee.

The running BioBuzz simulator used a Java 21 JBR, recorded in the process manifest. The separate
timer probe used the installed Java 17 and Microsoft Java 21 runtimes. Neither result is target
controller evidence. Full-loop CPU cost, allocation rate, sensor-to-output latency, and physical
controller missed deadlines were not measured here. Existing host runtime baselines remain in
the prior readiness report; do not substitute telemetry latency for those measurements.

## Remaining risks and specific next actions

1. **Measured pacing problem:** a focused follow-up should replace sleep-after-work with deadline
   pacing while preserving fixed physics steps, RobotClock semantics, leases, and safe interruption.
   Validate its new library candidate with the same consumers and compare wall-clock distributions.
   Consider a separately explicit unpaced batch-verification mode only if needed; do not silently
   accelerate the interactive robot clock.
2. **Estimator versus truth:** driving into the perimeter produced roughly 0.73 m odometry and
   0.72 m estimator divergence; the capture also includes up to 1.2 m during startup/reset.
   The tagless field supplies no absolute tag correction, and encoder motion can diverge under
   contact/slip. Truth was never substituted into the estimator. Do not treat this TeleOp demo as
   evidence of collision-tolerant autonomous localization. A known no-contact trajectory and a
   contact/slip case are the appropriate next targeted estimator check if autonomous use is planned.
3. **Hardware:** ball friction, restitution, launcher calibration, real hive detent behavior,
   camera/encoder freshness, physical stopping, and controller timing remain unverified.
4. **Deferred small cleanup:** normal parent shutdown left a 595,804-byte simulator classpath
   snapshot after its PID exited. The owned copy was removed during housekeeping; the underlying
   parent/child finalizer behavior remains a separate small resource issue. Desktop snapshots cleaned up.
   The imported XRP publisher also contains an unused duplicate `keyboardScale` local; no behavior
   depends on it, so cosmetic cleanup was deferred.

## Reproduction and restart record

Local evidence root, relative to this worktree: `ARES-Analytics/build/biobuzz-readiness/`.
The extracted project is `projects/ARES-BIOBUZZ-Example-1.0.2/`; captures, command logs, failed
attempts, XML results, NT4 observations, timing probe sources/results, and exact process identities
are retained. [The evidence manifest](BIOBUZZ_READINESS_EVIDENCE.json) records hashes and the
reviewed source inventory. Build-output evidence remains local and is not part of a release.

Use the same immutable repository and candidate arguments for both builds and app launch:

```powershell
# From the extracted BioBuzz project, with the installed Android SDK in ANDROID_HOME:
.\gradlew.bat generateAresProject :TeamCode:verifyAresProject `
  :TeamCode:testDebugUnitTest :simulator:test :TeamCode:assembleDebug `
  -ParesVersion=19.0.1-rc.6d49e143e3a9 `
  -ParesRepository=file:///C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/audit-pass191/ARESLib-Kotlin/build/release-repository `
  --no-parallel --max-workers=1 --console=plain

# From ARES-Analytics, preserving other app processes and the dedicated evidence home:
.\gradlew.bat :app:run -PskipKill `
  -ParesIsolatedDesktopHome=build/biobuzz-readiness/home `
  -ParesVersion=19.0.1-rc.6d49e143e3a9 `
  -ParesRepository=file:///C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/audit-pass191/ARESLib-Kotlin/build/release-repository
```

The saved project reopens in Studio. Select Local Sim, Verify & launch, then Start driving.
Use the [BioBuzz guide](../../ARES-FTC/biobuzz/docs/BIOBUZZ.md) for controls and model assumptions.
This checkpoint stops here. The old every-file audit stays paused; no automatic broad pass follows.
