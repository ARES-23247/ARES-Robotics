package com.areslib.state.reducer

import com.areslib.action.RobotAction
import com.areslib.reducer.SuperstructureReducer
import com.areslib.state.SubsystemState
import com.areslib.state.SuperstructureState
import kotlin.test.*
import org.junit.jupiter.api.Test

class SuperstructureReducerTest {
    private data class Mechanism(val target: Double) : SubsystemState

    @Test fun independentUpdatesAndPreviousSnapshotOwnership() {
        val arm = Mechanism(1.0)
        val intake = Mechanism(2.0)
        val before = SuperstructureState(mapOf("led" to 0.2), mapOf("rgb" to 1000), mapOf("arm" to arm), arm)
        val named = SuperstructureReducer.reduce(before, RobotAction.UpdateNamedSubsystemState("intake", intake, 10L))
        assertEquals(mapOf("arm" to arm, "intake" to intake), named.subsystems)
        assertSame(arm, named.custom)
        val custom = SuperstructureReducer.reduce(named, RobotAction.UpdateSubsystemState(intake, 20L))
        assertSame(intake, custom.custom)
        assertSame(named.subsystems, custom.subsystems)
        val lights = SuperstructureReducer.reduce(custom, RobotAction.SetIndicatorLight("second", 0.7, 30L))
        val prism = SuperstructureReducer.reduce(lights, RobotAction.SetPrismDriver("rgb", 2000, 40L))
        assertEquals(mapOf("led" to 0.2, "second" to 0.7), prism.indicatorLights)
        assertEquals(mapOf("rgb" to 2000), prism.prismDrivers)
        assertEquals(mapOf("led" to 0.2), before.indicatorLights)
        assertEquals(mapOf("rgb" to 1000), before.prismDrivers)
        assertEquals(mapOf("arm" to arm), before.subsystems)
        assertSame(before, SuperstructureReducer.reduce(before, RobotAction.UpdatePathProgress(1.0, timestampMs = 50L)))
    }

    @Test fun repeatedIntentAndMissingNames() {
        val mechanism = Mechanism(1.0)
        val before = SuperstructureState(mapOf("led" to 0.2), mapOf("rgb" to 1000), mapOf("arm" to mechanism), mechanism)
        for (action in listOf(RobotAction.SetIndicatorLight("led", 0.2, 10L),
            RobotAction.SetPrismDriver("rgb", 1000, 10L), RobotAction.UpdateSubsystemState(mechanism, 10L),
            RobotAction.UpdateNamedSubsystemState("arm", mechanism, 10L))) {
            assertSame(before, SuperstructureReducer.reduce(before, action))
        }
        assertTrue(SuperstructureReducer.reduce(before, RobotAction.SetIndicatorLight("zero", 0.0, 20L)).indicatorLights.containsKey("zero"))
        val signed = SuperstructureReducer.reduce(before, RobotAction.SetIndicatorLight("led", -0.0, 20L))
        val positive = SuperstructureReducer.reduce(signed, RobotAction.SetIndicatorLight("led", 0.0, 30L))
        assertEquals(0.0.toBits(), positive.indicatorLights.getValue("led").toBits())
        assertNotSame(signed, positive)
        val unknown = before.copy(indicatorLights = mapOf("led" to Double.NaN))
        assertSame(unknown, SuperstructureReducer.reduce(unknown, RobotAction.SetIndicatorLight("led", Double.NaN, 40L)))
    }

    @Test fun distinctEqualSubsystemValueIsPublished() {
        val value = Mechanism(1.0)
        val equalCopy = value.copy()
        val before = SuperstructureState(custom = value, subsystems = mapOf("arm" to value))
        assertSame(equalCopy, SuperstructureReducer.reduce(before, RobotAction.UpdateSubsystemState(equalCopy, 10L)).custom)
        assertSame(equalCopy, SuperstructureReducer.reduce(before, RobotAction.UpdateNamedSubsystemState("arm", equalCopy, 10L)).subsystems["arm"])
    }
}
