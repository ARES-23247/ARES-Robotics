package com.areslib.math.estimation

import com.areslib.math.geometry.Matrix3x3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalizationConsistencyEvaluatorTest {
    @Test
    fun `accumulates NIS and NEES without inverting covariance`() {
        val evaluator = LocalizationConsistencyEvaluator()
        evaluator.recordNis(3.0)
        evaluator.recordNis(12.0)
        val identity = Matrix3x3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        assertTrue(evaluator.recordNees(1.0, 1.0, 1.0, 0.0, 0.0, 0.0, identity))
        assertFalse(evaluator.recordNees(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, Matrix3x3(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

        val result = evaluator.snapshot()
        assertEquals(2L, result.nisCount)
        assertEquals(7.5, result.meanNis, 1e-12)
        assertEquals(2.5, result.meanNormalizedNis, 1e-12)
        assertEquals(0.5, result.nisCoverage95, 1e-12)
        assertEquals(1L, result.neesCount)
        assertEquals(3.0, result.meanNees, 1e-12)
        assertEquals(1.0, result.neesCoverage95, 1e-12)
    }

    @Test
    fun `translation-only NIS uses the two degree-of-freedom bound`() {
        val evaluator = LocalizationConsistencyEvaluator()

        evaluator.recordNis(6.5, degreesOfFreedom = 2)

        val result = evaluator.snapshot()
        assertEquals(0.0, result.nisCoverage95, 0.0)
        assertEquals(3.25, result.meanNormalizedNis, 0.0)
    }
}
