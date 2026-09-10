package org.aresfirst.marvin.marvin

import com.areslib.Store
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Translation2d
import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.control.assist.ShotResult
import com.areslib.control.assist.ShotSetup

/**
 * Facade providing high-level operational commands for the shooter superstructure.
 *
 * This subsystem controller orchestrates multiple underlying controllers (flywheel,
 * cowl, feeder) to deliver a single-responsibility interface for aiming and firing.
 * 
 * SOTM uses measured field-frame chassis velocity rather than joystick intent. Field
 * positions are meters, headings are CCW-positive radians, flywheel targets are RPM,
 * and every cowl value is a mechanism rotation. Rearward-facing aim follows
 * [MarvinConfig.SHOT_CONFIG]. Feeding remains closed until heading, fresh RPM, and fresh cowl
 * position gates all pass.
 *
 * **Performance Guarantees:**
 * - Mutable [scratchSpeeds] and caller-owned [ShotResult] keep periodic calculation allocation-free.
 *
 * @param store The central Redux-style store containing global robot state.
 */
class MarvinShooterSubsystem(private val store: Store) {
    private val shotSetup = ShotSetup(MarvinConfig.SHOT_CONFIG)
    
    private val flywheelController = MarvinFlywheelController(store)
    private val cowlController = MarvinCowlController(store)
    private val feederController = MarvinFeederController(store)
    
    private val scratchSpeeds = ChassisSpeeds(0.0, 0.0, 0.0)
    private val staticShotResult = ShotResult()
    
    private var lastVx = 0.0
    private var lastVy = 0.0
    private var lastVTimeMs = 0L
    private var hasMotionSample = false
    private var accelerationX = 0.0
    private var accelerationY = 0.0

    /** Cancels transfer ownership and clears feeder/floor outputs for trigger release. */
    fun cancelTransfer() {
        resetMotionSample()
        feederController.cancelTransfer()
    }

    /**
     * Calculates SOTM parameters from measured field-frame motion, dispatches shooter
     * targets/interlocks, and returns a chassis omega command in radians per second.
     *
     * [shotResult] is populated in place for telemetry/caller inspection.
     */
    fun updateShootOnTheMove(
        currentPose: Pose2d,
        targetTranslation: Translation2d,
        shotResult: ShotResult,
        runFloorRollers: Boolean = false
    ): Double {
        val driveState = store.state.drive
        val nowMs = com.areslib.util.RobotClock.currentTimeMillis()
        val observedAt = driveState.poseEstimator.lastObservationTimestampMs
        val ageMs = nowMs - observedAt
        if (!driveState.measuredMotionValid || observedAt < 0L || nowMs < observedAt ||
            ageMs < 0L || ageMs > MAX_MOTION_AGE_MS ||
            hasMotionSample && observedAt < lastVTimeMs) {
            shotResult.clear()
            stopShot()
            return 0.0
        }
        val rx = driveState.measuredFieldXVelocityMetersPerSecond
        val ry = driveState.measuredFieldYVelocityMetersPerSecond
        val omega = driveState.measuredAngularVelocityRadiansPerSecond
        
        if (!rx.isFinite() || !ry.isFinite() || !omega.isFinite()) {
            shotResult.clear()
            stopShot()
            return 0.0
        }
        if (!hasMotionSample || observedAt > lastVTimeMs) {
            val dtMs = observedAt - lastVTimeMs
            // A first reading, or one after an observation gap, establishes a new baseline.
            if (hasMotionSample && dtMs in 1L..MAX_MOTION_AGE_MS) {
                val dt = dtMs / 1000.0
                accelerationX = (rx - lastVx) / dt
                accelerationY = (ry - lastVy) / dt
            } else {
                accelerationX = 0.0
                accelerationY = 0.0
            }
            lastVx = rx
            lastVy = ry
            lastVTimeMs = observedAt
            hasMotionSample = true
        }
        
        // Project measured acceleration through the mechanism/control response delay.
        scratchSpeeds.vxMetersPerSecond = lastVx + accelerationX * ACCELERATION_LOOKAHEAD_SECONDS
        scratchSpeeds.vyMetersPerSecond = lastVy + accelerationY * ACCELERATION_LOOKAHEAD_SECONDS
        scratchSpeeds.omegaRadiansPerSecond = omega
        
        shotSetup.calculate(currentPose, scratchSpeeds, targetTranslation, shotResult)
        if (!shotResult.isValid) {
            stopShot()
            return 0.0
        }
        
        val targetRpm = shotResult.targetFlywheelRpm
        flywheelController.spinUp(targetRpm)
        
        val targetCowlRotations = shotResult.targetCowlAngleRotations
        cowlController.setCowlAngleRotations(targetCowlRotations)
        
        val headingError = shotResult.robotTargetHeadingRad - currentPose.heading.radians
        val wrappedError = com.areslib.math.wrapAngle(headingError)
        val rotation = wrappedError * AIM_KP + shotResult.angularVelocityFeedforwardRadPerSec
        
        val headingAligned = kotlin.math.abs(wrappedError) < 0.05
        val rpmAligned = flywheelController.isRpmAligned(shotResult.targetFlywheelRpm)
        val cowlReady = cowlController.isAngleAligned(targetCowlRotations)
        
        feederController.updateFeeders(rpmAligned, headingAligned, cowlReady, runFloorRollers)
        
        return rotation
    }

    /**
     * Calculates a stationary shot, dispatches RPM/cowl targets, applies rearward-facing
     * heading control, and returns chassis omega in radians per second.
     */
    fun updateStaticShoot(
        currentPose: Pose2d,
        targetTranslation: Translation2d
    ): Double {
        resetMotionSample()
        scratchSpeeds.vxMetersPerSecond = 0.0
        scratchSpeeds.vyMetersPerSecond = 0.0
        scratchSpeeds.omegaRadiansPerSecond = 0.0
        shotSetup.calculate(currentPose, scratchSpeeds, targetTranslation, staticShotResult)
        if (!staticShotResult.isValid) {
            stopShot()
            return 0.0
        }
        val targetRpm = staticShotResult.targetFlywheelRpm
        val targetCowlRotations = staticShotResult.targetCowlAngleRotations
        
        flywheelController.spinUp(targetRpm)
        cowlController.setCowlAngleRotations(targetCowlRotations)
        
        val targetHeadingRad = staticShotResult.robotTargetHeadingRad
        val headingError = targetHeadingRad - currentPose.heading.radians
        val wrappedError = com.areslib.math.wrapAngle(headingError)
        val rotation = wrappedError * AIM_KP
        
        val headingAligned = kotlin.math.abs(wrappedError) < 0.05
        val rpmAligned = flywheelController.isRpmAligned(targetRpm)
        val cowlReady = cowlController.isAngleAligned(targetCowlRotations)
        
        feederController.updateFeeders(rpmAligned, headingAligned, cowlReady, false)
        
        return rotation
    }

    private fun resetMotionSample() {
        hasMotionSample = false
        accelerationX = 0.0
        accelerationY = 0.0
    }

    private fun stopShot() {
        resetMotionSample()
        flywheelController.stop()
        feederController.cancelTransfer()
    }

    private companion object {
        private const val MAX_MOTION_AGE_MS = 100L
        const val ACCELERATION_LOOKAHEAD_SECONDS = 0.2
        const val AIM_KP = 4.0
    }
}

