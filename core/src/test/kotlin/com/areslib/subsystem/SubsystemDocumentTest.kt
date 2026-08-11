package com.areslib.subsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubsystemDocumentTest {
    @Test
    fun `DSL and JSON share the same validated document model`() {
        val document = subsystem("elevator", "Elevator", SubsystemPlatform.FTC) {
            description = "Lift game pieces"
            val target = state.double("targetMeters", "Target", SubsystemFieldRole.TARGET, 0.0, "m")
            val position = state.double("positionMeters", "Position", SubsystemFieldRole.MEASUREMENT, 0.0, "m")
            val leader = hardware.motor("leader", "Leader") {
                hardwareMapName = "elevator"
                measurement(position, SubsystemMeasurementSource.MOTOR_POSITION_NATIVE, scale = 0.01)
            }
            control.positionPid("position", "Position", leader, target, position) {
                kP = 7.5
                maximumOutput = 10.0
                minimumOutput = -4.0
            }
        }

        assertTrue(validateSubsystemDocument(document).isEmpty())
        assertEquals(document, SubsystemDocumentCodec.decode(SubsystemDocumentCodec.encode(document)))
        assertEquals(0.01, document.hardware.single().measurementScale)
        assertEquals(64, SubsystemDocumentCodec.contentHash(document).length)
        assertTrue(
            validateSubsystemDocument(document.copy(documentId = "when"))
                .any { it.path == "documentId" && it.message.contains("keyword") }
        )
    }

    @Test
    fun `validation rejects dangling controller links and platform wiring mistakes`() {
        val document = SubsystemDocument(
            documentId = "arm",
            name = "Arm",
            platform = SubsystemPlatform.FRC,
            hardware = listOf(
                SubsystemHardwareDocument(
                    "leader", "Leader", SubsystemHardwareKind.MOTOR,
                    SubsystemHardwareConnection(hardwareMapName = "wrong-platform"),
                )
            ),
            stateFields = listOf(
                SubsystemStateFieldDocument(
                    "target", "Target", SubsystemValueType.DOUBLE, SubsystemFieldRole.TARGET,
                    defaultNumber = 0.0,
                )
            ),
            controlLoops = listOf(
                SubsystemControlLoopDocument(
                    "position", "Position", SubsystemControlStrategy.POSITION_PID,
                    "leader", "target", "missing",
                )
            ),
        )

        val issues = validateSubsystemDocument(document).map { it.message }
        assertTrue(issues.any { it.contains("CAN ID") })
        assertTrue(issues.any { it.contains("requires a measurement") })
        assertThrows(IllegalArgumentException::class.java) { SubsystemDocumentCodec.encode(document) }
    }
}
