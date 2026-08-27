package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryFrame
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoSyncServiceTest {

    @Test
    fun `aligned video seek maps to replay percentage and clamps both ends`() = runTest {
        val tempDir = Files.createTempDirectory("ares-video-sync").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        val replay = ReplayEngineService(database)
        val videoSync = VideoSyncService(replay)
        try {
            database.insertTelemetryFrames(
                listOf(
                    TelemetryFrame(1000, "session", "Drive/X", 1.0),
                    TelemetryFrame(3000, "session", "Drive/X", 3.0)
                )
            )
            replay.loadSession("session")
            videoSync.setVideoDuration(10_000)
            videoSync.alignTimestamp(videoTimeMs = 500, logTimeMs = 1000)
            assertEquals(500L, videoSync.logOffsetMs.value)

            videoSync.seekVideo(1500)
            assertEquals(0.5, replay.progress.value, 1e-9)
            // Between samples replay correctly exposes the most recent actual frame.
            assertEquals(1000L, replay.currentFrame.value?.timestampMs)

            videoSync.seekVideo(-50)
            assertEquals(0.0, replay.progress.value, 1e-9)

            videoSync.seekVideo(50_000)
            assertEquals(1.0, replay.progress.value, 1e-9)

            // Test adjustOffset
            videoSync.adjustOffset(250)
            assertEquals(750L, videoSync.logOffsetMs.value)

            // Test play and pause delegation
            videoSync.play()
            assertEquals(ReplayState.PLAYING, replay.state.value)
            videoSync.pause()
            assertEquals(ReplayState.PAUSED, replay.state.value)

            // Test loadVideo
            val dummyVideo = tempDir.resolve("dummy_match.mp4").apply { writeBytes(ByteArray(1024 * 1024 * 10)) }
            videoSync.loadVideo(dummyVideo)
            assertEquals(dummyVideo, videoSync.videoFile.value)
            assertEquals(0L, videoSync.currentVideoTimeMs.value)
        } finally {
            videoSync.dispose()
            replay.dispose()
            database.close()
            tempDir.deleteRecursively()
        }
    }
}
