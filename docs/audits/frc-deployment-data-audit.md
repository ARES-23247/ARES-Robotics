# FRC deployment data and vendor constants audit - pass 121

## Scope and ownership

Reviewed all deployed field JSON, all four deployed swerve offsets, the deployment README,
and the complete pinned CTRE-generated `TunerConstants.java`. The vendor file remains unchanged:
the ARES profile records its source hash, and CTRE Tuner X owns the motor/module configuration.
Reviewed factory use of calibration overlays and the generated profile values consumed by ARES.
The existing canonical configuration tests remain part of validation, not new coverage credit.

## Findings

No runtime calculation or data mismatch was found in this scope. Four new test methods verify:

- All sixteen AprilTag translations and rotations against the Crescendo layout bundled with
  the installed WPILib dependency. Degree/radian comparisons account for equivalent wrapped
  angles. Field dimensions agree within their existing one-millimeter rounding. Speaker tags
  slightly outside the nominal field bounds are intentional and match WPILib; they were not clamped.
- Eleven game pieces resolve unique type references and fit their entire planar footprint inside
  the field. Six obstacle identities are unique and their declared extents are inside the field.
  Dimensions, mass and friction/restitution values satisfy the checked model constraints.
- Actual CTRE module factory results match the generated ARES profile for drive/steer/encoder
  identities, drive inversions, CAN bus and gyro identity, module X/Y positions, wheel radius,
  gear ratios and free speed. The angular limit agrees with `linear speed / module radius`.
  The 10.875-inch half-spacing converts to 0.276225 m, and the 1.95-inch wheel radius agrees
  with the 0.09906 m wheel diameter in the profile.
- All four calibration factories create separate module constants with the requested offset
  while retaining module identities and geometry. Profile/deploy values match exactly in the
  existing tests, and their rounded rotations agree with vendor defaults within 1e-7 rotations.

A separate strict JSON check verifies exactly four named offsets, each finite and within
[-1, 1] rotations. The deployment guide's fetch workflow was traced to the actual Gradle task:
temporary download, required-key/value validation, atomic replacement and temporary-file cleanup.
No download or robot deployment was executed.

The guide previously specified 0.40 m center clearance for the 0.80 m square bumper without
explaining the effect of rotation. It now distinguishes axis-aligned clearance from rotated
corner checks: at 45 degrees the required clearance is `0.40 * sqrt(2)`, about 0.566 m.
The runtime autonomous footprint validator already checks rotated corners; its behavior did not change.

## Validation and limits

Full FRC validation passed 278 tests with zero failures, errors or skips, including four new
deployment-data methods and the four existing canonical configuration methods. Generated-project,
namespace, monorepo-policy and current documentation-link checks passed. Gradle reused valid
unchanged outputs. No additional allocation suite was needed because runtime code did not change.
XML, logs, hashes and offset-check evidence are retained under
`ARESLib-Kotlin/build/audit-pass121-verified-evidence/`.

Tests construct configuration objects with desktop HAL/Phoenix support; they do not construct
the vendor drivetrain or its motor devices. The generated drivetrain constructors were reviewed
for argument forwarding and compiled, not exercised against physical CAN hardware. Their scheduling
and device behavior are owned by the pinned vendor dependency. Public scalar tuning fields in the
generated file are startup configuration inputs, not an ARES-supported live tuning interface.

Agreement with bundled WPILib layout data does not replace a physical field survey. Obstacle and
game-piece shapes remain simplified simulator models. This review does not certify measured motor
gains, absolute encoder calibration, traction, worn wheel dimensions or actual hardware topology.

ARESLib remains unchanged at candidate `17.0.3-rc.100852e472fb`, tree
`100852e472fbeeba64fdf799665f51b4687f7f1b`. No push, merge, release or HIL run occurred.
