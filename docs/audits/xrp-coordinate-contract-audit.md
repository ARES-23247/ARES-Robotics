# XRP coordinate contract audit

Pass 71, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Findings and fixes

Canonical XRP project metadata requires `CENTER_ORIGIN_CCW`; the starter's Python collision
code and Studio editing bounds also use centered field extents. The JVM physics walls,
legacy startup/reset pose, Orbit preset, and XRP fmap conversion disagreed with that contract.
Pass 46 incorrectly adopted the legacy corner convention; its report now records this correction.

- XRP walls now span plus/minus half the configured dimensions. FRC retains its corner origin.
- XRP startup without a field explicitly selects XRP geometry, avoiding sibling FTC asset
  discovery. Startup and network pose reset share a helper using the currently loaded field:
  the default 2.54 m table starts at `(-0.92, 0, 0)` rather than `(0.35, 0.7112, 0)`.
- Orbit revision 2 translates every tag, obstacle, element and waypoint by `(-1.27, -0.7112)`.
  Dimensions, IDs, sizes and orientations are preserved. The central pedestal is at `(0, 0)`.
- XRP fmap import/export preserves centered coordinates; only FRC receives the corner offset.

The shared startup/reset helper removes duplicated pose constants. It runs on startup/reset,
not in the periodic loop; this batch makes no measured loop-time or allocation improvement claim.

## Evidence

Five independent contract tests all failed before the fixes (`audit-pass71-before.log` and
copied XML). The focused run passed 49 methods: 24 core codec tests and 25 simulator methods,
including all five new tests, drive lease behavior, reset neutralization, wall replacement,
and both XRP drive modes. Log: `ARESLib-Kotlin/build/audit-pass71-focused.log`; copied XML:
`ARESLib-Kotlin/build/audit-pass71-focused-evidence`.

The actual Python `FieldCollisionConstraint` loaded the revised Studio preset and confirmed
both launch positions clear, the central pedestal colliding, and both out-of-bounds X
positions rejected. Evidence: `ARESLib-Kotlin/build/audit-pass71-python-contract.json`.

Source commit `2562db21` binds library tree `7e9a7fb40abdf83deda57873b7b9cc3dd2733e2e`
to local candidate `17.0.3-rc.7e9a7fb40abd`. Full library tests, API checks, simulator Kover
and isolated publication passed in 2m 49s: 1,700 methods, zero failures/errors/skips. Unchanged
inputs reused valid cache/up-to-date results. The initial unquoted PowerShell version argument
failed task selection before tests; its log is preserved separately from the successful run.

Serial consumers passed against that candidate: FTC 111, FRC 134, FTC starter 14 and FRC
starter 34 methods, with generated-project verification and FTC debug assembly. Studio
reran shared/gateway/app tests: 1,779 passed and six existing opt-in skips. Its 56 dashboard
checks, one performance baseline, coverage gate, version alignment and production file-size
checks also passed; Studio completed in 4m 17s. Logs are `audit-pass71-{library,ftc,frc,
ftc-starter,frc-starter,studio}.log` under `ARESLib-Kotlin/build`.

Copied JUnit XML, per-file hashes and result summaries are in
`ARESLib-Kotlin/build/audit-pass71-verified-evidence`. Full-suite simulator Kover covers
engine 222/225 lines and 107/150 branches, physics world 95/103 lines and 28/32 branches.
These counts do not close the remaining lifecycle and domain questions below. Monorepo
policy evidence is recorded in `ARESLib-Kotlin/build/audit-pass71-policy.log`.

## Scope and limits

This establishes agreement among the inspected XRP project, editor, asset and simulator
coordinate contracts. It does not prove physical field calibration, camera orientation,
remote transport, hardware behavior or measured real-time performance. The fixture spawn
is not guaranteed collision-free for arbitrary obstacles or very small fields.

The engine and physics world remain partial reviews: prior lifecycle, publication and field
replacement concerns remain open. General `RobotFieldConfig.getInitialPose` still uses fixed
dimensions, and its caller paths need separate review. WPILib import axes and fmap source
versus destination dimension handling in Studio remain open integration work. A round trip
alone does not resolve those contracts. Existing user-authored XRP documents are not silently
translated: the bundled legacy preset is the known corrected asset.
