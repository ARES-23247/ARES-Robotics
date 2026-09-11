# Floodgate integration and FRC power audit - pass 89

This pass closes the Floodgate sensor and FRC power-coordinator scopes left by pass 88. It reviews
configuration, current conversion, filtering, charge/thermal integration, current-budget boundaries,
fallback ownership and host allocations. Changes remain local.

## Floodgate findings and fixes

- Invalid declared parameters silently selected other defaults. Construction now rejects invalid
  current/filter/fuse/time parameters and nonrepresentable current-square or thermal-capacity
  constants. Invalid warning thresholds also reject instead of silently suppressing comparisons.
- The first 20 A observation was filtered down to 3 A by a fictitious zero seed at alpha 0.15.
  The first valid observation now seeds the filter. Later invalid readings preserve filter history
  while the public current getters return zero with `isReadingValid == false`, as before.
- Charge and thermal integration used the first recovered observation for an unobserved interval.
  Integration now requires consecutive valid observations. Invalid gaps retain thermal history;
  the first valid recovery re-anchors without inventing charge or cooling during the gap.
- Linear cooling removed all thermal history after one cooling time constant; subdividing the
  same interval produced a different result. Cooling now uses the exact exponential decay for
  the declared first-order time constant. One 15-second interval and fifteen 1-second intervals
  both leave 36.787944117144235% from an initial 100% load. Rated current adds no heating, and the
  existing 40 A / 2-second default calibration point remains unchanged.
- Signed elapsed subtraction could overflow and silently discard a valid ordered interval.
  Ordinary elapsed calculation retains millisecond precision; the overflowing ordered case uses
  a wide floating-point difference. Clock rewinds re-anchor without negative integration.
- Extreme heating could overflow to infinity and subsequent cooling produce NaN thermal load.
  Validated constants and finite accumulator saturation prevent that failure. Getter percentages
  remain bounded while accumulated heat above the displayed 100% continues to affect cooling.
- Analog conversion was repeated by each instantaneous-current getter. Conversion now happens
  once during update; getters use its cached result. Redundant validated-parameter aliases and
  repeated fuse-rating squaring were removed.

The manufacturer lists a 0-3.3 V analog output and 80 A maximum measured current for V2;
the existing conversion range is unchanged. Its separate electronic limit and internal fuse are
not implemented by this software model. [goBILDA V2 specifications](https://www.gobilda.com/floodgate-power-switch-xt30-current-sensing/).

## FRC review

Seven new tests verify exact current thresholds/hysteresis, one read per supplier, bypassing branch
reads for valid PDH data, aggregate fallback for invalid/failed PDH data, unknown-current recovery,
failed brownout input, combined voltage/current limits and registry replacement. No FRC runtime
defect was confirmed in these paths, so runtime behavior is unchanged.

The existing FRC policy reports unknown current and retains a 0.4 current-only scale when neither
PDH nor complete cached branch coverage is available. Voltage/brownout protection can still reduce
final effort to zero. This differs intentionally from FTC's software-model rejection; it is not a
claim of fresh current data. The production ARES-FRC host installs live voltage and real-hardware
PDH/brownout suppliers. Broader starter/generated-host wiring is outside this pass's completion claim.

The FRC allocation test now includes both PDH and aggregate fallback paths, requires consecutive
zero-byte windows, closes its registry, and explicitly skips when JVM counters are unavailable.

## Evidence and limits

Nine Floodgate methods failed before their fixes: eight initial regressions plus warning-threshold
validation. Failure XML is preserved in `ARESLib-Kotlin/build/audit-pass89-before-results/`.
The signed-epoch test's hand-written expected value was corrected using exact integer/decimal
arithmetic: 18,446,744,073,709,551,595 ms at 1 A is approximately 5,124,095,576,030.431 Ah.
The original implementation returned zero for that interval; the defect remains independently proven.

Additional tests cover Ah/Wh units, cached reads, ADC/exception recovery, rewind/reset behavior,
filter endpoints, and allocation. Tracker reset intentionally preserves current/filter/validity while
clearing charge and thermal history. Charge totals omit unobserved intervals; nominal-voltage Wh
is an estimate. Thermal calibration is a configurable surrogate, not a measured physical fuse curve.

Host tests do not prove physical current accuracy, fuse protection, electrical suitability of the
retained FRC thresholds, or robot loop deadlines. No upstream WPILib defect is claimed.
