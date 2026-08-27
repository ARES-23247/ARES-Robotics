package com.areslib.subsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GeneratedSubsystemRegistrySupportTest {
    @Test
    fun `required failure rolls back in reverse order clears list and preserves cleanup errors`() {
        val closeOrder = mutableListOf<String>()
        val installed = mutableListOf<Subsystem>()
        val first = RecordingSubsystem("first", closeOrder)
        val secondCleanupError = IllegalStateException("second close failed")
        val second = RecordingSubsystem("second", closeOrder, secondCleanupError)
        GeneratedSubsystemRegistrySupport.install(installed, "first", required = true) { first }
        GeneratedSubsystemRegistrySupport.install(installed, "second", required = true) { second }

        val factoryError = IllegalArgumentException("missing hardware")
        val failure = assertThrows<IllegalStateException> {
            GeneratedSubsystemRegistrySupport.install(installed, "required", required = true) {
                throw factoryError
            }
        }

        assertEquals(listOf("second", "first"), closeOrder)
        assertTrue(installed.isEmpty())
        assertSame(factoryError, failure.cause)
        assertEquals(listOf(secondCleanupError), failure.suppressed.toList())
    }

    @Test
    fun `optional failure keeps earlier subsystem installed and open`() {
        val closeOrder = mutableListOf<String>()
        val installed = mutableListOf<Subsystem>()
        val first = RecordingSubsystem("first", closeOrder)
        GeneratedSubsystemRegistrySupport.install(installed, "first", required = true) { first }

        GeneratedSubsystemRegistrySupport.install(installed, "optional", required = false) {
            error("not connected")
        }

        assertEquals(listOf(first), installed)
        assertTrue(closeOrder.isEmpty())
    }

    private class RecordingSubsystem(
        private val id: String,
        private val closeOrder: MutableList<String>,
        private val closeError: Exception? = null,
    ) : Subsystem {
        override fun readSensors(store: com.areslib.Store, timestampMs: Long) = Unit

        override fun writeOutputs(state: com.areslib.state.RobotState, scale: Double) = Unit

        override fun close() {
            closeOrder += id
            closeError?.let { throw it }
        }
    }
}
