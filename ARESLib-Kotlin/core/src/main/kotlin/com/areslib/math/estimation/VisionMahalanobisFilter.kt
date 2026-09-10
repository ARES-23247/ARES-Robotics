package com.areslib.math.estimation

import com.areslib.state.VisionMeasurement
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
 *    Distance uses hypot for stable range scaling. The multi-tag factor is bounded below by 0.5,
 *    and inverse squared incidence cosine is bounded above by 10.
 * 3. **Innovation Covariance ($\mathbf{S}$)** & **Mahalanobis Distance ($d_M^2$)**:
 *    $$\mathbf{S} = \mathbf{P}_{\text{history}} + \mathbf{R}$$
 *    $$d_M^2 = \mathbf{y}^T \mathbf{S}^{-1} \mathbf{y}$$
 * 4. **Kalman Gain ($\mathbf{K}$)** & **Covariance Update**:
 *    $$\mathbf{K} = \mathbf{P}_{\text{history}} \mathbf{S}^{-1}$$
 *    Uses the Joseph covariance form $(I-K)P(I-K)^T + KRK^T$, without an absolute variance floor.
 *    A normalized Cholesky factor whitens residuals and solves for K without an explicit inverse.
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position $(x, y, d)$: Meters ($m$)
 * - Heading $(\theta, \phi)$: Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 * - Standard Deviations $(\sigma_x, \sigma_y, \sigma_\theta)$: Meters ($m$) and Radians ($rad$)
 *
 * ### Zero-GC Guarantee:
 * Uses caller-supplied pre-allocated matrix scratchpads (`scratchR`, `scratchS`, `scratchSInv`, `scratchK`, `scratchCov`)
 * and a caller-supplied history scratchpad to avoid dynamic heap allocations in the vision update.
 *
 * @see PoseEstimator
 * @see EKFStatePropagator
 */
object VisionMahalanobisFilter {


    /**
     * Processes a single AprilTag visual measurement, performs ambiguity, NaN, bounds, and Mahalanobis rejection,
     * updates state covariance, and re-propagates the EKF trajectory from the historical observation timestamp.
     *
     * @param state Current EKF pose estimator state snapshot.
     * @param measurement Observed AprilTag 3D pose measurement (units: meters, radians).
     * @param visionStdDevX X standard deviation in meters; baseline unless [scaleStdDevX] is false.
     * @param visionStdDevY Y standard deviation in meters; baseline unless [scaleStdDevY] is false.
     * @param visionStdDevHeading Heading standard deviation in radians; baseline unless [scaleStdDevHeading] is false.
     * @param numTags Total number of detected AprilTags in the current vision frame.
     * @param useMahalanobisRejection If true, rejects vision observations exceeding [mahalanobisThreshold].
     * @param mahalanobisThreshold Normalized-innovation-squared threshold for the 3-DOF
     * $(x, y, \theta)$ residual. [PoseEstimator] defaults this value to $12.0$; callers may tune it
     * from validated field data.
     * @param maxAmbiguity Finite nonnegative maximum acceptable available solver ambiguity.
     * @param activeTags Map of tag IDs to field-space 3D poses for incidence angle calculations.
     * @param baseQ Process noise covariance matrix.
     * @param scratchR Pre-allocated scratchpad matrix for measurement covariance $\mathbf{R}$.
     * @param scratchS Pre-allocated scratchpad matrix for normalized innovation covariance.
     * @param scratchSInv Legacy-named workspace holding normalized P for the gain solve; no inverse is formed.
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
        visionStdDevX: Double,
        visionStdDevY: Double,
        visionStdDevHeading: Double,
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
        scratchCov2: Matrix3x3,
        scratchInterpolatedEntry: PoseHistoryEntry,
        scaleStdDevX: Boolean = true,
        scaleStdDevY: Boolean = true,
        scaleStdDevHeading: Boolean = true
    ): PoseEstimatorState {
        if (state.history.isEmpty()) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "empty_history"
            return state
        }
        if (measurement.timestampMs < state.lastVisionTimestampMs) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "vision_out_of_order"
            return state
        }

        if (!maxAmbiguity.isFinite() || maxAmbiguity < 0.0) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "invalid_ambiguity_limit"
            return state
        }
        val ambiguity = if (measurement.ambiguityAvailable) measurement.ambiguity else 0.0
        if (!ambiguity.isFinite() || ambiguity < 0.0 || ambiguity > maxAmbiguity) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "high_ambiguity"
            return state
        }
        val measuredHeading = measurement.targetPose.rotation.z
        if (!measurement.targetPose.x.isFinite() || !measurement.targetPose.y.isFinite() || !measurement.targetPose.z.isFinite() ||
            !measurement.targetPose.rotation.x.isFinite() || !measurement.targetPose.rotation.y.isFinite() || !measuredHeading.isFinite()) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "nan_measurement"
            return state
        }

        if (numTags <= 0) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "no_tags"
            return state
        }
        if (!visionStdDevX.isFinite() || visionStdDevX <= 0.0 ||
            !visionStdDevY.isFinite() || visionStdDevY <= 0.0 ||
            !visionStdDevHeading.isFinite() || visionStdDevHeading <= 0.0) {
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

        val intervalFraction = EKFStatePropagator.interpolateHistoryEntry(
            state.history,
            closestIndex,
            measurement.timestampMs,
            baseQ,
            scratchInterpolatedEntry
        )
        val baseEntry = if (intervalFraction > 0.0) scratchInterpolatedEntry else state.history[closestIndex]
        if (!baseEntry.x.isFinite() || !baseEntry.y.isFinite() || !baseEntry.headingRad.isFinite() ||
            !validCovariance(baseEntry.covariance)) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "invalid_prior_covariance_or_pose"
            return state
        }

        val stdDevScale = if (scaleStdDevX || scaleStdDevY || scaleStdDevHeading) {
            val tagPose = activeTags[measurement.tagId]
            var incidenceScale = 1.0
            val distance = if (tagPose != null) {
                val dx = baseEntry.x - tagPose.x
                val dy = baseEntry.y - tagPose.y
                val planarDistance = kotlin.math.hypot(dx, dy)
                if (planarDistance > 1e-4) {
                    val tagYaw = tagPose.rotation.z
                    val cosPhi = kotlin.math.abs(
                        (dx * kotlin.math.cos(tagYaw) + dy * kotlin.math.sin(tagYaw)) / planarDistance)
                    incidenceScale = 1.0 / (cosPhi * cosPhi).coerceIn(0.1, 1.0)
                }
                planarDistance
            } else {
                val targetSpace = measurement.robotPoseTargetSpace
                kotlin.math.hypot(kotlin.math.hypot(targetSpace.x, targetSpace.y), targetSpace.z)
            }
            val ambiguityScale = 1.0 + 10.0 * (ambiguity * ambiguity)
            val multiTagFactor = kotlin.math.max(0.5, 1.0 / kotlin.math.sqrt(numTags.toDouble()))
            multiTagFactor * kotlin.math.hypot(1.0, distance) * incidenceScale * ambiguityScale
        } else 1.0
        val scaledStdDevsX = visionStdDevX * if (scaleStdDevX) stdDevScale else 1.0
        val scaledStdDevsY = visionStdDevY * if (scaleStdDevY) stdDevScale else 1.0
        val scaledStdDevsZ = visionStdDevHeading * if (scaleStdDevHeading) stdDevScale else 1.0

        scratchR.m00 = scaledStdDevsX * scaledStdDevsX; scratchR.m01 = 0.0; scratchR.m02 = 0.0
        scratchR.m10 = 0.0; scratchR.m11 = scaledStdDevsY * scaledStdDevsY; scratchR.m12 = 0.0
        scratchR.m20 = 0.0; scratchR.m21 = 0.0; scratchR.m22 = scaledStdDevsZ * scaledStdDevsZ

        if (!scratchR.m00.isFinite() || !scratchR.m11.isFinite() || !scratchR.m22.isFinite()) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "invalid_measurement_covariance"
            return state
        }
        val prior = baseEntry.covariance
        val varianceScaleX = maxOf(prior.m00, scratchR.m00)
        val varianceScaleY = maxOf(prior.m11, scratchR.m11)
        val varianceScaleHeading = maxOf(prior.m22, scratchR.m22)
        if (varianceScaleX <= 0.0 || varianceScaleY <= 0.0 || varianceScaleHeading <= 0.0) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "non_positive_definite_innovation_covariance"
            return state
        }
        // Normalize each axis before adding P and R. A single common scale can erase
        // valid small axes. With D = diag(sqrt(max(Pii, Rii))), solve in D^-1 S D^-1.
        val axisX = kotlin.math.sqrt(varianceScaleX)
        val axisY = kotlin.math.sqrt(varianceScaleY)
        val axisHeading = kotlin.math.sqrt(varianceScaleHeading)
        scratchSInv.m00 = prior.m00 / varianceScaleX
        scratchSInv.m01 = prior.m01 / axisX / axisY
        scratchSInv.m02 = prior.m02 / axisX / axisHeading
        scratchSInv.m10 = prior.m10 / axisY / axisX
        scratchSInv.m11 = prior.m11 / varianceScaleY
        scratchSInv.m12 = prior.m12 / axisY / axisHeading
        scratchSInv.m20 = prior.m20 / axisHeading / axisX
        scratchSInv.m21 = prior.m21 / axisHeading / axisY
        scratchSInv.m22 = prior.m22 / varianceScaleHeading
        scratchS.m00 = scratchSInv.m00 + scratchR.m00 / varianceScaleX
        scratchS.m01 = (scratchSInv.m01 + scratchSInv.m10) * 0.5
        scratchS.m02 = (scratchSInv.m02 + scratchSInv.m20) * 0.5
        scratchS.m10 = scratchS.m01
        scratchS.m11 = scratchSInv.m11 + scratchR.m11 / varianceScaleY
        scratchS.m12 = (scratchSInv.m12 + scratchSInv.m21) * 0.5
        scratchS.m20 = scratchS.m02
        scratchS.m21 = scratchS.m12
        scratchS.m22 = scratchSInv.m22 + scratchR.m22 / varianceScaleHeading

        // S must be symmetric positive definite. Cholesky factorization is more
        // numerically stable than an adjugate/determinant inverse and rejects an
        // invalid covariance before it can produce a plausible-looking Kalman gain.
        val l00Squared = scratchS.m00
        if (!l00Squared.isFinite() || l00Squared <= 0.0) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "non_positive_definite_innovation_covariance"
            return state
        }
        val l00 = kotlin.math.sqrt(l00Squared)
        val l10 = scratchS.m10 / l00
        val l20 = scratchS.m20 / l00
        val l11Squared = scratchS.m11 - l10 * l10
        if (!l11Squared.isFinite() || l11Squared <= 0.0) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "non_positive_definite_innovation_covariance"
            return state
        }
        val l11 = kotlin.math.sqrt(l11Squared)
        val l21 = (scratchS.m21 - l20 * l10) / l11
        val l22Squared = scratchS.m22 - l20 * l20 - l21 * l21
        if (!l22Squared.isFinite() || l22Squared <= 0.0) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "non_positive_definite_innovation_covariance"
            return state
        }
        val l22 = kotlin.math.sqrt(l22Squared)

        val rawHeadingDiff = measuredHeading - baseEntry.headingRad
        val headingDiff = if (rawHeadingDiff.isFinite()) wrapAngle(rawHeadingDiff)
            else wrapAngle(wrapAngle(measuredHeading) - wrapAngle(baseEntry.headingRad))

        val yX = measurement.targetPose.x - baseEntry.x
        val yY = measurement.targetPose.y - baseEntry.y
        val yZ = headingDiff

        // Whiten the residual with L before computing its squared norm. This avoids
        // cancellation in y^T S^-1 y, and rejected observations need no inverse.
        val whitenedX = (yX / axisX) / l00
        val whitenedY = (yY / axisY - l10 * whitenedX) / l11
        val whitenedHeading = (yZ / axisHeading - l20 * whitenedX - l21 * whitenedY) / l22
        val dMSquared = whitenedX * whitenedX + whitenedY * whitenedY + whitenedHeading * whitenedHeading
        state.lastNormalizedInnovationSquared = if (dMSquared.isFinite() && dMSquared >= 0.0) dMSquared else 0.0
        if (!dMSquared.isFinite() || dMSquared < -1e-9 ||
            (useMahalanobisRejection && dMSquared > mahalanobisThreshold)) {
                state.lastMeasurementAccepted = false
                state.lastRejectionReason = if (!dMSquared.isFinite() || dMSquared < -1e-9) "invalid_innovation" else "mahalanobis_rejected"
                state.lastInnovationX = yX
                state.lastInnovationY = yY
                state.lastInnovationTheta = yZ
                return state
        }

        // Solve S K^T = P^T directly. No explicit inverse or products of three large pivots.
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

        // Restore K = D K_normalized D^-1. Sequential multiply/divide avoids an
        // overflowing scale ratio, including zero cross gains between extreme axes.
        scratchK.m01 = scratchK.m01 * axisX / axisY
        scratchK.m02 = scratchK.m02 * axisX / axisHeading
        scratchK.m10 = scratchK.m10 * axisY / axisX
        scratchK.m12 = scratchK.m12 * axisY / axisHeading
        scratchK.m20 = scratchK.m20 * axisHeading / axisX
        scratchK.m21 = scratchK.m21 * axisHeading / axisY

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

        val sym00 = pn00.coerceAtLeast(0.0)
        val sym01 = pn01 * 0.5 + pn10 * 0.5
        val sym02 = pn02 * 0.5 + pn20 * 0.5

        val sym11 = pn11.coerceAtLeast(0.0)
        val sym12 = pn12 * 0.5 + pn21 * 0.5

        val sym22 = pn22.coerceAtLeast(0.0)

        scratchCov.m00 = sym00; scratchCov.m01 = sym01; scratchCov.m02 = sym02
        scratchCov.m10 = sym01; scratchCov.m11 = sym11; scratchCov.m12 = sym12
        scratchCov.m20 = sym02; scratchCov.m21 = sym12; scratchCov.m22 = sym22
        if (!dxX.isFinite() || !dxY.isFinite() || !dxZ.isFinite() || !validCovariance(scratchCov)) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "invalid_correction"
            return state
        }

        state.history.copyInto(scratchHistory)
        var replayIndex = closestIndex
        if (intervalFraction > 0.0) {
            // Prepare the capture-time split so history remains internally consistent
            // for the next camera frame. The following entry retains only the remaining
            // odometry motion and process noise after image capture.
            replayIndex = scratchHistory.insertEntryDirect(
                closestIndex + 1,
                baseEntry.timestampMs,
                baseEntry.x,
                baseEntry.y,
                baseEntry.headingRad,
                baseEntry.covariance,
                baseEntry.qScale,
                baseEntry.deltaXRobot,
                baseEntry.deltaYRobot,
                baseEntry.deltaHeadingRad,
                baseEntry.hasMotion,
                baseEntry.effectiveQHeadingScale
            )
            if (replayIndex + 1 < scratchHistory.size) {
                val remainingFraction = 1.0 - intervalFraction
                val followingEntry = scratchHistory[replayIndex + 1]
                val remainderScale = remainingFraction / intervalFraction
                followingEntry.deltaXRobot = baseEntry.deltaXRobot * remainderScale
                followingEntry.deltaYRobot = baseEntry.deltaYRobot * remainderScale
                followingEntry.deltaHeadingRad = baseEntry.deltaHeadingRad * remainderScale
                followingEntry.qScale = baseEntry.qScale * remainderScale
                followingEntry.qHeadingScale = baseEntry.effectiveQHeadingScale * remainderScale
                followingEntry.hasMotion = remainingFraction > 0.0
            }
        }

        if (!EKFStatePropagator.tryRepropagateHistory(
            state, scratchHistory, replayIndex, baseEntry,
            dxX, dxY, dxZ,
            scratchCov, baseQ,
            scratchHistory, scratchCov2
        )) {
            state.lastMeasurementAccepted = false
            state.lastRejectionReason = "invalid_replay"
            return state
        }

        scratchHistory.copyInto(state.history)

        val kg = state.lastKalmanGain
        kg[0] = scratchK.m00; kg[1] = scratchK.m01; kg[2] = scratchK.m02
        kg[3] = scratchK.m10; kg[4] = scratchK.m11; kg[5] = scratchK.m12
        kg[6] = scratchK.m20; kg[7] = scratchK.m21; kg[8] = scratchK.m22

        state.lastInnovationX = yX
        state.lastInnovationY = yY
        state.lastInnovationTheta = yZ
        state.lastMeasurementAccepted = true
        state.lastVisionTimestampMs = measurement.timestampMs
        state.lastRejectionReason = null

        return state
    }

    /** Correlation normalization checks each axis independently, including exact zero-variance axes. */
    private fun validCovariance(p: Matrix3x3): Boolean {
        if (!p.m00.isFinite() || !p.m01.isFinite() || !p.m02.isFinite() ||
            !p.m10.isFinite() || !p.m11.isFinite() || !p.m12.isFinite() ||
            !p.m20.isFinite() || !p.m21.isFinite() || !p.m22.isFinite() ||
            p.m00 < 0.0 || p.m11 < 0.0 || p.m22 < 0.0) return false
        if (p.m00 == 0.0 && (p.m01 != 0.0 || p.m10 != 0.0 || p.m02 != 0.0 || p.m20 != 0.0)) return false
        if (p.m11 == 0.0 && (p.m01 != 0.0 || p.m10 != 0.0 || p.m12 != 0.0 || p.m21 != 0.0)) return false
        if (p.m22 == 0.0 && (p.m02 != 0.0 || p.m20 != 0.0 || p.m12 != 0.0 || p.m21 != 0.0)) return false
        val x = kotlin.math.sqrt(p.m00)
        val y = kotlin.math.sqrt(p.m11)
        val z = kotlin.math.sqrt(p.m22)
        val xy = if (x == 0.0 || y == 0.0) 0.0 else (p.m01 / x) / y
        val xz = if (x == 0.0 || z == 0.0) 0.0 else (p.m02 / x) / z
        val yz = if (y == 0.0 || z == 0.0) 0.0 else (p.m12 / y) / z
        if (!xy.isFinite() || !xz.isFinite() || !yz.isFinite() ||
            kotlin.math.abs(xy) > 1.0 + 1e-12 || kotlin.math.abs(xz) > 1.0 + 1e-12 ||
            kotlin.math.abs(yz) > 1.0 + 1e-12) return false
        if (x != 0.0 && y != 0.0 && kotlin.math.abs(xy - (p.m10 / x) / y) > 1e-12) return false
        if (x != 0.0 && z != 0.0 && kotlin.math.abs(xz - (p.m20 / x) / z) > 1e-12) return false
        if (y != 0.0 && z != 0.0 && kotlin.math.abs(yz - (p.m21 / y) / z) > 1e-12) return false
        val determinant = 1.0 + 2.0 * xy * xz * yz - xy * xy - xz * xz - yz * yz
        return determinant.isFinite() && determinant >= -1e-12
    }
}
