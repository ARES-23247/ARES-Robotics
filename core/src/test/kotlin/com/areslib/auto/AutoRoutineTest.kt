package com.areslib.auto

import com.areslib.action.RobotAction
import com.areslib.pathing.CommandKey
import com.areslib.pathing.TrajectoryPreset
import com.areslib.sequencer.StateActionTask
import com.areslib.state.Alliance
import com.areslib.state.RobotState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds

class AutoRoutineTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `code DSL creates the same nested document model used by the editor`() {
        val routine = autonomous("Two Piece") {
            startAt(1.0.meters, 2.0.meters, 90.degrees)
            together {
                driveTo(2.5.meters, 2.0.meters, 90.degrees, TrajectoryPreset.SAFE)
                run(CommandKey("intake.collect"))
            }
            waitFor(250.milliseconds)
        }

        assertEquals("two-piece", routine.documentId)
        assertEquals(AutoStepKind.TOGETHER, routine.steps.first().kind)
        assertEquals(2, routine.steps.first().children.size)
        assertEquals(0.25, routine.steps.last().durationSeconds)
    }

    @Test
    fun `codec rejects payloads that conflict with the declared step kind`() {
        val invalid = AutoRoutine(
            documentId = "invalid",
            name = "Invalid",
            startingPose = AutoPose(0.0, 0.0, 0.0),
            steps = listOf(
                AutoStep(
                    kind = AutoStepKind.WAIT,
                    commandKey = "intake.stop",
                    durationSeconds = 0.5
                )
            )
        )

        val error = assertThrows(IllegalArgumentException::class.java) { AresAutoCodec.encode(invalid) }
        assertTrue(error.message.orEmpty().contains("do not belong"))
    }

    @Test
    fun `state action task observes state at initialization rather than registration`() {
        val task = StateActionTask("Choose alliance") { state ->
            RobotAction.SetAlliance(state.drive.alliance, timestampMs = 42L)
        }
        val state = RobotState().copy(drive = RobotState().drive.copy(alliance = Alliance.RED))

        val action = task.initialize(state).single() as RobotAction.SetAlliance

        assertEquals(Alliance.RED, action.alliance)
        assertTrue(task.isCompleted(state, 0L))
    }

    @Test
    fun `file loader prefers deployed autos and rejects traversal`() {
        val deploy = temporaryDirectory.resolve("deploy/ares/autos").createDirectories()
        val routine = autonomous("Simple") {
            startAt(0.meters, 0.meters)
            waitFor(100.milliseconds)
        }
        deploy.resolve("simple.aresauto").writeText(AresAutoCodec.encode(routine))

        val loaded = AresAutoFileLoader.load("simple", listOf(deploy.toFile()))

        assertEquals(routine, loaded)
        assertThrows(IllegalArgumentException::class.java) {
            AresAutoFileLoader.load("../simple", listOf(deploy.toFile()))
        }
    }
}
