package com.ares.analytics.viewmodel.subsystem

import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubsystemDocumentAuthoringTest {
    @Test
    fun `FRC hardware additions share one collision-free CAN and channel allocation policy`() {
        val initial = emptySubsystem(SubsystemPlatform.FRC)
        val withMotor = SubsystemDocumentAuthoring.addHardware(
            initial,
            SubsystemHardwareKind.MOTOR,
            "device",
            SubsystemPlatform.FRC,
        )
        val withSecondMotor = SubsystemDocumentAuthoring.addHardware(
            withMotor,
            SubsystemHardwareKind.MOTOR,
            "device2",
            SubsystemPlatform.FRC,
        )
        val withSensor = SubsystemDocumentAuthoring.addHardware(
            withSecondMotor,
            SubsystemHardwareKind.DIGITAL_INPUT,
            "limit",
            SubsystemPlatform.FRC,
        )

        assertEquals(listOf(1, 2), withSensor.hardware.mapNotNull { it.connection.canId })
        assertEquals(0, withSensor.hardware.single { it.hardwareId == "limit" }.connection.channel)
        assertTrue(withSensor.stateFields.any { it.fieldId == "deviceTargetVoltage" })
        assertTrue(withSensor.controlLoops.any { it.actuatorId == "device" })
    }

    @Test
    fun `FTC hardware addition uses the stable hardware ID as its map name`() {
        val initial = emptySubsystem(SubsystemPlatform.FTC)

        val updated = SubsystemDocumentAuthoring.addHardware(
            initial,
            SubsystemHardwareKind.IMU,
            "armImu",
            SubsystemPlatform.FTC,
        )

        assertEquals("armImu", updated.hardware.single().connection.hardwareMapName)
        assertNotNull(updated.stateFields.singleOrNull { it.fieldId == "armImuYaw" })
    }

    @Test
    fun `hardware addition rejects duplicate IDs and unsupported platform devices`() {
        val initial = emptySubsystem(SubsystemPlatform.FTC)
        val withMotor = SubsystemDocumentAuthoring.addHardware(
            initial,
            SubsystemHardwareKind.MOTOR,
            "device",
            SubsystemPlatform.FTC,
        )

        assertFailsWith<IllegalArgumentException> {
            SubsystemDocumentAuthoring.addHardware(
                withMotor,
                SubsystemHardwareKind.MOTOR,
                "device",
                SubsystemPlatform.FTC,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SubsystemDocumentAuthoring.addHardware(
                initial,
                SubsystemHardwareKind.SOLENOID,
                "valve",
                SubsystemPlatform.FTC,
            )
        }
    }

    private fun emptySubsystem(platform: SubsystemPlatform) = SubsystemDocument(
        documentId = "mechanism",
        displayName = "Mechanism",
        kotlinTypeName = "Mechanism",
        platform = platform,
    )
}
