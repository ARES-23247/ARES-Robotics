package com.ares.analytics.domain.analysis

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CohortStatisticsAnalyzerTest {

    private fun buildRun(
        id: String,
        createdAt: Long,
        tags: List<String>,
        p95LoopMs: Double,
        minBatteryV: Double = 11.8,
        alliance: String = "red",
    ): Pair<Session, SessionSummary> {
        val session = Session(
            sessionId = id,
            teamId = "23247",
            seasonId = "2026",
            robotId = "bot",
            createdAt = createdAt,
            durationMs = 30_000L,
            tags = tags,
            allianceColor = alliance,
        )
        val summary = SessionSummary(
            sessionId = id,
            teamId = "23247",
            seasonId = "2026",
            robotId = "bot",
            createdAt = createdAt,
            durationMs = 30_000L,
            minBatteryVoltage = minBatteryV,
            p95LoopTimeMs = p95LoopMs,
            avgLoopTimeMs = p95LoopMs * 0.7,
            tags = tags,
            allianceColor = alliance,
        )
        return session to summary
    }

    @Test
    fun `five-number summary and outlier detection flag 1_5x IQR spike`() {
        val runs = listOf(
            buildRun("r1", 1000L, listOf("v1"), p95LoopMs = 10.0),
            buildRun("r2", 2000L, listOf("v1"), p95LoopMs = 10.5),
            buildRun("r3", 3000L, listOf("v1"), p95LoopMs = 11.0),
            buildRun("r4", 4000L, listOf("v1"), p95LoopMs = 10.2),
            buildRun("r5-spike", 5000L, listOf("v1"), p95LoopMs = 34.0),
        )
        val sessions = runs.map { it.first }
        val summaries = runs.associate { it.first.sessionId to it.second }
        val metric = CohortStatisticsAnalyzer.defaultMetrics().first { it.id == "p95_loop_ms" }

        val stats = CohortStatisticsAnalyzer.summarizeCohort("Tag: v1", sessions, summaries, emptyMap(), metric)
        assertNotNull(stats)
        assertEquals(5, stats.count)
        assertEquals(10.0, stats.min)
        assertEquals(34.0, stats.max)
        assertEquals(10.5, stats.median, 1e-6)
        assertEquals(listOf("r5-spike"), stats.outlierSessionIds)
    }

    @Test
    fun `tag vs tag comparison detects favorable reduction in lower-is-better metric`() {
        val runs = listOf(
            buildRun("a1", 1000L, listOf("v1-pid"), p95LoopMs = 18.0),
            buildRun("a2", 2000L, listOf("v1-pid"), p95LoopMs = 19.0),
            buildRun("a3", 3000L, listOf("v1-pid"), p95LoopMs = 20.0),
            buildRun("b1", 4000L, listOf("v2-pid"), p95LoopMs = 9.5),
            buildRun("b2", 5000L, listOf("v2-pid"), p95LoopMs = 10.0),
            buildRun("b3", 6000L, listOf("v2-pid"), p95LoopMs = 10.5),
        )
        val sessions = runs.map { it.first }
        val summaries = runs.associate { it.first.sessionId to it.second }
        val metric = CohortStatisticsAnalyzer.defaultMetrics().first { it.id == "p95_loop_ms" }

        val comparison = CohortStatisticsAnalyzer.compareCohorts(
            sessions = sessions,
            summariesById = summaries,
            metric = metric,
            mode = CohortSplitMode.TAG_VS_TAG,
            primaryTag = "v1-pid",
            secondaryTag = "v2-pid",
        )

        assertEquals(true, comparison.improved)
        assertNotNull(comparison.medianDelta)
        assertTrue(abs(comparison.medianDelta - (-9.0)) < 1e-6)
        assertNotNull(comparison.cohensD)
        assertTrue(comparison.cohensD < -2.0)
        assertTrue(comparison.effectMagnitudeLabel.startsWith("Large shift"))
        assertTrue(comparison.plainEnglishTakeaway.contains("do not prove causation"))
        assertTrue(comparison.markdownEvidenceBlock.contains("| Tag: v1-pid | 3 |"))
        assertTrue(comparison.markdownEvidenceBlock.contains("| Tag: v2-pid | 3 |"))
    }
}
