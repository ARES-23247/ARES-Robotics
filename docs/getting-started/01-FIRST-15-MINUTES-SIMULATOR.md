# 1. Your First 15 Minutes in the Local Simulator

You do **not** need a physical robot, battery, or Driver Station to drive a robot and inspect telemetry in **ARES Robotics Studio**. Studio includes a built-in local physics simulator for **FTC**, **FRC**, and **XRP**.

---

## Step 1: Open the Dashboard and Choose "Local Simulator"

1. Launch **ARES Robotics Studio** and open or create an FTC, FRC, or XRP starter project.
2. Click **Dashboard** in the left navigation bar.
3. At the top of the Dashboard, look at the **Mission Header** or the **Offline Guidance Banner**:
   - Select **Local Simulator (`127.0.0.1`)** as your connection target, or click **"Practice in Local Simulator"** directly on the welcome banner.
4. Notice the **3-Step Simulator Readiness Bar** inside the `Local FTC Simulator` / `Local FRC Simulator` strip:
   - **1. Launch Sim** $\rightarrow$ **2. Start TeleOp / Auto** $\rightarrow$ **3. Arm Controls (`WASD`)**

---

## Step 2: Launch the Physics Server

1. Click **Launch simulator** (or **Verify & launch** if your project was just edited).
2. Studio compiles your robot project and starts the local physics server on `127.0.0.1:5810`.
3. Once connected, the status dot turns green/amber and **Step 1 (Launch Sim)** shows a green checkmark.

> **Why isn't the robot moving yet?**
> Starting the simulator only boots the physics server and NetworkTables (`NT4`) connection. Just like a real match, the robot stays safely disabled until you start a mode and arm your controls.

---

## Step 3: Start Driving (`TeleOp`) and Arm `WASD`

1. In the simulator dropdown, make sure a **TeleOp** mode is selected, then click **Start driving**.
2. Click **Arm control** on the right side of the simulator bar.
3. Look at the **Keyboard Keycap Bar** at the bottom of the simulator strip:
   - `W` / `S` — Drive Forward / Backward
   - `A` / `D` — Strafe Left / Right
   - `←` / `→` — Rotate Counter-Clockwise (CCW+) / Clockwise
   - `Space` — Trigger primary mechanism action

> **Safety Tip (Why did my controls disarm?):**
> Whenever you click outside the Studio window or open a side drawer, ARES automatically **disarms** keyboard drive and sends neutral (`0.0`) commands so your simulated robot stops immediately. Simply click **Arm control** again when you are ready to drive.

---

## Step 4: Explore Dashboard Presets & Telemetry Charts

At the top of the Dashboard, use the **Layout Preset Pills** to switch between curated views:

- **Novice Quick-Start (`Starter`):** Shows only the 3 essential cards (*Field Viewer*, *Robot Pose*, and *Telemetry Line Chart*) so the screen stays uncluttered.
- **Pit Check:** Adds battery, motor health, hardware topology, and controller diagnostics for pre-match inspection.
- **Drive Practice:** Highlights the 2D field, swerve/mecanum chassis vectors, and driver station controls.
- **PID Tuning:** Opens live tuning sliders alongside high-rate telemetry charts.

### Adding Signals to a Telemetry Chart without Typing

In any **Telemetry Line Chart** card:
1. Click the quick-filter chips (**All**, **Drive**, **Pose**, **Vision**, **Battery**) to filter available robot signals.
2. Click any signal chip (such as `Drive/Vx` or `Odometry/HeadingDeg`) to plot it immediately—no need to memorize topic paths!

---

## Next Step

Ready to add an intake, arm, or shooter to your robot? Continue to **[2. Building Your First Subsystem](02-BUILDING-YOUR-FIRST-SUBSYSTEM.md)**.
