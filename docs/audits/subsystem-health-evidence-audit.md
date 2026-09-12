# Subsystem health evidence and observation audit

Pass 193, 2026-09-12. Reviewed the previously pending subsystem health accumulator
and dashboard card, following generated hardware heartbeats through NT4 coercion,
TelemetryStore and the coalesced UI stream. Added a dedicated observation helper.
The raw NT4 client, telemetry store and hardware registry were inspected only at
these boundaries; this pass does not grant those larger files complete review.

## Confirmed fixes

NaN health values satisfied the presence check while matching neither true nor
false, allowing a subsystem to remain Ready. Infinite and noncanonical boolean
values could also act as valid health evidence. Textual telemetry's numeric
placeholder could satisfy health checks. Invalid updates now remove the prior
signal's evidence. Health flags require numeric zero or one; heartbeats require a
finite nonnegative numeric value. Missing or invalid required evidence prevents
Ready, and a valid replacement restores the normal status evaluation. Non-numeric
or non-finite measurement values are also removed instead of displayed as numbers.

Once a heartbeat has been observed, only another valid heartbeat renews liveness.
Unrelated position/sensor traffic cannot hide a stopped heartbeat. Backward receipt
clock observations are stale; signed monotonic-clock wrap retains correct elapsed
time. Positive stale intervals are required. The missing-signal set is calculated
once per snapshot and reused for status and issue text. Topic parsing now strips
all leading transport slashes and rejects blank subsystem/signal identities.

The card previously retained one accumulator across target changes. New target
heartbeats could combine with the previous robot's flags. Accumulation and snapshot
sampling now carry the target epoch and discard all cached values when it changes.
The collector checks frame identity and rechecks the epoch before accepting a
sample. A 4 Hz sampler also clears the old target when no new frame arrives.

Reopening a card no longer assigns a new receipt time to a retained heartbeat.
Retained state flags may be used for the same target, but a newly received heartbeat
is required to establish readiness. A single owned collector and sampler are
cancelled on service replacement or card disposal. Snapshot publication remains
four times per second even during same-topic bursts; no measured CPU speedup is
claimed. Epoch changes are reflected by the next sample, within the 250 ms sampler
interval when scheduling is timely.

## Validation

The unmodified accumulator failed all six new baseline cases; its five original
cases passed. The regression matrix covers every required signal with non-finite
values, noncanonical flags and textual placeholders, plus recovery, heartbeat
expiry, backward clocks, interval validation and topic normalization. Additional
cases exercise epoch reset, signed clock wrap, retained heartbeat rejection,
queued/racing old frames, burst coalescing and collector cancellation. A real
Compose offscreen composition tests service replacement and disposal. Existing
builder screenshot tests exercise the status content.

All 30 focused tests passed with no failures or skips: 13 accumulator tests, four
observation tests, one Compose lifecycle test and 12 existing builder render tests.
The generated health-card image was inspected: readable labels distinguish Ready,
Homing Required and Output Fault Latched without relying only on color. This is
offscreen rendering evidence, not a launched Studio window. The full Studio app
suite passed: 1,849 tests, 1,843 successful executions, zero failures/errors and six
opt-in/environment skips (three generated-project integrations, native file chooser,
performance baseline and physical dashboard target). Evidence is under
`ARESLib-Kotlin/build/audit-pass193-verified-evidence/`.

## Remaining scope

Accumulator review remains partial for lifetime bounds on distinct subsystem and
measurement names. Per-topic replacement bounds repeated samples, but does not
bound the number of names from a high-cardinality source. The dashboard card also
remains partial for complete replay selection/seek semantics: replay frames enter
the same UI bus, and the broader replay state boundary has not been audited here.
No physical robot,
visible Studio window or live network target was exercised. Lossy UI fan-out may
omit a health flag; the card then remains incomplete until that evidence arrives.
The library and frozen candidate identity are unchanged.

Adjacent preliminary review found work for the next commissioning pass:
`HardwareCommissioningModel.kt` does not consult inventory errors or require unique
hardware-map names when deciding diagnostic availability, and its clipboard can
include partial diagnostic instructions when availability is false.
`HardwareInventoryFormatting.kt` needs a regression for integral Doubles at the
Long upper boundary, where conversion may report a saturated integer. The larger
setup service, its test suite and setup screen were read only at these boundaries.
These findings are not counted as fixed or fully validated in this pass.
