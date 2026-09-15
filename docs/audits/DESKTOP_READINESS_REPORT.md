# Desktop robot-readiness follow-up

**Complete for the selected hardware-free scope, 2026-09-15.** Actual Studio windows, fresh Lightbot
creation/build, live simulated drive controls, shutdown/reopen and the existing dashboard baseline
passed. No new high-impact behavior defect was established. This follow-up changes documentation
and evidence only. Prior fixes and checks remain in the [readiness report](ROBOT_READINESS_REPORT.md)
and [alignment report](RELEASE_ALIGNMENT_REPORT.md). Their live-window, native-dialog, fresh-Lightbot
and dashboard-baseline gaps are now closed for the scenarios below.

## Verified behavior

- Started a visible, rendered Studio window with a dedicated isolated home. Verified its exact native
  window, settled startup diagnostics and screenshots. Cloud synchronization stayed off.
- Selected Explore Lightbot through onboarding, selected an owned directory through the real modal
  folder chooser, and created the bundled example through the production project service. Archive
  SHA-256: `8c34305bfdc9aa846014b3c9887cf53fb3316a125221582c4956331adac32b5d`. Initial local history:
  `9d7b1b486a5ca27bd6b513e6b035ec17ab82b7a8`. Project status remained clean.
- Clicked Studio Build. Generated-project verification, unit/simulator tests and debug APK packaging
  passed in 55 seconds: **174 TeamCode and 13 simulator tests**, no failures or skips. The durable
  verification report records the exact command and candidate provenance. It correctly leaves
  physical checks unverified. No deployment occurred.
- Launched Local Sim through Studio. `ARESMecanumTeleOp` completed INIT/START and reached Control
  ready with keyboard arming. Holding/releasing W moved ground truth **0.6921 m**. All four reported
  motor powers returned to zero. Packed poses showed **zero further displacement from one second
  after release** until the next command. Disarming during a second held W command also settled
  with zero subsequent displacement. Stop changed the OpMode state to **DISABLED**, with all four
  motor powers zero.
- Rebuilt `:FtcRobotController:bundleLibCompileToJarDebug --rerun-tasks` while the simulator remained
  alive. It passed in 14 seconds; the same simulator PID continued using its isolated classpath.
- Graceful Studio close disposed the window and terminated its simulator. Both app run commands
  returned exit 0. A second launch restored the Lightbot selection and rendered an offline dashboard,
  then closed through native WM_CLOSE. Both Studio runtime snapshots were removed automatically.
- The opt-in native chooser fixture passed selection and cancellation cycles, checked visible native
  windows and cleared dialog callbacks. Both rendered captures were inspected. Dashboard smoke **59**,
  baseline **1**, and native chooser **1** checks passed without skips.

The read-only NT4 observer never published controls. Existing packed-frame truth and estimator fields
remain distinct. These observations establish simulated behavior, not applied physical motor output
or a calibrated plant. Earlier generated multi-mechanism, cancellation, freshness, partial-failure
and independent-math scenarios are unchanged and retain their recorded evidence; they were not
redundantly rerun here.

## Performance and limits

The existing smoke workload represents 10 seconds at 100 Hz across 12 topics, batched into database
ingestion. It persisted and restored **12,000 / 12,000** frames with no drops. Measurements below are
Windows desktop service results with the live app and simulator closed. Thresholds are the checked-in
baseline plus allowed regression fractions: loose CI regression limits, not robot budgets or UI SLAs.

| Desktop metric | Observed | Existing acceptance |
|---|---:|---:|
| Ingestion | 237,952 frames/s | At least 25,500 frames/s |
| Query p95 | 16.12 ms | At most 100.05 ms |
| Replay load | 18.33 ms | At most 500.08 ms |
| Replay scrub p95 | 16.36 ms | At most 100 ms |
| Retained heap growth | 5.76 MB | At most 64 MB |

Query p95 uses only three iterations and is a small-sample smoke statistic. Retained heap growth is
not total allocation volume. No demonstrated dashboard bottleneck required production optimization.
Prior whole-loop execution, allocation and simulated sensor-to-output baselines remain in the
readiness report: FTC/FRC/XRP execution fit 10–20/20/20 ms budgets, while desktop scheduling did not
establish steady 20 ms pacing.

The live observer received **3,684 distinct packed pose frames over 114.45 seconds**, with no sequence
gaps. Arrival intervals: p50 **31.06 ms**, p95 **62.08 ms**, p99 **62.95 ms**, worst **64.62 ms**;
2,981 intervals exceeded 20 ms. This includes Studio ingestion, a second subscriber, a controller
rebuild, transport batching and desktop scheduling. It does **not** establish 50 Hz end-to-end
delivery. Arrival intervals cannot isolate simulator execution or count target deadline misses.
Attribution requires a controlled cadence/profile comparison; safety leases must not be weakened
to hide these delays. No robot-controller performance claim follows from these desktop measurements.

## Deferred findings and remaining checkpoints

- **Lower priority, disk cleanup:** Studio process-tree shutdown left a simulator classpath snapshot
  with 113 files / 3,083,009 bytes. Gradle has a finalizer; Studio terminates the owned process tree.
  The outcome is consistent with interruption before that finalizer runs. Both processes exited;
  no live owner remained. The exact directory was removed after verifying path and process ownership.
  This existed at this follow-up's starting commit; its earlier origin was not established. A focused
  maintenance change should test repeated start/stop and recovery without deleting another run's
  snapshot. No runtime/archive change was made to clean up this disposable directory.
- **Lower priority, wording:** onboarding says “built with ARES 3.0.54”; that is the Lightbot archive
  version, while the canonical library is 19.0.1. Provenance and build resolution are correct. Defer
  the label correction to ordinary Studio maintenance.
- **Unverified hardware:** Control Hub/roboRIO/Pico applied-output neutralization, physical feedback
  freshness, sensor/bus latency, calibration, sustained loop deadlines and heap/GC behavior. The
  existing physical-controller checklist is the next robot acceptance checkpoint when hardware
  becomes available. No hardware, public artifact availability, signed installer or protected remote
  release validation is claimed. This run adds no fresh Lightbot portable-export check.

## Reproduction and evidence

Source: `220d1557db4028911a7d6ab5fbf1b9fcfc0739c5`, isolated branch `codex/robot-loop-math-audit`.
Candidate `19.0.1-rc.6d49e143e3a9` and all **410** artifact hashes remain unchanged; library tree
`6d49e143e3a919569c5b3d933b8a8200adeb9a36`. Historical checkpoint JSON files were preserved.
The [durable manifest](checkpoints/desktop-readiness.json) binds logs, screenshots, XML, project
verification, observations and summary scripts. Full local evidence is under
`ARES-Analytics/build/desktop-readiness/` in the isolated worktree.

Compile/run from `ARES-Analytics` with identical candidate version/repository properties. Use
`:app:run -PskipKill -ParesIsolatedDesktopHome=<dedicated-home>` and the repository desktop tester
skill's dedicated loopback input/capture controls. Create a fresh owned Lightbot directory through
the UI, Build, launch Local Sim, Start driving, hold/release W, disarm, Stop, then close Studio.
The observer uses Python `websockets` and task-local `msgpack` 1.2.2; exact source is hash-bound.
Its first attempt used the wrong subprotocol and received HTTP 404; switching this test helper to
the source-defined `v4.1.networktables.first.wpi.edu` resolved it. Deep local paths require Git's
per-command `-c core.longpaths=true` for a correct status check; global settings were not changed.

Run `:app:dashboardPerformanceBaseline` and focused `com.ares.analytics.ui.AresFileChooserNativeTest`
through `:app:test`, with `ARES_CHOOSER_NATIVE_TEST=true`, one worker and the candidate properties.
The native test needs a visible Windows desktop. This run reused the 32 MB test-startup-heap init
script. The baseline already runs in the dashboard changed-part scope in
[Analytics Validation](../../.github/workflows/analytics-validation.yml). Shared runtime/generator
consumer routing is preserved as recorded in the readiness report. No CI redesign or bypass was
needed. Documentation/evidence changes receive source-policy and link checks.

All owned application, simulator and check processes are terminal. Unrelated work and processes
were preserved. This checkpoint is closed; no automatic broad audit or release follows it.
