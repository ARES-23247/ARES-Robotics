# XRP project serialization and starter audit - pass 135

Reviewed the previously pending generated-project and hardware-bus tests, starter
descriptors, and the host generator. The generator retains partial status: its
broader invalid-input policy, generated safety-test strength and CLI/reporting
behavior need further work beyond this serialization pass.

## Confirmed defect and correction

The generator serialized `PROJECT` as JSON and globally replaced `true`, `false`
and `null` with Python literal spellings. Those substitutions also changed string
contents. For example, Wi-Fi SSID `true-false-null` became `True-False-None`, preventing
the generated runtime from using the configured network name.

It now uses the host standard library's `pprint.pformat` with sorted dictionary
keys to produce Python literals directly. String contents, escapes and Unicode
survive; booleans remain booleans. Two string regression subcases failed before
the change and pass afterward. This changes generation-time work, not robot-loop
allocation or latency. No new runtime dependency was introduced.

The discovery regression previously accepted any combined test count of eight or
more. It now requires an actual source test, generated safety tests and unique test
identities, so losing a discovery root cannot be hidden by the other root's count.

## Starter coverage

Reviewed all fields of the project, differential drivetrain, simulation tuning,
tabletop field, autonomous catalog and two-waypoint routine. Checked matching IDs,
the 100-by-56-inch field conversion, track width from wheel positions and the robot
footprint at each authored pose. The tuning document intentionally has no overrides.
The generated-project suite exercises canonical generation and routine construction.

Reviewed the input-only rangefinder descriptor: centimeters convert to meters in
the hardware adapter; the declaration then uses unit scale, a 0–4 m measurement
range and 250 ms feedback limits. Generated mock tests exercise invalid and failed
feedback. Actuator-only generated cases may do no work for this input-only fixture;
their success is not actuator coverage.

Controller, controls and action-catalog documents were read and references checked.
They remain partial because this host generator only checks selected control-target
constraints. The Studio gamepad mapping, chord timing and action delivery are not
established by this pass. Physical geometry, wiring, bus transactions, calibration,
firmware provenance and loop timing are not certified by desktop fixture tests.

## Validation

Full XRP host verification: 108 tests, zero failures/errors/skips. Generated-source
freshness, policy and documentation-link checks passed. The initial sandboxed run
recorded the two expected regression failures plus other errors, then stalled. Its
identified test process was stopped; the full rerun with normal temporary-file
access passed in approximately two seconds. The incomplete initial run is retained
as diagnostic evidence, not counted as a completed suite.

Logs, final JUnit XML and source fingerprints are retained under
`ARESLib-Kotlin/build/audit-pass135-verified-evidence/`. Changes remain local; no
library source, device deployment, physical test or release was performed.
