package com.ares.analytics.service.analysis

import com.ares.analytics.shared.models.TelemetryFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CycleSplitAnalyzerTest {

    @Test
    fun `autonomous step transitions produce exact per step split durations and A B deltas`() {
        val primaryFrames = listOf(
            stateFrame("s1", 1000L, "Autonomous/CurrentStep", "PRELOAD_SCORE"),
            stateFrame("s1", 3200L, "Autonomous/CurrentStep", "INTAKE_SPIKE_1"),
            stateFrame("s1", 5100L, "Autonomous/CurrentStep", "SCORE_SPIKE_1"),
            stateFrame("s1", 6900L, "Autonomous/CurrentStep", "PARK"),
            stateFrame("s1", 8000L, "Autonomous/CurrentStep", "COMPLETE"),
        )
        val compareFrames = listOf(
            stateFrame("s2", 2000L, "Autonomous/CurrentStep", "PRELOAD_SCORE"),
            stateFrame("s2", 4500L, "Autonomous/CurrentStep", "INTAKE_SPIKE_1"),
            stateFrame("s2", 6200L, "Autonomous/CurrentStep", "SCORE_SPIKE_1"),
            stateFrame("s2", 8300L, "Autonomous/CurrentStep", "PARK"),
            stateFrame("s2", 9500L, "Autonomous/CurrentStep", "COMPLETE"),
        )

        val report = CycleSplitAnalyzer.analyze(
            primaryFrames = primaryFrames,
            compareFrames = compareFrames,
        )
        assertTrue(report.phaseSplits.size >= 4)
        val preload = report.phaseSplits.first { it.stateName == "PRELOAD_SCORE" }
        assertEquals(2200.0, preload.meanDurationMs, 1e-6)
        // Primary 2200 ms vs Compare 2500 ms => primary is 300 ms faster (-300 ms delta)
        assertEquals(-300.0, preload.compareDeltaMeanMs ?: 0.0, 1e-6)
    }

    @Test
    fun `repeating superstructure cycles compute p50 p95 and best lap cadence`() {
        // 4 full TeleOp cycles: INTAKE -> TRAVEL -> SCORE -> IDLE
        // Cycle durations (INTAKE start to SCORE completion / IDLE): 4100, 3800, 4000, 5200 ms
        val cycleDurations = listOf(4100L, 3800L, 4000L, 5200L)
        val frames = mutableListOf<TelemetryFrame>()
        var t = 10_000L
        frames += stateFrame("teleop", t, "Superstructure/State", "IDLE")
        t += 500L
        cycleDurations.forEach { totalCycleMs ->
            val intakeMs = (totalCycleMs * 0.4).toLong()
            val travelMs = (totalCycleMs * 0.35).toLong()
            val scoreMs = totalCycleMs - intakeMs - travelMs
            frames += stateFrame("teleop", t, "Superstructure/State", "INTAKING")
            t += intakeMs
            frames += stateFrame("teleop", t, "Superstructure/State", "TRAVELING")
            t += travelMs
            frames += stateFrame("teleop", t, "Superstructure/State", "SCORING")
            t += scoreMs
            frames += stateFrame("teleop", t, "Superstructure/State", "IDLE")
            t += 400L
        }

        val report = CycleSplitAnalyzer.analyze(primaryFrames = frames)
        assertEquals(4, report.completedCycles.size)
        assertEquals(3800.0, report.cycleSummary?.bestMs ?: -1.0, 1.0)
        assertTrue((report.cycleSummary?.p50Ms ?: 0.0) in 3950.0..4150.0)
        assertTrue((report.cycleSummary?.p95Ms ?: 0.0) >= 5000.0)
    }

    private fun stateFrame(sessionId: String, timestampMs: Long, key: String, state: String) =
        TelemetryFrame(
            sessionId = sessionId,
            timestampMs = timestampMs,
            key = key,
            value = Double.NaN,
            stringValue = state,
        )
}
