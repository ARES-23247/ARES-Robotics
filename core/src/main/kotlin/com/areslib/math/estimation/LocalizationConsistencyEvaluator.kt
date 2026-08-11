package com.areslib.math.estimation

import com.areslib.math.geometry.Matrix3x3
import com.areslib.math.wrapAngle

/**
 * Allocation-free NIS/NEES accumulator for localization calibration runs.
 *
 * NIS checks whether vision residuals agree with their claimed covariance. NEES
 * additionally requires ground-truth pose and checks the estimator covariance itself.
 * Snapshot creation is intentionally outside the robot hot path.
 */
class LocalizationConsistencyEvaluator {
    private var nisCount = 0L
    private var nisSum = 0.0
    private var nisWithin95 = 0L
    private var neesCount = 0L
    private var neesSum = 0.0
    private var neesWithin95 = 0L

    fun recordNis(normalizedInnovationSquared: Double) {
        if (!normalizedInnovationSquared.isFinite() || normalizedInnovationSquared < 0.0) return
        nisCount++
        nisSum += normalizedInnovationSquared
        if (normalizedInnovationSquared <= CHI_SQUARE_3_DOF_95) nisWithin95++
    }

    fun recordNees(
        estimateX: Double,
        estimateY: Double,
        estimateHeading: Double,
        truthX: Double,
        truthY: Double,
        truthHeading: Double,
        covariance: Matrix3x3
    ): Boolean {
        val e0 = estimateX - truthX
        val e1 = estimateY - truthY
        val e2 = wrapAngle(estimateHeading - truthHeading)
        if (!e0.isFinite() || !e1.isFinite() || !e2.isFinite()) return false

        val l00Squared = covariance.m00
        if (!l00Squared.isFinite() || l00Squared <= 1e-18) return false
        val l00 = kotlin.math.sqrt(l00Squared)
        val l10 = covariance.m10 / l00
        val l20 = covariance.m20 / l00
        val l11Squared = covariance.m11 - l10 * l10
        if (!l11Squared.isFinite() || l11Squared <= 1e-18) return false
        val l11 = kotlin.math.sqrt(l11Squared)
        val l21 = (covariance.m21 - l20 * l10) / l11
        val l22Squared = covariance.m22 - l20 * l20 - l21 * l21
        if (!l22Squared.isFinite() || l22Squared <= 1e-18) return false
        val l22 = kotlin.math.sqrt(l22Squared)

        // Solve L y = e, then NEES = y^T y. This avoids constructing P^-1.
        val y0 = e0 / l00
        val y1 = (e1 - l10 * y0) / l11
        val y2 = (e2 - l20 * y0 - l21 * y1) / l22
        val nees = y0 * y0 + y1 * y1 + y2 * y2
        if (!nees.isFinite() || nees < 0.0) return false
        neesCount++
        neesSum += nees
        if (nees <= CHI_SQUARE_3_DOF_95) neesWithin95++
        return true
    }

    fun reset() {
        nisCount = 0L
        nisSum = 0.0
        nisWithin95 = 0L
        neesCount = 0L
        neesSum = 0.0
        neesWithin95 = 0L
    }

    fun snapshot(): LocalizationConsistencySnapshot = LocalizationConsistencySnapshot(
        nisCount = nisCount,
        meanNis = if (nisCount == 0L) Double.NaN else nisSum / nisCount,
        nisCoverage95 = if (nisCount == 0L) Double.NaN else nisWithin95.toDouble() / nisCount,
        neesCount = neesCount,
        meanNees = if (neesCount == 0L) Double.NaN else neesSum / neesCount,
        neesCoverage95 = if (neesCount == 0L) Double.NaN else neesWithin95.toDouble() / neesCount
    )

    companion object {
        const val CHI_SQUARE_3_DOF_95 = 7.814727903251179
    }
}

data class LocalizationConsistencySnapshot(
    val nisCount: Long,
    val meanNis: Double,
    val nisCoverage95: Double,
    val neesCount: Long,
    val meanNees: Double,
    val neesCoverage95: Double
)
