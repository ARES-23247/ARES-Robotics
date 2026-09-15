# FTC telemetry helper audit - pass 168

Reviewed the complete season telemetry helper and its tests, tracing the robot update
caller, cached power getters, immutable estimator snapshot and downstream Driver Station
text snapshot. No ARESLib source was changed.

Three regression tests failed before the fix: first publication at time zero, publication
after clock rewind, and the one-state-read assertion. An explicit initialized flag now
permits the first update at any clock value. Rewind and elapsed overflow restart the
100-ms throttle window; repeated timestamps remain throttled. Suppressed calls return
before reading robot state or cached power values.

Each published summary now reads one Redux snapshot instead of two. Direct estimator
scalar access eliminates the temporary Pose2d convenience allocation while preserving
meters and CCW degrees in the displayed values. Strings and the custom telemetry map
still allocate/update at the low-rate boundary; this is not an allocation-free publisher
or a measured loop-time improvement.

Tests also exercise all nonfinite/zero/negative battery readings, low-voltage rounding,
normal battery and power display, heading conversion, and 150-character custom-value
truncation/replacement. The internal low-battery formatter is called only for finite
positive voltage below 11.5 V. Its rounding contract is nearest-even at decimal ties.

The helper is single-loop-owned. The downstream concurrent text map is not an atomic
six-value frame; its asynchronous presentation may span updates. This pass does not
claim Driver Station rendering, latency, device clock behavior or hardware validation.
Custom keys remain caller-owned; the helper caps each value, not the number of keys.

The initial new test fixture incorrectly attempted to copy the derived estimatedPose
property. That test compilation failure was corrected to use the real snapshot scalar
fields before the three production regressions were executed.

Evidence: `ARESLib-Kotlin/build/audit-pass168-verified-evidence/`.
The library candidate remains `17.0.3-rc.100852e472fb`. Changes remain local.

Validation: five focused tests pass; full FTC validation reports 117 TeamCode and six
simulator tests passing without skips. Debug APK assembly, monorepo policy, documentation
links and staged whitespace checks pass. Unchanged library and other product suites were
not rerun.
