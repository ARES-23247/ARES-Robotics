@file:Suppress("UNUSED_PARAMETER")
package org.firstinspires.ftc.robotcore.external.navigation

enum class AngleUnit { DEGREES, RADIANS }
enum class AxesOrder { ZYX, XYZ, YZX, XZY, YXZ, ZXY }
enum class AxesReference { EXTRINSIC, INTRINSIC }
enum class Distance {
    METER, CM, MM, INCH
}

enum class DistanceUnit {
    METER, CM, MM, INCH;

    fun fromUnit(unit: DistanceUnit, distance: Double): Double {
        val inMeters = when (unit) {
            METER -> distance
            CM -> distance / 100.0
            MM -> distance / 1000.0
            INCH -> distance * 0.0254
        }
        return when (this) {
            METER -> inMeters
            CM -> inMeters * 100.0
            MM -> inMeters * 1000.0
            INCH -> inMeters / 0.0254
        }
    }
}

enum class UnnormalizedAngleUnit {
    DEGREES, RADIANS
}

/**
 * Class implementation for Pose2 D.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
class Pose2D(
    val distanceUnit: DistanceUnit,
    val x: Double,
    val y: Double,
    val angleUnit: AngleUnit,
    val heading: Double
) {
    constructor() : this(DistanceUnit.METER, 0.0, 0.0, AngleUnit.RADIANS, 0.0)
    
    fun getX(unit: DistanceUnit): Double = x
    fun getY(unit: DistanceUnit): Double = y
    fun getHeading(unit: AngleUnit): Double = heading
}

/**
 * Class implementation for Orientation.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
class Orientation(val firstAngle: Float = 0f, val secondAngle: Float = 0f, val thirdAngle: Float = 0f)

enum class CurrentUnit { AMPS, MILLIAMPS }

/**
 * Class implementation for Yaw Pitch Roll Angles.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
class YawPitchRollAngles(
    val yawUnit: AngleUnit = AngleUnit.DEGREES,
    val yaw: Double = 0.0,
    val pitch: Double = 0.0,
    val roll: Double = 0.0,
    val acquisitionTime: Long = 0
) {
    fun getYaw(unit: AngleUnit): Double = if (unit == yawUnit) yaw else Math.toRadians(yaw)
    fun getPitch(unit: AngleUnit): Double = if (unit == yawUnit) pitch else Math.toRadians(pitch)
    fun getRoll(unit: AngleUnit): Double = if (unit == yawUnit) roll else Math.toRadians(roll)
}

/**
 * Class implementation for Angular Velocity.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
class AngularVelocity(
    val unit: AngleUnit = AngleUnit.DEGREES,
    val xRotationRate: Float = 0f,
    val yRotationRate: Float = 0f,
    val zRotationRate: Float = 0f,
    val acquisitionTime: Long = 0
) {
    fun getXRotationRate(unit: AngleUnit): Float = if (unit == this.unit) xRotationRate else Math.toRadians(xRotationRate.toDouble()).toFloat()
    fun getYRotationRate(unit: AngleUnit): Float = if (unit == this.unit) yRotationRate else Math.toRadians(yRotationRate.toDouble()).toFloat()
    fun getZRotationRate(unit: AngleUnit): Float = if (unit == this.unit) zRotationRate else Math.toRadians(zRotationRate.toDouble()).toFloat()
}

/**
 * Class implementation for Position.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
class Position(
    @JvmField val unit: DistanceUnit = DistanceUnit.METER,
    @JvmField val x: Double = 0.0,
    @JvmField val y: Double = 0.0,
    @JvmField val z: Double = 0.0,
    @JvmField val acquisitionTime: Long = 0
) {
    constructor() : this(DistanceUnit.METER, 0.0, 0.0, 0.0, 0)
    fun toUnit(targetUnit: DistanceUnit): Position = this
}

/**
 * Class implementation for Pose3 D.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
class Pose3D(
    val position: Position = Position(),
    val orientation: YawPitchRollAngles = YawPitchRollAngles()
)

