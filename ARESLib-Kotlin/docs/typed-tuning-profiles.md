# Typed tuning declarations and profiles

ARES tuning has two owners: components declare what a parameter means, while the robot project owns
named value profiles. Runtime JSON reflection over arbitrary Redux state is not part of this
contract.

Declarations come from drivebase documents, subsystem documents, and optional project/global
`.ares/tuning-components/*.arestuningcomponent` documents. Each declaration includes a stable UID,
component UID, novice-facing name/description, type, unit, bounds, default, and application policy:

| Policy | Runtime behavior |
|---|---|
| `LIVE_SAFE` | Applies only in an explicitly armed tuning session. |
| `DISABLED_ONLY` | Requires an armed session and disabled robot. |
| `RESTART_REQUIRED` | Never changes the running value. |
| `REBUILD_REQUIRED` | Never changes the running value; checked-in regeneration is required. |
| `CALIBRATION_ONLY` | Requires an armed calibration session authorizing that parameter UID. |
| `READ_ONLY_VENDOR` | Display-only; runtime mutation is always rejected. |

Values are exactly one of double, integer, boolean, text, or a declared enum choice. Unknown UIDs,
wrong types, non-finite numbers, and values outside bounds are rejected.

## Canonical profiles and local experiments

Checked-in robot-owned profiles live in `.ares/tuning/*.arestuning` with authority
`CANONICAL_CHECKED_IN`. A profile may name at most one explicit parent. Composition is deliberately
shallow: parent values are copied, then child values replace matching parameter UIDs. Missing
parents, cycles, deeper chains, duplicate assignments, and unknown parameters fail validation.

Robot-local experiments live only in `.ares/local/tuning/*.arestuning` with authority
`LOCAL_EXPERIMENTAL`. Runtime persistence is atomic. Codegen never reads this directory and runtime
code never writes `.ares/tuning`, so an experiment cannot silently become authoritative.

Promotion is an explicit authoring action that creates/updates checked-in canonical data. Promotion
records the source local-profile UID/hash, matched evidence paths/hashes, reviewer, and review
summary. Local profiles cannot claim that they were promoted.

Generated direct constants and typed runtime access use the same resolved canonical values. Robot
constructors, Redux initial state, controllers, physical IO, and simulator setup must consume this
single source so the first periodic update cannot replace constructor defaults with different Redux
defaults.

## Live request and acknowledgement transport

Parameters use `Tuning/Parameters/<declaration UID>/...` topics. Studio enqueues the typed
`Requested` value followed by its numeric `RequestNonce` in one NT4 binary frame on the same ready
connection. A full outgoing queue accepts neither update. Each request must still pass the robot's
policy, armed-session and compiled-consumer checks; a successful enqueue alone is not an apply.

The robot publishes `Acknowledgement` as one string using the shared `TuningAcknowledgementCodec`:
`1|nonce|RESULT_CODE`. Nonces are canonical decimal integers in 0 through 9,007,199,254,740,991;
result codes are bounded uppercase identifiers. The initial empty string means no result yet.
The version, nonce and result are decoded together, so delayed or reordered scalar topics cannot
associate a new nonce with an old `APPLIED` result. Metadata refresh preserves the last acknowledgement.

`Current`, `ProcessedNonce` and `LastResult` remain available for diagnostics and older consumers.
Their publication order does not guarantee delivery order through an NT4 server. Studio verifies
an experimental apply only from a matching atomic acknowledgement on the original connection.
An older robot providing only scalar acknowledgements leaves the result unknown and needs updated
robot code before Studio can verify live-test results. Canonical profiles are never changed by a
live request, rejected request, timeout or reconnection.
