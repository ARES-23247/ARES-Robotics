package com.areslib.control.drivetrain

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import com.areslib.math.wrapAngle
import com.areslib.util.RobotClock
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/**
 * Closed-Loop Autonomous Alignment Controller for AprilTag Scoring Targets.
 *
 * Transforms target-space camera measurements into robot-centric translational and rotational control demands.
 * Includes low-pass exponential filtering for vision noise mitigation, rate-of-change jump filtering for PnP pose flips,
 * deadband bounds to prevent limit-cycle jitter, P-D + $k_S$ friction compensation for heading control, and automated sweep search behaviors when tag tracking is temporarily lost.
 *
 * ### Target-Space & Coordinate Transformation Mathematics:
 * In Limelight target space ($Z$ forward depth, $X$ right offset):
 * $$e_{forward} = |Z| - d_{target}, \quad e_{left} = X$$
 * $$\phi = -\text{rotation.y} \quad \text{(Robot heading yaw in target space, CCW-positive)}$$
 * Robot-centric coordinate frame error rotation:
 * $$\begin{bmatrix} e_X \\ e_Y \end{bmatrix} = \begin{bmatrix} \cos\phi & \sin\phi \\ -\sin\phi & \cos\phi \end{bmatrix} \begin{bmatrix} e_{forward} \\ e_{left} \end{bmatrix}$$
 * Target-pointing heading error for camera FOV centering:
 * $$e_{\theta} = \text{wrap}\left(\text{atan2}(e_{left}, Z) - \phi\right)$$
 * Rotational Control Law (P-D + $k_S$ static friction feedforward):
 * $$u_{\omega} = \text{coerce}\left(K_p \cdot e_{\theta} + K_d \frac{\Delta e_{\theta}}{\Delta t} + \text{sign}(e_{\theta}) \cdot k_S, -\omega_{max}, \omega_{max}\right)$$
 *
 * ### Physical Units & Coordinate System:
 * - Target Distance ($d_{target}, Z$): Meters ($m$)
 * - Lateral Target Offset ($X$): Meters ($m$)
 * - Robot Yaw ($\phi, e_{\theta}$): Radians ($rad$), counter-clockwise positive
 * - Linear Velocity Commands ($u_X, u_Y$): Meters per second ($m/s$) or normalized duty cycle
 * - Angular Velocity Command ($u_{\omega}$): Radians per second ($rad/s$)
 * - Data Freshness Threshold: $\le 250$ milliseconds ($ms$)
 *
 * @see RobotState
 * @see RobotAction.JoystickDriveIntent
 */
class VisionAlignController {
    private var hasPrevFiltered = false
    private var prevRawYaw = 0.0
    private var prevErrX = 0.0
    private var prevErrY = 0.0
    private var prevErrHeading = 0.0
    private var prevErrHeadingForD = 0.0
    private var prevLoopTimeMs = RobotClock.currentTimeMillis()
    private var integralAccum = 0.0

    // Tag search state
    private var lastKnownSearchDirection = 0.0 // +1.0 = rotate CCW, -1.0 = rotate CW
    private var tagLostTimestampMs = 0L
    private var wasTrackingTag = false

    /**
     * Calculates the required driver intent to align the robot with the specified target AprilTag.
     *
     * @param state Immutable Redux [RobotState] containing latest vision measurements and tuning constants.
     * @param targetTagId Numerical ID of the target AprilTag (e.g. 1 to 24).
     * @param isAlignmentRequested `true` if the driver alignment trigger button is actively held down; `false` otherwise.
     * @return Commanded [RobotAction.JoystickDriveIntent] containing closed-loop alignment velocities, or `null` if alignment is disabled.
     */
    fun calculate(state: RobotState, targetTagId: Int, isAlignmentRequested: Boolean, imuPitch: Double = 0.0): RobotAction.JoystickDriveIntent? {
        if (!isAlignmentRequested) {
            // Reset state when button is released
            wasTrackingTag = false
            tagLostTimestampMs = 0L
            hasPrevFiltered = false
            prevErrHeadingForD = 0.0
            prevLoopTimeMs = RobotClock.currentTimeMillis()
            integralAccum = 0.0
            return null
        }

        val now = RobotClock.currentTimeMillis()
        
        // Require reasonably fresh data (<= 250ms) for active closed-loop control
        var activeMeasurement: com.areslib.state.VisionMeasurement? = null
        for (i in 0 until state.vision.measurements.size) {
            val measurement = state.vision.measurements[i]
            if (measurement.tagId == targetTagId && (now - measurement.timestampMs) < 250L) {
                activeMeasurement = measurement
                break
            }
        }

        if (activeMeasurement != null) {
            // Tag reacquired — reset search state
            tagLostTimestampMs = 0L
            
            val robotPoseTargetSpace = activeMeasurement.robotPoseTargetSpace
            
            // target-space coordinates (Limelight: Z forward, X right)
            val tuning = state.tuning
            val rawZ = robotPoseTargetSpace.z
            val rawY = robotPoseTargetSpace.y
            val correctedZ = rawZ * cos(imuPitch) - rawY * sin(imuPitch)
            val distanceZ = abs(correctedZ)
            val targetDistanceMeters = tuning.visionAlignTargetDistance
            val errorForwardT = distanceZ - targetDistanceMeters
            val errorLeftT = robotPoseTargetSpace.x
            
            // In target-space: Z+ is outward from tag, Y+ is up.
            // Yaw (robot turning left/right) = rotation around Y axis = rotation.y
            // Negated to match the controller's sign convention (positive = CCW)
            val robotYaw = -robotPoseTargetSpace.rotation.y
            val wrappedYaw = wrapAngle(robotYaw)
            
            // 1. Yaw rate-of-change sanity check (reject PnP flips/jumps)
            val maxHeadingChange = tuning.visionAlignMaxHeadingChangeRad
            val sanitizedYaw = if (hasPrevFiltered) {
                val diff = wrapAngle(wrappedYaw - prevRawYaw)
                if (abs(diff) > maxHeadingChange) prevRawYaw else wrappedYaw
            } else {
                wrappedYaw
            }
            prevRawYaw = sanitizedYaw
            
            val phi = sanitizedYaw
            // Rotate translation errors into robot-centric frame using the correct -phi rotation matrix
            val errX = errorForwardT * cos(phi) + errorLeftT * sin(phi)
            val errY = -errorForwardT * sin(phi) + errorLeftT * cos(phi)
            
            // Heading goal: rotate to keep the tag centered in the camera FOV
            val pointingTarget = atan2(errorLeftT, distanceZ)
            val errHeading = wrapAngle(pointingTarget - phi)
            
            // 2. Low-pass filters to smooth out high-frequency vision noise
            val alphaTranslation = tuning.visionAlignAlphaTranslation
            val alphaHeading = tuning.visionAlignAlphaHeading
            
            val hadPreviousMeasurement = hasPrevFiltered
            val errXFiltered = if (hadPreviousMeasurement) alphaTranslation * errX + (1.0 - alphaTranslation) * prevErrX else errX
            val errYFiltered = if (hadPreviousMeasurement) alphaTranslation * errY + (1.0 - alphaTranslation) * prevErrY else errY
            
            val errHeadingFiltered = if (hadPreviousMeasurement) {
                val diff = wrapAngle(errHeading - prevErrHeading)
                wrapAngle(prevErrHeading + alphaHeading * diff)
            } else {
                errHeading
            }
            
            prevErrX = errXFiltered
            prevErrY = errYFiltered
            prevErrHeading = errHeadingFiltered
            
            val kP_translation = tuning.visionAlignKpTranslation
            val kP_rotation = tuning.visionAlignKpRotation
            val kD_rotation = tuning.visionAlignKdRotation
            
            // 3. Apply deadbands to prevent limit-cycle oscillations (jittering)
            val translationDeadband = tuning.visionAlignTranslationDeadband
            val headingErrorDeadband = tuning.visionAlignHeadingErrorDeadband
            
            // Speed-limit translation commands to keep the tag in the camera's FOV
            var ctrlX = if (abs(errXFiltered) > translationDeadband) {
                errXFiltered * kP_translation
            } else 0.0
            
            var ctrlY = if (abs(errYFiltered) > translationDeadband) {
                errYFiltered * kP_translation
            } else 0.0

            val maxClamp = tuning.visionAlignClampTranslationX
            val magnitude = kotlin.math.hypot(ctrlX, ctrlY)
            if (magnitude > maxClamp) {
                val scale = maxClamp / magnitude
                ctrlX *= scale
                ctrlY *= scale
            }
            
            val kS_rotational = tuning.visionAlignKsRotational
            
            // Compute derivative term: rate of heading error change
            val dtSec = ((now - prevLoopTimeMs).coerceIn(1, 200)) / 1000.0
            prevLoopTimeMs = now
            val headingErrorRate = if (hadPreviousMeasurement) {
                wrapAngle(errHeadingFiltered - prevErrHeadingForD) / dtSec
            } else 0.0
            prevErrHeadingForD = errHeadingFiltered
            hasPrevFiltered = true
            
            val ctrlOmega = if (abs(errHeadingFiltered) > headingErrorDeadband) {
                val currentSign = sign(errHeadingFiltered)
                val activeErr = errHeadingFiltered - currentSign * headingErrorDeadband
                
                integralAccum += activeErr * dtSec
                integralAccum = integralAccum.coerceIn(-0.3, 0.3)
                
                val pTerm = activeErr * kP_rotation
                val iTerm = (tuning.visionAlignKpRotation * 0.1) * integralAccum
                val dTerm = headingErrorRate * kD_rotation
                (pTerm + iTerm + dTerm + currentSign * kS_rotational).coerceIn(-tuning.visionAlignClampRotation, tuning.visionAlignClampRotation)
            } else {
                integralAccum = 0.0
                0.0
            }

            // Update search direction
            when {
                abs(ctrlOmega) > 0.02 -> lastKnownSearchDirection = sign(ctrlOmega)
                abs(ctrlY) > 0.02 -> lastKnownSearchDirection = sign(ctrlY)
            }
            wasTrackingTag = true
            
            return RobotAction.JoystickDriveIntent(
                targetXVelocity = ctrlX,
                targetYVelocity = ctrlY,
                targetAngularVelocity = ctrlOmega,
                isFieldCentric = false
            )
        } else {
            val tuning = state.tuning
            hasPrevFiltered = false
            prevErrHeadingForD = 0.0
            prevLoopTimeMs = RobotClock.currentTimeMillis()
            
            // Tag is not visible while requested — initiate search rotation
            if (tagLostTimestampMs == 0L) {
                tagLostTimestampMs = RobotClock.currentTimeMillis()
                if (!wasTrackingTag) lastKnownSearchDirection = -1.0 // start CW
            }
            
            val firstSweepMs = tuning.visionAlignSearchFirstSweepMs
            val secondSweepMs = tuning.visionAlignSearchSecondSweepMs
            val totalSearchMs = firstSweepMs + secondSweepMs
            val timeSinceLost = RobotClock.currentTimeMillis() - tagLostTimestampMs
            val searchSpeed = tuning.visionAlignSearchSpeed
            
            if (timeSinceLost < totalSearchMs) {
                // Active search
                val currentDirection = if (timeSinceLost < firstSweepMs) lastKnownSearchDirection else -lastKnownSearchDirection
                val searchOmega = currentDirection * searchSpeed
                return RobotAction.JoystickDriveIntent(
                    targetXVelocity = 0.0,
                    targetYVelocity = 0.0,
                    targetAngularVelocity = searchOmega,
                    isFieldCentric = false
                )
            } else {
                // Both sweeps exhausted — stop
                return RobotAction.JoystickDriveIntent(
                    targetXVelocity = 0.0,
                    targetYVelocity = 0.0,
                    targetAngularVelocity = 0.0,
                    isFieldCentric = false
                )
            }
        }
    }
}
