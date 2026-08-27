# Drivebase Builder

The Drivebase Builder is the dedicated authoring workflow for the part of a robot that moves. It is separate from the Subsystem Builder because drivetrain geometry, localization, field-relative controls, vendor calibration, and simulation form one safety-critical contract.

## Student workflow

1. Choose FTC mecanum, FRC CTRE swerve, differential, or advanced/custom.
2. Select devices on the top-down chassis and enter their hardware-map names or CAN identities. Differential and advanced/custom projects can add or remove devices. A follower explicitly names one direct drive-motor leader; its Reverse switch is independent, so both same-direction and mirrored gearboxes are representable without follower chains.
3. Measure wheel radius, track width, and wheelbase. ARES stores meters; the screen explains which measurement each field expects. Overall bumper dimensions remain project/field configuration, not drivebase geometry.
4. Choose supported control layers: open-loop, wheel velocity, chassis velocity, and trajectory. Only make a closed-loop mode the default after its feedback and tuning exist.
5. Use the built-in drive-mixing lab to inspect every wheel/module command, field-relative transform, inversion, and follower output. It performs deterministic local math only and never publishes NT4 or commands hardware.
6. Choose localization sources. Heading is counter-clockwise positive and uses radians in robot code.
7. Review safe-neutral, configuration-health, feedback-freshness, current-validity, speed-envelope, fault-latching, brake/coast, and explicit neutral-recovery requirements.
8. Review a structured diff and explicitly confirm the save.

ARES disables authoring while the selected project is loading. Reloading or changing drive type after an edit requires a discard confirmation, so a late disk read or accidental template click cannot silently replace student work.

Canonical documents live at `.ares/drivetrains/<stable-uid>.aresdrivetrain`. A display-name change does not rename its stable identity. Before a reviewed replacement, ARES stores the prior document at `.ares/history/drivetrains/<stable-uid>/<content-hash>.aresdrivetrain`. A fresh project also receives the matching empty canonical `.ares/tuning/*.arestuning` profile during the reviewed save, so deterministic code generation never points at a missing profile. Physical geometry remains only in the drivebase document and is not duplicated as a tuning constant.

## CTRE TunerConstants

ARES may read a snapshot of `TunerConstants.java` to help populate CTRE swerve fields. The vendor file remains read-only:

- ARES never overwrites, reformats, or generates `TunerConstants.java`.
- Typed units such as `Inches.of(...)`, module positions, CAN IDs, encoder offsets, inversion, and CAN-bus identity must be recognized or surfaced for manual review.
- The import records the canonical source path and SHA-256 hash as calibration provenance.
- Warnings are blocking evidence for students to review; import is not proof of physical calibration.

## Without a physical robot

The builder, pure direction lab, mock/simulator parity, descriptor validation, CTRE parsing, structured diff, history, and code generation can be tested offline. Physical wheel direction, CAN wiring, measured geometry, current behavior, odometry scale, and calibration still require later hardware verification.

ARES intentionally does not offer a desktop-only physical “pulse motor” button. A safe active test
needs a robot-side protocol that proves the robot is in the allowed mode, bounds output and time,
holds a short lease, acknowledges the exact device and nonce, neutrals on every timeout, and reports
the observed result. Until that protocol exists and is physically validated, use the drive-mixing
lab for configuration and follow the supervised one-device-at-a-time procedure for hardware.
