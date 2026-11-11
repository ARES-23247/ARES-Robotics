# ARES Robotics Studio — Student & Mentor Getting Started Hub

Welcome to **ARES Robotics Studio**! Whether your team competes in **FTC**, **FRC**, or learns robotics with **XRP**, these guides take you from opening the app for the first time to building mechanisms, running the local physics simulator, and writing custom robot code safely.

## Learning Path (Start to Finish)

| Step | Guide | Time | What You'll Learn |
|---|---|---|---|
| **1** | **[Your First 15 Minutes in the Local Simulator](01-FIRST-15-MINUTES-SIMULATOR.md)** | 15 min | Launch Studio, start the local simulator without hardware, arm keyboard controls (`WASD`), and graph telemetry signals. |
| **2** | **[Building Your First Subsystem](02-BUILDING-YOUR-FIRST-SUBSYSTEM.md)** | 20 min | Use **Student Essentials Mode** in the Subsystem Builder to create a simple roller intake and a PID-controlled arm. |
| **3** | **[The GUI ↔ Code Bridge](03-GUI-TO-CODE-GUIDE.md)** | 15 min | See how `.ares` files generate read-only plumbing under `build/generated/ares/` and where your team writes custom Kotlin or MicroPython code safely. |
| **4** | **[Coming from the FTC SDK or WPILib](04-COMING-FROM-FTCSDK-OR-WPILIB.md)** | 15 min | Translate classic `OpMode` loops or WPILib Command-Based subsystems into ARES's deterministic `Input → Action → Reducer → State → Controller → IO` flow. |

## Ready-to-Use Student Examples

Looking for working mechanism recipes? Browse the **[Student Example Cookbook (`../examples/README.md`)](../examples/README.md)**:

- **[FTC Intake & Arm Example](../examples/ftc-intake-and-arm.md)** — Voltage-controlled intake + profiled position arm with soft limits.
- **[FRC Shooter & Indexer Example](../examples/frc-shooter-and-indexer.md)** — Velocity-controlled flywheel + beam-break sensor interlock.
- **[XRP Autonomous Sensors Example](../examples/xrp-autonomous-sensors.md)** — Ultrasonic rangefinder + reflectance line follower in MicroPython.

## Core Safety Rules Every Student Should Know

1. **Practice First in the Local Simulator:** You never need a physical robot to test your code or dashboard layouts.
2. **Outputs Default to Safe Neutral:** Motors will not spin unless your configuration is valid, sensors are fresh, and the robot/simulator mode is explicitly armed.
3. **Clicking Outside Disarms Keyboard Drive:** In the simulator, if you click another window or open an inspector drawer, ARES automatically disarms `WASD` driving so the robot never drives away unexpectedly.
