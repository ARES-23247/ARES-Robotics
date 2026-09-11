# Core current-budget and filter audit - pass 87

This pass reviews CurrentBudgetManager, BrownoutGuard, Debouncer and EMAFilter beyond their old
partial audit scopes. It checks invalid observations, electrical configuration, registration and
calibration ownership, state transitions, filtering, and steady-loop allocation. Work stays local.

## Findings and changes

- Missing battery voltage became a plausible 12V estimate and could report full available power.
  Invalid voltage now makes total and cached slot current unknown and sets available power to zero.
  Per-motor estimation uses the same validity rules without changing the aggregate state.
- Invalid scale, unknown velocity and failed cached getters could become plausible stall/zero
  estimates or leave previous budget state after an exception. They now invalidate the budget.
  Fresh valid observations recover through the existing hysteresis state machine.
- Invalid electrical parameters silently selected a different default motor model. Registration
  now requires finite positive parameters and representable positive resistance/back-EMF constants,
  rejecting invalid proposals before mutation. Three old tests that encoded the permissive fallback
  behavior were updated to assert rejection or unavailable current.
- Re-registering a motor double-counted it. Matching registration is now idempotent and preserves
  calibration; changed electrical parameters replace its slot and clear its learned correction.
  An internal last-calibrated-current field was written but never read and has been removed.
- Brownout percentage substituted 13V for a valid small nominal voltage. It now uses the validated
  construction value and clamps the fraction before percentage scaling.
- Debounce elapsed subtraction could overflow across ordered signed mock-clock epochs. Elapsed
  dwell now saturates in that case, while clock rewinds still restart the pending edge.
- A filter test fixture left the process-global clock mocked. Its teardown now restores system
  time. EMA's recurrence, invalid-data recovery, reset, endpoints and extreme finite convex updates
  were reviewed and tested; no runtime change was needed in EMAFilter.

The current-budget loop still samples each motor's effort, scale and velocity once, and only one
cached measured current per frame for round-robin calibration. Invalid battery frames avoid these
getters. An allocation test exercises valid and rejected budget updates with brownout/EMA/debounce
after warmup, requiring two consecutive zero-allocation windows of 10,000 updates.

## Evidence and limits

Eight current-budget methods and two guard/filter methods failed before fixes. Eighteen new test
methods cover the regressions and valid/recovery/operation-count/allocation behavior. Preserved
failure XML is under `ARESLib-Kotlin/build/audit-pass87-before-results/`.

The budget is an approximate sum of modeled motor current, not a physical battery-current
measurement or a fuse-trip guarantee. Existing FTC aggregate/constituent reconciliation and its
repeated per-motor estimation calls were traced but remain a separate integration/efficiency scope.
SDK doubles and host allocations do not establish physical loop deadlines or calibration accuracy.

The configured FTC/FRC guard thresholds are unchanged. REV documents an 8V Control Hub operating
minimum; the FTC guard retains its existing margin. The FRC KDoc now describes 6.8V as the ARES
software cutoff rather than a universal roboRIO hardware trigger. See
[REV Control Hub specifications](https://docs.revrobotics.com/duo-control/control-system-overview/control-hub-basics)
and [WPILib's staged brownout documentation](https://docs.wpilib.org/en/latest/docs/software/roborio-info/roborio-brownouts.html).
No upstream WPILib defect is claimed.

## Final validation

The final source passed 68 selected core tests and all library API checks before freezing. Eighteen new methods include ten distinct failure-before regressions. Public API is unchanged. The allocation test observed zero bytes in each of two consecutive 10,000-update windows.

Source `a03f0598eacf10d4b9d716df722684f0f33ba08e`; library tree `25bcdd2a73bf8eb095d5322c656230770884efd2`.
Local candidate `17.0.3-rc.25bcdd2a73bf`.

| Scope | Passed | Skipped |
| --- | ---: | ---: |
| library | 1997 | 0 |
| ftc | 111 | 0 |
| frc | 148 | 0 |
| ftc-starter | 14 | 0 |
| frc-starter | 34 | 0 |
| studio | 1790 | 6 |

All groups have zero failures/errors. Library API/Kover/local publication, generated-project verification, FTC assembly, Studio Kover/version/file-size gates and monorepo policy passed. Gradle reused valid unchanged outputs; passing counts do not imply every test was freshly executed. Conditional Studio skips are recorded in the copied XML.

Copied XML, per-file hashes, build logs and candidate BOM identity are recorded under `ARESLib-Kotlin/build/audit-pass87-verified-evidence/summary.json`. Validation is on the host and consumers; no physical timing, current accuracy, fuse protection or usable Studio-window result is claimed.
