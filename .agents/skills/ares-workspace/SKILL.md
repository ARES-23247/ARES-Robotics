---
name: ares-workspace
description: Develop, debug, review, or document the ARES Robotics source monorepo across ARESLib-Kotlin, FTC/FRC products and starters, and ARES Robotics Studio. Use for cross-product changes, Redux/state flow, typed tuning and drivebase descriptors, telemetry contracts, robot/simulator parity, hardware safety, zero-GC loops, or deciding which monorepo product owns a change.
---

# ARES Workspace

## Start safely

1. Read the workspace `AGENTS.md` completely. Treat it as the current source of truth.
2. Inspect the monorepo branch and dirty state before editing. Preserve unrelated changes, including
   changes in product directories you are not modifying.
3. Identify ownership before changing code: shared behavior belongs in ARESLib; season hardware and
   game logic belong in FTC/FRC; exportable generic project sources belong in the starters; desktop
   workflows belong in Studio's `ARES-Analytics/` source directory.
4. Trace the full runtime boundary before editing: input, Redux action, reducer, immutable state, controller, IO, telemetry, simulator, and consumer UI.
5. Make the smallest coherent change across all affected producers, consumers, tests, and documentation.

## Preserve invariants

- Keep reducers pure and state immutable. Do not bypass Redux for actuator intent.
- Read hardware once per loop into cached inputs. Never read hardware from getters or output writers.
- Keep periodic robot and simulator paths allocation-free where required.
- Use `RobotClock`; do not introduce system wall-clock calls in library/runtime code.
- Keep headings CCW-positive and radians internally. Verify field/canvas and Limelight boundaries before changing signs.
- Keep robots offline-first. Robots serve local telemetry/logs; the desktop performs cloud work.
- Fail closed on invalid, stale, unhomed, unconfigured, or faulted hardware.
- Preserve FTC/FRC/mock/simulator behavioral parity.
- Keep physical hardware identity in canonical drivebase descriptors, tunable values in typed tuning profiles, and runtime experiments in local overlays. Never model CAN IDs, motor names, inversion, or topology as live tuning values.
- Treat live tuning as a proposal transaction: publish a typed requested value with a monotonic safe nonce, require an explicit consumer callback, and roll back rejected or unmapped values.

## Route detailed work

- Read [references/repository-map.md](references/repository-map.md) when locating ownership or integration points.
- Read [references/runtime-contracts.md](references/runtime-contracts.md) before changing controls, telemetry, hardware, vision, coordinates, or hot loops.
- Use `$ares-subsystem-authoring` for subsystem generation, descriptors, lifecycle, actions, or hand-authored registration.
- Use `$ares-build-release` for dependency resolution, launching, validation, publishing, or CI/release work.

## Shared repository guidance

These skills are canonical in `.agents/skills/` for Codex, Gemini CLI, and Antigravity.
Edit them here and commit the references alongside them. Do not sync from a developer's
home directory or keep separate tool-specific copies. Read `docs/agents/README.md`
for instruction entry points and `docs/agents/WORKSPACE_GUIDE.md` for detailed contracts.

## Verify proportionally

Run focused tests first, then the full affected product suites. When ARESLib changes, validate
consumers in dependency order. Report simulator/desktop coverage separately from physical HIL work;
never imply hardware was tested when it was not.
