package com.ares.analytics.service

import com.ares.analytics.shared.models.AnalysisDiagnostic
import com.ares.analytics.shared.models.CalculatedSummary
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.mockito.Mockito
import java.nio.file.Files
import kotlin.test.*

class AnalysisTelemetryAuditTest {
    @Test fun `NIS counts include every recorded source observation`() = runTest { fixture {
        repeat(3_001) { add("Vision/EKF_NIS", it * 1_000L, 2.0) }
        assertEquals(3_001.0, generate()["Diagnostics/EKF/NISSamples"])
    } }
    @Test fun `a narrow path error peak is not lost to topic sampling`() = runTest { fixture {
        repeat(3_001) { add("Path/Error_CrossTrack", it * 1_000L, if (it == 1) 0.5 else 0.0) }
        val diagnostics = generate()
        assertEquals(0.5, diagnostics["Diagnostics/Auto/MaxCrossTrackM"])
        assertEquals(3_001.0, diagnostics["Diagnostics/Auto/CrossTrackSamples"])
    } }
    @Test fun `latest invalid NIS survives before source observations are counted`() = runTest { fixture {
        add("Vision/EKF_NIS", 0, 100.0); add("Vision/EKF_NIS", 0, Double.NaN)
        repeat(3_000) { add("Vision/EKF_NIS", (it + 1) * 1_000L, 1.0) }
        val diagnostics = generate()
        assertEquals(3_000.0, diagnostics["Diagnostics/EKF/NISSamples"])
        assertEquals(1.0, diagnostics["Diagnostics/EKF/AvgNIS"])
    } }
    @Test fun `summary fits use all aligned rows instead of a per topic sample`() = runTest { fixture {
        drive(3_001)
        assertEquals(3_001.0, generate()["Diagnostics/SysId/FitSamples"])
    } }
    @Test fun `an omitted invalid velocity cannot be bridged by a derivative`() = runTest { fixture {
        drive(3_001, acceleration = false)
        frames.replaceAll { if (it.key == "Drive/Velocity" && it.timestampUs == 1_000L) it.copy(value = Double.NaN) else it }
        generate()
        assertTrue(fits.single().none { it.timestampMs == 2L })
    } }
    @Test fun `pose disagreement counts preserve complete source aligned observations`() = runTest { fixture {
        repeat(3_001) { i ->
            val t = i * 1_000L
            add("Vision/Pose_X", t, 1.0); add("Vision/Pose_Y", t, 2.0)
            add("Drive/Pose_X", t, 0.0); add("Drive/Pose_Y", t, 0.0)
        }
        assertEquals(3_001.0, generate()["Diagnostics/EKF/PoseDisagreementSamples"])
    } }
    @Test fun `irrelevant hardware topics cannot evict later diagnostic inputs`() = runTest { fixture {
        repeat(130) { motor -> repeat(800) { i -> add("Hardware/Motors/m$motor/Configuration", i * 1_000L, 1.0) } }
        add("Vision/EKF_NIS", 2_000_000, 2.0)
        assertEquals(1.0, generate()["Diagnostics/EKF/NISSamples"])
    } }
    @Test fun `a SysId failure preserves health and localization while removing stale fits`() = runTest { fixture {
        drive(30); failFit = true
        add("Robot/LoopTimeMs", 0, 50.0); add("Vision/EKF_NIS", 0, 2.0); add("Path/Error_CrossTrack", 0, 0.2)
        database.replaceAnalysisDiagnostics("analysis-audit", listOf(AnalysisDiagnostic("analysis-audit", "Diagnostics/SysId/kV", 999.0)))
        val diagnostics = generate()
        assertEquals(1.0, diagnostics["Diagnostics/System/LoopSamplesOver40Ms"])
        assertEquals(2.0, diagnostics["Diagnostics/EKF/AvgNIS"])
        assertEquals(0.2, diagnostics["Diagnostics/Auto/MaxCrossTrackM"])
        assertNull(diagnostics["Diagnostics/SysId/kV"])
        assertEquals("analysis_failed", inputStatus("SysId"))
    } }
    @Test fun `a driver analysis failure does not discard successful numerical diagnostics`() = runTest { fixture {
        drive(30); failDriver = true
        add("Robot/LoopTimeMs", 0, 50.0); add("Vision/EKF_NIS", 0, 2.0)
        val diagnostics = generate()
        assertEquals(30.0, diagnostics["Diagnostics/SysId/FitSamples"])
        assertEquals(1.0, diagnostics["Diagnostics/System/LoopSamplesOver40Ms"])
        assertEquals(2.0, diagnostics["Diagnostics/EKF/AvgNIS"])
        assertEquals("analysis_failed", inputStatus("Driver"))
    } }

    @Test fun `oversized SysId input is unavailable while smaller localization inputs remain complete`() = runTest { fixture {
        drive(33_334)
        add("Vision/EKF_NIS", 0, 2.0); add("Path/Error_CrossTrack", 0, 0.2)
        val result = generate()
        assertTrue(fits.isEmpty())
        assertNull(result["Diagnostics/SysId/FitSamples"])
        assertEquals("row_limit", inputStatus("SysId"))
        assertEquals(100_002.0, result["Diagnostics/SysId/InputSourceRows"])
        assertEquals(2.0, result["Diagnostics/EKF/AvgNIS"])
        assertEquals(0.2, result["Diagnostics/Auto/MaxCrossTrackM"])
        assertFailsWith<IllegalStateException> {
            SysIdService(database).analyzeMotorData(session.sessionId, "Drive/Voltage", "Drive/Velocity", "Drive/Acceleration")
        }
    } }
    @Test fun `empty newly computed localization suppresses stale raw derived metrics`() = runTest { fixture {
        add("Diagnostics/EKF/AvgNIS", 0, 999.0); add("Diagnostics/Auto/MaxCrossTrackM", 0, 999.0)
        generate()
        assertEquals("empty", inputStatus("EKF")); assertEquals("empty", inputStatus("Auto"))
        assertTrue(DiagnosticCoachService(database).analyze(session.sessionId).findings.isEmpty())
    } }
    @Test fun `fit cancellation propagates without replacing previously persisted diagnostics`() = runTest { fixture {
        drive(30); cancelFit = true
        database.replaceAnalysisDiagnostics(session.sessionId, listOf(AnalysisDiagnostic(session.sessionId, "Diagnostics/SysId/kV", 999.0)))
        assertFailsWith<CancellationException> { generate() }
        assertEquals(999.0, database.getAnalysisDiagnostics(session.sessionId).single().value)
    } }
    @Test fun `an input query failure preserves independent health and clears stale derived fits`() = runTest { fixture {
        failRead = true
        add("Robot/LoopTimeMs", 0, 50.0)
        database.replaceAnalysisDiagnostics(session.sessionId, listOf(AnalysisDiagnostic(session.sessionId, "Diagnostics/SysId/kV", 999.0)))
        val result = generate()
        assertEquals(1.0, result["Diagnostics/System/LoopSamplesOver40Ms"])
        assertNull(result["Diagnostics/SysId/kV"])
        for (family in listOf("SysId", "EKF", "Auto")) assertEquals("read_failed", inputStatus(family))
    } }

    private class Fixture(val database: DatabaseService) {
        val frames = mutableListOf<TelemetryFrame>()
        val fits = mutableListOf<List<AlignedDataRow>>()
        var failFit = false
        var failDriver = false
        var cancelFit = false
        var failRead = false
        val session = Session("analysis-audit", "team", "season", "robot", 0)
        private val solver = Mockito.mock(SysIdService::class.java) { invocation ->
            if (invocation.method.name == "analyzeRawData") {
                if (cancelFit) throw CancellationException("Expected fixture cancellation")
                if (failFit) error("Expected fixture fit failure")
                fits += invocation.getArgument<List<AlignedDataRow>>(0).toList()
                CalculatedSummary(kS = 0.4, kV = 1.6, kA = 0.32, rSquared = 1.0)
            } else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        fun add(key: String, timeUs: Long, value: Double, text: String? = null) {
            frames += TelemetryFrame(timeUs / 1_000, session.sessionId, key, value, text, timeUs)
        }
        fun drive(n: Int, acceleration: Boolean = true) {
            repeat(n) { i ->
                add("Drive/Voltage", i * 1_000L, 3.0); add("Drive/Velocity", i * 1_000L, i + 1.0)
                if (acceleration) add("Drive/Acceleration", i * 1_000L, 1.0)
            }
        }
        suspend fun generate(): Map<String, Double> {
            database.insertTelemetryFrames(frames)
            val driver = if (failDriver) Mockito.mock(DriverAnalysisService::class.java) { invocation ->
                if (invocation.method.name == "analyzeDriverJitter") error("Expected fixture driver failure")
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            } else DriverAnalysisService(database, solver)
            val engineDatabase = if (failRead) Mockito.mock(DatabaseService::class.java) { invocation ->
                if (invocation.method.name.startsWith("getAnalysisTelemetry")) error("Expected fixture read failure")
                invocation.method.invoke(database, *invocation.rawArguments)
            } else database
            SummaryEngineService(engineDatabase, solver, driver).generateSummary(session)
            return database.getAnalysisDiagnostics(session.sessionId).associate { it.key to it.value }
        }
        suspend fun inputStatus(family: String) = database.getAnalysisDiagnostics(session.sessionId)
            .single { it.key == "Diagnostics/$family/InputStatus" }.stringValue
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val directory = Files.createTempDirectory("ares-analysis-inputs").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try { Fixture(database).block() } finally { database.close(); directory.deleteRecursively() }
    }
}
