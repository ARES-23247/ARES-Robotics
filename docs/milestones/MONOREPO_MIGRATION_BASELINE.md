# ARES Robotics Monorepo Migration Baseline

Recorded: 2026-08-27

This ledger is the rollback boundary for the source-monorepo migration. The migration begins with
Git topology only: component source, build logic, package coordinates, and runtime behavior remain
unchanged until their later protected milestones.

## Authoritative source commits

| Source repository | Branch | Commit | Latest release/tag observed |
|---|---|---|---|
| `ares-workspace` | `codex/studio-project-session-refactor` | `3d10f63a57c9f11de5d15a4b82bff3417f5607d0` | none |
| `ARESLib-Kotlin` | `master` | `13599358be7fb87d44e9804e798c7f27c6e84b21` | `v10.1.0` |
| `ARES-FTC` | `master` | `0cb74896a60fd7a3b46bb70ca04440aaac91fc6a` | `audit-2026-08-hil-pending` |
| `ARES-FRC` | `master` | `98e2ab05e3a864e9738e9eb56965997412f8b581` | `audit-2026-08-hil-pending` |
| `ARES-FTC-Starter` | `master` | `8db9f3651cea58cc0af038919f9b2b1f48fb67e3` | `v10.1.0` |
| `ARES-FRC-Starter` | `master` | `eab3a8db8a19f7f9153a6eaa12192f78da673c8a` | `v10.1.0` |
| `ARES-Analytics` | `master` | `09e00086c3d0ec29bcd2c11f6805c35d42e7267d` | `v1.7.0` |

The root `main` branch also contains local commit
`beda36e986dda7e98ea4dc718651bfe9703fafa6` above `origin/main`; it is an ancestor of the selected
migration baseline and is therefore retained.

## Primary checkout preservation

The primary `C:/Users/david/dev/robotics/ares` worktree was intentionally not cleaned, reset, or
switched. At baseline time it contained user/unrelated changes to:

- `.agents/skills/compose-desktop-tester/scripts/capture_app.ps1`
- `AGENTS.md`
- untracked `.agents/rules/`, `.pnpm-store/`, `.release-validation/`, and starter checkout paths

The migration is isolated in `.tmp/ares-robotics-monorepo` so those bytes remain untouched.

## Immutable release boundaries

- Current ARES library and starter line: `10.1.0`.
- Current ARES Robotics Studio line: `1.7.0`.
- The `ARESLib-Kotlin` `maven` branch remains the external GitHub Maven artifact host during source
  migration. A source-monorepo cutover does not authorize changing published coordinates or
  replacing released bytes.
- Public FTC and FRC starter repositories remain independently buildable until generated mirrors
  have passed standalone acceptance and two protected release cycles.

## Migration invariants

1. Preserve each imported component history as a parent of the monorepo history.
2. Import under the existing directory names before any layout refactor.
3. Do not combine Git topology changes with runtime or source reorganization.
4. Keep isolated Gradle roots and standalone builds working.
5. Validate component HEAD tree hashes against the imported directory trees.
6. Do not archive component repositories or move the Maven artifact channel until protected
   monorepo acceptance succeeds.
7. Report simulation and desktop evidence separately from physical hardware validation.

