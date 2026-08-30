package com.ares.analytics.viewmodel.routine

import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.CapabilityParameterType
import com.areslib.catalog.ConditionDescriptor
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineDriveMarker
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineStepKind
import com.areslib.routine.RoutineValidationSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoutineEditorModelTest {
    @Test
    fun `every supported step kind gets an editable payload`() {
        RoutineStepKind.entries.forEach { kind ->
            val step = defaultRoutineStep(
                kind,
                RoutinePose(1.0, 2.0, 0.5),
                "Intake.Start",
                "Shooter.Ready",
                "score-piece"
            )
            assertEquals(kind, step.kind)
            when (kind) {
                RoutineStepKind.ACTION -> assertEquals("Intake.Start", step.actionKey)
                RoutineStepKind.DRIVE_TO -> assertNotNull(step.drive)
                RoutineStepKind.WAIT -> assertNotNull(step.durationSeconds)
                RoutineStepKind.WAIT_UNTIL -> {
                    assertEquals("Shooter.Ready", step.conditionKey)
                    assertNotNull(step.timeoutSeconds)
                }
                RoutineStepKind.TOGETHER,
                RoutineStepKind.FIRST_TO_FINISH,
                RoutineStepKind.REPEAT,
                RoutineStepKind.BRANCH -> assertTrue(step.children.isNotEmpty())
                RoutineStepKind.DEADLINE -> assertNotNull(step.deadline)
                RoutineStepKind.CALL -> assertEquals("score-piece", step.routineId)
            }
        }
    }

    @Test
    fun `field edits clamp each nested drive goal to the robot footprint`() {
        val dimensions = RobotDimensions(.6, .4)
        val routine = listOf(
            RoutineStep.together(
                listOf(RoutineStep.driveTo(com.areslib.routine.RoutineDriveStep(RoutinePose(0.0, 0.0, 0.0))))
            )
        )
        val updated = routine.withRoutineRouteWaypoints(
            listOf(Waypoint(100.0, 100.0, 0.0)).iterator(),
            League.FTC,
            dimensions
        )
        val target = updated.single().children.single().drive!!.target
        assertEquals(target, clampRoutinePose(target, League.FTC, dimensions))
        assertFalse(target.xMeters == 100.0)
    }

    @Test
    fun `deadline render order and field mutation order are identical`() {
        val deadlineDrive = com.areslib.routine.RoutineDriveStep(RoutinePose(0.25, 0.0, 0.0))
        val childDrive = com.areslib.routine.RoutineDriveStep(RoutinePose(0.50, 0.0, 0.0))
        val steps = listOf(
            RoutineStep.deadline(
                deadline = RoutineStep.driveTo(deadlineDrive),
                companions = listOf(RoutineStep.driveTo(childDrive)),
            )
        )

        assertEquals(
            listOf(deadlineDrive, childDrive),
            steps.routineDriveStepsInExecutionOrder(),
            "Canvas rendering must visit the deadline before its companion children",
        )

        val dimensions = RobotDimensions.defaultFor(League.FTC)
        val updated = steps.withRoutineRouteWaypoints(
            listOf(
                Waypoint(0.75, 0.10, rotationDeg = 10.0),
                Waypoint(1.00, 0.20, rotationDeg = 20.0),
            ).iterator(),
            League.FTC,
            dimensions,
        ).single()

        assertEquals(0.75, updated.deadline!!.drive!!.target.xMeters)
        assertEquals(10.0, Math.toDegrees(updated.deadline!!.drive!!.target.headingRadians), 1e-9)
        assertEquals(1.00, updated.children.single().drive!!.target.xMeters)
        assertEquals(20.0, Math.toDegrees(updated.children.single().drive!!.target.headingRadians), 1e-9)
    }

    @Test
    fun `stable identities target the same nested step after sibling reordering`() {
        val first = RoutineStep.wait(0.25, stepId = "step-first")
        val target = RoutineStep.action("Intake.Start", stepId = "step-target")
        val group = RoutineStep.together(listOf(first, target), stepId = "step-group")

        val reordered = listOf(group).updateStepById("step-group") {
            it.copy(children = it.children.moveStepById("step-target", -1))
        }
        val updated = reordered.updateStepById("step-target") { it.copy(actionKey = "Intake.Stop") }

        assertEquals(listOf("step-target", "step-first"), updated.single().children.map(RoutineStep::stepId))
        assertEquals("Intake.Stop", updated.single().children.first().actionKey)
        assertEquals("step-target", updated.single().children.first().stepId)
    }

    @Test
    fun `removing one nested identity leaves equal-valued siblings intact`() {
        val left = RoutineStep.wait(0.25, stepId = "step-left")
        val right = RoutineStep.wait(0.25, stepId = "step-right")

        val remaining = listOf(
            RoutineStep.together(listOf(left, right), stepId = "step-group")
        ).removeStepById("step-left")

        assertEquals(listOf("step-right"), remaining.single().children.map(RoutineStep::stepId))
    }

    @Test
    fun `catalog parameter types are validated before save`() {
        val catalog = CapabilityCatalogDocument(
            projectId = "test-project",
            actions = listOf(
                ActionDescriptor(
                    key = "Shooter.Set",
                    displayName = "Set shooter",
                    description = "Sets the shooter mode",
                    parameters = listOf(
                        CapabilityParameterDescriptor(
                            key = "rpm",
                            displayName = "Speed",
                            description = "Flywheel speed",
                            type = CapabilityParameterType.NUMBER,
                            minimum = 0.0,
                            maximum = 6000.0
                        )
                    )
                )
            ),
            conditions = listOf(
                ConditionDescriptor("Shooter.Ready", "Shooter ready", "True at commanded speed")
            )
        )
        val invalid = RoutineDocument(
            documentId = "shoot",
            name = "Shoot",
            steps = listOf(RoutineStep.action("Shooter.Set", mapOf("rpm" to "fast")))
        )
        val issues = routineEditorValidation(
            invalid,
            catalog,
            listOf(invalid),
            League.FTC,
            RobotDimensions.defaultFor(League.FTC),
            null
        )
        assertTrue(issues.any { it.code == "invalid_argument" && it.severity == RoutineValidationSeverity.ERROR })

        val valid = invalid.copy(steps = listOf(RoutineStep.action("Shooter.Set", mapOf("rpm" to "4500"))))
        val validIssues = routineEditorValidation(
            valid,
            catalog,
            listOf(valid),
            League.FTC,
            RobotDimensions.defaultFor(League.FTC),
            null
        )
        assertTrue(validIssues.none { it.code == "invalid_argument" || it.code == "missing_argument" })
    }

    @Test
    fun `drive action references fail closed against an empty or unavailable catalog`() {
        val routine = RoutineDocument(
            documentId = "drive-actions",
            name = "Drive actions",
            steps = listOf(
                RoutineStep.driveTo(
                    RoutineDriveStep(
                        target = RoutinePose(1.0, 1.0, 0.0),
                        markers = listOf(RoutineDriveMarker(0.5, "Intake.Start")),
                        duringActionKeys = listOf("Intake.Hold"),
                        arrivalActionKeys = listOf("Intake.Stop"),
                    ),
                ),
            ),
        )
        val emptyCatalog = CapabilityCatalogDocument(projectId = "test-project")

        val emptyCatalogCodes = routineEditorValidation(
            routine,
            emptyCatalog,
            listOf(routine),
            League.FTC,
            RobotDimensions.defaultFor(League.FTC),
            null,
        ).map { it.code }.toSet()
        assertTrue("unknown_marker_action" in emptyCatalogCodes)
        assertTrue("unknown_during_action" in emptyCatalogCodes)
        assertTrue("unknown_arrival_action" in emptyCatalogCodes)

        val unavailableCodes = routineEditorValidation(
            routine,
            null,
            listOf(routine),
            League.FTC,
            RobotDimensions.defaultFor(League.FTC),
            null,
        ).map { it.code }
        assertTrue(unavailableCodes.count { it == "capability_catalog_unavailable" } >= 3)
    }
}
