# Calibration ingestion, statistics and observation integrity

Pass 77 in progress, 2026-09-10. All changes remain local; no candidate has been frozen.

## Confirmed and fixed so far

- Empty/incomplete calibration reports threw while serializing NaN statistics. JSON now
  explicitly represents unavailable values as null. A single sample has unavailable sample
  standard deviation; directionally ambiguous headings have unavailable circular statistics.
- Invalid truth coordinates contaminated fits despite the truth-valid flag. Vision and route
  calculations now reject nonfinite inputs; incremental moments preserve small noise around
  a large bias, and heading bias/spread use wrapped deviations around a circular mean.
- Routes could end before they started or merge across independent files reusing a run ID.
  Import preserves source identity, grouping includes that identity, and endpoint selection
  is chronological. Repeated normalized input paths are deduplicated.
- NIS/NEES sums overflowed despite representable means. Incremental averages retain the
  existing mixed-DOF normalization. NEES validates both covariance triangles, rejects invalid
  raw poses, and normalizes covariance/error before Cholesky to remove a unit-dependent cutoff.
- CSV import retained a duplicate full-file line list. It now streams rows, handles a UTF-8
  BOM, rejects invalid required IDs/headers and preserves invalid DOF as invalid rather than
  inventing a three-DOF observation. Recorder/parser fixtures use owned temporary directories.
- CLI output could overwrite an input dataset. It now requires inputs and rejects equivalent
  output/input files before producing output.
- Calibration associated the estimator's default/stale NIS with a current raw camera packet,
  including external-estimator frames that produced no local innovation. Immutable scalar
  observation metadata now travels through Store processing and the pure vision reducer.
  Capture requires a unique source/frame/timestamp/tag/solver match and uses that observation's
  DOF, distance and acceptance status. Finite statistically rejected innovations remain available;
  early rejection, external-only processing and pose reset cannot masquerade as a fresh NIS.
- Surveyed full turns were lost in both front ends and in route normalization. New samples
  preserve signed heading travel with an explicit CSV flag; legacy files retain shortest-arc
  interpretation. Pose seeds alone normalize heading. Nonfinite truth adjustments fail before
  mutation, and overflowed route heading differences are rejected.
- FRC recorded stationary ground truth while driving, and FTC's local dwell gate treated
  default/stale zero velocity as stationary. Both front ends now use a shared allocation-free
  gate with valid/fresh motion feedback, neutral controls, and uninterrupted 500 ms dwell.
  Stale/future camera frames are excluded. Pose seeds and checkpoints wait for this gate;
  editing truth or test type cancels pending actions. END advances the run ID only on capture.
  FTC's smoothed commands use the stationary tolerances rather than exact floating-point zero.
  FRC close cancels pending work and prevents later seeds or recording.

## Evidence so far

Sixteen distinct methods reproduced failures before their fixes: eight initial numerical/report
cases, one independent-file route collision, one CLI overwrite, three NIS attribution cases,
two surveyed-truth adjustment cases and one moving-stationary collection case.
Copied baseline XML/logs are under `ARESLib-Kotlin/build/audit-pass77-` with suffixes `before`,
`routes-before`, `cli-before`, `nis-before`, `turns-before` and `motion-before`. Later tests cover correlated factorization,
invalid inputs/reset, mixed DOF, parser boundaries, rejected innovations and packet reuse.

The current focused run passed 41 core tests (including calibration, reducer, snapshot and
zero-allocation checks) and 6 FRC session tests. FTC's 2 gate tests and the FRC product's 134
tests passed using explicit sibling-source substitution. Copied XML, hashes and logs are in
`ARESLib-Kotlin/build/audit-pass77-workflow-evidence`. Core and FRC API dumps succeeded and
their diffs were reviewed. Full candidate validation and file-ledger credit remain pending.

## Remaining validation before freezing

- Core/FRC API snapshots have been regenerated and their diffs reviewed. Sample/capture,
  VisionState constructor/copy and FRC periodic JVM descriptors changed; consumers must rebuild.
- Complete any remaining capture-edge review, freeze source identity and run one candidate
  library/API/coverage/publication and dependent consumer matrix. Focused sibling builds are
  development evidence, not final candidate validation.
- Reconcile file-ledger credit and final evidence only after that matrix succeeds. RobotState,
  DriveReducer and ARESRobot were inspected only in relevant sections, not as whole files.

These tests establish host behavior and numerical contracts; they do not establish physical
calibration accuracy, measured robot loop jitter or reliable sensor feedback on actual hardware.
