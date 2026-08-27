# Drivebase authoring contract

Drivebases are robot systems, not ordinary generated subsystems. They own physical topology,
geometry, localization, driver and trajectory control, safety, simulation, and calibration. Store
one canonical contract in `.ares/drivetrains/<id>.aresdrivetrain`; do not represent the drivebase as
an `.aressubsystem` merely to reuse that editor.

## Supported starting points

- `FTC_MECANUM`: four explicitly named drive motors and a primary localization source.
- `FRC_CTRE_SWERVE`: four explicit modules. Each module associates its drive motor, steer motor,
  and absolute encoder. `TunerConstants.java` remains vendor-owned and read-only; the document
  records its path, hash, generator name/version, and fully qualified constants class.
- `DIFFERENTIAL`: left/right grouped drive hardware with differential geometry.
- `ADVANCED_CUSTOM`: an explicit topology that still satisfies the common safety/simulation
  contracts.

Every component has an immutable UID independent of its hardware-map/CAN name. Swerve modules also
have UIDs and physical X/Y positions. Components declare controller/encoder models, inversion,
current-measurement capability, an optional controller-enforced current limit, and module
association. Renaming a display or hardware ID must not change its UID.

Localization declares a primary odometry source, one explicit CCW-positive heading source, and zero
or more vision-fusion sources. All reads are cached. The simulator must use the same physical
geometry and selected canonical tuning profile as the robot; a second set of simulator constants is
invalid.

Safety is fail-closed: configuration health and fresh feedback gate output, required current
monitoring uses finite cached samples, disabled output is neutral, brake/coast behavior is explicit,
output faults latch, recovery requires a confirmed neutral write, and periodic paths remain
allocation-free. A component declares `currentLimitAmps` only when its runtime controller really
configures and enforces that limit; current-reading validity never implies a fabricated limit.

## Calibration provenance

Calibration-derived values name the affected parameter UIDs and link immutable evidence by
project-relative path and SHA-256. Accepted provenance includes measured geometry, SysId, vendor
generation, manufacturer data, and reviewed manual measurements. Never promote a value merely
because it happens to exist in a robot-local overlay.

## Generated plumbing

Build codegen reads the drivebase document and canonical profiles, then writes only:

- `GeneratedAresDrivebaseConfig.kt`: typed geometry, physical ratios, drivebase safety policy,
  vendor provenance, document hash, and direct drivebase parameter constants;
- `GeneratedAresTuningConfig.kt`: direct project-wide canonical constants, complete parameter
  declarations/metadata, and the `TypedTuningRuntime` factory used by robot and dashboard transport;
- `.ares-drivebase-manifest`: deterministic generated-file ownership.

Use `--drivebase-output <build/generated/...>` and `--drivebase-package <package>`. Ordinary compile
may refresh this mechanical output and remove files listed by a stale manifest when no drivebase
document remains. It never creates editable source, edits a vendor file, or consumes
`.ares/local/tuning`.

## Hand-authoring checklist

1. Measure wheel diameter, track width, wheelbase, ratios, and physical module positions in SI units.
2. Give every physical component and module a stable UID.
3. Declare the primary odometry, heading, and optional vision-fusion sources.
4. Declare supported/default control modes and all fail-closed policies.
5. Declare simulator model/adapter classes that consume generated geometry/profile values.
6. Declare typed parameters at the component that consumes them.
7. Add a checked-in canonical profile and calibration evidence.
8. Verify safe startup/disable/stop, invalid/stale feedback, current validity, fault recovery,
   physical/simulator parity, and no first-periodic-update value jump.
