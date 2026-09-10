package com.areslib.sequencer

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.control.feedback.PIDController
import com.areslib.math.geometry.Pose2d
import com.areslib.pathing.*
import com.areslib.state.RobotState
import com.areslib.subsystem.DrivetrainSubsystem
import com.areslib.util.RobotClock
import org.junit.jupiter.api.Test
import kotlin.test.*

class TaskCompletionPathTest {
    private class Drive : DrivetrainSubsystem {
        var vx = 0.0
        override fun setChassisSpeeds(vx: Double, vy: Double, omega: Double) { this.vx = vx }
        override fun getEstimatedPose() = Pose2d()
        override fun readSensors(store: Store, timestampMs: Long) = Unit
        override fun writeOutputs(state: RobotState, scale: Double) = Unit
        override fun close() = Unit
    }

    @Test fun `overdue marker completion fails path and interrupts marker`() { runMarker("elapsed") }
    @Test fun `marker normal end crossing deadline still interrupts marker`() { runMarker("end") }
    @Test fun `throwing marker normal end remains owned for interrupted cleanup`() { runMarker("throw") }

    private fun runMarker(mode: String) {
        RobotClock.useMockTime(0)
        val state = RobotState()
        val endings = mutableListOf<Boolean>()
        var ready = false
        var completions = 0
        val marker = object : Task {
            override val name = "marker-$mode"
            override fun isCompleted(state: RobotState, elapsedMs: Long) = ready
            override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                endings.add(interrupted)
                if (!interrupted && mode == "end") RobotClock.useMockTime(100)
                if (!interrupted && mode == "throw") error("marker cleanup")
                return super.end(state, interrupted)
            }
        }.withTimeout(10).onComplete { completions++ }
        NamedCommands.register(CommandKey("completion-marker"), "Audit") { marker }
        val drive = Drive()
        val follower = HolonomicPathFollower(drive, PIDController(0.0, 0.0, 0.0),
            PIDController(0.0, 0.0, 0.0), PIDController(0.0, 0.0, 0.0))
        val path = Path(listOf(PathPoint(Pose2d(), 1.0, 0.0), PathPoint(Pose2d(2.0, 0.0), 1.0, 2.0)),
            listOf(PathEvent("completion-marker", 0.0)))
        val task = FollowPathTask(follower, path, mirrorForAlliance = false)
        val executor = TaskExecutor()
        try {
            executor.addTask(task)
            executor.update(state, 0)
            RobotClock.useMockTime(1)
            executor.update(state, 1)
            assertTrue(drive.vx > 0.0)
            ready = true
            val next = if (mode == "elapsed") 12L else 2L
            synchronized(TaskTimeoutManager) {
                RobotClock.useMockTime(next)
                executor.update(state, next)
            }
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertEquals(0.0, drive.vx)
            assertEquals(0, completions)
            assertEquals(if (mode == "elapsed") listOf(true) else listOf(false, true), endings)
            assertEquals(0, executor.size)
        } finally {
            executor.cancelAll(state)
            task.reset(); marker.reset(); NamedCommands.clear(); RobotClock.useSystemTime()
        }
    }
}
