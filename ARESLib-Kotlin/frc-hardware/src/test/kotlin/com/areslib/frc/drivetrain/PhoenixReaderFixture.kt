package com.areslib.frc.drivetrain

import com.ctre.phoenix6.StatusCode
import com.ctre.phoenix6.StatusSignal
import com.ctre.phoenix6.Timestamp
import com.ctre.phoenix6.hardware.CANcoder
import com.ctre.phoenix6.hardware.Pigeon2
import com.ctre.phoenix6.hardware.TalonFX
import com.ctre.phoenix6.swerve.SwerveDrivetrain
import com.ctre.phoenix6.swerve.SwerveModule
import edu.wpi.first.math.kinematics.SwerveModuleState
import org.mockito.Mockito.*

/** Vendor mocks bypass device constructors; tests never open CAN devices or command motors. */
@Suppress("UNCHECKED_CAST")
internal class PhoenixReaderFixture {
    val drivetrain = mock(SwerveDrivetrain::class.java) as SwerveDrivetrain<TalonFX, TalonFX, CANcoder>
    val originals = linkedMapOf<String, StatusSignal<Any>>()
    val clones = linkedMapOf<String, StatusSignal<Any>>()
    val timestamps = linkedMapOf<String, Timestamp>()
    val driveMotors = Array(4) { mock(TalonFX::class.java) }
    val steerMotors = Array(4) { mock(TalonFX::class.java) }
    val encoders = Array(4) { mock(CANcoder::class.java) }
    val pigeon = mock(Pigeon2::class.java)
    val state = SwerveDrivetrain.SwerveDriveState().apply {
        ModuleStates = Array(4) { SwerveModuleState() }
        Timestamp = 99.99
    }

    init {
        for ((index, name) in listOf("fl", "fr", "rl", "rr").withIndex()) {
            val module = mock(SwerveModule::class.java) as SwerveModule<TalonFX, TalonFX, CANcoder>
            `when`(drivetrain.getModule(index)).thenReturn(module)
            `when`(module.driveMotor).thenReturn(driveMotors[index])
            `when`(module.steerMotor).thenReturn(steerMotors[index])
            `when`(module.encoder).thenReturn(encoders[index])
            doReturn(signal("$name/current", 1.0)).`when`(driveMotors[index]).supplyCurrent
            doReturn(signal("$name/encoder", 0.1)).`when`(encoders[index]).absolutePosition
            doReturn(signal("$name/drive-hardware")).`when`(driveMotors[index]).getFault_Hardware()
            doReturn(signal("$name/drive-brownout")).`when`(driveMotors[index]).getFault_BridgeBrownout()
            doReturn(signal("$name/drive-temperature")).`when`(driveMotors[index]).getFault_DeviceTemp()
            doReturn(signal("$name/steer-hardware")).`when`(steerMotors[index]).getFault_Hardware()
            doReturn(signal("$name/steer-brownout")).`when`(steerMotors[index]).getFault_BridgeBrownout()
            doReturn(signal("$name/steer-temperature")).`when`(steerMotors[index]).getFault_DeviceTemp()
        }
        `when`(drivetrain.pigeon2).thenReturn(pigeon)
        doReturn(signal("pitch")).`when`(pigeon).pitch
        doReturn(signal("roll")).`when`(pigeon).roll
        doReturn(signal("yaw")).`when`(pigeon).yaw
        doReturn(signal("yaw-rate")).`when`(pigeon).angularVelocityZWorld
        `when`(drivetrain.stateCopy).thenReturn(state)
    }

    private fun signal(name: String, value: Double = 0.0): StatusSignal<Any> {
        val original = mock(StatusSignal::class.java) as StatusSignal<Any>
        val cloned = mock(StatusSignal::class.java) as StatusSignal<Any>
        val stamp = mock(Timestamp::class.java)
        `when`(original.clone()).thenReturn(cloned)
        `when`(cloned.valueAsDouble).thenReturn(value)
        `when`(cloned.status).thenReturn(StatusCode.OK)
        `when`(cloned.timestamp).thenReturn(stamp)
        `when`(stamp.isValid).thenReturn(true)
        `when`(stamp.time).thenReturn(99.98)
        `when`(cloned.setUpdateFrequency(anyDouble(), anyDouble())).thenReturn(StatusCode.OK)
        originals[name] = original; clones[name] = cloned; timestamps[name] = stamp
        return original
    }
}
