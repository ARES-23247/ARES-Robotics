package com.areslib.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimulationDeviceContractTest {
    @Test
    fun `cached observation distinguishes validity freshness visibility and configuration`() {
        val observation = SimulationDeviceObservation().apply {
            value = 3.5
            sampleTimestampNanos = 20_000_000L
            sequence = 7L
            health = SimulationDeviceHealth.HEALTHY
            configurationHealthy = true
            homed = true
            targetVisible = false
        }

        assertTrue(observation.valid)
        assertEquals(10_000_000L, observation.ageNanos(30_000_000L))
        assertFalse(observation.targetVisible, "Healthy camera with no target is not disconnected")

        observation.health = SimulationDeviceHealth.STALE
        assertFalse(observation.valid)
    }

    @Test
    fun `unknown or rewound sample age fails closed`() {
        val observation = SimulationDeviceObservation()
        assertEquals(Long.MAX_VALUE, observation.ageNanos(0L))
        observation.sampleTimestampNanos = 20L
        assertEquals(Long.MAX_VALUE, observation.ageNanos(10L))
    }
}
