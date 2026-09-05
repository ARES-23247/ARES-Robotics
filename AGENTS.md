# ARES Robotics - shared agent instructions

This is the authoritative source monorepo for ARESLib, FTC, FRC, XRP, and ARES Robotics
Studio. These instructions apply to every AI coding tool working in this repository.
Open the monorepo root as the workspace so its instructions and skills are discoverable.

## Start here

1. Inspect the branch and dirty state. Preserve other people's changes and running processes.
   Use an isolated feature branch/worktree when the checkout contains unrelated work.
2. Read the applicable sections of [the workspace engineering guide](docs/agents/WORKSPACE_GUIDE.md)
   before editing. It preserves the complete product map, telemetry contract, desktop launch
   requirements, simulator controls, and release workflow. Paths in that guide are repo-relative.
3. Read nested `AGENTS.md` or `GEMINI.md` guidance for the product being changed. In particular,
   [ARESLib conventions](ARESLib-Kotlin/GEMINI.md) apply to library/coordinate work, and each
   FTC/FRC starter has its own `AGENTS.md`. These are engineering guidance for every tool,
   regardless of their filename. Apply both parent and relevant product guidance.
4. Use the relevant repository skill below. If a personal copy has the same name, read the
   repository copy directly; repository guidance is the maintained project source of truth.

## Shared skills

| Work | Skill |
|---|---|
| Cross-product development, reviews, hardware safety, Redux, telemetry | [ares-workspace](.agents/skills/ares-workspace/SKILL.md) |
| Gradle, dependencies, tests, packaging, protected releases | [ares-build-release](.agents/skills/ares-build-release/SKILL.md) |
| Subsystem descriptors, generation, registration, authoring UI | [ares-subsystem-authoring](.agents/skills/ares-subsystem-authoring/SKILL.md) |
| Studio launch, rendered-window evidence, interaction, shutdown | [compose-desktop-tester](.agents/skills/compose-desktop-tester/SKILL.md) |

## Invariants

- Preserve the isolated Gradle products. Shared behavior belongs in ARESLib; season behavior
  belongs in FTC/FRC. XRP controller code is MicroPython; ARES-owned JVM code is Kotlin-first.
- Preserve `input -> action -> pure reducer -> immutable state -> controller -> IO`.
  Cache hardware reads once per loop, avoid allocations in hot loops, and use `RobotClock`.
- Nonzero output requires valid configuration, fresh feedback, and explicit enable/arm.
  Disable, stop, fault, stale control, and close must neutralize safely. Never weaken leases
  or substitute simulator truth for the Redux estimator to make a test or demo pass.
- Heading is CCW-positive and radians internally. Read the coordinate and Limelight rules
  before touching transforms. Preserve the packed simulator pose frame and NT4 normalization.
- Robots remain offline-first. The desktop pulls logs locally and owns cloud synchronization.
- `.ares` documents own generated robot plumbing. Keep generated source under build outputs;
  preserve USER-OWNED extensions and the product's reviewed replacement/confirmation flow.
- A successful compile or live JVM does not prove a usable Studio window. Follow the tester
  skill for exact-window capture and graceful shutdown. Preserve isolated runtime snapshots,
  single-instance ownership, and `clean` independence from `killExisting`. Serialize compilers
  writing the same module. Do not terminate another task's app, simulator, or build.
- Read versions from `release/ares-versions.properties`. Library changes require a new version
  and source-tree identity, then dependency-ordered candidate validation. Releases promote an
  attested candidate with exact protected-main tree equality; never rebuild after approval,
  reuse different bytes under an existing version, or bypass required branch checks.
- Never commit directly to `main` unless the user explicitly instructs it. Release merges use
  protected pull requests. Run checks appropriate to the change and report actual evidence,
  distinguishing simulator/desktop tests from physical hardware validation.
- State facts and limitations plainly. Do not claim tests, UI visibility, or hardware results
  that were not observed. Keep credentials and machine-local state out of shared instructions.

## Maintaining instructions

See [agent setup and maintenance](docs/agents/README.md). Shared instructions and skills must
be tracked in Git. Codex reads this file; Gemini and Antigravity adapters reference this same
file. Edit the canonical files instead of creating divergent per-tool policies.

Run `python scripts/verify_agent_guidance.py` after staging guidance changes. The check also
runs in source-policy CI and rejects ignored/untracked guidance, broken local links, adapter
drift, and an oversized root guide. Agent permissions and installed tools remain controlled
by each user's application settings; these files provide equal access to project guidance.
