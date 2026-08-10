package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.pathing.CommandKey
import com.areslib.pathing.NamedCommands
import com.areslib.state.Alliance
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
    fun `missing deferred command fails during initialization with its key`() {
        val task = NamedCommands.task(CommandKey("not_registered"))
        val error = assertThrows(IllegalArgumentException::class.java) {
            task.initialize(RobotState())
        }
        assertTrue(error.message.orEmpty().contains("not_registered"))
    }
}
