package com.ares.analytics.domain.analysis

import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.RobotActionRecord
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionAnnotation
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.TelemetryFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MatchStrategyAnalyzerTest {

    @Test
    fun `computeSpatialFieldHeatmap bins dwell speed and voltage sag across league coordinates`() {
        val poses = listOf(
            StrategyPosePoint(x = -1.2, y = -1.0, headingRadians = 0.0, timestampMs = 1_000L),
            StrategyPosePoint(x = -1.0, y = -1.0, headingRadians = 0.0, timestampMs = 1_100L),
            StrategyPosePoint(x = 0.8, y = 0.9, headingRadians = 1.2, timestampMs = 1_300L),
            StrategyPosePoint(x = 0.82, y = 0.91, headingRadians = 1.2, timestampMs = 1_500L),
        )
        val frames = listOf(
            TelemetryFrame(1_000L, "s1", "Robot/BatteryVoltage", 12.8),
            TelemetryFrame(1_100L, "s1", "Robot/BatteryVoltage", 12.7),
            TelemetryFrame(1_300L, "s1", "Robot/BatteryVoltage", 10.9),
            TelemetryFrame(1_500L, "s1", "Robot/BatteryVoltage", 12.5),
        )

        val heatmap = MatchStrategyAnalyzer.computeSpatialFieldHeatmap(
            league = League.FTC,
            poses = poses,
            telemetryFrames = frames,
        )

        assertEquals(4, heatmap.poseSampleCount)
        assertEquals(12, heatmap.cols)
        assertEquals(12, heatmap.rows)
        assertTrue(heatmap.cells.isNotEmpty())
        assertTrue(heatmap.peakSpeedMps > 0.5)
        assertTrue(heatmap.maxSagVolts >= 1.8)
        assertTrue(heatmap.maxCellMetric(StrategyHeatmapMode.OCCUPANCY) > 0.0)
        assertTrue(heatmap.maxCellMetric(StrategyHeatmapMode.SPEED) > 0.0)
        assertTrue(heatmap.maxCellMetric(StrategyHeatmapMode.VOLTAGE_SAG) >= 1.8)
    }

    @Test
    fun `analyzeMatchCoachingInsights surfaces deterministic replay-linked coaching cards`() {
        val summary = SessionSummary(
            sessionId = "qual-7",
            teamId = "23247",
            seasonId = "2026",
            robotId = "ares-bot",
            createdAt = 10_000L,
            durationMs = 150_000L,
            minBatteryVoltage = 9.6,
            maxEkfDrift = 0.04,
            avgLoopTimeMs = 12.0,
            p95LoopTimeMs = 24.5,
            motorCurrentAverages = mapOf("fl" to 9.2, "fr" to 5.1, "rl" to 5.0, "rr" to 5.2),
            visionAcceptanceRate = 0.52,
            avgCrossTrackError = 0.095,
            avgBatteryResistance = 0.058,
            avgVisionLatencyMs = 28.0,
        )
        val frames = listOf(
            TelemetryFrame(11_000L, "qual-7", "Robot/BatteryVoltage", 12.5),
            TelemetryFrame(11_300L, "qual-7", "Robot/BatteryVoltage", 9.6),
            TelemetryFrame(14_200L, "qual-7", "Drive/CrossTrackError", 0.24),
            TelemetryFrame(18_500L, "qual-7", "Robot/LoopTimeMs", 33.0),
        )
        val actions = listOf(
            RobotActionRecord(12_000L, "qual-7", "r1", "ares-bot", 7, "BLUE", "SCORE", "{}"),
            RobotActionRecord(20_000L, "qual-7", "r1", "ares-bot", 7, "BLUE", "SCORE", "{}"),
            RobotActionRecord(29_500L, "qual-7", "r1", "ares-bot", 7, "BLUE", "SCORE", "{}"),
        )

        val insights = MatchStrategyAnalyzer.analyzeMatchCoachingInsights(
            league = League.FTC,
            summary = summary,
            telemetryFrames = frames,
            alerts = emptyList(),
            actions = actions,
        )

        val byId = insights.associateBy { it.id }
        val brownout = assertNotNull(byId["battery_brownout_risk"])
        assertEquals(CoachingSeverity.CRITICAL, brownout.severity)
        assertEquals(11_300L, brownout.jumpTimestampMs)

        val rInt = assertNotNull(byId["battery_internal_resistance"])
        assertEquals(CoachingSeverity.WARNING, rInt.severity)

        val tracking = assertNotNull(byId["path_tracking_hotspot"])
        assertEquals(14_200L, tracking.jumpTimestampMs)

        val loop = assertNotNull(byId["control_loop_overrun"])
        assertEquals(18_500L, loop.jumpTimestampMs)

        val asymmetry = assertNotNull(byId["drive_current_asymmetry"])
        assertTrue(asymmetry.title.contains("FL"))

        assertNotNull(byId["vision_rejection_rate"])
        assertNotNull(byId["cycle_split_consistency"])
    }

    @Test
    fun `buildEventProgressionSummary and MatchDebriefReportBuilder produce multi-match trends and escaped reports`() {
        val sessions = listOf(
            Session("s1", "23247", "2026", "bot", 1_000L, 120_000L, matchNumber = 1, allianceColor = "blue"),
            Session("s2", "23247", "2026", "bot", 2_000L, 120_000L, matchNumber = 2, allianceColor = "red"),
        )
        val summaries = listOf(
            SessionSummary(
                sessionId = "s1",
                teamId = "23247",
                seasonId = "2026",
                robotId = "bot",
                createdAt = 1_000L,
                durationMs = 120_000L,
                minBatteryVoltage = 10.8,
                avgBatteryResistance = 0.048,
                p95LoopTimeMs = 18.5,
                visionAcceptanceRate = 0.70,
                avgCrossTrackError = 0.06,
                matchNumber = 1,
            ),
            SessionSummary(
                sessionId = "s2",
                teamId = "23247",
                seasonId = "2026",
                robotId = "bot",
                createdAt = 2_000L,
                durationMs = 120_000L,
                minBatteryVoltage = 11.9,
                avgBatteryResistance = 0.026,
                p95LoopTimeMs = 11.2,
                visionAcceptanceRate = 0.92,
                avgCrossTrackError = 0.025,
                matchNumber = 2,
            ),
        )

        val series = MatchStrategyAnalyzer.buildEventProgressionSummary(sessions, summaries)
        assertEquals(5, series.size)
        assertTrue(series.all { it.trend == ProgressionTrend.IMPROVING })

        val md = MatchDebriefReportBuilder.buildSessionMarkdownDebrief(
            league = League.FTC,
            session = sessions[1],
            summary = summaries[1],
            insights = listOf(
                MatchCoachingInsight(
                    id = "i1",
                    title = "Strong Voltage <Headroom>",
                    detail = "Held 11.90 V & clean slew.",
                    category = "POWER",
                    severity = CoachingSeverity.EXCELLENT,
                    metricBadge = "11.90 V",
                    jumpTimestampMs = 2_500L,
                )
            ),
            heatmap = null,
            alerts = listOf(AlertRecord("a1", "s2", "Test/Rule", 2_400L, null, 100L, 1.0, false)),
            annotations = listOf(SessionAnnotation("n1", "s2", "Note <script>safe</script>", 9_000L, playheadMs = 2_200L)),
            sessionStartMs = 2_000L,
        )
        assertTrue(md.contains("Match #2 (RED)"))
        assertTrue(md.contains("11.90 V"))

        val html = MatchDebriefReportBuilder.buildSessionHtmlDebrief(
            league = League.FTC,
            session = sessions[1],
            summary = summaries[1],
            insights = emptyList(),
            heatmap = null,
            alerts = emptyList(),
            annotations = listOf(SessionAnnotation("n1", "s2", "Note <script>safe</script>", 9_000L, playheadMs = 2_200L)),
            sessionStartMs = 2_000L,
        )
        assertTrue(html.contains("&lt;script&gt;safe&lt;/script&gt;"))
        assertTrue(!html.contains("<script>safe</script>"))
    }
}
