package org.aresfirst.starter.frc

import com.areslib.action.RobotAction
import com.areslib.math.estimation.PoseEstimatorSnapshot
import com.areslib.math.geometry.Pose2d
import com.areslib.routine.ownRoutineTaskTree
import com.areslib.sequencer.*
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StarterMarkerTransitionFailureAuditTest {
    @Test fun `owned action pause failure crosses the private marker boundary and blocks incoming work`() {
        val wasMocked = RobotClock.isMocked
        val old = RobotClock.currentTimeMillis()
        RobotClock.useMockTime(1000L)
        val neutral = RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0)
        var childEnds = 0
        val child = object : Task {
            override val name = "Failing pause action"
            override fun isCompleted(state: RobotState, elapsedMs: Long) = false
            override fun pause(state: RobotState): List<RobotAction> = error("Action pause failed")
            override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                childEnds++; super.end(state, interrupted); return listOf(neutral)
            }
        }
        var incomingStarts = 0
        val incoming = object : Task {
            override val name = "Incoming action"
            override fun initialize(state: RobotState): List<RobotAction> { incomingStarts++; return super.initialize(state) }
            override fun isCompleted(state: RobotState, elapsedMs: Long) = false
        }
        val marker = StarterFrcDriveMarkerTask(Pose2d(10.0, 0.0), 0.0, ownRoutineTaskTree(child))
        val root = ownRoutineTaskTree(marker)
        val executor = TaskExecutor().also { it.addTask(root) }
        val initial = RobotState()
        val state = initial.copy(drive = initial.drive.copy(measuredMotionValid = true,
            poseEstimator = PoseEstimatorSnapshot(lastObservationTimestampMs = 1000L)))
        try {
            executor.update(state, 1000L)
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(child))
            val actions = executor.preempt(incoming, state, 1000L)
            assertEquals(0, incomingStarts)
            assertTrue(actions.any { it === neutral })
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(root))
            assertEquals(1, childEnds)
            assertEquals(0, executor.size)
        } finally {
            try { executor.cancelAll(state); root.releaseRuntimeState(); root.reset(); marker.reset(); child.reset(); incoming.reset() }
            finally { if (wasMocked) RobotClock.useMockTime(old) else RobotClock.useSystemTime() }
        }
    }
}
