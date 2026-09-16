# Studio Project Persistence Milestone

Date: 2026-08-27

## Outcome

Phase 6 completes the first structural ARES Robotics Studio refactor. Canonical robot-project
reads and writes now cross a service-owned project boundary instead of being owned by UI package
implementations. One long-lived `ProjectSession` supplies an immutable effective project snapshot
and a revision token. Production authoring features submit typed commands against that token.

This milestone deliberately preserves every existing `.ares` file format, generated robot
behavior, offline-first rule, and FTC/FRC product boundary. It is an ownership and consistency
refactor, not a cosmetic file move and not a physical-hardware validation.

## Architecture after the migration

```text
Compose screen
  -> immutable ViewModel draft
  -> typed ProjectSession command + expected revision
  -> service/project persistence repository
  -> atomic per-file write + immutable history/recovery
  -> stable reload through ProjectDocumentGateway
  -> one new immutable effective project snapshot
  -> structured generation / verify / simulate / deploy authorization
```

- `service/project/ProjectDocuments.kt` is the canonical read gateway.
- `service/project/persistence/` owns codecs, paths, atomic replacement, immutable history,
  reviewed removals, and recovery copies.
- `ProjectSession` owns workspace selection, stable canonical fingerprinting, stale-edit rejection,
  typed mutations, and post-command snapshot refresh.
- `ProjectMutationTransaction` gives multi-document commands a recoverable transaction journal. A
  thrown operation rolls back immediately. An interrupted uncommitted transaction is restored on
  the next session load. A committed marker prevents a completed command from being rolled back if
  cleanup itself is interrupted.
- `ProjectExecutionCoordinator` and `SessionProjectGenerator` derive target platform and simulator
  product from the same effective project snapshot before starting an external process.
- `ServiceRegistry` owns the application-scoped session, repositories, and execution boundary.

The transaction journal is intentionally described as recoverable rather than as a filesystem-wide
atomic primitive. Individual canonical files still use atomic replacement; commands spanning more
than one file use snapshot, journal, commit-marker, and recovery semantics.

## Typed command coverage

| Project responsibility | Session-owned command behavior |
|---|---|
| Project identity and robot footprint | Reviewed save; invalid-file repair bound to the exact raw-byte hash |
| Drivebase | Reviewed save with drivetrain and derived tuning reloaded together |
| Routines and autonomous chooser | Routine plus catalog commit under one recoverable transaction |
| Routine recovery | Historical content restored as a new revision; stale screens are rejected |
| Controller profiles and bindings | All affected documents commit under one recoverable transaction |
| Subsystems | Revision-bound save, reviewed removal, recovery-copy restore |
| Superstructures | Typed save validated against the same effective subsystem/action catalogs |
| Fields | League-checked canonical field save |
| Tuning profiles | Hash-bound reviewed promotion with typed declarations and changes |
| Generation and execution | Invalid or mismatched projects are rejected before process launch |

Run-scoped verification evidence, hardware review evidence, local state, history, recovery,
backups, drafts, and generated output remain outside canonical content identity. Recording evidence
therefore cannot make an unchanged robot definition appear stale.

## Concurrency and failure semantics

1. Every production form retains the revision from the snapshot it rendered.
2. The session rejects a command if that revision is no longer current.
3. It independently fingerprints canonical bytes before the first write, catching external edits
   that occurred without an in-process session reload.
4. Only one command executes through the session lock at a time. Concurrent submissions from the
   same revision produce one applied result and one stale result.
5. After a successful command, the session reloads one complete effective project before publishing
   the next revision.
6. Workspace switches replace the complete selection and snapshot; no documents from the previous
   robot can leak into the new session.

## Platform separation

The refactor does not create a universal FTC/FRC runtime or simulator. Platform-neutral schemas,
effective-project assembly, revisions, and command contracts are shared. FTC Desktop OpMode and FRC
HAL simulation remain separate products with different lifecycles, hardware adapters, build tools,
and verification matrices. `ProjectExecutionCoordinator` selects the product explicitly from the
project platform.

## Verification evidence

All commands below used the isolated dependency candidate and never relied on `mavenLocal()`:

```text
-ParesVersion=10.0.0-rc.sim-product.1
-ParesRepository=file:///C:/Users/david/dev/robotics/ares/ARESLib-Kotlin/build/release-repository
```

- Focused ProjectSession, transaction, identity, drivebase, field, tuning, routine, controller,
  subsystem, superstructure, readiness, generation, and architecture tests passed.
- Regression coverage includes cached snapshot identity, evidence exclusion, external edits,
  concurrent saves, stale revisions, invalid identity repair, multi-document rollback, interrupted
  recovery, committed cleanup, removal recovery, routine restoration, workspace switching, product
  selection, and process rejection before generation.
- The complete Analytics `test` task passed in 1 minute 29 seconds (25 tasks; 3 executed and 22
  up-to-date) against the isolated candidate.
- Two real Compose Desktop cycles passed. Each created an isolated runtime classpath, reached
  `shown`, `opened`, exact-HWND `presented`, and settled `alwaysOnTop=false`; captured a rendered
  1424x861 Studio dashboard; posted native `WM_CLOSE`; cleaned the runtime snapshot; and left no
  `com.ares.analytics.MainKt` process.
- Captures are local build evidence:
  - `ARES-Analytics/build/diagnostics/project-session-phase6-cycle-1.png`
  - `ARES-Analytics/build/diagnostics/project-session-phase6-cycle-2.png`
- Offline NT4 connection refusal was observed and correctly did not block local authoring or window
  startup.
- No physical robot was connected or claimed.

## Rollback

Implementation remains isolated on `codex/studio-project-session-refactor`. Phase 6 is split into
small signed Analytics commits so individual ownership changes can be reviewed or reverted. The
root milestone documentation is committed separately.

Rollback does not require converting project files because this phase changed no canonical schema.
Reverting the Phase 6 Analytics commits restores the preceding Phase 5 adapters while preserving
all user documents, history, recovery copies, generated sources, and run-scoped evidence.

## Deliberately deferred work

The major persistence/session boundary is complete. The following are future maintainability work,
not blockers hidden inside this milestone:

- Split the largest authoring ViewModels into smaller use cases and presentation adapters.
- Reduce `MainScreen` further after navigation/status coordination receives its own shell model.
- Remove test-only repository fallback paths when all unit fixtures construct a ProjectSession.
- Decide whether physical-review evidence needs its own typed service command; it intentionally
  remains separate from canonical robot configuration today.
- Perform FTC and FRC physical commissioning only when hardware is available, preserving the
  evidence labels that distinguish configuration, compilation, simulation, and physical results.

