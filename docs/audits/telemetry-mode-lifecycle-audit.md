# Telemetry mode transitions, numeric fidelity and shutdown

Pass 75, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Findings and changes

- CSV and action mode transitions synchronously drained old loggers and constructed new
  ones inside publishing. Each stream now retains one bounded queue and worker. Pooled
  queue envelopes capture mode alongside the owned frame/action; the worker drains and
  switches files in record order. Initial construction and final drain still involve IO.
- CSV's custom four-place formatter clamped integer carry, saturated large doubles through
  Long conversion, and erased tiny values. Lossless double text now preserves rounding
  boundaries, signed zero, finite extremes and subnormal values on parse.
- Logging skipped the first frame at timestamp zero and could freeze on replay rewind.
  First/rewound/overflowed intervals now emit; ordinary throttling remains. Pending values
  flush under their original mode at mode changes and on close, without mixing fields.
- Closed telemetry accepted writes, could create another mode logger, and closed backends
  repeatedly. Publication is now terminal and close is idempotent. FTC close detaches only
  its owned action callback, preserves a successor, and joins its unblocked console worker.
- The first action before the first new-mode publish could retain the old mode. The Store
  listener now captures the mode atomically with action encoding. A throwing custom
  publisher no longer leaves out-of-frame NT4 forwarding disabled.
- A bounded FIFO could reject the newest Driver Station snapshot under backpressure. One
  latest-snapshot slot now retains the newest pending state without idle polling. Literal
  text selects the SDK value overload, avoiding accidental formatting of percent characters.
- HardwareRegistry drained auxiliary closeables before closing registered devices. Device
  closure now precedes service drains, while identity deduplication and best-effort teardown
  remain. A blocked logger cannot delay those device close calls.

## Evidence

Five of six initial core lifecycle/format fixtures failed, five of six FTC fixtures failed,
and the independent blocked-service registry fixture failed before its fix. Baseline logs
and copied XML are `ARESLib-Kotlin/build/audit-pass75-{before,ftc-before,registry-before}.*`.
The original FTC slow-console failure exposed the String formatting-overload problem;
the corrected literal-console fixture also verifies newest-snapshot delivery after blocking.

The final focused invocation passed 46 methods: 39 core logging/action/replay/registry
checks and seven FTC lifecycle checks. Tests use owned latches/threads and temporary core
log directories, verify exact mode/value attribution after a blocked writer, retain
enqueue-time mutable-action snapshots, and check shutdown ordering and deduplication.
The additive API contains ActionLogger.beginMode and explicit-mode logAction only.
Final candidate validation is pending.

## Scope limits

The changed logger, telemetry and registry shutdown paths were reviewed. Broader disk
failure/finalization/retention behavior, generic registry mutation, shared robot status and
proxy ownership remain separate open scopes. Final logger drain intentionally waits for
accepted records; it is not bounded against a permanently unresponsive filesystem. The
Driver Station join is bounded to one second and cannot cancel a stuck SDK call. Constructor
file IO remains on the initialization path. Host checks do not establish physical robot
jitter, storage durability under power loss, or successful stop of a disconnected actuator.
All work remains local; no push, merge, remote publication or device action is included.
