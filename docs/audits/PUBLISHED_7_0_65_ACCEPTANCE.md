# Published Studio 7.0.65 acceptance checkpoint

Date: 2026-09-25. Base: `8294bd6de9663e4f9cecc86dbd6a64acc3ae36ee`.
Branch: `codex/published-app-acceptance`. This is a bounded follow-up to the completed
maintenance/release work, not a reopened all-files audit. Changes remain local.

## Installed artifact and isolation

The published Windows MSI (228,584,063 bytes) was downloaded and its published SHA-256 verified:
`28c16f73f824bb60129811d3fa5d57f11be227524851514218fa42a6630067b0`.
It upgraded the existing 5.0.1 installation to 7.0.65. The first silent attempt failed with
Windows error 1730 (administrator rights required); the normal UAC-elevated installer succeeded.
The installed program under `C:/Program Files/ARES Robotics Studio` supplied the native baseline.
Its executable/JARs were not replaced with local builds.

All workspaces, recordings, fault fixtures and captures used a disposable data directory under
`build/published-app-acceptance-2026-09-25/`. Normal user data was inventoried separately and not
used for testing. Raw evidence remains there; the [evidence manifest](PUBLISHED_7_0_65_ACCEPTANCE_EVIDENCE.json)
records artifact hashes and scoped JUnit totals. The host was Windows 11 Home 10.0.26200,
Ryzen 7 8845HS (8 cores/16 threads), 64 GB RAM.

## Reproduced defects and local changes

All three issues below were observed with the published installation before their local fixes.

1. **Incorrect health freshness:** constant loop time/battery values disappeared after two seconds
   despite an active producer. The custom NT4 server only queued changed values. Active server
   publications now refresh equal values at most twice per second. A stopped producer stays stale;
   unrelated telemetry and flush calls cannot renew its measurements. Value changes still publish
   immediately, and equal-value refreshes do not invoke change listeners.
2. **Measured summary bottleneck:** saving a long recording stalled telemetry presentation while
   native summary queries occupied the database. Device-pattern classification ran repeatedly over
   millions of samples. Both summary aggregators now classify distinct topics once and join the
   samples by topic, preserving aliases, sample precedence, filtering and exact aggregate inputs.
3. **Failed shutdown cleanup:** after the soak, the 15-second shutdown watchdog halted Studio before
   its simulator was stopped. Owned simulator/build/deploy processes now stop before waiting for
   auto-import cleanup. Import cancellation still joins before ADB/storage dependencies close.
   The orphaned baseline simulator was identified by PID, ancestry and its test snapshot, recorded,
   and cleaned up without terminating other tasks' processes.

ARESLib is staged for the new, unpublished version **19.1.5**. Local consumer validation uses
`19.1.5-rc.acceptance.20260925.1` from `ARESLib-Kotlin/build/release-repository`; no published version
was overwritten. Its source-tree identity is `534a2778c52356641621ceaea71532a63219b8e2`.

## Desktop performance evidence

The baseline used published BioBuzz 1.1.6 and ARESLib 19.1.4. It accumulated **1,800.014 seconds of
connected observation across two connections and a planned restart**, over 2,192.150 seconds wall
time. This is not an uninterrupted 30-minute drive test. The workload combined dashboard display,
recording, keyboard motion, idle periods and local regression builds. Later keyboard requests
encountered the normal focus/arming guard and did not move the robot; command dispatch acknowledgments
alone were not counted as proof of actuation.

The interactive simulator targets **20 ms / 50 Hz**. Its displayed `LoopTimeMs = 20` is simulated
time, not a host computation measurement. Host instrumentation captured 100,000 frames over
2,001.135 seconds; 97,197 active frames remained after the 30-second warm-up filter.

| Host metric | Median | p95 | p99 | Worst observed |
|---|---:|---:|---:|---:|
| Runner work | 0.823 ms | 1.673 ms | 2.313 ms | 53.023 ms |
| Frame start interval | 15.788 ms | 31.429 ms | 31.989 ms | 75.417 ms |
| Pacer wait | 14.935 ms | 30.636 ms | 31.230 ms | 69.426 ms |
| Wake lateness | 7.718 ms | 14.629 ms | 15.481 ms | 56.668 ms |
| Runner-thread allocation | 122,936 B | 204,552 B | 220,848 B | 555,536 B |

Work exceeded 20 ms in 5 selected frames; 21 frame intervals exceeded 40 ms. The complete run rebased
45 wall-clock periods. The mean period was 20.010 ms, while mean work was 0.926 ms. Windows wake-up
timing accounts for substantial jitter; this is not evidence that computation normally takes 20 ms.
Mean runner allocation was 136,300 B/frame (about 6.8 MB/s at 50 Hz), excluding other threads.

The NT4 observer saw 87,072 pose frames, one unobserved sequence number, no sequence regressions or
duplicates, and a worst arrival interval of 311.753 ms. Sequence observations include possible
coalescing and do not identify an internal drop counter. Intentional reconnect downtime is excluded
from the connected duration. Test-control PING/capture timings do not measure rendered frame rate.

Actual Studio JVM sampling (not the jpackage launcher parent) covered 431 samples over 2,154.7 s:
heap used ranged 61.7–224.9 MiB; old generation went from 58.2 to 65.0 MiB, peaking at 68.5 MiB.
GC time increased 5.257 s with 72 full collections. This bounded observation does not establish a
memory leak or prove stability for arbitrary durations. Native/reserved private bytes are not heap.

On the same 3,226,531-row recording, the published metric query took 15.250 s and 16.082 s on a
warm-cache recheck; the changed query took 0.581 s. The published health query exceeded the 30 s
cancellation bound twice; the changed query took 1.033 s. Metric results agree within floating-point
rounding. The standalone query probe uses DuckDB defaults for both versions; Studio enforces its
own memory/thread caps. These are query measurements, not end-to-end UI or controller latency guarantees.

## Inspection-error presentation

With the app closed, one deliberately malformed session-tags row was inserted into the disposable
database, after preserving a backup. The published native Robot Studio showed **readiness unavailable**
with the JSON error, rather than indefinite loading or green readiness. Removing only that fixture
row and restarting recovered readiness. The native check covers fresh-session failure/recovery;
the existing ownership regression covers failure after earlier success and runtime updates.

## Validation and remaining boundary

The new NT4 wire regression failed before the fix and passes after it. Full library tests/API checks,
FTC starter generation/verification/tests/APK build, FRC generation/verification/tests, and focused
Studio health/NT4/pipeline/session-ownership checks passed against the local candidate. The first
full library attempt encountered a Windows `BindException` in the unchanged Limelight proxy test;
the unchanged full retry passed. Summary and lifecycle suites also passed after the local changes.

The checks are ordinary library/app test sources and remain selected by existing changed-part CI.
Shared library changes select consumer checks; release metadata currently selects the full matrix.
The workflow edit only aligns its ARES version with the new unpublished library version.
Repository policy, guidance/link checks, source identity and the maintainability ratchet passed.

The tagless BioBuzz field retains the previously documented collision/localization limitation:
perimeter contact produced about 0.78 m truth-to-EKF drift after initialization. Simulator truth was
not substituted for the estimator. This checkpoint does not certify autonomous localization near
collisions, physical neutralization, electrical behavior, or target-controller timing. Physical
hardware was unavailable. Sensor-to-output latency across physical devices was not measured.

## Native follow-up and disposition

| Journey | Observed result |
|---|---|
| Published BioBuzz import | Verified bundled 1.1.6 copied through setup; generated verification, tests and APK build completed through the dashboard; actual simulator connected. |
| Published BioBuzz interaction | Explicit arm/drive, stop/restart, recording save, field fullscreen/restore and reconnect observed. A later normal start restored driving after the focus guard disarmed it. |
| BioBuzz save/reopen | Reviewed display-name change saved only the authored project identity document. The saved name was read back in the local development follow-up using the same profile. |
| Published Lightbot import | Bundled 3.0.67 copied through setup, reviewed name change saved, generated build/verification completed and actual TeleOp drove/stopped. |
| Published Lightbot reopen | Installed app read back `Lightbot Installer Acceptance`; successful repeat restart and close, with the earlier failed attempt retained below. |
| Native inspection failure | Published app displayed unavailable readiness for the injected failure and recovered after fixture removal/restart. |
| Candidate health | Over 60.009 connected seconds, 120 unchanged loop-time samples and 111 unchanged battery samples arrived; maximum respective receipt gaps were 0.517 and 0.549 s. Dashboard readings stayed visible beyond the two-second freshness limit. |
| Candidate motion/save | With the window focused and explicitly armed, control became ready and keyboard motion was visible; the recording saved and remained listed. |
| Candidate shutdown | Active BioBuzz app/simulator processes exited in 1.19 s. A second local app launch with Lightbot rendered the saved identity and closed in 0.61 s. Neither used the forced-exit watchdog. Runtime snapshots disappeared after Gradle finalization. |

The candidate tests used a separate Gradle development runtime snapshot with the committed fixes
(`db52fd2e2`) and the local library candidate. Only the two disposable robot copies' dependency
manifests were advanced to 19.1.5 for this step; their original published manifests were retained.
This does **not** mean the installed 7.0.65 binaries contain these fixes.

Two additional observations remain explicit follow-ups:

- Reopening the large BioBuzz profile triggered background CSV imports and substantial database
  waits (the UI reported DB p95 as high as 76.4 s during catch-up). Readiness eventually recovered.
  The query optimization improves the measured summaries but does not establish acceptable bulk
  import responsiveness. Isolating metadata/live-control reads from long import/analysis work is
  the next performance investigation supported by this run.
- One installed Lightbot reopen entered a window-close sequence before startup settled, then logged
  `SQLException: Connection was closed` from a late workspace-session read on the UI coroutine.
  The cause of the initial close was not established. Repeating the same installed launch succeeded;
  both subsequent fixed-app launch/close cycles passed. The crash log is retained, and immediate
  close-during-startup deserves a focused lifecycle reproduction. No speculative fix or claim of
  eliminating this event is made.

The test-owned apps, simulators and helpers are stopped, and ports 49329, 5810 and 5002 have no
listeners. Only the proven orphan from the baseline required manual process cleanup; its abandoned
snapshot was removed after verifying no owner remained. Normal user data still has the same 86
files with unchanged lengths and UTC modification times (metadata comparison, not content hashes).
The 7.0.65 installation remains available. No GitHub push or release was performed.

This acceptance pass is closed with local fixes and the above limitations recorded. It is not an
unconditional release approval or a physical robot-readiness certificate. Continue with the two
specific lifecycle/import investigations before claiming the long-session experience is clean;
do not automatically start another broad audit.
