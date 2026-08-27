package com.ares.analytics.ui.controls

import com.ares.analytics.ui.components.controls.advancedBindingSummary
import com.ares.analytics.ui.components.controls.actionAccessibleLabel
import com.ares.analytics.ui.components.controls.actionBrowserGroups
import com.ares.analytics.ui.components.controls.actionCatalogSummary
import com.ares.analytics.ui.components.controls.bindingLearningTrace
import com.ares.analytics.ui.components.controls.canvasCollisionOffsetY
import com.ares.analytics.ui.components.controls.hasAdvancedBindingSettings
import com.ares.analytics.shared.League
import com.ares.analytics.viewmodel.controls.ControlsEditorState
import com.areslib.catalog.ActionDescriptor
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlSourceDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetDocument
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControlTimingDocument
import com.areslib.controls.ControllerAnchorDocument
import com.areslib.controls.ControllerAssignment
import com.areslib.controls.ControllerControlDocument
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.ControllerInputMappingDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileDocument
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControlsEditorPresentationTest {
    private val actions = listOf(
        ActionDescriptor(
            key = "intake.collect",
            displayName = "Collect game piece",
            description = "Starts the intake.",
            category = "Intake"
        ),
        ActionDescriptor(
            key = "SetIndicatorColor_GREEN",
            displayName = "Primary light: Green",
            description = "Sets the primary indicator light to green.",
            category = "Primary indicator"
        ),
        ActionDescriptor(
            key = "prism.setEffect",
            displayName = "Set Prism effect",
            description = "Changes the goBILDA Prism LED effect.",
            category = "Prism"
        )
    )

    private fun binding(timing: ControlTimingDocument = ControlTimingDocument()) = ControlBindingDocument(
        bindingId = "intake",
        displayName = "Run intake",
        source = ControlSourceDocument(ControlSourceKind.BUTTON, "driver", listOf("a")),
        event = ControlEvent.PRESS,
        target = ControlTargetDocument(ControlTargetKind.ACTION, "intake.start"),
        timing = timing
    )

    @Test
    fun `same-row stick axes receive opposite callout offsets without moving authored layouts`() {
        fun axis(id: String, y: Double) = ControllerControlDocument(
            controlId = id,
            displayName = id,
            type = ControllerControlTypeDocument.AXIS,
            anchor = ControllerAnchorDocument(.35, y),
        )
        val sameRow = listOf(axis("left_stick_x", .5), axis("left_stick_y", .5))
        assertEquals(-28f, sameRow[0].canvasCollisionOffsetY(sameRow))
        assertEquals(28f, sameRow[1].canvasCollisionOffsetY(sameRow))

        val authoredRows = listOf(axis("left_stick_x", .62), axis("left_stick_y", .72))
        assertEquals(0f, authoredRows[0].canvasCollisionOffsetY(authoredRows))
        assertEquals(0f, authoredRows[1].canvasCollisionOffsetY(authoredRows))
    }

    @Test
    fun `default timing stays collapsed with a plain-language summary`() {
        val binding = binding()

        assertFalse(hasAdvancedBindingSettings(binding))
        assertTrue(advancedBindingSummary(binding).contains("safe defaults"))
    }

    @Test
    fun `non-default safety timing is surfaced automatically`() {
        val binding = binding(ControlTimingDocument(maximumActiveSeconds = 2.0, cooldownSeconds = 0.25))

        assertTrue(hasAdvancedBindingSettings(binding))
        assertTrue(advancedBindingSummary(binding).contains("maximum active time"))
        assertTrue(advancedBindingSummary(binding).contains("cooldown"))
    }

    @Test
    fun `blank action search shows the entire catalog grouped with counts`() {
        val groups = actionBrowserGroups(actions, "")

        assertEquals(actions.size, groups.sumOf { it.actions.size })
        assertEquals(listOf("Intake", "Primary indicator", "Prism"), groups.map { it.category })
        assertEquals("3 actions in 3 categories", actionCatalogSummary(actions))
    }

    @Test
    fun `lighting aliases find indicator and Prism actions without relying on color swatches`() {
        assertEquals(
            setOf("SetIndicatorColor_GREEN", "prism.setEffect"),
            actionBrowserGroups(actions, "LED").flatMap { it.actions }.map { it.key }.toSet()
        )
        assertEquals(
            listOf("SetIndicatorColor_GREEN"),
            actionBrowserGroups(actions, "color green").flatMap { it.actions }.map { it.key }
        )
        assertEquals(
            listOf("prism.setEffect"),
            actionBrowserGroups(actions, "Prism").flatMap { it.actions }.map { it.key }
        )
    }

    @Test
    fun `action labels remain explicit and preserve stable catalog keys`() {
        val indicator = actions[1]

        assertEquals("SetIndicatorColor_GREEN", actionBrowserGroups(actions, "light").flatMap { it.actions }[0].key)
        assertTrue(actionAccessibleLabel(indicator).contains("Primary light: Green"))
        assertTrue(actionAccessibleLabel(indicator).contains("Sets the primary indicator light to green"))
    }

    @Test
    fun `binding trace explains canonical input to IO without claiming execution`() {
        val profile = ControllerProfileDocument(
            documentId = "student-pad",
            displayName = "Student pad",
            controls = listOf(
                ControllerControlDocument(
                    controlId = "a",
                    displayName = "A",
                    type = ControllerControlTypeDocument.BUTTON,
                    anchor = ControllerAnchorDocument(.5, .5),
                    mappings = listOf(
                        ControllerInputMappingDocument(ControllerInputPlatform.FTC, buttonIndex = 0),
                    ),
                ),
            ),
        )
        val selectedBinding = ControlBindingDocument(
            bindingId = "lift-target",
            displayName = "Raise lift",
            source = ControlSourceDocument(ControlSourceKind.BUTTON, "driver", listOf("a")),
            event = ControlEvent.PRESS,
            target = ControlTargetDocument(
                kind = ControlTargetKind.ACTION,
                key = "subsystem.practice-lift.set.target",
                arguments = mapOf("value" to "0.4"),
            ),
        )
        val scheme = ControlSchemeDocument(
            documentId = "competition",
            name = "Competition",
            controllers = listOf(ControllerAssignment("driver", "Driver", profile.documentId, devicePort = 0)),
            bindings = listOf(selectedBinding),
        )
        val trace = bindingLearningTrace(
            ControlsEditorState(
                projectPath = "C:/robot",
                league = League.FTC,
                targetPlatform = ControllerInputPlatform.FTC,
                profiles = listOf(profile),
                schemes = listOf(scheme),
                selectedSchemeId = scheme.documentId,
                selectedControllerSlot = "driver",
                selectedBindingId = selectedBinding.bindingId,
            ),
        ) ?: error("Expected trace")

        assertTrue(trace.input.contains("Driver.A"))
        assertTrue(trace.input.contains("button 0 on FTC"))
        assertTrue(trace.target.contains("subsystem.practice-lift.set.target"))
        assertTrue(trace.target.contains("value=0.4"))
        assertTrue(trace.runtimePath.contains("Redux"))
        assertTrue(trace.runtimePath.contains("cached IO"))
        assertFalse(trace.hasBlockingProblem)
    }
}
