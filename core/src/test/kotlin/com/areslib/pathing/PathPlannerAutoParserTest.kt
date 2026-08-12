package com.areslib.pathing

import com.areslib.sequencer.*
import com.areslib.state.RobotState
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.subsystem.DrivetrainSubsystem
import com.areslib.Store
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PathPlannerAutoParserTest {

    private fun createMockFollower(): HolonomicPathFollower {
        val mockDrivetrain = object : DrivetrainSubsystem {
            override fun setChassisSpeeds(vx: Double, vy: Double, omega: Double) {}
            override fun getEstimatedPose(): Pose2d = Pose2d()
            override fun readSensors(store: Store, timestampMs: Long) {}
            override fun writeOutputs(state: RobotState, scale: Double) {}
        }
        return HolonomicPathFollower(mockDrivetrain)
    }

    private class MockConditionTask(
        override val name: String,
        private val completeAfterUpdates: Int
    ) : Task {
        var updates = 0
        var isInitCalled = false
        var isEndCalled = false

        override fun initialize(state: RobotState): List<com.areslib.action.RobotAction> {
            isInitCalled = true
            return emptyList()
        }

        override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
            updates++
            return updates >= completeAfterUpdates
        }

        override fun end(state: RobotState, interrupted: Boolean): List<com.areslib.action.RobotAction> {
            isEndCalled = true
            return emptyList()
        }
    }

    @Test
    fun testNamedCommandsRegistry() {
        NamedCommands.clear()
        NamedCommands.register(CommandKey("TestCmd"), "Test command") { _ ->
            MockConditionTask("MockTask", 1)
        }

        val resolved = NamedCommands.getCommand("TestCmd", 1000L)
        assertNotNull(resolved)
        assertNull(NamedCommands.getCommand("SetThirdIndicatorColor_RED", 1000L))
        assertEquals("MockTask", resolved!!.name)
    }

    @Test
    fun testParallelRaceGroup() {
        val t1 = MockConditionTask("Task1", 3)
        val t2 = MockConditionTask("Task2", 2) // Completes first
        val race = ParallelRaceGroup(listOf(t1, t2))

        val state = RobotState()
        race.initialize(state)
        assertTrue(t1.isInitCalled)
        assertTrue(t2.isInitCalled)

        // First update: neither complete
        assertFalse(race.isCompleted(state, 20))
        // Second update: t2 completes, race completes
        assertTrue(race.isCompleted(state, 40))

        // End race
        race.end(state, interrupted = false)
        assertTrue(t2.isEndCalled)
        // t1 was interrupted/ended too
        assertTrue(t1.isEndCalled)
    }

    @Test
    fun testParallelDeadlineGroup() {
        val deadline = MockConditionTask("DeadlineTask", 2)
        val other = MockConditionTask("OtherTask", 5) // Takes longer than deadline
        val group = ParallelDeadlineGroup(deadline, listOf(other))

        val state = RobotState()
        group.initialize(state)

        // Update 1
        assertFalse(group.isCompleted(state, 20))
        // Update 2 -> Deadline completes
        assertTrue(group.isCompleted(state, 40))

        group.end(state, interrupted = false)
        assertTrue(deadline.isEndCalled)
        // other task should have been ended/interrupted
        assertTrue(other.isEndCalled)
    }

    @Test
    fun testParseAutoJson() {
        NamedCommands.clear()
        NamedCommands.register(CommandKey("ActionKey"), "Test auto action") { _ ->
            MockConditionTask("MockAction", 1)
        }

        val mockAutoJson = """
            {
              "version": 1.0,
              "choreographed": false,
              "command": {
                "type": "sequential",
                "data": {
                  "commands": [
                    {
                      "type": "wait",
                      "data": {
                        "waitTime": 0.5
                      }
                    },
                    {
                      "type": "named",
                      "data": {
                        "name": "ActionKey"
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val follower = createMockFollower()
        val compiledTask = PathPlannerAutoParser.parseAuto(mockAutoJson, follower, 1000L)

        assertNotNull(compiledTask)
        assertTrue(compiledTask is SequentialTaskGroup)
        assertEquals("Sequential(TimeWait(500 ms), MockAction)", compiledTask.name)
    }
}
