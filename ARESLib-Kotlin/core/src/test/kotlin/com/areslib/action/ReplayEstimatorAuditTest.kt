package com.areslib.action

import com.areslib.Store
import com.google.gson.Gson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayEstimatorAuditTest {
    @TempDir lateinit var directory: File

    @Test fun `offline replay reconstructs the live store odometry estimator`() {
        val actions = listOf(
            RobotAction.PoseUpdate(0.0, 0.0, 0.0, 1000L, isReset = true),
            RobotAction.DriveHardwareUpdate(1.0, 0.0, 0.0, 0.02, 0.0, 0.0, 1020L),
            RobotAction.DriveHardwareUpdate(1.0, 0.0, 0.0, 0.02, 0.0, 0.0, 1040L))
        val store = Store()
        actions.forEach(store::dispatch)
        assertTrue(store.state.drive.poseEstimator.estimatedPoseX > 0.01)
        val log = File(directory, "odometry.jsonl")
        val gson = Gson()
        log.writeText(actions.joinToString("\n") { action ->
            """{"schema_version":1,"type":"${action.javaClass.simpleName}","payload":${gson.toJson(action)}}"""
        })
        val replay = ActionReplay.replayLog(log)
        assertEquals(actions.size + 1, replay.size)
        assertEquals(store.state.drive.poseEstimator.estimatedPoseX,
            replay.last().drive.poseEstimator.estimatedPoseX, 1e-12)
        assertEquals(0.0, replay.first().drive.poseEstimator.estimatedPoseX)
        assertEquals(gson.toJsonTree(store.state), gson.toJsonTree(replay.last()))
    }

    @Test fun `replay preserves delayed vision corrections and retained earlier snapshots`() {
        val actions = listOf(
            RobotAction.PoseUpdate(0.0, 0.0, 0.0, 100L, isReset = true),
            RobotAction.PoseUpdate(1.0, 0.0, 0.0, 200L),
            RobotAction.VisionMeasurementsReceived(listOf(com.areslib.state.VisionMeasurement(
                timestampMs = 100L, tagId = 2, ambiguity = 0.01,
                targetPose = com.areslib.math.geometry.Pose3d(
                    com.areslib.math.geometry.Translation3d(0.25, 0.0, 0.0),
                    com.areslib.math.geometry.Rotation3d()))), timestampMs = 220L))
        val store = Store()
        val live = mutableListOf(store.state)
        for (action in actions) { store.dispatch(action); live += store.state }
        assertTrue(store.state.drive.poseEstimator.lastMeasurementAccepted)
        val gson = Gson()
        val log = File(directory, "vision.jsonl").also { file ->
            file.writeText(actions.joinToString("\n") {
                """{"schema_version":1,"type":"${it.javaClass.simpleName}","payload":${gson.toJson(it)}}"""
            })
        }
        com.areslib.util.RobotClock.useMockTime(-500L)
        try {
            val replayed = ActionReplay.replayLog(log)
            assertEquals(live.map(gson::toJsonTree), replayed.map(gson::toJsonTree))
            assertEquals(-500L, com.areslib.util.RobotClock.currentTimeMillis())
            assertEquals(0.0, replayed[1].drive.poseEstimator.estimatedPoseX)
            assertEquals(1.0, replayed[2].drive.poseEstimator.estimatedPoseX, 1e-12)
        } finally {
            com.areslib.util.RobotClock.useSystemTime()
        }
    }

    @Test fun `malformed later record prevents any reducer execution`() {
        val log = File(directory, "partial.jsonl").also { it.writeText(
            """{"schema_version":1,"type":"SetAlliance","payload":{"alliance":"RED","timestampMs":1}}""" +
                "\n{broken") }
        var calls = 0
        val error = kotlin.test.assertFailsWith<ActionReplayException> {
            ActionReplay.replayLog(log) { state, action ->
                calls++
                com.areslib.reducer.rootReducer(state, action)
            }
        }
        assertEquals(0, calls)
        assertTrue(error.message.orEmpty().contains(":2:"))
    }
}
