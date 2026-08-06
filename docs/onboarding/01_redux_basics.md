# Module 1: Redux & Robot State Machine Basics

Welcome to ARESLib-Kotlin! In this module, you will learn the core principles of the Redux Architecture used by our robot software.

---

## 1. The 3 Core Pillars of Redux

1. **Single Source of Truth (`RobotState`)**: All robot data (drivetrain pose, velocities, tuning gains, superstructure positions) lives in one single immutable data class.
2. **State is Read-Only (`RobotAction`)**: You never mutate state variables directly. To change anything, you dispatch an immutable `RobotAction`.
3. **Changes are Made by Pure Reducers (`rootReducer`)**: Reducers are pure mathematical functions that calculate the new state from the old state and an incoming action:
   $$\text{State}_{\text{new}} = \text{rootReducer}(\text{State}_{\text{old}}, \text{Action})$$

---

## 2. Hands-On Exercise: Writing Your First Unit Test

Open `core/src/test/kotlin/com/areslib/student/StudentOnboardingTest.kt` and run your first unit test:

```kotlin
package com.areslib.student

import com.areslib.action.RobotAction
import com.areslib.reducer.rootReducer
import com.areslib.state.DriveMode
import com.areslib.state.RobotState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StudentOnboardingTest {

    @Test
    fun testHeadingLockStateTransition() {
        val initialState = RobotState()
        
        // Dispatch an action to lock heading at 90 degrees (Math.PI / 2)
        val newState = rootReducer(initialState, RobotAction.SetHeadingLockTarget(Math.PI / 2.0))
        
        // Assert state transitions
        assertEquals(DriveMode.HEADING_HOLD, newState.drive.driveMode)
        assertEquals(Math.PI / 2.0, newState.drive.headingLockTargetRadians)
    }
}
```

Run tests from your terminal:
```powershell
.\gradlew.bat test
```
