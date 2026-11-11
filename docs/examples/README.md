# ARES Robotics — Annotated Student Example Cookbook

This cookbook contains step-by-step, student-friendly examples for **FTC**, **FRC**, and **XRP** mechanisms. Each example walks through:
1. **What to configure in the Studio GUI** (Subsystem Builder, Port Map, Controller Bindings).
2. **What ARES generates automatically** (`build/generated/ares/`).
3. **Heavily commented student extension code** (`USER-OWNED`) that you can copy, customize, and test in the Local Simulator without risking overwrites.

---

## Recipes by League

| League | Recipe | Mechanisms Covered | Control Concepts |
|---|---|---|---|
| **FTC** | **[FTC Intake + Position-Controlled Arm](ftc-intake-and-arm.md)** | Open-loop roller intake + PID/profiled scoring arm with soft limits | Open-loop voltage, closed-loop position, soft limits, safe coordination helper |
| **FRC** | **[FRC Flywheel Shooter + Beam-Break Indexer](frc-shooter-and-indexer.md)** | Velocity-controlled dual-motor flywheel + sensor-interlocked feeder | Velocity PID + feedforward, digital beam-break sensor gating, ready-to-fire check |
| **XRP** | **[XRP Autonomous Ultrasonic & Line Sensors](xrp-autonomous-sensors.md)** | Ultrasonic rangefinder + reflectance line sensor in MicroPython | Deterministic sensor reads, safe threshold helpers, local XRP simulator verification |

---

## New to ARES?

If you haven't run the Local Simulator yet, start with **[Student & Mentor Getting Started (`../getting-started/README.md`)](../getting-started/README.md)**.
