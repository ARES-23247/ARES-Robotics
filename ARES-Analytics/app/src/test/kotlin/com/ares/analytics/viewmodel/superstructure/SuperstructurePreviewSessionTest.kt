package com.ares.analytics.viewmodel.superstructure

import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.superstructure.StateTransitionEdge
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureFieldReference
import com.areslib.superstructure.SuperstructureHealthFallbackPolicy
import com.areslib.superstructure.SuperstructureStatePreset
import com.areslib.superstructure.SuperstructureSubsystemTarget
import com.areslib.superstructure.TransitionGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuperstructurePreviewSessionTest {
    private val subsystem = SubsystemTemplates.create(
        SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
        "arm",
        "Arm",
        SubsystemPlatform.FTC,
    )
    private val target = subsystem.stateFields.single { it.role == SubsystemFieldRole.TARGET }
    private val measurement = subsystem.stateFields.first { it.role == SubsystemFieldRole.MEASUREMENT }
    private val targetReference = SuperstructureFieldReference(subsystem.uid, target.uid)
    private val measurementReference = SuperstructureFieldReference(subsystem.uid, measurement.uid)

    @Test
    fun `preview runs production transition semantics and exposes trace`() {
        val session = SuperstructurePreviewSession(document(), listOf(subsystem))

        session.setNumeric(measurementReference, 1.0)
        val active = session.request("machine.activate")

        assertEquals("ACTIVE", active.currentStateId)
        assertEquals(listOf("indicator.active"), active.lifecycleActions)
        assertTrue(active.transitionSequence > 0L)
        assertFalse(active.isFaulted)
    }

    @Test
    fun `stale cached evidence enters the declared fail closed posture`() {
        val session = SuperstructurePreviewSession(document(), listOf(subsystem))
        session.setNumeric(measurementReference, 1.0)
        session.request("machine.activate")

        val faulted = session.inject(measurementReference, PreviewPortCondition.STALE)

        assertEquals("FAULT", faulted.currentStateId)
        assertTrue(faulted.isFaulted)
        assertTrue(faulted.faultReason.orEmpty().contains("arm-feedback"))
        assertEquals(PreviewPortCondition.STALE, faulted.ports.single { it.reference.fieldUid == measurement.uid }.condition)
    }

    private fun document() = SuperstructureDocument(
        superstructureId = "main-machine",
        initialStateId = "IDLE",
        faultStateId = "FAULT",
        states = listOf(
            preset("IDLE", target.defaultNumber ?: 0.0),
            preset("ACTIVE", 1.0).copy(onEntryActionKeys = listOf("indicator.active")),
            preset("FAULT", target.defaultNumber ?: 0.0),
        ),
        transitions = listOf(
            StateTransitionEdge(
                transitionId = "activate",
                sourceStateId = "IDLE",
                targetStateId = "ACTIVE",
                actionKey = "machine.activate",
                guards = listOf(
                    TransitionGuard(
                        guardId = "ready",
                        source = measurementReference,
                        comparison = InterlockComparison.GREATER_THAN,
                        expectedDoubleValue = 0.5,
                    )
                ),
                timeoutSeconds = 1.0,
                timeoutTargetStateId = "FAULT",
            )
        ),
        healthFallbacks = listOf(
            SuperstructureHealthFallbackPolicy(
                policyId = "arm-feedback",
                source = measurementReference,
                fallbackStateId = "FAULT",
            )
        ),
    )

    private fun preset(id: String, value: Double) = SuperstructureStatePreset(
        stateId = id,
        subsystemTargets = listOf(SuperstructureSubsystemTarget(targetReference, constantDoubleValue = value)),
    )
}
