package com.ares.analytics.service.hardware

import com.ares.analytics.shared.models.TelemetryFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubsystemHealthTelemetryTest {
    private fun frame(key: String, value: Double) = TelemetryFrame(
        timestampMs = 1L,
        sessionId = "test",
        key = key,
        value = value,
    )

    @Test
    fun `discovers generated namespaces and preserves measurements`() {
        val accumulator = SubsystemHealthAccumulator()
        assertFalse(accumulator.accept(frame("Drive/FeedbackValid", 1.0), 10L))
        assertTrue(accumulator.accept(frame("/Subsystems/arm/ConfigurationHealthy", 1.0), 10L))
        accumulator.accept(frame("Subsystems/arm/TelemetryHeartbeat", 1.0), 10L)
        accumulator.accept(frame("Subsystems/arm/FeedbackValid", 1.0), 10L)
        accumulator.accept(frame("Subsystems/arm/Homed", 1.0), 10L)
        accumulator.accept(frame("Subsystems/arm/Calibrated", 1.0), 10L)
        accumulator.accept(frame("Subsystems/arm/CurrentReadingValid", 1.0), 10L)
        accumulator.accept(frame("Subsystems/arm/HomingFaultLatched", 0.0), 10L)
        accumulator.accept(frame("Subsystems/arm/OutputFaultLatched", 0.0), 10L)
        accumulator.accept(frame("Subsystems/arm/position", 0.42), 10L)

        val snapshot = accumulator.snapshots(10L).single()
        assertEquals(SubsystemHealthStatus.HEALTHY, snapshot.status)
        assertEquals(0.42, snapshot.measurements["position"])
    }

    @Test
    fun `partial or nested telemetry never claims a subsystem is ready`() {
        val accumulator = SubsystemHealthAccumulator()
        assertFalse(accumulator.accept(frame("Subsystems/arm/controller/FeedbackValid", 1.0), 10L))
        accumulator.accept(frame("Subsystems/arm/FeedbackValid", 1.0), 10L)

        val snapshot = accumulator.snapshots(10L).single()
        assertEquals(SubsystemHealthStatus.INCOMPLETE, snapshot.status)
        assertTrue(snapshot.issues.single().contains("Waiting for generated health signals"))
    }

    @Test
    fun `latched output fault outranks other live health problems`() {
        val accumulator = SubsystemHealthAccumulator()
        accumulator.accept(frame("Subsystems/intake/OutputFaultLatched", 1.0), 5_000_000L)
        accumulator.accept(frame("Subsystems/intake/ConfigurationHealthy", 0.0), 5_000_000L)

        val snapshot = accumulator.snapshots(5_000_000L).single()
        assertEquals(SubsystemHealthStatus.OUTPUT_FAULT, snapshot.status)
        assertTrue(snapshot.issues.any { it.contains("output write", ignoreCase = true) })
        assertTrue(snapshot.issues.any { it.contains("device names", ignoreCase = true) })
    }

    @Test
    fun `staleness uses desktop receipt time rather than robot timestamp`() {
        val accumulator = SubsystemHealthAccumulator(staleAfterMs = 100L)
        accumulator.accept(frame("Subsystems/flywheel/FeedbackValid", 1.0), 1_000_000L)

        assertEquals(
            SubsystemHealthStatus.STALE,
            accumulator.snapshots(102_000_000L).single().status,
        )
    }

    @Test
    fun `heartbeat keeps unchanged health live without appearing as a measurement`() {
        val accumulator = SubsystemHealthAccumulator(staleAfterMs = 100L)
        accumulator.accept(frame("Subsystems/claw/TelemetryHeartbeat", 1.0), 1_000_000L)
        accumulator.accept(frame("Subsystems/claw/FeedbackValid", 1.0), 1_000_000L)
        accumulator.accept(frame("Subsystems/claw/ConfigurationHealthy", 1.0), 1_000_000L)
        accumulator.accept(frame("Subsystems/claw/Homed", 1.0), 1_000_000L)
        accumulator.accept(frame("Subsystems/claw/Calibrated", 1.0), 1_000_000L)
        accumulator.accept(frame("Subsystems/claw/CurrentReadingValid", 1.0), 1_000_000L)
        accumulator.accept(frame("Subsystems/claw/HomingFaultLatched", 0.0), 1_000_000L)
        accumulator.accept(frame("Subsystems/claw/OutputFaultLatched", 0.0), 1_000_000L)
        accumulator.accept(frame("Subsystems/claw/TelemetryHeartbeat", 2.0), 99_000_000L)

        val snapshot = accumulator.snapshots(150_000_000L).single()

        assertEquals(SubsystemHealthStatus.HEALTHY, snapshot.status)
        assertFalse(snapshot.measurements.containsKey("TelemetryHeartbeat"))
    }
}
