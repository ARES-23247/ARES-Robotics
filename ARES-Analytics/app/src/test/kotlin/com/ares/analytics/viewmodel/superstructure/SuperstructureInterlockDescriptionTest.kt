package com.ares.analytics.viewmodel.superstructure

import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.subsystem.SubsystemValueType
import com.areslib.superstructure.SuperstructureInterlockRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuperstructureInterlockDescriptionTest {
    private val elevator = SubsystemTemplates.create(
        SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
        "elevator",
        "Elevator",
        SubsystemPlatform.FTC,
    )
    private val intake = SubsystemTemplates.create(
        SubsystemTemplate.SIMPLE_ACTUATOR,
        "intake",
        "Intake",
        SubsystemPlatform.FTC,
    )
    private val source = SuperstructureFieldOption(
        elevator,
        SubsystemStateFieldDocument(
            fieldId = "height",
            displayName = "Height",
            role = SubsystemFieldRole.MEASUREMENT,
            type = SubsystemValueType.DOUBLE,
            unit = "m",
        ),
    )
    private val target = SuperstructureFieldOption(
        intake,
        SubsystemStateFieldDocument(
            fieldId = "requestedVoltage",
            displayName = "Requested voltage",
            role = SubsystemFieldRole.TARGET,
            type = SubsystemValueType.DOUBLE,
            unit = "V",
        ),
    )

    @Test
    fun `automatic explanation follows the selected mechanisms comparison and threshold`() {
        val rule = rule().copy(conditionComparison = InterlockComparison.GREATER_THAN, conditionThreshold = 0.3)

        assertEquals(
            "Clamp Intake · Requested voltage (V) when Elevator · Height (m) is above 0.3.",
            automaticInterlockDescription(rule, listOf(source), listOf(target)),
        )
    }

    @Test
    fun `automatic and legacy explanations remain distinguishable from student prose`() {
        val automatic = rule().copy(
            description = "Clamp Intake · Requested voltage (V) when Elevator · Height (m) is below 0.0.",
        )
        val legacy = rule().copy(
            description = "Clamp Intake · Requested voltage (V) while Elevator · Height (m) is below the reviewed threshold.",
        )
        val student = rule().copy(description = "Keep the intake stopped until the lift clears the frame.")

        assertTrue(isAutomaticInterlockDescription(automatic, listOf(source), listOf(target)))
        assertTrue(isAutomaticInterlockDescription(legacy, listOf(source), listOf(target)))
        assertFalse(isAutomaticInterlockDescription(student, listOf(source), listOf(target)))
    }

    private fun rule() = SuperstructureInterlockRule(
        ruleId = "protect-frame",
        description = "",
        primary = source.reference,
        constrained = target.reference,
        conditionComparison = InterlockComparison.LESS_THAN,
        conditionThreshold = 0.0,
        clampMinimum = 0.0,
        clampMaximum = 0.0,
    )
}
