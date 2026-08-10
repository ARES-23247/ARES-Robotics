package com.areslib.math.estimation

import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.Vector3
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Matrix3x3
import com.areslib.math.wrapAngle

/**
 * Extended Kalman Filter (EKF) Vision Measurement Update and Mahalanobis Outlier Rejection Pipeline.
 *
 * Implements 3-DOF ($x, y, \theta$) EKF correction from camera-observed AprilTag poses
 * with statistical Mahalanobis distance outlier filtering and historical trajectory rewind.
 *
 * ### Mathematical Formulation:
 * 1. **Innovation Residual ($\mathbf{y}$)**:
 *    $$\mathbf{y} = \begin{bmatrix} x_{\text{vision}} - x_{\text{est}} \\ y_{\text{vision}} - y_{\text{est}} \\ \text{wrapAngle}(\theta_{\text{vision}} - \theta_{\text{est}}) \end{bmatrix}$$
 * 2. **Measurement Noise Covariance ($\mathbf{R}$)**:
 *    Scales baseline standard deviations $(\sigma_x, \sigma_y, \sigma_\theta)$ by distance, multi-tag count, incidence angle, and tag ambiguity:
 *    $$\sigma_{\text{scaled}} = \sigma_{\text{base}} \cdot \frac{\sqrt{1 + d^2}}{\sqrt{N_{\text{tags}}}} \cdot \frac{1}{\cos^2(\phi)} \cdot (1 + 10 \cdot \text{ambiguity}^2)$$
 * 3. **Innovation Covariance ($\mathbf{S}$)** & **Mahalanobis Distance ($d_M^2$)**:
 *    $$\mathbf{S} = \mathbf{P}_{\text{history}} + \mathbf{R}$$
 *    $$d_M^2 = \mathbf{y}^T \mathbf{S}^{-1} \mathbf{y}$$
 * 4. **Kalman Gain ($\mathbf{K}$)** & **Covariance Update**:
 *    $$\mathbf{K} = \mathbf{P}_{\text{history}} \mathbf{S}^{-1}$$
 *    $$\mathbf{P}_{\text{updated}} = (\mathbf{I} - \mathbf{K}) \mathbf{P}_{\text{history}}$$
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position $(x, y, d)$: Meters ($m$)
 * - Heading $(\theta, \phi)$: Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 * - Standard Deviations $(\sigma_x, \sigma_y, \sigma_\theta)$: Meters ($m$) and Radians ($rad$)
 *
 * ### Zero-GC Guarantee:
 * Uses caller-supplied pre-allocated matrix scratchpads (`scratchR`, `scratchS`, `scratchSInv`, `scratchK`, `scratchCov`)
 * and a 16-slot ring pool (`kalmanGainPool`) to maintain zero dynamic heap allocations.
 *
 * @see PoseEstimator
 * @see EKFStatePropagator
 */
object VisionMahalanobisFilter {
    private val kalmanGainPool = Array(16) { DoubleArray(9) }
    private var kalmanGainPoolIndex = 0


    /**
     * Processes a single AprilTag visual measurement, performs ambiguity, NaN, bounds, and Mahalanobis rejection,
     * updates state covariance, and re-propagates the EKF trajectory from the historical observation timestamp.
     *
     * @param state Current EKF pose estimator state snapshot.
     * @param measurement Observed AprilTag 3D pose measurement (units: meters, radians).
     * @param visionStdDevs Baseline vision standard deviations $(\sigma_x, \sigma_y, \sigma_\theta)$ (units: meters, radians).
     * @param numTags Total number of detected AprilTags in the current vision frame.
     * @param useMahalanobisRejection If true, rejects vision observations exceeding [mahalanobisThreshold].
     * @param mahalanobisThreshold Chi-squared threshold for outlier rejection (typically 9.21 for 99% confidence at 3 DOF).
     * @param maxAmbiguity Maximum acceptable AprilTag pose solver ambiguity ratio (0.0 to 1.0).
     * @param activeTags Map of tag IDs to field-space 3D poses for incidence angle calculations.
     * @param baseQ Process noise covariance matrix.
     * @param scratchR Pre-allocated scratchpad matrix for measurement covariance $\mathbf{R}$.
     * @param scratchS Pre-allocated scratchpad matrix for innovation covariance $\mathbf{S}$.
     * @param scratchSInv Pre-allocated scratchpad matrix for inverted innovation covariance $\mathbf{S}^{-1}$.
     * @param scratchK Pre-allocated scratchpad matrix for Kalman Gain $\mathbf{K}$.
     * @param scratchCov Pre-allocated scratchpad matrix for updated state covariance $\mathbf{P}$.
     * @param scratchHistory Pre-allocated scratchpad buffer for trajectory re-propagation.
     * @param scratchCov2 Secondary pre-allocated scratchpad matrix for state propagation.
     *
     * @return Updated [PoseEstimatorState] with updated state history and diagnostic metrics (`lastMeasurementAccepted`, `lastRejectionReason`).
     */
    fun processVisionMeasurement(
        state: PoseEstimatorState,
        measurement: VisionMeasurement,
        visionStdDevs: Vector3,
        numTags: Int,
        useMahalanobisRejection: Boolean,
        mahalanobisThreshold: Double,
        maxAmbiguity: Double,
        activeTags: Map<Int, Pose3d>,
        baseQ: Matrix3x3,
        scratchR: Matrix3x3,
        scratchS: Matrix3x3,
        scratchSInv: Matrix3x3,
        scratchK: Matrix3x3,
        scratchCov: Matrix3x3,
        scratchHistory: HistoryBuffer,
        scratchCov2: Matrix3x3
    ): PoseEstimatorState {
        if (state.history.isEmpty()) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "empty_history"
            return state
        }

        if (measurement.ambiguity.isNaN() || measurement.ambiguity > maxAmbiguity) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "high_ambiguity"
            return state
        }
        if (measurement.targetPose.x.isNaN() || measurement.targetPose.y.isNaN() || measurement.targetPose.z.isNaN() ||
            measurement.targetPose.rotation.x.isNaN() || measurement.targetPose.rotation.y.isNaN() || measurement.targetPose.rotation.z.isNaN()) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "nan_measurement"
            return state
        }

        if (numTags <= 0) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "no_tags"
            return state
        }
        if (visionStdDevs.x.isNaN() || visionStdDevs.x.isInfinite() || 
            visionStdDevs.y.isNaN() || visionStdDevs.y.isInfinite() || 
            visionStdDevs.z.isNaN() || visionStdDevs.z.isInfinite()) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "invalid_std_devs"
            return state
        }
        if (mahalanobisThreshold.isNaN() || mahalanobisThreshold.isInfinite() || mahalanobisThreshold <= 0.0) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "invalid_threshold"
            return state
        }

        var closestIndex = -1
        for (i in state.history.size - 1 downTo 0) {
            if (state.history[i].timestampMs <= measurement.timestampMs) {
                closestIndex = i
                break
            }
        }

        if (closestIndex == -1) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "vision_too_old"
            return state
        }

        val baseEntry = state.history[closestIndex]

        val tagPose = activeTags[measurement.tagId]
        var incidenceScale = 1.0
        val distance = if (tagPose != null) {
            val dx = baseEntry.x - tagPose.x
            val dy = baseEntry.y - tagPose.y
            val dz = 0.0 - tagPose.z
            val dist3d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (dist3d > 1e-4) {
                val losAngle = kotlin.math.atan2(-dy, -dx)
                val tagYaw = tagPose.rotation.z
                val cosPhi = kotlin.math.abs(kotlin.math.cos(losAngle - tagYaw))
                incidenceScale = 1.0 / (cosPhi * cosPhi).coerceIn(0.1, 1.0)
            }
            kotlin.math.sqrt(dx * dx + dy * dy)
        } else {
            kotlin.math.abs(measurement.robotPoseTargetSpace.z)
        }

        val ambiguityScale = 1.0 + 10.0 * (measurement.ambiguity * measurement.ambiguity)
        val finalScale = incidenceScale * ambiguityScale

        val multiTagFactor = kotlin.math.max(0.5, 1.0 / kotlin.math.sqrt(numTags.coerceAtLeast(1).toDouble()))
        val distFactor = kotlin.math.sqrt(1.0 + distance * distance)
        val scaledStdDevsX = visionStdDevs.x * (multiTagFactor * distFactor * finalScale)
        val scaledStdDevsY = visionStdDevs.y * (multiTagFactor * distFactor * finalScale)
        val scaledStdDevsZ = visionStdDevs.z * (multiTagFactor * distFactor * finalScale)

        scratchR.m00 = scaledStdDevsX * scaledStdDevsX; scratchR.m01 = 0.0; scratchR.m02 = 0.0
        scratchR.m10 = 0.0; scratchR.m11 = scaledStdDevsY * scaledStdDevsY; scratchR.m12 = 0.0
        scratchR.m20 = 0.0; scratchR.m21 = 0.0; scratchR.m22 = scaledStdDevsZ * scaledStdDevsZ

        scratchS.m00 = baseEntry.covariance.m00 + scratchR.m00
        scratchS.m01 = baseEntry.covariance.m01
        scratchS.m02 = baseEntry.covariance.m02
        scratchS.m10 = baseEntry.covariance.m10
        scratchS.m11 = baseEntry.covariance.m11 + scratchR.m11
        scratchS.m12 = baseEntry.covariance.m12
        scratchS.m20 = baseEntry.covariance.m20
        scratchS.m21 = baseEntry.covariance.m21
        scratchS.m22 = baseEntry.covariance.m22 + scratchR.m22

        val det = scratchS.m00 * (scratchS.m11 * scratchS.m22 - scratchS.m12 * scratchS.m21) -
                  scratchS.m01 * (scratchS.m10 * scratchS.m22 - scratchS.m12 * scratchS.m20) +
                  scratchS.m02 * (scratchS.m10 * scratchS.m21 - scratchS.m11 * scratchS.m20)

        if (det.isNaN() || det.isInfinite() || kotlin.math.abs(det) < 1e-24) {
            // Singular innovation covariance: S^-1 is undefined. Previously SInv was zeroed,
            // forcing dM^2 = 0 and K = 0, which silently ACCEPTED the measurement as a no-op
            // while reporting "vision healthy". Reject it up-front, like every other gate.
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "singular_innovation_covariance"
            return state
        }
        val invDet = 1.0 / det
        scratchSInv.m00 =  (scratchS.m11 * scratchS.m22 - scratchS.m12 * scratchS.m21) * invDet
        scratchSInv.m01 = -(scratchS.m01 * scratchS.m22 - scratchS.m02 * scratchS.m21) * invDet
        scratchSInv.m02 =  (scratchS.m01 * scratchS.m12 - scratchS.m02 * scratchS.m11) * invDet

        scratchSInv.m10 = -(scratchS.m10 * scratchS.m22 - scratchS.m12 * scratchS.m20) * invDet
        scratchSInv.m11 =  (scratchS.m00 * scratchS.m22 - scratchS.m02 * scratchS.m20) * invDet
        scratchSInv.m12 = -(scratchS.m00 * scratchS.m12 - scratchS.m02 * scratchS.m10) * invDet

        scratchSInv.m20 =  (scratchS.m10 * scratchS.m21 - scratchS.m11 * scratchS.m20) * invDet
        scratchSInv.m21 = -(scratchS.m00 * scratchS.m21 - scratchS.m01 * scratchS.m20) * invDet
        scratchSInv.m22 =  (scratchS.m00 * scratchS.m11 - scratchS.m01 * scratchS.m10) * invDet

        val measurementPose2d = measurement.targetPose.toPose2d()
        val headingDiff = wrapAngle(measurementPose2d.heading.radians - baseEntry.headingRad)

        val yX = measurementPose2d.x - baseEntry.x
        val yY = measurementPose2d.y - baseEntry.y
        val yZ = headingDiff

        val sInvYX = scratchSInv.m00 * yX + scratchSInv.m01 * yY + scratchSInv.m02 * yZ
        val sInvYY = scratchSInv.m10 * yX + scratchSInv.m11 * yY + scratchSInv.m12 * yZ
        val sInvYZ = scratchSInv.m20 * yX + scratchSInv.m21 * yY + scratchSInv.m22 * yZ

        if (useMahalanobisRejection) {
            val dMSquared = yX * sInvYX + yY * sInvYY + yZ * sInvYZ
            if (dMSquared.isNaN() || dMSquared > mahalanobisThreshold) {
                state.lastMeasurementAccepted = false
                state.lastRejectionReason = if (dMSquared.isNaN()) "nan_innovation" else "mahalanobis_rejected"
                state.lastInnovationX = yX
                state.lastInnovationY = yY
                state.lastInnovationTheta = yZ
                return state
            }
        }

        scratchK.m00 = baseEntry.covariance.m00 * scratchSInv.m00 + baseEntry.covariance.m01 * scratchSInv.m10 + baseEntry.covariance.m02 * scratchSInv.m20
        scratchK.m01 = baseEntry.covariance.m00 * scratchSInv.m01 + baseEntry.covariance.m01 * scratchSInv.m11 + baseEntry.covariance.m02 * scratchSInv.m21
        scratchK.m02 = baseEntry.covariance.m00 * scratchSInv.m02 + baseEntry.covariance.m01 * scratchSInv.m12 + baseEntry.covariance.m02 * scratchSInv.m22

        scratchK.m10 = baseEntry.covariance.m10 * scratchSInv.m00 + baseEntry.covariance.m11 * scratchSInv.m10 + baseEntry.covariance.m12 * scratchSInv.m20
        scratchK.m11 = baseEntry.covariance.m10 * scratchSInv.m01 + baseEntry.covariance.m11 * scratchSInv.m11 + baseEntry.covariance.m12 * scratchSInv.m21
        scratchK.m12 = baseEntry.covariance.m10 * scratchSInv.m02 + baseEntry.covariance.m11 * scratchSInv.m12 + baseEntry.covariance.m12 * scratchSInv.m22

        scratchK.m20 = baseEntry.covariance.m20 * scratchSInv.m00 + baseEntry.covariance.m21 * scratchSInv.m10 + baseEntry.covariance.m22 * scratchSInv.m20
        scratchK.m21 = baseEntry.covariance.m20 * scratchSInv.m01 + baseEntry.covariance.m21 * scratchSInv.m11 + baseEntry.covariance.m22 * scratchSInv.m21
        scratchK.m22 = baseEntry.covariance.m20 * scratchSInv.m02 + baseEntry.covariance.m21 * scratchSInv.m12 + baseEntry.covariance.m22 * scratchSInv.m22

        val dxX = scratchK.m00 * yX + scratchK.m01 * yY + scratchK.m02 * yZ
        val dxY = scratchK.m10 * yX + scratchK.m11 * yY + scratchK.m12 * yZ
        val dxZ = scratchK.m20 * yX + scratchK.m21 * yY + scratchK.m22 * yZ

        val imk00 = 1.0 - scratchK.m00
        val imk01 = -scratchK.m01
        val imk02 = -scratchK.m02
        val imk10 = -scratchK.m10
        val imk11 = 1.0 - scratchK.m11
        val imk12 = -scratchK.m12
        val imk20 = -scratchK.m20
        val imk21 = -scratchK.m21
        val imk22 = 1.0 - scratchK.m22

        val P = baseEntry.covariance
        val t00 = imk00 * P.m00 + imk01 * P.m10 + imk02 * P.m20
        val t01 = imk00 * P.m01 + imk01 * P.m11 + imk02 * P.m21
        val t02 = imk00 * P.m02 + imk01 * P.m12 + imk02 * P.m22

        val t10 = imk10 * P.m00 + imk11 * P.m10 + imk12 * P.m20
        val t11 = imk10 * P.m01 + imk11 * P.m11 + imk12 * P.m21
        val t12 = imk10 * P.m02 + imk11 * P.m12 + imk12 * P.m22

        val t20 = imk20 * P.m00 + imk21 * P.m10 + imk22 * P.m20
        val t21 = imk20 * P.m01 + imk21 * P.m11 + imk22 * P.m21
        val t22 = imk20 * P.m02 + imk21 * P.m12 + imk22 * P.m22

        val p1_00 = t00 * imk00 + t01 * imk01 + t02 * imk02
        val p1_01 = t00 * imk10 + t01 * imk11 + t02 * imk12
        val p1_02 = t00 * imk20 + t01 * imk21 + t02 * imk22

        val p1_10 = t10 * imk00 + t11 * imk01 + t12 * imk02
        val p1_11 = t10 * imk10 + t11 * imk11 + t12 * imk12
        val p1_12 = t10 * imk20 + t11 * imk21 + t12 * imk22

        val p1_20 = t20 * imk00 + t21 * imk01 + t22 * imk02
        val p1_21 = t20 * imk10 + t21 * imk11 + t22 * imk12
        val p1_22 = t20 * imk20 + t21 * imk21 + t22 * imk22

        val r00 = scratchR.m00
        val r11 = scratchR.m11
        val r22 = scratchR.m22

        val kr00 = scratchK.m00 * r00
        val kr01 = scratchK.m01 * r11
        val kr02 = scratchK.m02 * r22

        val kr10 = scratchK.m10 * r00
        val kr11 = scratchK.m11 * r11
        val kr12 = scratchK.m12 * r22

        val kr20 = scratchK.m20 * r00
        val kr21 = scratchK.m21 * r11
        val kr22 = scratchK.m22 * r22

        val p2_00 = kr00 * scratchK.m00 + kr01 * scratchK.m01 + kr02 * scratchK.m02
        val p2_01 = kr00 * scratchK.m10 + kr01 * scratchK.m11 + kr02 * scratchK.m12
        val p2_02 = kr00 * scratchK.m20 + kr01 * scratchK.m21 + kr02 * scratchK.m22

        val p2_10 = kr10 * scratchK.m00 + kr11 * scratchK.m01 + kr12 * scratchK.m02
        val p2_11 = kr10 * scratchK.m10 + kr11 * scratchK.m11 + kr12 * scratchK.m12
        val p2_12 = kr10 * scratchK.m20 + kr11 * scratchK.m21 + kr12 * scratchK.m22

        val p2_20 = kr20 * scratchK.m00 + kr21 * scratchK.m01 + kr22 * scratchK.m02
        val p2_21 = kr20 * scratchK.m10 + kr21 * scratchK.m11 + kr22 * scratchK.m12
        val p2_22 = kr20 * scratchK.m20 + kr21 * scratchK.m21 + kr22 * scratchK.m22

        val pn00 = p1_00 + p2_00
        val pn01 = p1_01 + p2_01
        val pn02 = p1_02 + p2_02

        val pn10 = p1_10 + p2_10
        val pn11 = p1_11 + p2_11
        val pn12 = p1_12 + p2_12

        val pn20 = p1_20 + p2_20
        val pn21 = p1_21 + p2_21
        val pn22 = p1_22 + p2_22

        val sym00 = pn00.coerceAtLeast(1e-9)
        val sym01 = (pn01 + pn10) / 2.0
        val sym02 = (pn02 + pn20) / 2.0

        val sym11 = pn11.coerceAtLeast(1e-9)
        val sym12 = (pn12 + pn21) / 2.0

        val sym22 = pn22.coerceAtLeast(1e-9)

        scratchCov.m00 = sym00; scratchCov.m01 = sym01; scratchCov.m02 = sym02
        scratchCov.m10 = sym01; scratchCov.m11 = sym11; scratchCov.m12 = sym12
        scratchCov.m20 = sym02; scratchCov.m21 = sym12; scratchCov.m22 = sym22

        state.history.copyInto(scratchHistory)

        EKFStatePropagator.repropagateHistory(
            state, closestIndex, baseEntry,
            dxX, dxY, dxZ,
            scratchCov, baseQ,
            scratchHistory, scratchCov2
        )

        scratchHistory.copyInto(state.history)

        val kg = kalmanGainPool[kalmanGainPoolIndex]
        kg[0] = scratchK.m00; kg[1] = scratchK.m01; kg[2] = scratchK.m02
        kg[3] = scratchK.m10; kg[4] = scratchK.m11; kg[5] = scratchK.m12
        kg[6] = scratchK.m20; kg[7] = scratchK.m21; kg[8] = scratchK.m22
        kalmanGainPoolIndex = (kalmanGainPoolIndex + 1) % 16

        state.lastInnovationX = yX
        state.lastInnovationY = yY
        state.lastInnovationTheta = yZ
        state.lastKalmanGain = kg
        state.lastMeasurementAccepted = true
        state.lastRejectionReason = null

        return state
    }
}
