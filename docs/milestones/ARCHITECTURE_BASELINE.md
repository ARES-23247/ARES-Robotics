# ARES Pre-Refactor Architecture Baseline

Date: 2026-08-26

This document records the behavioral and build baseline that must remain explainably stable while
ARES is restructured. It is evidence for comparison, not a physical-hardware validation record.

## Scope

- Representative GUI-authored FTC robot: Lightbot in `ARES-FTC`
- Existing FRC season robot in `ARES-FRC`
- Hardware-neutral FTC and FRC starter projects
- ARESLib generation, runtime, hardware, simulator, and API contracts
- ARES Robotics Studio project loading, controller bindings, autonomous actions, and visible startup

The baseline deliberately does not implement Systemcore targets, merge FTC/FRC simulators, move the
repositories, or change robot control behavior.

## Rollback branches and commits

Every repository uses the rollback branch `codex/architecture-baseline`.

| Repository | Baseline commit | Baseline-specific change |
|---|---:|---|
| Workspace metadata | `3443ba2` | Clean-slate architecture review |
| ARESLib-Kotlin | `6a3ef0db` | Intrinsic indicator set/cycle capabilities and generated runtime |
| ARES-FTC | `6b985c0` | Clean Lightbot left/right IDs and color-cycle routine coverage |
| ARES-FRC | `b0029b1` | Prevent verification from pruning tracked FRC log fixtures |
| ARES-Analytics | `9b5782d` | Effective catalog in Autonomous Builder plus real cross-screen characterization |
| ARES-FTC-Starter | `811fd04` | Existing starter baseline, no source change |
| ARES-FRC-Starter | `fef8452` | Existing starter baseline, no source change |

Unrelated pre-existing workspace metadata edits are intentionally excluded from these commits.

## Dependency provenance

Final validation candidate:

```text
org.aresfirst.ares:*:10.0.0-rc.arch-baseline.1
file:///C:/Users/david/dev/robotics/ares/ARESLib-Kotlin/build/release-repository
```

ARESLib was tested and API-checked before publishing this isolated candidate. Every consumer was
then validated with both the exact version and the absolute repository URI. No consumer result in
the final matrix relies on sibling substitution or an ambient `mavenLocal()` artifact.

An initial `10.1.0-rc.arch-baseline.1` candidate was rejected by Studio's release-alignment guard
because Studio 1.7.0 pins ARES 10.0.0. The source was unchanged; the final matrix was rerun using the
aligned `10.0.0-rc.arch-baseline.1` identity above.

## Canonical and generated fingerprints

Each fingerprint is SHA-256 over the sorted sequence of `relative/path<TAB>file-sha256` entries.
Build intermediates outside the named generated ARES root are excluded.

After the full verification matrix, all four generators were run a second time against the exact
candidate above. Every canonical and generated aggregate remained byte-identical to this table.

| Project tree | Files | Aggregate SHA-256 |
|---|---:|---|
| Lightbot FTC canonical `.ares` | 20 | `7d8231c9bf1763dc5ba2f71393e773a09bbab4ec97368d9189821010b8cd753e` |
| Lightbot FTC generated ARES | 27 | `4ff71f90f6eaf31ac4c371476f80130c2294ddb1801d34f7de5a32bea1d30e2c` |
| ARES FRC canonical `.ares` | 8 | `b83ba399cc3eddb4d7e08e22ec3b6bbc4e01f4806360353ead2cd8c7d5f87952` |
| ARES FRC generated ARES | 10 | `32862080cdb257048f5c72e8f6e2beaf7dffeee1d9a1b67e0c55a2c3a70157a5` |
| FTC Starter canonical `.ares` | 7 | `930cbfaa1896ba8b1d2e29f636ef46e858bbc05086177591313b2fb1c6f5613c` |
| FTC Starter generated ARES | 11 | `2935e85a47f3bd85f775259370d15edad91bf68e5a94400963aafd19e8e0606b` |
| FRC Starter canonical `.ares` | 8 | `cea8aace0160dd1a0e63a97e772f6dcbb1674c0796b5ddee3f80dedd1df5829c` |
| FRC Starter generated ARES | 10 | `298bbc0670c50119441e54e954770f23ba28c8bb9bb2a514a80961cb61701efc` |

## Automated verification matrix

| Area | Final command scope | Result | Observed Gradle time |
|---|---|---|---:|
| ARESLib | full `test`, `apiCheck`, isolated publication | Passed | 14 s plus 5 s aligned republish |
| FTC season robot | generation, verification, TeamCode tests, simulator tests, debug APK | Passed | 20 s final aligned run |
| FRC season robot | generation, verification, tests including Dyn4j simulation | Passed | 8 s final aligned run |
| FTC Starter | generation, verification, TeamCode tests, simulator tests, debug APK | Passed | 7 s final aligned run |
| FRC Starter | generation, verification, tests including deterministic simulation | Passed | 5 s final aligned run |
| Studio | shared, gateway, and app tests | Passed | 1 min 54 s final aligned run |

Important characterization added in this milestone:

- Controller Bindings and Autonomous Builder load the same subsystem-derived effective action set.
- Indicator set, cycle-forward, and cycle-backward actions are visible to both consumers.
- TeleOp-only explicit-neutral recovery is not exposed as an autonomous action.

## Visible Studio evidence

The native app was compiled and launched against the exact isolated candidate above. The startup
trace reached `shown`, `opened`, `presented`, and `settled`, with a real visible Compose-owned HWND:

```text
Title: ARES Robotics Studio — Mission Control
Class: SunAwtFrame
Window: 1440 x 900
Native visible: true
Startup settled: alwaysOnTop=false, focused=true, active=true, showing=true
```

The visible journey then:

1. Loaded the Lightbot FTC workspace on the Dashboard.
2. Opened Autonomous → Auto Builder and the existing `Light Practice` routine.
3. Opened the robot-action selector and observed all six intrinsic indicator-light actions:
   left/right set, left/right cycle forward, and left/right cycle backward.
4. Observed the Prism set-pattern action in the same catalog.
5. Selected `Cycle Left light color forward`; Studio represented the action as
   `subsystem.indicator-lights.cycleForward.leftColor` with no missing-action warning.
6. Closed the native window through `WM_CLOSE`; Gradle completed successfully and `jps` reported no
   remaining `com.ares.analytics.MainKt` process.

Local diagnostic captures are in `ARES-Analytics/build/diagnostics/`:

- `architecture-baseline-startup.png`
- `architecture-baseline-autonomous.png`
- `architecture-baseline-light-action-menu.png`
- `architecture-baseline-cycle-selected.png`

Offline NT4 connection failures and the unsigned-in optional Drive destination were observed and
correctly did not prevent local project authoring.

## Known warnings and non-goals

- FTC and FRC use Gradle 8.14.5, satisfying the current Kotlin toolchain's Gradle 8.14.4 minimum.
  Future wrapper upgrades remain compatibility milestones rather than automatic release changes.
- Existing Kotlin compiler warnings remain. They were not introduced or broadly cleaned during the
  behavioral-baseline milestone.
- FRC verification previously allowed production log retention to prune tracked sample logs because
  tests run from the repository root. The test task now disables retention only for tests; production
  retention remains enabled.
- Offline NT4 and Google Drive errors do not invalidate a rendered offline Studio window.
- No physical FTC or FRC hardware was connected. Configuration, compilation, generated tests, and
  simulation cannot claim wiring, direction, bus, device-firmware, or physical safety validation.

## Gate for the first structural refactor

The `project-schema` / `project-model` milestone may begin only after:

1. Regeneration reproduces every generated fingerprint above. **Passed.**
2. The visible Studio acceptance journey is recorded. **Passed.**
3. Every baseline-specific repository is clean after its evidence commit.
4. The implementation commit table in this document matches the actual repository heads.
