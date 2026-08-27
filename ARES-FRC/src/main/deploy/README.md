# RoboRIO deploy files

GradleRIO copies this directory to `/home/lvuser/deploy` during deployment.

## Expected layout

```text
src/main/deploy/
|-- README.md
|-- swerve_offsets.json
`-- paths/
    `-- field.json
```

Autonomous routines and their capability catalog live only in the repository-root `.ares/`
project and are compiled into `GeneratedAresProject.kt`. The RoboRIO never loads loose routine or
capability documents at runtime. `SmartDashboard/SelectedAuto` selects among those compiled entries;
`do-nothing` is the fail-safe default.

`do-nothing` is reserved: the runner validates it but preserves the current localized pose instead
of applying the document's placeholder starting pose.

Autos are authored once in Blue-alliance, corner-origin field coordinates. Red execution reflects
X across the alliance-wall axis before trajectory generation. Keep every robot center at least
0.40 m from the field boundary for Marvin's current 0.80 m square bumper footprint.

## Swerve offsets

`swerve_offsets.json` contains module azimuth offsets in rotations:

- `frontLeft`
- `frontRight`
- `backLeft`
- `backRight`

The Gradle `fetchOffsets` task retrieves `/home/lvuser/swerve_offsets_runtime.json` from the RoboRIO
at `10.232.47.2` into a temporary file, validates all four finite rotation values, and atomically
replaces this baseline only on success. Review all four values and re-test module orientation before
deploying.

## Relative mechanism safe-zero

Marvin's cowl, intake pivot, and climber have relative TalonFX encoders and no absolute/limit
reference. Every real robot process therefore starts with mechanism outputs inhibited. Physically
place all three mechanisms at their documented zero stops, then have both operators hold
Back+Start together once while Disabled or Test-enabled. Any failed zero write leaves every
mechanism inhibited; configuration alone never clears this guard.

Do not put secrets or cloud credentials in this directory. The robot is offline-first: logs and telemetry are consumed over the local robot network, while cloud synchronization runs on the laptop.
