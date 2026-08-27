package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import com.areslib.pathing.*
import com.areslib.subsystem.*
import com.areslib.Store
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows

class MockTestTask(
    override val name: String,
    private val durationMs: Long,
    private val actionToDispatch: RobotAction? = null,
    override val requiredResources: Long = TaskResources.NONE
) : Task {
    var initialized = false
    var ended = false
    var interrupted = false

    override fun initialize(state: RobotState): List<RobotAction> {
        initialized = true
        return actionToDispatch?.let { listOf(it) } ?: emptyList()
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        return elapsedMs >= durationMs
    }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        ended = true
        this.interrupted = interrupted
        return emptyList()
    }
}

class TaskGroupTest {

    @Test
    fun `parallel groups reject overlapping task resources at construction`() {
        val first = MockTestTask("first drive", 100L, requiredResources = TaskResources.DRIVE)
        val second = MockTestTask("second drive", 100L, requiredResources = TaskResources.DRIVE)

        val failure = assertThrows(IllegalArgumentException::class.java) {
            ParallelTaskGroup(listOf(first, second))
        }

        assertTrue(failure.message.orEmpty().contains("drive"))
    }

    @Test
    fun `parallel groups allow disjoint resources and sequential groups allow reuse`() {
        val drive = MockTestTask("drive", 100L, requiredResources = TaskResources.DRIVE)
        val intake = MockTestTask("intake", 100L, requiredResources = TaskResources.INTAKE)
        val secondDrive = MockTestTask("second drive", 100L, requiredResources = TaskResources.DRIVE)

        val parallel = ParallelTaskGroup(listOf(drive, intake))
        val sequential = SequentialTaskGroup(listOf(drive, secondDrive))

        assertEquals(TaskResources.DRIVE or TaskResources.INTAKE, parallel.requiredResources)
        assertEquals(TaskResources.DRIVE, sequential.requiredResources)
    }

    @Test
    fun `test SequentialTaskGroup execution order`() {
        val action1 = RobotAction.PathEventTriggered("Event1", 100L)
        val action2 = RobotAction.PathEventTriggered("Event2", 200L)
        
        val task1 = MockTestTask("Task1", 100L, action1)
        val task2 = MockTestTask("Task2", 150L, action2)
        val sequence = SequentialTaskGroup(listOf(task1, task2))

        val state = RobotState()
        
        // Initialize sequence: task1 should initialize
        val initActions = sequence.initialize(state)
        assertTrue(task1.initialized)
        assertFalse(task2.initialized)
        assertEquals(1, initActions.size)
        assertEquals(action1, initActions[0])

        // First tick at 50ms: task1 not completed
        assertFalse(sequence.isCompleted(state, 50L))
        
        // Second tick at 100ms: task1 completed, triggers task1 end and task2 initialize
        assertFalse(sequence.isCompleted(state, 100L))
        
        assertTrue(task1.ended)
        assertTrue(task2.initialized)
        
        // The execute call immediately following should return the pending actions (action2)
        val execActions = sequence.execute(state, 100L)
        assertEquals(1, execActions.size)
        assertEquals(action2, execActions[0])

        // Third tick at 200ms: task2 elapsed is 200 - 100 = 100ms (not yet completed)
        assertFalse(sequence.isCompleted(state, 200L))
        
        // Fourth tick at 250ms: task2 elapsed is 250 - 100 = 150ms (completed)
        assertTrue(sequence.isCompleted(state, 250L))
        assertTrue(task2.ended)
    }

    @Test
    fun `test ParallelTaskGroup concurrent execution`() {
        val action1 = RobotAction.PathEventTriggered("Event1", 100L)
        val action2 = RobotAction.PathEventTriggered("Event2", 200L)

        val task1 = MockTestTask("Task1", 100L, action1)
        val task2 = MockTestTask("Task2", 200L, action2)
        val parallel = ParallelTaskGroup(listOf(task1, task2))

        val state = RobotState()

        // Initialize: both child tasks initialize
        val initActions = parallel.initialize(state)
        assertTrue(task1.initialized)
        assertTrue(task2.initialized)
        assertEquals(2, initActions.size)

        // At 50ms: neither completed
        assertFalse(parallel.isCompleted(state, 50L))

        // At 100ms: task1 completed, task2 not completed
        assertFalse(parallel.isCompleted(state, 100L))
        assertTrue(task1.ended)
        assertFalse(task2.ended)

        // At 200ms: task2 completed, parallel completed
        assertTrue(parallel.isCompleted(state, 200L))
        assertTrue(task2.ended)
    }

    @Test
    fun `test FollowPathTask spline tracking`() {
        val mockJson = """
            {
              "waypoints": [
                {"anchor": {"x": 2.0, "y": 2.0}},
                {"anchor": {"x": 8.0, "y": 6.0}}
              ]
            }
        """.trimIndent()
        val path = PathPlannerParser.parsePath(mockJson)

        var speedsVx = 0.0
        var speedsVy = 0.0
        var speedsOmega = 0.0
        val mockDrivetrain = object : DrivetrainSubsystem {
            override fun setChassisSpeeds(vx: Double, vy: Double, omega: Double) {
                speedsVx = vx
                speedsVy = vy
                speedsOmega = omega
            }
            override fun getEstimatedPose(): com.areslib.math.geometry.Pose2d {
                return com.areslib.math.geometry.Pose2d(1.9, 1.9, com.areslib.math.geometry.Rotation2d.fromDegrees(0.0))
            }
            override fun readSensors(store: Store, timestampMs: Long) {}
            override fun writeOutputs(state: RobotState, scale: Double) {}
            override fun close() {}
        }

        val follower = HolonomicPathFollower(mockDrivetrain)
        val task = FollowPathTask(follower, path)

        com.areslib.util.RobotClock.useMockTime(1000L)
        var state = RobotState()
        val initActions = task.initialize(state)
        assertEquals(1, initActions.size)
        assertTrue(initActions[0] is RobotAction.SwitchPath)

        state = com.areslib.reducer.rootReducer(state, initActions[0])

        com.areslib.util.RobotClock.useMockTime(1020L)
        val execActions = task.execute(state, 20L)
        assertEquals(1, execActions.size)
        assertTrue(execActions[0] is RobotAction.UpdatePathProgress)

        assertTrue(speedsVx > 0.0, "vx should be positive towards target")
        assertTrue(speedsVy > 0.0, "vy should be positive towards target")
        assertTrue(speedsOmega >= 0.0, "omega should be positive towards target")

        task.end(state, interrupted = false)
        assertEquals(0.0, speedsVx)
        assertEquals(0.0, speedsVy)
        
        com.areslib.util.RobotClock.useSystemTime()
    }
}
