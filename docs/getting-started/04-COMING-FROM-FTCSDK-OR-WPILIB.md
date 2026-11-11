# 4. Coming from the FTC SDK or WPILib

If you have programmed an **FTC** robot with `LinearOpMode` / `OpMode` or an **FRC** robot with WPILib's `SubsystemBase` and `Command`, ARES will feel familiar once you see how its **deterministic control loop** maps to what you already know.

---

## Why ARES Uses `Input → Action → Reducer → State → Controller → IO`

In traditional student robot code, it is easy to accidentally call `motor.setPower(...)` or `motor.getCurrentPosition()` in five different places during the same loop. On real hardware (especially FTC I2C/RS-485 Lynx hubs and FRC CAN buses), scattered hardware reads and writes cause:
- **Slow loop times** (blocking I2C/CAN reads inside commands)
- **Conflicting motor commands** (two commands fighting over the same motor in one loop)
- **Hard-to-replay bugs** (no single snapshot of what the robot thought its state was)

ARES solves this by running every 20 ms robot loop in a strict, predictable order:

```text
1. IO Read (Cached Once)  -->  Reads all encoders, IMUs, and sensors at the start of the loop.
2. Actions                -->  Gamepad buttons, autonomous steps, and sensor events create Actions.
3. Pure Reducer           -->  Combines current Immutable State + Action -> New Immutable State.
4. Controller             -->  Computes safe motor voltages/outputs from the New Immutable State.
5. IO Write (Once)        -->  Sends the final, safety-checked outputs to motors/servos.
```

---

## Side-by-Side Comparison

| Concept | Classic FTC SDK / WPILib | How You Do It in ARES |
|---|---|---|
| **Declaring a motor or sensor** | `hardwareMap.get(DcMotorEx::class.java, "intake")` or `SparkMax(10, ...)` | Add the device in **Subsystems $\rightarrow$ 2. Hardware & IO** (or the **Port Map** tab). ARES generates the hardware adapter automatically. |
| **Reading an encoder** | Calling `motor.currentPosition` inside `loop()` or `periodic()` | Read the cached value from the subsystem's **Immutable State** (cached once at the start of the loop—zero extra bus delay!). |
| **Commanding a mechanism** | Calling `intakeMotor.power = 1.0` directly on the motor object | Dispatch a typed **Action** (or bind a gamepad button in **Controller Bindings** to `subsystem.intake.set.targetVoltage`). |
| **Safety stop on disable / fault** | Remembering to write `motor.power = 0.0` in every command's `end()` method | Built-in **Fail-Closed Contract**: if the robot disables, a sensor faults, or a control lease expires, ARES automatically forces neutral outputs. |
| **Testing without a robot** | Often requires commenting out hardware code or deploying to a physical hub | Click **Launch simulator** in the Dashboard—every generated subsystem includes a simulation adapter automatically. |

---

## Key Coordinate & Math Conventions

To keep autonomous paths, odometry, and field visualizations consistent across FTC, FRC, and XRP:

1. **Counter-Clockwise Positive (CCW+) Heading:** Turning **left** (counter-clockwise when viewed from above) always **increases** heading angle ($\theta$).
2. **Radians Inside Math, Friendly Units in the GUI:** Internal kinematics and estimators use **radians** and **meters**, while Studio's inspectors and charts let you view and tune angles in **degrees** (`deg`).
3. **Robot Clock Only (`RobotClock`):** Never use `System.currentTimeMillis()` in control logic; ARES passes deterministic loop timestamps so your code behaves identically in live matches, unit tests, and log replay.

---

## Where to Go Next

Explore complete, copy-ready mechanism walkthroughs in the **[Annotated Student Example Cookbook (`../examples/README.md`)](../examples/README.md)**.
