package com.ares.analytics.ui.help

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RobotSignalFlowTeachingModelTest {
    @Test
    fun `motor loop shapes input and preserves the retained snapshot`() {
        val previous = RobotSignalTeachingSnapshot(eventSequence = 4L, requestedValue = 0.2)
        val result = runRobotSignalTeachingLoop(
            previous,
            RobotSignalTeachingInput(
                path = TeachingSignalPath.MOTOR,
                rawInput = 0.5,
                deadband = 0.1,
                inverted = true,
                cachedMeasurement = 2.5,
            ),
        )

        assertEquals(4L, result.previousState.eventSequence)
        assertEquals(0.2, result.previousState.requestedValue)
        assertEquals(5L, result.reducerState.eventSequence)
        assertEquals(-4.0 / 9.0, result.reducerState.requestedValue, 1e-9)
        assertEquals(result.reducerState.requestedValue, result.controllerOutput)
        assertEquals(2.5, result.telemetry.value)
        assertEquals("rot/s", result.telemetry.unit)
        assertTrue(result.telemetry.valid)
    }

    @Test
    fun `stale motor feedback requests neutral and is not valid telemetry`() {
        val result = runRobotSignalTeachingLoop(
            RobotSignalTeachingSnapshot(),
            RobotSignalTeachingInput(
                path = TeachingSignalPath.MOTOR,
                rawInput = 0.8,
                measurementAgeMs = 101L,
                feedbackTimeoutMs = 100L,
            ),
        )

        assertEquals(0.0, result.controllerOutput)
        assertTrue("stale" in result.controllerDecision.lowercase())
        assertFalse(result.telemetry.valid)
        assertNull(result.telemetry.value)
    }

    @Test
    fun `positional servo maps inversion and latches a failed mock write`() {
        val result = runRobotSignalTeachingLoop(
            RobotSignalTeachingSnapshot(),
            RobotSignalTeachingInput(
                path = TeachingSignalPath.POSITIONAL_SERVO,
                rawInput = 0.5,
                deadband = 0.0,
                inverted = true,
                outputWriteSucceeds = false,
            ),
        )

        assertEquals(0.25, result.reducerState.requestedValue, 1e-9)
        assertEquals(0.25, result.controllerOutput)
        assertTrue(result.finalState.outputFaultLatched)
        assertFalse(result.telemetry.valid)
        assertNull(result.telemetry.value)
    }

    @Test
    fun `sensor path refreshes cached state without an actuator write`() {
        val result = runRobotSignalTeachingLoop(
            RobotSignalTeachingSnapshot(),
            RobotSignalTeachingInput(
                path = TeachingSignalPath.DISTANCE_SENSOR,
                rawInput = 42.0,
                measurementValid = true,
                measurementAgeMs = 10L,
            ),
        )

        assertNull(result.controllerOutput)
        assertEquals(42.0, result.reducerState.cachedMeasurement)
        assertEquals(42.0, result.telemetry.value)
        assertEquals("cm", result.telemetry.unit)
        assertTrue("No actuator write" in result.ioResult)
    }

    @Test
    fun `existing output fault keeps later actuator commands neutral`() {
        val result = runRobotSignalTeachingLoop(
            RobotSignalTeachingSnapshot(outputFaultLatched = true),
            RobotSignalTeachingInput(path = TeachingSignalPath.MOTOR, rawInput = 1.0),
        )

        assertEquals(0.0, result.controllerOutput)
        assertTrue("already latched" in result.controllerDecision)
        assertTrue(result.finalState.outputFaultLatched)
    }
}
