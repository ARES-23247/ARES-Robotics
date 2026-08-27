# Hardware Setup

Hardware Setup is the bridge between a simulated robot project and a reviewed physical wiring plan.
It does not replace the Drivebase Builder or Subsystem Builder, and it does not scan Kotlin source.

## Where hardware values belong

- Drive motors, localization sensors, CAN buses, geometry, and drivetrain inversion stay in
  `.ares/drivetrains/*.aresdrivetrain`.
- Mechanism motors, servos, sensors, channels, safe outputs, current limits, and follower
  relationships stay in `.ares/subsystems/*.aressubsystem`.
- Hardware Setup reads both sources and reports one combined inventory. Use its links to edit the
  owning builder instead of creating a second mapping.

## What the review proves

Beside the disabled robot, compare every listed device with the robot configuration and wiring
diagram. Confirm all five checks:

1. The device exists and the wiring diagram matches.
2. Hardware-map names, CAN IDs and buses, PWM/DIO/analog channels match the controller.
3. Device inversion and leader/follower direction match the mechanism.
4. Every actuator has a reviewed safe neutral and disabled/stop behavior.
5. Current, soft, motion, homing, and feedback limits are reviewed where applicable.

ARES appends configuration-review evidence under `.ares/evidence/hardware/configuration/`. Each
record contains the exact descriptor inventory hash, source hashes, reviewer, and timestamp. A
drivetrain or subsystem edit changes those hashes and makes prior evidence **stale** without erasing
it. Re-review the new mapping; do not edit evidence JSON by hand. Powered physical validation is a
different record under `.ares/evidence/hardware/physical/` and can never be inferred from a form,
build, or simulation.

For FTC, the commissioning card gathers every exact name to enter in **Driver Station → Configure
Robot → Hardware** and can copy the complete checklist. Copy spelling and case exactly; the default
drive motor names are `fl`, `fr`, `rl`, and `rr` (rear, not back). The individual device cards show
the same source-owned values. For FRC, each card shows the CAN ID and bus. ARES blocks duplicate
addresses, but students must still confirm the physical labels and vendor configuration.

## Read the subsystem commissioning plan

Hardware Setup also derives a per-mechanism plan from the saved subsystem descriptors. For each
actuator it names the safe output, available control strategies, cached measurements, follower
relationships, homing evidence, and any calibration or current-monitoring requirement. Treat this
as a checklist for a mentor-led bring-up, not as proof that the declarations match the machine.

The displayed **UNARMED PULSE PROPOSAL** is intentionally non-executable. It documents a bounded
proposal (at most 10% away from neutral for at most 250 ms) so a student can discuss identity and
direction before any powered test. The desktop app does not send that proposal to a robot. Use the
league's disabled/enable boundary, physical restraints, a spotter, and the purpose-built robot-side
diagnostic when the team later authorizes physical commissioning.

Transfer gains only after the sensor sign, canonical unit, home reference, soft limits, neutral
behavior, and configuration health are verified. Start from simulation evidence, change one
bounded value, preserve the run, and never interpret a copied gain as physical validation.

## Check FTC motor identity and direction

ARES Robotics Studio does not command a physical motor from the desktop. Instead, it shows the generated
name, configured direction, and face-button mapping used by the **ARES Drivetrain Diagnostic**
TeleOp on the Driver Station. This keeps enabled hardware control inside the normal FTC OpMode and
Stop-button boundary.

1. Put the robot on secure blocks so every wheel is clear of the floor. Remove game pieces and keep
   hands, hair, clothing, and tools away from moving parts.
2. In the Driver Station, select **ARES Drivetrain Diagnostic**. During INIT, confirm all four exact
   generated hardware names report **FOUND**. One missing device blocks every motor.
3. Press Play. Hold only one displayed face button at a time. Release it to command zero immediately.
4. Confirm the named wheel moves in the expected forward direction. Press Stop immediately if a
   different wheel or direction moves.
5. Correct the canonical drivetrain name or inversion in Drivebase Builder, regenerate the project,
   and repeat the check. Do not create an alias in the Robot Controller configuration.
6. Return to Hardware Setup and complete the physical review. Optionally open the live read-only
   self-test for sensor and telemetry evidence.

This diagnostic checks only motor identity and the configured direction boundary. It does not prove
odometry scale, localization, closed-loop gains, current limits, mechanism safety, or match readiness.
Those require their own simulated and supervised physical checks.

## What the review does not prove

A current review is not a powered hardware test, calibration result, inspection approval, or proof
that a mechanism is safe to move. Follow your team's supervised bring-up procedure, test one device
at a time at low output, and preserve logs.

The desktop app never pulses a physical actuator. Any future desktop-initiated identification flow
would need robot-side mode gates, bounded output and duration, a lease/nonce, automatic neutral on
communication loss, and physical test evidence. The current Driver Station diagnostic is local,
hold-to-run, blocks partial mappings, uses 40% output, and attempts to neutralize every discovered
motor on stop or failure. The offline drive-mixing lab still checks intended direction and follower
math only.

Downloaded Team 23247 season starters remain **simulation/reference only** even after a review.
Their hand-authored composition is not yet fully represented by GUI-owned descriptors, so the
inventory cannot prove that every physical device was reviewed. A future generic composition may
use the review-required policy only after that completeness is verified. ARES will still block it
whenever the review is missing, stale, invalid, or has address conflicts.
