# ARES Clean-Slate Architecture Completion Record

Recorded: 2026-08-27

## Outcome

The clean-slate program was completed as a behavior-preserving restructuring, not a rewrite. The
authoritative source is the `ARES-23247/ARES-Robotics` monorepo, while Android, GradleRIO, Compose,
and ARESLib remain isolated Gradle builds. Canonical `.ares` documents remain the editable robot
source of truth. FTC and FRC retain distinct lifecycle, controller, hardware, deployment, and
simulator products.

The migration does not republish or mutate the already immutable ARES `10.1.0`, starter `10.1.0`,
or Studio `1.7.0` artifacts. Validation uses a unique `10.1.0-rc.<commit>` identity and an isolated
file repository. Final releases must use a new version.

## Implemented ownership boundaries

| Responsibility | Authoritative owner |
| --- | --- |
| Canonical codecs, schema versions, stable serialized IDs, target selections | `ARESLib-Kotlin:project-schema` |
| Complete raw snapshot, derived effective model, cross-document validation and queries | `ARESLib-Kotlin:project-model` |
| Typed IR, artifact ownership plan, canonical/generated hashes and verification manifest | `ARESLib-Kotlin:project-compiler` |
| Deterministic Kotlin, test, registry and manifest rendering | `ARESLib-Kotlin:codegen` |
| Platform-neutral generated control scheduling | ARESLib `core` |
| FTC OpMode lifecycle and safe autonomous host | ARESLib `ftc-hardware` plus thin FTC season adapters |
| Vendor-neutral FRC generated controls | ARESLib `frc-runtime` plus distinct FRC lifecycle/season adapters |
| Deterministic simulator selection and shared contracts | `ARESLib-Kotlin:simulation-foundation` |
| FTC and FRC physics/lifecycle products | Separate FTC and FRC simulator implementations |
| Current immutable project state and save/generate/verify/simulate commands | Studio `ProjectSession` and service gateways |
| Versions, repository identity, build ordering and release policy | `release/ares-versions.properties`, root build scripts and protected workflows |
| Public starter contents | Deterministic exports from `templates/ftc` and `templates/frc` |

## Standalone and code-first development

Studio-created FTC and FRC robots are complete standalone repositories, not monorepo worktrees.
Their Gradle wrappers, immutable ARES dependency pin, platform configuration, canonical `.ares`
documents, generated-source/test directories, IDE tasks, verification, simulation, and deployment
tasks remain functional after Studio closes. Robot Studio exposes **Open in IDE** and the project
wizard labels this boundary as **Create standalone robot project** / **Export standalone repository**.

`.ares/project.json` explicitly selects one authoring model:

- `GUI_OWNED`: canonical `.ares` documents own all robot behavior.
- `CODE_FIRST`: project Kotlin is authoritative and Studio displays only declared registrations.
- `HYBRID`: `.ares` owns drivetrain and routines while registered Kotlin owns selected mechanisms.

Project metadata schema 4 requires that field. Schemas 1-3 and the retired `.ares-robot.json`
split identity are unsupported; Studio contains no legacy identity decoder or migration branch.

ARES never reverse-engineers arbitrary Kotlin. A hand-authored subsystem's `.aressubsystem`
registration must declare its user-owned module/source files, runtime and IO types, action keys,
telemetry, typed tunables, safety and verification evidence, and simulator/mock capability. Missing
declarations fail closed and remain visibly unavailable. Each starter contains a clearly
`USER-OWNED` extension package and league-appropriate IDE guidance: Android Studio for FTC, and
WPILib VS Code or IntelliJ/GradleRIO for FRC. Local Git history is created automatically; GitHub
backup is optional.

Every imported component retains its original Git history as an ancestor. The exact import commits
and source tree hashes are recorded in `MONOREPO_MIGRATION_BASELINE.md` and enforced by
`scripts/verify-imported-histories.ps1` and its POSIX equivalent. The user's original dirty primary
checkout was never reset or cleaned; the migration was performed in an isolated worktree.

## Requirements achieved

- One canonical project identity and one effective model feed Studio, code generation, verification,
  and simulation selection.
- Codegen source is physically owned by its module; the former cross-module source-set aliases are
  removed.
- Generated mechanical source and tests live under Gradle generated directories and bind to a
  content-hash verification manifest.
- User-owned files, generated starters, and replaceable generated plumbing retain distinct ownership
  and regeneration rules.
- FTC and FRC share only proven platform-neutral scheduling/simulation contracts; league semantics
  and physics remain separate.
- Checked-in consumer copies of generated FTC/FRC runtime plumbing are removed.
- Normal builds use immutable published artifacts. Validation uses an explicit candidate version and
  isolated repository. Ambient `mavenLocal()` publication/resolution is rejected by policy.
- Starter repositories are reproducible mirrors, and a fresh-project acceptance job generates,
  compiles, tests, simulates, and packages their output.
- Windows MSI and native macOS DMG builds are first-class protected gates.
- The Studio workflow was visibly exercised with the GUI-authored Lightbot project through verify,
  simulator launch, TeleOp selection, INIT/START, bounded driving, live NT4 telemetry, simulator stop,
  and graceful application close.

## Verification contract

The protected pull request must pass all of these independent gates before promotion to `main`:

1. Imported-history, source-ownership, dependency-provenance, and deterministic-mirror policy.
2. ARESLib tests, API compatibility, and isolated candidate publication.
3. FTC robot tests, generated-project verification, simulator tests, and APK assembly.
4. FRC robot, generated runtime, lifecycle, and simulator contracts.
5. Both canonical starter source trees and a second fresh-project generation journey.
6. Studio, shared model, gateway, dashboard performance, and autonomous contract tests.
7. CodeQL over every ARES-owned Kotlin/Java source boundary.
8. Real Windows MSI and native macOS DMG packaging.

Local acceptance additionally covers the complete release-validation matrix, Windows installer
maintenance/repair behavior, exact Compose HWND capture, and a visible FTC simulator journey.
Configuration, compilation, simulation, and packaging are not physical robot evidence.

## Deliberately retained or deferred

- The large legacy renderer and UI files can continue to be decomposed internally behind the new
  compiler and session boundaries. Their size is maintainability cleanup, not an alternate source of
  robot meaning.
- Public component repositories remain available during the transition. Starter mirrors should
  complete two protected release cycles before old source repositories are archived or made read-only.
- Systemcore FTC and Systemcore FRC targets remain separate future adapters. They are intentionally
  not invented before stable vendor APIs and deployment contracts exist.
- Physical wiring, direction, homing, current limits, radio behavior, and real-device failure modes
  still require explicit physical validation and separately stored evidence.

## Rollback and release rules

- `MONOREPO_MIGRATION_BASELINE.md` is the byte-level rollback ledger.
- The migration branch and protected pull request preserve a reviewable rollback point before `main`.
- No generated mirror may be edited manually; change its canonical template and regenerate it.
- No final version may be rebuilt with different bytes. A failed or changed release receives a new
  version.
- Legacy component repositories and the existing GitHub Maven branch remain available until the
  protected monorepo transition has accumulated sufficient release evidence.
