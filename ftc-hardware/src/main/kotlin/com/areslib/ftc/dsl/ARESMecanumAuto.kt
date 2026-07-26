package com.areslib.ftc.dsl

import com.qualcomm.robotcore.eventloop.opmode.Autonomous
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.areslib.util.RobotClock
import com.areslib.ftc.FtcMecanumRobot

/**
 * A simplified, clean autonomous OpMode base class.
 * Coordinates EKF fused state updating and path-following with high-level facade calls.
 * @param R The type of the Robot facade.
 */
abstract class FtcMecanumAutoBase<R> : LinearOpMode() {

    open val pathName: String = "Example Path"

    companion object {
        /** Threshold above which we log a loop overrun warning (50 Hz = 20ms) */
        private const val OVERRUN_THRESHOLD_MS = 30L
    }
    /**
     * buildRobot declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    abstract fun buildRobot(): R
    /**
     * getMecanumRobot declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    abstract fun getMecanumRobot(robot: R): FtcMecanumRobot
    /**
     * updateRobot declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    abstract fun updateRobot(robot: R)
    /**
     * closeRobot declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    abstract fun closeRobot(robot: R)

    /**
     * runOpMode declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun runOpMode() {
        // --- 1. Initialization ---
        val wrapper = buildRobot()
        val robot = getMecanumRobot(wrapper)

        // Calibrate static friction feedforward (kS) to overcome physical drivetrain deadband
        robot.mecanumIO.kS = if (robot.driveFeedforward.kS > 0.0) robot.driveFeedforward.kS else 0.05

        // Parse trajectory spline path using the new declarative AutoBuilder
        var autoTask: com.areslib.sequencer.Task? = null
        var pathLoadError: String? = null
        try {
            val jsonString = com.areslib.pathing.DynamicPathLoader.loadAutoJsonString(pathName)
            val alliance = robot.store.state.drive.alliance
            autoTask = robot.autoBuilder.buildAuto(pathName, com.areslib.util.RobotClock.currentTimeMillis(), alliance)
            
            // Extract starting pose and seed EKF
            var startPose = com.areslib.pathing.PathPlannerAutoParser.getStartingPose(jsonString)
            if (startPose == null) {
                val firstPath = com.areslib.pathing.PathPlannerAutoParser.getFirstPathName(jsonString)
                if (firstPath != null) {
                    val path = com.areslib.pathing.DynamicPathLoader.loadPath(firstPath)
                    val wp = path.points.firstOrNull()
                    if (wp != null) {
                        startPose = com.areslib.math.geometry.Pose2d(wp.pose.x, wp.pose.y, wp.pose.heading)
                    }
                }
            }

            if (startPose != null) {
                var x = startPose.x
                var y = startPose.y
                var heading = startPose.heading.radians

                if (robot.store.state.drive.alliance == com.areslib.state.Alliance.RED) {
                    // Mirror pose for FTC field (0,0 center)
                    x = -x
                    y = -y
                    heading = heading + Math.PI
                }

                // 1. Hard-reset the OpMode EKF
                robot.store.dispatch(
                    com.areslib.action.RobotAction.PoseUpdate(
                        xMeters = x,
                        yMeters = y,
                        headingRadians = heading,
                        isReset = true,
                        timestampMs = com.areslib.util.RobotClock.currentTimeMillis()
                    )
                )

                // 2. Reset Pinpoint odometry hardware to match the EKF seed.
                // Without this, the Pinpoint reports stale position from the
                // previous OpMode and the EKF snaps to the wrong location.
                robot.resetPose(
                    com.areslib.math.geometry.Pose2d(
                        x, y,
                        com.areslib.math.geometry.Rotation2d(heading)
                    )
                )
            }
        } catch (e: Exception) {
            pathLoadError = e.message ?: "Unknown error"
        }

        if (pathLoadError != null || autoTask == null) {
            telemetry.addData("Error", "Failed to load dynamic path: $pathLoadError")
            telemetry.addData("Status", "Initialization Failed!")
            telemetry.update()
        } else {
            telemetry.addData("Status", "Initialized. Auto Task built successfully.")
            telemetry.update()
        }

        try {
            waitForStart()
            com.areslib.telemetry.RobotStatusTracker.activeOpMode = "Auto"

            if (autoTask == null) {
                telemetry.addData("CRASH", "Aborting: Path not loaded. Error: $pathLoadError")
                telemetry.update()
                sleep(2000L)
                return
            }

            // --- 2. Autonomous Loop ---
            val executor = com.areslib.sequencer.TaskExecutor()
            executor.addTask(autoTask)
            
            var loopCount = 0L
            var overrunCount = 0L

            while (opModeIsActive() && !Thread.currentThread().isInterrupted) {
                val loopStartMs = com.areslib.util.RobotClock.currentTimeMillis()

                try {
                    // A. Polls pinpoint/limelight, updates Redux EKF, and runs loop under the hood
                    updateRobot(wrapper)

                    // B. Evaluate sequence hierarchy
                    if (executor.size > 0) {
                        val actions = executor.update(robot.store.state, loopStartMs)
                        actions.forEach { robot.store.dispatch(it) }
                    } else {
                        robot.mecanumIO.setMotorPowers(0.0, 0.0, 0.0, 0.0)
                    }
                } catch (e: Exception) {
                    // Per-iteration failsafe: disable outputs if a single iteration fails
                    try {
                        robot.mecanumIO.setMotorPowers(0.0, 0.0, 0.0, 0.0)
                    } catch (_: Exception) { /* best-effort */ }
                    telemetry.addData("LOOP_ERROR", e.message ?: "Unknown error")
                }

                // Loop time watchdog
                val loopElapsedMs = com.areslib.util.RobotClock.currentTimeMillis() - loopStartMs
                loopCount++
                if (loopElapsedMs > OVERRUN_THRESHOLD_MS) {
                    overrunCount++
                }

                telemetry.addData("EKF Pose X", robot.drive.odometryPose.x)
                telemetry.addData("EKF Pose Y", robot.drive.odometryPose.y)
                telemetry.addData("Loop ms", loopElapsedMs)
                telemetry.addData("Overruns", "$overrunCount / $loopCount")
                telemetry.update()
            }

            // Clean stop at target & persist pose for TeleOp
            executor.clear()
            robot.mecanumIO.setMotorPowers(0.0, 0.0, 0.0, 0.0)
            com.areslib.util.PoseStorage.currentPose = robot.drive.odometryPose
            com.areslib.util.PoseStorage.hasValidPose = true
            
        } catch (e: Exception) {
            // Top-level failsafe: disable all outputs and log
            try {
                robot.mecanumIO.setMotorPowers(0.0, 0.0, 0.0, 0.0)
            } catch (_: Exception) { /* best-effort shutoff */ }
            telemetry.addData("CRASH", e.message ?: "Unknown error")
            telemetry.update()
        } finally {
            closeRobot(wrapper)
        }
    }
}

