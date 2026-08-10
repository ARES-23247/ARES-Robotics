@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.hardware.gobilda

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.UnnormalizedAngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D

/**
 * Thread-visible desktop state double for the FTC goBILDA Pinpoint driver API used by ARESLib.
 *
 * Simulation writes [posX], [posY], [trueHeading], and velocity fields in meters, radians, and
 * per-second units. Public readings subtract the origin captured by [resetPosAndIMU] and account for
 * configured pod offsets. Only the `METER`/`RADIANS` call pattern used by ARESLib is modeled; unit
 * parameters on getters are accepted for signature compatibility but are not converted. Hardware
 * configuration and calibration methods that do not affect the simulation are deliberate no-ops.
 */
open class GoBildaPinpointDriver {
    @Volatile var posX: Double = 0.0
    @Volatile var posY: Double = 0.0
    @Volatile var heading: Double = 0.0
    @Volatile var trueHeading: Double = 0.0
    @Volatile var velX: Double = 0.0
    @Volatile var velY: Double = 0.0
    @Volatile var headingVelocity: Double = 0.0

    @Volatile var xOffsetMeters: Double = 0.0
    @Volatile var yOffsetMeters: Double = 0.0

    @Volatile private var rawOffsetX: Double = 0.0
    @Volatile private var rawOffsetY: Double = 0.0
    @Volatile private var rawOffsetHeading: Double = 0.0
    @Volatile private var trueOffsetHeading: Double = 0.0
    
    @Synchronized
    fun getPosX(unit: DistanceUnit): Double {
        val cosH = kotlin.math.cos(trueHeading)
        val sinH = kotlin.math.sin(trueHeading)
        val centerOfRotationX = posX - (xOffsetMeters * cosH - yOffsetMeters * sinH)
        val centerOfRotationY = posY - (xOffsetMeters * sinH + yOffsetMeters * cosH)
        val fieldDx = centerOfRotationX - rawOffsetX
        val fieldDy = centerOfRotationY - rawOffsetY
        val cosOffset = kotlin.math.cos(trueOffsetHeading)
        val sinOffset = kotlin.math.sin(trueOffsetHeading)
        return fieldDx * cosOffset + fieldDy * sinOffset
    }

    @Synchronized
    fun getPosY(unit: DistanceUnit): Double {
        val cosH = kotlin.math.cos(trueHeading)
        val sinH = kotlin.math.sin(trueHeading)
        val centerOfRotationX = posX - (xOffsetMeters * cosH - yOffsetMeters * sinH)
        val centerOfRotationY = posY - (xOffsetMeters * sinH + yOffsetMeters * cosH)
        val fieldDx = centerOfRotationX - rawOffsetX
        val fieldDy = centerOfRotationY - rawOffsetY
        val cosOffset = kotlin.math.cos(trueOffsetHeading)
        val sinOffset = kotlin.math.sin(trueOffsetHeading)
        return -fieldDx * sinOffset + fieldDy * cosOffset
    }

    /** Returns reset-relative heading in radians; [unit] is accepted but not converted. */
    @Synchronized
    fun getHeading(unit: AngleUnit): Double = heading - rawOffsetHeading
    /** Returns unwrapped reset-relative heading in radians; [unit] is not converted. */
    @Synchronized
    fun getHeading(unit: UnnormalizedAngleUnit): Double = heading - rawOffsetHeading
    /** Returns angular velocity in radians per second; [unit] is not converted. */
    @Synchronized
    fun getHeadingVelocity(unit: UnnormalizedAngleUnit): Double = headingVelocity
    /** Returns X velocity in meters per second; [unit] is not converted. */
    @Synchronized
    fun getVelX(unit: DistanceUnit): Double = velX
    /** Returns Y velocity in meters per second; [unit] is not converted. */
    @Synchronized
    fun getVelY(unit: DistanceUnit): Double = velY
    
    /** Returns a meter/radian pose snapshot after reset and pod-offset compensation. */
    @Synchronized
    fun getPosition(): Pose2D {
        return Pose2D(DistanceUnit.METER, getPosX(DistanceUnit.METER), getPosY(DistanceUnit.METER), AngleUnit.RADIANS, heading - rawOffsetHeading)
    }
    
    /** Compatibility no-op: simulation writes public state fields directly. */
    fun update() {}
    
    /** Captures the current corrected pose and heading as the new zero without moving simulated truth. */
    @Synchronized
    fun resetPosAndIMU() {
        val cosH = kotlin.math.cos(trueHeading)
        val sinH = kotlin.math.sin(trueHeading)
        rawOffsetX = posX - (xOffsetMeters * cosH - yOffsetMeters * sinH)
        rawOffsetY = posY - (xOffsetMeters * sinH + yOffsetMeters * cosH)
        rawOffsetHeading = heading
        trueOffsetHeading = trueHeading
    }
    /** Compatibility no-op; the mock has no modeled IMU calibration state. */
    fun recalibrateIMU() {}

    /** SDK-compatible pod encoder direction values. */
    enum class EncoderDirection { FORWARD, REVERSE }
    /** SDK-compatible predefined pod models. */
    enum class GoBildaOdometryPods { goBilda_SWERVE_POD, goBilda_4_BAR_POD }

    /**
     * Stores pod offsets in meters. The SDK's first argument is lateral (robot Y) and its second is
     * forward (robot X), which is intentionally opposite the local field names.
     */
    @Synchronized
    fun setOffsets(xOffset: Double, yOffset: Double, unit: DistanceUnit) {
        val mult = if (unit == DistanceUnit.MM) 0.001 else 1.0
        // GoBilda setOffsets 1st argument (xOffset) refers to sideways distance -> robot's Y offset
        yOffsetMeters = xOffset * mult
        // GoBilda setOffsets 2nd argument (yOffset) refers to forward distance -> robot's X offset
        xOffsetMeters = yOffset * mult
    }
    
    /** Compatibility no-op; simulated position is already expressed in meters. */
    fun setEncoderResolution(resolution: Double, unit: DistanceUnit) {}
    /** Compatibility no-op; predefined pod scale is not modeled. */
    fun setEncoderResolution(pod: GoBildaOdometryPods) {}
    /** Compatibility no-op; simulator truth already uses the configured ARES coordinate convention. */
    fun setEncoderDirections(xDirection: EncoderDirection, yDirection: EncoderDirection) {}
}
