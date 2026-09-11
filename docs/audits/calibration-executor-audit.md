# Calibration executor and logging audit — pass 81

This pass continues the previously partial FTC empirical calibration and FRC SysId executor
boundaries. It reviews moving-routine safety, sample validity, timestamps and repeated work.
Changes remain local; no physical calibration, deployment, push or release was performed.

## Confirmed defects and fixes

- FRC accepted power scales above one, logged a sample after the SysId manager rejected its
  arithmetic, and mixed new velocity with retained derivatives on duplicate timestamps. It now
  rejects invalid power, clears rejected samples, and preserves the entire previous sample when
  the timestamp repeats. Public stop immediately clears status and data.
- FRC read the cached velocity repeatedly for validation, calculation and error selection. Each
  active update now reads and validates one velocity value. Common stop handling is consolidated.
- FTC moving empirical routines lacked the supply, motion freshness, power-range and current
  guards used by characterization. They now require finite positive supply, fresh finite measured
  drive feedback, valid motor scales and available cached current. Sustained overcurrent stops
  motion; invalid current limits and backward clocks stop immediately. Valid derating remains
  supported. Stationary vision collection does not require moving-drive current feedback.
- FTC logging accepted infinite encoder conversion scales, republished stale drive feedback,
  and represented a missing vision target as a valid field origin. It now selects the first
  finite positive conversion scale, rejects invalid geometry/nonfinite samples, and publishes
  empty data when feedback is unavailable. Vision data uses its capture timestamp and requires
  a connected source with a measurement no older than 500 ms and not in the future.
- FTC disable now immediately clears the published sample/status. Telemetry publication and
  encoder conversions share one path; scalar estimator access removes convenience-pose
  allocations. Signed linear averaging scales before summing to avoid intermediate overflow.

The obsolete rotating-shot offset comment from pass 80 is also corrected; its test behavior
is unchanged. There is no public library API change in this pass.

## Regression evidence

Four preserved pre-fix XML/log pairs contain 16 distinct failing methods: five FRC executor
regressions, five FTC moving-safety regressions, one direct output-clock regression, and five
FTC logging regressions. They are under `ARESLib-Kotlin/build/audit-pass81-*-before.*`.
Final focused validation passed 38 FTC calibration tests and 15 FRC SysId tests, with zero
failures, errors or skips. FTC API verification passed before the source freeze.

Additional passing cases cover valid vision capture time plus stale/future/disconnected frames,
signed encoder averages, default conversion fallback, permitted derating, invalid current limits
and immediate zero-duration stall protection. Output-path assertions check that cached current
is consumed without extra hardware polling. These are host tests, not robot timing measurements.

## Remaining boundaries

`EstimateMotorIO` retains cached encoder position when a device read throws and does not expose
position freshness to calibration. Fresh drive pose/current alone cannot establish that each
encoder sample is fresh. The FTC controller therefore retains partial coverage pending the
hardware-input contract pass. That pass must investigate and reproduce the failure through the
consumer before selecting a validity contract. FTC flywheel cached-velocity rereads and diagnostic
error clearing also remain explicit follow-ups; the FRC velocity optimization does not cover them.

No physical loop deadline, motor-current threshold suitability, wheel calibration accuracy,
camera calibration accuracy or usable Studio window is established by this batch.

## Candidate validation

Focused evidence is preserved under `ARESLib-Kotlin/build/audit-pass81-focused-evidence/`.
The dependency-ordered candidate matrix and final ledger reconciliation are pending. The overall
monorepo audit remains active; passing these suites does not grant review credit to unread files.
