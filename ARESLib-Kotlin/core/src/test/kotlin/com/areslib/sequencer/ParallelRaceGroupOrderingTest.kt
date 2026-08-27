package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Iteration-order semantics of [ParallelRaceGroup]: the first finisher wins its evaluation
 * pass. A failure observed before the winner in iteration order fails the race; a sibling
 * that would report failure only after the winner's slot must not retroactively convert a
 * finished race into a failure.
 */
class ParallelRaceGroupOrderingTest {
    private class ScriptedTask(
        override val name: String,
        private val completionAtMs: Long,
        private val failDuringCompletionCheck: Boolean = false
    ) : Task {
        var endCalls = 0
        var interruptedEnds = 0

        override fun initialize(state: RobotState): List<RobotAction> {
            super.initialize(state)
            return emptyList()
        }

        override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
            if (failDuringCompletionCheck && elapsedMs >= completionAtMs) {
                TaskStateMachine.markFailed(this)
            }
            return !failDuringCompletionCheck && elapsedMs >= completionAtMs
        }

        override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
            endCalls++
            if (interrupted) interruptedEnds++
            super.end(state, interrupted)
            return emptyList()
        }
    }

    @Test
    fun `a sibling failing after the winner slot cannot override the won race`() {
        val winner = ScriptedTask("winner", completionAtMs = 100L)
        val lateFailure = ScriptedTask("late-failure", completionAtMs = 100L, failDuringCompletionCheck = true)
        val race = ParallelRaceGroup(listOf(winner, lateFailure))
        race.initialize(RobotState())

        val completed = race.isCompleted(RobotState(), elapsedMs = 100L)

        assertTrue(completed, "the first finisher must win the race")
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(race))
        race.end(RobotState(), interrupted = false)
        assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(race))
        // The straggler is interrupted, not completed; the never-evaluated failure is not
        // observed and the race reports success.
        assertEquals(1, lateFailure.interruptedEnds)
        race.reset()
        winner.reset()
        lateFailure.reset()
    }

    @Test
    fun `a sibling failing before the winner slot still fails the race`() {
        val earlyFailure = ScriptedTask("early-failure", completionAtMs = 100L, failDuringCompletionCheck = true)
        val winner = ScriptedTask("winner", completionAtMs = 100L)
        val race = ParallelRaceGroup(listOf(earlyFailure, winner))
        race.initialize(RobotState())

        val completed = race.isCompleted(RobotState(), elapsedMs = 100L)

        assertFalse(completed, "an observed failure before any finisher fails the race")
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(race))
        race.end(RobotState(), interrupted = true)
        assertEquals(1, winner.interruptedEnds, "the unfinished sibling is cleaned up interrupted")
        race.reset()
        winner.reset()
        earlyFailure.reset()
    }

    @Test
    fun `two finishers in one pass do not both complete normally`() {
        val first = ScriptedTask("first", completionAtMs = 50L)
        val second = ScriptedTask("second", completionAtMs = 50L)
        val race = ParallelRaceGroup(listOf(first, second))
        race.initialize(RobotState())

        assertTrue(race.isCompleted(RobotState(), elapsedMs = 50L))
        race.end(RobotState(), interrupted = false)

        // Only the first-evaluated finisher ends normally; the other is interrupted.
        assertEquals(1, first.endCalls - first.interruptedEnds)
        assertEquals(1, second.interruptedEnds)
        race.reset()
        first.reset()
        second.reset()
    }
}
