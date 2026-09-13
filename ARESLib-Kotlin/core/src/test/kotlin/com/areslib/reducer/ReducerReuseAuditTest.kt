package com.areslib.reducer

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.state.*
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.*
import org.junit.jupiter.api.Test

class ReducerReuseAuditTest {
    private class Mechanism : SubsystemState {
        override fun equals(other: Any?): Boolean = error("Do not call custom state equality")
        override fun hashCode(): Int = 1
    }

    @Test fun unchangedSlicesReuseSnapshotButTimestampStillAdvances() {
        val before = RobotState(timestampMs = 10L)
        val unrelated = RobotAction.RoutineStepEntered(100L, "r", "step", "wait", 10L)
        assertSame(before, rootReducer(before, unrelated))
        val later = rootReducer(before, unrelated.copy(timestampMs = 20L))
        assertEquals(20L, later.timestampMs)
        assertSame(before.drive, later.drive)
        assertSame(before.routineState, later.routineState)
        assertEquals(10L, before.timestampMs)
    }

    @Test fun repeatedIntentDoesNotRebuildMapsOrSlices() {
        val mechanism = Mechanism()
        val before = RobotState(superstructure = SuperstructureState(
            mapOf("led" to 0.2), mapOf("rgb" to 1000), mapOf("arm" to mechanism), mechanism))
        val actions = listOf(RobotAction.SetIndicatorLight("led", 0.2, 0L),
            RobotAction.SetPrismDriver("rgb", 1000, 0L), RobotAction.UpdateSubsystemState(mechanism, 0L),
            RobotAction.UpdateNamedSubsystemState("arm", mechanism, 0L),
            RobotAction.UpdatePathProgress(0.0, timestampMs = 0L))
        for (action in actions) assertSame(before, rootReducer(before, action))
    }

    @Test fun newMechanismIdentityIsPublishedWithoutUserEquality() {
        val first = Mechanism()
        val second = Mechanism()
        val before = SuperstructureState(subsystems = mapOf("arm" to first), custom = first)
        assertSame(second, SuperstructureReducer.reduce(before, RobotAction.UpdateSubsystemState(second, 10L)).custom)
        assertSame(second, SuperstructureReducer.reduce(before, RobotAction.UpdateNamedSubsystemState("arm", second, 10L)).subsystems["arm"])
    }

    @Test fun observersStillReceiveEveryRepeatedAction() {
        val store = Store()
        var observations = 0
        var actions = 0
        store.subscribe { observations++ }
        store.actionListener = { actions++ }
        val action = RobotAction.UpdatePathProgress(0.0, timestampMs = 0L)
        repeat(5) { store.dispatch(action) }
        assertEquals(5, observations)
        assertEquals(5, actions)
    }

    @Test fun repeatedPublishedIntentHasBoundedAllocation() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val mechanism = Mechanism()
        val store = Store(RobotState(superstructure = SuperstructureState(
            mapOf("led" to 0.2), mapOf("rgb" to 1000), mapOf("arm" to mechanism), mechanism)))
        val actions = arrayOf<RobotAction>(RobotAction.SetIndicatorLight("led", 0.2, 0L),
            RobotAction.SetPrismDriver("rgb", 1000, 0L), RobotAction.UpdateNamedSubsystemState("arm", mechanism, 0L),
            RobotAction.UpdateSubsystemState(mechanism, 0L), RobotAction.UpdatePathProgress(0.0, timestampMs = 0L))
        val id = Thread.currentThread().id
        repeat(30_000) { store.dispatch(actions[it % actions.size]) }
        val before = bean.getThreadAllocatedBytes(id)
        repeat(10_000) { store.dispatch(actions[it % actions.size]) }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        println("Repeated intent: " + allocated + " bytes / 10000 published actions")
        assertTrue(allocated <= 4096L, "Repeated intent allocated " + allocated + " bytes")
    }
}
