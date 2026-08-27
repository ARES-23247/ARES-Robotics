# Typed Project Compiler Milestone

## Outcome

Phase 2 of the clean-slate refactor is implemented on the rollback-safe
`codex/typed-project-compiler` branches. Canonical `.ares` documents are still the only editable
robot source of truth, but generation now crosses an explicit validated compiler boundary:

```text
canonical documents
  -> RobotProjectAssembler
  -> EffectiveRobotProject
  -> RobotProjectCompiler
  -> immutable RobotProjectIr
  -> focused artifact renderers
  -> generated main/test sources + verification manifest
```

This is a structural refactor, not a robot-control rewrite. FTC and FRC lifecycle hosts, hardware
adapters, simulators, Redux behavior, user-owned starter files, and physical-review evidence remain
separate.

## Ownership after the milestone

| Responsibility | Owner |
| --- | --- |
| Canonical schemas, codecs, stable serialized IDs | `ARESLib-Kotlin:project-schema` |
| Complete project assembly, derivation, validation, effective queries | `ARESLib-Kotlin:project-model` |
| Typed IDs, platform-neutral IR, artifact plans, content hashes | `ARESLib-Kotlin:project-compiler` |
| Kotlin, generated-test, registry, and manifest rendering | `ARESLib-Kotlin:codegen` |
| Redux/control/hardware contracts and runtime primitives | Existing ARESLib runtime modules |
| FTC/FRC competition lifecycle and target-specific behavior | Separate FTC/FRC runtime and consumer projects |
| Project authoring and presentation | ARES Robotics Studio (`ARES-Analytics`) |

Codegen source and tests now physically live in the `codegen` module. The former Gradle source-set
aliases into `core` are gone. `CapabilityArgumentReader` moved to its runtime-owned `core` package
because generated robot code, not the generator itself, consumes it.

## Generation and regeneration contract

- The compiler refuses invalid projects and cross-league targets before rendering.
- Artifact plans name the source set, responsibility, destination, ownership, and explanation.
- `USER_OWNED`, `GENERATED_STARTER`, and `GENERATED_DO_NOT_EDIT` remain distinct.
- Mechanical artifacts are replaced deterministically; user-owned sources are never silently
  overwritten.
- Every generation emits `build/generated/ares/verification/ares-project-verification.json`.
- The manifest binds the canonical project SHA-256 to the exact generated mechanical artifact
  hashes. User-owned starter contents are deliberately excluded from mechanical-output identity.
- Check mode reports a stale manifest or stale generated source.
- Empty superstructure projects still generate their registry contract; this is now regression
  tested.

## Studio integration

`AresProjectDocumentSnapshot` now exposes one validated `EffectiveRobotProjectQueries` view instead
of compatibility getters that allowed features to reconstruct partial project meaning. Readiness,
packaged validation, drivetrain/path planning, subsystem authoring, controls, and superstructure
features use this shared query boundary.

The student workflow and generated file layout are unchanged. The benefit is that every feature is
now asking the same effective model which actions, routines, controls, subsystems, superstructures,
drivetrains, fields, and tuning documents exist.

## Verification evidence

Candidate version: `10.0.0-rc.typed-compiler.1`

Candidate repository:
`file:///C:/Users/david/dev/robotics/ares/ARESLib-Kotlin/build/release-repository`

The following completed successfully against that same immutable candidate:

- ARESLib: all tests, API compatibility, and isolated release-validation publication.
- ARES-FTC: generation, stale-output verification, TeamCode unit tests, simulator tests, and Android
  debug assembly.
- ARES-FRC: generation, stale-output verification, and all tests.
- ARES-FTC-Starter: generation, verification, TeamCode tests, simulator tests, and Android assembly.
- ARES-FRC-Starter: generation, verification, and all tests.
- ARES Robotics Studio: full test suite using the isolated candidate.
- Visible desktop acceptance: the exact Compose HWND was shown, focused, active, captured at
  1440x900 with the GUI-authored Lightbot project, and closed gracefully. Offline NT4 and unavailable
  cloud services remained optional and did not prevent authoring UI startup.

Representative generated manifests were inspected in all four consumer projects. Their canonical
project and generated artifact hashes were present and deterministic.

Two integration defects were found and fixed by this matrix:

1. `project-compiler` was initially absent from the publication graph while the BOM referenced it.
   A publication-graph regression test now requires the BOM and immutable publication list to agree.
2. Projects without superstructures initially omitted the empty generated registry. A renderer
   contract test now protects that consumer compile boundary.

No physical robot validation was performed or claimed.

## Remaining intentional work

The typed compiler boundary is complete, but the wider clean-slate roadmap is not. Phase 3 should
consolidate copied platform-host plumbing without merging league semantics:

- Separate league lifecycle, controller target, and device-adapter contracts.
- Move genuinely mechanical FTC/FRC generated-host behavior into ARESLib runtime modules.
- Keep FTC and FRC lifecycle hosts and simulators distinct.
- Reduce examples/starters to canonical project documents plus explicit team hardware extensions.
- Prove generated starter mirrors contain no copied ARES implementation.
- Do not introduce speculative Systemcore targets until stable FTC and FRC APIs exist; design those
  as separate targets when they do.

Large legacy renderer implementations can now be split internally behind the typed IR and artifact
plan boundary. That cleanup should remain behavior-preserving and must not collapse domain, control,
hardware, simulation, and verification responsibilities merely to reduce file count.
