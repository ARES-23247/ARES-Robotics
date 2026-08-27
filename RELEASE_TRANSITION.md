# ARES Monorepo Release Transition

This is the current release and repository policy. Historical milestone and cycle records describe
the state at the time they were written and are not operational instructions.

## Authoritative source and public artifacts

- `ARES-23247/ARES-Robotics` is the only authoritative source repository.
- Final ARES Maven artifacts are published first to the monorepo `maven` branch under immutable
  `org.aresfirst.ares` coordinates.
- One protected Studio release contains the Windows MSI, macOS DMG, deterministic FTC and FRC
  standalone starter archives, and SHA-256 checksums.
- Maven Central is an optional additional channel. It is never required to validate a release.
- Old component repositories and their Maven/release assets remain readable so existing projects
  continue to resolve immutable releases.

## Required dependency order

1. Test ARESLib and publish a unique isolated RC repository.
2. Test FTC, FRC, both starters, simulators, and Studio against that exact RC.
3. Stage final ARES coordinates and reject attempts to replace existing version bytes.
4. Build deterministic starter archives and verify their recorded hashes.
5. Package Studio against the staged final ARES repository.
6. After every protected gate passes on `main`, publish the Maven branch first.
7. Publish the combined Studio/starter GitHub release second.

Studio must never advertise a final dependency that is not available in the same completed release
sequence.

## Legacy repository retirement

Do not archive component repositories yet. Keep them clearly labeled as legacy history/release
sources until **two successful protected monorepo release cycles** complete. Then source repositories
may be archived; immutable Maven branches and release assets must remain available. Starter
repositories may remain as read-only discovery mirrors.

## Local checkout migration

Never reset or clean the former workspace during migration. Use
`scripts/prepare-clean-monorepo-checkout.ps1` to inventory it, clone the monorepo elsewhere, review
user-owned files explicitly, and switch only after the clean checkout passes setup and validation.

