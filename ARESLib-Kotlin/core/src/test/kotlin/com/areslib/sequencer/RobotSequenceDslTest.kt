package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.pathing.CommandKey
import com.areslib.pathing.NamedCommands
import com.areslib.state.Alliance
import com.areslib.state.DriveState
import com.areslib.state.RobotState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RobotSequenceDslTest {
    @AfterEach
    fun clearCommands() {
        NamedCommands.clear()
    }

    @Test
    fun `typed sequence makes nested control flow visible`() {
        val task = robotSequence {
            dispatch(RobotAction.SetAlliance(Alliance.RED, timestampMs = 10L))
            parallel {
                waitFor(100.milliseconds)
                sequence {
                    waitFor(20.milliseconds)
                    waitFor(30.milliseconds)
                }
            }
            race {
                waitFor(1.seconds)
                waitFor(2.seconds)
            }
        }

        assertTrue(task is SequentialTaskGroup)
        assertTrue(task.name.contains("Parallel"))
        assertTrue(task.name.contains("ParallelRace"))
    }

    @Test
    fun `empty task groups fail while the auto is being built`() {
        assertThrows(IllegalArgumentException::class.java) {
            robotSequence { parallel {} }
        }
        assertThrows(IllegalArgumentException::class.java) {
            robotSequence { race {} }
        }
    }

    @Test
    fun `invalid durations fail before a match starts`() {
        assertThrows(IllegalArgumentException::class.java) {
            robotSequence { waitFor((-1).milliseconds) }
        }
    }

    @Test
    fun `named commands expose metadata and create fresh task instances`() {
        var created = 0
        val key = CommandKey("collect_piece")
        NamedCommands.register(key, "Run the intake until a piece is detected", "Intake") { _ ->
            created++
            TimeWaitTask(10L)
        }

        val first = NamedCommands.create(key, 1L)
        val second = NamedCommands.create(key, 2L)

        assertNotSame(first, second)
        assertEquals(2, created)
        assertEquals(key, NamedCommands.catalog().single().key)
        assertEquals("Intake", NamedCommands.catalog().single().category)
    }

    @Test
    fun `named command resources participate in parallel conflict validation`() {
        val firstKey = CommandKey("collect.first")
        val secondKey = CommandKey("collect.second")
        NamedCommands.register(
            firstKey,
            "Run the first intake action",
            requiredResources = TaskResources.INTAKE
        ) { _ ->
            StateActionTask("first", TaskResources.INTAKE) {
                RobotAction.SetAlliance(Alliance.RED, timestampMs = 1L)
            }
        }
        NamedCommands.register(
            secondKey,
            "Run the second intake action",
            requiredResources = TaskResources.INTAKE
        ) { _ ->
            StateActionTask("second", TaskResources.INTAKE) {
                RobotAction.SetAlliance(Alliance.RED, timestampMs = 1L)
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            robotSequence {
                parallel {
                    namedCommand(firstKey)
                    namedCommand(secondKey)
                }
            }
        }
    }

    @Test
    fun `named command factory must match its advertised resources`() {
        val key = CommandKey("misdeclared")
        NamedCommands.register(
            key,
            "Incorrect resource declaration",
            requiredResources = TaskResources.INTAKE
        ) { _ -> TimeWaitTask(10L) }

        val error = assertThrows(IllegalArgumentException::class.java) {
            NamedCommands.create(key, 1L)
        }
        assertTrue(error.message.orEmpty().contains("misdeclared"))
    }

    @Test
    fun `missing deferred command fails during initialization with its key`() {
        val task = NamedCommands.task(CommandKey("not_registered"))
        val error = assertThrows(IllegalArgumentException::class.java) {
            task.initialize(RobotState())
        }
        assertTrue(error.message.orEmpty().contains("not_registered"))
    }

    private data class SetTargetAction(val target: Double) : RobotAction
    private data class CollectAction(val power: Double) : RobotAction

    @Test
    fun `shared vocabulary aliases and state-aware dispatch execute deterministically`() {
        NamedCommands.register(
            key = CommandKey("intake.collect"),
            description = "Collect game piece",
        ) {
            ActionDispatchTask(CollectAction(1.0))
        }

        val sequence = robotSequence {
            together {
                step("intake.collect")
                waitSeconds(0.02)
            }
            firstToFinish {
                waitSeconds(0.01)
                waitSeconds(5.0)
            }
            deadline(
                main = { waitSeconds(0.02) },
                companions = {
                    dispatch("setHeadingTarget") { state ->
                        SetTargetAction(state.drive.odometryHeading * 2.0)
                    }
                },
            )
        }

        val state = RobotState(drive = DriveState(odometryHeading = 1.25))
        val dispatched = mutableListOf<RobotAction>()
        dispatched += sequence.initialize(state)
        var elapsedMs = 0L
        while (!sequence.isCompleted(state, elapsedMs) && elapsedMs <= 500L) {
            dispatched += sequence.execute(state, elapsedMs)
            elapsedMs += 10L
        }
        dispatched += sequence.end(state, interrupted = false)

        assertTrue(sequence.isCompleted(state, elapsedMs))
        assertTrue(dispatched.contains(CollectAction(1.0)))
        assertTrue(dispatched.contains(SetTargetAction(2.5)))
        assertEquals(2, dispatched.size)
    }
}
