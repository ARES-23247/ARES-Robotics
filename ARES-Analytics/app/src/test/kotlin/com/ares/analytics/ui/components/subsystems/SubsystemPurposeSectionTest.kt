package com.ares.analytics.ui.components.subsystems

import kotlin.test.Test
import kotlin.test.assertEquals

class SubsystemPurposeSectionTest {
    @Test
    fun `student-facing mechanism names derive safe Kotlin names`() {
        assertEquals("Arm", subsystemKotlinTypeName("arm"))
        assertEquals("PracticeLift", subsystemKotlinTypeName("Practice lift"))
        assertEquals("RearIntake", subsystemKotlinTypeName("rear-intake"))
        assertEquals("Mechanism2JointArm", subsystemKotlinTypeName("2 joint arm"))
        assertEquals("NewSubsystem", subsystemKotlinTypeName("---"))
    }
}
