# FRC dashboard input lifecycle audit

Pass 107 reviews simulator dashboard command processing, receiver freshness, axis mapping and cleanup.

## Reproduced defects

Two tests failed against the original input class using isolated local NetworkTables instances:

1. Polling after close still returned the previously leased gate. Closed subscriptions yielded no
   updates, but the cached command remained eligible until its lease expired.
2. A throwing drive-subscriber close skipped the remaining close calls. Both owned publisher topics
   were still published after the failure.

The input now records closure before cleanup and returns no command on subsequent polls. Cleanup
uses the existing season failure collector to attempt simulator disable, state publication and every
subscriber/publisher close before rethrowing the first failure. Repeated close calls do not retry
already-disposed resources. Operations retain their existing single robot-loop ownership assumption.

## Efficiency and contract review

The simulator-enable expression repeated `receiverReady(nowMs)` after a non-null command already
proved readiness, mode and field-centric validity at that same timestamp. That redundant check is
removed. Command recognition uses trimmed case-insensitive comparison, avoiding a separate uppercase
string. NT queue allocation and acknowledgement publication remain; this does not claim a zero-GC
poll loop or measured timing improvement.

The existing five-method mapping/protocol test file was read in full and executed. It checks neutral
handshake, FRC axis signs/scaling, lease expiry, explicit mode/alliance flags, invalid frames and receiver
acknowledgements. This test review does not newly certify the entire shared protocol implementation.

## Added integration coverage

Four new methods exercise actual local NT input construction and publisher/subscriber lifecycles:
closed-command revocation; injected subscriber-close failure with both publisher lifetimes checked;
queued neutral/motion frames, expiry, renewed handshake and mode rejection; and simulated driver-station
enable/disable requests. The latter covers lower-case padded commands, no enable without a fresh frame,
expiry disabling, explicit disable and close disabling. Tests close their isolated NT instances; the
driver-station test resets its simulated enable/attachment state. The initial two-method run had two
failures, preserved as XML. A first expanded full run passed before the fourth test was added.

## Remaining scope

The source file remains partial for failures inside its constructor before all subscribers/publishers
are acquired, concurrent close/poll misuse and delayed queue/backlog timestamp interpretation. The
current lease is based on receiver polling time; this pass does not prove network-arrival age bounds.
No physical Driver Station, real robot output, interactive simulator or loop-time measurement is claimed.

The change is FRC season-only and uses unchanged library candidate `17.0.3-rc.de2cb9c407a0`.
Validation is scoped to full FRC tests, generated-project checks and repository policy.

## Final evidence

Full FRC validation passed 180 tests, including four new lifecycle/NT tests and five existing dashboard mapping/protocol tests, with zero failures, errors or skips. Generated-project and namespace verification and monorepo policy passed. Gradle reused valid unchanged outputs.

Copied FRC JUnit XML and successful logs, with verified SHA-256 hashes, are recorded in `ARESLib-Kotlin/build/audit-pass107-verified-evidence/summary.json`. Initial failure XML is preserved in `ARESLib-Kotlin/build/audit-pass107-before-evidence/`.
