package com.areslib.project.model

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.schema.AresControllerTarget
import com.areslib.project.schema.AresSimulatorTarget
import com.areslib.project.schema.ProjectActionKey
import com.areslib.simulation.SimulationProductId
import com.areslib.tuning.TuningComponentDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RobotProjectAssemblerTest {
    @Test
    fun `one raw FTC snapshot produces typed effective identity target and actions`() {
        val effective = RobotProjectAssembler.assemble(validSnapshot(), ControllerInputPlatform.FTC)

        assertTrue(effective.isValid, effective.issues.joinToString { it.message })
        assertEquals("lightbot", effective.projectId?.value)
        assertEquals(AresControllerTarget.FTC_CONTROL_HUB, effective.target?.controller)
        assertEquals(AresSimulatorTarget.FTC, effective.target?.simulator)
        assertEquals(SimulationProductId.FTC_DESKTOP_OPMODE, effective.simulationPlan?.product?.id)
        assertTrue(effective.simulationPlan?.isSupported == true)
        assertTrue(ProjectActionKey("lights.cycleForward") in effective.actions)
    }

    @Test
    fun `feature query API reads the validated effective catalog`() {
        val queries = RobotProjectAssembler.assemble(validSnapshot()).queries()

        assertEquals("C:/robot/lightbot", queries.projectRoot)
        assertTrue(queries.isValid, queries.issues.joinToString())
        assertEquals("lightbot", queries.projectId?.value)
        assertEquals(AresControllerTarget.FTC_CONTROL_HUB, queries.target?.controller)
        assertEquals("lightbot", queries.metadata?.projectId)
        assertEquals(listOf("lights.cycleForward"), queries.actions.map { it.key })
        assertTrue(ProjectActionKey("lights.cycleForward") in queries.actionKeys)
        assertEquals("Cycle lights forward", queries.action("lights.cycleForward")?.displayName)
        assertEquals(SimulationProductId.FTC_DESKTOP_OPMODE, queries.simulationPlan?.product?.id)
        assertEquals(null, queries.action("invalid key with spaces"))
    }

    @Test
    fun `project identity mismatch and cross league assembly fail before generation`() {
        val mismatched = validSnapshot().copy(
            baseCapabilityCatalog = CapabilityCatalogDocument(projectId = "another-project"),
        )
        val wrongPlatform = RobotProjectAssembler.assemble(mismatched, ControllerInputPlatform.FRC)

        assertFalse(wrongPlatform.isValid)
        assertTrue(wrongPlatform.issues.any { it.code == "project_identity_mismatch" })
        assertTrue(wrongPlatform.issues.any { it.code == "platform_mismatch" })
    }

    @Test
    fun `raw load failures remain part of the effective project evidence`() {
        val loadIssue = ProjectModelIssue(
            ProjectModelSeverity.ERROR,
            com.areslib.project.schema.ProjectDocumentKind.FIELD,
            "season-field",
            "decode",
            "decode_error",
            "Field bytes are malformed",
        )
        val effective = RobotProjectAssembler.assemble(validSnapshot().copy(loadIssues = listOf(loadIssue)))

        assertFalse(effective.isValid)
        assertTrue(loadIssue in effective.issues)
    }

    @Test
    fun `legacy tuning scope is explicit and cannot split inside one project`() {
        val component = TuningComponentDocument(
            uid = "component.drive",
            projectUid = "runtime.lightbot",
            displayName = "Drive",
            description = "Typed drive tuning scope",
            parameters = emptyList(),
        )
        val effective = RobotProjectAssembler.assemble(
            validSnapshot().copy(tuningComponents = listOf(component)),
        )
        val split = RobotProjectAssembler.assemble(
            validSnapshot().copy(
                tuningComponents = listOf(
                    component,
                    component.copy(uid = "component.lights", projectUid = "runtime.another-robot"),
                ),
            ),
        )

        assertTrue(effective.isValid, effective.issues.joinToString())
        assertEquals("runtime.lightbot", effective.tuningScopeUid)
        assertFalse(split.isValid)
        assertTrue(split.issues.any { it.code == "tuning_scope_mismatch" })
    }

    private fun validSnapshot() = RobotProjectSnapshot(
        projectRoot = "C:/robot/lightbot",
        metadata = AresProjectMetadataDocument(
            projectId = "lightbot",
            identity = AresProjectIdentityDocument("23247", "2026", "Lightbot", "Lightbot"),
            league = AresLeague.FTC,
            coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
            robotLengthMeters = 0.46,
            robotWidthMeters = 0.46,
            fieldLengthMeters = 3.6576,
            fieldWidthMeters = 3.6576,
        ),
        baseCapabilityCatalog = CapabilityCatalogDocument(
            projectId = "lightbot",
            actions = listOf(
                ActionDescriptor(
                    key = "lights.cycleForward",
                    displayName = "Cycle lights forward",
                    description = "Advances to the next named color.",
                ),
            ),
        ),
    )
}
