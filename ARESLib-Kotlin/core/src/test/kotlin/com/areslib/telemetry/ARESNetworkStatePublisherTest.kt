package com.areslib.telemetry

import com.areslib.state.Pose3dSnapshot
import com.areslib.state.RobotState
import com.areslib.state.VisionMeasurementSnapshot
import com.areslib.state.VisionState
import com.areslib.util.RobotClock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ARESNetworkStatePublisherTest {
    @Test
    fun `each published state advances the telemetry heartbeat`() {
        val telemetry = RecordingTelemetry()
        val publisher = ARESNetworkStatePublisher(telemetry)
        val state = RobotState()

        publisher.publish(state, flush = false)
        val first = telemetry.numbers.getValue(TelemetryTopicConstants.TELEMETRY_FRAME_SEQUENCE)
        publisher.publish(state, flush = false)
        val second = telemetry.numbers.getValue(TelemetryTopicConstants.TELEMETRY_FRAME_SEQUENCE)

        assertEquals(0.0, first)
        assertEquals(1.0, second)
    }
    @AfterTest
    fun restoreClock() {
        RobotClock.useSystemTime()
    }

    @Test
    fun `vision telemetry publishes newest retained measurement`() {
        RobotClock.useMockTime(10_000L)
        val telemetry = RecordingTelemetry()
        val state = RobotState(
            vision = VisionState(
                hasTarget = true,
                measurements = listOf(
                    measurement(timestampMs = 9_020L, x = -1.0, frameId = 1L),
                    measurement(timestampMs = 9_980L, x = 2.5, frameId = 2L),
                ),
            ),
        )

        ARESNetworkStatePublisher(telemetry).publish(state, flush = false)

        assertTrue(telemetry.booleans.getValue("Vision/HasTarget"))
        assertEquals(2.5, telemetry.numbers.getValue("Vision/Pose_X"))
        assertEquals(2.0, telemetry.numbers.getValue("Vision/Primary_FrameId"))
        assertContentEquals(doubleArrayOf(2.5, -0.25, 0.5), telemetry.arrays.getValue("Vision/PoseArray"))
    }

    @Test
    fun `vision telemetry clears a retained measurement after it becomes stale`() {
        RobotClock.useMockTime(10_000L)
        val telemetry = RecordingTelemetry()
        val state = RobotState(
            vision = VisionState(
                hasTarget = true,
                measurements = listOf(measurement(timestampMs = 9_499L, x = 2.5, frameId = 2L)),
            ),
        )

        ARESNetworkStatePublisher(telemetry).publish(state, flush = false)

        assertFalse(telemetry.booleans.getValue("Vision/HasTarget"))
        assertEquals(0.0, telemetry.numbers.getValue("Vision/Pose_X"))
        assertEquals(0.0, telemetry.numbers.getValue("Vision/Primary_FrameId"))
        assertContentEquals(doubleArrayOf(), telemetry.arrays.getValue("Vision/PoseArray"))
    }

    private fun measurement(timestampMs: Long, x: Double, frameId: Long) = VisionMeasurementSnapshot(
        timestampMs = timestampMs,
        targetPose = Pose3dSnapshot(x = x, y = -0.25, quaternionW = kotlin.math.cos(0.25), quaternionZ = kotlin.math.sin(0.25)),
        frameId = frameId,
    )

    private class RecordingTelemetry : ITelemetry {
        val numbers = mutableMapOf<String, Double>()
        val booleans = mutableMapOf<String, Boolean>()
        val strings = mutableMapOf<String, String>()
        val arrays = mutableMapOf<String, DoubleArray>()

        override fun putNumber(key: String, value: Double) { numbers[key] = value }
        override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun putDoubleArray(key: String, value: DoubleArray) { arrays[key] = value.copyOf() }
        override fun getNumber(key: String, defaultValue: Double): Double = numbers[key] ?: defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = booleans[key] ?: defaultValue
        override fun getString(key: String, defaultValue: String): String = strings[key] ?: defaultValue
    }
}
