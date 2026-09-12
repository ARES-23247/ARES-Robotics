package com.ares.analytics.service.hardware

import com.ares.analytics.shared.models.TelemetryFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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

    private val healthySignals = mapOf(
        "TelemetryHeartbeat" to 1.0,
        "ConfigurationHealthy" to 1.0,
        "FeedbackValid" to 1.0,
        "Homed" to 1.0,
        "Calibrated" to 1.0,
        "CurrentReadingValid" to 1.0,
        "HomingFaultLatched" to 0.0,
        "OutputFaultLatched" to 0.0,
    )

    private fun readyAccumulator(receiptNs: Long = 0L): SubsystemHealthAccumulator =
        SubsystemHealthAccumulator(staleAfterMs = 100L).also { accumulator ->
            healthySignals.forEach { (signal, value) ->
                accumulator.accept(frame("Subsystems/arm/$signal", value), receiptNs)
            }
            assertEquals(SubsystemHealthStatus.HEALTHY, accumulator.snapshots(receiptNs).single().status)
        }

    @Test
    fun `invalid health values replace prior readiness with incomplete evidence`() {
        for (signal in healthySignals.keys) {
            val invalid = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY) +
                if (signal == "TelemetryHeartbeat") listOf(-1.0) else listOf(-1.0, 0.25, 1.5)
            for (value in invalid) {
                val accumulator = readyAccumulator()
                accumulator.accept(frame("Subsystems/arm/$signal", value), 1L)
                val snapshot = accumulator.snapshots(1L).single()
                assertEquals(SubsystemHealthStatus.INCOMPLETE, snapshot.status, "$signal=$value")
                assertTrue(snapshot.issues.any { signal in it })
                accumulator.accept(frame("Subsystems/arm/$signal", healthySignals.getValue(signal)), 2L)
                assertEquals(SubsystemHealthStatus.HEALTHY, accumulator.snapshots(2L).single().status)
            }
        }
    }

    @Test
    fun `text placeholders cannot satisfy required numeric health signals`() {
        for ((signal, value) in healthySignals) {
            val accumulator = readyAccumulator()
            accumulator.accept(frame("Subsystems/arm/$signal", value).copy(stringValue = value.toString()), 1L)
            assertEquals(SubsystemHealthStatus.INCOMPLETE, accumulator.snapshots(1L).single().status, signal)
        }
    }

    @Test
    fun `measurement traffic cannot refresh an expired subsystem heartbeat`() {
        val accumulator = readyAccumulator()
        accumulator.accept(frame("Subsystems/arm/position", 0.42), 101_000_000L)
        val snapshot = accumulator.snapshots(101_000_000L).single()
        assertEquals(SubsystemHealthStatus.STALE, snapshot.status)
        assertEquals(101L, snapshot.ageMs)
        accumulator.accept(frame("Subsystems/arm/TelemetryHeartbeat", 2.0), 102_000_000L)
        assertEquals(SubsystemHealthStatus.HEALTHY, accumulator.snapshots(102_000_000L).single().status)
    }

    @Test
    fun `backward receipt clock cannot make a subsystem look fresh`() {
        val accumulator = readyAccumulator(receiptNs = 1_000_000L)
        assertEquals(SubsystemHealthStatus.STALE, accumulator.snapshots(999_999L).single().status)
    }

    @Test
    fun `stale interval must be positive`() {
        for (interval in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { SubsystemHealthAccumulator(interval) }
        }
    }

    @Test
    fun `topic normalization removes all transport slashes and rejects blank identities`() {
        val accumulator = SubsystemHealthAccumulator()
        assertTrue(accumulator.accept(frame("///Subsystems/arm/FeedbackValid", 1.0), 0L))
        assertFalse(accumulator.accept(frame("Subsystems/ /FeedbackValid", 1.0), 0L))
        assertFalse(accumulator.accept(frame("Subsystems/arm/ ", 1.0), 0L))
        assertEquals(listOf("arm"), accumulator.snapshots(0L).map { it.subsystemId })
    }

    @Test
    fun `target changes clear every field before accepting the new subsystem heartbeat`() {
        val accumulator = readyAccumulator()
        assertTrue(accumulator.snapshots(1L, targetEpoch = 1L).isEmpty())
        accumulator.accept(frame("Subsystems/arm/TelemetryHeartbeat", 2.0), 2L, targetEpoch = 1L)
        assertEquals(SubsystemHealthStatus.INCOMPLETE, accumulator.snapshots(2L, targetEpoch = 1L).single().status)
        accumulator.accept(frame("Subsystems/arm/position", 0.5), 3L, targetEpoch = 2L)
        val snapshot = accumulator.snapshots(3L, targetEpoch = 2L).single()
        assertEquals(SubsystemHealthStatus.INCOMPLETE, snapshot.status)
        assertTrue(snapshot.issues.any { "TelemetryHeartbeat" in it })
    }

    @Test
    fun `monotonic clock wrapping retains the correct elapsed time`() {
        val start = Long.MAX_VALUE - 50_000_000L
        val accumulator = readyAccumulator(start)
        val snapshot = accumulator.snapshots(start + 75_000_000L).single()
        assertEquals(75L, snapshot.ageMs)
        assertEquals(SubsystemHealthStatus.HEALTHY, snapshot.status)
    }
}
