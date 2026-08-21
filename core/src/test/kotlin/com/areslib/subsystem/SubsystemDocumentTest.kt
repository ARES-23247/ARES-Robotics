package com.areslib.subsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubsystemDocumentTest {
    @Test
    fun `every capability template is valid and round trips on both robot platforms`() {
        SubsystemPlatform.entries.forEach { platform ->
            SubsystemTemplate.entries.forEach { template ->
                val id = "sample-${template.name.lowercase().replace('_', '-')}"
                val document = SubsystemTemplates.create(template, id, "Sample${template.name.toTypeName()}", platform)
                val issues = validateSubsystemDocument(document)
                assertTrue(issues.isEmpty()) { "$platform $template was invalid: $issues" }
                assertEquals(document, SubsystemDocumentCodec.decode(SubsystemDocumentCodec.encode(document)))
            }
        }
    }

    @Test
    fun `profiled position template declares bounded motion and feedforward`() {
        val elevator = SubsystemTemplates.create(
            SubsystemTemplate.ELEVATOR_LIFT,
            "elevator",
            "Elevator",
            SubsystemPlatform.FTC,
        )
        val loop = elevator.controlLoops.single()

        assertEquals(SubsystemControlStrategy.PROFILED_POSITION_PID, loop.strategy)
        assertTrue(loop.motionProfile.maximumVelocity > 0.0)
        assertTrue(loop.motionProfile.maximumAcceleration > 0.0)
        assertEquals(SubsystemFeedforwardKind.ELEVATOR, loop.feedforward.kind)
    }

    @Test
    fun `fault recovery ownership survives codec normalization`() {
        val base = SubsystemTemplates.create(
            SubsystemTemplate.INTAKE_CONVEYOR,
            "intake",
            "Intake",
            SubsystemPlatform.FTC,
        )
        val decoded = SubsystemDocumentCodec.decode(SubsystemDocumentCodec.encode(base))

        assertTrue(decoded.safety.faultRecovery.enabled)
        assertEquals("motor", decoded.safety.faultRecovery.actuatorId)
        assertEquals("currentAmps", decoded.safety.faultRecovery.currentFieldId)
    }

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
            displayName = "Arm",
            kotlinTypeName = "Arm",
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
        assertEquals(SubsystemHomingMethod.DIGITAL_SENSOR, document.safety.homing.method)
        assertTrue(document.safety.requiresCurrentMonitoring)
        assertEquals(setOf("position", "velocity", "currentAmps"), document.hardware.first().measurements.map { it.fieldId }.toSet())
        assertEquals("homeSwitchActive", document.safety.homing.evidence.single().fieldId)
    }

    @Test
    fun `stall homing requires fresh bounded evidence dwell and timeout`() {
        val base = SubsystemTemplates.create(
            SubsystemTemplate.HOMED_MECHANISM,
            "stall-lift",
            "StallLift",
            SubsystemPlatform.FTC,
        )
        val document = base.copy(
            safety = base.safety.copy(
                homing = SubsystemHomingDocument(
                    method = SubsystemHomingMethod.CURRENT_AND_VELOCITY_STALL,
                    actuatorId = "motor",
                    searchOutput = -2.0,
                    evidence = listOf(
                        SubsystemHomingEvidenceDocument(
                            "currentAmps",
                            SubsystemHomingComparison.AT_OR_ABOVE,
                            7.0,
                        ),
                        SubsystemHomingEvidenceDocument(
                            "velocity",
                            SubsystemHomingComparison.ABS_AT_OR_BELOW,
                            0.5,
                        ),
                    ),
                    dwellMs = 300L,
                    timeoutMs = 4_000L,
                )
            )
        )

        assertTrue(validateSubsystemDocument(document).isEmpty())
        assertTrue(
            validateSubsystemDocument(
                document.copy(safety = document.safety.copy(
                    homing = document.safety.homing.copy(searchOutput = 8.0, timeoutMs = 100L)
                ))
            ).any { it.path == "safety.homing.searchOutput" }
        )
    }

    @Test
    fun `follower actuator shares one controller and rejects competing or incompatible leaders`() {
        val document = subsystem("dual-flywheel", "DualFlywheel", SubsystemPlatform.FTC) {
            val volts = state.double("volts", "Voltage", SubsystemFieldRole.TARGET, 0.0, "V", -12.0, 12.0)
            val leader = hardware.motor("leader", "Leader motor") { hardwareMapName = "leader" }
            hardware.motor("follower", "Follower motor") {
                hardwareMapName = "follower"
                follow(leader, SubsystemFollowerTransform.INVERTED)
            }
            control.direct("flywheel", "Flywheel", leader, volts)
        }

        assertTrue(validateSubsystemDocument(document).isEmpty())
        assertEquals("leader", document.hardware.single { it.hardwareId == "follower" }.following?.leaderId)

        val competing = document.copy(
            controlLoops = document.controlLoops + document.controlLoops.single().copy(
                loopId = "competing",
                uid = "competing",
                actuatorId = "follower",
            )
        )
        assertTrue(validateSubsystemDocument(competing).any { it.message.contains("follower cannot own", ignoreCase = true) })

        val mirroredMotor = document.copy(hardware = document.hardware.map {
            if (it.hardwareId == "follower") it.copy(
                following = it.following?.copy(transform = SubsystemFollowerTransform.MIRRORED_POSITION)
            ) else it
        })
        assertTrue(validateSubsystemDocument(mirroredMotor).any { it.message.contains("positional servos") })

        val signedPositionalFollower = document.copy(hardware = document.hardware.map {
            it.copy(kind = SubsystemHardwareKind.POSITIONAL_SERVO, safeOutput = 0.5)
        })
        assertTrue(
            validateSubsystemDocument(signedPositionalFollower)
                .any { it.message.contains("mirrored position rather than signed inversion") }
        )

        val invertedSensor = document.copy(hardware = document.hardware.mapIndexed { index, device ->
            if (index == 0) device.copy(kind = SubsystemHardwareKind.DIGITAL_INPUT, inverted = true, safeOutput = null)
            else device
        })
        assertTrue(validateSubsystemDocument(invertedSensor).any { it.path == "hardware[0].inverted" })
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
    fun `codec requires explicit current schema implementation and homing metadata`() {
        val encoded = SubsystemDocumentCodec.encode(handAuthoredPrismDocument())

        val oldSchema = assertThrows(IllegalArgumentException::class.java) {
            SubsystemDocumentCodec.decode(encoded.replace("\"schemaVersion\": 9", "\"schemaVersion\": 8"))
        }
        assertTrue(oldSchema.message.orEmpty().contains("Unsupported subsystem schema 8"))

        val withoutImplementation = assertThrows(IllegalArgumentException::class.java) {
            SubsystemDocumentCodec.decode(
                """{"schemaVersion":9,"documentId":"prism","displayName":"Prism","kotlinTypeName":"Prism","platform":"FTC"}"""
            )
        }
        assertTrue(withoutImplementation.message.orEmpty().contains("implementation metadata is required"))
    }

    private fun handAuthoredPrismDocument() = SubsystemDocument(
        documentId = "prism",
        displayName = "Prism lights",
        kotlinTypeName = "Prism",
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

    @Test
    fun `decode normalizes missing optional string fields so copy operations succeed`() {
        val legacyJson = """
            {
              "schemaVersion": 9,
              "documentId": "indicator-lights",
              "displayName": "Indicator lights",
              "kotlinTypeName": "IndicatorLightSubsystem",
              "platform": "FTC",
              "revision": 1,
              "hardware": [
                {
                  "hardwareId": "primaryIndicator",
                  "displayName": "Primary indicator light",
                  "kind": "POSITIONAL_SERVO",
                  "connection": { "hardwareMapName": "indicator" },
                  "required": false,
                  "safeOutput": 0.0
                }
              ],
              "stateFields": [
                {
                  "fieldId": "primaryPosition",
                  "displayName": "Primary color position",
                  "type": "DOUBLE",
                  "role": "TARGET",
                  "defaultNumber": 0.0
                }
              ],
              "controlLoops": [
                {
                  "loopId": "primaryColorOutput",
                  "displayName": "Primary indicator color",
                  "strategy": "SERVO_POSITION",
                  "actuatorId": "primaryIndicator",
                  "targetFieldId": "primaryPosition",
                  "minimumOutput": 0.0,
                  "maximumOutput": 1.0
                }
              ],
              "tuningParameters": [],
              "implementation": {
                "kind": "GENERATED_STARTER",
                "ownership": "GENERATED_STARTER"
              },
              "safety": {
                "homing": { "method": "NONE" }
              }
            }
        """.trimIndent()

        val decoded = SubsystemDocumentCodec.decode(legacyJson)
        val hardware = decoded.hardware.single()
        val copiedHardware = hardware.copy(displayName = "Updated Primary Light")
        assertEquals("Updated Primary Light", copiedHardware.displayName)
        assertEquals("", copiedHardware.description)
        assertEquals("primaryIndicator", copiedHardware.uid)

        val stateField = decoded.stateFields.single()
        val copiedStateField = stateField.copy(displayName = "Updated Position")
        assertEquals("Updated Position", copiedStateField.displayName)
        assertEquals("", copiedStateField.description)

        val loop = decoded.controlLoops.single()
        val copiedLoop = loop.copy(displayName = "Updated Loop")
        assertEquals("Updated Loop", copiedLoop.displayName)
        assertEquals("", copiedLoop.description)
    }
}

private fun String.toTypeName(): String = lowercase().split('_').joinToString("") { token ->
    token.replaceFirstChar { it.uppercase() }
}
