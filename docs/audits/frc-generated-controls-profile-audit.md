# FRC generated controls and profile audit - pass 125

## Scope

Reviewed the complete driver control scheme, project manifest, competition tuning
profile and existing generated-controls runtime test. Read the drivebase descriptor
to resolve profile references, parameter types/bounds, ownership policies and
geometry. Its full hardware safety enforcement remains a partial review.

## Controls behavior

The existing runtime test only exercised a default disconnected frame. Added a
regression through the actual generated project runtime and its installed bindings:
neutral arming, full forward input, simultaneous half-scale strafe/rotation,
repeated unchanged commands, the exact deadband boundary, and disconnect after
nonzero input. All expected values passed without a runtime correction.

Raw axes 1, 0 and 4 map to forward, strafe and rotation respectively, with all
three inverted. The 0.1 deadband is rescaled: magnitude 0.55 produces magnitude
0.5, not 0.55. The season sink independently scales normalized translation by
4.5 m/s and rotation by pi rad/s; RED mirrors translation only. Existing sink
tests cover that scaling. The new test observes normalized generated commands.
These tests exercise injected input frames, not a physical HID or native key events.

Repeated VALUE emissions are intentional: the downstream controller needs the
current command each enabled frame. Changing the scheme to emit only on changes
would alter that contract. Three separate axis bindings are likewise needed to
populate three independently sampled command components. No efficiency change
was justified by this review.

## Project and tuning consistency

All 23 profile values have unique known parameter IDs, the declared scalar type,
and values within descriptor bounds. All read-only vendor values match descriptor
defaults; inspected vendor gains/current settings agree with the profile. The four
calibrated offsets retain the reviewed deploy overlay, whose agreement is already
tested. This is consistency evidence, not independent measurement of calibration.

Project/profile/drivebase identifiers agree. The manifest's 0.8 m bumper dimensions
match the simulator profile and season constants. Its 16.54175 by 8.21055 m field
matches the shared field constants. FRC league, 2024 field identity, blue-corner
CCW coordinates and hybrid authoring are consistent with this source product.
The empty runtime-options object introduces no hidden overrides.

Vendor configuration stays read-only, offsets require calibration, simulator
parameters require rebuild, and only trajectory velocity scale/acceleration limit
are eligible for the season Redux runtime adapter. Numeric bounds do not prove
that physical gains are optimal or that every declared safety policy is enforced;
those claims are outside this configuration pass.

## Evidence and limits

Focused generated-controls tests passed (2). Generated-project verification and
the full FRC suite passed (289 tests, zero failures/errors/skips). Monorepo policy,
documentation links and staged whitespace checks passed. Local XML, logs, profile
check results and file hashes are retained under
`ARESLib-Kotlin/build/audit-pass125-verified-evidence/`.

No new runtime defect was found. Added meaningful coverage without changing
working runtime code or manufacturing a fix. No additional allocation benchmark
or dependency matrix was needed for this test-only change. No GUI, hardware,
push, merge or release was performed.
