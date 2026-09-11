# Studio electrical unit inference audit - pass 142

Continued the open topic-name inference scope of the shared unit converter.
Confirmed errors affect inferred chart labels and subsequent display conversion.

## Confirmed defects

Matching any `amp` substring classified `Vision/TimestampMs`, sample counts, ramp
rates and signal amplitude as electrical current. Ampere detection now recognizes
standalone or camel-case words such as `amps` and `amperes`, including separated
case-insensitive forms. Existing explicit `current` detection remains intact.

Generic quantity matches also took precedence over explicit supported units:
millivolts were labeled volts, milliamperes amperes, and Kelvin temperatures Celsius.
Explicit full-word milli-unit and Kelvin detection now precedes those generic matches.
This preserves the scale/offset information needed by display conversion.

Two new regressions failed before the correction and pass afterward. Tests retain
genuine current names while rejecting unrelated amp-containing words and verifying
the explicit electrical/temperature unit examples.

The fixed Cartesian-axis lookup set is now initialized once instead of allocated
for every inference call. Word-boundary regexes are also initialized once; the camel
case transformation is only used for keys containing `amp`. No whole-path allocation
or performance benchmark claim is made.

## Remaining scope

The converter remains partial. Other substring-based inference, angular-velocity
unit precedence, abbreviations and ambiguous topic names need further review.
These tests do not establish that arbitrary custom telemetry names carry sufficient
information to infer physical units. No robot runtime or telemetry schema changed.

## Validation

Final Studio validation passed: 22 shared tests, 18 gateway tests and 1,762 app
entries including six skips, totaling 1,796 passes and six skips. Skips remain the
three opt-in template/integration cases, native chooser, performance baseline and
physical dashboard telemetry. Policy, documentation links and whitespace checks pass.

All seven focused converter tests pass. Before/final logs, XML and source hashes
are retained under `ARESLib-Kotlin/build/audit-pass142-verified-evidence/` using the
unchanged library candidate `17.0.3-rc.100852e472fb`. No physical test, deployment,
push, merge or release was performed.
