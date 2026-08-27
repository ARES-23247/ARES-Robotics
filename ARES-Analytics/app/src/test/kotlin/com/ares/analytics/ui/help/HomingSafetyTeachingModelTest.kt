package com.ares.analytics.ui.help

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomingSafetyTeachingModelTest {
    @Test
    fun `sensor evidence must remain fresh for the full dwell`() {
        val ready = HomingSafetyTeachingInput(sensorActive = true, requiredDwellMs = 100L)

        val partial = advanceHomingEvidence(ready, 50L)
        assertFalse(partial.homed)

        val stale = advanceHomingEvidence(partial.copy(feedbackAgeMs = 101L), 50L)
        assertFalse(stale.homed)
        assertTrue(stale.evidenceDwellMs == 0L)
        assertFalse(evaluateHomingSafetyTeaching(stale).motionPermitted)

        val homed = advanceHomingEvidence(stale.copy(feedbackAgeMs = 20L), 100L)
        assertTrue(homed.homed)
        assertTrue(evaluateHomingSafetyTeaching(homed).motionPermitted)
    }

    @Test
    fun `current stall rejects an invalid current reading`() {
        val invalid = HomingSafetyTeachingInput(
            method = TeachingHomingMethod.CURRENT_STALL,
            currentValid = false,
            currentAmps = 6.0,
        )

        val result = evaluateHomingSafetyTeaching(invalid)
        assertFalse(result.requiredMeasurementsValid)
        assertFalse(result.homingEvidencePresent)
        assertTrue(result.reasons.any { "measurement" in it.lowercase() })
    }

    @Test
    fun `sensor homing ignores thresholds that do not belong to its method`() {
        val sensor = HomingSafetyTeachingInput(
            method = TeachingHomingMethod.SENSOR,
            sensorActive = true,
            currentThresholdAmps = Double.NaN,
            velocityThresholdRps = Double.NaN,
        )

        val result = evaluateHomingSafetyTeaching(sensor)
        assertTrue(result.requiredMeasurementsValid)
        assertTrue(result.homingEvidencePresent)
    }

    @Test
    fun `combined stall requires current and velocity evidence`() {
        val currentOnly = HomingSafetyTeachingInput(
            method = TeachingHomingMethod.COMBINED_STALL,
            currentAmps = 5.0,
            velocityRps = 1.0,
        )
        assertFalse(evaluateHomingSafetyTeaching(currentOnly).homingEvidencePresent)

        val both = currentOnly.copy(velocityRps = 0.1)
        assertTrue(evaluateHomingSafetyTeaching(both).homingEvidencePresent)
    }

    @Test
    fun `latched output fault clears only after successful neutral`() {
        val faulted = HomingSafetyTeachingInput(
            sensorActive = true,
            homed = true,
            faultLatched = true,
        )

        val failedRecovery = attemptTeachingNeutralRecovery(faulted, neutralWriteSucceeded = false)
        assertTrue(failedRecovery.faultLatched)
        assertFalse(evaluateHomingSafetyTeaching(failedRecovery).motionPermitted)

        val recovered = attemptTeachingNeutralRecovery(failedRecovery, neutralWriteSucceeded = true)
        assertFalse(recovered.faultLatched)
        assertTrue(recovered.homed)
        assertTrue(evaluateHomingSafetyTeaching(recovered).motionPermitted)
    }
}
