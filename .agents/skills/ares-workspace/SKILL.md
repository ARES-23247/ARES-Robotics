---
name: ares-workspace
description: Resolve ARES product ownership and change cross-product runtime contracts, including Redux, telemetry, coordinates, and hardware safety.
---

# ARES Workspace

Keep shared behavior in ARESLib, season behavior in FTC/FRC, exportable project sources in
starters, and desktop workflows in `ARES-Analytics/`. Preserve the root `AGENTS.md` invariants
and the affected product's guidance.

## Read for the affected boundary

- Use [repository-map.md](references/repository-map.md) when ownership or integration points are unclear.
- Use [runtime-contracts.md](references/runtime-contracts.md) when changing controls, hardware,
  telemetry, simulation, vision, coordinates, tuning, or hot loops. Trace the affected producers
  and consumers far enough to preserve their contract; unrelated runtime paths need no audit.
- Use [ares-subsystem-authoring](../ares-subsystem-authoring/SKILL.md) for subsystem descriptors,
  generation, or registration, and [ares-build-release](../ares-build-release/SKILL.md) for
  Gradle validation, dependency resolution, or release work.
- For instruction maintenance, use [the maintenance guide](../../../docs/agents/README.md).
  Edit these canonical repository skills; personal copies must not supply competing policy.

## Completion

Complete the requested change across affected producers and consumers. Verify changed behavior
with focused checks; broaden coverage when shared contracts, failures, or release requirements
justify it. ARESLib changes still require dependency-ordered candidate validation through the
build/release skill. Report observed simulator/desktop evidence separately from physical hardware
validation. Documentation-only changes use guidance/link checks rather than product test suites.
