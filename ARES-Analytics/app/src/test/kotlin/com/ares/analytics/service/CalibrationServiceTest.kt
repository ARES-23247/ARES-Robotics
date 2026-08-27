package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryFrame
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CalibrationServiceTest class.
 */
class CalibrationServiceTest {

    @Test
    /**
     * testSolveCameraExtrinsics fun.
     */
    fun testSolveCameraExtrinsics() {
        val tempDb = File.createTempFile("calib_solve_test", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val calibrationService = CalibrationService(databaseService)

        // Mock camera extrinsics and generate target-space translations with the
        // inverse of the solver's standard Rz(yaw)*Ry(pitch)*Rx(roll) transform.
        val targetDx = 0.1
        val targetDy = 0.2
        val targetDz = 0.3
        val targetRoll = 0.03
        val targetPitch = -0.04
        val targetYaw = 0.05
        val measurements = mutableListOf<CalibrationMeasurement>()
        // Generate mock measurements at different gyro positions
        for (i in 0 until 5) {
            val gyro = i * 0.2
            val Cx = 1.0
            val Cy = 1.5
            val Cz = 0.5
            val cosG = kotlin.math.cos(gyro)
            val sinG = kotlin.math.sin(gyro)
            val robotVector = doubleArrayOf(
                (-Cx * sinG + Cy * cosG) - targetDy,
                Cz - targetDz,
                (Cx * cosG + Cy * sinG) - targetDx
            )
            val cr = kotlin.math.cos(targetRoll); val sr = kotlin.math.sin(targetRoll)
            val cp = kotlin.math.cos(targetPitch); val sp = kotlin.math.sin(targetPitch)
            val cy = kotlin.math.cos(targetYaw); val sy = kotlin.math.sin(targetYaw)
            val rotation = arrayOf(
                doubleArrayOf(cy * cp, cy * sp * sr - sy * cr, cy * sp * cr + sy * sr),
                doubleArrayOf(sy * cp, sy * sp * sr + cy * cr, sy * sp * cr - cy * sr),
                doubleArrayOf(-sp, cp * sr, cp * cr)
            )
            val measured = DoubleArray(3) { row ->
                rotation.indices.sumOf { col -> rotation[col][row] * robotVector[col] }
            }

            measurements.add(
                CalibrationMeasurement(
                    gyroHeading = gyro,
                    tagId = 1,
                    tagFieldX = Cx,
                    tagFieldY = Cy,
                    tagFieldZ = Cz,
                    targetSpaceX = measured[0],
                    targetSpaceY = measured[1],
                    targetSpaceZ = measured[2],
                    targetSpaceRoll = 0.0,
                    targetSpacePitch = 0.0,
                    targetSpaceYaw = 0.0
                )
            )
        }
        val solved = calibrationService.solveCameraExtrinsics(measurements)
        assertEquals(targetDx, solved.x, 0.05)
        assertEquals(targetDy, solved.y, 0.05)
        assertEquals(targetDz, solved.z, 0.05)
        assertEquals(targetRoll, solved.roll, 0.05)
        assertEquals(targetPitch, solved.pitch, 0.05)
        assertEquals(targetYaw, solved.yaw, 0.05)

        // Test diagnostics version
        val diag = calibrationService.solveCameraExtrinsicsWithDiagnostics(measurements)
        assertEquals(targetDx, diag.pose.x, 0.05)
        assertEquals(targetDy, diag.pose.y, 0.05)
        assertEquals(targetYaw, diag.pose.yaw, 0.05)

        // Assert standard errors are non-negative and reasonably sized
        assertEquals(6, diag.standardErrors.size)
        diag.standardErrors.forEach { se ->
            assertTrue(se >= 0.0)
        }
        // Assert covariance matrix is 6x6
        assertEquals(6, diag.covarianceMatrix.size)
        diag.covarianceMatrix.forEach { row ->
            assertEquals(6, row.size)
        }
        assertTrue(diag.reducedChiSquared >= 0.0)

        tempDb.delete()
    }

    @Test
    /**
     * testRunExtrinsicCalibration fun.
     */
    fun testRunExtrinsicCalibration() = runTest {
        val tempDb = File.createTempFile("calib_run_test", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val calibrationService = CalibrationService(databaseService)
        val sessionId = "calib-session"
        val cameraIndex = 0

        // Insert a minimally observable sweep. The target-space payload is six Euler
        // components and the actual configured field-tag position is a separate array.
        val frames = buildList {
            repeat(5) { sample ->
                val timestamp = 1000L + sample * 20L
                val gyro = sample * 0.2
                add(TelemetryFrame(timestamp, sessionId, "Calibration/GyroHeading", gyro))
                add(TelemetryFrame(timestamp, sessionId, "Calibration/TagIndex", 1.0))
                add(TelemetryFrame(timestamp, sessionId, "Calibration/CameraIndex", 0.0))
                add(TelemetryFrame(timestamp, sessionId, "Calibration/CameraToTag/0", 0.5))
                add(TelemetryFrame(timestamp, sessionId, "Calibration/CameraToTag/1", 0.2))
                add(TelemetryFrame(timestamp, sessionId, "Calibration/CameraToTag/2", 1.2))
                add(TelemetryFrame(timestamp, sessionId, "Calibration/CameraToTag/3", 0.0))
                add(TelemetryFrame(timestamp, sessionId, "Calibration/CameraToTag/4", 0.0))
                add(TelemetryFrame(timestamp, sessionId, "Calibration/CameraToTag/5", -0.05))
                add(TelemetryFrame(timestamp, sessionId, "Calibration/TagField/0", 1.8))
                add(TelemetryFrame(timestamp, sessionId, "Calibration/TagField/1", 0.6))
                add(TelemetryFrame(timestamp, sessionId, "Calibration/TagField/2", 0.12))
            }
        }

        databaseService.insertTelemetryFrames(frames)
        val solved = calibrationService.runExtrinsicCalibration(sessionId, cameraIndex)
        // Canonical normalized array topics must produce a real measurement instead
        // of the all-zero sentinel returned for missing calibration data.
        assertTrue(solved != Pose3d(0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        val diag = calibrationService.runExtrinsicCalibrationWithDiagnostics(sessionId, cameraIndex)
        assertTrue(diag.pose != Pose3d(0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(6, diag.standardErrors.size)
        databaseService.close()
        tempDb.delete()
    }

    @Test
    fun testSolveCameraExtrinsicsEmptyMeasurementsReturnsZero() {
        val tempDb = File.createTempFile("calib_empty_test", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val calibrationService = CalibrationService(databaseService)

        val solved = calibrationService.solveCameraExtrinsics(emptyList())
        assertEquals(0.0, solved.x)
        assertEquals(0.0, solved.y)
        assertEquals(0.0, solved.z)
        assertEquals(0.0, solved.roll)
        assertEquals(0.0, solved.pitch)
        assertEquals(0.0, solved.yaw)

        databaseService.close()
        tempDb.delete()
    }

    @Test
    fun testSolveCameraExtrinsicsWithDiagnosticsEmptyMeasurementsReturnsZero() {
        val tempDb = File.createTempFile("calib_empty_diag_test", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val calibrationService = CalibrationService(databaseService)

        val diag = calibrationService.solveCameraExtrinsicsWithDiagnostics(emptyList())
        assertEquals(0.0, diag.pose.x)
        assertEquals(0.0, diag.pose.y)
        assertEquals(0.0, diag.pose.z)
        assertEquals(0.0, diag.pose.roll)
        assertEquals(0.0, diag.pose.pitch)
        assertEquals(0.0, diag.pose.yaw)
        assertEquals(6, diag.standardErrors.size)
        assertEquals(6, diag.covarianceMatrix.size)
        assertEquals(0.0, diag.reducedChiSquared)

        databaseService.close()
        tempDb.delete()
    }
}
