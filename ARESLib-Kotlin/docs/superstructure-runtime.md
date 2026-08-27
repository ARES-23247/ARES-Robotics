# Generated Superstructure Runtime Contract

Canonical `.ares/superstructures/*.aressuperstructure` schema 3 documents coordinate complete
target postures across generated subsystems. They do not replace subsystem feedback controllers,
hardware IO, homing, current monitoring, neutral recovery, or season-specific strategy.

## Runtime order

For each robot-frame sensor phase, `SuperstructureRuntime`:

1. Initializes the entry timestamp from the supplied `RobotClock`-domain frame timestamp.
2. Applies the document's explicit disabled policy.
3. Evaluates cached-port health fallbacks.
4. Evaluates fault-directed automatic transitions in ascending explicit priority.
5. Evaluates one pending action request, including guards, debounce, and deadline fallback.
6. Evaluates ordinary automatic sensor/time transitions in ascending explicit priority.
7. Resolves a complete target preset into preallocated primitive buffers.
8. Preflights every generated target task before dispatching any target action.
9. Runs catalog-backed lifecycle tasks once per monotonic transition sequence, source exit before
   destination entry.
10. Publishes one immutable runtime snapshot when observable state changed.

Automatic transitions leaving one state must use unique priorities. Lower numbers run first; the
runtime never uses document list order or last-assignment-wins as an implicit policy.

## Typed cached ports

Documents refer to immutable subsystem and field UIDs. Generated bindings resolve those UIDs once
at construction into integer slots. The periodic evaluator reads primitive typed values and health
bits from cached Redux state only. It does not use string lookup, reflection, generic value maps,
or hardware getters in the hot path.

Health requirements are:

- `VALUE_ONLY`: type/finite checks only; advanced derived values.
- `FRESH_VALID`: valid sample within the descriptor-defined lease.
- `CONTROL_READY`: fresh/valid plus configuration, homing, calibration, current validity, and
  output health.

Canonical units are descriptor units. Pass-through ports must have matching units. LUT input and
output units must match their connected ports before generation.

## Faults and recovery

Health fallback policies execute before requested motion. A policy may latch its fallback, which
requires a legal explicit recovery transition after the port is healthy again. IO adapters remain
the final fail-closed boundary and must independently reject stale, invalid, unconfigured,
unhomed, reset, or failed outputs.

Lifecycle actions are parameterless project-catalog tasks. Missing or failed tasks fault the
coordinator. They are suitable for bounded effects such as indicators or logging, not for physical
clearance sequencing. Use transient postures with measured guards when one mechanism must stop and
be verified before another moves.

## Allocation boundary

After initialization and JVM warm-up, unchanged steady-state evaluation is required to allocate
zero bytes per tick. Transitions may allocate immutable Redux events and task objects. The
regression test measures repeated post-warm-up windows; this claim does not include document load,
generation, transitions, telemetry serialization, or hardware SDK internals.
