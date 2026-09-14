package org.aresfirst.starter.frc

import com.areslib.action.RobotAction
import com.areslib.math.estimation.PoseEstimatorSnapshot
import com.areslib.math.geometry.Pose2d
import com.areslib.sequencer.*
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StarterMarkerLifecycleAuditTest {
    private class Action : Task {
        override val name = "Marker probe"
        var fail = false
        var cancel = false
        var completes = false
        var executions = 0
        var pauses = 0
        var resumes = 0
        var ends = 0
        var releases = 0
        val neutral = RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0)
        override fun isCompleted(state: RobotState, elapsedMs: Long) = completes && executions > 0
        override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
            super.execute(state, elapsedMs)
            executions++
            if (fail) TaskStateMachine.markFailed(this)
            if (cancel) TaskStateMachine.transitionTo(this, TaskStatus.CANCELLED)
            return emptyList()
        }
        override fun pause(state: RobotState): List<RobotAction> { pauses++; return listOf(neutral) }
        override fun resume(state: RobotState): List<RobotAction> { resumes++; return emptyList() }
        override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
            ends++; super.end(state, interrupted); return listOf(neutral)
        }
        override fun releaseRuntimeState() { releases++; super.releaseRuntimeState() }
    }

    private fun feedback(x: Double, time: Long = RobotClock.currentTimeMillis()): RobotState {
        val state = RobotState()
        return state.copy(drive = state.drive.copy(measuredMotionValid = true,
            poseEstimator = PoseEstimatorSnapshot(estimatedPoseX = x, lastObservationTimestampMs = time)))
    }

    private inline fun withMarker(progress: Double = 0.5, target: Double = 10.0, initialize: Boolean = true,
        block: (StarterFrcDriveMarkerTask, Action) -> Unit) {
        val wasMocked = RobotClock.isMocked
        val old = RobotClock.currentTimeMillis()
        RobotClock.useMockTime(1000L)
        val action = Action()
        val marker = StarterFrcDriveMarkerTask(Pose2d(target, 0.0), progress, action)
        try { if (initialize) marker.initialize(feedback(0.0)); block(marker, action) } finally {
            try { marker.end(feedback(0.0), interrupted = true) } finally {
                marker.reset(); action.reset()
                if (wasMocked) RobotClock.useMockTime(old) else RobotClock.useSystemTime()
            }
        }
    }

    @Test fun `failed action fails its marker while retaining child neutral output`() = withMarker { marker, action ->
        action.fail = true
        val actions = marker.execute(feedback(5.0), 0L)
        assertTrue(actions.any { it === action.neutral })
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(marker))
        assertFalse(marker.isCompleted(feedback(5.0), 0L))
    }

    @Test fun `cancelled action cancels its marker instead of becoming successful`() = withMarker { marker, action ->
        action.cancel = true
        marker.execute(feedback(5.0), 0L)
        assertEquals(TaskStatus.CANCELLED, TaskStateMachine.getStatus(marker))
        assertFalse(marker.isCompleted(feedback(5.0), 0L))
    }

    @Test fun `successful action completes exactly once after crossing progress`() = withMarker { marker, action ->
        action.completes = true
        marker.execute(feedback(4.9), 0L)
        assertEquals(0, action.executions)
        marker.execute(feedback(5.0), 0L)
        marker.execute(feedback(7.0), 0L)
        assertTrue(marker.isCompleted(feedback(7.0), 0L))
        assertEquals(1, action.executions)
        assertEquals(1, action.ends)
    }

    @Test fun `endpoint marker fires inside the drive controllers accepted position tolerance`() = withMarker(1.0, 1.0) { marker, action ->
        marker.execute(feedback(0.97), 0L)
        assertEquals(1, action.executions)
    }

    @Test fun `stale feedback cannot trigger an action`() = withMarker { marker, action ->
        RobotClock.useMockTime(2000L)
        marker.execute(feedback(10.0, 1000L), 1000L)
        assertEquals(0, action.executions)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(marker))
    }

    @Test fun `invalid normalized progress rejects construction`() {
        for (progress in listOf(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                StarterFrcDriveMarkerTask(Pose2d(), progress, Action())
            }
        }
    }

    @Test fun `untriggered marker releases the child callback registry`() = withMarker { marker, action ->
        var callbacks = 0
        action.onComplete { callbacks++ }
        marker.end(feedback(0.0), interrupted = true)
        TaskCallbacks.invokeComplete(action)
        assertEquals(0, callbacks)
        assertEquals(1, action.releases)
        assertEquals(0, action.ends)
    }

    @Test fun `normal owner teardown cannot leave an unfinished child running`() = withMarker { marker, action ->
        marker.execute(feedback(5.0), 0L)
        val cleanup = marker.end(feedback(5.0), interrupted = false)
        assertTrue(cleanup.any { it === action.neutral })
        assertEquals(1, action.ends)
        assertEquals(1, action.releases)
        assertNotEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(marker))
    }

    @Test fun `pausing a triggered marker forwards neutral and suspends the child clock`() = withMarker { marker, action ->
        action.withTimeout(50L)
        marker.execute(feedback(5.0), 0L)
        RobotClock.useMockTime(1020L)
        val neutral = marker.pause(feedback(5.0))
        assertTrue(neutral.any { it === action.neutral })
        assertEquals(1, action.pauses)
        RobotClock.useMockTime(2000L)
        marker.resume(feedback(5.0))
        marker.execute(feedback(5.0), 20L)
        assertEquals(1, action.resumes)
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(action))
    }

    @Test fun `nonfinite progress geometry cannot remain a running marker`() = withMarker { marker, action ->
        marker.execute(feedback(Double.NaN), 0L)
        assertEquals(0, action.executions)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(marker))
    }

    @Test fun `expired marker cannot start its action after default execute fails`() = withMarker { marker, action ->
        marker.withTimeout(10L)
        RobotClock.useMockTime(1020L)
        marker.execute(feedback(5.0), 20L)
        assertEquals(0, action.executions)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(marker))
    }

    @Test fun `zero length translation triggers once at the start`() = withMarker(1.0, 0.0) { marker, action ->
        action.completes = true
        marker.execute(feedback(0.0), 0L)
        marker.execute(feedback(0.0), 0L)
        assertEquals(1, action.executions)
        assertTrue(marker.isCompleted(feedback(0.0), 0L))
    }

    @Test fun `failed marker fails the deadline group and prevents arrival work`() = withMarker(0.0, initialize = false) { marker, action ->
        action.fail = true
        val arrival = Action()
        val drive = StarterFrcDriveToPoseTask(Pose2d(10.0, 0.0), StarterFrcMotionPreset.SAFE)
        val group = ParallelDeadlineGroup(drive, listOf(marker))
        val parent = SequentialTaskGroup(listOf(group, arrival))
        val executor = TaskExecutor().also { it.addTask(parent) }
        val store = com.areslib.Store(initialState = feedback(0.0))
        try {
            executor.update(store.state, 1000L).forEach(store::dispatch)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(parent))
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(group))
            assertEquals(0, arrival.executions)
            assertEquals(0, executor.size)
            assertEquals(0.0, store.state.drive.xVelocityMetersPerSecond)
            assertEquals(1, action.ends)
        } finally { executor.cancelAll(store.state); parent.reset(); group.reset(); drive.reset(); arrival.reset() }
    }

    @Test fun `endpoint marker starts before a real drive deadline completes inside tolerance`() = withMarker(1.0, 1.0, initialize = false) { marker, action ->
        action.completes = true
        val drive = StarterFrcDriveToPoseTask(Pose2d(1.0, 0.0), StarterFrcMotionPreset.SAFE)
        val group = ParallelDeadlineGroup(drive, listOf(marker))
        val executor = TaskExecutor().also { it.addTask(group) }
        try {
            executor.update(feedback(0.0), 1000L)
            for (now in listOf(1020L, 1040L, 1060L)) {
                RobotClock.useMockTime(now)
                executor.update(feedback(0.97), now)
            }
            assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(group))
            assertEquals(1, action.executions)
            assertEquals(1, action.ends)
            assertEquals(0, executor.size)
        } finally { executor.cancelAll(feedback(0.97)); group.reset(); drive.reset() }
    }

    @Test fun `untriggered repeated teardown releases child metadata only once`() = withMarker { marker, action ->
        marker.end(feedback(0.0), interrupted = true)
        marker.end(feedback(0.0), interrupted = true)
        marker.releaseRuntimeState()
        marker.releaseRuntimeState()
        assertEquals(1, action.releases)
        assertEquals(0, action.ends)
    }

    @Test fun `metadata cancellation releases the nested task without pretending to dispatch hardware cleanup`() = withMarker { marker, action ->
        marker.execute(feedback(5.0), 0L)
        marker.cancel()
        assertEquals(TaskStatus.CANCELLED, TaskStateMachine.getStatus(marker))
        assertEquals(TaskStatus.CANCELLED, TaskStateMachine.getStatus(action))
        assertEquals(1, action.releases)
        assertEquals(0, action.ends)
        marker.execute(feedback(10.0), 0L)
        assertEquals(1, action.executions)
    }

    @Test fun `marker can be reused after proper terminal cleanup without an old executor child`() = withMarker { marker, action ->
        marker.execute(feedback(5.0), 0L)
        marker.end(feedback(5.0), interrupted = true)
        marker.releaseRuntimeState()
        RobotClock.useMockTime(2000L)
        marker.initialize(feedback(0.0))
        marker.execute(feedback(5.0), 0L)
        assertEquals(2, action.executions)
        assertEquals(1, action.ends)
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(marker))
    }

    @Test fun `reversed feedback cannot trigger a waiting marker`() = withMarker { marker, action ->
        RobotClock.useMockTime(1020L)
        marker.execute(feedback(4.0), 20L)
        RobotClock.useMockTime(1040L)
        marker.execute(feedback(5.0, 1000L), 40L)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(marker))
        assertEquals(0, action.executions)
    }

    @Test fun `invalid target and missing initial feedback fail before any child work`() {
        for (target in listOf(Pose2d(Double.NaN, 0.0), Pose2d(0.0, Double.POSITIVE_INFINITY),
            Pose2d(0.0, 0.0, com.areslib.math.geometry.Rotation2d(Double.NaN)))) {
            assertThrows(IllegalArgumentException::class.java) { StarterFrcDriveMarkerTask(target, 0.5, Action()) }
        }
        withMarker { marker, action ->
            marker.initialize(feedback(0.0, -1L))
            marker.execute(feedback(10.0), 0L)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(marker))
            assertEquals(0, action.executions)
        }
    }

    @Test fun `marker reuse retains an explicitly configured timeout`() = withMarker { marker, action ->
        marker.withTimeout(30L)
        marker.end(feedback(0.0), interrupted = true)
        marker.releaseRuntimeState()
        RobotClock.useMockTime(2000L)
        marker.initialize(feedback(0.0))
        RobotClock.useMockTime(2040L)
        marker.execute(feedback(5.0), 40L)
        assertEquals(0, action.executions)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(marker))
    }
}
