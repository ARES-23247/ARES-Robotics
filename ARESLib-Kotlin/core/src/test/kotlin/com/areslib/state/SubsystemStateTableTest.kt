package com.areslib.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubsystemStateTableTest {
    private data class DummySubsystemState(val position: Double) : SubsystemState

    @Test
    fun `preserves map equality, insertion order, and immutability across updates`() {
        val flywheelA = DummySubsystemState(10.0)
        val intakeA = DummySubsystemState(1.0)
        val flywheelB = DummySubsystemState(20.0)

        val first = SubsystemStateTable.withUpdated(emptyMap(), "flywheel", flywheelA)
        val second = SubsystemStateTable.withUpdated(first, "intake", intakeA)
        val unchanged = SubsystemStateTable.withUpdated(second, "flywheel", flywheelA)
        val third = SubsystemStateTable.withUpdated(second, "flywheel", flywheelB)

        assertSame(second, unchanged)
        assertEquals(mapOf("flywheel" to flywheelA, "intake" to intakeA), second)
        assertEquals(mapOf("flywheel" to flywheelB, "intake" to intakeA), third)
        assertEquals(listOf("flywheel", "intake"), third.keys.toList())
        assertTrue(third.containsKey(String("flywheel".toCharArray())))
        assertTrue(third.containsValue(intakeA))
        assertFalse(third.containsKey("missing"))
        assertNull(third["missing"])
        // Earlier snapshots remain strictly immutable.
        assertEquals(flywheelA, second["flywheel"])
    }

    @Test
    fun `upgrades arbitrary initial map without losing keys or order`() {
        val initial: Map<String, SubsystemState> = linkedMapOf(
            "arm" to DummySubsystemState(0.5),
            "wrist" to DummySubsystemState(0.25),
        )
        val updatedWrist = DummySubsystemState(0.75)
        val table = SubsystemStateTable.withUpdated(initial, "wrist", updatedWrist)

        assertEquals(listOf("arm", "wrist"), table.keys.toList())
        assertEquals(updatedWrist, table["wrist"])
        assertEquals(DummySubsystemState(0.5), table["arm"])
    }
}
