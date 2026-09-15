package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.AnalysisDiagnostic
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class DiagnosticCoachHealthAuditTest {
    @Test fun `the first high current sample after a gap starts a new candidate span`() = runTest {
        fixture {
            add(CURRENT, 0, 45.0); highCurrent(startUs = 1_000_000)
            assertEquals(1, analyze().findings.size)
        }
    }

    @Test fun `invalid current samples break evidence of a sustained interval`() = runTest {
        fixture {
            highCurrent(); add(CURRENT, 200_000, Double.NaN)
            assertTrue(analyze().findings.isEmpty())
        }
    }

    @Test fun `latest invalid same time updates cannot revive earlier current readings`() = runTest {
        fixture {
            highCurrent(); add(CURRENT, 200_000, 0.0, "invalid")
            assertTrue(analyze().findings.isEmpty())
        }
    }

    @Test fun `reported current peak belongs to the qualifying span`() = runTest {
        fixture {
            add(CURRENT, 0, 1_000.0); add(CURRENT, 100_000, 0.0); highCurrent(startUs = 200_000)
            val finding = analyze().findings.single()
            assertTrue(finding.observation.contains("45.0 A")); assertFalse(finding.observation.contains("1000.0 A"))
        }
    }

    @Test fun `motor current aliases do not compete with a present preferred stream`() = runTest {
        fixture {
            highCurrent(CURRENT, value = 10.0); highCurrent("Hardware/Motors/arm/Current")
            assertTrue(analyze().findings.isEmpty())
        }
    }

    @Test fun `nested motor configuration paths are not motor current measurements`() = runTest {
        fixture {
            highCurrent("Hardware/Motors/arm/Tuning/Current")
            assertTrue(analyze().findings.isEmpty())
            assertTrue("Per-motor current" in analyze().missingSignals)
        }
    }

    @Test fun `published drive motor current aliases provide current evidence`() = runTest {
        fixture {
            highCurrent("Drive/MotorCurrent_arm")
            assertEquals(1, analyze().findings.size)
        }
    }

    @Test fun `negative voltage and numeric text placeholders do not diagnose a battery`() = runTest {
        fixture {
            add("Robot/BatteryVoltage", 0, 12.0); add("Robot/BatteryVoltage", 100, -1.0)
            add("Robot/BatteryVoltage", 200, 0.0, "unavailable")
            assertTrue(analyze().findings.isEmpty())
        }
    }

    @Test fun `battery minimum uses only the latest update at each source time`() = runTest {
        fixture {
            add("Robot/BatteryVoltage", 100, 9.0); add("Robot/BatteryVoltage", 100, 12.0)
            assertTrue(analyze().findings.isEmpty())
        }
    }

    @Test fun `textual loop values cannot create a timing warning`() = runTest {
        fixture {
            add("Robot/LoopTimeMs", 0, 20.0); add("Robot/LoopTimeMs", 100, 100.0, "invalid")
            assertTrue(analyze().findings.isEmpty())
        }
    }

    @Test fun `an initial positive guard counter is not an event in the recording`() = runTest {
        fixture {
            add("Diagnostics/Power/BrownoutCount", 0, 2.0)
            assertTrue(analyze().findings.isEmpty())
        }
    }

    @Test fun `guard counter reset does not fabricate a new trip`() = runTest {
        fixture {
            add("Diagnostics/Power/BrownoutCount", 0, 10.0); add("Diagnostics/Power/BrownoutCount", 100, 0.0)
            assertTrue(analyze().findings.isEmpty())
        }
    }

    @Test fun `guard increments count transitions and do not establish a critical hardware brownout`() = runTest {
        fixture {
            add("Diagnostics/Power/BrownoutCount", 0, 100.0); add("Diagnostics/Power/BrownoutCount", 100, 103.0)
            val finding = analyze().findings.single()
            assertEquals(DiagnosticSeverity.REVIEW, finding.severity)
            assertTrue(finding.observation.contains("3 guard counter increment"))
        }
    }

    @Test fun `textual power scale placeholders cannot establish guard activity`() = runTest {
        fixture {
            add("Robot/BrownoutPowerScale", 0, 0.0, "invalid"); add("Robot/BrownoutPowerScale", 100, 1.0)
            assertTrue(analyze().findings.isEmpty())
        }
    }

    @Test fun `partial guard scaling is a review observation with measurement uncertainty`() = runTest {
        fixture {
            add("Robot/BrownoutPowerScale", 0, 0.75)
            val finding = analyze().findings.single()
            assertEquals(DiagnosticSeverity.REVIEW, finding.severity)
            assertTrue(finding.thresholdContext.contains("invalid voltage"))
        }
    }

    @Test fun `current interval wording distinguishes sampled evidence from continuous truth`() = runTest {
        fixture {
            highCurrent()
            val finding = analyze().findings.single()
            assertFalse(finding.observation.contains("stayed"))
            assertTrue(finding.thresholdContext.contains("between samples"))
        }
    }

    @Test fun `each motor with a qualifying current span receives a finding`() = runTest {
        fixture {
            highCurrent(); highCurrent("Hardware/Motors/wrist/CurrentAmps")
            assertEquals(2, analyze().findings.size)
        }
    }

    @Test fun `an invalid latest loop update suppresses a previous same time warning`() = runTest {
        fixture {
            add("Robot/LoopTimeMs", 0, 90.0); add("Robot/LoopTimeMs", 0, Double.NaN)
            assertTrue(analyze().findings.isEmpty())
            assertTrue("Control loop period" in analyze().missingSignals)
        }
    }

    @Test fun `preferred guard counter aliases do not borrow activity from another stream`() = runTest {
        fixture {
            add("Diagnostics/Power/BrownoutCount", 0, 0.0); add("Diagnostics/Power/BrownoutCount", 100, 0.0)
            add("Robot/BrownoutCount", 0, 1.0); add("Robot/BrownoutCount", 100, 3.0)
            assertTrue(analyze().findings.isEmpty())
        }
    }

    @Test fun `live buffer queries use the live store and stay isolated from recordings`() = runTest {
        fixture {
            database.insertTelemetryFrames(listOf(
                TelemetryFrame(0, "live-telemetry", "Robot/BatteryVoltage", 9.0),
                TelemetryFrame(0, "coach-health", "Robot/BatteryVoltage", 12.0),
            ))
            val service = DiagnosticCoachService(database)
            assertTrue(service.analyze("live-telemetry").findings.single().observation.contains("9.00 V"))
            assertTrue(service.analyze("coach-health").findings.isEmpty())
        }
    }

    @Test fun `invalid preferred battery sources remain missing instead of using a legacy stream`() = runTest {
        fixture {
            add("Robot/BatteryVoltage", 0, 0.0, "missing"); add("Battery/Voltage", 0, 9.0)
            val result = analyze()
            assertTrue(result.findings.isEmpty()); assertTrue("Battery voltage" in result.missingSignals)
        }
    }

    @Test fun `current sample gap boundary uses microseconds without rounding`() = runTest {
        for (gap in listOf(200_000L, 200_001L)) fixture {
            repeat(4) { add(CURRENT, it * gap, 40.0) }
            assertEquals(if (gap == 200_000L) 1 else 0, analyze().findings.size)
        }
    }

    @Test fun `current duration boundary includes exactly half a second`() = runTest {
        for (end in listOf(499_999L, 500_000L)) fixture {
            listOf(0L, 200_000L, 400_000L, end).forEach { add(CURRENT, it, 40.0) }
            assertEquals(if (end == 500_000L) 1 else 0, analyze().findings.size)
        }
    }

    @Test fun `a standalone invalid sample breaks a current interval even within the gap limit`() = runTest {
        fixture {
            repeat(7) { add(CURRENT, it * 100_000L, if (it == 2) Double.NaN else 45.0) }
            assertTrue(analyze().findings.isEmpty())
        }
    }

    @Test fun `a valid below threshold dip breaks the current span`() = runTest {
        fixture {
            repeat(7) { add(CURRENT, it * 100_000L, if (it == 3) 39.999 else 40.0) }
            assertTrue(analyze().findings.isEmpty())
        }
    }

    @Test fun `one earliest qualifying span per motor retains its own timestamp and peak`() = runTest {
        fixture {
            highCurrent(startUs = 123); highCurrent(startUs = 1_000_123, value = 80.0)
            val finding = analyze().findings.single()
            assertEquals(0.000123, finding.timestampSeconds, 1e-12)
            assertTrue(finding.observation.contains("45.0 A")); assertFalse(finding.observation.contains("80.0 A"))
        }
    }

    @Test fun `invalid health channels remain missing and do not produce an all clear claim`() = runTest {
        fixture {
            add(CURRENT, 0, -1.0); add("Robot/BatteryVoltage", 0, Double.NaN); add("Robot/LoopTimeMs", 0, -20.0)
            val result = analyze()
            assertEquals(listOf("Battery voltage", "Per-motor current", "Control loop period"), result.missingSignals)
            assertTrue(result.findings.isEmpty()); assertTrue(result.evidenceNotice.contains("not root-cause"))
        }
    }

    @Test fun `guard increments skip resets and comparisons crossing invalid updates`() = runTest {
        fixture {
            listOf(10.0, 12.0, Double.NaN, 20.0, 23.0, 0.0, 1.0).forEachIndexed { i, value -> add("Diagnostics/Power/BrownoutCount", i * 100L, value) }
            assertTrue(analyze().findings.single().observation.contains("6 guard counter increment"))
        }
    }

    @Test fun `guard neutralization and tiny scale reductions retain distinct meaning`() = runTest {
        for (value in listOf(0.0, 0.9999, Double.MIN_VALUE)) fixture {
            add("Robot/BrownoutPowerScale", 0, value)
            val finding = analyze().findings.single()
            assertEquals(if (value == 0.0) DiagnosticSeverity.URGENT else DiagnosticSeverity.REVIEW, finding.severity)
            if (value == 0.9999) assertTrue(finding.observation.contains("99.99%"))
            if (value > 0.0) assertFalse(finding.observation.contains("was 0%") || finding.observation.contains("was 100%"))
        }
    }

    @Test fun `coaching queries return bounded evidence from large raw and derived streams`() = runTest {
        fixture {
            repeat(12_001) { add("Robot/LoopTimeMs", it * 1_000L, 50.0) }
            add("Diagnostics/EKF/AvgNIS", 0, 100.0); store()
            database.replaceAnalysisDiagnostics("coach-health", (0 until 2_000).map {
                AnalysisDiagnostic("coach-health", "Diagnostics/SysId/Motors/m$it/kV", 1.0)
            } + AnalysisDiagnostic("coach-health", "Diagnostics/EKF/AvgNIS", 2.0))
            var queries = 0; var rows = 0
            val reader = DiagnosticCoachSnapshotReader { sql, parameters ->
                queries++
                database.executeQueryWithParams(sql, parameters).also { rows += it.rows.size }
            }
            val snapshot = reader.read("coach-health"); val generated = reader.readGenerated("coach-health")
            assertEquals(2, queries); assertEquals(4, rows)
            assertEquals(50.0, snapshot.first("loop")?.value)
            val finding = DiagnosticCoachLocalization(snapshot.localization("coach-health"), generated.metrics, "coach-health", generated.families).ekfFinding()
            assertTrue(assertNotNull(finding).observation.contains("2.00"))
            println("Coaching evidence: 2 queries; 12002 telemetry rows; 2001 derived rows; 4 result rows")
        }
    }

    @Test fun `generated family presence suppresses old raw metrics even without a recognized generated value`() = runTest {
        fixture {
            add("Diagnostics/EKF/AvgNIS", 0, 100.0)
            database.replaceAnalysisDiagnostics("coach-health", listOf(AnalysisDiagnostic("coach-health", "Diagnostics/EKF/NISSamples", 3.0)))
            assertTrue(analyze().findings.isEmpty())
        }
    }

    @Test fun `invalid generated metrics supersede raw values without reviving legacy evidence`() = runTest {
        fixture {
            add("Diagnostics/EKF/AvgNIS", 0, 100.0)
            database.replaceAnalysisDiagnostics("coach-health", listOf(AnalysisDiagnostic("coach-health", "Diagnostics/EKF/AvgNIS", 0.0, "invalid")))
            assertTrue(analyze().findings.isEmpty())
        }
    }

    @Test fun `excess qualifying motor evidence fails explicitly rather than dropping warnings`() = runTest {
        fixture {
            repeat(257) { highCurrent("Hardware/Motors/m$it/CurrentAmps") }
            assertFailsWith<IllegalArgumentException> { analyze() }
        }
    }

    @Test fun `truncated or inconsistent query evidence cannot become a checklist`() = runTest {
        val raw = QueryResult(emptyList(), emptyList(), isTruncated = true)
        assertFailsWith<IllegalArgumentException> { DiagnosticCoachSnapshotReader { _, _ -> raw }.read("s") }
        assertFailsWith<IllegalArgumentException> { DiagnosticCoachSnapshotReader { _, _ -> raw }.readGenerated("s") }
        val missingFamily = QueryResult(emptyList(), listOf(listOf("metric", "Diagnostics/EKF/AvgNIS", "2")))
        assertFailsWith<IllegalArgumentException> { DiagnosticCoachSnapshotReader { _, _ -> missingFamily }.readGenerated("s") }
        val malformedSpan = QueryResult(emptyList(), listOf(listOf("current", CURRENT, "45", "2", "1", "6")))
        assertFailsWith<IllegalArgumentException> { DiagnosticCoachSnapshotReader { _, _ -> malformedSpan }.read("s") }
    }

    @Test fun `oversized motor names are bounded in SQL and rejected without a misleading label`() = runTest {
        fixture {
            highCurrent("Hardware/Motors/" + "m".repeat(QueryResult.MAX_CELL_CHARACTERS * 2) + "/CurrentAmps")
            store()
            var largestCell = 0
            val reader = DiagnosticCoachSnapshotReader { sql, parameters ->
                database.executeQueryWithParams(sql, parameters).also { result ->
                    largestCell = result.rows.flatten().maxOf { it.length }
                }
            }
            assertFailsWith<IllegalArgumentException> { reader.read("coach-health") }
            assertEquals(QueryResult.MAX_CELL_CHARACTERS + 1, largestCell)
        }
    }

    @Test fun `both coaching queries bind session identity as data`() = runTest {
        fixture {
            highCurrent(); store()
            database.replaceAnalysisDiagnostics("coach-health", listOf(AnalysisDiagnostic("coach-health", "Diagnostics/EKF/AvgNIS", 2.0)))
            val reader = DiagnosticCoachSnapshotReader(database::executeQueryWithParams)
            val other = "coach-health' OR 1=1 --"
            assertTrue(coachHealth(reader.read(other)).findings.isEmpty())
            assertTrue(reader.readGenerated(other).metrics.isEmpty())
        }
    }

    private class Fixture(val database: DatabaseService) {
        val frames = mutableListOf<TelemetryFrame>()
        private var inserted = 0
        fun add(key: String, timeUs: Long, value: Double, text: String? = null) {
            frames += TelemetryFrame(timeUs / 1_000, "coach-health", key, value, text, timeUs)
        }
        fun highCurrent(key: String = CURRENT, startUs: Long = 0, value: Double = 45.0) {
            repeat(6) { add(key, startUs + it * 100_000L, value) }
        }
        suspend fun store() {
            database.insertTelemetryFrames(frames.drop(inserted)); inserted = frames.size
        }
        suspend fun analyze(): PitDiagnosticSummary {
            store()
            return DiagnosticCoachService(database).analyze("coach-health")
        }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val directory = Files.createTempDirectory("ares-coach-health").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try { Fixture(database).block() } finally { database.close(); directory.deleteRecursively() }
    }
    companion object { private const val CURRENT = "Hardware/Motors/arm/CurrentAmps" }
}
