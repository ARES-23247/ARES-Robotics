@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.hardware.sparkfun

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit

/**
 * Thread-visible desktop state double for the SparkFun Optical Tracking Odometry Sensor (OTOS)
 * driver API used by ARESLib.
 */
open class SparkFunOTOS {
    data class Pose2D(
        @JvmField var x: Double = 0.0,
        @JvmField var y: Double = 0.0,
        @JvmField var h: Double = 0.0
    )

    data class Version(
        @JvmField var major: Byte = 1,
        @JvmField var minor: Byte = 0,
        @JvmField var patch: Byte = 0
    )

    @Volatile private var _position = Pose2D()
    @Volatile private var _velocity = Pose2D()
    @Volatile private var _acceleration = Pose2D()
    @Volatile private var _offset = Pose2D()
    @Volatile private var _linearScalar = 1.0
    @Volatile private var _angularScalar = 1.0

    @Synchronized
    open fun getPosition(): Pose2D = _position.copy()

    @Synchronized
    open fun getVelocity(): Pose2D = _velocity.copy()

    @Synchronized
    open fun getAcceleration(): Pose2D = _acceleration.copy()

    @Synchronized
    open fun setPosition(pos: Pose2D) {
        _position = pos.copy()
    }

    @Synchronized
    open fun setVelocity(vel: Pose2D) {
        _velocity = vel.copy()
    }

    @Synchronized
    open fun setAcceleration(acc: Pose2D) {
        _acceleration = acc.copy()
    }

    @Synchronized
    open fun setOffset(off: Pose2D) {
        _offset = off.copy()
    }

    @Synchronized
    open fun setLinearScalar(scalar: Double) {
        _linearScalar = scalar
    }

    @Synchronized
    open fun setAngularScalar(scalar: Double) {
        _angularScalar = scalar
    }

    open fun calibrateImu() {}

    @Synchronized
    open fun resetTracking() {
        _position = Pose2D()
    }

    open fun getLinearUnit(): DistanceUnit = DistanceUnit.METER
    open fun setLinearUnit(unit: DistanceUnit) {}
    open fun getAngularUnit(): AngleUnit = AngleUnit.RADIANS
    open fun setAngularUnit(unit: AngleUnit) {}
    open fun getVersionInfo(hw: Version, fw: Version) {}
}
