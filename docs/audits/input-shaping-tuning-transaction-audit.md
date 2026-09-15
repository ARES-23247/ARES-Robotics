# Input snapshots, radial shaping and tuning transactions

Pass 233 reviewed AresGamepad input sampling and analog math, tuning transport polling,
typed runtime initialization and metadata ownership. It does not repeat pass 232's
gamepad wire mapping or NT4 accessor review. Tuning file persistence and recovery after
transport failures remain partial. No upstream dependency source was changed.

## Input correctness and efficiency

Previously, analog callbacks ran while later axes and all digital button properties
still held the previous frame. Early callbacks could also mutate the caller's input
buffer before later axes sampled it, even though the gamepad wrapper already held an
owned copy. Digital callbacks saw later buttons before their current transition was
published. Sampling now completes for every analog and digital control before callbacks
run, preserving callback order. Two retained state buffers swap roles, eliminating the
redundant copy of the entire prior frame. Calls remain owned by one non-reentrant loop.

Radial shaping previously normalized a saturated vector, recomputed its magnitude and
applied the deadband to that rounded value. For a deadband immediately below one, a
full-scale diagonal could become zero. Shaping now uses one original magnitude and a
capped scalar radius for deadband and exponentiation before projecting to Cartesian
components. This also removes repeated hypot and duplicated radial scaling. Independent
polar/signed-magnitude oracles cover 1,936 input/threshold/exponent combinations, and a
separate full-scale case covers the near-one threshold. Direction-change checks verify
the Euclidean slew-distance bound; no physical timing improvement is inferred.

Nonfinite inputs now neutralize immediately even when a slew limiter previously held a
nonzero value. One invalid stick component invalidates that pair. Recovery begins from
neutral through the configured slew limit. Ordered clock checks prevent signed elapsed
overflow from confusing forward advances with rewinds, and an explicit initialized flag
allows Long.MIN_VALUE to be a real sample. The first interval remains 20 ms, the forward
cap remains 200 ms, and equal or backward timestamps produce no slew advance.

All 35 digital controls are checked for held-through-INIT suppression, release, repress,
edge and level behavior. Existing toggle tests preserve authoritative Redux state and
existing primitive callback/allocation checks remain in the focused suite. Invalid
feedback handling does not add an enable or bypass actuator safety.

## Tuning correctness and efficiency

Malformed integer requests previously fell back to the current value and could invoke
the consumer and report APPLIED. Missing or incompatible values could do the same.
All five declared payload types now reject absent/incompatible data; numeric requests
also retain finite, integral and range validation. Valid false, sentinel-valued text,
signed integer limits and finite doubles remain accepted. No new public telemetry API
is required. Nonces retain the exactly representable double integer bound and an
acknowledgement publishes Current/LastResult before ProcessedNonce.

Every proposal now obtains fresh arm/disable context. If the first callback disarms a
session, a later proposal in that poll is blocked. Metadata refresh no longer overwrites
pending requests or erases completed acknowledgements. Initial publication still creates
the request/acknowledgement topics. Polling works with signed mock-clock timestamps,
rebases a rewind without applying, and handles large forward advances without overflow.

Runtime construction now validates declarations, effective initial values and exact
agreement with transport metadata. Unknown canonical keys, duplicate declarations,
invalid initial values and mismatched policies fail before a consumer reads them.
Declaration options and metadata lists are owned immutable snapshots, so later mutation
of constructor inputs cannot change advertised or accepted tuning. Returning to the
canonical value removes a redundant local experimental assignment.

Only consumer failure rolls back a staged tuning value. A later telemetry failure cannot
roll back the store while the controller retains the accepted value. If both the consumer
and diagnostic publishing fail, the consumer exception remains primary and the diagnostic
failure is suppressed. These cases have regressions; retries of incomplete transport
acknowledgements and cross-writer transactions are not claimed solved.

Idle polling now reads cached nonce topics without constructing topic strings, looking
up current values or querying apply context. The original implementation allocated
2,720,000 bytes per 20,000 idle polls in each of two measured windows. The corrected
implementation measured zero in both warmed windows. The test retains escaping topic
keys and checks 140,000 reads across five warmups and two measured windows. This measures
one declared idle parameter with a primitive test backend, not network or whole-loop
allocation. Explicit proposals still allocate for validation and optional persistence.

## Evidence and validation

A broader suite exposed another 480,000 bytes per 20,000 polls after the first cache fix,
despite the focused run measuring zero. Bytecode showed nullable nonce checks boxing Double
and Long values; those checks now use primitive comparisons and conversion. The allocation
assertion was not relaxed. Both earlier candidate identities remained unpublished and were
superseded; full validation below belongs to the final identity.

The original runtime failed 21 assertion scenarios among 23 new baseline cases. Those
are not 21 distinct bugs. Five additional regression methods cover exception handling,
payload-type boundaries and topic identity. The final focused suite contains 69 passing
tests, including 28 new methods, existing drive-facade/tuning tests and ZeroGcRegressionTest.
The new cases run alongside the existing AresGamepadDslTest allocation check.

One fixture-only compile attempt omitted abstract telemetry stub getters; those were
completed before the behavioral baseline. An initial API check caught two sentinel
constants unintentionally exposed from a private Kotlin companion; explicit private
visibility fixed them without changing the API catalog. These diagnostics are separate
from the runtime baseline. Final source/API/candidate identities are recorded below.

The first full library run encountered another simulator listening on production port 5002.
Six log-server tests failed their bind precondition; the new input and tuning tests passed.
The test fixture now uses NanoHTTPD's existing socket-factory hook to bind loopback port zero
directly, uses the actual bound port for every HTTP/socket connection, and restores its prior
factory after stopping its owned listener. All six targeted tests passed alongside the other
simulator. Production ports and library server code are unchanged. One intermediate fixture
run exposed a leftover fixed listing URL; it was corrected without weakening the rate limiter.
The initial candidate was superseded before any publication; the identity below includes the
test isolation fix. No existing application or simulator was stopped.

Source commit `fca5e5986d1071a646e571e3dbd6ff390309b4e3` binds library tree
`1387676e0c2bdeb795f0e9ac842f156bae493b17`. Candidate `17.0.40-rc.1387676e0c2b`
was validated locally. Versions are ARES/FTC/FRC starters 17.0.40, Studio 7.0.40
and XRP/Lightbot 3.0.39.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,860 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,046 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |
| Headless browser fixture checks | 9 | 0 |

There are 5,704 passing results, zero failures/errors and six unchanged Studio opt-in skips.
The skips cover three starter integration scenarios, native file chooser, dashboard performance
baseline and physical dashboard validation. Gradle results may be executed, up-to-date or cached.
FTC/starter generated-project checks and APK assembly passed; FRC/starter generation checks passed.
All 410 candidate file hashes were reverified after consumer validation. Monorepo policy passed,
including links in 395 current documents and 38 historical skips. Four normalized starter archives
differ only in release version properties. The final full library suite also measured zero idle
tuning allocation in both 20,000-poll windows.

## Coverage and limits

The ledger accounts for 3,028 tracked files: 1,341 fully reviewed, 182 partially reviewed
and 1,505 pending, with zero stale or orphaned records. This is file review and appropriate
validation accounting, not universal executable test coverage.

The complete file review covers AresGamepad, TuningTopics, the TypedTuningConsumer
interface, three existing test classes and both new test classes. Interface and topic
declarations are accounted for through source/consumer inspection, compilation and
applicable behavior tests rather than invented executable coverage.

TuningManager remains partial for interrupted acknowledgement recovery, concurrent
writers/reentrancy and optional persistence timing/failure paths. TypedTuningRuntime's
in-memory policy/initialization/overlay construction were reviewed and tested; the
co-located LocalTuningOverlayStore still needs filesystem alias/race/failure auditing.
No live robot network, rendered Studio window, physical loop/CAN timing, HIL or remote
GitHub Actions execution is claimed. All changes and candidates remain local.

Evidence: `ARESLib-Kotlin/build/audit-pass233-verified-evidence/`, including baseline and
focused XML, allocation output, validation logs, immutable candidate hashes and archive
comparisons. The wider monorepo goal remains active.
