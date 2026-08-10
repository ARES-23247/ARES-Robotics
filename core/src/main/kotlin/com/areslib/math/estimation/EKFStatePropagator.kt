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
        scratchCov2: Matrix3x3
    ) {
        var currentX = baseEntry.x + dxX
        var currentY = baseEntry.y + dxY
        var currentHeadingRad = wrapAngle(baseEntry.headingRad + dxZ)

        scratchCov2.setTo(updatedCovariance)

        scratchHistory.updateEntryDirect(closestIndex, baseEntry.timestampMs, currentX, currentY, currentHeadingRad, scratchCov2, baseEntry.qScale)

        for (i in (closestIndex + 1) until state.history.size) {
            val prevRaw = state.history[i - 1]
            val currRaw = state.history[i]

            val originalFieldDx = currRaw.x - prevRaw.x
            val originalFieldDy = currRaw.y - prevRaw.y
            val deltaHeading = wrapAngle(currRaw.headingRad - prevRaw.headingRad)

            // Recover the robot-frame arc displacement that produced the original
            // historical transition. Replaying the old field delta directly would leave
            // all later positions unchanged when vision corrects the historical heading.
            val originalCos = kotlin.math.cos(prevRaw.headingRad)
            val originalSin = kotlin.math.sin(prevRaw.headingRad)
            val robotArcDx = originalFieldDx * originalCos + originalFieldDy * originalSin
            val robotArcDy = -originalFieldDx * originalSin + originalFieldDy * originalCos

            // Rotate that same local motion through the corrected pre-update heading.
            val correctedCos = kotlin.math.cos(currentHeadingRad)
            val correctedSin = kotlin.math.sin(currentHeadingRad)
            val correctedFieldDx = robotArcDx * correctedCos - robotArcDy * correctedSin
            val correctedFieldDy = robotArcDx * correctedSin + robotArcDy * correctedCos

            currentX += correctedFieldDx
            currentY += correctedFieldDy
            currentHeadingRad = wrapAngle(currentHeadingRad + deltaHeading)

            val scale = currRaw.qScale
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
