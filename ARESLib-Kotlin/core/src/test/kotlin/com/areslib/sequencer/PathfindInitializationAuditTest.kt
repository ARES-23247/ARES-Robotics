package com.areslib.sequencer

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Translation2d
import com.areslib.pathing.Costmap
import com.areslib.pathing.HolonomicPathFollower
import com.areslib.state.PathState
import com.areslib.state.RobotState
import com.areslib.subsystem.DrivetrainSubsystem
import com.areslib.util.RobotClock
import kotlin.test.*

class PathfindInitializationAuditTest {
    private val drive = RecordingDrive()
    private val follower = HolonomicPathFollower(drive)
    private val tasks = mutableListOf<PathfindToPoseTask>()
    private val state = RobotState()

    @BeforeTest fun startClock() { RobotClock.useMockTime(1000L) }
    @AfterTest fun cleanup() {
        drive.stopFailure = null
        try { tasks.forEach { try { it.end(state, true) } finally { it.reset() } } }
        finally { RobotClock.useSystemTime() }
    }
    private fun task(velocity: Double = 2.0, acceleration: Double = 1.5) = PathfindToPoseTask(
        Pose2d(1.0, 0.5), follower, Costmap(6.0, 6.0, 0.1, Translation2d(-3.0, -3.0)),
        velocity, acceleration, mirrorForAlliance = false,
    ).also(tasks::add)

    @Test fun `invalid profile limits neutralize fail and remove deadline before escaping`() {
        for ((velocity, acceleration) in listOf(0.0 to 1.0, Double.NaN to 1.0, 1.0 to Double.POSITIVE_INFINITY)) {
            val task = task(velocity, acceleration)
            var failures = 0
            task.withTimeout(1000L).onFail { failures++ }
            drive.setChassisSpeeds(0.4, 0.2, 0.1)
            assertFailsWith<IllegalArgumentException> { task.initialize(state) }
            assertEquals(Triple(0.0, 0.0, 0.0), drive.last)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertFalse(TaskTimeoutManager.isTimedOut(task, Long.MAX_VALUE))
            assertFalse(task.isCompleted(state, 0L))
            assertEquals(1, failures)
            task.end(state, true)
            TaskCallbacks.invokeFail(task)
            assertEquals(1, failures)
        }
    }

    @Test fun `executor generation failure neutralizes the prior drive command`() {
        val executor = TaskExecutor()
        val task = task(velocity = 0.0)
        drive.setChassisSpeeds(0.5, 0.0, 0.2)
        executor.addTask(task)
        executor.update(state, 1000L)
        assertEquals(0, executor.size)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        assertEquals(Triple(0.0, 0.0, 0.0), drive.last)
    }

    @Test fun `uninitialized or terminal tasks do not report domain completion`() {
        val task = task()
        assertFalse(task.isCompleted(state, 0L))
        task.cancel()
        assertFalse(task.isCompleted(state, 0L))
    }

    @Test fun `active reinitialization cannot discard the delegated lifecycle`() {
        val task = task()
        task.initialize(state)
        val original = delegate(task)
        assertFailsWith<IllegalStateException> { task.initialize(state) }
        assertSame(original, delegate(task), "retain the old delegate so its owner can end it")
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        assertEquals(Triple(0.0, 0.0, 0.0), drive.last)
        task.end(state, true)
        assertNull(delegate(task))
    }

    @Test fun `cancelled wrapper cannot continue its running delegate`() = assertInactiveExecute(reset = false)
    @Test fun `reset wrapper cannot continue its running delegate`() = assertInactiveExecute(reset = true)

    private fun assertInactiveExecute(reset: Boolean) {
        val task = task()
        task.initialize(state)
        if (reset) task.reset() else task.cancel()
        RobotClock.useMockTime(1020L)
        drive.setChassisSpeeds(0.5, 0.0, 0.2)
        val actions = task.execute(state.copy(pathState = PathState(currentDistanceMeters = 0.5)), 20L)
        assertTrue(actions.isEmpty())
        assertEquals(Triple(0.0, 0.0, 0.0), drive.last)
        assertEquals(if (reset) TaskStatus.PENDING else TaskStatus.CANCELLED, TaskStateMachine.getStatus(task))
    }

    @Test fun `delegate execution exception fails the wrapper immediately`() {
        val task = task()
        task.initialize(state)
        RobotClock.useMockTime(1020L)
        assertFailsWith<IllegalArgumentException> {
            task.execute(state.copy(pathState = PathState(currentDistanceMeters = Double.NaN)), 20L)
        }
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        assertEquals(Triple(0.0, 0.0, 0.0), drive.last)
    }

    @Test fun `startup stop failure stays primary when failure callback also throws`() {
        val task = task()
        val primary = IllegalStateException("stop failed")
        val secondary = IllegalArgumentException("callback failed")
        drive.stopFailure = primary
        task.onFail { throw secondary }
        val actual = assertFailsWith<IllegalStateException> { task.initialize(state) }
        assertSame(primary, actual)
        assertTrue(actual.suppressed.any { it === secondary })
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }

    @Test fun `ended task can initialize a fresh delegate without retained callbacks`() {
        val task = task()
        task.initialize(state)
        val first = assertNotNull(delegate(task))
        var oldCallbacks = 0
        first.onComplete { oldCallbacks++ }
        task.end(state, true)
        assertNull(delegate(task))
        TaskCallbacks.invokeComplete(first)
        assertEquals(0, oldCallbacks)
        task.reset()
        task.initialize(state)
        assertNotSame(first, delegate(task))
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(task))
    }

    @Test fun `same-position target retains and executes its requested heading`() {
        val task = PathfindToPoseTask(
            Pose2d(0.0, 0.0, com.areslib.math.geometry.Rotation2d(Math.PI / 2)), follower,
            Costmap(6.0, 6.0, 0.1, Translation2d(-3.0, -3.0)), mirrorForAlliance = false,
        ).also(tasks::add)
        val path = (task.initialize(state).single() as com.areslib.action.RobotAction.SwitchPath).path
        assertEquals(Math.PI / 2, path.points.last().pose.heading.radians)
        assertFalse(task.isCompleted(state, 0L))
        RobotClock.useMockTime(1020L)
        task.execute(state, 20L)
        assertEquals(0.0, drive.last.first)
        assertEquals(0.0, drive.last.second)
        assertTrue(drive.last.third > 0.0, "positive heading error must command CCW rotation")
        val atTarget = state.copy(drive = state.drive.copy(
            poseEstimator = com.areslib.math.estimation.PoseEstimatorSnapshot(estimatedPoseHeading = Math.PI / 2),
        ))
        assertTrue(task.isCompleted(atTarget, 20L))
    }

    private fun delegate(task: PathfindToPoseTask): Task? =
        PathfindToPoseTask::class.java.getDeclaredField("delegateTask").apply { isAccessible = true }.get(task) as Task?

    private class RecordingDrive : DrivetrainSubsystem {
        var last = Triple(0.0, 0.0, 0.0)
        var stopFailure: Throwable? = null
        override fun setChassisSpeeds(vx: Double, vy: Double, omega: Double) {
            if (vx == 0.0 && vy == 0.0 && omega == 0.0) stopFailure?.let { throw it }
            last = Triple(vx, vy, omega)
        }
        override fun getEstimatedPose() = Pose2d()
        override fun readSensors(store: com.areslib.Store, timestampMs: Long) = Unit
        override fun writeOutputs(state: RobotState, scale: Double) = Unit
    }
}
