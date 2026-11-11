package com.ares.analytics.ui.components.superstructure

import com.ares.analytics.viewmodel.superstructure.PreviewPortCondition
import com.ares.analytics.viewmodel.superstructure.SuperstructurePreviewPort
import com.ares.analytics.viewmodel.superstructure.SuperstructurePreviewSession
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.subsystem.SubsystemValueType
import com.areslib.superstructure.StateTransitionEdge
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureFieldReference
import com.areslib.superstructure.SuperstructureHealthFallbackPolicy
import com.areslib.superstructure.SuperstructureStatePreset
import com.areslib.superstructure.SuperstructureSubsystemTarget
import com.areslib.superstructure.TransitionTriggerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuperstructureSimulationLabTest {

    private val armSub = SubsystemTemplates.create(
        SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
        "arm",
        "Arm",
        SubsystemPlatform.FTC,
    )
    private val targetField = armSub.stateFields.single { it.role == SubsystemFieldRole.TARGET }
    private val measurementField = armSub.stateFields.first { it.role == SubsystemFieldRole.MEASUREMENT }
    private val targetRef = SuperstructureFieldReference(armSub.uid, targetField.uid)
    private val measurementRef = SuperstructureFieldReference(armSub.uid, measurementField.uid)

    private fun sampleDocument(): SuperstructureDocument = SuperstructureDocument(
        superstructureId = "main-super",
        initialStateId = "idle",
        faultStateId = "fault",
        states = listOf(
            SuperstructureStatePreset(
                stateId = "idle",
                subsystemTargets = listOf(SuperstructureSubsystemTarget(targetRef, constantDoubleValue = 0.0)),
            ),
            SuperstructureStatePreset(
                stateId = "score",
                subsystemTargets = listOf(SuperstructureSubsystemTarget(targetRef, constantDoubleValue = 60.0)),
            ),
            SuperstructureStatePreset(
                stateId = "fault",
                subsystemTargets = listOf(SuperstructureSubsystemTarget(targetRef, constantDoubleValue = 0.0)),
            ),
        ),
        transitions = listOf(
            StateTransitionEdge(
                transitionId = "to-score",
                sourceStateId = "idle",
                targetStateId = "score",
                triggerKind = TransitionTriggerKind.ACTION_REQUEST,
                actionKey = "super.requestScore",
            ),
            StateTransitionEdge(
                transitionId = "to-idle",
                sourceStateId = "score",
                targetStateId = "idle",
                triggerKind = TransitionTriggerKind.ACTION_REQUEST,
                actionKey = "super.requestIdle",
            ),
            StateTransitionEdge(
                transitionId = "auto-timeout",
                sourceStateId = "score",
                targetStateId = "idle",
                triggerKind = TransitionTriggerKind.TIME_ELAPSED,
                timeoutSeconds = 2.0,
            ),
        ),
        healthFallbacks = listOf(
            SuperstructureHealthFallbackPolicy(
                policyId = "arm-health",
                source = measurementRef,
                fallbackStateId = "fault",
            )
        ),
    )

    @Test
    fun `extractSuperstructureActionTriggers returns distinct action keys from ACTION_REQUEST edges`() {
        val triggers = extractSuperstructureActionTriggers(sampleDocument())
        assertEquals(listOf("super.requestScore", "super.requestIdle"), triggers)
    }

    @Test
    fun `formatPreviewPortValue and previewPortConditionDisplayName format port telemetry`() {
        val doublePort = SuperstructurePreviewPort(
            reference = measurementRef,
            label = "Arm · Position",
            type = SubsystemValueType.DOUBLE,
            unit = "deg",
            numericValue = 45.125,
            ageMs = 10L,
            healthBits = 0xFF,
            condition = PreviewPortCondition.HEALTHY,
        )
        assertEquals("45.125 deg", formatPreviewPortValue(doublePort))
        assertEquals("Healthy", previewPortConditionDisplayName(PreviewPortCondition.HEALTHY))
        assertEquals("Stale Age", previewPortConditionDisplayName(PreviewPortCondition.STALE))
        assertEquals("Unhomed", previewPortConditionDisplayName(PreviewPortCondition.UNHOMED_OR_UNCALIBRATED))
    }

    @Test
    fun `SuperstructurePreviewSession steps clock, fires action requests, and injects port faults`() {
        val session = SuperstructurePreviewSession(sampleDocument(), listOf(armSub))
        val initial = session.snapshot()
        assertEquals("idle", initial.currentStateId)
        assertEquals(0L, initial.nowMs)

        val afterAction = session.request("super.requestScore")
        assertEquals("score", afterAction.currentStateId)

        val afterStep = session.tick(100L)
        assertEquals(100L, afterStep.nowMs)

        val afterFault = session.inject(measurementRef, PreviewPortCondition.OUTPUT_FAULT)
        assertEquals("fault", afterFault.currentStateId)
        assertTrue(afterFault.ports.first { it.reference == measurementRef }.condition == PreviewPortCondition.OUTPUT_FAULT)
    }
}
