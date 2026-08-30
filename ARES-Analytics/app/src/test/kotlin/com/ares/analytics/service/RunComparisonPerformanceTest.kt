package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue

class RunComparisonPerformanceTest {
    @Test
    fun `match length paired comparison remains bounded`() = runTest {
        val root = Files.createTempDirectory("run-comparison-performance").toFile()
        val database = DatabaseService(File(root, "analytics.duckdb").path)
        try {
            val workspace = WorkspaceConfig(
                id = "perf",
                teamId = "23247",
                seasonId = "decode",
                robotId = "comparison-perf",
                projectPath = root.path,
                league = League.FTC,
            )
            listOf("perf-a", "perf-b").forEachIndexed { runIndex, sessionId ->
                val start = 1_000_000L + runIndex * 1_000_000L
                database.insertSession(Session(sessionId, workspace.teamId, workspace.seasonId, workspace.robotId, start))
                val frames = ArrayList<TelemetryFrame>(TICKS * KEYS_PER_TICK)
                repeat(TICKS) { tick ->
                    val timestamp = start + tick * 20L
                    val timestampUs = timestamp * 1_000L
                    fun add(key: String, value: Double, stringValue: String? = null) {
                        frames += TelemetryFrame(timestamp, sessionId, key, value, stringValue, timestampUs)
                    }
                    add("Robot/BatteryVoltage", 12.6 - runIndex * 0.2 - tick * 0.0002)
                    add("Robot/LoopTimeMs", 10.0 + runIndex * 1.5 + (tick % 25) * 0.04)
                    add("Hardware/Motors/fl/CurrentAmps", 4.0 + (tick % 20) * 0.1)
                    add("Hardware/Motors/fr/CurrentAmps", 4.2 + (tick % 20) * 0.1)
                    add("Drive/Pose_X", tick * 0.002)
                    add("Drive/Pose_Y", runIndex * 0.05)
                    add("Gamepad1/LeftX", (tick % 10) / 10.0)
                    add("Gamepad1/LeftY", 0.5)
                    add("Robot/Mode", 0.0, if (tick < 100) "DISABLED" else "AUTONOMOUS_RUNNING")
                }
                database.insertTelemetryFrames(frames)
            }

            lateinit var report: RunComparisonReport
            val elapsedMs = measureTimeMillis {
                report = RunComparisonService(database).compare(
                    workspace,
                    RunComparisonRequest("perf-a", listOf("perf-b"), AUTONOMOUS_START_ALIGNMENT_ID),
                )
            }

            assertTrue(elapsedMs < 5_000L, "Paired comparison took ${elapsedMs}ms")
            assertTrue(report.metrics.isNotEmpty())
            assertTrue(report.metrics.flatMap { it.series }.all { it.samples.size <= 1_500 })
            assertTrue(report.trajectories.all { it.points.size <= 1_500 })
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    private companion object {
        const val TICKS = 1_500
        const val KEYS_PER_TICK = 9
    }
}
