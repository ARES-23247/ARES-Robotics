package com.areslib.sim

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationRotationControlTest {
    @Test
    fun inverseAxisReconstructsDesiredPostCurveEffort() {
        val desiredEfforts = doubleArrayOf(-1.0, -0.22, -0.01, 0.01, 0.22, 1.0)

        for (desired in desiredEfforts) {
            val axis = verificationDriverAxis(desired)
            val deadzoned = (abs(axis) - 0.05) / 0.95 * sign(axis)
            val shaped = sign(deadzoned) * abs(deadzoned).pow(3.0)
            assertEquals(desired, shaped, 1e-12)
        }
    }

    @Test
    fun inverseAxisFailsNeutralForInvalidEffortAndRejectsInvalidConfiguration() {
        assertEquals(0.0, verificationDriverAxis(Double.NaN), 0.0)
        assertEquals(0.0, verificationDriverAxis(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0.0, verificationDriverAxis(0.0), 0.0)
        assertTrue(runCatching { verificationDriverAxis(0.1, deadband = 1.0) }.isFailure)
        assertTrue(runCatching { verificationDriverAxis(0.1, exponent = 0.0) }.isFailure)
    }
}
