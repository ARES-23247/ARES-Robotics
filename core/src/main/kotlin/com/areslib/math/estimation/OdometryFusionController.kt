package com.areslib.math.estimation

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.math.geometry.Matrix3x3

/**
 * Drivetrain Wheel Odometry and Gyroscope Fusion Controller.
 *
 * Integrates high-frequency dead-wheel displacement deltas with IMU gyro heading rates, performing tilt hysteresis beaching detection,
 * dynamic process noise covariance expansion during wheel slip or high-tilt impacts, and online gyro bias estimation.
 *
 * ### Mathematical Formulations:
 * 1. **Beaching & Tilt Angle Metric**:
 *    $$\theta_{\text{tilt}} = \sqrt{\theta_{\text{pitch}}^2 + \theta_{\text{roll}}^2}$$
 *    - Enters beached state if $\theta_{\text{tilt}} > 15^\circ$; exits if $\theta_{\text{tilt}} < 12^\circ$ (hysteresis protection).
 * 2. **Tilt Covariance Scale ($s_{\text{tilt}}$)**:
 *    For $\theta_{\text{tilt}} > 5^\circ$, scales process noise quadratic expansion up to $100\times$:
 *    $$s_{\text{tilt}} = 1.0 + 99.0 \cdot \left[\text{clamp}\left(\frac{\theta_{\text{tilt}} - 5^\circ}{10^\circ}, 0, 1\right)\right]^2$$
 * 3. **Online Gyro Bias IIR Filter**:
 *    Estimates gyro zero-rate drift when stationary ($\Delta x = \Delta y = \Delta\theta = 0$):
 *    $$\beta_k = (1 - \alpha) \cdot \beta_{k-1} + \alpha \cdot \omega_{\text{gyro}}, \quad \alpha = 0.01 \cdot \Delta t$$
 * 4. **Wheel Slip Mismatch Detection**:
 *    If $|\frac{\Delta\theta}{\Delta t} - (\omega_{\text{gyro}} - \beta_k)| > 0.5 \text{ rad/s}$, applies $s_{\text{slip}} = 10.0$ covariance multiplier.
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position & Displacements $(\Delta x, \Delta y)$: Meters ($m$)
 * - Heading & Angular Rates $(\Delta\theta, \omega_{\text{gyro}}, \beta)$: Radians ($rad$), Radians per second ($rad/s$), **CCW-positive**
 * - IMU Tilt Angles & Velocities: Degrees ($^\circ$), Degrees per second ($^\circ/s$)
 * - Loop Time ($\Delta t$): Seconds ($s$), Timestamp ($t$): Milliseconds ($ms$)
 *
 * ### Zero-GC Guarantee:
 * Operates entirely via in-place mutation of caller-provided scratchpads (`scratchQ`, `scratchCov`), maintaining zero dynamic heap allocations.
 *
 * @see PoseEstimator
 * @see EKFStatePropagator
 */
object OdometryFusionController {

    /**
     * Processes raw odometry translation vector, heading delta, and IMU telemetry to update EKF state.
     *
     * @param state Active pose estimator state snapshot.
     * @param timestampMs System timestamp in milliseconds ($ms$).
     * @param deltaTranslation Robot-centric displacement vector in meters ($m$).
     * @param deltaHeading Robot-centric heading change in radians ($rad$).
     * @param pitchDegrees IMU pitch angle in degrees ($^\circ$).
     * @param rollDegrees IMU roll angle in degrees ($^\circ$).
     * @param pitchVelocityDegPerSec IMU pitch rate in degrees per second ($^\circ/s$).
     * @param rollVelocityDegPerSec IMU roll rate in degrees per second ($^\circ/s$).
     * @param gyroRateRadPerSec Raw IMU yaw rate in radians per second ($rad/s$).
     * @param dtSeconds Elapsed time since last update cycle in seconds ($\Delta t$).
     * @param baseQ Baseline process noise covariance matrix $\mathbf{Q}$.
     * @param scratchQ Pre-allocated scratchpad matrix for scaled process noise $\mathbf{Q}$.
     * @param scratchCov Pre-allocated scratchpad matrix for updated state covariance $\mathbf{P}$.
     * @return Updated [PoseEstimatorState].
     */
    fun processOdometry(
        state: PoseEstimatorState,
        timestampMs: Long,
        deltaTranslation: Translation2d,
        deltaHeading: Rotation2d,
        pitchDegrees: Double,
        rollDegrees: Double,
        pitchVelocityDegPerSec: Double,
        rollVelocityDegPerSec: Double,
        gyroRateRadPerSec: Double,
        dtSeconds: Double,
        applyGyroBiasCorrection: Boolean = true,
        baseQ: Matrix3x3,
        scratchQ: Matrix3x3,
        scratchCov: Matrix3x3
    ): PoseEstimatorState {
        return processOdometryDirect(
            state, timestampMs, deltaTranslation.x, deltaTranslation.y, deltaHeading.radians,
            pitchDegrees, rollDegrees, pitchVelocityDegPerSec, rollVelocityDegPerSec,
            gyroRateRadPerSec, dtSeconds, applyGyroBiasCorrection, baseQ, scratchQ, scratchCov
        )
    }

    /**
     * Processes primitive scalar odometry deltas and IMU telemetry to update EKF state with zero dynamic allocations.
     *
     * @param state Active pose estimator state snapshot.
     * @param timestampMs System timestamp in milliseconds ($ms$).
     * @param deltaX Local X displacement in meters ($m$).
     * @param deltaY Local Y displacement in meters ($m$).
     * @param deltaHeadingRad Local heading change in radians ($rad$).
     * @param pitchDegrees IMU pitch in degrees ($^\circ$).
     * @param rollDegrees IMU roll in degrees ($^\circ$).
     * @param pitchVelocityDegPerSec IMU pitch rate in degrees per second ($^\circ/s$).
     * @param rollVelocityDegPerSec IMU roll rate in degrees per second ($^\circ/s$).
     * @param gyroRateRadPerSec Raw IMU yaw rate in radians per second ($rad/s$).
     * @param dtSeconds Elapsed time in seconds ($\Delta t$).
     * @param baseQ Baseline process noise matrix $\mathbf{Q}$.
     * @param scratchQ Pre-allocated scratchpad matrix for scaled $\mathbf{Q}$.
     * @param scratchCov Pre-allocated scratchpad matrix for updated $\mathbf{P}$.
     * @return Updated [PoseEstimatorState].
     */
    fun processOdometryDirect(
        state: PoseEstimatorState,
        timestampMs: Long,
        deltaX: Double,
        deltaY: Double,
        deltaHeadingRad: Double,
        pitchDegrees: Double,
        rollDegrees: Double,
        pitchVelocityDegPerSec: Double,
        rollVelocityDegPerSec: Double,
        gyroRateRadPerSec: Double,
        dtSeconds: Double,
        applyGyroBiasCorrection: Boolean = true,
        baseQ: Matrix3x3,
        scratchQ: Matrix3x3,
        scratchCov: Matrix3x3
    ): PoseEstimatorState {

        if (deltaX.isNaN() || deltaX.isInfinite() ||
            deltaY.isNaN() || deltaY.isInfinite() ||
            deltaHeadingRad.isNaN() || deltaHeadingRad.isInfinite() ||
            pitchDegrees.isNaN() || pitchDegrees.isInfinite() ||
            rollDegrees.isNaN() || rollDegrees.isInfinite() ||
            pitchVelocityDegPerSec.isNaN() || pitchVelocityDegPerSec.isInfinite() ||
            rollVelocityDegPerSec.isNaN() || rollVelocityDegPerSec.isInfinite() ||
            gyroRateRadPerSec.isNaN() || gyroRateRadPerSec.isInfinite() ||
            dtSeconds.isNaN() || dtSeconds.isInfinite()
        ) {
            return state
        }
        if (dtSeconds <= 0.0) return state

        val tiltDegrees = kotlin.math.sqrt(pitchDegrees * pitchDegrees + rollDegrees * rollDegrees)
        val tiltVelocity = kotlin.math.sqrt(pitchVelocityDegPerSec * pitchVelocityDegPerSec + rollVelocityDegPerSec * rollVelocityDegPerSec)

        var currentlyBeached = state.isBeached
        var unbeachedTime = state.lastUnbeachedTimeMs

        // Hysteresis logic
        when {
            !currentlyBeached && tiltDegrees > 15.0 -> {
                currentlyBeached = true
            }
            currentlyBeached && tiltDegrees < 12.0 -> {
                currentlyBeached = false
                unbeachedTime = timestampMs
            }
        }

        // Catastrophic tilt / beaching check: Freeze odometry updates
        if (currentlyBeached) {
            // No state or covariance propagation occurred for this sample, so a later
            // rewind must not inject process noise while replaying it.
            state.history.addEntryDirect(
                timestampMs,
                state.estimatedPoseX,
                state.estimatedPoseY,
                state.estimatedPoseHeading,
                state.covariance,
                0.0
            )
            state.isBeached = true
            state.lastUnbeachedTimeMs = unbeachedTime
            return state
        }

        val timeSinceUnbeachedMs = timestampMs - unbeachedTime
        val inRecovery = timeSinceUnbeachedMs < 500 && unbeachedTime != 0L

        // Continuous covariance scaling
        var tiltScale = 1.0
        if (tiltDegrees > 5.0) {
            val normalized = (tiltDegrees - 5.0) / 10.0 // 0.0 to 1.0
            val clamped = normalized.coerceIn(0.0, 1.0)
            tiltScale = 1.0 + 99.0 * (clamped * clamped)
        }

        // Impact prediction
        if (tiltVelocity > 20.0) {
            tiltScale = kotlin.math.max(tiltScale, 50.0)
        }

        // Post-beaching recovery forces max scale
        if (inRecovery) {
            tiltScale = 100.0
        }

        // Learn Control Hub gyro bias only after a thresholded stationary dwell. Pinpoint
        // already fuses its own IMU with dead wheels, so applying this correction to a
        // Pinpoint heading would count gyro information twice.
        val translationSpeed = kotlin.math.hypot(deltaX, deltaY) / dtSeconds
        val odometryYawRate = kotlin.math.abs(deltaHeadingRad) / dtSeconds
        val isStationary = translationSpeed < 0.02 &&
            odometryYawRate < 0.03 && kotlin.math.abs(gyroRateRadPerSec) < 0.08
        val stationarySince = when {
            !isStationary -> 0L
            state.stationarySinceMs == 0L -> timestampMs
            else -> state.stationarySinceMs
        }
        val stationaryDwellComplete = isStationary && timestampMs - stationarySince >= 500L
        val alpha = 1.0 - kotlin.math.exp(-dtSeconds / 5.0)
        val newBias = if (applyGyroBiasCorrection && stationaryDwellComplete) {
            state.gyroBiasRadPerSec * (1.0 - alpha) + gyroRateRadPerSec * alpha
        } else {
            state.gyroBiasRadPerSec
        }

        val correctedGyroRate = gyroRateRadPerSec - newBias
        val correctedDeltaHeading = if (applyGyroBiasCorrection && stationaryDwellComplete) {
            0.0
        } else if (applyGyroBiasCorrection) {
            deltaHeadingRad - newBias * dtSeconds
        } else {
            deltaHeadingRad
        }

        // Gyro rate mismatch check for wheel slippage detection
        val expectedHeadingVel = deltaHeadingRad / (if (dtSeconds > 1e-6) dtSeconds else 0.02)
        val slipThreshold = kotlin.math.max(0.5, kotlin.math.abs(correctedGyroRate) * 0.15)
        val slipScale = if (correctedGyroRate != 0.0 && kotlin.math.abs(expectedHeadingVel - correctedGyroRate) > slipThreshold) {
            10.0 // Dynamic wheel slippage covariance expansion
        } else {
            1.0
        }

        scratchQ.setTo(baseQ)
        val speed = kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY) / (if (dtSeconds > 1e-6) dtSeconds else 0.02)
        val translationMovementScale = if (isStationary) 0.001 else kotlin.math.max(0.001, speed)
        // Route calibration normalizes heading error by distance + rotation. This lets a turn-in-place
        // grow heading covariance without pretending that translation uncertainty grew equally.
        val headingMovementScale = if (isStationary) 0.001 else
            kotlin.math.max(0.001, speed + kotlin.math.abs(correctedGyroRate))
        val translationProcessNoiseScale = tiltScale * slipScale * translationMovementScale * dtSeconds
        val headingProcessNoiseScale = tiltScale * slipScale * headingMovementScale * dtSeconds
        val crossProcessNoiseScale = kotlin.math.sqrt(
            translationProcessNoiseScale.coerceAtLeast(0.0) *
                headingProcessNoiseScale.coerceAtLeast(0.0)
        )
        scratchQ.m00 *= translationProcessNoiseScale
        scratchQ.m01 *= translationProcessNoiseScale
        scratchQ.m10 *= translationProcessNoiseScale
        scratchQ.m11 *= translationProcessNoiseScale
        scratchQ.m02 *= crossProcessNoiseScale
        scratchQ.m12 *= crossProcessNoiseScale
        scratchQ.m20 *= crossProcessNoiseScale
        scratchQ.m21 *= crossProcessNoiseScale
        scratchQ.m22 *= headingProcessNoiseScale

        val slipWeight = if (applyGyroBiasCorrection && slipScale >= 10.0) 0.8 else 0.0
        val blendedDeltaHeading = (1.0 - slipWeight) * correctedDeltaHeading + slipWeight * (correctedGyroRate * dtSeconds)
        val newHeadingRad = com.areslib.math.wrapAngle(state.estimatedPoseHeading + blendedDeltaHeading)

        // The odometry input is already robot-local. Integrate its SE(2) twist into an
        // exact constant-curvature arc before rotating that arc into the field frame.
        val cosEst = kotlin.math.cos(state.estimatedPoseHeading)
        val sinEst = kotlin.math.sin(state.estimatedPoseHeading)

        // SE(2) Lie Group Pose Exponential Integration (Twist2d -> Pose2d exact
        // constant-curvature arc integration).
        val dTheta = blendedDeltaHeading
        val s: Double
        val c: Double
        if (kotlin.math.abs(dTheta) < 1e-6) {
            s = 1.0 - (dTheta * dTheta) / 6.0
            c = dTheta * 0.5
        } else {
            s = kotlin.math.sin(dTheta) / dTheta
            c = (1.0 - kotlin.math.cos(dTheta)) / dTheta
        }

        // Arc displacement in robot frame
        val dxArc = s * deltaX - c * deltaY
        val dyArc = c * deltaX + s * deltaY

        // Transform the robot-frame arc displacement into the field frame using the
        // pre-update heading.
        val fieldDx = dxArc * cosEst - dyArc * sinEst
        val fieldDy = dxArc * sinEst + dyArc * cosEst

        val newX = state.estimatedPoseX + fieldDx
        val newY = state.estimatedPoseY + fieldDy
        
        val newCovariance = scratchCov

        // The covariance Jacobian must linearize the same exact arc displacement used
        // above. Using the raw twist at a midpoint heading is only an approximation and
        // diverges from the state transition for finite rotations.
        EKFStatePropagator.propagate(
            state.covarianceArray,
            dxArc,
            dyArc,
            state.estimatedPoseHeading,
            scratchQ,
            newCovariance
        )
        
        state.covarianceArray[0] = newCovariance.m00
        state.covarianceArray[1] = newCovariance.m01
        state.covarianceArray[2] = newCovariance.m02
        state.covarianceArray[3] = newCovariance.m10
        state.covarianceArray[4] = newCovariance.m11
        state.covarianceArray[5] = newCovariance.m12
        state.covarianceArray[6] = newCovariance.m20
        state.covarianceArray[7] = newCovariance.m21
        state.covarianceArray[8] = newCovariance.m22

        state.history.addEntryDirect(
            timestampMs,
            newX,
            newY,
            newHeadingRad,
            newCovariance,
            translationProcessNoiseScale,
            deltaX,
            deltaY,
            blendedDeltaHeading,
            true,
            qHeadingScale = headingProcessNoiseScale
        )

        state.estimatedPoseX = newX
        state.estimatedPoseY = newY
        state.estimatedPoseHeading = newHeadingRad
        state.isBeached = currentlyBeached
        state.lastUnbeachedTimeMs = unbeachedTime
        state.gyroBiasRadPerSec = newBias
        state.stationarySinceMs = stationarySince

        return state
    }
}
