package com.ares.analytics.service

import com.ares.analytics.service.calibration.CameraCalibrationMathAuditTest.Companion.assertPose
import com.ares.analytics.service.calibration.CameraCalibrationMathAuditTest.Companion.frames
import com.ares.analytics.service.calibration.CameraCalibrationMathAuditTest.Companion.observations
import kotlinx.coroutines.test.runTest
import org.mockito.Mockito.mock
import java.nio.file.Files
import kotlin.test.*

class CalibrationServiceTest {
    @Test fun testSolveCameraExtrinsics() {
        val service = CalibrationService(mock(DatabaseService::class.java))
        val expected = Pose3d(0.1, 0.2, 0.3, 0.03, -0.04, 0.05)
        val data = observations(expected)
        assertPose(expected, service.solveCameraExtrinsics(data))
        val diagnostics = service.solveCameraExtrinsicsWithDiagnostics(data)
        assertPose(expected, diagnostics.pose)
        assertEquals(6, diagnostics.standardErrors.size)
        assertTrue(diagnostics.standardErrors.all { it.isFinite() && it >= 0.0 })
        assertTrue(diagnostics.covarianceMatrix.all { it.size == 6 })
        assertTrue(diagnostics.residualVariance >= 0.0)
    }

    @Test fun testRunExtrinsicCalibration() = runTest {
        val directory = Files.createTempDirectory("ares-calibration-service-").toFile()
        val database = DatabaseService(directory.resolve("test.db").absolutePath)
        try {
            val expected = Pose3d(0.1, 0.2, 0.3, 0.03, -0.04, 0.05)
            database.insertTelemetryFrames(frames(observations(expected)))
            val service = CalibrationService(database)
            assertPose(expected, service.runExtrinsicCalibration("calibration", 0))
            val diagnostics = service.runExtrinsicCalibrationWithDiagnostics("calibration", 0)
            assertPose(expected, diagnostics.pose)
            assertEquals(6, diagnostics.standardErrors.size)
        } finally {
            database.close()
            check(directory.name.startsWith("ares-calibration-service-"))
            check(directory.deleteRecursively())
        }
    }

    @Test fun emptyMeasurementsAreNotASuccessfulZeroCalibration() {
        val service = CalibrationService(mock(DatabaseService::class.java))
        assertFailsWith<IllegalArgumentException> { service.solveCameraExtrinsics(emptyList()) }
    }

    @Test fun emptyDiagnosticsAreNotPerfectCertainty() {
        val service = CalibrationService(mock(DatabaseService::class.java))
        assertFailsWith<IllegalArgumentException> { service.solveCameraExtrinsicsWithDiagnostics(emptyList()) }
    }
}
