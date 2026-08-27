# FRC drivebase configuration

Marvin XIX keeps CTRE Tuner X and ARES configuration deliberately separate:

- `src/main/java/frc/robot/generated/TunerConstants.java` is vendor-owned, read-only source.
- `.ares/drivetrains/marvin-ctre-swerve.aresdrivetrain` imports that source by path, class, generator,
  and SHA-256 while explicitly describing every drive motor, steer motor, CANcoder, module, Pigeon,
  localization source, safety policy, and simulator contract.
- `.ares/tuning/marvin-competition.arestuning` holds ARES-owned path/simulation values and a reviewed
  copy of the checked-in calibration baseline.
- `src/main/deploy/swerve_offsets.json` remains the explicit deploy-time calibration overlay.

Never hand-edit `TunerConstants.java`. Change vendor motor IDs, ratios, gains, limits, or module
geometry in Tuner X, regenerate the vendor file, then review and update the descriptor import hash.
ARES codegen writes typed plumbing only below `build/generated/ares/drivebase/kotlin`.

## Runtime flow and offsets

`CanonicalDrivebaseConfig` installs the checked-in ARES profile into immutable Redux state before
the shared swerve robot starts. CTRE still constructs physical modules from `TunerConstants`.
`SwerveOffsetManager` then loads `swerve_offsets.json` or the robot-local runtime offset file and
applies those four values explicitly. Generated offset constants document and test the reviewed
baseline; runtime intentionally does not substitute them for the overlay loader.

The current Marvin application exposes typed tuning metadata but has no dedicated armed tuning
mode, so runtime mutation fails closed. Even when an armed workflow is added, the current consumer
accepts only the two ARES path fields it rebuilds into immutable Redux state; vendor metadata,
offsets, simulator constructor values, and unknown fields roll back. Promotion remains an offline review: fetch offsets from the
RoboRIO, validate all four finite one-rotation values, update evidence/hash and canonical profile,
then commit them together.

## Simulation parity and verification

Dyn4j receives robot length/width and linear/angular tracking gains from the same generated profile.
Tests pin the vendor-source hash, geometry/ratios, exact deploy-offset precision, canonical Redux
values, and simulation dimensions. CTRE drive supply-current samples are cached and validity-gated;
configured drive and steer current limits remain owned by Tuner X. Disabled output is explicit
neutral brake, and output/configuration faults remain fail-closed.

Without a physical robot, run generation, unit tests, simulation, and assembly. Before competition,
also verify CAN IDs, module direction, CANcoder zero, Pigeon CCW-positive heading, current validity,
disabled neutral, and calibration recovery on a restrained chassis.
