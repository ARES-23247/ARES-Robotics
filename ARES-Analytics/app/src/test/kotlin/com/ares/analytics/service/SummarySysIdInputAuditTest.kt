package com.ares.analytics.service

import com.ares.analytics.shared.models.CalculatedSummary
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.ui.components.history.RunDataDictionary
import com.ares.analytics.ui.components.history.sysIdMotorNames
import org.mockito.Mockito
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class SummarySysIdInputAuditTest {
    @Test fun `initial nonzero speed is not a direction reversal`() = runTest { fixture {
        drive(10); generate(); assertEquals(10, fits.single().size)
    } }
    @Test fun `independently timed drive voltage aligns within the source time bound`() = runTest { fixture {
        drive(voltageOffset = 5_000); generate(); assertEquals(30, fits.single().size)
    } }
    @Test fun `distinct source microseconds never share a millisecond voltage map`() = runTest { fixture {
        repeat(60) { i ->
            val t = i / 2 * 20_000L + if (i % 2 == 0) 100 else 800
            add("Drive/Velocity", t, i + 1.0); add("Drive/Voltage", t, i + 10.0); add("Drive/Acceleration", t, 1.0)
        }
        generate(); assertEquals(60, fits.single().size)
        assertTrue(fits.single().all { it.voltage == it.velocity + 9 })
    } }
    @Test fun `a remote acceleration sample cannot characterize another interval`() = runTest { fixture {
        drive(acceleration = null); add("Drive/Acceleration", 5_000_000, 4.0)
        generate(); assertTrue(fits.isEmpty())
    } }
    @Test fun `recorded zero acceleration is never replaced with a derivative`() = runTest { fixture {
        drive(acceleration = 0.0); generate(); assertTrue(fits.single().all { it.accel == 0.0 })
    } }
    @Test fun `missing acceleration requires a real predecessor`() = runTest { fixture {
        drive(acceleration = null); generate(); assertEquals(29, fits.single().size)
        assertTrue(fits.single().all { it.accel == 100.0 })
    } }
    @Test fun `text voltage placeholders cannot enter a numerical fit`() = runTest { fixture {
        drive(); frames.replaceAll { if (it.key == "Drive/Voltage") it.copy(stringValue = "invalid") else it }
        generate(); assertTrue(fits.isEmpty())
    } }
    @Test fun `latest invalid voltage supersedes earlier values at the same source time`() = runTest { fixture {
        drive(); frames.filter { it.key == "Drive/Voltage" }.forEach { add(it.key, it.timestampUs, 1.0, "invalid") }
        generate(); assertTrue(fits.isEmpty())
    } }
    @Test fun `motor duty cycle is not a measured twelve volt supply`() = runTest { fixture {
        motor(voltageKey = "Power"); generate(); assertTrue(fits.isEmpty())
    } }
    @Test fun `nested motor configuration cannot supply applied voltage`() = runTest { fixture {
        motor(voltageKey = "Voltage/Configuration"); generate(); assertTrue(fits.isEmpty())
    } }
    @Test fun `voltage limits cannot be interpreted as motor voltage`() = runTest { fixture {
        motor(voltageKey = "VoltageLimit"); generate(); assertTrue(fits.isEmpty())
    } }
    @Test fun `preferred native velocity is not overwritten by an RPM stream`() = runTest { fixture {
        motor(); repeat(30) { add("Hardware/Motors/arm/VelocityRpm", it * 10_000L, 600.0 + it) }
        generate(); assertTrue(fits.single().all { it.velocity < 100.0 })
    } }
    @Test fun `invalid latest motor voltage does not revive earlier feedback`() = runTest { fixture {
        motor(); frames.filter { it.key.endsWith("/Voltage") }.forEach { add(it.key, it.timestampUs, 1.0, "invalid") }
        generate(); assertTrue(fits.isEmpty())
    } }
    @Test fun `motor names and electrical polarities do not establish angular drive voltage`() = runTest { fixture {
        motor("fl"); motor("fr"); repeat(30) { add("Drive/Velocity_Omega", it * 10_000L, it + 1.0) }
        val diagnostics = generate(); assertTrue(diagnostics.keys.none { it.startsWith("Diagnostics/SysId/Angular/") })
    } }
    @Test fun `holding voltage alone does not establish a gravity feedforward gain`() = runTest { fixture {
        repeat(30) { i -> add("Hardware/Motors/arm/Voltage", i * 10_000L, 2.0); add("Hardware/Motors/arm/Velocity", i * 10_000L, 0.0) }
        assertTrue(generate().keys.none { it.endsWith("/kG") })
    } }
    @Test fun `wheel encoder units cannot be compared to chassis speed as traction loss`() = runTest { fixture {
        motor("fl"); repeat(30) { add("Drive/Velocity", it * 10_000L, 2.0) }
        assertTrue("Diagnostics/Drive/TractionLoss" !in generate())
    } }
    @Test fun `untyped drive channels cannot imply metre based feedforward units`() {
        val labels = RunDataDictionary.buildBaseRowDefinitions().map { it.label }
        assertTrue(labels.none { it.contains("Linear kV (V/m/s)") || it.contains("Linear kA (V/m/s") })
    }

    @Test fun `alignment accepts fifty milliseconds but rejects the next microsecond`() {
        for (delta in listOf(50_000L, 50_001L)) {
            val rows = RecordedSysIdInputs.align(listOf(sample("u", delta, 4.0)), listOf(sample("v", 0, 2.0)), listOf(sample("a", 0, 1.0)))
            assertEquals(if (delta == 50_000L) 1 else 0, rows.size)
        }
    }
    @Test fun `invalid nearest voltage does not fall back to an older finite sample`() {
        val voltage = listOf(sample("u", 0, 4.0), sample("u", 10_000, 0.0, text = "unavailable"))
        assertTrue(RecordedSysIdInputs.align(voltage, listOf(sample("v", 10_000, 2.0)), listOf(sample("a", 10_000, 1.0))).isEmpty())
    }
    @Test fun `sample order outranks collection order when selecting latest updates`() {
        val latest = sample("u", 0, Double.NaN, order = 20)
        val earlier = sample("u", 0, 4.0, order = 10)
        for (voltage in listOf(listOf(latest, earlier), listOf(earlier, latest))) {
            assertTrue(RecordedSysIdInputs.align(voltage, listOf(sample("v", 0, 2.0)), listOf(sample("a", 0, 1.0))).isEmpty())
        }
    }
    @Test fun `recorded reversal exclusion is inclusive at fifty milliseconds`() {
        val times = listOf(0L, 49_999L, 50_000L, 75_000L, 100_000L, 125_000L, 150_000L, 150_001L)
        val velocity = times.map { sample("v", it, if (it < 100_000) 2.0 else -2.0) }
        val rows = RecordedSysIdInputs.align(times.map { sample("u", it, 3.0) }, velocity, times.map { sample("a", it, 1.0) })
        assertEquals(listOf(0L, 49L, 150L), rows.map { it.timestampMs })
    }
    @Test fun `a new segment after missing velocity does not invent a reversal timestamp`() {
        val velocity = listOf(sample("v", 0, 2.0), sample("v", 10_000, Double.NaN), sample("v", 20_000, -2.0))
        val rows = RecordedSysIdInputs.align(velocity.map { it.copy(key = "u", value = 3.0) }, velocity, velocity.map { it.copy(key = "a", value = 1.0) })
        assertEquals(listOf(0L, 20L), rows.map { it.timestampMs })
    }
    @Test fun `derived acceleration respects the exact gap bound`() {
        for (gap in listOf(50_000L, 50_001L)) {
            val velocity = listOf(sample("v", 0, 1.0), sample("v", gap, 2.0))
            val rows = RecordedSysIdInputs.align(velocity.map { it.copy(key = "u", value = 3.0) }, velocity)
            assertEquals(if (gap == 50_000L) 1 else 0, rows.size)
            if (rows.isNotEmpty()) assertEquals(20.0, rows.single().accel)
        }
    }
    @Test fun `an invalid velocity breaks the derivative chain`() {
        val velocity = listOf(sample("v", 0, 1.0), sample("v", 10_000, Double.NaN), sample("v", 20_000, 3.0))
        assertTrue(RecordedSysIdInputs.align(velocity.map { it.copy(key = "u", value = 3.0) }, velocity).isEmpty())
    }
    @Test fun `velocity derivatives retain submillisecond source precision`() {
        val velocity = listOf(sample("v", 100, 1.0), sample("v", 800, 1.7))
        val row = RecordedSysIdInputs.align(velocity.map { it.copy(key = "u", value = 3.0) }, velocity).single()
        assertEquals(1000.0, row.accel, 1e-10); assertEquals(0L, row.timestampMs)
    }
    @Test fun `derivative predecessor is independent of voltage alignment failures`() {
        val velocity = listOf(sample("v", 0, 1.0), sample("v", 10_000, 2.0), sample("v", 20_000, 4.0))
        val volts = listOf(sample("u", 0, 3.0), sample("u", 10_000, Double.NaN), sample("u", 20_000, 3.0))
        assertEquals(200.0, RecordedSysIdInputs.align(volts, velocity).single().accel)
    }
    @Test fun `unrepresentable derivatives never enter the solver`() {
        val velocity = listOf(sample("v", 0, 1e308), sample("v", 1, 1.7e308))
        assertTrue(RecordedSysIdInputs.align(velocity.map { it.copy(key = "u", value = 3.0) }, velocity).isEmpty())
    }
    @Test fun `explicitly required missing acceleration cannot turn into derived feedback`() {
        val velocity = listOf(sample("v", 0, 1.0), sample("v", 10_000, 2.0))
        assertTrue(RecordedSysIdInputs.align(velocity.map { it.copy(key = "u", value = 3.0) }, velocity, emptyList()).isEmpty())
    }
    @Test fun `source mixing and duplicated channel identities are rejected`() {
        val v = sample("v", 0, 2.0); val a = sample("a", 0, 1.0); val u = sample("u", 0, 3.0)
        assertFailsWith<IllegalArgumentException> { RecordedSysIdInputs.align(listOf(u.copy(sessionId = "other")), listOf(v), listOf(a)) }
        assertFailsWith<IllegalArgumentException> { RecordedSysIdInputs.align(listOf(u, u.copy(key = "other")), listOf(v), listOf(a)) }
        assertFailsWith<IllegalArgumentException> { RecordedSysIdInputs.align(listOf(v), listOf(v), listOf(a)) }
    }
    @Test fun `alignment leaves caller owned inputs unchanged`() {
        val velocity = listOf(sample("v", 20_000, 3.0), sample("v", 0, 1.0), sample("v", 10_000, 2.0))
        val before = velocity.toList()
        RecordedSysIdInputs.align(velocity.map { it.copy(key = "u", value = 3.0) }, velocity)
        assertEquals(before, velocity)
    }
    @Test fun `RPM and RPS keep their own acceleration basis`() = runTest { fixture {
        repeat(30) { i ->
            add("Hardware/Motors/arm/AppliedVoltage", i * 10_000L, 3.0)
            add("Hardware/Motors/arm/VelocityRps", i * 10_000L, i + 1.0)
            add("Hardware/Motors/arm/VelocityRpm", i * 10_000L, 600.0)
            add("Hardware/Motors/arm/AccelerationRps", i * 10_000L, 2.0)
            add("Hardware/Motors/arm/AccelerationRpm", i * 10_000L, 120.0)
        }
        generate(); assertTrue(fits.single().all { it.accel == 2.0 && it.velocity < 100.0 })
    } }
    @Test fun `an invalid preferred applied voltage never borrows a legacy voltage alias`() = runTest { fixture {
        motor(); repeat(30) { add("Hardware/Motors/arm/AppliedVoltage", it * 10_000L, Double.NaN) }
        generate(); assertTrue(fits.isEmpty())
    } }
    @Test fun `explicit signed angular effort supports recorded angular fitting`() = runTest { fixture {
        repeat(30) { i ->
            add("Drive/AngularVoltage", i * 10_000L, -3.0)
            add("Drive/Velocity_Omega", i * 10_000L, -i - 1.0)
            add("Drive/AngularAcceleration", i * 10_000L, -1.0)
        }
        val diagnostics = generate()
        assertEquals(1.6, diagnostics["Diagnostics/SysId/Angular/kV"])
        assertTrue(fits.single().all { it.voltage == -3.0 && it.accel == -1.0 })
    } }
    @Test fun `stored fits identify source channels and do not assert tuning approval`() = runTest { fixture {
        drive(); val values = generate()
        assertEquals(30.0, values["Diagnostics/SysId/FitSamples"])
        assertEquals(3.125, values["Diagnostics/SysId/InverseKA"])
        assertTrue(values.keys.none { it.endsWith("ADRC_b0") })
        val diagnostics = database.getAnalysisDiagnostics("sysid-summary").associateBy { it.key }
        assertEquals("Drive/Voltage", diagnostics["Diagnostics/SysId/VoltageSource"]?.stringValue)
        assertEquals("Drive/Acceleration", diagnostics["Diagnostics/SysId/AccelerationSource"]?.stringValue)
        assertTrue(assertNotNull(diagnostics["Diagnostics/SysId/Model"]?.stringValue).contains("no gravity model or tuning approval"))
    } }
    @Test fun `real summary regression recovers known coefficients with independent source timing`() = runTest { fixture {
        repeat(60) { i ->
            val t = i / 2 * 20_000L + if (i % 2 == 0) 100 else 800
            val v = 1.0 + 0.03 * i; val a = kotlin.math.sin(i * 0.7)
            add("Drive/Velocity", t, v); add("Drive/Acceleration", t + 10, a)
            add("Drive/Voltage", t + 10, 0.4 + 1.6 * v + 0.32 * a)
        }
        val result = generate(SysIdService(database))
        assertEquals(0.4, assertNotNull(result["Diagnostics/SysId/kS"]), 1e-10)
        assertEquals(1.6, assertNotNull(result["Diagnostics/SysId/kV"]), 1e-10)
        assertEquals(0.32, assertNotNull(result["Diagnostics/SysId/kA"]), 1e-10)
        assertEquals(1.0, assertNotNull(result["Diagnostics/SysId/R2"]), 1e-10)
    } }
    @Test fun `explicit channel analysis also rejects invalid latest numeric placeholders`() = runTest { fixture {
        drive(); frames.filter { it.key == "Drive/Voltage" }.forEach { add(it.key, it.timestampUs, 3.0, "missing") }
        database.insertTelemetryFrames(frames)
        val result = SysIdService(database).analyzeMotorData("sysid-summary", "Drive/Voltage", "Drive/Velocity", "Drive/Acceleration")
        assertEquals(0.0, result.rSquared)
    } }
    @Test fun `history rejects legacy unproven fits and invalid fit metadata`() {
        val row = RunDataDictionary.buildBaseRowDefinitions().single { it.label == "Drive kV (V/native speed)" }
        val session = Session("s", "t", "y", "r", 0)
        for (samples in listOf(null, 0.0, 9.0, 10.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            val values = mutableMapOf("Diagnostics/SysId/kV" to 1.6)
            if (samples != null) values["Diagnostics/SysId/FitSamples"] = samples
            assertEquals("N/A", row.getValue(session, null, values)); assertNull(row.getNumericValue(session, null, values))
        }
    }
    @Test fun `history preserves finite signed gains without implying SI conversion`() {
        val row = RunDataDictionary.buildBaseRowDefinitions().single { it.label == "Drive kV (V/native speed)" }
        val session = Session("s", "t", "y", "r", 0)
        for (value in listOf(-1.6, 1.6, Double.NaN, Double.POSITIVE_INFINITY)) {
            val values = mapOf("Diagnostics/SysId/FitSamples" to 30.0, "Diagnostics/SysId/kV" to value)
            assertEquals(value.takeIf { it.isFinite() }, row.getNumericValue(session, null, values))
        }
    }
    @Test fun `SysId motor discovery preserves distinct device names and rejects nested paths`() {
        assertEquals(listOf("FL", "bl", "fl", "rl"), sysIdMotorNames(listOf(mapOf(
            "Diagnostics/SysId/Motors/fl/kV" to 1.0, "Diagnostics/SysId/Motors/FL/kV" to 2.0,
            "Diagnostics/SysId/Motors/rl/kV" to 3.0, "Diagnostics/SysId/Motors/bl/kV" to 4.0,
            "Diagnostics/SysId/Motors/fl/nested/kV" to 5.0, "Diagnostics/SysId/Motors//kV" to 6.0))))
    }
    @Test fun `large alternating velocity sequence excludes every near reversal sample`() {
        val velocity = List(20_000) { sample("v", it * 1_000L, if (it % 2 == 0) 1.0 else -1.0) }
        assertTrue(RecordedSysIdInputs.align(velocity.map { it.copy(key = "u", value = 3.0) }, velocity, velocity.map { it.copy(key = "a", value = 1.0) }).isEmpty())
        println("SysId alignment stress: 20000 velocity samples and 19999 reversals; monotonic cursor implementation")
    }

    private fun sample(key: String, time: Long, value: Double, order: Long = 0, text: String? = null) =
        TelemetryFrame(time / 1_000, "s", key, value, text, time, order)

    private class Fixture(val database: DatabaseService) {
        val frames = mutableListOf<TelemetryFrame>()
        val fits = mutableListOf<List<AlignedDataRow>>()
        private val solver = Mockito.mock(SysIdService::class.java) { invocation ->
            if (invocation.method.name == "analyzeRawData") {
                fits += invocation.getArgument<List<AlignedDataRow>>(0).toList()
                CalculatedSummary(kS = 0.4, kV = 1.6, kA = 0.32, rSquared = 1.0)
            } else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        fun add(key: String, timeUs: Long, value: Double, text: String? = null) {
            frames += TelemetryFrame(timeUs / 1_000, "sysid-summary", key, value, text, timeUs)
        }
        fun drive(n: Int = 30, voltageOffset: Long = 0, acceleration: Double? = 1.0) {
            repeat(n) { i ->
                val t = i * 10_000L
                add("Drive/Velocity", t, i + 1.0); add("Drive/Voltage", t + voltageOffset, i + 10.0)
                if (acceleration != null) add("Drive/Acceleration", t, acceleration)
            }
        }
        fun motor(name: String = "arm", voltageKey: String = "Voltage") {
            repeat(30) { i ->
                add("Hardware/Motors/$name/$voltageKey", i * 10_000L, 0.5 + i * 0.01)
                add("Hardware/Motors/$name/Velocity", i * 10_000L, i + 1.0)
            }
        }
        suspend fun generate(selectedSolver: SysIdService = solver): Map<String, Double> {
            database.insertTelemetryFrames(frames)
            val session = Session("sysid-summary", "team", "season", "robot", 0L, 1000L)
            SummaryEngineService(database, selectedSolver, DriverAnalysisService(database, selectedSolver)).generateSummary(session)
            return database.getAnalysisDiagnostics(session.sessionId).associate { it.key to it.value }
        }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val directory = Files.createTempDirectory("ares-summary-sysid").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try { Fixture(database).block() } finally { database.close(); directory.deleteRecursively() }
    }
}
