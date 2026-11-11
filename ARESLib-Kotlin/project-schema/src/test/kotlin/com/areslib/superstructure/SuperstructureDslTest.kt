package com.areslib.superstructure

import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.SubsystemValueType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuperstructureDslTest {
    @Test
    fun `superstructure DSL builds valid document with states transitions guards LUTs interlocks and round trips JSON`() {
        val elevatorSubsystem = SubsystemDocument(
            uid = "subsystem.elevator",
            documentId = "elevator",
            displayName = "Elevator",
            kotlinTypeName = "Elevator",
            description = "Elevator lift",
            platform = SubsystemPlatform.FTC,
            stateFields = listOf(
                SubsystemStateFieldDocument(
                    uid = "elevator-target-height",
                    fieldId = "targetHeightMeters",
                    displayName = "Target Height",
                    type = SubsystemValueType.DOUBLE,
                    role = SubsystemFieldRole.TARGET,
                    unit = "m",
                    defaultNumber = 0.0,
                ),
                SubsystemStateFieldDocument(
                    uid = "elevator-measured-height",
                    fieldId = "heightMeters",
                    displayName = "Measured Height",
                    type = SubsystemValueType.DOUBLE,
                    role = SubsystemFieldRole.MEASUREMENT,
                    unit = "m",
                    defaultNumber = 0.0,
                ),
            ),
        )
        val clawSubsystem = SubsystemDocument(
            uid = "subsystem.claw",
            documentId = "claw",
            displayName = "Claw",
            kotlinTypeName = "Claw",
            description = "Gripper claw",
            platform = SubsystemPlatform.FTC,
            stateFields = listOf(
                SubsystemStateFieldDocument(
                    uid = "claw-target-closed",
                    fieldId = "targetClosed",
                    displayName = "Target Closed",
                    type = SubsystemValueType.BOOLEAN,
                    role = SubsystemFieldRole.TARGET,
                    defaultBoolean = false,
                ),
                SubsystemStateFieldDocument(
                    uid = "claw-piece-detected",
                    fieldId = "pieceDetected",
                    displayName = "Piece Detected",
                    type = SubsystemValueType.BOOLEAN,
                    role = SubsystemFieldRole.MEASUREMENT,
                    defaultBoolean = false,
                ),
            ),
        )

        val doc = superstructure(
            id = "scoring-coordinator",
            initialStateId = "STOWED",
            faultStateId = "STOWED",
        ) {
            val elevatorTarget = port(elevatorSubsystem, "targetHeightMeters")
            val elevatorMeasured = port(elevatorSubsystem, "heightMeters")
            val clawTarget = port(clawSubsystem, "targetClosed")
            val pieceDetected = port(clawSubsystem, "pieceDetected")

            val heightLut = lut("height-by-sensor", inputUnit = "m", outputUnit = "m") {
                point(0.0, 0.25)
                point(1.0, 0.85)
            }

            state("STOWED") {
                targetDouble(elevatorTarget, 0.0)
                targetBoolean(clawTarget, false)
            }
            state("INTAKING") {
                targetDouble(elevatorTarget, 0.05)
                targetBoolean(clawTarget, false)
                onEntry("intake.spinIn")
                onExit("intake.stop")
            }
            state("HOLDING") {
                targetDouble(elevatorTarget, 0.15)
                targetBoolean(clawTarget, true)
            }
            state("SCORING") {
                targetLut(elevatorTarget, heightLut, source = elevatorMeasured)
                targetBoolean(clawTarget, true)
                timeout(seconds = 3.0, targetStateId = "STOWED")
            }

            onActionTransition("start-intake", from = "STOWED", to = "INTAKING", actionKey = "superstructure.intake")
            sensorTransition("capture-piece", from = "INTAKING", to = "HOLDING", priority = 0) {
                guardBoolean("has-piece", source = pieceDetected, expected = true)
            }
            onActionTransition("score-high", from = "HOLDING", to = "SCORING", actionKey = "superstructure.score") {
                timeoutSeconds = 2.0
                timeoutTargetStateId = "STOWED"
                guardDouble(
                    id = "elevator-ready",
                    source = elevatorMeasured,
                    comparison = InterlockComparison.GREATER_THAN,
                    expected = 0.10,
                )
            }
            timedTransition("auto-stow", from = "SCORING", to = "STOWED", timeoutSeconds = 1.5)

            interlock(
                id = "claw-clearance",
                primary = elevatorMeasured,
                constrained = elevatorTarget,
                comparison = InterlockComparison.LESS_THAN,
                threshold = 0.20,
                clampMaximum = 0.50,
                description = "Limit elevator target when below clearance threshold",
            )
            healthFallback(
                id = "elevator-health",
                source = elevatorMeasured,
                fallbackStateId = "STOWED",
            )
            layout("STOWED", 100.0, 100.0)
            layout("SCORING", 400.0, 100.0)
        }

        assertEquals("Scoring Coordinator", doc.displayName)
        assertEquals(4, doc.states.size)
        assertEquals(4, doc.transitions.size)
        assertEquals(1, doc.interlocks.size)
        assertEquals(1, doc.healthFallbacks.size)
        assertEquals(1, doc.luts.size)

        val projectIssues = validateSuperstructureProject(
            document = doc,
            subsystems = listOf(elevatorSubsystem, clawSubsystem),
            actionKeys = setOf("superstructure.intake", "superstructure.score", "intake.spinIn", "intake.stop"),
        )
        assertTrue(projectIssues.none { it.severity == SuperstructureIssueSeverity.ERROR }) {
            formatSuperstructureValidationReport(projectIssues)
        }

        val json = SuperstructureDocumentCodec.encode(doc)
        val decoded = SuperstructureDocumentCodec.decode(json)
        assertEquals(doc, decoded)
    }

    @Test
    fun `invalid superstructure fails fast with structured diagnostic report`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            superstructure(id = "Invalid_ID", initialStateId = "MISSING", faultStateId = "MISSING") {}
        }
        assertTrue(error.message!!.contains("Superstructure validation found"))
        assertTrue(error.message!!.contains("[ERROR] superstructureId"))
    }
}
