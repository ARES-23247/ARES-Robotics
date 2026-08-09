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
        baseQ: Matrix3x3,
        scratchQ: Matrix3x3,
        scratchCov: Matrix3x3
    ): PoseEstimatorState {
        return processOdometryDirect(
            state, timestampMs, deltaTranslation.x, deltaTranslation.y, deltaHeading.radians,
            pitchDegrees, rollDegrees, pitchVelocityDegPerSec, rollVelocityDegPerSec,
            gyroRateRadPerSec, dtSeconds, baseQ, scratchQ, scratchCov
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
            val poseForHistory = Pose2d(state.estimatedPoseX, state.estimatedPoseY, Rotation2d(state.estimatedPoseHeading))
            val covForHistory = Matrix3x3(
                state.covarianceArray[0], state.covarianceArray[1], state.covarianceArray[2],
                state.covarianceArray[3], state.covarianceArray[4], state.covarianceArray[5],
                state.covarianceArray[6], state.covarianceArray[7], state.covarianceArray[8]
            )
            state.history.addEntry(timestampMs, poseForHistory, covForHistory, 1.0)
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

        // Online Gyro Bias Estimation & Bias Correction
        val isStationary = deltaX == 0.0 && deltaY == 0.0 && deltaHeadingRad == 0.0
        val alpha = 0.01 * dtSeconds
        val newBias = if (isStationary && gyroRateRadPerSec != 0.0) {
            state.gyroBiasRadPerSec * (1.0 - alpha) + gyroRateRadPerSec * alpha
        } else {
            state.gyroBiasRadPerSec
        }

        val correctedGyroRate = gyroRateRadPerSec - newBias
        val correctedDeltaHeading = if (isStationary) {
            0.0
        } else {
            deltaHeadingRad - newBias * dtSeconds
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
        val movementScale = if (isStationary) 0.001 else kotlin.math.max(0.001, speed)
        scratchQ.multiplyInPlace(tiltScale * slipScale * movementScale * dtSeconds)

        val newX = state.estimatedPoseX + deltaX
        val newY = state.estimatedPoseY + deltaY
        
        val slipWeight = if (slipScale >= 10.0) 0.8 else 0.0
        val blendedDeltaHeading = (1.0 - slipWeight) * correctedDeltaHeading + slipWeight * (correctedGyroRate * dtSeconds)
        val newHeadingRad = com.areslib.math.wrapAngle(state.estimatedPoseHeading + blendedDeltaHeading)
        
        val thetaMid = state.estimatedPoseHeading + blendedDeltaHeading * 0.5
        val cosEst = kotlin.math.cos(state.estimatedPoseHeading)
        val sinEst = kotlin.math.sin(state.estimatedPoseHeading)
        val robotLocalDx =  deltaX * cosEst + deltaY * sinEst
        val robotLocalDy = -deltaX * sinEst + deltaY * cosEst
        
        val newCovariance = scratchCov

        EKFStatePropagator.propagate(
            state.covarianceArray,
            robotLocalDx,
            robotLocalDy,
            thetaMid,
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

        state.history.addEntryDirect(timestampMs, newX, newY, newHeadingRad, newCovariance, tiltScale * slipScale)

        state.estimatedPoseX = newX
        state.estimatedPoseY = newY
        state.estimatedPoseHeading = newHeadingRad
        state.isBeached = currentlyBeached
        state.lastUnbeachedTimeMs = unbeachedTime
        state.gyroBiasRadPerSec = newBias

        return state
    }
}
