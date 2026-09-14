# Pose handoff and simulator signal audit

Pass 275. Source commit `11d5aaf2b332fa9f6e51940b5bd3df43595e7610`, library tree `caed526a0608c82073b8b8af734c69b842e77304`,
local candidate `18.0.0-rc.caed526a0608`. This shared-contract integration checkpoint covers
20 changed source, test, API, documentation, and release-identity files.

## Confirmed behavior and fixes

- The simulator output registry joined subsystem and actuator IDs with `/`. Distinct opaque
  pairs could address the same signal. A structured pair key now preserves identity.
- The process-global registry strongly retained every registered signal cell and its key.
  It now stores weak references, prunes collected entries during registry operations, and
  conditionally removes queued references so late notifications cannot remove replacements.
  Models and IO retain live handles. Reset neutralizes values while preserving those handles:
  the desktop launcher constructs its field model before resetting and creating robot IO.
- TeleOp read alliance/validity before dispatching an alliance action, then read the pose after
  synchronous observers had run. A clearing observer changed the restored pose to `(0, 0)`.
  PoseStorage now publishes a single immutable pose/alliance snapshot, which TeleOp reads once
  at START and retains through callbacks. Save rejects non-finite x/y/raw heading and clears an
  older handoff on invalid input; clear no longer allocates a replacement pose.
- Template ownership conversion recreated simulation metadata and discarded the intake's
  conveyor interaction, motor trigger, and threshold. Copying existing metadata preserves
  those values for declarative and editable-starter ownership across FTC, FRC, and XRP.

The pose-storage change intentionally removes the separate public pose/alliance/validity
properties. Writers use `save(pose, alliance)`; readers use nullable `snapshot`. All source
callers and affected fixtures were migrated. The reviewed core API delta contains only this
replacement and snapshot getters. Canonical ARES/FTC/FRC starter versions are 18.0.0; these are
local candidate identities, not published releases. Storage remains process-local, without
automatic expiry or robot-identity enforcement.

## Regression evidence

The registry baseline had two failures in six cases (key alias and retained signal). Both
template ownership cases failed before the fix. The original pose baseline and first fixed
run instead failed during Mockito setup; those failures are **not defect evidence**. The fixture
now captures the mocked store before stubbing. Against the original runtime, its corrected
baseline fails with the actual zero-pose restoration mismatch; all six lifecycle cases pass
against the fix, and all 23 selected FTC fixture cases pass together.

New coverage exercises concurrent registration and pose publication, late reference-queue
notifications, abandoned-signal collection, non-finite inputs, immutable snapshot retention,
allocation bounds, and a real Dyn4j field-model binding constructed before registry reset.
The field test verifies neutral output does not capture a piece and later applied output does.

The intake fix changes each FTC/FRC generated-artifact manifest by exactly one 64-character
registry document fingerprint. Full before/after manifests have identical lengths and all other
bytes match. Only those two reviewed expected hashes changed; temporary probe code was removed.
All 79 codegen tests pass, preserving prior case identities.

## Integration validation

- **2,985 library tests**, 468 suites, zero failures/errors/skips; all prior 465 suites and their
  case identities remain. All **13 API checks** and the unchanged source-size guard pass.
- FTC TeamCode/simulator: **174/13**; FRC: **306**; FTC starter TeamCode/simulator: **16/1**;
  FRC starter: **206**. All **716 consumer tests** pass against the same candidate, with prior
  case identities retained. Generated-project verification and FTC APK assembly also pass.
- Total: **3,701 passing tests**. The local candidate's 410 artifact files retain their hashes.
- Desktop allocation probes: Sim signal publication: 0 bytes / 10,000 warmed writes (desktop JVM); PoseStorage clear: 0 bytes / 10,000 warmed calls (desktop JVM). These measure only the named warmed operations,
  not Android ART, RoboRIO, whole-loop timing, or physical hardware behavior.

Studio's normal shared/gateway/app test invocation fails at the existing release-version
alignment gate. All three test-source compilation tasks pass; this is compilation evidence,
not executed Studio tests or rendered UI evidence. Archive/workflow migration remains
unapproved, and repository policy still requires the missing current FTC starter archive.
No gate bypass, archive rewrite, push, merge, remote publication, UI launch, or hardware run
was performed. Lightbot physical dimensions remain unresolved.

## Coverage and continuing method

Whole-file review closes the two small registry/storage production files and the reviewed
regression fixtures. The broader TeleOp lifecycle, generated-autonomous implementation, and
template factories retain partial review status. Existing release-validation deferrals remain
open; passing a suite does not complete every source file exercised by it.

Continue with coherent batches of roughly **20-40 related files**. Use focused tests while
fixing defects, affected-module suites at batch closure, and dependency-ordered consumer checks
at shared API, math, safety, or generated-code integration checkpoints. Consolidate versioning
and reporting at those checkpoints; do not repeat successful broad checks for unchanged files.
The next batch contains 23 code-generation pipeline, ownership/writer, and associated test files.

Evidence is in `ARESLib-Kotlin/build/audit-pass275-verified-evidence/`: corrected and rejected
baseline logs/XML, focused results, manifest byte comparisons, API diff, final library/consumer
logs/XML, source/artifact hashes, policy results, and the coverage inventory. `next-batch.json`
records planned scope without claiming those files reviewed. The monorepo audit remains active.
