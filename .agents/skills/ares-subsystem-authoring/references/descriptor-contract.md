# Descriptor contract

Read the current descriptor schema from `ARES_SUBSYSTEM_SCHEMA_VERSION` in `ARESLib-Kotlin/project-schema/src/main/kotlin/com/areslib/subsystem/SubsystemDocument.kt`. Verify that constant before changing schema-sensitive code; do not pin a version in this guide.

Do not couple the descriptor schema to an ARES release-major assumption. Do not add older-schema fallbacks or silently reinterpret old descriptors. Migrate canonical project documents explicitly.

The current schema requires component-owned `tuningParameters` (an empty list is valid). Each declaration has a stable UID/key/owner,
type, unit/bounds/options/default, novice-facing explanation, and explicit apply policy. Canonical
values live in named `.ares/tuning/*.arestuning` profiles; runtime experiments stay under
`.ares/local/tuning` and require reviewed promotion before canonical replacement.

## Identity and natural hardware state

- `displayName` is novice-facing text; `kotlinTypeName` names generated classes; immutable `uid`
  values preserve editor selection/history while code IDs are renamed with reference-safe updates.
- Adding hardware scaffolds explicit normal state instead of hidden inference. Motors expose cached
  position, velocity, and current; servos expose their normal command/position; sensors expose typed
  readings. Additional state remains optional and explicit.

## Homing and control

- Homing is a bounded state machine with explicit request, actuator/search output, cached evidence,
  dwell, timeout, and assigned zero. Supported evidence includes digital sensors, current stall,
  velocity stall, combined current-and-velocity stall, and custom typed measurements.
- Feedforward is typed as none, simple motor, elevator, or arm and declares `kS`, `kV`, `kA`, `kG`
  plus optional desired velocity/acceleration and required arm-angle references.
- Followers reference one independent leader of the same actuator kind. They cannot own control
  loops or homing. Motor/continuous-servo followers may invert; positional servos may mirror 0–1.
- Per-device `inverted` corrects physical mounting and is distinct from the follower transform.
  Relationship transforms are applied first, then device inversion, in physical and mock adapters.
  Physical and mock adapters must command and safe the group consistently.

## Implementation choices

- `GENERATED_STARTER` + `GENERATED_STARTER` ownership: generator may create missing starter files and replace only generated-starter files after preview/token confirmation.
- `HAND_AUTHORED` + `USER_OWNED` ownership: generator validates metadata and emits mechanical registration reminders only; it must not generate, guess, or replace source.

## Hand-authored metadata

Declare:

- `modulePath`
- `sourceFiles`
- subsystem, IO contract, and hardware adapter class names when applicable
- simulation support and adapter class
- teaching level, summary, documentation path, and concepts
- explicit `capabilityActionKeys`

Validate source paths are project-relative, classes are explicit, and every capability action exists in the action catalog.

## Generated responsibilities

Keep state/domain, controller, IO, hardware, simulation, and verification conceptually separate. Mechanical registry/DSL plumbing belongs under `build/generated`. A generated registry may omit hand-authored factories when constructor wiring is season-owned, but must make that omission explicit.
