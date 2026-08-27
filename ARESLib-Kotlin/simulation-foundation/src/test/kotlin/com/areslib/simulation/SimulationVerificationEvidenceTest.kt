package com.areslib.simulation

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SimulationVerificationEvidenceTest {
    @Test
    fun `simulation cannot issue physical-validation evidence`() {
        assertFailsWith<IllegalArgumentException> {
            SimulationVerificationEvidence(
                verificationRunId = "run-1",
                checkId = "motor-direction",
                level = VerificationEvidenceLevel.PHYSICALLY_VALIDATED,
                passed = true,
                timestampNanos = 0L,
                message = "Direction looked correct in simulation",
                projectContentSha256 = "a".repeat(64),
            )
        }
    }
}
