# Replay correctness and state-flow delivery

Pass 92 reviews `ActionReplay.kt`, the new internal `ActionReplayJson.kt`,
`CoroutineExtensions.kt`, and `VisionExtrinsicCalibrationAction.kt`. Store reduction
and subscription boundaries were traced without changing Store's public contract.

## Confirmed failures and fixes

Ten new regression methods failed before their corresponding fixes:

- Replay folded actions directly through `rootReducer`, omitting the Store-owned EKF
  runtime. An odometry log produced zero estimated displacement while the live Store
  moved. Replay now dispatches into an isolated Store with the supplied reducer, including
  derived estimator transitions. Delayed vision, retained earlier states, and live final
  state now match. No hardware listener is installed and RobotClock is left unchanged.
- A slow flow collector could fill the callback buffer; ignored `trySend` failures then
  silently lost state updates. Overflow now closes collection with a descriptive exception.
  Dispatch does not wait for buffer space. A caller can still explicitly select a dropping
  policy. Collection cancellation removes the observer.
- `waitUntil` threw when a finite flow ended without a matching state. It now returns false
  on normal completion or timeout while preserving upstream/predicate failures and cancellation.
- Missing timestamps, missing drive fields, unknown enum values and numeric-string timestamps
  could decode into different/default values. Known core fields now have explicit required/null
  and JSON-type checks; every action requires an exact timestamp.
- Subsystem timestamp conversion truncated fractions or overflowed integers. Exact decimal-to-
  integer conversion rejects these records. The timestamp is normalized once for decoding.
- Duplicate JSON members silently overwrote earlier values, and single-quoted non-JSON input
  was accepted. The new token reader rejects duplicates before materializing objects and avoids
  JsonParser's implicit lenient mode. It preserves numeric spelling, including negative zero,
  and exact integer conversion also reaches nested Gson adapters.

The initial state-flow snapshot previously preceded registration. Setup now registers the
observer and captures the initial state under the Store monitor, with callbacks behind a
short observation lock. A deterministic test holds the Store monitor until the producer is
confirmed waiting on it, dispatches a change, then verifies the initial snapshot includes it.
This closes the lost-update window. Concurrent dispatchers retain Store's existing callback
ordering contract and can notify out of reduction order; a single dispatch owner remains the
normal robot-loop contract. An unconfined/custom coroutine dispatcher may execute collector
code inline, so nonblocking buffer operations do not establish a physical execution deadline.

## Ownership, efficiency and compatibility

Reflection metadata for built-in action fields is cached. JSON containers use an explicit
stack rather than recursive application traversal. Parsing still completes before any reducer
executes, so a malformed later record cannot leave a partial replay presented as complete.
The returning API intentionally retains all actions and states; memory grows with log size.
No arbitrary log-size cap or claim of allocation-free replay/flow operation was introduced.

Core nullable targets and optional vision deviations remain supported. Registered custom
actions/states own their nested schema and domain validation and must encode a timestamp;
generic Gson decoding is not a proof that every custom constructor invariant holds. Unknown
extra payload fields retain Gson's existing compatibility behavior. JSON syntax follows the
legacy-compatible JsonReader mode, not a claim of exhaustive RFC 8259 strictness across every
Gson release. The implementation avoids newer-only Gson APIs used outside the FTC SDK surface.

Calibration actions are events, not hardware adapters. Their KDoc now describes radians,
producer-defined transform layout, authorization ownership, and the mutable caller-owned
array. Logging snapshots that array before queuing; no speculative transform layout, deep-copy
cost on every action construction, or actuator policy was added.

The flow behavior was checked against the official Kotlin
[callbackFlow](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/callback-flow.html),
[trySend](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-send-channel/try-send.html),
and [firstOrNull](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/first-or-null.html)
contracts. Exact integer conversion follows
[BigDecimal.longValueExact](https://docs.oracle.com/javase/8/docs/api/java/math/BigDecimal.html#longValueExact--).
The [Gson JsonReader API](https://www.javadoc.io/static/com.google.code.gson/gson/2.8.5/com/google/gson/stream/JsonReader.html)
provides the older token-reader surface used here.

## Final validation

Pending final focused/older-Gson evidence and the frozen library/consumer matrix.
