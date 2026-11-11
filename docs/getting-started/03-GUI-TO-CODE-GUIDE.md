# 3. The GUI ↔ Code Bridge (Where Do I Write My Code?)

One of the most common questions students ask when using a visual builder is:
> *"When I click **Save & create files** in the GUI, what files get created, and which files am I allowed to edit in Android Studio / VS Code / IntelliJ?"*

You can click the **GUI ↔ Code Bridge** button at the top of the Subsystem Builder at any time to see the exact file paths for your mechanism. Here is how the 3-part file boundary works.

---

## The 3-Part File Ownership Boundary

```text
Your Robot Project
├── .ares/
│   └── subsystems/
│       └── intake.aressubsystem          <-- 1. VISUAL DESCRIPTOR (Saved by Studio GUI)
├── build/
│   └── generated/ares/                   <-- 2. AUTO-GENERATED PLUMBING (Read-Only! Never edit)
│       ├── IntakeState.kt
│       ├── IntakeAction.kt
│       ├── IntakeController.kt
│       └── GeneratedRobotRegistry.kt
└── src/main/kotlin/.../subsystems/
    └── IntakeSubsystemExtensions.kt      <-- 3. YOUR TEAM'S CUSTOM CODE (Safe to edit!)
```

### 1. Visual Descriptor (`.ares/subsystems/<id>.aressubsystem`)
- **Who owns it:** ARES Robotics Studio GUI (and Git).
- **What's inside:** Your motors, port numbers, state fields, PID rules, and safety limits.
- **Should you commit it to Git?** **Yes!** Always commit `.ares/` files so your teammates see the exact same mechanisms in Studio.

### 2. Auto-Generated Plumbing (`build/generated/ares/...`)
- **Who owns it:** The ARES project compiler.
- **What's inside:** The mechanical Redux state classes, action types, hardware IO adapters, and safety interlocks.
- **Rule:** **Never edit files inside `build/generated/` by hand.** Every time you click **Save & create files** or run Gradle, ARES regenerates these files from your `.ares` descriptors.

### 3. Your Team's Custom Extension Files (`src/main/kotlin/...` or `src/subsystems/...`)
- **Who owns it:** **You and your team (`USER-OWNED`)!**
- **What's inside:** Custom helper functions, game-piece detection logic, multi-subsystem coordination, or custom state queries.
- **Rule:** ARES **never** overwrites `USER-OWNED` files. Even if a file starts as a `GENERATED STARTER`, if you edit it, Studio detects your changes and shows a side-by-side diff confirmation before touching anything.

---

## Example: Writing a Safe Student Helper in Kotlin (FTC / FRC)

Suppose you created an `Arm` subsystem in the GUI. ARES generates `ArmSubsystemState` in `build/generated/ares/`. You can create `ArmSubsystemExtensions.kt` in your `src/main/kotlin/` folder to add custom team helpers:

```kotlin
// Safe Student Extension File: ArmSubsystemExtensions.kt
// ARES never overwrites this file when you update your subsystem in the GUI!

package org.aresfirst.team.subsystems

import kotlin.math.abs

/**
 * Returns true when the arm is enabled, not faulted, and within 2 degrees of its target angle.
 */
fun ArmSubsystemState.isAtScoringAngle(toleranceDeg: Double = 2.0): Boolean {
    if (!enabled || faulted) return false
    return abs(positionDeg - targetPositionDeg) <= toleranceDeg
}
```

---

## Example: Writing a Safe Student Helper in MicroPython (XRP)

On an XRP robot, ARES compiles your `.ares` files into `build/generated/ares/python/`. Your team can write custom Python helpers in `src/subsystems/`:

```python
# Safe Student Extension File: src/subsystems/range_extensions.py
# ARES never overwrites this file when you click 'Save & create files' in the GUI.

def should_stop_for_wall(robot_state, stop_distance_cm=15.0):
    """Returns True when the ultrasonic sensor reads closer than stop_distance_cm."""
    distance = robot_state.get("range_distance_cm", 999.0)
    return distance <= stop_distance_cm
```

---

## Next Step

Previously used the standard FTC SDK (`OpMode`) or WPILib (`SubsystemBase`)? Read **[4. Coming from the FTC SDK or WPILib](04-COMING-FROM-FTCSDK-OR-WPILIB.md)**.
