# Store failure boundaries and custom replay registration

Pass 93 closes the remaining Store failure and custom replay registration scopes from
pass 92. It also traces the logger's final JSON writer and reads all four existing
Store snapshot-immutability tests. The prior pass's odometry, flow delivery and generic
JSON validation tests are reused as regression evidence.

## Confirmed issues

Eight new test methods failed before their fixes:

- A custom action using only RobotAction's live-clock timestamp getter could enter a
  completed log without a timestamp. Logging now rejects that payload before queueing,
  increments the existing dropped-action counter, and preserves subsequent valid records.
- A custom getter could disagree with its serialized timestamp. Encoding and decoding
  now reject observed disagreement with the exact recorded epoch. Custom actions must
  implement a stable timestamp; one comparison cannot prove an arbitrary getter's future
  behavior, and the codec does not change RobotClock to accommodate a live getter.
- Gson omitted explicit nulls from custom action and subsystem payloads. A class with a
  no-argument constructor could then restore its non-null default instead. Both the
  snapshot encoder and final asynchronous writer now preserve null members. End-to-end
  logger tests verify this boundary for both payload forms.
- Estimator preparation mutates private EKF history before the configured reducer
  returns. If public or derived reduction threw, the old published snapshot remained
  visible while later dispatch could reuse changed history. A reduction failure now
  invalidates the Store: both dispatch entry points reject further work before action
  observers, retain the last published snapshot, and expose the original failure as
  the cause. The original failing call rethrows the original object, including Errors.

## Efficiency and failure contract

Healthy dispatch adds a null check under the existing monitor. There is no history
checkpoint, rollback copy, additional lock, or healthy-path failure allocation. A failed
Store must be replaced under lifecycle-owner control after neutralizing hardware; a new
Store seeded from a snapshot does not restore its earlier delayed-measurement history.
FTC and FRC base update loops already latch fatal update failures, invoke safeHardware,
and rethrow. Those call sites were traced; no hardware execution is claimed.

Action-observer failure occurs before preparation and does not invalidate the estimator.
Subscriber failure occurs after commit and retains the existing synchronous fail-fast
notification order. Batch dispatch serializes actions but is not a rollback transaction:
an error retains the committed prefix and skips final notification. These distinctions
are documented and tested rather than presenting every exception as transactional rollback.
Empty successful batches retain their existing notification behavior.

Timestamp checks at the logging boundary apply to custom actions; built-in action classes
already own Long timestamp fields. Registry lookups remain unchanged. No speculative
registration concurrency mechanism or arbitrary custom-schema validator was introduced.
Null preservation adds the necessary explicit JSON members; schema version 1 and public
method signatures remain unchanged.

## Registration and coverage

Twelve new registration/codec tests cover aliases, class-name overloads, repeated identical
registration, name/class collisions, blank names, built-in name/class protection,
unregistered-type failure, mutable nested snapshot ownership, timestamps, and nulls.
Failed registrations leave both lookup directions intact. Six new Store tests cover
public/derived reduction failures, both dispatch entry points, observer versus reducer
failure, committed batch prefixes, successful batch notifications, and empty batches.

Store and ActionReplay can now leave their prior partial-review status. Custom season
constructors, adapters and nested domain schemas remain the responsibility of their owning
files; reviewing the registry does not validate every possible application class. A
custom reducer must remain pure and compose the root reducer as required by repository
guidance. Recursive dispatch from reducers/action observers is outside that contract.

## Final validation

The final source passed 52 focused tests and all API checks before freezing. Eighteen new methods include eight failure-before regressions. A separate replay run passed 29 tests with cached Gson 2.8.5 substituted for the normal runtime. Public signatures are unchanged.

Source `e179d16b500ae60b236e6a9e2c905e5b934f5f49`; library tree `b83ebb7aea38d94993d54add1415b638e239867e`.
Local candidate `17.0.3-rc.b83ebb7aea38`.

| Scope | Passed | Skipped |
| --- | ---: | ---: |
| library | 2114 | 0 |
| ftc | 111 | 0 |
| frc | 148 | 0 |
| ftc-starter | 14 | 0 |
| frc-starter | 34 | 0 |
| studio | 1790 | 6 |

All groups have zero failures/errors. Library API/Kover/local publication, generated-project verification, FTC assembly, Studio Kover/version/file-size gates and monorepo policy passed. Gradle reused valid unchanged outputs; counts do not imply every test was freshly executed. Conditional Studio skips remain recorded in XML.

Copied XML, hashes, logs and candidate BOM identity are under `ARESLib-Kotlin/build/audit-pass93-verified-evidence/summary.json`. Focused and older-Gson XML are under `ARESLib-Kotlin/build/audit-pass93-focused-evidence/`. No physical robot timing, hardware fault recovery or usable Studio-window result is claimed. ActionLogger remains partial for disk failure/finalization behavior beyond this codec boundary.
