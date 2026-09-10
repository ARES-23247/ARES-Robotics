package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.hardware.actuator.IndicatorLightColor as Color
import com.areslib.sequencer.tasks.BlinkIndicatorTask
import com.areslib.sequencer.tasks.SetIndicatorColorTask
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class IndicatorTaskTimingTest {
    private val state = RobotState()
    private val owned = mutableListOf<Task>()
    @BeforeEach fun clock() { RobotClock.useMockTime(0) }
    @AfterEach fun cleanup() { owned.forEach { it.reset() }; RobotClock.useSystemTime() }
    private fun <T : Task> own(task: T): T = task.also { owned.add(it) }
    private fun blink(period: Long = 500, duration: Long = Long.MAX_VALUE, a: Color = Color.RED, b: Color = Color.BLUE) =
        own(BlinkIndicatorTask("light", a, b, duration, period))
    private fun color(actions: List<RobotAction>) = (actions.single() as RobotAction.SetIndicatorLight).position

    @Test fun `both indicator initializers publish running and arm watchdog`() {
        for (task in listOf(blink(), own(SetIndicatorColorTask("light", Color.GREEN)))) {
            task.withTimeout(10)
            task.initialize(state)
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(task))
            TaskTimeoutManager.runWatchdogCheck(11)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        }
    }

    @Test fun `blink normal completion updates status and calls completion exactly once`() {
        val task = blink(duration = 10)
        var calls = 0
        task.onComplete { calls++ }
        task.initialize(state)
        assertFalse(task.isCompleted(state, 9)); assertTrue(task.isCompleted(state, 10))
        assertEquals(Color.RED.position, color(task.end(state, false)))
        assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(task))
        task.end(state, false)
        assertEquals(1, calls)
    }

    @Test fun `blink elapsed timeout invokes failure and emits no phase action`() {
        val task = blink(period = 2)
        var calls = 0
        task.withTimeout(10).onFail { calls++ }
        task.initialize(state)
        assertTrue(task.execute(state, 11).isEmpty())
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        assertEquals(1, calls)
        assertTrue(task.execute(state, 13).isEmpty())
        assertEquals(1, calls)
    }

    @Test fun `terminal blink cannot emit another phase action`() {
        for (status in listOf(TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.COMPLETED)) {
            val task = blink(period = 2); task.initialize(state)
            TaskStateMachine.transitionTo(task, status)
            assertTrue(task.execute(state, 1).isEmpty())
            assertEquals(status, TaskStateMachine.getStatus(task))
        }
    }

    @Test fun `odd period preserves exact full cycle and gives first color the extra millisecond`() {
        val task = blink(period = 5)
        assertEquals(Color.RED.position, color(task.initialize(state)))
        assertTrue(task.execute(state, 2).isEmpty())
        assertEquals(Color.BLUE.position, color(task.execute(state, 3)))
        assertTrue(task.execute(state, 4).isEmpty())
        assertEquals(Color.RED.position, color(task.execute(state, 5)))
        assertTrue(task.execute(state, 7).isEmpty())
        assertEquals(Color.BLUE.position, color(task.execute(state, 8)))
    }

    @Test fun `maximum period and elapsed values do not overflow phase boundaries`() {
        val task = blink(period = Long.MAX_VALUE); task.initialize(state)
        val lastA = Long.MAX_VALUE / 2
        assertTrue(task.execute(state, lastA).isEmpty())
        assertEquals(Color.BLUE.position, color(task.execute(state, lastA + 1)))
        assertEquals(Color.RED.position, color(task.execute(state, Long.MAX_VALUE)))
    }

    @Test fun `constructors reject blank names and invalid durations or periods`() {
        for (name in listOf("", "  ")) {
            assertFailsWith<IllegalArgumentException> { BlinkIndicatorTask(name, Color.RED, Color.BLUE, 10, 2) }
            assertFailsWith<IllegalArgumentException> { SetIndicatorColorTask(name, Color.RED) }
        }
        assertFailsWith<IllegalArgumentException> { BlinkIndicatorTask("light", Color.RED, Color.BLUE, -1, 2) }
        for (period in listOf(Long.MIN_VALUE, -1, 0, 1))
            assertFailsWith<IllegalArgumentException> { BlinkIndicatorTask("light", Color.RED, Color.BLUE, 10, period) }
    }

    @Test fun `negative elapsed fails blink even without optional timeout`() {
        for (completion in listOf(false, true)) {
            val task = blink(period = 2); var failures = 0
            task.onFail { failures++ }; task.initialize(state)
            if (completion) assertFalse(task.isCompleted(state, -1)) else assertTrue(task.execute(state, -1).isEmpty())
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertEquals(1, failures)
            assertFalse(task.isCompleted(state, -1))
            assertEquals(1, failures)
        }
    }

    @Test fun `equivalent colors suppress redundant phase commands`() {
        for ((a, b) in listOf(Color.RED to Color.RED, Color.PURPLE to Color.VIOLET)) {
            val task = blink(period = 2, a = a, b = b)
            task.initialize(state)
            for (elapsed in 1L..10L) assertTrue(task.execute(state, elapsed).isEmpty())
        }
    }

    @Test fun `emitted actions retain fresh clock timestamps and independent values`() {
        val task = blink(period = 2)
        RobotClock.useMockTime(100)
        val first = task.initialize(state).single() as RobotAction.SetIndicatorLight
        RobotClock.useMockTime(200)
        val second = task.execute(state, 1).single() as RobotAction.SetIndicatorLight
        assertEquals(100, first.timestampMs); assertEquals(200, second.timestampMs)
        assertEquals(Color.RED.position, first.position); assertEquals(Color.BLUE.position, second.position)
        assertNotSame(first, second)
    }

    @Test fun `instant color task completes through executor and can be reinitialized`() {
        val task = own(SetIndicatorColorTask("light", Color.GREEN))
        assertFalse(task.isCompleted(state, 0))
        repeat(2) {
            var completions = 0
            task.onComplete { completions++ }
            val executor = TaskExecutor(); executor.addTask(task)
            assertEquals(Color.GREEN.position, color(executor.update(state, 0)))
            assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(task))
            assertEquals(1, completions)
            assertEquals(0, executor.size)
        }
    }

    @Test fun `random periods match exact integer phase oracle including large elapsed values`() {
        val random = java.util.Random(6401)
        repeat(200) {
            val period = (random.nextLong() and Long.MAX_VALUE).coerceAtLeast(2)
            val task = blink(period = period); task.initialize(state)
            val modulus = java.math.BigInteger.valueOf(period)
            var previous = Color.RED.position
            for (elapsed in listOf(0L, period / 2, period / 2 + 1, period, Long.MAX_VALUE)) {
                val remainder = java.math.BigInteger.valueOf(elapsed).remainder(modulus)
                val expected = if (remainder.shiftLeft(1) < modulus) Color.RED.position else Color.BLUE.position
                val actions = task.execute(state, elapsed)
                if (expected == previous) assertTrue(actions.isEmpty()) else assertEquals(expected, color(actions))
                previous = expected
            }
            task.reset()
        }
    }

    @Test fun `unchanged blink phase produces no steady-state allocations`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        org.junit.jupiter.api.Assumptions.assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        requireNotNull(bean).isThreadAllocatedMemoryEnabled = true
        val task = blink(); task.initialize(state)
        repeat(5000) { task.execute(state, 0) }
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        repeat(10_000) { task.execute(state, 0) }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        println("[Indicator audit] 10000 unchanged-phase updates allocated $allocated bytes")
        assertTrue(allocated <= 256L, "Unchanged phase allocated $allocated bytes")
    }
}
