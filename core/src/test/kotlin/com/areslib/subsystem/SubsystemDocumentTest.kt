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
        assertEquals(0.01, document.hardware.single().measurements.single().scale)
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
                    safeOutput = 0.0,
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

    @Test
    fun `homed template declares every safety input including current`() {
        val document = SubsystemTemplates.create(
            SubsystemTemplate.HOMED_MECHANISM,
            "prototype-lift",
            "PrototypeLift",
            SubsystemPlatform.FTC,
        )

        assertTrue(validateSubsystemDocument(document).isEmpty())
        assertTrue(document.safety.requiresHoming)
        assertTrue(document.safety.requiresCurrentMonitoring)
        assertEquals(setOf("position", "currentAmps"), document.hardware.first().measurements.map { it.fieldId }.toSet())
        assertEquals("homeSwitch", document.safety.homingSensorId)
    }

    @Test
    fun `hand-authored descriptor records user ownership without scanning source`() {
        val document = handAuthoredPrismDocument()

        assertTrue(validateSubsystemDocument(document).isEmpty())
        assertEquals(document, SubsystemDocumentCodec.decode(SubsystemDocumentCodec.encode(document)))
        assertEquals(SubsystemSourceOwnership.USER_OWNED, document.implementation.ownership)
        assertEquals(SubsystemTeachingLevel.BEGINNER, document.implementation.teaching.level)
        assertEquals(listOf("prism.setEffect", "prism.off"), document.capabilityActionKeys)
    }

    @Test
    fun `hand-authored descriptor fails closed when source ownership is ambiguous`() {
        val implementation = handAuthoredPrismDocument().implementation.copy(
            ownership = SubsystemSourceOwnership.GENERATED_STARTER,
            sourceFiles = listOf("../PrismSubsystem.kt"),
            subsystemClassName = "PrismSubsystem",
        )
        val issues = validateSubsystemDocument(handAuthoredPrismDocument().copy(implementation = implementation))

        assertTrue(issues.any { it.path == "implementation.ownership" })
        assertTrue(issues.any { it.path == "implementation.sourceFiles[0]" })
        assertTrue(issues.any { it.path == "implementation.subsystemClassName" })
    }

    @Test
    fun `codec requires explicit version five implementation metadata`() {
        val encoded = SubsystemDocumentCodec.encode(handAuthoredPrismDocument())

        val oldSchema = assertThrows(IllegalArgumentException::class.java) {
            SubsystemDocumentCodec.decode(encoded.replace("\"schemaVersion\": 5", "\"schemaVersion\": 4"))
        }
        assertTrue(oldSchema.message.orEmpty().contains("Unsupported subsystem schema 4"))

        val withoutImplementation = assertThrows(IllegalArgumentException::class.java) {
            SubsystemDocumentCodec.decode(
                """{"schemaVersion":5,"documentId":"prism","name":"Prism","platform":"FTC"}"""
            )
        }
        assertTrue(withoutImplementation.message.orEmpty().contains("implementation metadata is required"))
    }

    private fun handAuthoredPrismDocument() = SubsystemDocument(
        documentId = "prism",
        name = "Prism",
        description = "Controls the goBILDA Prism light",
        platform = SubsystemPlatform.FTC,
        hardware = listOf(
            SubsystemHardwareDocument(
                hardwareId = "prism",
                displayName = "Prism",
                kind = SubsystemHardwareKind.POSITIONAL_SERVO,
                connection = SubsystemHardwareConnection(hardwareMapName = "prism"),
                safeOutput = 0.0,
            )
        ),
        stateFields = listOf(
            SubsystemStateFieldDocument(
                fieldId = "effect",
                displayName = "Effect",
                type = SubsystemValueType.DOUBLE,
                role = SubsystemFieldRole.TARGET,
                defaultNumber = 0.0,
                minimum = 0.0,
                maximum = 1.0,
            )
        ),
        controlLoops = listOf(
            SubsystemControlLoopDocument(
                loopId = "effect",
                displayName = "Effect",
                strategy = SubsystemControlStrategy.SERVO_POSITION,
                actuatorId = "prism",
                targetFieldId = "effect",
                minimumOutput = 0.0,
                maximumOutput = 1.0,
            )
        ),
        implementation = SubsystemImplementationDocument(
            kind = SubsystemImplementationKind.HAND_AUTHORED,
            ownership = SubsystemSourceOwnership.USER_OWNED,
            modulePath = ":TeamCode",
            sourceFiles = listOf(
                "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/PrismSubsystem.kt"
            ),
            subsystemClassName = "org.firstinspires.ftc.teamcode.subsystems.PrismSubsystem",
            ioContractClassName = "com.areslib.hardware.PrismIO",
            hardwareAdapterClassName = "com.areslib.ftc.hardware.FtcPrismDriverIO",
            simulation = SubsystemSimulationDocument(SubsystemSimulationSupport.UNAVAILABLE),
            teaching = SubsystemTeachingDocument(
                level = SubsystemTeachingLevel.BEGINNER,
                summary = "A small output-only subsystem example.",
                documentationPath = "docs/examples/prism-subsystem.md",
                concepts = listOf("safe neutral", "vendor adapter"),
            ),
        ),
        capabilityActionKeys = listOf("prism.setEffect", "prism.off"),
        generateMockIo = false,
        generateTest = false,
    )
}
