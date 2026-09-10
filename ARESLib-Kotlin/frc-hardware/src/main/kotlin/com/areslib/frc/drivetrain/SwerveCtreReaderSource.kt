package com.areslib.frc.drivetrain

import com.ctre.phoenix6.StatusSignal
import com.ctre.phoenix6.Utils
import com.ctre.phoenix6.hardware.CANcoder
import com.ctre.phoenix6.swerve.SwerveDrivetrain

/** Internal acquisition seam; indices are 4 currents, 4 encoders, 4 IMU signals, 6x4 faults. */
internal interface SwerveCtreReaderSource {
    fun configure(): Boolean
    fun refresh(index: Int)
    fun statusOk(index: Int): Boolean
    fun value(index: Int): Double
    fun latencySeconds(index: Int): Double
    fun timestampValid(index: Int): Boolean
    fun state(): SwerveDrivetrain.SwerveDriveState
    fun stateAgeSeconds(state: SwerveDrivetrain.SwerveDriveState): Double
}

/** Owns cloned signal caches, borrowing the drivetrain and its devices without closing them. */
internal class PhoenixSwerveReaderSource(private val drivetrain: SwerveDrivetrain<*, *, *>) : SwerveCtreReaderSource {
    private val signals: Array<StatusSignal<*>> = Array(36) { index ->
        val module = if (index < 8 || index >= 12) drivetrain.getModule(index % 4) else null
        val signal = when (index) {
            in 0..3 -> module!!.driveMotor.supplyCurrent
            in 4..7 -> (module!!.encoder as CANcoder).absolutePosition
            8 -> drivetrain.pigeon2.pitch
            9 -> drivetrain.pigeon2.roll
            10 -> drivetrain.pigeon2.yaw
            11 -> drivetrain.pigeon2.angularVelocityZWorld
            in 12..15 -> module!!.driveMotor.getFault_Hardware()
            in 16..19 -> module!!.driveMotor.getFault_BridgeBrownout()
            in 20..23 -> module!!.driveMotor.getFault_DeviceTemp()
            in 24..27 -> module!!.steerMotor.getFault_Hardware()
            in 28..31 -> module!!.steerMotor.getFault_BridgeBrownout()
            else -> module!!.steerMotor.getFault_DeviceTemp()
        }
        signal.clone()
    }

    override fun configure(): Boolean {
        var valid = true
        for (index in signals.indices) {
            val frequency = when (index) {
                in 0..3, 8, 9 -> 20.0
                in 4..7 -> 50.0
                in 12..35 -> 4.0
                else -> continue // Leave odometry-owned yaw/rate frequencies unchanged.
            }
            if (!signals[index].setUpdateFrequency(frequency, 0.0).isOK) valid = false
        }
        return valid
    }

    override fun refresh(index: Int) { signals[index].refresh() }
    override fun statusOk(index: Int) = signals[index].status.isOK
    override fun value(index: Int) = signals[index].valueAsDouble
    override fun latencySeconds(index: Int) = signals[index].timestamp.latency
    override fun timestampValid(index: Int) = signals[index].timestamp.isValid
    // Phoenix documents this owning copy for thread-safe consumption. It allocates.
    override fun state(): SwerveDrivetrain.SwerveDriveState = drivetrain.stateCopy
    override fun stateAgeSeconds(state: SwerveDrivetrain.SwerveDriveState) =
        Utils.getCurrentTimeSeconds() - state.Timestamp
}
