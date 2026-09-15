# Library API size gate and component extraction audit

Pass 274. Local source commit `65542ea9ede24187a75e44bcf28c910950fc7a70`; ARESLib tree
`f570536575d85fafd974039512c60474ebfd6ff5`; isolated candidate `17.0.52-rc.f570536575d8`.

## Confirmed issue and fix

The library registered `verifyAresLibSourceFileSizes` but attached it only to root tasks named
`apiCheck`. The normal command selected subproject API tasks; no matching root aggregate task
existed. A baseline dry run omitted the guard, and explicitly running it exposed 19 production
files above the existing limits. Earlier passing API snapshots and functional test results remain
valid evidence for their scopes, but do not prove that the source-size policy was enforced.

The guard now attaches to every project's API check. Its declared inputs and execution share a
Gradle file tree that excludes build/cache output, replacing a full recursive walk followed by
filtering. No ceiling was raised. A real normal API invocation rejects a temporary 501-line
production source; the corresponding oversized generated build file is excluded. Both fixtures
were removed before the source commit. Full candidate API validation executes and passes the guard.

## Cohesive extractions

The changes move callback/path ownership, executor timing/failure policy, trajectory sampling,
log streams/dashboard presentation, primitive analog timing, covariance validation, registry
telemetry/topology classification, template tuning, schema safety/control validation, generator
options/tuning/actions/assertions, FTC IMU caching, motor/calibration telemetry, and autonomous
selection into 23 separate components. Arithmetic, serialized field/default order, and public
class names remain unchanged. Public top-level FTC pose/terminal functions remain in their
original JVM file facade. The IMU's identical absent/invalid fallback assignments now share one
method; the sample buffer remains allocated once per robot. Inherited trailing whitespace was
removed from five newly extracted files before freezing.

| Original file | Before | After | Existing ceiling |
| --- | ---: | ---: | ---: |
| `AresProjectCodegenCli.kt` | 568 | 471 | 567 |
| `SubsystemControllerRenderer.kt` | 651 | 584 | 617 |
| `SubsystemGeneratedTestRenderer.kt` | 515 | 467 | 514 |
| `SubsystemKotlinGenerator.kt` | 672 | 480 | 661 |
| `HardwareRegistry.kt` | 540 | 495 | 500 |
| `ARESDataLogger.kt` | 654 | 630 | 638 |
| `LogManagerServer.kt` | 537 | 301 | 525 |
| `VisionMahalanobisFilter.kt` | 504 | 481 | 500 |
| `TrajectoryPlanning.kt` | 589 | 426 | 500 |
| `Task.kt` | 649 | 225 | 525 |
| `TaskExecutor.kt` | 553 | 500 | 500 |
| `SubsystemTemplates.kt` | 832 | 730 | 755 |
| `AresGamepad.kt` | 525 | 509 | 515 |
| `FtcBaseRobot.kt` | 612 | 563 | 574 |
| `FtcMecanumRobot.kt` | 517 | 493 | 504 |
| `FtcMecanumCalibrationController.kt` | 694 | 565 | 566 |
| `FtcGeneratedAutonomousOpMode.kt` | 609 | 543 | 601 |
| `SubsystemDocument.kt` | 710 | 595 | 609 |
| `SubsystemValidation.kt` | 572 | 457 | 500 |

Registration still stages ordered entries under the registry monitor and publishes the telemetry
snapshot after rebuilding the lifecycle snapshot. Publishing retains a single array and atomic,
exactly representable heartbeat sequence. Close clears registrations and resets the sequence at
the original lifecycle points. The existing heartbeat-boundary test now reaches the relocated
counter; its assertions are unchanged. Polling/thread ownership remains in the registry.

Calibration sample buffers moved with their validity/time ownership. The controller still owns
enable tokens, leases, neutral handshakes, mode/routine transitions, current/stall safety, and
output writes. Telemetry reuses captured SysId data and retains existing diagnostics, topic order,
freshness checks, and immediate calibration publication. Broader prior partial review scopes
remain partial; moving a component does not certify all behavior of its former containing file.

## Redundant calibration output

Removed ten tracked `frc-hardware/backups/swerve_offsets_2026-08-*.json` files. They were identical
(SHA-256 `1e5d97f4232796933c102cd089d9820b6eb6038600dbbb49a6ac9f9bafbb57ef`) and contained
the current save/load test's four CANcoder-rotation values: `0.1234`, `0.5678`, `-0.4321`,
`-0.8765`. No tracked explicit path references or source/resource packaging inputs consumed them.
Normal offset loading ignores backups; explicit recovery can scan them. Their exact creation
history is not asserted. Copies/hashes remain in the local evidence directory. Five persistence
tests pass using temporary storage without recreating source-tree output. Narrow ignore rules
cover this module's backup directory and runtime-offset JSON.

## Validation

- Focused batches passed: sequencer/persistence 232, trajectory/schema 136, codegen/schema 156,
  logging/gamepad/vision 381, and registry/templates/FTC 824. These overlap and are not summed.
- The final candidate passed **2,968 library tests in 465 suites**, with no failures, errors, or
  skips; every prior suite/test identity was preserved. All **13 API snapshots** match compiled
  output, normalizing only platform line endings. The six previously pending generated API files
  are accounted as generated surface baselines; this does not complete their source-code reviews.
- FTC TeamCode/simulator: 173/13; FRC: 306; FTC starter TeamCode/simulator: 16/1; FRC starter: 206.
  **715 consumer tests**, no failures/errors/skips. Generated-project verification and FTC APK
  assembly passed against the same candidate, for **3,683 total passing tests**.
- Generator characterization retained its existing full-artifact hashes for every supported
  FTC/FRC subsystem template. No expected API or generated-artifact baselines were rewritten.
- The registry's existing desktop allocation probe observed **896 bytes across 10,000 warmed
  publication passes with 32 aliases**, under its 4,096-byte bound. This is a narrow desktop probe,
  not physical loop timing or a whole-robot zero-allocation guarantee.
- The 410 candidate artifact hashes remain unchanged after consumer validation. Prior candidate
  manifests are checked separately; no prior coordinate was overwritten.

Studio's normal `:shared:test :gateway:test :app:test` invocation fails at
`verifyReleaseVersionAlignment`: the existing distribution workflow does not match canonical
ARES version `17.0.52`. All three test-source compilation tasks passed. Compilation is not Studio
test execution or UI evidence. Archive/workflow migration remains unapproved; no gate exclusion,
archive rewrite, push, merge, remote publication, GUI, or physical-hardware run was performed.
The repository policy's existing missing-starter-archive limitation remains separate from the
successful library API/size validation. Physical Lightbot dimensions remain unresolved.

## Evidence and coverage boundaries

Local evidence is under `ARESLib-Kotlin/build/audit-pass274-verified-evidence/`: baseline/wired API
plans, original ratchet failure, extraction body hashes, before/after size table, removed-backup
manifest/copies, real negative/positive fixture logs, focused XML snapshots, final library and
consumer logs/XML, API verification, source/candidate manifests, repository-policy output, and
the final coverage inventory. Generated and declarative file reviews are distinguished from
executable behavior; existing partial reviews and release-validation deferrals are preserved.
The audit goal remains active for the rest of the monorepo.
