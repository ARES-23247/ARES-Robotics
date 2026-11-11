package com.ares.analytics.viewmodel.superstructure

import com.areslib.subsystem.*
import com.areslib.superstructure.*
import kotlin.test.*

class ClearanceInterlockPresetTest {
    private val lift = SubsystemTemplates.create(SubsystemTemplate.ELEVATOR_LIFT, "lift", "Lift", SubsystemPlatform.FTC)
    private val arm = SubsystemTemplates.create(SubsystemTemplate.ARM_PIVOT, "arm", "Arm", SubsystemPlatform.FTC)
    private val state = SuperstructureStudioState(projectPath = ".", subsystems = listOf(lift, arm))
    private val measurement = state.sourceFields.first { it.subsystem == lift && it.field.role == SubsystemFieldRole.MEASUREMENT && it.field.unit == "m" }
    private val target = state.targetFields.first { it.subsystem == arm && it.field.type == SubsystemValueType.DOUBLE }
    private val liftTarget = state.targetFields.first { it.subsystem == lift }
    private val config = ClearanceInterlockConfiguration(measurement.reference, target.reference, 0.25, 0.0, 0.0)
    private val document = SuperstructureDocument(superstructureId = "clearance", initialStateId = "active", faultStateId = "stow",
        states = listOf(SuperstructureStatePreset(stateId = "active", subsystemTargets = listOf(
            SuperstructureSubsystemTarget(liftTarget.reference, constantDoubleValue = 0.8),
            SuperstructureSubsystemTarget(target.reference, constantDoubleValue = 0.5),
        )), SuperstructureStatePreset(stateId = "stow", subsystemTargets = listOf(
            SuperstructureSubsystemTarget(liftTarget.reference, constantDoubleValue = liftTarget.field.defaultNumber),
            SuperstructureSubsystemTarget(target.reference, constantDoubleValue = target.field.defaultNumber),
        ))))

    @Test
    fun `unreviewed preset cannot mutate the document`() {
        assertEquals(document, applySuperstructureCoordinationPreset(state, document, SuperstructureCoordinationPreset.RAISE_BEFORE_EXTEND_INTERLOCK))
    }

    @Test
    fun `reviewed measurement mapping is stable across field and subsystem order`() {
        val reordered = state.copy(subsystems = state.subsystems.reversed().map { it.copy(stateFields = it.stateFields.reversed()) })
        val first = applySuperstructureCoordinationPreset(state, document, SuperstructureCoordinationPreset.RAISE_BEFORE_EXTEND_INTERLOCK, config)
        val second = applySuperstructureCoordinationPreset(reordered, document, SuperstructureCoordinationPreset.RAISE_BEFORE_EXTEND_INTERLOCK, config)
        assertTrue(validateSuperstructureProject(first, state.subsystems, emptySet()).none { it.severity == SuperstructureIssueSeverity.ERROR })
        assertEquals(first.interlocks, second.interlocks)
        assertEquals(measurement.reference, first.interlocks.single().primary)
        assertEquals(target.reference, first.interlocks.single().constrained)
        val roundTrip = SuperstructureDocumentCodec.decode(SuperstructureDocumentCodec.encode(first))
        assertEquals(first.interlocks, roundTrip.interlocks)
        val preview = SuperstructurePreviewSession(roundTrip, state.subsystems)
        val low = preview.setNumeric(measurement.reference, 0.1)
        assertEquals(0.8, low.ports.single { it.reference == liftTarget.reference }.numericValue)
        assertEquals(0.0, low.ports.single { it.reference == target.reference }.numericValue)
        val high = preview.setNumeric(measurement.reference, 0.3)
        assertEquals(0.5, high.ports.single { it.reference == target.reference }.numericValue)
        val stale = preview.inject(measurement.reference, PreviewPortCondition.STALE)
        assertTrue(stale.isFaulted)
        assertEquals("stow", stale.currentStateId)
    }

    @Test
    fun `targets same mechanism stale references and invalid limits are rejected`() {
        val invalid = listOf(config.copy(primary = liftTarget.reference), config.copy(constrained = liftTarget.reference),
            config.copy(primary = SuperstructureFieldReference("missing", "missing")),
            config.copy(threshold = Double.NaN), config.copy(clampMaximum = Double.POSITIVE_INFINITY),
            config.copy(clampMinimum = 1.0, clampMaximum = 0.0), config.copy(clampMinimum = -1e20))
        invalid.forEach {
            assertNotNull(clearanceInterlockError(state, it))
            assertFailsWith<IllegalArgumentException> {
                applySuperstructureCoordinationPreset(state, document, SuperstructureCoordinationPreset.RAISE_BEFORE_EXTEND_INTERLOCK, it)
            }
        }
    }
}
