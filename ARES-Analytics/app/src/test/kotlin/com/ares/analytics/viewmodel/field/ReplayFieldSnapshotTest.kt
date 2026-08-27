package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.shared.TelemetryFrame
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayFieldSnapshotTest {
    @Test
    fun `packed simulator pose preserves truth ekf and odometry as distinct sources`() {
        val values = (0..9).associate { index -> "ARES/SimulatorPoseFrame/$index" to (index + 0.25) }
        val state = ReplayFrame(1_000, values).toReplayPoseState()

        assertTrue(state.hasTruePoseData)
        assertEquals(0.25, state.trueX)
        assertEquals(3.25, state.ekfX)
        assertEquals(6.25, state.odomX)
        assertEquals(2.25, state.trueHeading)
        assertEquals(5.25, state.ekfHeading)
        assertEquals(8.25, state.odomHeading)
    }

    @Test
    fun `missing truth remains missing instead of borrowing estimator data`() {
        val state = ReplayFrame(
            timestampMs = 1_000,
            values = mapOf(
                "ARES/EstimatedPose/0" to 1.0,
                "ARES/EstimatedPose/1" to 2.0,
                "ARES/EstimatedPose/2" to 0.5,
            ),
        ).toReplayPoseState()

        assertFalse(state.hasTruePoseData)
        assertEquals(1.0, state.ekfX)
        assertNull(state.odomX)
    }

    @Test
    fun `healthy camera with no target clears only vision observations`() {
        val state = ReplayFrame(
            timestampMs = 1_000,
            values = mapOf(
                "Vision/HasTarget" to 0.0,
                "Vision/Pose_X" to 9.0,
                "Vision/Pose_Y" to 8.0,
                "Vision/Pose_Heading" to 0.7,
            ),
        ).toReplayPoseState()

        assertFalse(state.visionHasTarget)
        assertNull(state.visionX)
        assertTrue(state.visionPoses.isEmpty())
    }

    @Test
    fun `field trace prefers recorded truth and joins scalar samples by source timestamp`() = runBlocking {
        val directory = Files.createTempDirectory("ares-replay-field-trace").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try {
            val frames = buildList {
                listOf(1_000L, 1_100L).forEachIndexed { index, time ->
                    val us = time * 1_000L
                    add(TelemetryFrame(time, "trace", "ARES/EstimatedPose/0", 90.0 + index, timestampUs = us, sampleOrder = 1))
                    add(TelemetryFrame(time, "trace", "ARES/EstimatedPose/1", 80.0 + index, timestampUs = us, sampleOrder = 2))
                    add(TelemetryFrame(time, "trace", "ARES/EstimatedPose/2", 70.0 + index, timestampUs = us, sampleOrder = 3))
                    add(TelemetryFrame(time, "trace", "ARES/TruePose/0", 1.0 + index, timestampUs = us, sampleOrder = 4))
                    add(TelemetryFrame(time, "trace", "ARES/TruePose/1", 2.0 + index, timestampUs = us, sampleOrder = 5))
                    add(TelemetryFrame(time, "trace", "ARES/TruePose/2", 0.25 + index, timestampUs = us, sampleOrder = 6))
                }
            }
            database.insertTelemetryFrames(frames)

            val trace = loadReplayFieldTrace(database, "trace", 1_000, 1_100, maxPoints = 20)

            assertEquals(2, trace.size)
            assertEquals(1.0, trace.first().x)
            assertEquals(2.0, trace.first().y)
            assertEquals(0.25, trace.first().headingRad)
            assertEquals(2.0, trace.last().x)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
