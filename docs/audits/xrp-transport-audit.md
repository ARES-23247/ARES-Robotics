# XRP transport and control lease audit

Pass 7 reviews the shared MicroPython telemetry server: socket ownership, bounded JSONL
framing, explicit mode revisions, control freshness, handshake identity, and simulator
field transactions. The robot facade and Studio link were traced as consumers; their
remaining implementation is not closed by this pass. Source commit: `faaa271a`.

## Confirmed findings and fixes

- **A late heartbeat could conceal an expired lease.** The robot polls before asking for
  its drive frame. Previously, a packet arriving after a long loop gap replaced the old
  timestamp before expiry was checked. Expiry now runs before input processing, and a
  neutralized request cannot arm again until a newer explicit request revision arrives.
  Negative clock deltas also invalidate the lease. The exact timeout boundary remains
  inclusive; expiry occurs when age exceeds the configured interval.
- **Invalid input could leave a pending Start or rearm the same request.** Neutralization
  cancels pending Start commands and invalidates the active request. Disconnect discards
  all pending commands and partial input. Sequence/revision booleans, negative sequences,
  nonnumeric drive values, and malformed selected-operation identities are rejected.
  Valid out-of-order packets remain ignored. A subsequent valid newer Start still works.
- **Receive memory was unbounded and UTF-8 was decoded per TCP chunk.** A peer sending
  no newline could grow the buffer indefinitely; splitting a multibyte character silently
  removed it. Input is now retained as bytes until a complete line is available. Invalid
  UTF-8 fails closed. Pending input and output each have a 16,384-byte limit; overflow
  disconnects and neutralizes. One receive call still reads at most 512 bytes per poll.
  Existing ordered partial-write and output-backpressure behavior is preserved.
- **Failed startup leaked owned sockets.** A listener becomes published only after all
  setup succeeds. Setup failure closes it, and repeated successful `start()` calls reuse
  it. Accepted clients are owned before their nonblocking setup, so failures close them.
- **A rejected field envelope could still replace collision geometry.** Payload ID,
  revision, and SHA-256 are now checked before calling the mutating field handler. The
  returned receipt is still checked afterward. Hashing imports/work occur only when a
  field-capable runtime receives a field update, outside ordinary control heartbeats.
- **Configuration and metadata could weaken the link contract.** Timeout validation now
  matches the project's integer 100..1000 ms constraint. Content identity must be a
  lowercase hexadecimal SHA-256. Optional runtime metadata cannot overwrite validated
  handshake protocol, role, project ID, content hash, or drivetrain type.
- **Desktop leases used wall time.** The desktop fallback uses monotonic time. Embedded
  execution retains `ticks_ms` and wrap-aware `ticks_diff`.

The socket implementation preserves partial sends because MicroPython explicitly permits
short writes. Embedded tick subtraction retains the documented wrap-aware operation.
Sources: [MicroPython socket contract](https://docs.micropython.org/en/v1.28.0/library/socket.html)
and [MicroPython time contract](https://docs.micropython.org/en/v1.28.0/library/time.html).

## Validation

Eighteen new deterministic tests cover the missed-deadline race, explicit restart,
deadline boundary, rewind/wrap, malformed control, pending-command cleanup, bytewise
UTF-8 fragmentation, bounded unfinished input, stale sequence ordering, field rejection
before mutation, failed socket setup/retry, configuration, and handshake metadata.
An integration regression drives the robot facade and a mechanism through a late
heartbeat: both stop, remain disabled, and resume only after a newer Start revision.

The initial 14-test regression run produced 30 failing assertions and one error against
the old implementation; four additional contract/integration tests were added afterward.
The shared Python suite now passes **62 tests**. XRP source/generated verification and
the separately extracted standalone starter each pass **107 tests**. These suites are
distinct: the starter verification does not include all shared-runtime unit tests.

The trace run includes imports and test discovery. It reports **90.9% executable-line
coverage** for `telemetry.py` and **99.1%** for the new regression file. These percentages
are not branch coverage or physical execution. Annotated results are under
`ARESLib-Kotlin/ares-micro/build/audit-pass7-trace/`.

The library test/API/local-publication gate and FTC, FRC, FTC-starter, and FRC-starter
consumer gates passed in dependency order. Unchanged JVM test tasks reused Gradle's
up-to-date results; FTC TeamCode tests executed. Their available reports contain 1,089
library and 291 robot/starter passing tests, with no failures or skips. This does not mean
those 1,380 tests all executed again in this pass. Studio's app suite executed and passed
1,209 tests with six opt-in skips; shared/gateway checks reused their 35 passing results.
Release alignment, source/archive policy, guidance, and links in 168 current documents
passed. The previously documented opt-in/hardware limitations remain open.

A separate native localhost TCP smoke test passed real accept/handshake, split UTF-8,
late-heartbeat rejection, fresh Start, telemetry reception, disconnect, and reconnect.
It bound only an ephemeral loopback port and closed its own client/listener afterward.
Evidence is `ARESLib-Kotlin/build/audit-pass7-loopback.log`; this one-off integration
check is separate from the retained 62-test unit suite and its line-coverage measurement.

All gates use isolated candidate **`17.0.3-rc.c363e29cff00`**, library source tree
`c363e29cff00a24cc0e6ff9d62f0e4382fae3099`. The unpublished XRP 3.0.3 archive SHA-256 is
`5919f689a410fb5f454c069e2f13920dd6fdbe5513e01911bbe54c05ab893f45`.
The other three deterministic archives reproduced unchanged. Logs are under
`ARESLib-Kotlin/build/audit-pass7-*.log`, `ARES-XRP-Starter/build/audit-pass7-verify.log`,
and `.codex-validation/audit-pass7-standalone/audit-pass7-standalone.log`.

## Limits and next coverage

The byte-buffer limit includes framing and any pending suffix. Oversized field documents
are rejected by disconnecting; this transport does not implement large-field streaming.
Bounded buffering limits accumulation, but JSON parsing, list/dictionary creation, and
buffer copies still allocate. This is not a zero-allocation Python loop or a measured
Pico execution-time bound. A field update can occupy several polls and expire active
control, requiring a fresh Start afterward.

Tests use CPython, socket doubles, and a local TCP smoke test, not a Pico radio, actual
MicroPython interpreter, or physical actuators. Untested paths include some socket/cleanup
exceptions, missing socket support, and handler contract violations. A handler that
mutates incorrectly and then returns an inconsistent receipt cannot be rolled back by
the transport; the built-in field handler remains responsible for atomic installation.

Continue with robot shutdown aggregation and loop allocations, then shared subsystem,
sensor, and autonomous runtime behavior. Studio's own bounded input reading also remains
open. The whole-repository audit goal remains active; nothing was pushed or released.
