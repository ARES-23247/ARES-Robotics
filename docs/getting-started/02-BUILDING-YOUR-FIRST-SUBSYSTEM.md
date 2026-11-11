# 2. Building Your First Subsystem (Intake & Arm)

In **ARES Robotics Studio**, you don't have to write hundreds of lines of boilerplate motor setup and safety checks by hand. Instead, you design your mechanism in the **Subsystem Builder** using **Student Essentials Mode**, and ARES generates the safety-checked hardware plumbing for you.

---

## Understanding "Student Essentials Mode" vs. "Full Engineering Mode"

When you open the **Subsystems** tab in Studio, you'll see a banner at the top of the workspace:

- **Student Essentials Mode (Default):** Focuses on the 4 core steps every mechanism needs:
  1. **Purpose & Template** — Name your mechanism and pick a starter archetype.
  2. **Hardware & IO** — Add motors, servos, and sensors, and assign their ports.
  3. **Stateflow & Control** — Define target goals (voltage, velocity, or angle/position).
  4. **Tuning & Review** — Check for warnings, view the **GUI ↔ Code Bridge**, and click **Save & create files**.
- **Full Engineering Mode:** A 1-click toggle for mentors and advanced students that reveals commissioning checklists, interactive fault-injection labs, and unit-test verification controls.

---

## Walkthrough A: Creating a Simple Roller Intake (Open-Loop Voltage)

An intake roller usually doesn't need an encoder or PID loop—it just spins forward to collect a game piece and backward to eject it.

1. **Step 1 (Purpose & Template):**
   - Click **Add a mechanism**.
   - Type `Intake` under **Mechanism name**.
   - Select the **Simple Actuator** starter template.
2. **Step 2 (Hardware & IO):**
   - Click the default motor in the hardware list to open its inspector drawer.
   - Rename it to `intakeMotor` and assign its physical port (for example, Control Hub Port `0` in FTC or CAN ID `10` in FRC).
3. **Step 3 (Stateflow & Control):**
   - Notice that the template already created a writable target (`targetVoltage` / `targetDutyCycle`) and an **Open-Loop** control rule.
4. **Step 4 (Tuning & Review):**
   - Confirm that **Ready-to-build Summary** shows `0 blocking validation errors`.
   - Click **Save & create files** in the top-right header.

---

## Walkthrough B: Creating a Position-Controlled Arm (Closed-Loop PID)

An arm or elevator moves to specific angles or heights (like `Intake`, `Carry`, and `Score`) and must stop at safe upper and lower limits.

1. **Step 1 (Purpose & Template):**
   - Add a new subsystem named `Arm`.
   - Choose the **Position Mechanism** (or **Homed Mechanism** if your arm zeroes against a limit switch on startup).
2. **Step 2 (Hardware & IO):**
   - Configure `armMotor` and ensure an encoder feedback source is attached.
   - Set the gear ratio / unit scale so the position is measured in **degrees** or **radians** (internally ARES uses radians for math, while displaying friendly units in the GUI).
3. **Step 3 (Stateflow & Control):**
   - Inspect the measured state field (`position`) and target state field (`targetPosition`).
   - Open the **Control Loop** rule to review your initial `kP`, `kD`, and soft limits (`minPosition`, `maxPosition`).
4. **Step 4 (Tuning & Review):**
   - Click **Save & create files**.
   - You can now bind controller buttons to `Set Arm Target Position` in the **Controller Bindings** screen or tune `kP` live in the Simulator!

---

## Next Step

Where do the generated files go, and where should your team write custom code? Continue to **[3. The GUI ↔ Code Bridge](03-GUI-TO-CODE-GUIDE.md)**.
