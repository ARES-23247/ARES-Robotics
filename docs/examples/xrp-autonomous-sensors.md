# XRP Example: Autonomous Ultrasonic Rangefinder & Line Follower (MicroPython)

The **XRP (Experiential Robotics Platform)** runs **MicroPython** on a Raspberry Pi Pico W. **ARES Robotics Studio** supports XRP as a first-class citizen: you design your sensors and mechanisms visually in Studio, and ARES compiles deterministic Python plumbing into `build/generated/ares/python/`.

---

## Part 1: Setting Up Your XRP Sensors in Studio

1. Open an **XRP Starter** project in **ARES Robotics Studio**.
2. Go to **Subsystems** and click **Add a mechanism**.
3. In **1. Purpose & Template**:
   - **Mechanism name:** `RangeSensor`
   - **Template:** `Distance Sensor`
4. In **2. Hardware & IO**:
   - Verify the ultrasonic trigger/echo pins match your XRP board header (`Pin 20 / Pin 21`).
5. Click **Save & create files** to generate the MicroPython sensor contract and safety tests.

---

## Part 2: Writing Safe Student MicroPython Helpers (`USER-OWNED`)

ARES generates its read-only Python plumbing inside `build/generated/ares/python/`. Your team writes custom autonomous decision functions inside `src/subsystems/` where ARES will never overwrite them:

```python
# File: src/subsystems/xrp_autonomous_helpers.py
# Ownership: USER-OWNED (Safe for students to edit in VS Code / Thonny / Studio)

SAFE_STOP_DISTANCE_CM = 15.0
SLOW_APPROACH_DISTANCE_CM = 35.0
LINE_CONTRAST_THRESHOLD = 0.50


def compute_safe_forward_effort(distance_cm: float, sensor_valid: bool) -> float:
    """
    Computes a safe forward drive effort [0.0 .. 0.6] from the ultrasonic sensor.

    Fail-Closed Rule:
    If sensor_valid is False or distance_cm is closer than SAFE_STOP_DISTANCE_CM,
    immediately return 0.0 (neutral stop).
    """
    if not sensor_valid or distance_cm <= SAFE_STOP_DISTANCE_CM:
        return 0.0
    if distance_cm <= SLOW_APPROACH_DISTANCE_CM:
        # Smoothly slow down as the XRP approaches the wall
        ratio = (distance_cm - SAFE_STOP_DISTANCE_CM) / (
            SLOW_APPROACH_DISTANCE_CM - SAFE_STOP_DISTANCE_CM
        )
        return round(0.15 + 0.45 * max(0.0, min(1.0, ratio)), 3)
    return 0.60


def compute_line_follow_turn_effort(
    left_reflectance: float,
    right_reflectance: float,
    k_p: float = 0.45,
) -> float:
    """
    Proportional steering helper for following a dark tape line on a light floor.

    Convention (CCW+):
    - Positive return value turns LEFT (counter-clockwise).
    - Negative return value turns RIGHT (clockwise).
    """
    error = left_reflectance - right_reflectance
    turn = k_p * error
    # Clamp steering effort to [-0.4, +0.4] so the XRP stays smooth and controllable
    return max(-0.4, min(0.4, turn))
```

---

## Part 3: Testing in the Built-In XRP Simulator

1. Open the **Dashboard** tab in **ARES Robotics Studio**.
2. Use the **XRP Simulator Control Bar** at the top of the Dashboard to start a simulated XRP session.
3. Watch your ultrasonic distance and reflectance signals in the **Telemetry Line Chart** or inspect exact timestamped rows using the **Telemetry Data Table** widget (`+ Add Widget` $\rightarrow$ `Telemetry Data Table`)!
