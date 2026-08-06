package com.areslib.ftc.dsl

import com.qualcomm.robotcore.eventloop.opmode.Autonomous
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.areslib.util.RobotClock
import com.areslib.ftc.FtcMecanumRobot

/**
 * Abstract foundational base class for declarative FTC Autonomous OpModes.
 *
 * Coordinates EKF pose initialization, trajectory loading via [com.areslib.pathing.DynamicPathLoader],
 * PathPlanner JSON auto parsing via [com.areslib.pathing.PathPlannerAutoParser], alliance color mirroring,
 * high-frequency task sequence execution, and loop-time watchdog profiling.
 *
 * ### Autonomous Lifecycle Flow:
 * 1. **Initialization (`runOpMode`)**:
 *    - Instantiates team robot facade via [buildRobot].
 *    - Parses named trajectory JSON (`pathName`).
 *    - Extracts trajectory start pose, applies alliance mirroring if needed, and hard-resets EKF + Pinpoint hardware encoders.
 * 2. **Active Execution**:
 *    - Polls physical hardware sensors via [updateRobot].
 *    - Evaluates active task sequence via [com.areslib.sequencer.TaskExecutor].
 *    - Dispatches resulting [com.areslib.action.RobotAction] actions into Redux state store.
 *    - Measures loop execution time and logs overrun warnings (>30ms).
 * 3. **Termination**:
 *    - Stops drivetrain motor outputs.
 *    - Persists final autonomous pose into [com.areslib.util.PoseStorage] for seamless transition into TeleOp.
 *    - Releases hardware resources via [closeRobot].
 *
 * ### Physical Units & Coordinates:
 * - Field Coordinates: Meters ($m$), $+X$ forward, $+Y$ left.
 * - Heading: Radians ($rad$), **CCW-positive** standard ($0 = +X$, $\pi/2 = +Y$).
 * - Execution Frequency: Target 50Hz ($20\text{ms}$ loop pacing), overrun alert threshold set to 30ms.
 *
 * @param R Type of team robot facade class.
 *
 * @see LinearOpMode
 * @see FtcMecanumRobot
 * @see com.areslib.sequencer.TaskExecutor
 */
abstract class FtcMecanumAutoBase<R> : LinearOpMode() {

    /** Name of the autonomous trajectory file to load from assets or flash storage. Defaults to `"Example Path"`. */
    open val pathName: String = "Example Path"

    companion object {
        /** Threshold duration above which loop overrun warnings are recorded (50Hz = 20ms baseline). */
        private const val OVERRUN_THRESHOLD_MS = 30L
    }

    /**
     * Factory hook constructing the team robot facade instance.
     *
     * @return Initialized robot wrapper instance [R].
     */
    abstract fun buildRobot(): R

    /**
     * Extracts the core [FtcMecanumRobot] reference from the team robot wrapper [robot].
     *
     * @param robot Team robot wrapper instance.
     * @return Core [FtcMecanumRobot] base instance.
     */
    abstract fun getMecanumRobot(robot: R): FtcMecanumRobot

    /**
     * Periodic update hook executing hardware sensor reads and subsystem updates for the team robot facade.
     *
     * @param robot Team robot wrapper instance.
     */
    abstract fun updateRobot(robot: R)

    /**
     * Shutdown hook releasing active hardware resources upon Autonomous completion.
     *
     * @param robot Team robot wrapper instance.
     */
    abstract fun closeRobot(robot: R)

    /**
     * Main execution entry point for FTC LinearOpMode lifecycle.
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
            
            // Extract starting pose directly from the first path trajectory (with alliance mirroring) to guarantee 100% pose alignment
            var startPose: com.areslib.math.geometry.Pose2d? = null
            val firstPathName = com.areslib.pathing.PathPlannerAutoParser.getFirstPathName(jsonString)
            if (firstPathName != null) {
                val path = com.areslib.pathing.DynamicPathLoader.loadPath(firstPathName)
                val mirroredPath = com.areslib.math.coordinate.AllianceMirroring.mirror(
                    path, robot.store.state.drive.alliance,
                    com.areslib.math.coordinate.FieldSymmetry.MIRRORED
                )
                val wp = mirroredPath.points.firstOrNull()
                if (wp != null) {
                    startPose = com.areslib.math.geometry.Pose2d(wp.pose.x, wp.pose.y, wp.pose.heading)
                }
            }
            if (startPose == null) {
                val rawPose = com.areslib.pathing.PathPlannerAutoParser.getStartingPose(jsonString)
                if (rawPose != null) {
                    startPose = com.areslib.math.coordinate.AllianceMirroring.mirror(
                        rawPose, robot.store.state.drive.alliance,
                        com.areslib.math.coordinate.FieldSymmetry.MIRRORED
                    )
                }
            }

            if (startPose != null) {
                val x = startPose.x
                val y = startPose.y
                val heading = startPose.heading.radians

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

                // 2. Hard-reset Pinpoint odometry hardware to match the EKF seed.
                //    Uses resetHardware=true to zero the Pinpoint's internal state,
                //    preventing stale accumulated position from previous OpModes.
                robot.resetPose(
                    com.areslib.math.geometry.Pose2d(
                        x, y,
                        com.areslib.math.geometry.Rotation2d(heading)
                    ),
                    resetHardware = true
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
            robot.visionTracker.hasInitializedPoseWithVision = true

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


