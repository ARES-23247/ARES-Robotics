package org.firstinspires.ftc.teamcode

import com.areslib.action.RobotAction
import com.areslib.ftc.FtcBaseRobot
import com.areslib.ftc.FtcMecanumRobot
import com.areslib.ftc.FtcTeleopDriveFrame
import com.areslib.networktables.NT4Instance
import com.areslib.sim.model.MecanumRobotDouble
import com.areslib.sim.opmode.SimOpModeRunner
import com.areslib.state.Alliance
import com.areslib.util.RobotClock
import org.firstinspires.ftc.teamcode.opmodes.ARESMecanumTeleOp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Validates the complete zero-code robot lifecycle:
 * 1. OpMode & Mecanum Robot Double instantiation with full hardware registry
 * 2. Redux store initialization, alliance configuration, and pose seeding
 * 3. 50Hz physics loop stepping with encoder integration and Pinpoint simulation
 * 4. TeleOp gamepad driving (translation and CCW rotation)
 * 5. Closed-loop EKF pose estimator tracking physical movement
 * 6. Clean lifecycle teardown
 */
class ZeroCodeLifecycleEndToEndTest {

    @After
    fun cleanUp() {
        NT4Instance.defaultInstance.closeServer()
        RobotClock.useSystemTime()
    }

    @Test
    fun `test complete zero-code FTC mecanum robot lifecycle in simulation`() {
        println("[E2E Lifecycle] Stage 1: Initializing virtual robot hardware and clock...")
        RobotClock.useMockTime(1_000L)
        val robotDouble = MecanumRobotDouble()

        // Instantiate and initialize the zero-code teleop opmode
        println("[E2E Lifecycle] Stage 2: Initializing ARESMecanumTeleOp OpMode...")
        val rawOpMode = ARESMecanumTeleOp()
        val opModeLifecycle = requireNotNull(
            SimOpModeRunner.createOpModeInstance(rawOpMode, null),
            { "Failed to create ARESMecanumTeleOp instance" }
        )

        try {
            opModeLifecycle.initialize(robotDouble.hardwareMap)
            val robot = requireNotNull(FtcBaseRobot.activeInstance as? FtcMecanumRobot) {
                "FtcMecanumRobot must be active in FtcBaseRobot.activeInstance"
            }

            // Set Red Alliance and seed initial pose at (0, 0, 0)
            robot.store.dispatch(RobotAction.SetAlliance(Alliance.RED))
            robot.store.dispatch(
                RobotAction.PoseUpdate(
                    xMeters = 0.0,
                    yMeters = 0.0,
                    headingRadians = 0.0,
                    timestampMs = RobotClock.currentTimeMillis(),
                    isReset = true
                )
            )

            var trueX = 0.0
            var trueY = 0.0
            var trueHeading = 0.0
            val dt = 0.02 // 50Hz

            robotDouble.updateSensors(dt, 0.0, 0.0, 0.0, trueX, trueY, trueHeading)
            opModeLifecycle.tick()
            assertEquals(Alliance.RED, robot.store.state.drive.alliance)

            val initialEstimatedPose = robot.store.state.drive.poseEstimator.estimatedPose
            println("[E2E Lifecycle] Initial Estimated Pose: ($initialEstimatedPose)")

            // Start OpMode
            println("[E2E Lifecycle] Stage 3: Starting OpMode...")
            opModeLifecycle.start()

            // Drive forward for 50 ticks (1.0 second at 50Hz / 20ms step)
            println("[E2E Lifecycle] Stage 4: Driving forward (gamepad1.left_stick_y = -1.0)...")
            rawOpMode.gamepad1.left_stick_y = -1.0f
            rawOpMode.gamepad1.left_stick_x = 0.0f
            rawOpMode.gamepad1.right_stick_x = 0.0f

            repeat(50) {
                RobotClock.setMockTimeMs(RobotClock.currentTimeMillis() + 20L)
                opModeLifecycle.tick()

                val driveCmd = robot.store.state.drive
                val vx = driveCmd.xVelocityMetersPerSecond
                val vy = driveCmd.yVelocityMetersPerSecond
                val omega = driveCmd.angularVelocityRadiansPerSecond

                trueX += (vx * cos(trueHeading) - vy * sin(trueHeading)) * dt
                trueY += (vx * sin(trueHeading) + vy * cos(trueHeading)) * dt
                trueHeading += omega * dt

                robotDouble.updateSensors(dt, vx, vy, omega, trueX, trueY, trueHeading)
            }

            val forwardPose = robot.store.state.drive.poseEstimator.estimatedPose
            println("[E2E Lifecycle] True Pose after forward drive: ($trueX, $trueY, $trueHeading)")
            println("[E2E Lifecycle] EKF Pose after forward drive: ($forwardPose)")

            val trueDisplacement = hypot(trueX, trueY)
            val ekfDisplacement = hypot(forwardPose.x - initialEstimatedPose.x, forwardPose.y - initialEstimatedPose.y)
            println("[E2E Lifecycle] True Displacement: ${trueDisplacement}m, EKF Displacement: ${ekfDisplacement}m")

            assertTrue(
                "True robot position should have moved by at least 0.5m (actual: ${trueDisplacement}m)",
                trueDisplacement > 0.5
            )
            assertTrue(
                "EKF estimated position should have moved by at least 0.5m (actual: ${ekfDisplacement}m)",
                ekfDisplacement > 0.5
            )

            // Drive rotation CCW for 50 ticks (1.0 second at 50Hz)
            println("[E2E Lifecycle] Stage 5: Commanding CCW rotation (gamepad1.right_stick_x = -1.0)...")
            rawOpMode.gamepad1.left_stick_y = 0.0f
            rawOpMode.gamepad1.left_stick_x = 0.0f
            rawOpMode.gamepad1.right_stick_x = -1.0f

            repeat(50) {
                RobotClock.setMockTimeMs(RobotClock.currentTimeMillis() + 20L)
                opModeLifecycle.tick()

                val driveCmd = robot.store.state.drive
                val vx = driveCmd.xVelocityMetersPerSecond
                val vy = driveCmd.yVelocityMetersPerSecond
                val omega = driveCmd.angularVelocityRadiansPerSecond

                trueX += (vx * cos(trueHeading) - vy * sin(trueHeading)) * dt
                trueY += (vx * sin(trueHeading) + vy * cos(trueHeading)) * dt
                trueHeading += omega * dt

                robotDouble.updateSensors(dt, vx, vy, omega, trueX, trueY, trueHeading)
            }

            val rotatedPose = robot.store.state.drive.poseEstimator.estimatedPose
            println("[E2E Lifecycle] True Pose after rotation: ($trueX, $trueY, $trueHeading)")
            println("[E2E Lifecycle] EKF Pose after rotation: ($rotatedPose)")
            assertTrue(
                "True heading should have rotated CCW (actual: $trueHeading rad)",
                trueHeading > 0.3
            )

            // Stop motion
            println("[E2E Lifecycle] Stage 6: Stopping motion and validating zero velocity command...")
            rawOpMode.gamepad1.left_stick_y = 0.0f
            rawOpMode.gamepad1.left_stick_x = 0.0f
            rawOpMode.gamepad1.right_stick_x = 0.0f
            opModeLifecycle.tick()

            val stoppedDriveState = robot.store.state.drive
            assertEquals(0.0, stoppedDriveState.xVelocityMetersPerSecond, 1e-4)
            assertEquals(0.0, stoppedDriveState.yVelocityMetersPerSecond, 1e-4)
            assertEquals(0.0, stoppedDriveState.angularVelocityRadiansPerSecond, 1e-4)

            println("[E2E Lifecycle] Complete FTC Mecanum robot lifecycle validated successfully!")

        } finally {
            println("[E2E Lifecycle] Stage 7: Clean teardown...")
            opModeLifecycle.stop()
        }
    }
}
