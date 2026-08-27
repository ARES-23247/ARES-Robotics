package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.state.SubsystemState
import com.areslib.state.SuperstructureState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class NamedSubsystemStateTest {
    private data class TestState(val value: Double) : SubsystemState

    @Test
    fun `named subsystem updates do not replace season state or sibling mechanisms`() {
        val season = TestState(1.0)
        val sibling = TestState(2.0)
        val initial = SuperstructureState(custom = season, subsystems = mapOf("sibling" to sibling))

        val updated = SuperstructureReducer.reduce(
            initial,
            RobotAction.UpdateNamedSubsystemState("arm", TestState(3.0), 10L),
        )

        assertSame(season, updated.custom)
        assertSame(sibling, updated.subsystems["sibling"])
        assertEquals(TestState(3.0), updated.subsystems["arm"])
    }
}
