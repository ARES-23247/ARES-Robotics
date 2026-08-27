package com.areslib.frc.marvin

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
    private var lastVTime = 0.0

    /** Cancels transfer ownership and clears feeder/floor outputs for trigger release. */
    fun cancelTransfer() = feederController.cancelTransfer()

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
        if (!driveState.measuredMotionValid) {
            lastVx = 0.0
            lastVy = 0.0
            lastVTime = 0.0
            clearShotResult(shotResult)
            flywheelController.stop()
            feederController.cancelTransfer()
            return 0.0
        }
        val rx = driveState.measuredFieldXVelocityMetersPerSecond
        val ry = driveState.measuredFieldYVelocityMetersPerSecond
        val omega = driveState.measuredAngularVelocityRadiansPerSecond
        
        val now = com.areslib.util.RobotClock.currentTimeMillis() / 1000.0
        val dt = if (lastVTime > 0.0) now - lastVTime else 0.02
        val ax = if (dt > 0.0) (rx - lastVx) / dt else 0.0
        val ay = if (dt > 0.0) (ry - lastVy) / dt else 0.0
        
        lastVx = rx
        lastVy = ry
        lastVTime = now
        
        // Project measured acceleration through the mechanism/control response delay.
        scratchSpeeds.vxMetersPerSecond = rx + ax * ACCELERATION_LOOKAHEAD_SECONDS
        scratchSpeeds.vyMetersPerSecond = ry + ay * ACCELERATION_LOOKAHEAD_SECONDS
        scratchSpeeds.omegaRadiansPerSecond = omega
        
        shotSetup.calculate(currentPose, scratchSpeeds, targetTranslation, shotResult)
        
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
        scratchSpeeds.vxMetersPerSecond = 0.0
        scratchSpeeds.vyMetersPerSecond = 0.0
        scratchSpeeds.omegaRadiansPerSecond = 0.0
        shotSetup.calculate(currentPose, scratchSpeeds, targetTranslation, staticShotResult)
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

    private fun clearShotResult(result: ShotResult) {
        result.virtualTargetX = 0.0
        result.virtualTargetY = 0.0
        result.aimAngleRad = 0.0
        result.robotTargetHeadingRad = 0.0
        result.aimDistanceMeters = 0.0
        result.targetFlywheelRpm = 0.0
        result.targetCowlAngleRotations = 0.0
        result.angularVelocityFeedforwardRadPerSec = 0.0
    }

    private companion object {
        const val ACCELERATION_LOOKAHEAD_SECONDS = 0.2
        const val AIM_KP = 4.0
    }
}

