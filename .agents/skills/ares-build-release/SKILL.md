---
name: ares-build-release
description: Build, test, launch, or release ARES Gradle products; resolve ARESLib dependencies and validate release candidates.
---

# ARES Build and Release

Choose the dependency mode explicitly and keep it consistent across compile, test, and run:

- Released development uses configured Maven Central and GitHub-hosted ARES Maven releases.
- Shared-source development opts into `-ParesUseSiblingLib=true`.
- Candidate validation uses `publishReleaseValidation`, one prerelease `-ParesVersion`, and
  the same absolute `-ParesRepository=file:///.../build/release-repository` for consumers.

Do not mix modes or rely on ambient `mavenLocal()` artifacts. Read versions from
`release/ares-versions.properties`. Serialize tasks that write the same outputs or repository.

## Select the work

- For build/test tasks and coordinates, use [commands.md](references/commands.md).
  Run the checks relevant to the changed product and contract; an ordinary consumer change
  does not require rebuilding the library or publishing a candidate.
- For dependency or Gradle failures, use [failure-modes.md](references/failure-modes.md).
- For Studio launch or rendered UI validation, use
  [compose-desktop-tester](../compose-desktop-tester/SKILL.md), including its process ownership checks.
  Simulator task selection is in [commands.md](references/commands.md).
- For library changes, packaging, or promotion, read the dependency and protected-release contract
  in [the engineering guide](../../../docs/agents/WORKSPACE_GUIDE.md).

Library changes require a new version/source-tree identity and dependency-ordered candidate
validation. Packaging verifies generated projects. Promotion publishes the attested candidate
with exact protected-main tree equality, versions, and hashes; never rebuild after approval or
reuse a version for different bytes. Signing credentials remain in the protected release environment.

Complete the requested build/validation workflow, fix failures caused by the change, and rerun
its affected checks. Report unresolved failures and the actual evidence supporting completion.
