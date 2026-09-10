package com.areslib.xrp.hardware

import com.areslib.kinematics.MecanumKinematics
import com.areslib.math.geometry.ChassisSpeeds

/**
 * High-level hardware IO contract for a 4-wheel Mecanum XRP Drivetrain.
 */
interface XrpMecanumHardwareIO {
    val frontLeftMotor: XrpMotorIO
    val frontRightMotor: XrpMotorIO
    val backLeftMotor: XrpMotorIO
    val backRightMotor: XrpMotorIO
    val kinematics: MecanumKinematics
    val wheelRadiusMeters: Double

    /** Invalid coupled commands neutralize all four motors; incomplete writes attempt every stop. */
    fun setPowers(fl: Double, fr: Double, bl: Double, br: Double) {
        val valid = fl.isFinite() && fr.isFinite() && bl.isFinite() && br.isFinite()
        neutralizeOnFailure {
            frontLeftMotor.effort = if (valid) fl.coerceIn(-1.0, 1.0) else 0.0
            frontRightMotor.effort = if (valid) fr.coerceIn(-1.0, 1.0) else 0.0
            backLeftMotor.effort = if (valid) bl.coerceIn(-1.0, 1.0) else 0.0
            backRightMotor.effort = if (valid) br.coerceIn(-1.0, 1.0) else 0.0
        }
    }

    /**
     * Scales wheel speeds together before converting to power. This default allocates scratch;
     * the standard implementation reuses a buffer. Enable, freshness and fault-latch ownership
     * remains with robot controllers and concrete motor adapters, outside this raw IO layer.
     */
    fun drive(chassisSpeeds: ChassisSpeeds, maxLinearSpeedMps: Double = 0.85) {
        applyMecanumDrive(this, chassisSpeeds, maxLinearSpeedMps, DoubleArray(4))
    }

    /** Attempts every stop, preserving the first failure and suppressing later distinct failures. */
    fun stop() {
        var failure = attemptMotorStop(null) { frontLeftMotor.stop() }
        failure = attemptMotorStop(failure) { frontRightMotor.stop() }
        failure = attemptMotorStop(failure) { backLeftMotor.stop() }
        failure = attemptMotorStop(failure) { backRightMotor.stop() }
        if (failure != null) throw failure
    }

    /** Refreshes each motor once on success; incomplete refresh attempts all neutral outputs. */
    fun update() {
        neutralizeOnFailure {
            frontLeftMotor.update()
            frontRightMotor.update()
            backLeftMotor.update()
            backRightMotor.update()
        }
    }
}

open class StandardXrpMecanumHardwareIO(
    override val frontLeftMotor: XrpMotorIO = XrpMotorDouble(1),
    override val frontRightMotor: XrpMotorIO = XrpMotorDouble(2),
    override val backLeftMotor: XrpMotorIO = XrpMotorDouble(3),
    override val backRightMotor: XrpMotorIO = XrpMotorDouble(4),
    trackWidthMeters: Double = 0.155,
    wheelBaseMeters: Double = 0.140,
    wheelRadiusMeters: Double = 0.030
) : XrpMecanumHardwareIO {
    override val wheelRadiusMeters: Double = wheelRadiusMeters.also { radius ->
        require(radius.isFinite() && radius > 0.0) {
            "wheelRadiusMeters must be finite and positive"
        }
    }
    override val kinematics: MecanumKinematics = MecanumKinematics(trackWidthMeters, wheelBaseMeters)
    private val wheelSpeeds = DoubleArray(4)

    /** Single-owner periodic drive path using preallocated wheel scratch. */
    override fun drive(chassisSpeeds: ChassisSpeeds, maxLinearSpeedMps: Double) {
        applyMecanumDrive(this, chassisSpeeds, maxLinearSpeedMps, wheelSpeeds)
    }
}

private fun applyMecanumDrive(io: XrpMecanumHardwareIO, speeds: ChassisSpeeds, maximum: Double, output: DoubleArray) {
    val vx = speeds.vxMetersPerSecond
    val vy = speeds.vyMetersPerSecond
    val omega = speeds.omegaRadiansPerSecond
    if (!maximum.isFinite() || maximum <= 0.0 || !vx.isFinite() || !vy.isFinite() || !omega.isFinite()) {
        io.setPowers(0.0, 0.0, 0.0, 0.0)
        return
    }
    io.kinematics.toWheelSpeeds(vx, vy, omega, output)
    MecanumKinematics.normalize(output, maximum)
    io.setPowers(output[0] / maximum, output[1] / maximum, output[2] / maximum, output[3] / maximum)
}

private inline fun attemptMotorStop(failure: Throwable?, stop: () -> Unit): Throwable? {
    try { stop() } catch (caught: Throwable) {
        if (failure == null) return caught
        if (caught !== failure) failure.addSuppressed(caught)
    }
    return failure
}

private inline fun XrpMecanumHardwareIO.neutralizeOnFailure(operation: () -> Unit) {
    try { operation() } catch (failure: Throwable) {
        try { stop() } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}
