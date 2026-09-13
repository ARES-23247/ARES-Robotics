package com.areslib.subsystem

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SubsystemScaffoldContractAuditTest {
    @Test
    fun `every supported hardware scaffold forms a valid declarative document`() {
        for (platform in SubsystemPlatform.entries) {
            for (kind in SubsystemHardwareKind.entries.filter { it.supportsPlatform(platform) }) {
                val scaffold = SubsystemHardwareScaffolding.create(kind, "device", "Device", platform)
                val document = SubsystemDocument(
                    documentId = "device", displayName = "Device", kotlinTypeName = "Device",
                    platform = platform, hardware = listOf(scaffold.hardware),
                    stateFields = scaffold.stateFields, controlLoops = scaffold.controlLoops,
                    safety = SubsystemSafetyDocument(
                        latchOutputFaults = scaffold.hardware.safeOutput != null,
                        requiresExplicitNeutralRecovery = scaffold.hardware.safeOutput != null,
                    ),
                )
                assertEquals(emptyList<SubsystemValidationIssue>(), SubsystemSchema.validate(document), "$platform $kind")
                assertEquals(scaffold.stateFields.size, scaffold.stateFields.map { it.fieldId }.distinct().size)
                scaffold.hardware.measurements.forEach { measurement ->
                    assertEquals(SubsystemFieldRole.MEASUREMENT, document.stateFields.single { it.fieldId == measurement.fieldId }.role)
                    assertTrue(measurement.source in kind.compatibleMeasurementSources())
                }
                scaffold.controlLoops.forEach { loop ->
                    assertEquals("device", loop.actuatorId)
                    assertEquals(SubsystemFieldRole.TARGET, document.stateFields.single { it.fieldId == loop.targetFieldId }.role)
                    assertTrue(requireNotNull(scaffold.hardware.safeOutput) in loop.minimumOutput..loop.maximumOutput)
                }
            }
        }
    }

    @Test
    fun `encoder scaffold expresses a quarter turn and two turns per second in SI units`() {
        val encoder = SubsystemHardwareScaffolding.create(
            SubsystemHardwareKind.QUADRATURE_ENCODER, "encoder", "Encoder", SubsystemPlatform.FRC, channel = 8,
        )
        val position = encoder.hardware.measurements.single { it.source == SubsystemMeasurementSource.ENCODER_POSITION_TURNS }
        val velocity = encoder.hardware.measurements.single { it.source == SubsystemMeasurementSource.ENCODER_VELOCITY_TURNS_PER_SECOND }
        assertEquals(Math.PI / 2, 0.25 * position.scale + position.offset, 1e-12)
        assertEquals(4 * Math.PI, 2.0 * velocity.scale + velocity.offset, 1e-12)
        assertEquals(listOf("rad", "rad/s"), encoder.stateFields.map { it.unit })
        assertEquals(8, encoder.hardware.connection.channel)
        assertEquals(9, encoder.hardware.connection.secondaryChannel)
    }

    @Test
    fun `XRP scaffolds do not invent current feedback and retain built in addressing`() {
        val motor = SubsystemHardwareScaffolding.create(SubsystemHardwareKind.MOTOR, "motor", "Motor", SubsystemPlatform.XRP)
        assertEquals(3, motor.hardware.connection.channel)
        assertEquals(setOf(SubsystemMeasurementSource.MOTOR_POSITION_NATIVE, SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND), motor.hardware.measurements.map { it.source }.toSet())
        assertFalse(motor.stateFields.any { it.unit == "A" })
        val imu = SubsystemHardwareScaffolding.create(SubsystemHardwareKind.IMU, "imu", "IMU", SubsystemPlatform.XRP)
        assertEquals(9, imu.hardware.measurements.size)
        assertNull(imu.hardware.connection.channel)
    }
}
