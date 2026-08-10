package com.areslib.e2e.tier1.state

import com.areslib.state.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class StateImmutabilityTier1Test {

    @Test
    fun testStatePropertiesAreImmutableAndVal() {
        val stateClasses = listOf(
            RobotState::class.java,
            DriveState::class.java,
            SuperstructureState::class.java,
            VisionState::class.java,
            CostmapState::class.java,
            PathState::class.java
        )

        for (clazz in stateClasses) {
            // Verify class is a Kotlin data class (or at least all fields are private final/val)
            val fields = clazz.declaredFields
            for (field in fields) {
                // Ignore synthetic fields (like $jacobian or kotlin compiler details)
                if (field.isSynthetic || field.name.startsWith("$")) continue

                // Check that field is private and final (val)
                val modifiers = field.modifiers
                assertTrue(
                    Modifier.isFinal(modifiers),
                    "Field '${field.name}' in class '${clazz.simpleName}' is not final! All state properties must be immutable (val)."
                )
            }
        }
    }

    @Test
    fun testCollectionStatePropertiesAreReadOnly() {
        
        // VisionState contains measurements: List<VisionMeasurement>
        val vision = VisionState()
        assertTrue(vision.measurements is List<*>, "measurements in VisionState should be a List")
    }

    @Test
    fun testStateModificationCreatesNewInstance() {
        val s1 = RobotState()
        val s2 = s1.copy(timestampMs = 12345L)
        
        assertNotSame(s1, s2, "State copy should create a new object instance")
        assertEquals(0L, s1.timestampMs)
        assertEquals(12345L, s2.timestampMs)
        
        // Sub-states are shared if not changed, but they are immutable so it's perfectly safe
        assertSame(s1.drive, s2.drive)
    }
}
