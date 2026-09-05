# Subsystem review checklist

## Ownership and regeneration

- Every artifact has `USER-OWNED`, `GENERATED STARTER`, or `GENERATED - DO NOT EDIT` ownership.
- User-owned and unknown existing files are protected.
- Starter replacement requires a current structured diff and hash-bound token.
- Deleting the final descriptor clears stale generated plumbing.

## Runtime safety

- Inputs are cached once per loop with validity/freshness.
- Nonzero output requires configuration health and required homing.
- Declared safe output is honored for every actuator type.
- Failed nonzero and failed neutral writes behave fail-closed.
- Recovery requires an explicit successful neutral.
- Disabled, stop, fault, and close paths neutralize idempotently.

## Verification and teaching

- Generated tests exercise behavior rather than only source shape.
- Mock and real adapters share command limits, homing, faults, and close semantics.
- Hardware-created natural state remains explicit/editable, and ID renames update references without
  changing immutable editor identity.
- Sensorless homing has bounded output, fresh evidence, dwell, timeout, neutral-before-zero, and
  latched recovery coverage.
- Feedforward units/references are validated and its output is combined with feedback before limits.
- Leader/follower transforms, group neutral, follower-write faults, and mock/physical parity are tested.
- Device mounting inversion is visibly distinct from follower direction and is applied consistently
  for motors, positional servos, and continuous servos on FTC, FRC, and mock adapters.
- Public generated members document units, validity, safe output, and recovery.
- UI explains each artifact, its destination module, runtime flow, safety warnings, and generated-vs-owned status.
- Major concepts have keyboard/hover explanations or documentation links; interactive teaching labs
  never command hardware or imply physical validation.
- AI form proposals send only the descriptor plus the student's request, preserve platform/revision/
  ownership/catalog identity, and use the canonical draft/undo/validation/diff path. Invalid
  proposals cannot be applied; applying never saves, generates, or writes source directly.
- Every editable builder field has keyboard-focusable hover help and a stable guide anchor. Accent
  foregrounds meet WCAG 4.5:1 in normal, colorblind, and high-contrast palettes.
- Hand-authored examples remain runnable and documented.
