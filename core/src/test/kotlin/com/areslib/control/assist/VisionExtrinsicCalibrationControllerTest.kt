package com.areslib.control.assist

import com.areslib.action.RobotAction
import com.areslib.action.CalibrationFrameLogged
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Quaternion
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Pose3d
import com.areslib.state.RobotState
import com.areslib.state.VisionMeasurement
import com.areslib.Store
import com.areslib.telemetry.ARESNetworkStatePublisher
import com.areslib.telemetry.ITelemetry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.areslib.control.feedback.PIDController
import com.areslib.control.drivetrain.HolonomicDriveController
import com.areslib.state.RobotFieldAprilTag
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldManager

/**
 * VisionExtrinsicCalibrationControllerTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class VisionExtrinsicCalibrationControllerTest {

    @Test
    /**
     * testCalibrationSweepAndTargetLogging declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testCalibrationSweepAndTargetLogging() {
        val actions = mutableListOf<RobotAction>()
        val store = Store(RobotState()) { state, action ->
            actions.add(action)
            com.areslib.reducer.rootReducer(state, action)
        }

        val xController = PIDController(p = 1.0, i = 0.0, d = 0.0)
        val yController = PIDController(p = 1.0, i = 0.0, d = 0.0)
        val thetaController = PIDController(p = 1.0, i = 0.0, d = 0.0)
        val holonomic = HolonomicDriveController(xController, yController, thetaController)

        val telemetryMap = mutableMapOf<String, Any>()
        val mockTelemetry = object : ITelemetry {
            override fun putNumber(key: String, value: Double) { telemetryMap[key] = value }
            override fun putBoolean(key: String, value: Boolean) { telemetryMap[key] = value }
            override fun putString(key: String, value: String) { telemetryMap[key] = value }
            override fun putDoubleArray(key: String, value: DoubleArray) { telemetryMap[key] = value }
            override fun getNumber(key: String, defaultValue: Double): Double = defaultValue
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
            override fun getString(key: String, defaultValue: String): String = defaultValue
            override fun update() {}
        }
        val publisher = ARESNetworkStatePublisher(mockTelemetry)

        val controller = VisionExtrinsicCalibrationController(
            store = store,
            holonomicDriveController = holonomic,
            publisher = publisher,
            sweepSpeedRadPerSec = 1.0
        )

        // 1. Initial State: inactive
        assertFalse(controller.isActive)
        val speeds0 = controller.update(Pose2d(0.0, 0.0, Rotation2d()), emptyList(), 0.02)
        assertEquals(0.0, speeds0.vxMetersPerSecond)
        assertEquals(0.0, speeds0.vyMetersPerSecond)
        assertEquals(0.0, speeds0.omegaRadiansPerSecond)

        // 2. Start sweep
        controller.start(cameraIndex = 1, currentHeading = 0.0)
        assertTrue(controller.isActive)
        assertEquals(1, controller.cameraIndex)

        // 3. Update active sweep with detected vision tag
        val priorFieldConfig = RobotFieldManager.activeConfig
        RobotFieldManager.setActiveConfig(
            RobotFieldConfig(apriltags = listOf(RobotFieldAprilTag(id = 42, x = 4.0, y = 5.0, z = 0.75)))
        )
        val visionMeasurement = VisionMeasurement(
            timestampMs = 12345L,
            tagId = 42,
            robotPoseTargetSpace = Pose3d(Translation3d(1.0, 2.0, 3.0), Rotation3d(Quaternion(1.0, 0.0, 0.0, 0.0)))
        )

        val speeds1 = controller.update(
            currentPose = Pose2d(0.0, 0.0, Rotation2d()),
            measurements = listOf(visionMeasurement),
            dtSeconds = 0.02
        )

        // Verify speeds contains rotational command
        assertTrue(speeds1.omegaRadiansPerSecond != 0.0)

        // Verify actions contains CalibrationFrameLogged
        val loggedFrames = actions.filterIsInstance<CalibrationFrameLogged>()
        assertEquals(1, loggedFrames.size)
        val frame = loggedFrames[0]
        assertEquals(42, frame.tagId)
        assertEquals(1, frame.cameraIndex)

        // Verify Telemetry publisher output
        assertEquals(true, telemetryMap["Calibration/IsActive"])
        assertEquals(42.0, telemetryMap["Calibration/TagIndex"])
        assertArrayEquals(
            doubleArrayOf(1.0, 2.0, 3.0, 0.0, 0.0, 0.0),
            telemetryMap["Calibration/CameraToTag"] as DoubleArray,
            1e-9
        )
        assertArrayEquals(
            doubleArrayOf(4.0, 5.0, 0.75),
            telemetryMap["Calibration/TagField"] as DoubleArray,
            1e-9
        )
        RobotFieldManager.setActiveConfig(priorFieldConfig)
    }
}
