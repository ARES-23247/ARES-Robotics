# Studio lighting display color audit - pass 163

Read IndicatorLightColorMapper and PrismColorMapper in full, traced their dashboard and
path-renderer call sites, and compared named color stops to local indicator/Prism preset
definitions. Both display utilities let NaN fall through to an ordinary color: white for
the indicator and a cyan animation accent for Prism. Indicator positive infinity also
became white through clamping. The live RobotLightingTelemetry parser already rejects
nonfinite readings; this pass closes the utility boundary rather than claiming a proven
live dashboard incident. No before-fix test execution is claimed.

Nonfinite indicator input now produces the neutral dark display color and an Unknown
label. Nonfinite Prism input produces transparent color, matching its existing invalid
pulse-range behavior. Finite clamping, negative indicator sentinel handling, representative
rainbow bands and named solid/animation colors remain unchanged. The path renderer may
override indicator alpha, so the indicator fallback deliberately uses neutral dark color
rather than promising an invisible rendered marker.

Three tests cover every local indicator solid preset and its name/ARGB channels, the
rainbow band and finite endpoint behavior, 1,001 finite interpolated positions, all three
nonfinite inputs, Prism solid/sine/pulse representatives, opacity for every declared active
Prism preset and existing invalid/off boundaries. Indicator RAINBOW is a negative command
sentinel in the local enum, not an accepted servo-output position; the display tests use
the mapper's documented finite rainbow band rather than equating that sentinel with PWM.

Both utility files and the new test file are fully reviewed for their approximate display
contract. These checks verify local preset consistency and numeric behavior, not external
manufacturer chart provenance, exact hardware band thresholds, animation frames, physical
color calibration or native rendering. The existing 1049-1090 microsecond Prism off band
is retained; its complete physical transition behavior is not independently certified.
No hardware-driver source, actuator output or telemetry transport behavior changed.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass163-verified-evidence/`.
Candidate remains `17.0.3-rc.100852e472fb`; changes remain local.

Validation: all three focused tests pass. The full app suite reports 1,816 tests: 1,810 passed and 6 opt-in skips, with no failures or errors. Unchanged shared/gateway suites were not rerun. Monorepo policy, documentation links and staged whitespace checks pass.
