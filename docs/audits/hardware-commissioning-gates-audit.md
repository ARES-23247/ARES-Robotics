# Hardware commissioning, port ownership and evidence gates

Pass 194, 2026-09-12. This pass follows the open commissioning and formatting
findings from pass 193 through the hardware inventory service, setup state and UI.
The canonical library candidate remains unchanged; all edits belong to Studio.

## Confirmed fixes

Drivetrain diagnostics now require a complete, unambiguous FTC wheel-role mapping,
distinct motor names and no blocking inventory errors. Copied instructions obey
the same availability decision as the screen. An unavailable diagnostic includes
its reason and does not print partial hold-to-run button instructions. Non-FTC
checklists no longer print the FTC Driver Station hardware-configuration path.

The setup screen previously cached its commissioning plan by inventory hash alone.
A malformed additional document produces an inventory error without changing the
fingerprints of the successfully decoded documents. The cache now follows the
complete snapshot, so a new error invalidates the prior eligibility result.

The physical-evidence service and form previously required a matching review and
passing simulation but omitted inventory errors. A shared readiness predicate now
requires a reviewable inventory as well. Invalid inventories cannot accept another
physical-evidence record, and matching older evidence is not exposed as current
physical validation until the inventory is valid again. Evidence files remain on
disk. The reviewability predicate also avoids allocating a filtered issue list.

FRC absolute encoders now appear as DIO devices, matching the generated
`DutyCycleEncoder` adapter and its digital input boundary. Quadrature encoders
reserve each A/B channel independently; a separate digital input on either channel
is a collision. Numeric overlap between an analog input and a DIO input is allowed.
The DIO label now covers both digital input and output. These changes are consistent
with the official [DutyCycleEncoder API](https://github.wpilib.org/allwpilib/docs/release/java/edu/wpi/first/wpilibj/DutyCycleEncoder.html)
and [Encoder API](https://github.wpilib.org/allwpilib/docs/release/java/edu/wpi/first/wpilibj/Encoder.html).
The defects were in Studio's inventory interpretation, not in WPILib.

`formatSetupNumber` no longer converts the exactly representable Double `2^63` to
the saturated, smaller integer `Long.MAX_VALUE`. The upper integer-formatting bound
is exclusive; the exact lower bound is retained. In-range integral conversion is
performed once, while fractional/out-of-range values retain Double formatting.

## Evidence

The initial 14-case baseline had five failures across commissioning availability,
clipboard output and integer formatting. A separate four-case FRC baseline had
three failures covering absolute-encoder classification/collisions and quadrature
channel ownership. Both added physical-evidence baseline cases failed as well.
All original test cases are retained and expanded where appropriate. Added checks
also cover follower-only pulse exclusion and same-hash eligibility changes in a
real offscreen Compose composition. A local text-encoding issue interrupted one
edit before the UI portion was applied; that intermediate run validated only the
service/state changes. The complete focused run includes the final UTF-8 UI edit.

All 21 final focused tests passed (18 inventory/commissioning cases and three
formatting cases), with no failures or skips. Full Studio app validation passed:
1,861 tests, 1,855 successful executions, zero failures/errors and six opt-in or
environment skips (three generated-project integrations, native file chooser,
performance baseline and physical dashboard target). Repository policy also passed.
Evidence is under
`ARESLib-Kotlin/build/audit-pass194-verified-evidence/`.

## Scope and limitations

The commissioning model and formatting helper were read completely. The inventory
service was also read completely, but remains partial for XRP port namespaces,
CAN/bus identity edge cases, concurrent source edits and evidence-file ownership,
append ordering and races. This pass does not claim those paths are resolved.

The setup view model was read completely; only its physical-evidence predicate is
closed here. Cancellation, operation replacement and preservation of edits/checks
across refresh/save remain for a separate lifecycle pass. The screen was inspected
at the evidence ladder, form and commissioning-plan boundary; its remaining UI
behavior is not claimed fully reviewed. Robot generator files were read only at
the adapter/type mapping boundary. No generated robot source, library identity,
release artifact or physical device was changed. Test evidence uses synthetic
human-review records; no physical test was performed or inferred.
