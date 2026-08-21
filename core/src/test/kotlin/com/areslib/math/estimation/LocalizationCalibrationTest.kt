package com.areslib.math.estimation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalizationCalibrationTest {
    @Test
    fun `fitter estimates vision bias variance and route-normalized process noise`() {
        val samples = mutableListOf(
            sample(1, truthX = 1.0, mt1X = 0.9, mt2X = 0.8),
            sample(2, truthX = 1.0, mt1X = 1.1, mt2X = 1.2),
            route(10, LocalizationCalibrationCheckpoint.START, truthX = 0.0, odomX = 0.0),
            route(10, LocalizationCalibrationCheckpoint.END, truthX = 2.0, odomX = 2.2)
        )

        val report = LocalizationCalibrationFitter.fit(samples)

        assertEquals(2, report.mt1.sampleCount)
        assertEquals(0.0, report.mt1.biasX, 1e-12)
        assertEquals(kotlin.math.sqrt(0.02), report.mt1.stdDevX, 1e-12)
        assertEquals(1, report.processNoise.routeCount)
        assertEquals(0.02, report.processNoise.qX, 1e-12)
    }

    @Test
    fun `recorder CSV round trips into the offline parser`() {
        val directory = Files.createTempDirectory("localization-calibration").toFile()
        LocalizationCalibrationRecorder(LocalizationCalibrationPlatform.FTC, directory).use { recorder ->
            recorder.record(sample(7, truthX = 2.0, mt1X = 2.1, mt2X = 1.9))
        }

        val file = directory.listFiles().orEmpty().single { it.name.endsWith(".csv.gz") }
        val parsed = LocalizationCalibrationCsv.read(listOf(file))

        assertEquals(1, parsed.size)
        assertEquals(LocalizationCalibrationPlatform.FTC, parsed.single().platform)
        assertEquals(2.1, parsed.single().mt1X, 1e-9)
        assertTrue(parsed.single().truthValid)
    }

    private fun sample(id: Int, truthX: Double, mt1X: Double, mt2X: Double) =
        LocalizationCalibrationSample(
            timestampMs = id.toLong(),
            platform = LocalizationCalibrationPlatform.FTC,
            testType = LocalizationCalibrationTestType.VISION_STATIONARY,
            runId = id,
            truthValid = true,
            truthX = truthX,
            truthY = 0.0,
            truthHeading = 0.0,
            odometryX = truthX,
            odometryY = 0.0,
            odometryHeading = 0.0,
            estimateX = truthX,
            estimateY = 0.0,
            estimateHeading = 0.0,
            covariance = identity(),
            linearVelocityMps = 0.0,
            angularVelocityRadPerSec = 0.0,
            mt1Valid = true,
            mt1X = mt1X,
            mt1Y = 0.0,
            mt1Heading = 0.0,
            mt2Valid = true,
            mt2X = mt2X,
            mt2Y = 0.0,
            mt2Heading = 0.0,
            nis = 1.0,
            visionAccepted = true
        )

    private fun route(
        runId: Int,
        checkpoint: LocalizationCalibrationCheckpoint,
        truthX: Double,
        odomX: Double
    ) = LocalizationCalibrationSample(
        timestampMs = if (checkpoint == LocalizationCalibrationCheckpoint.START) 1L else 2L,
        platform = LocalizationCalibrationPlatform.FTC,
        testType = LocalizationCalibrationTestType.ODOMETRY_TRANSLATION,
        runId = runId,
        checkpoint = checkpoint,
        truthValid = true,
        truthX = truthX,
        truthY = 0.0,
        truthHeading = 0.0,
        odometryX = odomX,
        odometryY = 0.0,
        odometryHeading = 0.0,
        estimateX = odomX,
        estimateY = 0.0,
        estimateHeading = 0.0,
        covariance = identity(),
        linearVelocityMps = 0.0,
        angularVelocityRadPerSec = 0.0
    )

    private fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
}
