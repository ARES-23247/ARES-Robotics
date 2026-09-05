---
name: ares-subsystem-authoring
description: Design, generate, register, review, or document ARES robot subsystems and their authoring UI. Use for `.aressubsystem` descriptors, capability templates, generated starters/plumbing/tests, hand-authored FTC or FRC subsystems, Redux actions, controller bindings, safety contracts, simulation adapters, or subsystem migration decisions.
---

# ARES Subsystem Authoring

## Choose ownership first

1. Inspect the live implementation and descriptor before generating anything.
2. Use `GENERATED_STARTER` only for a new subsystem whose editable starter source should be created.
3. Use `HAND_AUTHORED` for existing Kotlin. Declare its module, source files, class names, simulation support, teaching metadata, and capability action keys.
4. Never infer ownership by scanning arbitrary Kotlin or overwrite user-owned source.
5. Keep the conceptual boundaries explicit: domain, control, IO contract, hardware, simulation, lifecycle/registration, and verification.

## Generate safely

- Treat `USER-OWNED` as protected and never replacement-token eligible.
- Preview structured diffs before replacing a `GENERATED STARTER`; require the exact current confirmation token.
- Generate mechanical plumbing into Gradle generated-source directories.
- Keep editable starters discoverable in normal source directories.
- Emit deterministic output and ownership headers.
- Generate no starter/mock/test source for hand-authored descriptors.

## Require safety behavior

For actuator-capable subsystems, cover safe startup, disabled/stop behavior, cached and fresh feedback, configuration health, homing/calibration when required, soft limits, current validity, failed writes, fault latching, explicit neutral recovery, close cleanup, and simulator/mock parity. Preserve zero-allocation periodic behavior where applicable.

## Expose capabilities

Actions used by controller bindings must exist in the project action catalog and route through NamedCommands/tasks/Redux. Use explicit text labels and descriptions; never depend on color swatches alone. Hardware-gate optional capabilities such as Prism lighting.

## Prototype before migration

Compare a representative subsystem before broad migration: user-owned/generated file counts, customization points, safety/test coverage, regeneration behavior, build integration, and migration cost. Do not rewrite working subsystems merely to make file counts smaller. Existing safe subsystem code should remain sample material.

Read [references/descriptor-contract.md](references/descriptor-contract.md) for schema and ownership details. Read [references/review-checklist.md](references/review-checklist.md) before declaring a generator or migration complete.
