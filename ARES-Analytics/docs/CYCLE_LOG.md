# ARES Product Improvement Cycle Log

This log records evidence for simulator-first product cycles. It is not a claim that desktop or
simulator verification proves physical robot safety.

## Cycle 1 — Robot Academy and Robot Studio truthfulness

### Objective

Give a novice a discoverable first mission and one project-wide place to understand what is ready,
what is missing, what is optional, and what requires code or supervised physical validation.

### User-visible outcome

- Robot Academy now has six role paths: New student, Driver/operator, Robot builder, Autonomous
  developer, Data analyst, and Mentor.
- Lessons have prerequisites, recommended next work, resumable active state, durable versioned
  checkpoints, and persistent contextual coaching.
- The First Mission records only observable app facts for Local Sim selection, simulator process
  state, and local NT4 connectivity. Interpretation and safety decisions remain explicit human
  checkpoints.
- Robot Studio presents the twelve project stages in one guided view, names the exact canonical
  evidence it found, routes to the existing specialist screen, and shows status with words and
  icons in addition to color.
- FTC generated subsystems are installed in the season runtime instead of merely compiling.
- Drivebase choices are filtered by league. Differential and custom templates are clearly marked
  `CODE REQUIRED` and cannot be saved as complete no-code configurations.
- Generated recovery and calibration capabilities use one-shot Redux requests, fail-closed health
  gates, successful neutral recovery, and a neutral hold until a later explicit target command.
- Terminal output uses an explicit semantic foreground, including readable dark-theme handling for
  ANSI black.

### Repositories and ownership

- **ARESLib-Kotlin:** shared generated subsystem actions, safety sequencing, startup rollback, and
  cross-platform drivebase validation.
- **ARES-FTC:** generated subsystem lifecycle installation and simulator parity tests.
- **ARES-Analytics:** Academy, Robot Studio/readiness evidence, league-aware drivebase UX, nested
  local validation-repository propagation, terminal contrast, tests, and teaching documentation.
- **ARES-FRC:** no source change in this cycle; full consumer validation was still required and run.

User-owned subsystem Kotlin remains user-owned. The generator does not scan arbitrary Kotlin,
overwrite unknown source, or silently replace generated starters. Existing specialist builders
remain the source of editing behavior; Robot Studio is an evidence-and-routing layer.

### Verification evidence

All consumer builds used:

```text
-ParesRepository=file:///C:/Users/david/dev/robotics/ares/ARESLib-Kotlin/build/release-repository
```

No `mavenLocal()` fallback was used.

- ARESLib focused generator/capability/registry tests: passed.
- ARESLib full test suite: passed.
- ARESLib `apiCheck`: passed after intentional API baseline review.
- ARESLib `publishReleaseValidation`: passed; 5.0.0 artifacts were present in the isolated
  `org.aresfirst.ares` repository.
- FTC generation and generated-project verification: passed.
- FTC TeamCode and simulator: 101 tests, 0 failures; simulator compile and Android debug assembly
  passed.
- FRC generation, verification, tests, coverage, and build: 95 tests, 0 failures.
- Analytics focused Academy/Studio/drivebase/process tests: 57 tests, 0 failures.
- Analytics full repository tests: app 409, gateway 15, shared 13; 0 failures, 2 intentional app
  skips.
- Dashboard smoke: 12,000 expected and persisted frames, 0 drop, successful Parquet round-trip,
  and no performance violations.
- Trimmed packaged-runtime project loading: passed for canonical routine and subsystem documents.
- Visual walkthrough: normal theme, colorblind + high contrast + large text together, Robot Studio,
  Academy, Local Sim launch, NT4 connection, and automatic 1/5 → 3/5 First Mission progress.
- Release MSI bytecode shrinking was disabled because the desktop application intentionally uses
  reflective/platform-specific DuckDB, Ktor, JNA, and LWJGL entry points. The executable jlink
  runtime remains validated. MSI construction then stopped at the expected protected production
  OAuth client/broker configuration gate; no placeholder credential was embedded.

### Delivery

Protected pull requests were opened in dependency order, passed their required checks, and were
merged to `master`:

- ARESLib-Kotlin: [PR #25](https://github.com/ARES-23247/ARESLib-Kotlin/pull/25)
- ARES-FTC: [PR #25](https://github.com/ARES-23247/ARES-FTC/pull/25)
- ARES-Analytics: [PR #38](https://github.com/ARES-23247/ARES-Analytics/pull/38)

### Remaining limitations after Cycle 1

- Production MSI packaging requires the protected Google Desktop OAuth client ID and HTTPS broker
  URL in the release workflow. This local shell intentionally did not have them.
- Robot Studio reports canonical evidence and routes work, but project scaffolding and several
  specialist builder flows still require further novice-focused refinement.
- AI remains proposal-only. Builder form assistance must continue through structured validation,
  diff, undo, and explicit review before it can be called complete.
- No physical robot was available. Hardware-in-the-loop validation remains required for generated
  IO, neutral recovery, calibration/homing, current behavior, and real actuator direction.

## Cycle 2 — Guided run evidence review

### Objective

Give a novice one read-only route from selecting an imported run to understanding what was
measured, what remains a hypothesis, and what safe tool to open next—without starting from SQL or
a comparison spreadsheet.

### User-visible outcome

- **Analysis → Guided Run Review** is now the default Analysis destination; advanced Run History
  remains available through progressive disclosure.
- Runs are listed and analyzed only when team, season, and robot identity exactly match the active
  workspace.
- The review shows source provenance, decoder and SHA-256 when available, persisted timestamp
  range, units, historical freshness, and an explicit qualitative confidence explanation.
- Measured threshold evidence is visually and structurally separate from possible causes and safe
  verification steps. Finding timestamps are shown in seconds.
- Recent-run baselines are restricted to the same team, season, and robot; cross-workspace data can
  neither appear in the picker nor become a comparison baseline.
- Suggested actions only navigate to existing replay, import, tuning, Academy, or advanced-history
  tools. The review never writes tuning, source, or hardware output.
- Evidence export uses the existing checked atomic-file path and preserves the original session.
- Robot Studio and the Academy comparison lesson route to the guided review.

### Safety and ownership decisions

- Existing deterministic diagnostic, advanced-analytics, driver-review, import-archive, replay,
  and tuning services remain the owning implementations. Guided Run Review composes them rather
  than inventing a second analyzer.
- Source identity is reported as incomplete or ambiguous instead of selecting a file silently.
- Confidence is bounded at **Moderate evidence**. Historical desktop evidence is never described
  as causal proof, live freshness, or physical safety evidence.
- Raw tables and SQL remain in the existing advanced tools; they are not removed.

### Verification evidence

All Gradle validation used:

```text
-ParesRepository=file:///C:/Users/david/dev/robotics/ares/ARESLib-Kotlin/build/release-repository
```

- Focused analytics, workspace-race, navigation, and Robot Studio tests: 17 tests, 0 failures.
- Full Analytics repository tests: app 415, gateway 15, shared 13; 0 failures, 2 intentional app
  skips.
- Dashboard smoke and performance baseline: passed with 12,000 expected/persisted frames, zero
  drop, 12 ms query p95, exact Parquet restore, and no budget violations.
- Trimmed distributable project loading: passed for one routine and one subsystem document.
- The interactive app launched and reached the real selected workspace. The automated screenshot
  session was stopped at the Windows PIN screen; no PIN was entered, so a post-unlock visual pass
  remains outstanding. Static review confirmed responsive `LazyColumn`/`FlowRow` layout, semantic
  headings, text-labelled actions, semantic accent foregrounds, and no color-only status.
- No physical robot was used or required for this desktop, persistence, and analysis slice.

### Delivery

Protected [PR #39](https://github.com/ARES-23247/ARES-Analytics/pull/39) passed Dashboard Validation,
CodeQL Java/Kotlin, and the CodeQL result gate, then was squash-merged to `master`.

### Remaining limitations and next cycle

- Perform the normal/colorblind/high-contrast/large-text visual walkthrough after the desktop is
  unlocked.
- Guided review currently links to existing builder/tuning destinations at the tool level; future
  work can add validated deep links to a specific parameter or mechanism when the finding carries
  a stable canonical ID.
- No physical validation is claimed. Any later physical recommendation still needs a supervised
  team procedure.

## Cycle 3 — Homing, freshness, and safe-recovery teaching lab

### Objective

Give a robot-builder student a hardware-free way to understand why homing and fault recovery need
valid cached evidence, freshness, bounded dwell, and a confirmed neutral write.

### User-visible outcome

- Robot Academy now includes a fifth interactive lab, **Homing & safe recovery**.
- Students can compare digital-sensor, current-stall, velocity-stall, and combined-stall evidence.
- The model exposes configuration health, cached-feedback age, measurement validity, evidence
  dwell, home-reference state, a latched output fault, and successful or failed neutral recovery.
- The decision is stated as **MOTION PERMITTED** or **MOTION BLOCKED** with explanatory text and an
  icon; color is supplemental.
- The Robot builder path includes the lab after safe subsystem design.
- The mentor guide includes objectives, an activity sequence, misconceptions, and explicit model
  and physical-safety limits.

### Safety and ownership decisions

- The lab is a pure immutable teaching model. It owns no NT4 publisher, service, project file,
  simulator command, or hardware reference.
- Stale or invalid required evidence fails closed and resets the modeled dwell.
- A latched output fault clears only after the modeled neutral write succeeds.
- Establishing a home reference does not bypass later configuration, freshness, validity, or fault
  checks.

### Verification evidence

- Focused model and catalog tests: 13 tests, 0 failures.
- Full Analytics app suite: 428 tests, 0 failures, 0 errors, 2 intentional skips.
- Dashboard smoke: passed.
- Trimmed distributable project loading: passed for one routine and one subsystem document.
- `git diff --check`: passed; only line-ending notices were emitted.
- No ARESLib contract changed in this slice, so no local validation-repository publication or
  consumer dependency substitution was required.

### Delivery and limitations

- Implementation is on `codex/academy-homing-safety-lab-v5`, based on the still-open safe-build
  Analytics change so its current Academy wording is preserved. It must be rebased or retargeted
  after that dependency merges before opening its protected PR.
- A post-unlock visual walkthrough in normal, colorblind, high-contrast, large-text, keyboard, and
  narrow-window modes remains required. The available desktop capture is still blocked by the
  Windows PIN screen; no credential was entered or bypassed.
- No physical robot was used. Real thresholds, directions, neutral behavior, and homing mechanics
  still require supervised hardware-in-the-loop validation.

## Cycle 4 — Controller-to-telemetry state-flow lab

### Objective

Let a novice manipulate one complete, hardware-free ARES-style signal flow instead of memorizing
an architecture diagram.

### User-visible outcome

- Robot Academy now includes **Input, state & telemetry** for the Driver/operator and Robot builder
  paths.
- Students can trace a motor command plus cached encoder, a positional-servo command, or a
  distance-sensor sample.
- The lab keeps the retained previous Redux snapshot visible beside the reducer's new immutable
  snapshot.
- Each trace shows typed action text, the controller decision, mock IO result, telemetry topic,
  value, unit, validity, and freshness.
- Device inversion, controller deadband, stale/invalid cached measurements, unhealthy
  configuration, and a failed mock output write all produce explicit textual outcomes.
- Student and mentor guides now include the activity sequence, misconceptions, and model boundary.

### Safety and ownership decisions

- The flow is a pure teaching model. It does not use the production Redux store, NT4, project
  files, simulator commands, or physical hardware.
- Motor output becomes neutral when required cached encoder feedback is invalid or stale.
- Sensor-only flow refreshes cached state and telemetry without inventing an actuator command.
- Failed mock output writes latch a visible fault in the new teaching snapshot.
- The model does not claim generated-code, simulator, or physical parity.

### Verification evidence

- Focused state-flow and catalog tests: 14 tests, 0 failures.
- Full Analytics app suite: 434 tests, 0 failures, 0 errors, 2 intentional skips.
- Dashboard smoke: passed.
- Trimmed distributable project loading: passed for one routine and one subsystem document.
- No ARESLib contract changed, so an isolated Maven publication was not required for this slice.

### Delivery and limitations

- Implementation is on `codex/academy-redux-signal-lab-v6`, stacked on the validated homing lab and
  safe-build Analytics work. Retarget or rebase it in dependency order before opening its protected
  PR.
- Normal, colorblind, high-contrast, large-text, keyboard, and narrow-window visual walkthroughs
  remain required after the Windows desktop is unlocked.
- No physical robot was used. Production state flow and hardware behavior remain subject to source,
  generated-verification, simulator, and later supervised hardware-in-the-loop evidence.

## Cycle 5 - Reviewed Project Identity setup

### Objective

Remove the novice dead end where Robot Studio detected a missing `.ares/project.json` but routed
students to workspace settings, which could not create or safely repair the canonical document.

### User-visible outcome

- Robot Studio's identity and platform stages now open a dedicated **Project Identity** workflow.
- The screen shows the selected repository, canonical destination, runtime consumers, stable
  project ID, fixed league/convention, measured robot footprint, and editable field frame.
- Missing physical dimensions remain blank unless the workspace already contains measured values;
  the app does not invent robot geometry.
- Current league field dimensions are visibly labeled as a preset that must be verified for the
  season.
- Creation and updates require a structured before/after review and explicit confirmation.
- Project Identity is also discoverable under the Robot navigation section, command search,
  contextual Academy help, First Launch, and the documentation index.

### Safety, ownership, and recovery decisions

- The stable project ID is locked after creation in this editor so an ordinary rename cannot break
  references across drivebase, subsystem, controls, autonomous, and tuning documents.
- A valid prior project document is checkpointed under `.ares/history/project/<content-hash>.json`
  before an atomic update.
- The reviewed save uses the previewed content hash as an optimistic-concurrency boundary. A file
  changed after preview is preserved and the save fails visibly.
- Corrupt existing content and workspace/canonical league mismatches are protected. The editor does
  not overwrite or silently migrate either one.
- The screen explicitly states that document validation is not build, simulation, deployment, or
  physical-robot safety evidence.

### Verification evidence

- Focused repository, view-model, Robot Studio, navigation, and Academy catalog tests: passed.
- Full Analytics app suite: 445 tests, 0 failures, 0 errors, 2 intentional skips.
- Dashboard smoke: passed.
- Trimmed distributable project loading: passed for one routine and one subsystem document.
- `git diff --check`: passed; only line-ending notices were emitted.
- No ARESLib contract changed, so no local validation-repository publication was required.

### Delivery and limitations

- Implementation is on `codex/project-identity-setup-v7`, stacked on the validated Academy and
  safe-build Analytics work. Retarget or rebase it in dependency order before opening its protected
  PR.
- The post-unlock normal, colorblind, high-contrast, large-text, keyboard, and narrow-window visual
  walkthrough remains required. The available desktop capture is still blocked by the Windows PIN
  screen; no credential was entered or bypassed.
- No physical robot was used or required. Robot measurements and season field dimensions still need
  human verification before this metadata can support later physical work.

## Cycle 6 - Local dependency propagation and no-code safety actions

### Objective

Prove that the merged ARESLib generated-subsystem safety contracts propagate through the real FTC,
FRC, and Analytics consumers without relying on `mavenLocal()` or stale cached artifacts.

### Outcome and verification evidence

- A clean branch at ARESLib `origin/master` passed `apiCheck`, all module tests, and
  `publishReleaseValidation` into the isolated `build/release-repository`.
- FTC resolved that repository explicitly, regenerated and verified the project, passed 96 TeamCode
  tests and simulator tests, and assembled the debug APK. Its worktree remained clean.
- FRC resolved the same repository explicitly, regenerated and verified the project, passed its full
  tests and coverage gate, and completed the normal build. Its worktree remained clean.
- Analytics resolved the same repository explicitly and exposed one real compatibility gap: new
  controller assignments require stable device ports. The default Driver/Operator scheme now uses
  ports 0/1 and tests preserve those assignments.
- The Controller Bindings integration test now proves that a generated homed/calibrated subsystem's
  **Recover with neutral** and **Confirm calibration** actions appear offline, require explicit
  boolean confirmation, and save as typed bindings without Kotlin.
- Final Analytics suite: 446 tests, 0 failures, 0 errors, 2 intentional skips. Dashboard smoke and
  packaged-project loading also passed against the isolated repository.

### Boundaries

- These results prove source, generated code, desktop UI, simulator, tests, and packaging agree. They
  do not prove wiring, sensor polarity, neutral output, calibration procedure, or physical safety.
- A supervised physical test remains required before a team uses either safety action on hardware.

## Cycle 7 - Autonomous planning teaching lab

### Objective

Replace the Academy's prose-only autonomous lesson prerequisite with a hardware-free interactive
lab that teaches the validation decisions a student must understand before using the real routine
builder.

### User-visible outcome

- Robot Academy now includes **Autonomous planning** in the Autonomous developer path before the
  safe first routine lesson.
- Students can move a starting and target pose, compare drive speed with timeout margin, toggle a
  named mechanism action and its condition, create a parallel resource conflict, and compare
  failure policies.
- Results always say **PREVIEW READY** or **PREVIEW BLOCKED** with an icon and written reasons; color
  is supplemental.
- The lab explains units and the complete robot-footprint check, and it links conceptually into the
  existing canonical routine-builder workflow without duplicating or modifying that builder.
- Mentor material now includes objectives, an activity sequence, misconceptions, and the boundary
  between the teaching model, generated verification, simulation, and physical field testing.

### Safety and ownership decisions

- The evaluator is a pure immutable teaching model. It has no project repository, NT4 publisher,
  simulator process, generated-source writer, or hardware command path.
- Missing actions, missing conditions, invalid geometry, insufficient timeout margin, resource
  conflicts, and unsafe continue-on-failure choices fail closed.
- A false but available condition is shown as a deliberate skip rather than misreported as a
  validation failure.
- A passing result is explicitly not collision, traction, generated-code, or physical-safety
  evidence.

### Verification evidence

- Focused autonomous-model and Academy-catalog tests: passed.
- Full Analytics app suite: 455 tests, 0 failures, 0 errors, 2 intentional skips.
- Dashboard smoke: passed.
- Packaged distributable project loading: passed for one routine and one subsystem document.
- Validation resolved ARESLib from
  `file:///C:/Users/david/dev/robotics/ares/ARESLib-Kotlin/build/release-repository`; it did not use
  `mavenLocal()` or stale Maven Central binaries.
- Final `git diff --check`: passed with only line-ending notices.

### Delivery and limitations

- Work is on `codex/academy-autonomous-planning-v8`, stacked on the validated Project Identity and
  earlier Academy slices. Retarget or rebase it in dependency order before protected review.
- Normal, colorblind, high-contrast, large-text, keyboard, and narrow-window walkthroughs remain
  pending until the Windows desktop can be inspected without the lock-screen obstruction.
- No physical robot was used or required. Real paths still require simulator evidence and later
  supervised field and hardware validation.

## Cycle 8 - In-app glossary, search corpus, and documentation goal

### Objective

Make the in-app help self-sufficient for vocabulary and give the documentation effort a durable,
testable goal: surface the written glossary inside the app, widen Academy search to lesson content,
and record the remaining workstreams in a tracked document.

### User-visible outcome

- The Academy header has a **Glossary** button opening a searchable pane of all 33 terms ported
  verbatim from `docs/learn/GLOSSARY.md`, each cross-linked to the lesson (opens in-place) or
  Developer Reference entry (opens the viewer) that owns the concept.
- **Ctrl+K** now returns glossary matches below screen matches; choosing one navigates to the
  Academy glossary pane focused on that term.
- Academy lesson search now matches step and before-you-start text, not only titles, outcomes,
  tracks, levels, and keywords - a search for "hysteresis" or "deadband" finds the lesson that
  teaches it.
- Onboarding's first setup step carries a "How this system works" orientation card; the Academy
  opens with "Why documents instead of programs"; "What immutable state buys" explains the
  architecture's payoff; the first mission path is titled "New team member - First mission".
- `docs/DOCUMENTATION_GOAL.md` records the verified baseline and the remaining prioritized
  workstreams (G2 contextual lessons, G4 error-to-lesson links, G5 nudges, G6 sync) with
  acceptance criteria.

### Safety and ownership decisions

- The glossary is content only: no project repository, NT4, simulator, or hardware path.
- `docs/learn/GLOSSARY.md` remains authoritative; `GlossaryCatalogTest` fails when the term sets
  drift, so neither copy can silently diverge.
- Two terms (ADB, Gateway) are explicitly unlinked until their owning lessons exist rather than
  carrying an inaccurate cross-link.
- Help text continues to state limits; nothing in this cycle claims certification or physical
  safety.

### Verification evidence

- Focused help-suite run: `GlossaryCatalogTest` (4 tests), `LearningCatalogTest` (17),
  `LearningJourneyTest` (13), `DeveloperReferenceCatalogTest` (2), plus the remaining help classes
  and `OnboardingModelTest` - 0 failures.
- Full Analytics app suite: 620 tests, 0 errors, 3 skips, 1 failure —
  `DashboardWidgetGridBytecodeTest.generatedDashboardGridLambdaIsValidJvmBytecode`, which also
  fails on a clean `master` tree with these changes stashed (verified 2026-08-17; the reflected
  lambda class name no longer matches after the recent Kotlin/Compose bumps). It is unrelated to
  this cycle and remains open for the dashboard owners.
- Markdown parity confirmed by the drift test resolving `docs/learn/GLOSSARY.md` from the test
  working directory.

### Delivery and limitations

- Changes sit uncommitted on the working tree of `master` in ARES-Analytics, stacked with the
  earlier lesson/dev-reference work from this cycle; move to a feature branch before review.
- G2, G4, G5, and G6 from the documentation goal remain open.

## Cycle 9 - Contextual help on every workflow screen

### Objective

Close documentation goal G2: no workflow screen should lack an accurate in-context lesson, so the
Help affordance teaches something everywhere it can appear.

### User-visible outcome

- Six previously unmapped screens now open an owning lesson from Help: Cloud Sync
  ("What syncs and what never leaves on its own"), Field Editor ("Edit the field everyone plans
  against"), Strategy Preview ("Read driver coaching as evidence"), Guided Run Review (the existing
  run-evidence lesson), Database ("Query stored telemetry directly"), and Hardware Setup ("Review
  hardware addresses before deploy").
- Five new lessons follow the house shape (outcome, boundaries, steps, success criteria, safety
  note, reflection checkpoints) and were placed into the driver-operator, robot-builder,
  data-analyst, and mentor paths without disturbing tested recommendation orderings.
- The Gateway glossary term now cross-links to the offline-sync lesson and left the unlinkable
  allowlist; ADB remains the only unlinked term.

### Safety and ownership decisions

- Lesson content was written from each screen's documented behavior (read-only SQL bounds,
  descriptor-backed address review, history-based coaching), not from aspiration.
- Safety notes keep the verification boundary explicit: address review does not prove wiring,
  coaching does not predict new hardware, field documents are models, sync status is not robot
  status.
- A new catalog test enforces the invariant structurally: every NavigationTarget except PROFILE
  and ADMIN must resolve a contextual lesson, so future screens fail CI until documented.

### Verification evidence

- Help suite plus onboarding: 69 tests, 0 failures (LearningCatalogTest now 18 including the new
  blanket-coverage assertion; GlossaryCatalogTest link-resolution and allowlist checks updated).
- Full Analytics app suite: 621 tests, 0 errors, 3 skips, 1 failure — only the pre-existing
  `DashboardWidgetGridBytecodeTest` failure documented in Cycle 8 (fails identically on clean
  `master`; unrelated to help work).

### Delivery and limitations

- Uncommitted on the ARES-Analytics working tree, stacked on the Cycle 8 changes; move both to a
  feature branch together.
- G4 (error-to-lesson deep links), G5 (nudges and empty states), and the remainder of G6 stay
  open in docs/DOCUMENTATION_GOAL.md.

## Cycle 10 — Guided first autonomous routine

### Objective

Help a student create one bounded simulator-first autonomous draft without starting from a blank
expert editor, losing unsaved work, or confusing a preview with saved/generated/physical behavior.

### User-visible outcome

- An empty Routine Builder now offers **Start guided first routine**.
- The four-step guide explains purpose, meters and counter-clockwise degrees, starting pose, drive
  goal, alliance mirroring, the fixed **Safe** motion preset, and the simulator/physical boundary.
- The guide rejects invalid numbers, out-of-field robot footprints, moves under 0.10 m, and first
  moves over 2.00 m. Raw invalid text cannot leave an older valid value eligible for application.
- Applying the guide creates an unsaved canonical `.aresroutine` draft and autonomous-catalog entry
  through the existing model. It does not save, generate, deploy, launch simulation, or command a
  robot.
- **New**, **Open**, guided replacement, and project-folder changes require explicit discard
  confirmation when the visible draft has unsaved changes.
- Project loading blocks editing with a plain-language status. The project path is bound before the
  editor becomes enabled, and a late load result cannot overwrite a draft created during loading.
- Wide windows retain the side-by-side field/editor layout; narrow windows stack scrollable editor
  and field regions instead of clipping the workflow.

### Safety and ownership decisions

- `RoutineDocument`, `AutonomousCatalogEntry`, existing validation, field-footprint bounds,
  revision storage, preview, and project generation remain authoritative. The guide is only a
  reviewed draft constructor.
- Stable routine and step IDs are created once and then preserved by the normal editor.
- A student must explicitly acknowledge that the field preview still needs obstacle review and
  that simulation does not prove physical safety.
- No ARESLib, FTC, FRC, generated source, or physical runtime was changed in this cycle.

### Verification evidence

All Gradle validation used:

```text
-ParesRepository=file:///C:/Users/david/dev/robotics/ares/ARESLib-Kotlin/build/release-repository
```

- Focused routine model/ViewModel tests: passed, including invalid-plan preservation, deterministic
  FTC/FRC defaults, persistence, dirty-state transitions, and the delayed project-load race.
- Full Analytics app tests: 421 tests, 0 failures/errors, 2 intentional skips.
- Trimmed distributable project loading: passed for one canonical routine and one subsystem.
- Dashboard smoke/performance baseline: passed with 12,000 expected/persisted frames, zero drops,
  exact Parquet restore, 12.3434 ms query p95, and no budget violations.
- Static accessibility review: every guide action has visible text, errors use **Needs attention**
  wording in addition to color, raw numeric fields expose labels/units/errors, dialogs are scrollable,
  and the builder has an explicit narrow-window layout.
- No physical robot was used or required. Restrained hardware and field-clearance checks remain a
  future supervised HIL step.

### Delivery and remaining limitations

Implementation is on `codex/guided-first-routine-v3`; protected PR delivery and hosted checks
remain for this cycle.

- A post-unlock visual walkthrough of the actual dialog is still required before merge.
- ARES still requires an existing FTC or FRC robot repository. No authoritative team-owned starter
  template exists today, so project scaffolding remains a product decision rather than an unsafe
  clone of season-specific hardware code.
