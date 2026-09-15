package com.areslib.math.estimation

import com.areslib.math.geometry.Matrix3x3
import com.google.gson.JsonParser
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CalibrationNumericsAuditTest {
    @TempDir lateinit var directory: File
    @Test
    fun `empty calibration exports valid JSON with explicit unavailable values`() {
        val json = JsonParser.parseString(LocalizationCalibrationFitter.fit(emptyList()).toJson()).asJsonObject
        assertEquals(0, json.getAsJsonObject("mt1").get("sampleCount").asInt)
        assertTrue(json.getAsJsonObject("mt1").get("stdDevX").isJsonNull)
        assertTrue(json.getAsJsonObject("consistency").get("meanNis").isJsonNull)
    }

    @Test
    fun `truth validity flag cannot admit nonfinite truth into a fit`() {
        val result = LocalizationCalibrationFitter.fit(listOf(sample(1L, 0.1), sample(2L, 0.2).copy(truthX = Double.NaN)))
        assertEquals(1, result.mt1.sampleCount)
        assertEquals(0.1, result.mt1.biasX, 1e-12)
    }

    @Test
    fun `small measurement noise around a large bias survives variance calculation`() {
        val result = LocalizationCalibrationFitter.fit(listOf(sample(1L, 1e12 - 1.0), sample(2L, 1e12 + 1.0)))
        assertEquals(1e12, result.mt1.biasX)
        assertEquals(sqrt(2.0), result.mt1.stdDevX, 1e-12)
    }

    @Test
    fun `heading residuals near the wrap boundary form one circular cluster`() {
        val result = LocalizationCalibrationFitter.fit(listOf(
            sample(1L, 0.0).copy(mt1Heading = PI - 0.01),
            sample(2L, 0.0).copy(mt1Heading = -PI + 0.01)
        ))
        assertEquals(PI, abs(result.mt1.biasHeading), 1e-12)
        assertEquals(sqrt(2.0) * 0.01, result.mt1.stdDevHeading, 1e-10)
    }

    @Test
    fun `route end must occur after its start`() {
        val start = sample(20L, 0.0).copy(testType = LocalizationCalibrationTestType.ODOMETRY_TRANSLATION,
            checkpoint = LocalizationCalibrationCheckpoint.START)
        val end = start.copy(timestampMs = 10L, checkpoint = LocalizationCalibrationCheckpoint.END,
            truthX = 1.0, odometryX = 1.1)
        assertEquals(0, LocalizationCalibrationFitter.fit(listOf(start, end)).processNoise.routeCount)
    }

    @Test
    fun `separate calibration logs cannot merge reused route IDs`() {
        val files = (1..2).map { index ->
            val folder = File(directory, "run$index").also { it.mkdirs() }
            val start = sample(1L, 0.0).copy(testType = LocalizationCalibrationTestType.ODOMETRY_TRANSLATION,
                checkpoint = LocalizationCalibrationCheckpoint.START)
            val end = start.copy(timestampMs = 2L, checkpoint = LocalizationCalibrationCheckpoint.END,
                truthX = 1.0, odometryX = 1.0 + index * 0.1)
            LocalizationCalibrationRecorder(LocalizationCalibrationPlatform.FTC, folder).use {
                it.record(start)
                it.record(end)
            }
            folder.listFiles()!!.single { it.name.endsWith(".csv.gz") }
        }
        val result = LocalizationCalibrationFitter.fit(LocalizationCalibrationCsv.read(files))
        assertEquals(2, result.processNoise.routeCount)
        assertEquals(0.025, result.processNoise.qX, 1e-12)
    }

    @Test
    fun `signed surveyed turns survive capture CSV and normalize angular process noise`() {
        val samples = (-3..3).filter { it != 0 }.flatMap { turns ->
            val start = LocalizationCalibrationSample.capture(
                100L, LocalizationCalibrationPlatform.FRC, LocalizationCalibrationTestType.ODOMETRY_ROTATION,
                turns, com.areslib.state.RobotState(), emptyList(),
                checkpoint = LocalizationCalibrationCheckpoint.START, truthValid = true,
                truthX = 0.0, truthY = 0.0, truthHeading = 0.0, truthHeadingUnwrapped = true
            )
            listOf(start, start.copy(timestampMs = 200L, checkpoint = LocalizationCalibrationCheckpoint.END,
                truthHeading = turns * 2.0 * PI, odometryHeading = 0.1))
        }
        LocalizationCalibrationRecorder(LocalizationCalibrationPlatform.FRC, directory).use { recorder ->
            samples.forEach(recorder::record)
        }
        val decoded = LocalizationCalibrationCsv.read(directory.listFiles()!!.filter { it.name.endsWith(".csv.gz") })
        assertEquals(samples.size, decoded.size)
        assertTrue(decoded.all { it.truthHeadingUnwrapped })
        for (turns in (-3..3).filter { it != 0 }) {
            val fit = LocalizationCalibrationFitter.fit(decoded.filter { it.runId == turns }).processNoise
            assertEquals(1, fit.routeCount)
            assertEquals(0.01 / (abs(turns) * 2.0 * PI), fit.qTheta, 1e-12)
        }
    }

    @Test
    fun `legacy heading routes retain shortest arc and reject overflowed differences`() {
        val start = sample(1L, 0.0).copy(testType = LocalizationCalibrationTestType.ODOMETRY_ROTATION,
            checkpoint = LocalizationCalibrationCheckpoint.START, truthHeading = PI - 0.1, odometryHeading = PI - 0.1)
        val end = start.copy(timestampMs = 2L, checkpoint = LocalizationCalibrationCheckpoint.END,
            truthHeading = -PI + 0.1, odometryHeading = -PI + 0.2)
        assertEquals(0.05, LocalizationCalibrationFitter.fit(listOf(start, end)).processNoise.qTheta, 1e-12)
        assertEquals(0, LocalizationCalibrationFitter.fit(listOf(start.copy(truthHeading = -Double.MAX_VALUE),
            end.copy(truthHeading = Double.MAX_VALUE))).processNoise.routeCount)
        val legacy = File(directory, "legacy.csv")
        legacy.writeText("TimestampMs,Platform,TestType,RunId,Checkpoint\n1,FTC,ODOMETRY_ROTATION,1,START\n")
        assertFalse(LocalizationCalibrationCsv.read(listOf(legacy)).single().truthHeadingUnwrapped)
    }

    @Test
    fun `NEES rejects malformed upper covariance rather than ignoring it`() {
        for (upper in doubleArrayOf(Double.NaN, 0.5)) {
            val covariance = Matrix3x3(1.0, upper, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            val evaluator = LocalizationConsistencyEvaluator()
            assertFalse(evaluator.recordNees(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, covariance))
            assertEquals(0L, evaluator.snapshot().neesCount)
        }
    }

    @Test
    fun `NEES is invariant under consistent changes in error and covariance units`() {
        val evaluator = LocalizationConsistencyEvaluator()
        val covariance = Matrix3x3(1e-20, 0.0, 0.0, 0.0, 1e-20, 0.0, 0.0, 0.0, 1e-20)
        assertTrue(evaluator.recordNees(1e-10, 0.0, 0.0, 0.0, 0.0, 0.0, covariance))
        assertEquals(1.0, evaluator.snapshot().meanNees, 1e-12)
    }

    @Test
    fun `finite NIS samples retain a finite mean when their sum would overflow`() {
        val evaluator = LocalizationConsistencyEvaluator()
        repeat(2) { evaluator.recordNis(Double.MAX_VALUE) }
        val result = evaluator.snapshot()
        assertEquals(Double.MAX_VALUE, result.meanNis, Double.MAX_VALUE * 1e-14)
        assertEquals(Double.MAX_VALUE / 3.0, result.meanNormalizedNis, Double.MAX_VALUE * 1e-14)
    }

    @Test
    fun `NEES matches a known correlated factor and rejects nonfinite pose inputs`() {
        val evaluator = LocalizationConsistencyEvaluator()
        val l = doubleArrayOf(2.0, 0.0, 0.0, 0.3, 1.1, 0.0, -0.2, 0.4, 0.8)
        val y = doubleArrayOf(0.25, -0.3, 0.4)
        val p = DoubleArray(9) { i -> (0..2).sumOf { k -> l[i / 3 * 3 + k] * l[i % 3 * 3 + k] } }
        val e = DoubleArray(3) { i -> (0..2).sumOf { k -> l[i * 3 + k] * y[k] } }
        for (scale in doubleArrayOf(1.0, 1e-16)) {
            val q = p.map { it * scale }
            val covariance = Matrix3x3(q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7], q[8])
            assertTrue(evaluator.recordNees(e[0] * sqrt(scale), e[1] * sqrt(scale), e[2] * sqrt(scale),
                0.0, 0.0, 0.0, covariance))
        }
        assertEquals(y.sumOf { it * it }, evaluator.snapshot().meanNees, 1e-12)
        assertFalse(evaluator.recordNees(0.0, 0.0, Double.NaN, 0.0, 0.0, 0.0, Matrix3x3.IDENTITY))
        assertEquals(2L, evaluator.snapshot().neesCount)
    }

    @Test
    fun `mixed degrees of freedom invalid observations and reset preserve statistical meaning`() {
        val evaluator = LocalizationConsistencyEvaluator()
        evaluator.recordNis(3.0, 3)
        evaluator.recordNis(8.0, 2)
        for (invalid in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) evaluator.recordNis(invalid)
        evaluator.recordNis(100.0, 4)
        val result = evaluator.snapshot()
        assertEquals(2L, result.nisCount)
        assertEquals(5.5, result.meanNis)
        assertEquals(2.2, result.meanNormalizedNis, 1e-12)
        assertEquals(0.5, result.nisCoverage95)
        evaluator.reset()
        assertEquals(0L, evaluator.snapshot().nisCount)
        assertTrue(evaluator.snapshot().meanNis.isNaN())
    }

    @Test
    fun `insufficient and directionally ambiguous samples report unavailable uncertainty`() {
        val single = LocalizationCalibrationFitter.fit(listOf(sample(1L, 0.0)))
        assertTrue(single.mt1.stdDevX.isNaN())
        val ambiguous = LocalizationCalibrationFitter.fit(listOf(sample(1L, 0.0), sample(2L, 0.0).copy(mt1Heading = PI)))
        assertTrue(ambiguous.mt1.biasHeading.isNaN())
        assertTrue(ambiguous.mt1.stdDevHeading.isNaN())
    }

    @Test
    fun `CSV handles BOM duplicate paths malformed rows and invalid DOF without inventing data`() {
        val file = File(directory, "mixed.csv")
        file.writeText("\uFEFFTimestampMs,Platform,TestType,RunId,Checkpoint,NIS,NISDegreesOfFreedom\n" +
            "1,FTC,VISION_STATIONARY,bad,NONE,1,3\n" +
            "2,FTC,VISION_STATIONARY,7,NONE,1,4\n" +
            "3,FOREIGN,VISION_STATIONARY,8,NONE,1,3\n")
        val samples = LocalizationCalibrationCsv.read(listOf(file, File(directory, "./mixed.csv")))
        assertEquals(1, samples.size)
        assertEquals(7, samples.single().runId)
        assertEquals(0L, LocalizationCalibrationFitter.fit(samples).consistency.nisCount)
    }

    @Test
    fun `CLI requires an input and cannot overwrite a source log`() {
        val output = File(directory, "report.json")
        assertFailsWith<IllegalArgumentException> { LocalizationCalibrationCli.main(arrayOf("--output", output.path)) }
        val input = File(directory, "source.csv")
        val original = "TimestampMs,Platform,TestType,RunId,Checkpoint\n"
        input.writeText(original)
        assertFailsWith<IllegalArgumentException> {
            LocalizationCalibrationCli.main(arrayOf(input.path, "--output", input.path))
        }
        assertEquals(original, input.readText())
        LocalizationCalibrationCli.main(arrayOf(input.path, "--output", output.path))
        assertEquals(0, JsonParser.parseString(output.readText()).asJsonObject.getAsJsonObject("mt1")
            .get("sampleCount").asInt)
        assertEquals(original, input.readText())
    }

    private fun sample(time: Long, measurementX: Double) = LocalizationCalibrationSample(
        timestampMs = time, platform = LocalizationCalibrationPlatform.FTC,
        testType = LocalizationCalibrationTestType.VISION_STATIONARY, runId = 1,
        truthValid = true, truthX = 0.0, truthY = 0.0, truthHeading = 0.0,
        odometryX = 0.0, odometryY = 0.0, odometryHeading = 0.0,
        estimateX = 0.0, estimateY = 0.0, estimateHeading = 0.0,
        covariance = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
        linearVelocityMps = 0.0, angularVelocityRadPerSec = 0.0,
        mt1Valid = true, mt1X = measurementX, mt1Y = 0.0, mt1Heading = 0.0
    )
}
