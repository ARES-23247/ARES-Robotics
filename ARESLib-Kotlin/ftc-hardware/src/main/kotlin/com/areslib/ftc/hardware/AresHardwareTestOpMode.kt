package com.areslib.ftc.hardware

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.qualcomm.robotcore.eventloop.opmode.TeleOp
import com.areslib.ftc.FtcTestbedRobot
import com.areslib.ftc.update
import com.areslib.telemetry.AresGamepad

/**
 * Standard FTC TeleOp OpMode demonstrating hardware integration and subsystem facade usage in ARESLib-Kotlin.
 *
 * Demonstrates clean encapsulation where low-level Redux actions, EKF odometry updates,
 * and hardware I/O polling are automatically driven by high-frequency facade methods.
 *
 * ### Performance & Loop Watchdog:
 * - Baseline target loop rate: 50Hz ($20\text{ms}$ delta time step).
 * - Loop overrun warning threshold: 30ms.
 * - Hardware safety: Per-iteration failsafe catches runtime exceptions and cuts motor power gracefully.
 *
 * @see FtcMecanumRobot
 * @see AresGamepad
 */
@TeleOp(name = "ARES: Hardware Integration Test", group = "ARES")
class AresHardwareTestOpMode : LinearOpMode() {

    companion object {
        /** Target loop period in milliseconds (50Hz = 20ms). */
        private const val TARGET_LOOP_MS = 20L
        /** Threshold duration above which loop overrun warnings are logged. */
        private const val OVERRUN_THRESHOLD_MS = 30L
    }

    /**
     * Main entry point for FTC LinearOpMode execution.
     *
     * Initializes the [FtcMecanumRobot] container, sets up gamepad control bindings, and executes
     * the teleoperated control loop.
     */
    override fun runOpMode() {
        telemetry.addData("Status", "Initializing Robot Facade...")
        telemetry.update()

        // Centrally initialize the robot container and all subsystem facades
        val robot = com.areslib.ftc.FtcMecanumRobot(hardwareMap, pinpointName = "pinpoint")
        
        // Define declarative bindings
        val driver = AresGamepad()
        driver.leftStick.label("Robot Translation (X/Y)")
        driver.rightStickX.label("Robot Rotation")
        

        telemetry.addData("Status", "Initialized. Ready for match!")
        telemetry.update()
        
        waitForStart()

        val g1State = com.areslib.telemetry.GamepadState()
        var loopCount = 0L
        var overrunCount = 0L

        try {
            while (opModeIsActive()) {
                val loopStartMs = com.areslib.util.RobotClock.currentTimeMillis()

                // 1. Coordinates sensor reading, Redux updates, and motor command execution in the background
                robot.update()
                g1State.update(gamepad1)
                driver.update(g1State)

                val webFrame = com.areslib.telemetry.SimInputBridge.currentFrame()
                val webX = webFrame.vx
                val webY = webFrame.vy
                val webRot = webFrame.omega

                val driveX = if (kotlin.math.abs(g1State.leftStickY) > 0.05f) {
                    -g1State.leftStickY.toDouble()
                } else {
                    (webX / robot.mecanumDrive.maxSpeedMps).coerceIn(-1.0, 1.0)
                }
                val driveY = if (kotlin.math.abs(g1State.leftStickX) > 0.05f) {
                    -g1State.leftStickX.toDouble()
                } else {
                    (webY / robot.mecanumDrive.maxSpeedMps).coerceIn(-1.0, 1.0)
                }
                val driveRot = if (kotlin.math.abs(g1State.rightStickX) > 0.05f) {
                    g1State.rightStickX.toDouble()
                } else {
                    (webRot / robot.mecanumDrive.maxAngularSpeedRps).coerceIn(-1.0, 1.0)
                }

                robot.mecanumDrive.driveFieldRelativeNormalized(driveX, driveY, driveRot)


                // 4. Loop time watchdog
                val loopElapsedMs = com.areslib.util.RobotClock.currentTimeMillis() - loopStartMs
                loopCount++
                if (loopElapsedMs > OVERRUN_THRESHOLD_MS) {
                    overrunCount++
                }

                // 5. Stream automatically processed telemetry values
                telemetry.addData("Odometry X Pose", robot.drive.odometryX)
                telemetry.addData("Loop ms", loopElapsedMs)
                telemetry.addData("Overruns", "$overrunCount / $loopCount")
                telemetry.update()
            }
        } catch (e: Exception) {
            // Failsafe: disable all outputs and log instead of crashing
            try {
                robot.mecanumDrive.driveRobotRelativeNormalized(0.0, 0.0, 0.0)
            } catch (_: Exception) { /* best-effort shutoff */ }
            telemetry.addData("CRASH", e.message ?: "Unknown error")
            telemetry.update()
        } finally {
            robot.close()
        }
    }
}
