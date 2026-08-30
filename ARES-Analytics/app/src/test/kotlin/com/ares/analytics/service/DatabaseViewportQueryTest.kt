package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseViewportQueryTest {
    @Test
    fun `downsampled series is bounded and retains a narrow spike`() = runTest {
        withDatabase { database ->
            val frames = (0 until 1_000).map { index ->
                TelemetryFrame(
                    timestampMs = index.toLong(),
                    sessionId = "series",
                    key = "/Motor/Current",
                    value = if (index == 517) 99.0 else index % 10.toDouble(),
                    sampleOrder = index.toLong()
                )
            }
            database.insertTelemetryFrames(frames)

            val series = database.getTelemetrySeries("series", "/Motor/Current", 0, 999, maxPoints = 100)

            assertTrue(series.size <= 100)
            assertEquals(99.0, series.maxOf { it.value })
            assertTrue(series.zipWithNext().all { (left, right) -> left.timestampUs <= right.timestampUs })
        }
    }

    @Test
    fun `table query pages only selected keys and viewport`() = runTest {
        withDatabase { database ->
            database.insertTelemetryFrames(
                listOf(
                    frame("page", "A", 100, 1.0),
                    frame("page", "B", 110, 2.0),
                    frame("page", "C", 120, 3.0),
                    frame("page", "A", 200, 4.0)
                )
            )

            val page = database.getTelemetryPageForKeys("page", listOf("/A", "B"), 100, 150, limit = 10)

            assertEquals(listOf("A", "B"), page.map { it.key })
            assertEquals(listOf(100L, 110L), page.map { it.timestampMs })
        }
    }

    @Test
    fun `non-existent key or session in getTelemetrySeries returns empty list`() = runTest {
        withDatabase { database ->
            val series = database.getTelemetrySeries("missing-session", "NonExistentKey", 0, 1000, maxPoints = 50)
            assertTrue(series.isEmpty())
        }
    }

    @Test
    fun `getTelemetryPageForKeys with empty keys list returns empty list`() = runTest {
        withDatabase { database ->
            database.insertTelemetryFrames(listOf(frame("page", "A", 100, 1.0)))
            val page = database.getTelemetryPageForKeys("page", emptyList(), 0, 1000, limit = 10)
            assertTrue(page.isEmpty())
        }
    }

    @Test
    fun `diagnostic filter is deterministic and bounded while retaining endpoints`() = runTest {
        withDatabase { database ->
            database.insertTelemetryFrames(
                (0 until 10_000).map { index ->
                    frame("bounded", "Drive/Velocity", index.toLong(), index.toDouble())
                }
            )

            val frames = database.getTelemetryForFilters(
                sessionId = "bounded",
                keys = listOf("Drive/Velocity"),
                prefixes = emptyList(),
                maxFrames = 100,
                maxFramesPerTopic = 100,
            )

            assertTrue(frames.size <= 100)
            assertEquals(0L, frames.first().timestampMs)
            assertEquals(9_999L, frames.last().timestampMs)
            assertEquals(frames, database.getTelemetryForFilters(
                "bounded",
                listOf("Drive/Velocity"),
                emptyList(),
                maxFrames = 100,
                maxFramesPerTopic = 100,
            ))
        }
    }

    @Test
    fun `timestamp gap count stays scalar for long sessions`() = runTest {
        withDatabase { database ->
            database.insertTelemetryFrames(
                listOf(
                    frame("gaps", "A", 0, 1.0),
                    frame("gaps", "A", 20, 1.0),
                    frame("gaps", "A", 1_500, 1.0),
                    frame("gaps", "B", 1_500, 2.0),
                    frame("gaps", "A", 3_000, 1.0),
                )
            )

            assertEquals(2L, database.countTimestampGaps("gaps", 1_000))
        }
    }

    private suspend fun withDatabase(block: suspend (DatabaseService) -> Unit) {
        val directory = Files.createTempDirectory("ares-viewport-query").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try {
            block(database)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    private fun frame(session: String, key: String, time: Long, value: Double) = TelemetryFrame(
        timestampMs = time,
        sessionId = session,
        key = key,
        value = value
    )
}
