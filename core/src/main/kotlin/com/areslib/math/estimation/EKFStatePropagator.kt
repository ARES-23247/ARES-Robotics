package com.areslib.math.estimation

import com.areslib.math.geometry.Matrix3x3
import com.areslib.math.wrapAngle

/**
 * Extended Kalman Filter (EKF) State Transition and Historical Trajectory Re-propagation Engine.
 *
 * Propagates 3-DOF ($x, y, \theta$) pose state error covariance matrices forward using linearized local motion Jacobians ($\mathbf{F}_k$),
 * and executes 100Hz historical trajectory rewind passes whenever a delayed AprilTag vision measurement arrives to correct past states.
 *
 * ### Mathematical Formulation:
 * 1. **Kinematic Motion Model**:
 *    $$\mathbf{f}(x, y, \theta) = \begin{bmatrix} x + \Delta x_{arc} \cos\theta - \Delta y_{arc} \sin\theta \\ y + \Delta x_{arc} \sin\theta + \Delta y_{arc} \cos\theta \\ \text{wrapAngle}(\theta + \Delta\theta) \end{bmatrix}$$
 * 2. **Motion Model Jacobian ($\mathbf{F}_k$)**:
 *    $$\mathbf{F}_k = \begin{bmatrix} 1 & 0 & -\Delta x_{arc} \sin\theta - \Delta y_{arc} \cos\theta \\ 0 & 1 & \Delta x_{arc} \cos\theta - \Delta y_{arc} \sin\theta \\ 0 & 0 & 1 \end{bmatrix}$$
 * 3. **Covariance Propagation ($\mathbf{P}_k$)**:
 *    $$\mathbf{P}_k = \mathbf{F}_k \mathbf{P}_{k-1} \mathbf{F}_k^T + \mathbf{Q}$$
 * 4. **Historical Trajectory Re-propagation**:
 *    Starting from the closest historical frame index $i = k_{\text{vision}}$, applies innovation shift $[\delta x, \delta y, \delta\theta]^T$,
 *    then sequentially integrates stored odometry deltas $(\Delta x_j, \Delta y_j, \Delta\theta_j)$ forward to the current timestamp:
 *    $$\mathbf{P}_j \leftarrow \mathbf{F}_j \mathbf{P}_{j-1} \mathbf{F}_j^T + s_j \mathbf{Q}$$
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position $(x, y, \Delta x, \Delta y)$: Field-centric and robot-centric meters ($m$)
 * - Heading $(\theta, \Delta\theta, \theta_{\text{mid}})$: Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 * - Time ($t$): Milliseconds ($ms$)
 *
 * ### Zero-GC Guarantee:
 * Uses pre-allocated primitive array buffers (`covarianceArray`) and caller-supplied matrix scratchpads (`scratchCov2`, `scratchHistory`)
 * to eliminate heap allocations during 100Hz trajectory rewind passes.
 *
 * @see PoseEstimator
 * @see VisionMahalanobisFilter
 */
object EKFStatePropagator {

    /**
     * Predicts the historical state at an exact timestamp inside one stored odometry
     * interval. The interval twist and process noise are split by the same fraction.
     */
    fun interpolateHistoryEntry(
        history: HistoryBuffer,
        baseIndex: Int,
        timestampMs: Long,
        baseQ: Matrix3x3,
        output: PoseHistoryEntry
    ): Double {
        val base = history[baseIndex]
        if (baseIndex >= history.size - 1 || timestampMs <= base.timestampMs) {
            output.timestampMs = base.timestampMs
            output.x = base.x
            output.y = base.y
            output.headingRad = base.headingRad
            output.covariance.setTo(base.covariance)
            output.qScale = base.qScale
            output.deltaXRobot = 0.0
            output.deltaYRobot = 0.0
            output.deltaHeadingRad = 0.0
            output.hasMotion = false
            return 0.0
        }

        val next = history[baseIndex + 1]
        val intervalMs = next.timestampMs - base.timestampMs
        if (intervalMs <= 0L) return 0.0
        val fraction = ((timestampMs - base.timestampMs).toDouble() / intervalMs.toDouble()).coerceIn(0.0, 1.0)

        var twistX = next.deltaXRobot
        var twistY = next.deltaYRobot
        val twistHeading = if (next.hasMotion) next.deltaHeadingRad else wrapAngle(next.headingRad - base.headingRad)
        if (!next.hasMotion) {
            val fieldDx = next.x - base.x
            val fieldDy = next.y - base.y
            val cosBase = kotlin.math.cos(base.headingRad)
            val sinBase = kotlin.math.sin(base.headingRad)
            val arcX = fieldDx * cosBase + fieldDy * sinBase
            val arcY = -fieldDx * sinBase + fieldDy * cosBase
            if (kotlin.math.abs(twistHeading) < 1e-6) {
                twistX = arcX
                twistY = arcY
            } else {
                val s = kotlin.math.sin(twistHeading) / twistHeading
                val c = (1.0 - kotlin.math.cos(twistHeading)) / twistHeading
                val determinant = s * s + c * c
                twistX = (s * arcX + c * arcY) / determinant
                twistY = (-c * arcX + s * arcY) / determinant
            }
        }

        val partialX = twistX * fraction
        val partialY = twistY * fraction
        val partialHeading = twistHeading * fraction
        val s: Double
        val c: Double
        if (kotlin.math.abs(partialHeading) < 1e-6) {
            s = 1.0 - partialHeading * partialHeading / 6.0
            c = partialHeading * 0.5
        } else {
            s = kotlin.math.sin(partialHeading) / partialHeading
            c = (1.0 - kotlin.math.cos(partialHeading)) / partialHeading
        }
        val arcX = s * partialX - c * partialY
        val arcY = c * partialX + s * partialY
        val cosBase = kotlin.math.cos(base.headingRad)
        val sinBase = kotlin.math.sin(base.headingRad)
        val fieldDx = arcX * cosBase - arcY * sinBase
        val fieldDy = arcX * sinBase + arcY * cosBase

        output.timestampMs = timestampMs
        output.x = base.x + fieldDx
        output.y = base.y + fieldDy
        output.headingRad = wrapAngle(base.headingRad + partialHeading)
        output.qScale = next.qScale * fraction
        output.deltaXRobot = partialX
        output.deltaYRobot = partialY
        output.deltaHeadingRad = partialHeading
        output.hasMotion = true
        propagateMatrix(base.covariance, arcX, arcY, base.headingRad, baseQ, output.qScale, output.covariance)
        return fraction
    }

    private fun propagateMatrix(
        covariance: Matrix3x3,
        arcX: Double,
        arcY: Double,
        heading: Double,
        baseQ: Matrix3x3,
        qScale: Double,
        output: Matrix3x3
    ) {
        val sinHeading = kotlin.math.sin(heading)
        val cosHeading = kotlin.math.cos(heading)
        val f02 = -arcX * sinHeading - arcY * cosHeading
        val f12 = arcX * cosHeading - arcY * sinHeading
        val fp00 = covariance.m00 + f02 * covariance.m20
        val fp01 = covariance.m01 + f02 * covariance.m21
        val fp02 = covariance.m02 + f02 * covariance.m22
        val fp10 = covariance.m10 + f12 * covariance.m20
        val fp11 = covariance.m11 + f12 * covariance.m21
        val fp12 = covariance.m12 + f12 * covariance.m22
        val fp20 = covariance.m20
        val fp21 = covariance.m21
        val fp22 = covariance.m22
        val m00 = fp00 + f02 * fp02 + baseQ.m00 * qScale
        val m01 = fp01 + f12 * fp02 + baseQ.m01 * qScale
        val m02 = fp02 + baseQ.m02 * qScale
        val m10 = fp10 + f02 * fp12 + baseQ.m10 * qScale
        val m11 = fp11 + f12 * fp12 + baseQ.m11 * qScale
        val m12 = fp12 + baseQ.m12 * qScale
        val m20 = fp20 + f02 * fp22 + baseQ.m20 * qScale
        val m21 = fp21 + f12 * fp22 + baseQ.m21 * qScale
        val m22 = fp22 + baseQ.m22 * qScale
        output.m00 = m00
        output.m01 = (m01 + m10) * 0.5
        output.m02 = (m02 + m20) * 0.5
        output.m10 = output.m01
        output.m11 = m11
        output.m12 = (m12 + m21) * 0.5
        output.m20 = output.m02
        output.m21 = output.m12
        output.m22 = m22
    }

    /**
     * Propagates a 3x3 state error covariance matrix forward one step using linearized local displacement deltas.
     *
     * @param covarianceArray Flattened 9-element 3x3 covariance matrix array $[P_{00}, P_{01}, \dots, P_{22}]$.
     * @param deltaX Exact local robot-frame arc displacement along X in meters ($m$).
     * @param deltaY Exact local robot-frame arc displacement along Y in meters ($m$).
     * @param theta Heading at the start of the displacement in radians ($rad$).
     * @param qMatrix Process noise covariance matrix $\mathbf{Q}$.
     * @param outCovariance Output [Matrix3x3] scratchpad storing the updated covariance matrix $\mathbf{P}_k$.
     */
    fun propagate(
        covarianceArray: DoubleArray,
        deltaX: Double,
        deltaY: Double,
        theta: Double,
        qMatrix: Matrix3x3,
        outCovariance: Matrix3x3
    ) {
        val sinTheta = kotlin.math.sin(theta)
        val cosTheta = kotlin.math.cos(theta)
        val f02 = -deltaX * sinTheta - deltaY * cosTheta
        val f12 =  deltaX * cosTheta - deltaY * sinTheta

        val fp00 = covarianceArray[0] + f02 * covarianceArray[6]
        val fp01 = covarianceArray[1] + f02 * covarianceArray[7]
        val fp02 = covarianceArray[2] + f02 * covarianceArray[8]
        val fp10 = covarianceArray[3] + f12 * covarianceArray[6]
        val fp11 = covarianceArray[4] + f12 * covarianceArray[7]
        val fp12 = covarianceArray[5] + f12 * covarianceArray[8]
        val fp20 = covarianceArray[6]
        val fp21 = covarianceArray[7]
        val fp22 = covarianceArray[8]

        outCovariance.m00 = fp00 + f02 * fp02 + qMatrix.m00
        outCovariance.m01 = fp01 + f12 * fp02 + qMatrix.m01
        outCovariance.m02 = fp02 + qMatrix.m02
        outCovariance.m10 = fp10 + f02 * fp12 + qMatrix.m10
        outCovariance.m11 = fp11 + f12 * fp12 + qMatrix.m11
        outCovariance.m12 = fp12 + qMatrix.m12
        outCovariance.m20 = fp20 + f02 * fp22 + qMatrix.m20
        outCovariance.m21 = fp21 + f12 * fp22 + qMatrix.m21
        outCovariance.m22 = fp22 + qMatrix.m22

        val sym01 = (outCovariance.m01 + outCovariance.m10) * 0.5
        outCovariance.m01 = sym01
        outCovariance.m10 = sym01
        val sym02 = (outCovariance.m02 + outCovariance.m20) * 0.5
        outCovariance.m02 = sym02
        outCovariance.m20 = sym02
        val sym12 = (outCovariance.m12 + outCovariance.m21) * 0.5
        outCovariance.m12 = sym12
        outCovariance.m21 = sym12
    }

    /**
     * Re-propagates the pose history and covariance buffer from a delayed vision measurement timestamp to the present frame.
     *
     * @param state Active EKF pose estimator state snapshot.
     * @param closestIndex Buffer index of the historical pose entry matching the vision observation timestamp.
     * @param baseEntry Historical pose entry prior to vision correction.
     * @param dxX Innovation shift correction along X axis in meters ($m$).
     * @param dxY Innovation shift correction along Y axis in meters ($m$).
     * @param dxZ Innovation shift correction in heading in radians ($rad$).
     * @param updatedCovariance Vision-corrected 3x3 covariance matrix at [closestIndex].
     * @param baseQ Baseline process noise covariance matrix $\mathbf{Q}$.
     * @param scratchHistory Scratchpad history buffer to store re-propagated trajectory entries.
     * @param scratchCov2 Scratchpad 3x3 matrix for incremental covariance propagation.
     */
    fun repropagateHistory(

        state: PoseEstimatorState,
        closestIndex: Int,
        baseEntry: PoseHistoryEntry,
        dxX: Double, dxY: Double, dxZ: Double,
        updatedCovariance: Matrix3x3,
        baseQ: Matrix3x3,
        scratchHistory: HistoryBuffer,
        scratchCov2: Matrix3x3,
        intervalFraction: Double = 0.0
    ) {
        var currentX = baseEntry.x + dxX
        var currentY = baseEntry.y + dxY
        var currentHeadingRad = wrapAngle(baseEntry.headingRad + dxZ)

        scratchCov2.setTo(updatedCovariance)

        if (intervalFraction <= 0.0) {
            scratchHistory.updateEntryDirect(closestIndex, baseEntry.timestampMs, currentX, currentY, currentHeadingRad, scratchCov2, baseEntry.qScale)
        }

        for (i in (closestIndex + 1) until state.history.size) {
            val prevRaw = state.history[i - 1]
            val currRaw = state.history[i]
            var twistX = currRaw.deltaXRobot
            var twistY = currRaw.deltaYRobot
            var deltaHeading = if (currRaw.hasMotion) currRaw.deltaHeadingRad else wrapAngle(currRaw.headingRad - prevRaw.headingRad)
            if (!currRaw.hasMotion) {
                val originalFieldDx = currRaw.x - prevRaw.x
                val originalFieldDy = currRaw.y - prevRaw.y
                val originalCos = kotlin.math.cos(prevRaw.headingRad)
                val originalSin = kotlin.math.sin(prevRaw.headingRad)
                val robotArcDx = originalFieldDx * originalCos + originalFieldDy * originalSin
                val robotArcDy = -originalFieldDx * originalSin + originalFieldDy * originalCos
                if (kotlin.math.abs(deltaHeading) < 1e-6) {
                    twistX = robotArcDx
                    twistY = robotArcDy
                } else {
                    val s = kotlin.math.sin(deltaHeading) / deltaHeading
                    val c = (1.0 - kotlin.math.cos(deltaHeading)) / deltaHeading
                    val determinant = s * s + c * c
                    twistX = (s * robotArcDx + c * robotArcDy) / determinant
                    twistY = (-c * robotArcDx + s * robotArcDy) / determinant
                }
            }
            val fraction = if (i == closestIndex + 1 && intervalFraction > 0.0) 1.0 - intervalFraction else 1.0
            twistX *= fraction
            twistY *= fraction
            deltaHeading *= fraction
            val expS: Double
            val expC: Double
            if (kotlin.math.abs(deltaHeading) < 1e-6) {
                expS = 1.0 - deltaHeading * deltaHeading / 6.0
                expC = deltaHeading * 0.5
            } else {
                expS = kotlin.math.sin(deltaHeading) / deltaHeading
                expC = (1.0 - kotlin.math.cos(deltaHeading)) / deltaHeading
            }
            val robotArcDx = expS * twistX - expC * twistY
            val robotArcDy = expC * twistX + expS * twistY
            val correctedCos = kotlin.math.cos(currentHeadingRad)
            val correctedSin = kotlin.math.sin(currentHeadingRad)
            val correctedFieldDx = robotArcDx * correctedCos - robotArcDy * correctedSin
            val correctedFieldDy = robotArcDx * correctedSin + robotArcDy * correctedCos

            currentX += correctedFieldDx
            currentY += correctedFieldDy
            currentHeadingRad = wrapAngle(currentHeadingRad + deltaHeading)

            val scale = currRaw.qScale * fraction
            val reF02 = -correctedFieldDy
            val reF12 = correctedFieldDx

            val reFp00 = scratchCov2.m00 + reF02 * scratchCov2.m20
            val reFp01 = scratchCov2.m01 + reF02 * scratchCov2.m21
            val reFp02 = scratchCov2.m02 + reF02 * scratchCov2.m22
            val reFp10 = scratchCov2.m10 + reF12 * scratchCov2.m20
            val reFp11 = scratchCov2.m11 + reF12 * scratchCov2.m21
            val reFp12 = scratchCov2.m12 + reF12 * scratchCov2.m22
            val reFp20 = scratchCov2.m20
            val reFp21 = scratchCov2.m21
            val reFp22 = scratchCov2.m22

            val newM00 = reFp00 + reF02 * reFp02 + baseQ.m00 * scale
            val newM01 = reFp01 + reF12 * reFp02 + baseQ.m01 * scale
            val newM02 = reFp02 + baseQ.m02 * scale
            val newM10 = reFp10 + reF02 * reFp12 + baseQ.m10 * scale
            val newM11 = reFp11 + reF12 * reFp12 + baseQ.m11 * scale
            val newM12 = reFp12 + baseQ.m12 * scale
            val newM20 = reFp20 + reF02 * reFp22 + baseQ.m20 * scale
            val newM21 = reFp21 + reF12 * reFp22 + baseQ.m21 * scale
            val newM22 = reFp22 + baseQ.m22 * scale

            val sym01 = (newM01 + newM10) * 0.5
            val sym02 = (newM02 + newM20) * 0.5
            val sym12 = (newM12 + newM21) * 0.5

            scratchCov2.m00 = newM00
            scratchCov2.m01 = sym01
            scratchCov2.m02 = sym02
            scratchCov2.m10 = sym01
            scratchCov2.m11 = newM11
            scratchCov2.m12 = sym12
            scratchCov2.m20 = sym02
            scratchCov2.m21 = sym12
            scratchCov2.m22 = newM22

            scratchHistory.updateEntryDirect(i, state.history[i].timestampMs, currentX, currentY, currentHeadingRad, scratchCov2, currRaw.qScale)
        }

        // Apply back to state
        state.estimatedPoseX = currentX
        state.estimatedPoseY = currentY
        state.estimatedPoseHeading = currentHeadingRad

        state.covarianceArray[0] = scratchCov2.m00
        state.covarianceArray[1] = scratchCov2.m01
        state.covarianceArray[2] = scratchCov2.m02
        state.covarianceArray[3] = scratchCov2.m10
        state.covarianceArray[4] = scratchCov2.m11
        state.covarianceArray[5] = scratchCov2.m12
        state.covarianceArray[6] = scratchCov2.m20
        state.covarianceArray[7] = scratchCov2.m21
        state.covarianceArray[8] = scratchCov2.m22
    }
}
