package com.areslib.xrp.hardware

import com.areslib.kinematics.DifferentialDriveKinematics
import com.areslib.math.geometry.ChassisSpeeds

/**
 * High-level hardware IO contract for an XRP Differential Drivetrain.
 */
interface XrpDifferentialHardwareIO {
    val leftMotor: XrpMotorIO
    val rightMotor: XrpMotorIO
    val kinematics: DifferentialDriveKinematics
    val wheelRadiusMeters: Double

    /** Invalid paired commands neutralize together. Write failures attempt both stops and propagate. */
    fun setPowers(leftPower: Double, rightPower: Double) {
        val valid = leftPower.isFinite() && rightPower.isFinite()
        neutralizeOnFailure {
            leftMotor.effort = if (valid) leftPower.coerceIn(-1.0, 1.0) else 0.0
            rightMotor.effort = if (valid) rightPower.coerceIn(-1.0, 1.0) else 0.0
        }
    }

    /**
     * Preserves wheel ratios when saturating. This default allocates scratch; the standard
     * implementation reuses its own buffer. Enable/freshness/fault-latch ownership stays in
     * the robot controller and concrete motor adapters, outside this raw IO contract.
     */
    fun drive(chassisSpeeds: ChassisSpeeds, maxLinearSpeedMps: Double = 0.85) {
        applyDifferentialDrive(this, chassisSpeeds, maxLinearSpeedMps, DoubleArray(2))
    }

    /** Attempts both motor stops even if one fails, retaining the original failure. */
    fun stop() {
        var failure: Throwable? = null
        try { leftMotor.stop() } catch (caught: Throwable) { failure = caught }
        try { rightMotor.stop() } catch (caught: Throwable) {
            if (failure == null) throw caught
            if (caught !== failure) failure.addSuppressed(caught)
        }
        if (failure != null) throw failure
    }

    /** Refreshes each motor once; incomplete feedback refresh attempts both neutral outputs. */
    fun update() {
        neutralizeOnFailure {
            leftMotor.update()
            rightMotor.update()
        }
    }

    fun getWheelDistances(): Pair<Double, Double> {
        val leftMeters = leftMotor.positionRadians * wheelRadiusMeters
        val rightMeters = rightMotor.positionRadians * wheelRadiusMeters
        return Pair(leftMeters, rightMeters)
    }
}

/**
 * Standard implementation of [XrpDifferentialHardwareIO] with configurable track width and wheel radius.
 */
open class StandardXrpDifferentialHardwareIO(
    override val leftMotor: XrpMotorIO = XrpMotorDouble(1),
    override val rightMotor: XrpMotorIO = XrpMotorDouble(2),
    trackWidthMeters: Double = 0.155,
    wheelRadiusMeters: Double = 0.030
) : XrpDifferentialHardwareIO {
    override val wheelRadiusMeters: Double = wheelRadiusMeters.also { radius ->
        require(radius.isFinite() && radius > 0.0) {
            "wheelRadiusMeters must be finite and positive"
        }
    }
    override val kinematics: DifferentialDriveKinematics = DifferentialDriveKinematics(trackWidthMeters)
    private val wheelSpeeds = DoubleArray(2)

    /** Single-owner periodic drive path using preallocated wheel scratch. */
    override fun drive(chassisSpeeds: ChassisSpeeds, maxLinearSpeedMps: Double) {
        applyDifferentialDrive(this, chassisSpeeds, maxLinearSpeedMps, wheelSpeeds)
    }
}

private fun applyDifferentialDrive(io: XrpDifferentialHardwareIO, speeds: ChassisSpeeds, maximum: Double, output: DoubleArray) {
    val vx = speeds.vxMetersPerSecond
    val vy = speeds.vyMetersPerSecond
    val omega = speeds.omegaRadiansPerSecond
    if (!maximum.isFinite() || maximum <= 0.0 || !vx.isFinite() || !vy.isFinite() || !omega.isFinite()) {
        io.setPowers(0.0, 0.0)
        return
    }
    io.kinematics.toWheelSpeeds(vx, omega, output)
    DifferentialDriveKinematics.normalize(output, maximum)
    io.setPowers(output[0] / maximum, output[1] / maximum)
}

private inline fun XrpDifferentialHardwareIO.neutralizeOnFailure(operation: () -> Unit) {
    try { operation() } catch (failure: Throwable) {
        try { stop() } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}
