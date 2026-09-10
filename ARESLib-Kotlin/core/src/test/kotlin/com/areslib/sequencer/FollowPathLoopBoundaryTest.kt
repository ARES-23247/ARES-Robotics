package com.areslib.sequencer

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.control.feedback.PIDController
import com.areslib.math.geometry.Pose2d
import com.areslib.pathing.*
import com.areslib.state.RobotState
import com.areslib.subsystem.DrivetrainSubsystem
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class FollowPathLoopBoundaryTest {
    private val tasks = mutableListOf<Task>()
    @AfterEach fun cleanup() {
        for (task in tasks) { task.end(RobotState(), true); task.reset() }
        NamedCommands.clear()
        RobotClock.useSystemTime()
    }
    private class Drive : DrivetrainSubsystem {
        var vx = 0.0
        var nonzero = 0
        override fun setChassisSpeeds(vx: Double, vy: Double, omega: Double) {
            this.vx = vx
            if (vx != 0.0 || vy != 0.0 || omega != 0.0) nonzero++
        }
        override fun getEstimatedPose() = Pose2d()
        override fun readSensors(store: Store, timestampMs: Long) = Unit
        override fun writeOutputs(state: RobotState, scale: Double) = Unit
        override fun close() = Unit
    }
    private fun create(clock: Long = 1000, velocity: Double = 1.0, events: List<PathEvent> = emptyList()): Pair<FollowPathTask, Drive> {
        RobotClock.useMockTime(clock)
        val drive = Drive()
        val follower = HolonomicPathFollower(drive, PIDController(0.0, 0.0, 0.0),
            PIDController(0.0, 0.0, 0.0), PIDController(0.0, 0.0, 0.0))
        val path = Path(listOf(PathPoint(Pose2d(), velocity, 0.0),
            PathPoint(Pose2d(2.0, 0.0), velocity, 2.0)), events)
        val task = FollowPathTask(follower, path, mirrorForAlliance = false)
        tasks.add(task)
        task.initialize(RobotState())
        return task to drive
    }
    private fun progress(actions: List<RobotAction>) = actions.filterIsInstance<RobotAction.UpdatePathProgress>().single().distanceProgressMeters

    @Test fun `clock zero is a real origin and no elapsed time produces no movement`() {
        val (task, drive) = create(clock = 0)
        assertTrue(task.execute(RobotState(), 0).isEmpty())
        assertEquals(0, drive.nonzero)
        RobotClock.useMockTime(100)
        assertEquals(0.1, progress(task.execute(RobotState(), 100)), 1e-12)
    }

    @Test fun `repeated timestamp neutralizes the previously active output`() {
        val (task, drive) = create()
        RobotClock.useMockTime(1020)
        task.execute(RobotState(), 20)
        assertTrue(drive.vx > 0.0)
        assertTrue(task.execute(RobotState(), 20).isEmpty())
        assertEquals(0.0, drive.vx)
    }

    @Test fun `parent timeout cannot issue another moving command or trigger a marker`() {
        var created = 0
        NamedCommands.register(CommandKey("marker"), "Probe") {
            created++
            TimeWaitTask(1000)
        }
        val (task, drive) = create(events = listOf(PathEvent("marker", 0.0)))
        task.withTimeout(0)
        RobotClock.useMockTime(1020)
        assertTrue(task.execute(RobotState(), 20).isEmpty())
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        assertEquals(0, drive.nonzero)
        assertEquals(0, created)
    }

    @Test fun `reverse feedforward retains sign while distance progress advances`() {
        val (task, drive) = create(velocity = -1.0)
        RobotClock.useMockTime(1020)
        val advanced = progress(task.execute(RobotState(), 20))
        assertEquals(-1.0, drive.vx, 1e-12)
        assertEquals(0.02, advanced, 1e-12)
    }

    @Test fun `events are snapshotted and idle loops do not rescan the source list`() {
        class CountingEvents : AbstractList<PathEvent>() {
            var reads = 0
            override val size = 10000
            override fun get(index: Int): PathEvent { reads++; return PathEvent("future", 10.0 + index) }
        }
        val events = CountingEvents()
        val (task, _) = create(events = events)
        events.reads = 0
        repeat(20) {
            RobotClock.useMockTime(1020L + it * 20)
            task.execute(RobotState(), 20L + it * 20)
        }
        assertEquals(0, events.reads, "No marker has crossed; source events must not be revisited")
    }

    @Test fun `crossed markers execute in distance order with stable ties`() {
        val order = mutableListOf<String>()
        for (key in listOf("late", "first", "tie")) NamedCommands.register(CommandKey(key), "Probe") {
            order.add(key); TimeWaitTask(0)
        }
        val (task, _) = create(events = listOf(PathEvent("late", 0.015), PathEvent("first", 0.0), PathEvent("tie", -0.0)))
        RobotClock.useMockTime(1020)
        task.execute(RobotState(), 20)
        assertEquals(listOf("first", "tie", "late"), order)
    }

    @Test fun `failed marker immediately neutralizes direct task execution`() {
        NamedCommands.register(CommandKey("fail"), "Probe") {
            object : Task {
                override val name = "fail"
                override fun isCompleted(state: RobotState, elapsedMs: Long) = false
                override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
                    TaskStateMachine.markFailed(this)
                    return emptyList()
                }
            }
        }
        val (task, drive) = create(events = listOf(PathEvent("fail", 0.0)))
        RobotClock.useMockTime(1020)
        task.execute(RobotState(), 20)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        assertEquals(0.0, drive.vx)
    }

    @Test fun `resume excludes paused time and retains pre-pause child elapsed time`() {
        val elapsed = mutableListOf<Long>()
        NamedCommands.register(CommandKey("wait"), "Probe") {
            object : Task {
                override val name = "wait"
                override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
                    elapsed.add(elapsedMs); return false
                }
            }
        }
        val (task, drive) = create(events = listOf(PathEvent("wait", 0.0)))
        RobotClock.useMockTime(1020)
        task.execute(RobotState(), 20)
        RobotClock.useMockTime(1040)
        task.pause(RobotState())
        task.suspendTimeouts(true)
        task.suspendTimeouts(true)
        RobotClock.useMockTime(1500)
        assertTrue(task.execute(RobotState(), 40).isEmpty())
        assertEquals(0.0, drive.vx)
        RobotClock.useMockTime(2000)
        task.suspendTimeouts(false)
        RobotClock.useMockTime(2020)
        assertEquals(0.02, progress(task.execute(RobotState(), 60)), 1e-12)
        assertEquals(listOf(0L, 40L), elapsed)
    }

    @Test fun `clock rollback and signed subtraction overflow fail without progress`() {
        for ((start, end) in listOf(1000L to 999L, Long.MIN_VALUE to Long.MAX_VALUE)) {
            val (task, drive) = create(clock = start)
            RobotClock.useMockTime(end)
            assertTrue(task.execute(RobotState(), 20).isEmpty())
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertEquals(0, drive.nonzero)
        }
    }

    @Test fun `initializer failure leaves the child reachable for exactly one cleanup`() {
        val failure = IllegalStateException("initialize")
        var ended = 0
        NamedCommands.register(CommandKey("broken"), "Probe") {
            object : Task {
                override val name = "broken"
                override fun initialize(state: RobotState): List<RobotAction> {
                    super.initialize(state); throw failure
                }
                override fun isCompleted(state: RobotState, elapsedMs: Long) = false
                override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                    ended++; return super.end(state, interrupted)
                }
            }
        }
        val (task, drive) = create(events = listOf(PathEvent("broken", 0.0)))
        RobotClock.useMockTime(1020)
        assertSame(failure, assertFailsWith<IllegalStateException> { task.execute(RobotState(), 20) })
        assertEquals(0.0, drive.vx)
        task.end(RobotState(), true)
        assertEquals(1, ended)
    }

    @Test fun `invalid completion feedback cannot finish a velocity hold path`() {
        RobotClock.useMockTime(1000)
        val drive = Drive()
        val task = FollowPathTask(HolonomicPathFollower(drive),
            Path(listOf(PathPoint(Pose2d(), 1.0, 0.0))), mirrorForAlliance = false, holdVelocity = true)
        tasks.add(task)
        task.initialize(RobotState())
        val state = RobotState()
        val invalid = state.copy(drive = state.drive.copy(poseEstimator = state.drive.poseEstimator.copy(estimatedPoseHeading = Double.NaN)))
        assertFalse(task.isCompleted(invalid, 20))
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        assertEquals(0.0, drive.vx)
    }

    @Test fun `caller marker list mutation after initialization cannot change schedule`() {
        val events = mutableListOf(PathEvent("original", 0.0))
        val (task, _) = create(events = events)
        events.clear()
        events.add(PathEvent("replacement", 0.0))
        RobotClock.useMockTime(1020)
        val emitted = task.execute(RobotState(), 20).filterIsInstance<RobotAction.PathEventTriggered>()
        assertEquals(1, emitted.size)
        assertEquals("original", emitted.single().eventName)
    }

    @Test fun `authored zero and small positive feedforward are not raised to a speed floor`() {
        for (speed in doubleArrayOf(0.0, 0.01)) {
            val (task, drive) = create(velocity = speed)
            RobotClock.useMockTime(1020)
            task.execute(RobotState(), 20)
            assertEquals(speed, drive.vx, 1e-12)
        }
    }

    @Test fun `reused marker task instance is rejected without duplicate ownership`() {
        var endCalls = 0
        val reused = object : Task {
            override val name = "reused"
            override fun isCompleted(state: RobotState, elapsedMs: Long) = false
            override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                endCalls++; return super.end(state, interrupted)
            }
        }
        NamedCommands.register(CommandKey("same"), "Probe") { reused }
        val (task, drive) = create(events = listOf(PathEvent("same", 0.0), PathEvent("same", 0.01)))
        RobotClock.useMockTime(1020)
        assertFailsWith<IllegalArgumentException> { task.execute(RobotState(), 20) }
        assertEquals(0.0, drive.vx)
        task.end(RobotState(), true)
        assertEquals(1, endCalls)
    }

    @Test fun `invalid marker threshold is rejected before any output`() {
        for (value in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertFailsWith<IllegalArgumentException> { create(events = listOf(PathEvent("bad", value))) }
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(tasks.last()))
        }
    }
}
