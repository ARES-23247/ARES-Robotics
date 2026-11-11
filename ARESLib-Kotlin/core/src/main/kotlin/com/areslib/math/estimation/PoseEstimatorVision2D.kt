package com.areslib.math.estimation

import com.areslib.math.geometry.Matrix3x3

/**
 * Zero-allocation 3-DOF ($x, y, \theta$) and 2-DOF planar ($x, y$, heading unobserved)
 * Cholesky back-substitution Kalman gain solver and Joseph-form posterior covariance update.
 */
internal object PoseEstimatorVision2D {
    fun solveKalmanGainAndJosephCovariance(
        effectiveObserveHeading: Boolean,
        l00: Double,
        l10: Double,
        l11: Double,
        l20: Double,
        l21: Double,
        l22: Double,
        axisX: Double,
        axisY: Double,
        axisHeading: Double,
        prior: Matrix3x3,
        scratchR: Matrix3x3,
        scratchSInv: Matrix3x3,
        scratchK: Matrix3x3,
        scratchCov: Matrix3x3,
    ) {
        if (effectiveObserveHeading) {
            var z0 = scratchSInv.m00 / l00
            var z1 = (scratchSInv.m01 - l10 * z0) / l11
            var z2 = (scratchSInv.m02 - l20 * z0 - l21 * z1) / l22
            scratchK.m02 = z2 / l22
            scratchK.m01 = (z1 - l21 * scratchK.m02) / l11
            scratchK.m00 = (z0 - l10 * scratchK.m01 - l20 * scratchK.m02) / l00
            z0 = scratchSInv.m10 / l00
            z1 = (scratchSInv.m11 - l10 * z0) / l11
            z2 = (scratchSInv.m12 - l20 * z0 - l21 * z1) / l22
            scratchK.m12 = z2 / l22
            scratchK.m11 = (z1 - l21 * scratchK.m12) / l11
            scratchK.m10 = (z0 - l10 * scratchK.m11 - l20 * scratchK.m12) / l00
            z0 = scratchSInv.m20 / l00
            z1 = (scratchSInv.m21 - l10 * z0) / l11
            z2 = (scratchSInv.m22 - l20 * z0 - l21 * z1) / l22
            scratchK.m22 = z2 / l22
            scratchK.m21 = (z1 - l21 * scratchK.m22) / l11
            scratchK.m20 = (z0 - l10 * scratchK.m21 - l20 * scratchK.m22) / l00

            scratchK.m01 = scratchK.m01 * axisX / axisY
            scratchK.m02 = scratchK.m02 * axisX / axisHeading
            scratchK.m10 = scratchK.m10 * axisY / axisX
            scratchK.m12 = scratchK.m12 * axisY / axisHeading
            scratchK.m20 = scratchK.m20 * axisHeading / axisX
            scratchK.m21 = scratchK.m21 * axisHeading / axisY
        } else {
            // 2-DOF planar (x, y) solve: H = diag(1, 1, 0), so column 2 and row 2 of K are 0.
            var z0 = scratchSInv.m00 / l00
            var z1 = (scratchSInv.m01 - l10 * z0) / l11
            scratchK.m02 = 0.0
            scratchK.m01 = z1 / l11
            scratchK.m00 = (z0 - l10 * scratchK.m01) / l00

            z0 = scratchSInv.m10 / l00
            z1 = (scratchSInv.m11 - l10 * z0) / l11
            scratchK.m12 = 0.0
            scratchK.m11 = z1 / l11
            scratchK.m10 = (z0 - l10 * scratchK.m11) / l00

            scratchK.m20 = 0.0
            scratchK.m21 = 0.0
            scratchK.m22 = 0.0

            scratchK.m01 = scratchK.m01 * axisX / axisY
            scratchK.m10 = scratchK.m10 * axisY / axisX
        }

        val imk00 = 1.0 - scratchK.m00; val imk01 = -scratchK.m01; val imk02 = -scratchK.m02
        val imk10 = -scratchK.m10; val imk11 = 1.0 - scratchK.m11; val imk12 = -scratchK.m12
        val imk20 = -scratchK.m20; val imk21 = -scratchK.m21; val imk22 = 1.0 - scratchK.m22

        val t00 = imk00 * prior.m00 + imk01 * prior.m10 + imk02 * prior.m20
        val t01 = imk00 * prior.m01 + imk01 * prior.m11 + imk02 * prior.m21
        val t02 = imk00 * prior.m02 + imk01 * prior.m12 + imk02 * prior.m22

        val t10 = imk10 * prior.m00 + imk11 * prior.m10 + imk12 * prior.m20
        val t11 = imk10 * prior.m01 + imk11 * prior.m11 + imk12 * prior.m21
        val t12 = imk10 * prior.m02 + imk11 * prior.m12 + imk12 * prior.m22

        val t20 = imk20 * prior.m00 + imk21 * prior.m10 + imk22 * prior.m20
        val t21 = imk20 * prior.m01 + imk21 * prior.m11 + imk22 * prior.m21
        val t22 = imk20 * prior.m02 + imk21 * prior.m12 + imk22 * prior.m22

        val p1_00 = t00 * imk00 + t01 * imk01 + t02 * imk02
        val p1_01 = t00 * imk10 + t01 * imk11 + t02 * imk12
        val p1_02 = t00 * imk20 + t01 * imk21 + t02 * imk22

        val p1_10 = t10 * imk00 + t11 * imk01 + t12 * imk02
        val p1_11 = t10 * imk10 + t11 * imk11 + t12 * imk12
        val p1_12 = t10 * imk20 + t11 * imk21 + t12 * imk22

        val p1_20 = t20 * imk00 + t21 * imk01 + t22 * imk02
        val p1_21 = t20 * imk10 + t21 * imk11 + t22 * imk12
        val p1_22 = t20 * imk20 + t21 * imk21 + t22 * imk22

        val kr00 = scratchK.m00 * scratchR.m00; val kr01 = scratchK.m01 * scratchR.m11; val kr02 = scratchK.m02 * scratchR.m22
        val kr10 = scratchK.m10 * scratchR.m00; val kr11 = scratchK.m11 * scratchR.m11; val kr12 = scratchK.m12 * scratchR.m22
        val kr20 = scratchK.m20 * scratchR.m00; val kr21 = scratchK.m21 * scratchR.m11; val kr22 = scratchK.m22 * scratchR.m22

        val pn00 = p1_00 + kr00 * scratchK.m00 + kr01 * scratchK.m01 + kr02 * scratchK.m02
        val pn01 = p1_01 + kr00 * scratchK.m10 + kr01 * scratchK.m11 + kr02 * scratchK.m12
        val pn02 = p1_02 + kr00 * scratchK.m20 + kr01 * scratchK.m21 + kr02 * scratchK.m22

        val pn10 = p1_10 + kr10 * scratchK.m00 + kr11 * scratchK.m01 + kr12 * scratchK.m02
        val pn11 = p1_11 + kr10 * scratchK.m10 + kr11 * scratchK.m11 + kr12 * scratchK.m12
        val pn12 = p1_12 + kr10 * scratchK.m20 + kr11 * scratchK.m21 + kr12 * scratchK.m22

        val pn20 = p1_20 + kr20 * scratchK.m00 + kr21 * scratchK.m01 + kr22 * scratchK.m02
        val pn21 = p1_21 + kr20 * scratchK.m10 + kr21 * scratchK.m11 + kr22 * scratchK.m12
        val pn22 = p1_22 + kr20 * scratchK.m20 + kr21 * scratchK.m21 + kr22 * scratchK.m22

        val sym00 = pn00.coerceAtLeast(0.0)
        val sym01 = pn01 * 0.5 + pn10 * 0.5
        val sym02 = pn02 * 0.5 + pn20 * 0.5
        val sym11 = pn11.coerceAtLeast(0.0)
        val sym12 = pn12 * 0.5 + pn21 * 0.5
        val sym22 = pn22.coerceAtLeast(0.0)

        scratchCov.m00 = sym00; scratchCov.m01 = sym01; scratchCov.m02 = sym02
        scratchCov.m10 = sym01; scratchCov.m11 = sym11; scratchCov.m12 = sym12
        scratchCov.m20 = sym02; scratchCov.m21 = sym12; scratchCov.m22 = sym22
    }
}
