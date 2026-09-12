# Controller input validity, timing and lifecycle audit — pass 207

Scope: the previously pending shared controller-input pipeline and control DSL, plus
FTC/FRC sampling adapters needed to preserve the input validity contract. This pass
reviewed ten production files in full and twelve associated test files. It follows
pass 206's spline-heading and lighting-timeline work. Changes remain local.

## Confirmed issues and fixes

Missing, unwritten and nonfinite axes previously read as raw zero. With a calibrated
center of 0.5, that fallback became a fully negative command. Axis-threshold sources
could activate on disconnected feedback or retain an activation through release
debounce. Reusable frames now track explicitly written finite axes and written
buttons separately from their neutral read values. Bindings immediately terminate
when required feedback is unavailable and require a valid neutral sample to re-arm.
Legacy raw reads still return zero/false for unavailable inputs.

Both platform adapters preserve malformed-axis invalidity instead of converting it
to an apparently valid raw zero. FRC raw-button availability reflects actual writes,
independent of the fixed reserved POV indexes. An absent or malformed POV remains
unavailable; a reported POV at -1 is an available neutral control. An undersized
adapter frame is cleared before rejecting its configuration. These changes are in
ARES adapters; this pass did not identify or modify a WPILib implementation defect.

Copying a frame onto itself previously erased its values. Self-copy now preserves
contents and sequence; ordinary copies preserve validity as well as values. Invalid
sample counts, out-of-range writes and undersized copies clear old feedback before
throwing. Button bitmap sizing now computes ceiling division without signed integer
overflow, with positive-capacity validation before allocation.

Finite calibration endpoints can have an infinite difference in Double arithmetic.
Normalization now uses half-scaled differences only for an overflowing span, preserving
ordinary and subnormal ranges. Span terms are computed during construction, and the
default exponent of one skips a power operation. Finite slew rates now retain their
limit when both requested delta and allowed movement overflow: a half-scaled comparison
and step can recover a representable intermediate output. ON_CHANGE always emits a
return to zero after a nonzero command, even when that final change is below epsilon.

Long.MIN_VALUE was both a valid press timestamp and the sentinel for no previous press,
allowing a second activation through cooldown. A separate initialization flag fixes
that collision. Repeats now schedule relative to held duration, avoiding saturated
absolute deadlines that fired early near Long.MAX_VALUE or repeated indefinitely at
one timestamp. Missed intervals remain coalesced into one callback per update. Chord
spread subtraction now rejects overflow instead of treating very distant presses as
simultaneous. Runtime time-history initialization also uses an explicit flag.

A throwing release, reset or zone-exit callback could previously leave active state
behind and prevent other bindings from stopping. State is cleared before cleanup
callbacks; cleanup attempts every child, binding, zone exit and owned analog neutral
output. The first failure is retained and distinct later failures are suppressed.
Update and runtime-clock failures follow the same cleanup path. This guarantees
cleanup attempts and cleared binding state, not successful physical output when an
external callback or hardware write itself fails.

Cancellation during callbacks could recursively release the same binding or allow
later held/hold/zone callbacks to execute after cancellation. Generation checks stop
that update, and termination guards prevent recursive cleanup. Recursive updates
are rejected and unwound. Zone state is recorded before entry callbacks so a failed
entry is exited once. Chords require every physical member to return to neutral after
cancellation; a partly released chord or a suppressed held button cannot satisfy re-arm.

Suppression was keyed only by a frame sequence, so replacing a frame with another
object at the same sequence could inherit stale consumption. It now keys on object
identity plus sequence. Long generated binding IDs could collide after truncation and
then exceed the 64-character limit when suffixed. Collision suffixes now reserve their
own space. DSL construction reuses the identifier regex and one chord-member list.
Those DSL savings occur at construction, not in the robot loop.

## Validation

Before the fixes, all 13 numeric regression methods failed; nine of ten initial
lifecycle/DSL methods failed. Their XML results were preserved before subsequent runs.
The completed pass adds 36 test methods: 18 numeric/validity, 13 lifecycle/DSL, one
allocation measurement and four adapter methods. Existing input and adapter tests
also verify ordinary debounce, hysteresis, repeats, reconnects, raw index mapping,
POV directions, frame copying and the novice DSL. The complete library suite passes
2,301 tests, including the required zero-GC regression suite. API checks pass.

Numeric tests use BigDecimal differences of the represented Double inputs as an
independent calibration oracle, plus endpoint/monotonicity checks across ordinary,
subnormal and extreme ranges. Slew cases assert independently calculated intermediate
movement. Timing cases cover minimum/maximum timestamps and exact exhaustion.
Lifecycle tests inject callback/reset/clock failures, test reentrant cancellation and
update rejection, and verify neutral outputs, once-only release and suppressed failures.

The warmed desktop JVM allocation test recorded 176 bytes across 10,000 updates
(271.42 ns/update in that run). It exercises reusable sample/copy operations, thresholds,
chords, suppression, shaping, finite slew, zones, disconnects and cancellation. This
measurement uses explicit update timestamps and preallocated nonallocating callbacks;
it is not a bound on user callbacks, clocks, SDK polling, a complete robot loop, GC
pauses or target hardware timing. No pre/post speedup is claimed.

DigitalSource adds default availability and neutral-state methods so custom sources
can express their feedback and composite-neutral policies. javap confirms actual JVM
default methods; existing implementations inherit connected/!sample behavior. InputFrame
adds axis/button availability queries. The API snapshot contains additive methods and
the compiler-generated default bridges, with no removed signatures.

Source commit: `b5dda86de15e4b9ee9ff995241bc302d736453ae`.
Exact library tree: `ac39695702dc468db823e5418fd9368044a628f2`.
Candidate: `17.0.14-rc.ac39695702dc`. Publication is confined to the isolated local
validation repository. Its manifest records 410 publication-file SHA-256 values.
Consumer builds use that exact candidate and the absolute local repository after
publication. Starter bundle content comparisons found only dependency-version
properties changed; hashes and workflow pins were refreshed for the new versions.

| Validation scope | Tests | Passed | Skipped |
| --- | ---: | ---: | ---: |
| ARESLib JVM modules | 2,301 | 2,301 | 0 |
| FTC TeamCode + simulator | 156 | 156 | 0 |
| FRC | 305 | 305 | 0 |
| FTC starter TeamCode + simulator | 14 | 14 | 0 |
| FRC starter | 34 | 34 | 0 |
| Studio shared + gateway + app | 2,075 | 2,069 | 6 |
| MicroPython host suite | 130 | 130 | 0 |
| Repository tooling | 101 | 101 | 0 |

The validated suites contain 5,110 passing results and six explicit Studio skips,
with no failures/errors. Gradle reused unchanged or cached task outputs where applicable;
these are suite results, not a claim that every test re-executed. Focused runs are not
counted again. The skips remain the three generated-project integrations, native file
chooser, dashboard performance baseline and hardware dashboard validation.

Monorepo policy passed: source-tree/version alignment, bundle hashes, shared guidance
and links in 368 current documents, with 38 explicitly historical documents skipped.

Evidence: `ARESLib-Kotlin/build/audit-pass207-verified-evidence/`, including preserved
failing baselines, focused and full suite XML, allocation output, JVM default-method
evidence, candidate/artifact identities, archive comparisons, policy output and summary.

## Coverage boundaries and next work

Full-file review includes InputFrame, AxisTransform, AnalogBinding, DigitalBinding,
DigitalSource, ButtonSuppression, ControllerBindingRuntime, ControlSchemeDsl and both
platform input adapters. Existing and new associated test files were reviewed for
behavioral assertions rather than counted as production line or branch coverage.
The generated runtime and FTC/FRC host call sites were read contextually to confirm
that the normal loop samples before dispatch; their broader lifecycle remains outside
this pass. External callback ownership and custom digital-source policies remain with
their callers. These mutable binding objects are used on the owning robot-loop thread.

No fixed sample-age threshold was added. Explicit timestamps support deterministic
replay; the binding runtime does not certify hardware freshness or impose enable/lease
policy. Those remain platform/host responsibilities. Physical controller reconnects,
SDK thread behavior, end-to-end loop timing, hardware neutralization and full Studio
rendering were not exercised. No remote workflow, robot deployment or public release ran.

The ledger accounts for 2,916 tracked files: 1,122 reviewed, 158 partial and 1,636
pending. This pass closes 23 additional file records, including its report; there are
no stale fingerprints or orphaned records. File accounting is distinct from test totals
and measured line/branch coverage.

The monorepo goal remains active. Next work should use a distinct pending scope such
as composite vision input and outlier filtering, with existing partial approximation
and host-lifecycle boundaries retained in the ledger.
