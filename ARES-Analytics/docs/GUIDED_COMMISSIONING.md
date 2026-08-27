# Guided commissioning and control design

ARES Robotics Studio separates controller design, deterministic simulation, configuration review,
and real-robot validation. These are different kinds of evidence and must never be collapsed into
one green badge.

## Evidence levels

| Level | What it proves | What it does not prove |
|---|---|---|
| **Simulation verified** | Every descriptor-owned subsystem controller remained bounded and passed its applicable deterministic nominal, stale/invalid/frozen-feedback, failed-write, brownout, excessive-current, unconfigured, unhomed, and neutral-recovery scenarios. | Real motors, sensors, wiring, timing, friction, current, or mechanism clearance. |
| **Configuration reviewed** | A named person checked wiring, addresses, directions, neutral behavior, and limits for the exact canonical inventory hash. | That any device moved or reported correctly. |
| **Ready for physical validation** | Simulation evidence and configuration review are both current and no blocking inventory issue remains. | Physical validation itself. |
| **Physically validated** | A named observer explicitly recorded a supervised real-robot procedure, result, and limitations against the exact inventory hash. | Any behavior outside the recorded procedure. |

Changing a drivetrain or subsystem descriptor changes the inventory hash. Studio then marks the
review stale and stops presenting prior physical evidence as current.

## Hardware-free controller workflow

1. Choose the controller in Subsystem Builder.
2. Open **Commission this controller safely**.
3. Run the nominal teaching model, then every applicable injected fault.
4. Confirm faulted output is neutral, latching occurs where required, and explicit neutral recovery
   succeeds before motion resumes.
5. Adjust only the draft. Use the normal structured review before saving the canonical descriptor.

The sandbox is deterministic and useful for teaching control semantics. It is not a digital twin and
cannot establish robot-safe gains.

## SysId capability boundary

The hardware-free SysId lesson is available for every mechanism. Live motion is different: the
connected runtime must publish `SysId/SupportedMechanisms`, and the selected mechanism must be in
that explicit list. A missing topic, an empty list, or an unknown mechanism fails closed. An FTC
runtime also requires the fresh STOP-first arm lease. The generic FRC starter currently advertises an
empty list until a reviewed Test-mode voltage adapter exists.

Teaching-model recommendations use `digital-twin:` provenance and cannot enter the tuning proposal
or promotion path. Measured recommendations still require typed units, declared bounds, consumer
support, explicit review, and rollback evidence.

## Physical handoff

Use **Robot Studio → Port Map & Review / Hardware Setup**. Copy the displayed FTC hardware-map names
or FRC addresses exactly. Review direction, follower transforms, encoder polarity, SI units,
coordinate signs, freshness, neutral/disabled behavior, current and motion limits, homing,
calibration, and clearance.

The displayed subsystem pulse is an **unarmed proposal**. Studio does not pulse physical mechanisms
from the review page. If a team later performs physical validation, it must use its supervised safety
procedure and explicitly record the observer, evidence, and limitations. With no real robot, stop at
**Ready for physical validation**.

