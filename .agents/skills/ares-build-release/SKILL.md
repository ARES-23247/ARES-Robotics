---
name: ares-build-release
description: Build, test, launch, package, publish, or troubleshoot dependency resolution for the ARES Robotics source monorepo. Use for Gradle commands, ARESLib dependency order, Maven Central or GitHub Maven versus sibling/isolated validation repositories, FTC/FRC simulators or Studio launch, consumer validation, CI workflows, protected releases, or Maven artifact coordinates.
---

# ARES Build and Release

## Select the dependency mode

Use one mode deliberately:

1. **Released development**: use the configured Maven Central and GitHub-hosted ARES Maven repositories for immutable `org.aresfirst.ares` releases.
2. **Sibling source development**: pass `-ParesUseSiblingLib=true` when changing ARESLib and a consumer together.
3. **Release-candidate validation**: run ARESLib `publishReleaseValidation` with an explicit prerelease `-ParesVersion`, then pass that same version and the absolute `-ParesRepository=file:///.../build/release-repository` to consumers.

Do not rely on an accidental `mavenLocal()` artifact or silently combine modes.

## Build in dependency order

1. Run focused ARESLib tests for the changed module.
2. Run full ARESLib tests and API checks when public contracts changed.
3. Publish the isolated validation repository for release evidence. Explicit sibling substitution
   is available for focused development, not as a replacement for candidate validation.
4. Build affected FTC, FRC, starter, simulator, and Studio consumers in dependency order; serialize
   tasks that write shared outputs or validation repositories.
5. Run generated-project verification before packaging robot applications.

## Launch

- Studio app only: from `ARES-Analytics/`, run `.\gradlew.bat :app:run -ParesUseSiblingLib=true`
  for local shared-source work.
- Studio app plus gateway: run the Analytics root `run` task when gateway behavior is required.
- FTC desktop simulation: use the FTC product simulator tasks; do not claim physical hardware coverage.
- FRC simulation: use the FRC product's WPILib/HAL simulation task and validate season IO separately.

## Release safely

Published releases are immutable. `Build Desktop Packages` seals an attested candidate on a
reviewed tree; `Promote Verified Release Candidate` verifies provenance, protected-main tree
equality, versions, and hashes before publishing those same bytes. Never rebuild after approval
or reuse a version for different bytes. Library changes require a new version and source-tree
identity. Keep signing credentials in the protected release environment. Read the release
contract in `docs/agents/WORKSPACE_GUIDE.md` before packaging or promotion.

Read [references/commands.md](references/commands.md) for current coordinates and common commands. Read [references/failure-modes.md](references/failure-modes.md) when dependency or Gradle behavior is surprising.
