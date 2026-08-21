package com.areslib.subsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubsystemHardwareScaffoldingTest {
    @Test
    fun `motor scaffolds explicit target position velocity and current`() {
        val scaffold = SubsystemHardwareScaffolding.create(
            SubsystemHardwareKind.MOTOR,
            "liftMotor",
            "Lift motor",
            SubsystemPlatform.FTC,
        )

        assertEquals(
            listOf("liftMotorTargetVoltage", "liftMotorPosition", "liftMotorVelocity", "liftMotorCurrentAmps"),
            scaffold.stateFields.map { it.fieldId },
        )
        assertEquals(
            setOf(
                SubsystemMeasurementSource.MOTOR_POSITION_NATIVE,
                SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND,
                SubsystemMeasurementSource.MOTOR_CURRENT_AMPS,
            ),
            scaffold.hardware.measurements.map { it.source }.toSet(),
        )
        assertEquals(0.0, scaffold.hardware.safeOutput)
        assertEquals(SubsystemControlStrategy.DIRECT, scaffold.controlLoops.single().strategy)
    }

    @Test
    fun `pwm output scaffolds intent state and sensor scaffolds cached measurement`() {
        val prism = SubsystemHardwareScaffolding.create(
            SubsystemHardwareKind.POSITIONAL_SERVO,
            "prism",
            "Prism lights",
            SubsystemPlatform.FTC,
        )
        val limit = SubsystemHardwareScaffolding.create(
            SubsystemHardwareKind.DIGITAL_INPUT,
            "limit",
            "Limit switch",
            SubsystemPlatform.FTC,
        )

        assertEquals("prismPosition", prism.stateFields.single().fieldId)
        assertEquals(SubsystemFieldRole.TARGET, prism.stateFields.single().role)
        assertEquals(SubsystemControlStrategy.SERVO_POSITION, prism.controlLoops.single().strategy)
        assertTrue(prism.hardware.measurements.isEmpty())
        assertEquals("limitActive", limit.stateFields.single().fieldId)
        assertEquals(SubsystemFieldRole.MEASUREMENT, limit.stateFields.single().role)
        assertEquals(SubsystemMeasurementSource.DIGITAL_STATE, limit.hardware.measurements.single().source)
    }

    @Test
    fun `typed sensors scaffold natural cached state in canonical units`() {
        val encoder = SubsystemHardwareScaffolding.create(
            SubsystemHardwareKind.QUADRATURE_ENCODER,
            "armEncoder",
            "Arm encoder",
            SubsystemPlatform.FRC,
            channel = 4,
        )
        val distance = SubsystemHardwareScaffolding.create(
            SubsystemHardwareKind.DISTANCE_SENSOR,
            "range",
            "Range",
            SubsystemPlatform.FTC,
        )
        val imu = SubsystemHardwareScaffolding.create(
            SubsystemHardwareKind.IMU,
            "wristImu",
            "Wrist IMU",
            SubsystemPlatform.FTC,
        )

        assertEquals(5, encoder.hardware.connection.secondaryChannel)
        assertEquals(1.0, encoder.hardware.encoderCountsPerRevolution)
        assertEquals(setOf("rad", "rad/s"), encoder.stateFields.mapNotNull { it.unit }.toSet())
        assertEquals("m", distance.stateFields.single().unit)
        assertEquals(setOf("rad", "rad/s"), imu.stateFields.mapNotNull { it.unit }.toSet())
        assertEquals(SubsystemHubFacingDirection.UP, imu.hardware.imuLogoFacingDirection)
        assertEquals(SubsystemHubFacingDirection.FORWARD, imu.hardware.imuUsbFacingDirection)
        assertTrue(encoder.controlLoops.isEmpty())
    }
}
