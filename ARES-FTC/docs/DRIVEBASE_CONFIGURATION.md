# FTC drivebase configuration

The goBILDA mecanum drivebase has one checked-in physical contract and one checked-in competition
profile:

- `.ares/drivetrains/gobilda-mecanum.aresdrivetrain` describes motor identities and polarity,
  geometry, Pinpoint/IMU/Limelight localization, control modes, safety, and simulator parity.
- `.ares/tuning/competition.arestuning` assigns encoder scale, gains, feedforward, path limits,
  and localization calibration values. Hardware IDs and motor polarity are not tuning values;
  they live only in the physical descriptor.

Gradle validates those documents and writes `GeneratedAresDrivebaseConfig.kt`,
`GeneratedAresTuningConfig.kt`, and `GeneratedAresFtcMecanumRuntimeConfig.kt` below
`TeamCode/build/generated/ares/drivebase/kotlin`. Those files are deterministic mechanical
plumbing: do not edit or commit them. The generated runtime creates the shared FTC drivetrain and
maps the canonical profile into immutable Redux state. TeamCode no longer maintains a parallel
handwritten constant adapter that could drift from the reviewed documents.

## Safe editing workflow

1. Edit the descriptor when hardware identity, geometry, polarity, localization source, or safety
   behavior changes.
2. Edit the canonical profile when a reviewed value changes. Keep units and bounds accurate.
3. Run `gradlew :TeamCode:generateAresProject`, inspect the structured document diff, then
   run TeamCode and simulator tests.
4. For calibration-derived values, retain measured evidence and update its SHA-256 provenance.
5. With the robot restrained, verify wheel direction, neutral/disabled behavior, current validity,
   and CCW-positive heading before driving freely.

Hardware names are exactly `fl`, `fr`, `rl`, and `rr`; ARES uses **rear**, not back, naming. Pinpoint
normalizes heading at the hardware boundary, so do not add another sign inversion in Redux,
localization, telemetry, or simulation.

## Current and tuning safety

Each drivetrain adapter supplies a finite cached current sample. FTC electrical protection is the
shared aggregate 20 A software-fuse/current-budget model, with Floodgate data taking authority when
available. This robot does not claim a controller-enforced per-motor current limit.

Invalid drivetrain commands and failed motor writes latch all four outputs at neutral. The latch is
shown as `Drive/OutputFaultLatched` and as plain Driver Station text. In **Controller Bindings**,
search for **Recover drive after a fault** and bind it to a deliberate button or chord. Release the
drive controls before invoking it. Recovery succeeds only when Redux drive intent is neutral,
calibration does not own the motors, and all four motors accept a neutral write. Ordinary stop/safe
calls never silently clear the latch.

Typed tuning publishes only declared parameters. Live-safe values require an armed calibration
session; calibration-only values additionally require explicit UID authorization. Restart/rebuild
and read-only values never mutate at runtime. The consumer explicitly accepts only parameters it
can rebuild into immutable Redux state; unknown or constructor-only parameters roll back. FTC
currently has no trustworthy disabled signal in
the season facade, so disabled-only requests fail closed. Accepted experiments are written only to
`.ares/local/tuning/runtime.arestuning`; they never overwrite the checked-in competition profile.

## Simulation and physical verification

The desktop simulator consumes the same generated geometry, encoder scale, and profile as TeamCode.
Unit and simulator tests cover deterministic generation, initial-state parity, safe metadata, and
configuration mapping. Physical direction, current response, Pinpoint mounting calibration, and
traction still require a restrained robot when hardware becomes available.
