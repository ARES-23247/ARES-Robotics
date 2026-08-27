# Cycle 4 — Safe, evidence-backed project verification

## Objective

Make the novice-facing Robot Studio Build action truthful and simulator-first: verify generated ownership, run tests, and package the selected project without silently deploying to physical hardware.

## User-visible outcome

- The execution toolbar now says **Verify & build** instead of **Build & Deploy**.
- Its tooltip explains that verification, tests, and packaging run without deployment.
- The selected target reports **Online** or **Offline** in text as well as color.
- Robot Studio retains a typed running, passed, failed, or canceled result for the matching project path and league. A result from another workspace is ignored.
- Failed and canceled results give an explicit next action. Existing generated source alone remains **Needs action** because it is not proof of a current build.
- The Build terminal uses the same visible **Verify & build** language and prevents a second click while verification is running.

## Safety and ownership decisions

- The former successful-FTC-build path automatically called ADB connect/install. That hidden physical side effect was removed.
- FTC verification now runs generated-project verification, TeamCode unit tests, simulator tests, and debug APK assembly.
- FRC verification now runs generated-project verification, tests, and the normal build.
- Neither command connects to a robot, installs an APK, deploys FRC code, starts simulation, changes canonical project files, or commands hardware.
- Physical deployment remains a separate supervised team procedure. No physical-robot validation is claimed.

## Verification evidence

All ARES-dependent commands used the isolated repository:

```text
-ParesRepository=file:///C:/Users/david/dev/robotics/ares/ARESLib-Kotlin/build/release-repository
```

- Focused `ProcessManagerServiceTest` and `RobotStudioModelTest`: passed, including exact command contents, no deploy/install/ADB arguments, success, failure, cancellation, process cleanup, and cross-workspace result isolation.
- Exact FTC command on current `origin/master`: passed (`verifyAresProject`, TeamCode tests, simulator tests, debug APK assembly; 65 tasks).
- Exact FRC command on current `origin/master`: passed (`verifyAresProject`, tests, build; 17 tasks).
- Full Analytics app tests: 421 tests, 0 failures/errors, 2 intentional skips.
- Trimmed distributable project loading: passed for one canonical routine and one subsystem.
- Dashboard smoke/performance baseline: passed with 12,000 expected/persisted frames, zero drops, exact Parquet restore, 12.571 ms query p95, and no budget violations.
- `git diff --check`: passed.

## Delivery and limitations

Implementation branch: `codex/studio-safe-build-v4`. Protected PR delivery is pending final diff review.

- The Windows desktop remains locked, so a live visual walkthrough of the updated toolbar is still pending. Static Compose review and automated contrast/accessibility tests do not substitute for that walkthrough.
- This cycle deliberately does not add deployment UI. A future deploy workflow needs an explicit target, physical-side-effect warning, confirmation, permission boundary, and supervised team procedure.
