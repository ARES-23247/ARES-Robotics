package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.hardware.actuator.IndicatorLightColor as Color
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

class RobotSequenceDurationTest {
    private val owned = mutableListOf<Task>()
    private val state = RobotState()
    @AfterEach fun cleanup() { owned.forEach { it.reset() }; RobotClock.useSystemTime() }
    private fun build(block: RobotSequence.() -> Unit): Task = robotSequence(block).also { root ->
        owned.add(root); owned.addAll((root as SequentialTaskGroup).tasks)
    }
    private fun child(block: RobotSequence.() -> Unit) = (build(block) as SequentialTaskGroup).tasks.single()

    @Test fun `finite fractional waits round up instead of completing early`() {
        for ((duration, expected) in listOf(1.nanoseconds to 1L, 1.5.milliseconds to 2L, 2.milliseconds to 2L)) {
            val task = child { waitFor(duration) }; task.initialize(state)
            assertFalse(task.isCompleted(state, expected - 1))
            assertTrue(task.isCompleted(state, expected))
        }
    }
    @Test fun `distance fallback and blink duration do not end before fractional deadline`() {
        for (task in listOf(child { waitForDistance(1.0, 1.5.milliseconds) },
            child { blinkIndicator("light", Color.RED, duration = 1.5.milliseconds) })) {
            task.initialize(state)
            assertFalse(task.isCompleted(state, 1)); assertTrue(task.isCompleted(state, 2))
        }
    }
    @Test fun `blink period rounds up and rejects intervals unable to represent both colors`() {
        for (period in listOf(1.nanoseconds, 1.milliseconds))
            assertFailsWith<IllegalArgumentException> { build { blinkIndicator("light", Color.RED, duration = 10.milliseconds, period = period) } }
        val task = child { blinkIndicator("light", Color.RED, Color.BLUE, 100.milliseconds, 5.1.milliseconds) }
        task.initialize(state)
        assertTrue(task.execute(state, 2).isEmpty())
        assertEquals(Color.BLUE.position, (task.execute(state, 3).single() as RobotAction.SetIndicatorLight).position)
        assertEquals(Color.RED.position, (task.execute(state, 6).single() as RobotAction.SetIndicatorLight).position)
    }
    @Test fun `strict fractional timeout expires at first integer tick past the requested duration`() {
        RobotClock.useMockTime(0)
        val task = child { waitUntil(1.5.milliseconds) { false } }
        val executor = TaskExecutor(); executor.addTask(task)
        try {
            executor.update(state, 0); executor.update(state, 1)
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(task))
            executor.update(state, 2)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        } finally { executor.cancelAll(state) }
    }
    @Test fun `nonfinite and negative durations fail before building a routine`() {
        for (duration in listOf(Duration.INFINITE, -Duration.INFINITE, (-1).nanoseconds)) {
            assertFailsWith<IllegalArgumentException> { build { waitFor(duration) } }
            assertFailsWith<IllegalArgumentException> { build { waitUntil(duration) { true } } }
            assertFailsWith<IllegalArgumentException> { build { waitForDistance(1.0, duration) } }
            assertFailsWith<IllegalArgumentException> { build { blinkIndicator("light", Color.RED, duration = duration) } }
            assertFailsWith<IllegalArgumentException> { build { blinkIndicator("light", Color.RED, duration = 10.milliseconds, period = duration) } }
        }
    }
    @Test fun `zero and large finite waits retain exact integer boundaries`() {
        assertTrue(child { waitFor(Duration.ZERO) }.isCompleted(state, 0))
        val milliseconds = Long.MAX_VALUE / 4
        val duration = milliseconds.milliseconds
        assertTrue(duration.isFinite())
        val task = child { waitFor(duration) }
        assertFalse(task.isCompleted(state, milliseconds - 1))
        assertTrue(task.isCompleted(state, milliseconds))
    }
}
