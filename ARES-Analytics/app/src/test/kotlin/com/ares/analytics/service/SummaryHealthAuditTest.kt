package com.ares.analytics.service

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.ui.components.history.RunDataDictionary
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class SummaryHealthAuditTest {
    @Test fun `loop threshold counts use every recorded observation`() = runTest {
        fixture {
            repeat(6_001) { add("Robot/LoopTimeMs", it * 1_000L, 50.0) }
            assertEquals(6_001.0, diagnostics()["Diagnostics/System/LoopSamplesOver40Ms"])
        }
    }

    @Test fun `loop aliases and configuration topics cannot inflate threshold counts`() = runTest {
        fixture {
            add("Robot/LoopTimeMs", 0, 20.0); add("System/LoopTimeMs", 0, 100.0)
            add("Diagnostics/LoopBudgetMs", 0, 100.0)
            assertEquals(0.0, diagnostics()["Diagnostics/System/LoopSamplesOver40Ms"])
        }
    }

    @Test fun `invalid loop observations cannot create threshold evidence`() = runTest {
        fixture {
            add("Robot/LoopTimeMs", 0, Double.NaN); add("Robot/LoopTimeMs", 100, 100.0, "invalid")
            assertTrue(diagnostics().keys.none { it.contains("Loop") })
        }
    }

    @Test fun `recording gaps are not diagnosed as communication failures`() = runTest {
        fixture {
            add("Robot/LoopTimeMs", 0, 20.0); add("Robot/LoopTimeMs", 2_000_000, 20.0)
            assertFalse("CommsLoss" in generate().tags)
        }
    }

    @Test fun `recording gaps preserve source microsecond precision`() = runTest {
        fixture {
            add("Unrelated/Signal", 0, 1.0); add("Unrelated/Signal", 1_000_001, 1.0)
            assertEquals(1.0, diagnostics()["Diagnostics/System/RecordingGapsOver1s"])
        }
    }

    @Test fun `CAN error state is exposed as a peak rather than a total event count`() = runTest {
        fixture {
            add("Diagnostics/CANBus/rio/ErrorCount", 0, 100.0)
            add("Diagnostics/CANBus/rio/ErrorCount", 100, 90.0)
            val result = diagnostics()
            assertEquals(100.0, result["Diagnostics/System/PeakCANErrorCounter"])
            assertFalse("Diagnostics/System/TotalCANBusErrors" in result)
        }
    }

    @Test fun `bus off increments sum across buses after independent baselines`() = runTest {
        fixture {
            add("Diagnostics/CANBus/rio/BusOffCount", 0, 10.0); add("Diagnostics/CANBus/rio/BusOffCount", 100, 13.0)
            add("Diagnostics/CANBus/CAN2/BusOffCount", 0, 20.0); add("Diagnostics/CANBus/CAN2/BusOffCount", 100, 25.0)
            assertEquals(8.0, diagnostics()["Diagnostics/System/CANBusOffIncrements"])
        }
    }

    @Test fun `counter resets establish a new baseline without counting pre reset history`() = runTest {
        fixture {
            listOf(100.0, 102.0, 1.0, 4.0).forEachIndexed { i, v -> add("Diagnostics/CANBus/rio/BusOffCount", i * 100L, v) }
            assertEquals(5.0, diagnostics()["Diagnostics/System/CANBusOffIncrements"])
        }
    }

    @Test fun `initial counter alone does not establish a recorded event increment`() = runTest {
        fixture {
            add("Diagnostics/Power/BrownoutCount", 0, 12.0)
            val summary = generate()
            assertFalse("Brownout" in summary.tags)
            assertTrue(database.getAnalysisDiagnostics(session.sessionId).none { it.key.endsWith("BrownoutCount") })
        }
    }

    @Test fun `brownout guard transitions exclude initial accumulated history`() = runTest {
        fixture {
            add("Diagnostics/Power/BrownoutCount", 0, 100.0); add("Diagnostics/Power/BrownoutCount", 100, 102.0)
            assertEquals(2.0, diagnostics()["Diagnostics/System/BrownoutGuardTripIncrements"])
        }
    }

    @Test fun `CAN utilization is a valid ratio with no text placeholders`() = runTest {
        fixture {
            add("Diagnostics/CANBus/rio/Utilization", 0, 0.9)
            add("Diagnostics/CANBus/rio/Utilization", 100, 90.0)
            add("Diagnostics/CANBus/rio/Utilization", 200, 1.0, "invalid")
            assertEquals(0.9, diagnostics()["Diagnostics/System/MaxCANBusUtilization"])
        }
    }

    @Test fun `nested fault and CAN configuration suffixes cannot trigger hardware tags`() = runTest {
        fixture {
            add("Diagnostics/Motor/arm/Tuning/Faults", 0, 100.0)
            add("Diagnostics/CANBus/rio/Tuning/Utilization", 0, 1.0)
            assertTrue(generate().tags.none { it in setOf("MotorFault", "CANBusSaturated") })
        }
    }

    @Test fun `preferred bus alias is selected independently for each physical bus`() = runTest {
        fixture {
            add("Diagnostics/CANBus/rio/Utilization", 0, 0.2)
            add("Diagnostics/CAN/rio/BusUtilization", 0, 0.99)
            assertEquals(0.2, diagnostics()["Diagnostics/System/MaxCANBusUtilization"])
        }
    }

    @Test fun `missing health signals do not produce measured healthy zero values`() = runTest {
        fixture {
            add("Vision/EKF_NIS", 0, 2.0)
            assertTrue(diagnostics().keys.none { it.startsWith("Diagnostics/System/") })
        }
    }

    @Test fun `invalid newest bus value suppresses the previous same time update`() = runTest {
        fixture {
            add("Diagnostics/CANBus/rio/Utilization", 100, 0.99)
            add("Diagnostics/CANBus/rio/Utilization", 100, 0.0, "invalid")
            assertNull(diagnostics()["Diagnostics/System/MaxCANBusUtilization"])
        }
    }

    @Test fun `history labels distinguish observations and counter increments from event diagnoses`() {
        val labels = RunDataDictionary.buildBaseRowDefinitions().map { it.label }
        assertTrue("Recording Gaps >1s" in labels)
        assertTrue("Peak CAN Error Counter" in labels)
        assertTrue("Brownout Guard Trip Increments" in labels)
        assertFalse("Comms Loss Count" in labels || "Total CANbus Errors" in labels)
    }

    @Test fun `health SQL returns bounded aggregate rows from one all sample query`() = runTest {
        fixture {
            repeat(6_001) { add("Robot/LoopTimeMs", it * 1_000L, 50.0) }
            database.insertTelemetryFrames(frames)
            var queries = 0; var rows = 0
            val result = SummaryHealthAggregator { sql, parameters ->
                queries++
                database.executeQueryWithParams(sql, parameters).also { rows = it.rows.size }
            }.read(session.sessionId)
            assertEquals(1, queries); assertEquals(3, rows)
            assertEquals(6_001.0, result["Diagnostics/System/LoopSamples"])
            assertEquals(6_001.0, result["Diagnostics/System/LoopSamplesOver40Ms"])
            println("Health snapshot: 1 query; 6001 source samples; 3 aggregate rows")
        }
    }

    @Test fun `invalid counter observations break the adjacent increment chain`() = runTest {
        fixture {
            listOf(10.0, Double.NaN, 20.0, 23.0).forEachIndexed { i, v -> add("Diagnostics/Power/BrownoutCount", i * 100L, v) }
            assertEquals(3.0, diagnostics()["Diagnostics/System/BrownoutGuardTripIncrements"])
        }
    }

    @Test fun `invalid preferred counter streams do not fall back to legacy aliases`() = runTest {
        fixture {
            add("Diagnostics/CANBus/rio/BusOffCount", 0, 0.0, "invalid")
            add("Diagnostics/CAN/rio/BusOffs", 0, 1.0); add("Diagnostics/CAN/rio/BusOffs", 100, 9.0)
            assertNull(diagnostics()["Diagnostics/System/CANBusOffIncrements"])
        }
    }

    @Test fun `fractional negative and inexact counters cannot enter increment arithmetic`() = runTest {
        fixture {
            listOf(0.5, -1.0, 9_007_199_254_740_992.0, 0.0, 5.0).forEachIndexed { i, v -> add("Robot/BrownoutCount", i * 100L, v) }
            assertEquals(5.0, diagnostics()["Diagnostics/System/BrownoutGuardTripIncrements"])
        }
    }

    @Test fun `same time loop updates count only the latest value`() = runTest {
        fixture {
            add("Robot/LoopTimeMs", 100, 50.0); add("Robot/LoopTimeMs", 100, 20.0)
            val result = diagnostics()
            assertEquals(1.0, result["Diagnostics/System/LoopSamples"])
            assertEquals(0.0, result["Diagnostics/System/LoopSamplesOver40Ms"])
        }
    }

    @Test fun `case sensitive bus identities and aliases keep independent counter baselines`() = runTest {
        fixture {
            add("Diagnostics/CANBus/RIO/BusOffCount", 0, 1.0); add("Diagnostics/CANBus/RIO/BusOffCount", 100, 4.0)
            add("Diagnostics/CANBus/rio/BusOffCount", 0, 10.0); add("Diagnostics/CANBus/rio/BusOffCount", 100, 14.0)
            add("Diagnostics/CAN/rio/BusOffs", 0, 100.0); add("Diagnostics/CAN/rio/BusOffs", 100, 200.0)
            assertEquals(7.0, diagnostics()["Diagnostics/System/CANBusOffIncrements"])
        }
    }

    @Test fun `SQL session selection remains parameterized and isolated`() = runTest {
        fixture {
            add("Robot/LoopTimeMs", 0, 50.0); database.insertTelemetryFrames(frames)
            val service = SummaryHealthAggregator(database::executeQueryWithParams)
            assertTrue(service.read("health-audit' OR 1=1 --").isEmpty())
            assertEquals(1.0, service.read(session.sessionId)["Diagnostics/System/LoopSamples"])
        }
    }

    @Test fun `gap threshold is strict and duplicate source times do not create intervals`() = runTest {
        fixture {
            add("Other/A", 0, 1.0); add("Other/B", 0, 1.0); add("Other/A", 1_000_000, 1.0)
            assertEquals(0.0, diagnostics()["Diagnostics/System/RecordingGapsOver1s"])
        }
    }

    @Test fun `equal consecutive valid counters provide measured zero increments`() = runTest {
        fixture {
            add("Diagnostics/Power/BrownoutCount", 0, 10.0); add("Diagnostics/Power/BrownoutCount", 100, 10.0)
            assertEquals(0.0, diagnostics()["Diagnostics/System/BrownoutGuardTripIncrements"])
        }
    }

    @Test fun `history formats exact large counts without saturating to a signed integer`() {
        val row = RunDataDictionary.buildBaseRowDefinitions().single { it.label == "Brownout Guard Trip Increments" }
        val session = Session("s", "t", "y", "r", 0)
        val diagnostics = mapOf("Diagnostics/System/BrownoutGuardTripIncrements" to 3_000_000_000.0)
        assertEquals("3000000000", row.getValue(session, null, diagnostics))
        assertEquals(3_000_000_000.0, row.getNumericValue(session, null, diagnostics))
    }

    @Test fun `history invalid counts ratios and flags are unavailable rather than anomalous`() {
        val session = Session("s", "t", "y", "r", 0)
        for ((label, metric, invalid) in listOf(
            Triple("Recording Gaps >1s", "RecordingGapsOver1s", 1.5),
            Triple("Max CANbus Util (%)", "MaxCANBusUtilization", 2.0),
            Triple("Max CANbus Latency (ms)", "MaxCANBusLatencyMs", Double.NaN),
            Triple("Motor Fault Flag Observed", "MotorFaultObserved", -1.0),
        )) {
            val row = RunDataDictionary.buildBaseRowDefinitions().single { it.label == label }
            val diagnostics = mapOf("Diagnostics/System/$metric" to invalid)
            assertEquals("N/A", row.getValue(session, null, diagnostics)); assertNull(row.getNumericValue(session, null, diagnostics))
            assertFalse(row.isAnomaly(invalid))
        }
        val row = RunDataDictionary.buildBaseRowDefinitions().single { it.label == "Max CANbus Util (%)" }
        assertEquals("90.0%", row.getValue(session, null, mapOf("Diagnostics/System/MaxCANBusUtilization" to 0.9)))
        assertTrue(row.isAnomaly(0.9))
    }

    @Test fun `malformed or truncated aggregate output fails before persistence`() = runTest {
        val valid = QueryResult(listOf("metric", "value"), listOf(listOf("LoopSamples", "1")))
        for (result in listOf(
            valid.copy(isTruncated = true), valid.copy(truncatedCellCount = 1),
            valid.copy(rows = List(10) { listOf("LoopSamples", "1") }),
            valid.copy(rows = listOf(listOf("LoopSamples"))),
            valid.copy(rows = listOf(listOf("UnknownMetric", "1"))),
            valid.copy(rows = listOf(listOf("LoopSamples", "NaN"))),
            valid.copy(rows = listOf(listOf("LoopSamples", "-1"))),
            valid.copy(rows = listOf(listOf("LoopSamples", "1.5"))),
            valid.copy(rows = listOf(listOf("LoopSamples", "9007199254740992"))),
            valid.copy(rows = listOf(listOf("MaxCANBusUtilization", "2"))),
            valid.copy(rows = valid.rows + valid.rows),
        )) assertFailsWith<IllegalArgumentException> { SummaryHealthAggregator { _, _ -> result }.read("s") }
    }

    private class Fixture(val database: DatabaseService) {
        val session = Session("health-audit", "team", "season", "robot", 0)
        val frames = mutableListOf<TelemetryFrame>()
        fun add(key: String, timeUs: Long, value: Double, text: String? = null) {
            frames += TelemetryFrame(timeUs / 1_000, session.sessionId, key, value, text, timeUs)
        }
        suspend fun generate(): com.ares.analytics.shared.models.SessionSummary {
            database.insertTelemetryFrames(frames)
            val sysId = SysIdService(database)
            return SummaryEngineService(database, sysId, DriverAnalysisService(database, sysId)).generateSummary(session)
        }
        suspend fun diagnostics(): Map<String, Double> {
            generate()
            return database.getAnalysisDiagnostics(session.sessionId).associate { it.key to it.value }
        }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val directory = Files.createTempDirectory("ares-health-audit").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try { Fixture(database).block() } finally { database.close(); directory.deleteRecursively() }
    }
}
