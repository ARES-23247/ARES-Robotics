package com.areslib.frc

import com.areslib.action.RobotAction
import com.areslib.state.*
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.telemetry.ITelemetry
import com.areslib.hardware.drive.SwerveHardwareIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * FrcSwerveRobotTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class FrcSwerveRobotTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() {
            val jniDir = java.io.File("build/jni/release")
            if (jniDir.exists()) {
                val libs = listOf(
                    "wpiutil",
                    "wpinet",
                    "ntcore",
                    "wpiHal",
                    "wpiHaljni"
                )
                val ext = if (System.getProperty("os.name").lowercase().contains("win")) ".dll" else ".so"
                for (lib in libs) {
                    val libFile = java.io.File(jniDir, "$lib$ext")
                    if (libFile.exists()) {
                        try {
                            System.load(libFile.absolutePath)
                        } catch (e: Throwable) {
                            System.err.println("FrcSwerveRobotTest: Failed to load native library $lib: ${e.message}")
                        }
                    }
                }
            }
            edu.wpi.first.hal.HAL.initialize(500, 0)
        }
    }

    /**
     * MockSwerveHardwareIO declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    class MockSwerveHardwareIO : SwerveHardwareIO {
        var mockPitch = 0.0
        var mockRoll = 0.0
        var mockCurrents = doubleArrayOf(5.0, 5.0, 5.0, 5.0)
        var mockSpeeds = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        var mockPose = Pose2d(0.0, 0.0, Rotation2d(0.0))
        var seedPoseCalledWith: Pose2d? = null

        override fun refresh() {}

        override fun read(): DriveState {
            return DriveState(
                xVelocityMetersPerSecond = 0.0,
                yVelocityMetersPerSecond = 0.0,
                angularVelocityRadiansPerSecond = 0.0,
                odometryX = mockPose.x,
                odometryY = mockPose.y,
                odometryHeading = mockPose.heading.radians
            )
        }

        override fun write(driveState: DriveState) {}

        override fun getCurrents(out: DoubleArray) {
            System.arraycopy(mockCurrents, 0, out, 0, out.size)
        }

        override val pitchDegrees: Double
            get() = mockPitch

        override val rollDegrees: Double
            get() = mockRoll

        override fun getModuleSpeeds(out: DoubleArray) {
            System.arraycopy(mockSpeeds, 0, out, 0, out.size)
        }

        override fun seedPose(pose: Pose2d) {
            seedPoseCalledWith = pose
        }
    }

    @Test
    /**
     * testBeachedOdometryFreezeAndRecovery declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testBeachedOdometryFreezeAndRecovery() {
        val swerveIO = MockSwerveHardwareIO()
        val mockTelemetry = object : ITelemetry {
            override fun putNumber(key: String, value: Double) {}
            override fun putBoolean(key: String, value: Boolean) {}
            override fun putString(key: String, value: String) {}
            override fun putDoubleArray(key: String, value: DoubleArray) {}
            override fun getNumber(key: String, defaultValue: Double): Double = defaultValue
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
            override fun getString(key: String, defaultValue: String): String = defaultValue
        }
        val robot = FrcSwerveRobot(
            swerveIO = swerveIO,
            isSimulation = false,
            baseTelemetry = mockTelemetry,
            isEnabledProvider = { false },
            robotModeProvider = { "Teleop" }
        )

        // Set initial pose
        robot.store.dispatch(com.areslib.action.RobotAction.PoseUpdate(1.0, 2.0, Math.toRadians(45.0), timestampMs = 0L, isReset = true))
        swerveIO.mockPose = Pose2d(1.0, 2.0, Rotation2d.fromDegrees(45.0))
        robot.update()

        // Verify initial state is registered in store
        assertEquals(1.0, robot.store.state.drive.poseEstimator.estimatedPose.x, 1e-6)
        assertEquals(2.0, robot.store.state.drive.poseEstimator.estimatedPose.y, 1e-6)
        assertEquals(Math.toRadians(45.0), robot.store.state.drive.poseEstimator.estimatedPose.heading.radians, 1e-6)
        assertTrue(robot.store.state.drive.poseEstimateIsExternal)
        assertFalse(robot.isBeached)

        // 1. Move to a new pose under normal (unbeached) conditions
        robot.store.dispatch(com.areslib.action.RobotAction.PoseUpdate(1.5, 2.2, Math.toRadians(50.0), timestampMs = 20L, isReset = true))
        swerveIO.mockPose = Pose2d(1.5, 2.2, Rotation2d.fromDegrees(50.0))
        robot.update()
        assertEquals(1.5, robot.store.state.drive.poseEstimator.estimatedPose.x, 1e-6)
        assertEquals(2.2, robot.store.state.drive.poseEstimator.estimatedPose.y, 1e-6)
        assertEquals(Math.toRadians(50.0), robot.store.state.drive.poseEstimator.estimatedPose.heading.radians, 1e-6)

        // 2. Trigger beached condition (high tilt, high wheel speed, low motor current draw)
        swerveIO.mockPitch = 10.0
        swerveIO.mockSpeeds = doubleArrayOf(2.0, 2.0, 2.0, 2.0)
        swerveIO.mockCurrents = doubleArrayOf(3.0, 3.0, 3.0, 3.0) // < 8.0 A
        assertTrue(robot.isBeached, "Robot should be detected as beached")

        // Now move the underlying hardware odometry (wheels are spinning)
        // We simulate wheels spinning, claiming we traveled to (3.0, 4.0) and heading rotated to 90 degrees.
        swerveIO.mockPose = Pose2d(3.0, 4.0, Rotation2d.fromDegrees(90.0))
        robot.update()

        // Verify that position (X/Y) remains FROZEN at the last known unbeached pose (1.5, 2.2),
        // but the heading continues to update to 90 degrees.
        assertEquals(1.5, robot.store.state.drive.poseEstimator.estimatedPose.x, 1e-6)
        assertEquals(2.2, robot.store.state.drive.poseEstimator.estimatedPose.y, 1e-6)
        assertEquals(Math.toRadians(90.0), robot.store.state.drive.poseEstimator.estimatedPose.heading.radians, 1e-6)
        assertNull(swerveIO.seedPoseCalledWith, "Should not seed pose while beached")

        // 3. Exit beached condition (tilt drops back to normal)
        swerveIO.mockPitch = 0.0
        assertFalse(robot.isBeached, "Robot should no longer be beached")

        // Update robot. On transition exit, it should seed the hardware SwerveDrivetrain back to the frozen Redux EKF pose
        robot.update()

        assertNotNull(swerveIO.seedPoseCalledWith, "Exiting beaching state must call seedPose")
        val seeded = swerveIO.seedPoseCalledWith!!
        assertEquals(1.5, seeded.x, 1e-6)
        assertEquals(2.2, seeded.y, 1e-6)
        assertEquals(Math.toRadians(90.0), seeded.heading.radians, 1e-6)

        robot.close()
    }
}
