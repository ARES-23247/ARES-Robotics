package com.ares.analytics.service

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class SummaryAggregationAuditTest {
    @Test fun `missing battery data never becomes a measured twelve volts`() = runTest {
        fixture { assertEquals(0.0, generate().minBatteryVoltage) }
    }

    @Test fun `invalid samples are excluded before mean and percentile aggregation`() = runTest {
        fixture {
            add("Robot/LoopTimeMs", 0, 10.0); add("Robot/LoopTimeMs", 1_000, 20.0)
            add("Robot/LoopTimeMs", 2_000, Double.NaN); add("Robot/LoopTimeMs", 3_000, Double.POSITIVE_INFINITY)
            add("Robot/LoopTimeMs", 4_000, -10.0); add("Robot/LoopTimeMs", 5_000, 900.0, "invalid")
            val result = generate()
            assertEquals(15.0, result.avgLoopTimeMs, 1e-12)
            assertEquals(19.5, result.p95LoopTimeMs, 1e-12)
        }
    }

    @Test fun `canonical loop source is not mixed with differently sampled aliases`() = runTest {
        fixture {
            add("Robot/LoopTimeMs", 0, 10.0)
            repeat(20) { add("System/LoopTimeMs", it * 1_000L, 100.0) }
            assertEquals(10.0, generate().avgLoopTimeMs)
        }
    }

    @Test fun `topic substrings cannot turn gains and budgets into physical errors`() = runTest {
        fixture {
            add("Vision/LatencyMs", 0, 20.0); add("Diagnostics/Vision/LatencyBudgetMs", 0, 1_000.0)
            add("Path/Error_CrossTrack", 0, 0.2); add("Tuning/Parameters/xteGain/Current", 0, 500.0)
            add("Tuning/DriftLimit", 0, 100.0)
            val result = generate()
            assertEquals(20.0, result.avgVisionLatencyMs)
            assertEquals(0.2, result.avgCrossTrackError)
            assertEquals(0.0, result.maxEkfDrift)
        }
    }

    @Test fun `motor averages use actual device currents with one preferred alias`() = runTest {
        fixture {
            add("Hardware/Motors/fl/CurrentAmps", 0, 10.0)
            add("Drive/MotorCurrent_fl", 0, 20.0)
            add("Hardware/Motors/fl/CurrentLimit", 0, 40.0)
            add("Tuning/Parameters/kP/Current", 0, 5.0)
            add("Robot/TotalCurrentAmps", 0, 100.0)
            assertEquals(mapOf("fl" to 10.0), generate().motorCurrentAverages)
        }
    }

    @Test fun `EKF drift summarizes vector magnitude rather than its largest component`() = runTest {
        fixture {
            add("Drive/EKF_Drift_X", 100, -3.0); add("Drive/EKF_Drift_Y", 100, 4.0)
            assertEquals(5.0, generate().maxEkfDrift)
        }
    }

    @Test fun `different microsecond drift components cannot fabricate a vector`() = runTest {
        fixture {
            add("Drive/EKF_Drift_X", 100, 3.0); add("Drive/EKF_Drift_Y", 900, 4.0)
            assertEquals(0.0, generate().maxEkfDrift)
        }
    }

    @Test fun `vision acceptance is a fraction of counter increments not cumulative counts`() = runTest {
        fixture {
            add("Vision/EKF_AcceptedCount", 0, 100.0); add("Vision/EKF_RejectedCount", 0, 25.0)
            add("Vision/EKF_AcceptedCount", 1_000, 108.0); add("Vision/EKF_RejectedCount", 1_000, 27.0)
            repeat(10) { add("Vision/EKF_Accepted", it * 100L, 0.0) }
            assertEquals(0.8, generate().visionAcceptanceRate, 1e-12)
        }
    }

    @Test fun `inactive camera and path samples do not dilute active metrics`() = runTest {
        fixture {
            add("Vision/HasTarget", 0, 1.0); add("Vision/Primary_LatencyMs", 0, 20.0)
            add("Vision/HasTarget", 1_000, 0.0); add("Vision/Primary_LatencyMs", 1_000, 0.0)
            add("Path/Active", 0, 1.0); add("Path/Error_CrossTrack", 0, 0.2)
            add("Path/Active", 1_000, 0.0); add("Path/Error_CrossTrack", 1_000, 0.0)
            val result = generate()
            assertEquals(20.0, result.avgVisionLatencyMs)
            assertEquals(0.2, result.avgCrossTrackError)
        }
    }

    @Test fun `battery resistance accepts canonical robot voltage and total current`() = runTest {
        fixture {
            add("Robot/BatteryVoltage", 0, 12.0); add("Robot/TotalCurrentAmps", 0, 2.0)
            add("Robot/BatteryVoltage", 100_000, 11.0); add("Robot/TotalCurrentAmps", 100_000, 22.0)
            assertEquals(0.05, generate().avgBatteryResistance, 1e-12)
        }
    }

    @Test fun `battery resistance preserves distinct submillisecond observations`() = runTest {
        fixture {
            add("Battery/Voltage", 100, 12.0); add("Battery/Current", 100, 2.0)
            add("Battery/Voltage", 900, 11.0); add("Battery/Current", 900, 22.0)
            assertEquals(0.05, generate().avgBatteryResistance, 1e-12)
        }
    }

    @Test fun `battery channels at different source times cannot manufacture resistance`() = runTest {
        fixture {
            add("Battery/Voltage", 100, 12.0); add("Battery/Current", 900, 2.0)
            add("Battery/Voltage", 1_100, 11.0); add("Battery/Current", 1_900, 22.0)
            assertEquals(0.0, generate().avgBatteryResistance)
        }
    }

    @Test fun `latest invalid duplicate suppresses older numeric summary evidence`() = runTest {
        fixture {
            add("Robot/LoopTimeMs", 100, 10.0)
            frames += TelemetryFrame(0, session.sessionId, "Robot/LoopTimeMs", 900.0, "invalid", 100, 1)
            assertEquals(0.0, generate().avgLoopTimeMs)
        }
    }

    @Test fun `notebook cancellation propagates after the local summary is saved`() = runTest {
        fixture {
            val sysId = SysIdService(database)
            val engine = SummaryEngineService(database, sysId, DriverAnalysisService(database, sysId)) { _, _ ->
                throw CancellationException("cancel notebook")
            }
            assertFailsWith<CancellationException> { engine.generateSummary(session) }
            assertNotNull(database.getSessionSummary(session.sessionId))
        }
    }

    @Test fun `finite extremes retain means percentiles and scaled drift magnitudes`() = runTest {
        fixture {
            repeat(3) { add("Robot/LoopTimeMs", it * 100L, Double.MAX_VALUE) }
            add("Drive/EKF_Drift_X", 0, 1e200); add("Drive/EKF_Drift_Y", 0, 1e200)
            val result = aggregates()
            assertEquals(Double.MAX_VALUE, result["loop"])
            assertEquals(Double.MAX_VALUE, result["loop_p95"])
            assertEquals(kotlin.math.sqrt(2.0), result["drift"] / 1e200, 1e-12)
        }
    }

    @Test fun `zero observations remain present internally while missing metrics remain absent`() = runTest {
        fixture {
            add("Robot/LoopTimeMs", 0, 0.0)
            add("Vision/EKF_Accepted", 0, 1.0)
            val result = aggregates()
            assertEquals(mapOf("loop" to 0.0, "loop_p95" to 0.0), result.metrics)
            assertFalse("acceptance" in result.metrics, "A periodically repeated last-result flag is not an event acceptance rate")
        }
    }

    @Test fun `counter resets skip unknown intervals and count subsequent observed increments`() = runTest {
        fixture {
            listOf(100.0 to 50.0, 110.0 to 50.0, 1.0 to 0.0, 3.0 to 2.0).forEachIndexed { i, (a, r) ->
                add("Vision/EKF_AcceptedCount", i * 100L, a)
                add("Vision/EKF_RejectedCount", i * 100L, r)
            }
            add("Vision/AcceptanceRate", 0, 0.1)
            assertEquals(12.0 / 14.0, aggregates()["acceptance"], 1e-12)
        }
    }

    @Test fun `invalid counters cannot masquerade as observations and explicit rates stay bounded`() = runTest {
        fixture {
            add("Vision/EKF_AcceptedCount", 0, 1.5); add("Vision/EKF_RejectedCount", 0, 0.0)
            add("Vision/EKF_AcceptedCount", 100, 2.5); add("Vision/EKF_RejectedCount", 100, 1.0)
            add("Vision/AcceptanceRate", 0, 0.25); add("Vision/AcceptanceRate", 100, 1.5)
            add("Vision/AcceptanceRate", 200, -0.25)
            assertEquals(0.25, aggregates()["acceptance"])
        }
    }

    @Test fun `activity flags must be valid at the metric source time`() = runTest {
        fixture {
            add("Vision/HasTarget", 100, 1.0); add("Vision/Primary_LatencyMs", 900, 20.0)
            add("Path/Active", 100, 2.0); add("Path/Error_CrossTrack", 100, 0.2)
            val result = aggregates()
            assertFalse("latency" in result.metrics)
            assertFalse("cross_track" in result.metrics)
        }
    }

    @Test fun `resistance ignores incomplete samples and intervals across recording gaps`() = runTest {
        fixture {
            add("Robot/BatteryVoltage", 0, 12.0); add("Robot/TotalCurrentAmps", 0, 2.0)
            add("Robot/BatteryVoltage", 100_000, 11.0); add("Robot/TotalCurrentAmps", 100_000, 22.0)
            add("Robot/BatteryVoltage", 200_000, 0.0, "invalid"); add("Robot/TotalCurrentAmps", 200_000, 40.0)
            add("Robot/BatteryVoltage", 300_000, 10.0); add("Robot/TotalCurrentAmps", 300_000, 42.0)
            add("Robot/BatteryVoltage", 1_000_000, 11.0); add("Robot/TotalCurrentAmps", 1_000_000, 22.0)
            assertEquals(0.05, aggregates()["resistance"], 1e-12)
        }
        fixture {
            add("Battery/Voltage", 0, 12.0); add("Battery/Current", 0, 2.0)
            add("Battery/Voltage", 100, 11.0); add("Battery/Current", 100, 2.5)
            add("Battery/Voltage", 200, 12.0); add("Battery/Current", 200, 10.0)
            assertFalse("resistance" in aggregates().metrics)
        }
    }

    @Test fun `invalid preferred sources cannot be replaced with a convenient alias`() = runTest {
        fixture {
            add("/Robot/LoopTimeMs", 100, 20.0)
            frames += TelemetryFrame(0, session.sessionId, "/Robot/LoopTimeMs", Double.NaN, timestampUs = 100, sampleOrder = 1)
            add("System/LoopTimeMs", 100, 10.0)
            add("/Robot/OpMode", 100, 0.0, "AUTO")
            add("Tuning/Parameters/OpMode", 100, 0.0, "FAKE")
            val result = aggregates()
            assertFalse("loop" in result.metrics)
            assertEquals(setOf("AUTO"), result.opModes)
        }
    }

    @Test fun `one snapshot aggregates every recorded sample and isolates parameterized session identity`() = runTest {
        fixture {
            repeat(12_001) { add("Robot/LoopTimeMs", it * 100L, it.toDouble()) }
            database.insertTelemetryFrames(frames + TelemetryFrame(0, "other", "Robot/LoopTimeMs", 1e100))
            var queries = 0
            var returnedRows = 0
            val aggregator = SummaryMetricAggregator { sql, params ->
                queries++
                database.executeQueryWithParams(sql, params).also { returnedRows = it.rows.size }
            }
            val result = aggregator.read(session.sessionId)
            assertEquals(1, queries)
            assertEquals(2, returnedRows)
            assertEquals(6_000.0, result["loop"], 1e-9)
            assertEquals(11_400.0, result["loop_p95"], 1e-9)
            println("Summary snapshot: 1 query; 12001 source samples; 2 aggregate rows")
            assertTrue(aggregator.read("missing' OR '1'='1").metrics.isEmpty())
        }
    }

    @Test fun `truncated or malformed aggregate results cannot become successful summaries`() = runTest {
        val invalid = listOf(
            QueryResult(emptyList(), emptyList(), isTruncated = true),
            QueryResult(emptyList(), emptyList(), truncatedCellCount = 1),
            QueryResult(emptyList(), listOf(listOf("loop", "NaN", "NULL"))),
            QueryResult(emptyList(), listOf(listOf("loop", "-1.0", "NULL"))),
            QueryResult(emptyList(), listOf(listOf("loop", "1.0"))),
        )
        for (result in invalid) assertFailsWith<IllegalArgumentException> {
            SummaryMetricAggregator { _, _ -> result }.read("session")
        }
    }

    @Test fun `excess aggregate cardinality or labels fail instead of materializing unbounded reports`() = runTest {
        fixture {
            repeat(1_001) { add("Hardware/Motors/m$it/CurrentAmps", 0, 1.0) }
            assertFailsWith<IllegalArgumentException> { aggregates() }
        }
        fixture {
            add("Robot/OpMode", 0, 0.0, "x".repeat(QueryResult.MAX_CELL_CHARACTERS + 1))
            assertFailsWith<IllegalArgumentException> { aggregates() }
        }
    }

    private class Fixture(val database: DatabaseService) {
        val session = Session("summary-audit", "team", "season", "robot", 0)
        val frames = mutableListOf<TelemetryFrame>()
        fun add(key: String, timeUs: Long, value: Double, text: String? = null) {
            frames += TelemetryFrame(timeUs / 1_000, session.sessionId, key, value, text, timeUs)
        }
        suspend fun generate(): SessionSummary {
            database.insertTelemetryFrames(frames)
            val sysId = SysIdService(database)
            return SummaryEngineService(database, sysId, DriverAnalysisService(database, sysId)).generateSummary(session)
        }
        suspend fun aggregates(): SummaryAggregates {
            database.insertTelemetryFrames(frames)
            return SummaryMetricAggregator(database::executeQueryWithParams).read(session.sessionId)
        }
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val directory = Files.createTempDirectory("ares-summary-audit").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try { Fixture(database).block() } finally { database.close(); directory.deleteRecursively() }
    }
}
