package com.areslib.action

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Vector3
import com.areslib.pathing.Path
import com.areslib.pathing.PathEvent
import com.areslib.pathing.PathPoint
import com.areslib.reducer.rootReducer
import com.areslib.state.Alliance
import com.areslib.state.DriveMode
import com.areslib.state.SubsystemState
import com.areslib.state.TuningState
import com.google.gson.Gson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private data class ReplayTestState(val value: Int) : SubsystemState
private data class UnregisteredReplayTestState(val value: Int) : SubsystemState

class ActionReplayTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `every core action round trips in order with timestamps and final state`() {
        ActionReplay.registerSubsystemState(ReplayTestState::class.java)
        val path = Path(
            points = listOf(
                PathPoint(Pose2d(1.0, 2.0, Rotation2d(0.3)), 1.5, 0.0, 0.1, 0.2),
                PathPoint(Pose2d(2.0, 3.0, Rotation2d(0.4)), 1.0, 1.0, 0.2, 0.3)
            ),
            events = listOf(PathEvent("shoot", 0.5))
        )
        val actions: List<RobotAction> = listOf(
            RobotAction.DriveHardwareUpdate(1.0, 2.0, 0.3, 0.1, 0.2, 0.03, 1L),
            RobotAction.VisionMeasurementsReceived(emptyList(), 2L, Vector3(0.1, 0.2, 0.3), false),
            RobotAction.PoseUpdate(3.0, 4.0, 0.5, 3L, isReset = true),
            RobotAction.SetAlliance(Alliance.RED, 4L),
            RobotAction.SetDriveMode(DriveMode.POSITION_HOLD, 5L),
            RobotAction.SetHeadingLockTarget(0.8, 6L),
            RobotAction.CalibrateSwerveOffsets(7L),
            RobotAction.SetPositionLockTarget(5.0, 6.0, 8L),
            RobotAction.JoystickDriveIntent(0.1, 0.2, 0.3, 9L, false, true, true),
            RobotAction.PathEventTriggered("marker", 10L),
            RobotAction.RoutineRequested(1L, "routine", 11L),
            RobotAction.RoutineStarted(1L, "routine", 12L),
            RobotAction.RoutineStepEntered(1L, "routine", "steps/0", "wait", 13L),
            RobotAction.RoutineCompleted(1L, "routine", 14L),
            RobotAction.RoutineFailed(2L, "failed", "reason", 15L),
            RobotAction.RoutineCancelled(3L, "cancelled", "operator", 16L),
            RobotAction.UpdateSubsystemState(ReplayTestState(17), 17L),
            RobotAction.UpdateNamedSubsystemState("arm", ReplayTestState(18), 18L),
            RobotAction.SetIndicatorLight("status", 0.25, 19L),
            RobotAction.SetPrismDriver("prism", 1200, 20L),
            RobotAction.ChainPaths(listOf(path), 2.5, 1.25, 21L),
            RobotAction.SwitchPath(path, true, 0.4, 22L),
            RobotAction.UpdatePathProgress(0.8, 0.1, 0.2, 0.3, 23L),
            RobotAction.UpdateTuningState(TuningState(), 24L),
            StartCalibrationSweep(0.4, 1, 25L),
            CalibrationFrameLogged(0.5, 7, 1, doubleArrayOf(1.0, 2.0, 3.0), 26L)
        )
        assertEquals(actions.map { it.javaClass }.toSet(), ActionReplay.builtInActionClasses())

        val logger = ActionLogger(runId = "round-trip", logDirectory = tempDir)
        actions.forEach(logger::logAction)
        logger.stop()
        val log = tempDir.listFiles().orEmpty().single { it.extension == "jsonl" }

        val decoded = ActionReplay.parseActions(log)
        assertEquals(actions.size, decoded.size)
        assertEquals(actions.map { it.javaClass }, decoded.map { it.javaClass })
        assertEquals(actions.map { it.timestampMs }, decoded.map { it.timestampMs })
        val gson = Gson()
        assertEquals(
            actions.map { gson.toJsonTree(it, it.javaClass) },
            decoded.map { gson.toJsonTree(it, it.javaClass) }
        )

        val expectedFinal = actions.fold(com.areslib.state.RobotState(), ::rootReducer)
        val replayedStates = ActionReplay.replayLog(log)
        assertEquals(actions.size + 1, replayedStates.size)
        assertEquals(gson.toJsonTree(expectedFinal), gson.toJsonTree(replayedStates.last()))
        assertEquals(26L, replayedStates.last().timestampMs)
    }

    @Test
    fun `unknown action and unsupported schema fail visibly`() {
        val unknown = File(tempDir, "unknown.jsonl")
        unknown.writeText("""{"schema_version":1,"type":"NotARealAction","payload":{}}""")
        val unknownFailure = assertFailsWith<ActionReplayException> {
            ActionReplay.parseActions(unknown)
        }
        assertTrue(unknownFailure.message.orEmpty().contains("Unknown action type 'NotARealAction'"))

        val future = File(tempDir, "future.jsonl")
        future.writeText("""{"schema_version":2,"type":"SetAlliance","payload":{}}""")
        val versionFailure = assertFailsWith<ActionReplayException> {
            ActionReplay.parseActions(future)
        }
        assertTrue(versionFailure.message.orEmpty().contains("Unsupported action-log schema_version"))
    }

    @Test
    fun `season subsystem state requires its explicit codec registration`() {
        val logger = ActionLogger(runId = "season", logDirectory = tempDir)
        logger.logAction(RobotAction.UpdateSubsystemState(UnregisteredReplayTestState(4), 5L))
        logger.stop()
        val log = tempDir.listFiles().orEmpty().single { it.extension == "jsonl" }

        val failure = assertFailsWith<ActionReplayException> { ActionReplay.parseActions(log) }
        assertTrue(failure.message.orEmpty().contains("registerSubsystemState"))
    }
}
