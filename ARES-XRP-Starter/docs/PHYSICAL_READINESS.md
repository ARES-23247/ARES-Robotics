# XRP physical-readiness record

Status: **Ready for physical validation; not physically validated**
Updated: 2026-09-04

## Evidence completed without a robot

- The official XRP firmware release `v2.0.5` is pinned by commit, byte length, and SHA-256 for the
  SparkFun RP2350 and SparkFun beta RP2040 controllers.
- Both supported controllers expose motor ports 1–4. The RP2350 profile exposes servo ports 1–4;
  the beta RP2040 profile exposes servo ports 1–2. Studio and device preflight enforce those limits.
- MicroPython `1.28.0` and XRPLib `2026.08.2` are immutable requirements. Preflight reads the live
  runtime identities, detects exactly one supported controller, and rejects stale or ambiguous devices.
- The generated project, ARES MicroPython runtime, simulator, and standard JUnit verification evidence
  pass on desktop. Invalid commands, expired leases, brownouts, sensor failures, and output failures
  neutral motion and latch faults.
- SparkFun OTOS register addresses, product identity, scalar encoding, pose/velocity units, offsets,
  pose reset, and IMU calibration follow SparkFun's published driver. Qwiic bus assignments follow
  the published SparkFun controller pinouts.
- Deployment gives each attempt a fresh slot, hashes every payload file, and compiles every Python
  file on-device before activation. It replaces durable markers without removing the active marker
  first; the launcher can recover from the previous marker before executing any robot code.
  `plan-deploy` exercises the same payload compilation without touching a device; `rollback` restores
  the previous slot. Interrupted/older inactive slots are retained for diagnosis; remove only slots
  referenced by neither `/ares_active_slot.txt` nor `/ares_active_slot.prev` when reclaiming device storage.

## Physical checks still required

Perform these with the wheels lifted and mechanisms mechanically safe. Store the resulting review as
physical evidence; never change canonical configuration merely to claim a pass.

1. Confirm the detected board, firmware, MicroPython, XRPLib, and project content identity.
2. Pulse each drivetrain motor individually; record port, direction, and encoder polarity. Repeat for
   all four wheels on an expansion mecanum build.
3. Confirm distance, reflectance, user-button, IMU, and optional OTOS readings against known motion or
   references. Calibrate OTOS with the robot stationary and verify its mounting transform.
4. Verify each servo, light, buzzer, and expansion I/O safe state before enabling an action.
5. Remove the control heartbeat and network connection while moving; confirm neutral output within the
   declared deadman interval.
6. Measure the real battery-warning and brownout behavior rather than relying on simulated voltage.
7. Interrupt a deployment before activation, then confirm the prior slot remains bootable. Deploy a
   second known-good slot and verify explicit rollback.
8. Run TeleOp and autonomous at low limits, inspect telemetry freshness/fault state, then increase limits
   only after the configuration review is signed by the person who performed it.

## Authoritative protocol references

- [Open-STEM XRP firmware](https://github.com/Open-STEM/XRP_Firmware)
- [Open-STEM XRPLib / XRP MicroPython](https://github.com/Open-STEM/XRP_MicroPython)
- [SparkFun XRP controller pinout](https://docs.sparkfun.com/SparkFun_XRP_Controller/hardware_overview/)
- [SparkFun Qwiic OTOS Python driver](https://github.com/sparkfun/Qwiic_OTOS_Py/blob/master/qwiic_otos.py)
- [MicroPython mpremote](https://docs.micropython.org/en/latest/reference/mpremote.html)
