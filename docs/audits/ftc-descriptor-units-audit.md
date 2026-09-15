# FTC descriptor and feedforward units audit

Pass 259 continues the file audit independently of pass 258's archive approval gate.
The source baseline is `e89992dc4c9138445fa02a01431de9e2a223af1c`; validation uses the unchanged
`17.0.43-rc.3973a4d0eeb4` library candidate. All 410 candidate file hashes were rechecked.

## Confirmed fixes

The season drivebase, generic FTC starter, and Studio's FTC builder described `kS`, `kV`, and
`kA` as voltage gains. The actual FTC controller calculates nominal duty and multiplies by
`12 / batteryVolts`. The generated runtime passes the gains through without unit conversion.
Its units therefore are fractions of 12 V, fractions of 12 V per m/s, and fractions of 12 V
per m/s². Labels now state those units and explain division by 12 for voltage-based
characterization results. Static feedforward's declared maximum is one nominal duty, not 12.
The season's existing numeric gains are preserved.

The generic starter and new Studio drivebases additionally supplied `kV = 12` for a declared
1 m/s model. The runtime's `1 / kV` limit therefore became 0.083333 m/s. Their default is now
`kV = 1`, including the starter's canonical profile, which agrees with the declared model.
Existing saved user projects are not silently rewritten, and these generic simulation values
remain explicitly uncalibrated for physical deployment.

The two FTC regression classes contain five tests. On the original product sources, three
failed: season units, starter units, and starter speed. The other two established the season's
existing normalized convention: at steady 0.5 m/s, `0.638 * 0.5 + 0.05` produces 4.428 nominal
volts at both 12 V and 9 V batteries, and the declared linear/angular limits agree with the
kinematics. All five pass after the changes. Earlier fixture compilation mistakes involving
the Android Gson version and a private generated helper are excluded from failure evidence.

## File review

Read the twelve canonical FTC files: project metadata, drivetrain, competition tuning profile,
controller profile, control scheme, action and autonomous catalogs, both routines, both lighting
subsystems, and their README. Also reviewed the generic starter drivetrain/profile and the
changed builder parameter declarations and regression tests.

- All 34 season profile values match declaration defaults and fall within their typed bounds.
  Stable parameter/component/profile IDs and apply policies resolve. Physical identity is kept
  out of live tuning; calibration values require the existing calibration path.
- All 20 controller mappings match `FtcInputFrameAdapter`'s stable indexes. Forward, strafe,
  and rotation signs match the SDK-to-CCW boundary. Continuous axis emissions preserve control
  freshness. The runtime applies alliance translation mirroring once and does not repeat the
  scheme's deadband/exponent shaping. The recovery chord references the existing guarded action.
- Motor roles, names, inversion declarations, center coordinates and kinematic dimensions are
  internally consistent. Heading and vision sources reference declared components. Required
  current validity, fresh feedback, neutral output and fault recovery policies remain intact.
- The autonomous catalog resolves both routines and the default entry. The short path uses the
  supported safe motion preset. The lighting routine uses generated capability actions; the
  existing simulator test runs it to completion and observes both final indicator colors and
  the Prism timer preset.
- Schema-11 lighting documents keep distinct targets/outputs, correct adapter-unit bounds,
  declared safe outputs, optional-device behavior, generated ownership, simulation and tests.
  Their local documentation links resolve. Optional write-only lighting does not need invented
  position/current feedback. Adapter preset correspondence is software evidence, not a physical
  observation of the lights.
- No periodic allocation or redundant output change was justified by these declarative files.
  Descriptor loading, catalog construction and generation are setup work; this pass does not
  claim a loop-time benchmark or a whole-runtime allocation audit.

## Unresolved physical configuration

Lightbot's project metadata says overall length is 0.4572 m. Its drivetrain says wheel-center
spacing is 0.45 m and wheel diameter is 0.096 m, implying a longitudinal wheel envelope of at
least 0.546 m. The collision footprint and drivetrain dimensions cannot all describe the same
physical envelope. The measured overall length and wheel-center spacing were requested; neither
was guessed or changed. The project and drivetrain remain partially reviewed in the ledger
until this discrepancy can be resolved. Width, mounting polarity, encoder calibration and
actual timing likewise have no new physical validation in this pass.

## Validation and pending work

| Product | Evidence |
|---|---|
| FTC TeamCode | 171 tests passed; generated-project verification and debug APK build passed |
| FTC simulator | 13 tests passed |
| FTC starter TeamCode | 15 tests passed; generated-project verification and debug APK build passed |
| FTC starter simulator | 1 test passed |
| Studio | Production and test compilation passed; test execution still pending |

All 200 executed tests passed with no failures, errors or skips. The library source and candidate
were unchanged, so their full pass-258 evidence remains applicable. FRC was unaffected and was
not retested. No physical robot, live Studio UI, push, merge, deployment or release was performed.

Studio tests and final repository policy remain subject to pass 258's release/archive alignment
gate. Automatic approval review previously rejected the local archive/reference migration;
this pass does not bypass that gate or claim Studio tests passed. FTC/starter source changes
also mean the previous archive proposal is now stale: approval must be followed by a refreshed
exact archive/source comparison before it can be applied. No archive or release reference was
changed here. See the [shared safety audit](ftc-shared-safety-audit.md) for the prior checkpoint.

Local evidence is under `ARESLib-Kotlin/build/audit-pass259-verified-evidence/`: original failing
XML, full product XML/logs, Studio compilation log, `document-review.json`, source hashes,
`summary.json`, policy output and the final inventory. The ledger closes only fully reviewed
and validated scopes; Studio execution and the physical geometry discrepancy remain open.
