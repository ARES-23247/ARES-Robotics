package com.ares.analytics.viewmodel.controls

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityContext
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlSourceDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetDocument
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControllerAssignment
import kotlin.test.Test
import kotlin.test.assertEquals

class ControlsCoverageTest {
    @Test
    fun `coverage counts only enabled direct teleop action bindings`() {
        val actions = listOf(
            action("arm.raise", "Mechanism"),
            action("drive.recoverNeutral", "Drive safety"),
            action("auto.only", "Auto", listOf(CapabilityContext.AUTONOMOUS)),
        )
        val scheme = scheme(
            binding("arm", "arm.raise"),
            binding("disabled-recovery", "drive.recoverNeutral", enabled = false),
            binding("routine", "score", targetKind = ControlTargetKind.ROUTINE),
        )

        val result = controlsCoverage(actions, scheme)

        assertEquals(2, result.totalCount)
        assertEquals(1, result.boundCount)
        assertEquals(listOf("drive.recoverNeutral"), result.missingSafetyActions.map { it.key })
        assertEquals(listOf("drive.recoverNeutral"), result.missingActions.map { it.key })
    }

    @Test
    fun `safety actions use stable category and recovery key conventions`() {
        val result = controlsCoverage(
            listOf(
                action("lift.confirmCalibration", "Lift"),
                action("robot.emergency-stop", "General"),
                action("intake.stop", "Intake"),
            ),
            scheme(),
        )

        assertEquals(
            setOf("lift.confirmCalibration", "robot.emergency-stop"),
            result.safetyActions.mapTo(linkedSetOf()) { it.key },
        )
    }

    private fun action(
        key: String,
        category: String,
        contexts: List<CapabilityContext> = CapabilityContext.entries,
    ) = ActionDescriptor(key, key, "Fixture action.", category, allowedContexts = contexts)

    private fun scheme(vararg bindings: ControlBindingDocument) = ControlSchemeDocument(
        documentId = "driver",
        name = "Driver",
        controllers = listOf(ControllerAssignment("driver", "Driver", "profile", 0)),
        bindings = bindings.toList(),
    )

    private fun binding(
        id: String,
        key: String,
        enabled: Boolean = true,
        targetKind: ControlTargetKind = ControlTargetKind.ACTION,
    ) = ControlBindingDocument(
        bindingId = id,
        displayName = id,
        source = ControlSourceDocument(ControlSourceKind.BUTTON, "driver", listOf("a")),
        event = ControlEvent.PRESS,
        target = ControlTargetDocument(targetKind, key),
        enabled = enabled,
    )
}
