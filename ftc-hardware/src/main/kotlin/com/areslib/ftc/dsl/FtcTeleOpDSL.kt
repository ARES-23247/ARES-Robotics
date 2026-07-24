package com.areslib.ftc.dsl

import com.areslib.telemetry.AresGamepad
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.areslib.telemetry.GamepadState
import org.firstinspires.ftc.robotcore.external.Telemetry
import com.areslib.ftc.update

/**
 * Generic DSL Builder class for constructing a TeleOp.
 * @param R The type of the Robot facade.
 */
class FtcTeleOpBuilder<R> {
    internal var onInitBlock: ((R, Telemetry) -> Unit)? = null
    internal var onConfigureBlock: ((R, AresGamepad) -> Unit)? = null
    internal var onLoopBlock: ((R, AresGamepad, Telemetry) -> Unit)? = null

    /**
     * onInit declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun onInit(block: (robot: R, telemetry: Telemetry) -> Unit) {
        onInitBlock = block
    }
    
    /**
     * onConfigure declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun onConfigure(block: (robot: R, driver: AresGamepad) -> Unit) {
        onConfigureBlock = block
    }

    /**
     * onLoop declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun onLoop(block: (robot: R, driver: AresGamepad, telemetry: Telemetry) -> Unit) {
        onLoopBlock = block
    }
}

/**
 * Generic Base class for declarative student OpModes.
 * Manages lifecycle loops, proxy starting, log uploading, Redux loops, and telemetry flushing.
 */
abstract class FtcTeleOpBase<R> : LinearOpMode() {
    /**
     * Define the DSL layout for this OpMode.
     */
    abstract fun define(): FtcTeleOpBuilder<R>

    /**
     * Provide a method to build your team's specific robot wrapper.
     */
    abstract fun buildRobot(): R

    /**
     * Define how your team's robot handles periodic updates during the loop.
     */
    abstract fun updateRobot(robot: R, g1: GamepadState, g2: GamepadState)
    
    /**
     * Define how your team's robot handles shutdown sequences.
     */
    abstract fun closeRobot(robot: R)

    /**
     * runOpMode declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun runOpMode() {
        val builder = define()
        
        try {
            com.areslib.ftc.photon.AresPhotonCore.enable()
        } catch(e: Exception) {
            // Ignore in simulation
        }
        
        // Configure the EKF with the tag positions of the selected field layout
        com.areslib.math.estimation.PoseEstimator.activeTags = com.areslib.math.coordinate.FieldLayouts.getTagsForLayout(com.areslib.math.coordinate.FieldLayout.SQUARE_STANDARD)

        val robot = buildRobot()

        val driver = AresGamepad()
        driver.leftStick.label("Field-centric Translation (X/Y)")
        driver.rightStickX.label("Robot Rotation")
        driver.y.label("Reset Field Centric Pose")
        driver.x.label("Drive to TestWaypoint")

        builder.onConfigureBlock?.invoke(robot, driver)

        try {
            while (opModeInInit() && !Thread.currentThread().isInterrupted) {
                // Initial update pass with empty gamepad state
                updateRobot(robot, GamepadState(), GamepadState())
                builder.onInitBlock?.invoke(robot, telemetry)
                telemetry.update()
                sleep(20)
            }
            if (isStopRequested || Thread.currentThread().isInterrupted) return

            com.areslib.telemetry.RobotStatusTracker.activeOpMode = "TeleOp"

            // Set initial pose: restore from Autonomous if valid, otherwise use alliance default
            try {
                val baseField = robot!!.javaClass.getDeclaredField("base")
                baseField.isAccessible = true
                val baseRobot = baseField.get(robot) as? com.areslib.ftc.FtcBaseRobot
                if (baseRobot != null) {
                    if (com.areslib.util.PoseStorage.hasValidPose) {
                        baseRobot.resetPose(com.areslib.util.PoseStorage.currentPose)
                    } else {
                        baseRobot.resetPoseForAlliance()
                    }
                }
            } catch (_: Exception) {
                (robot as? com.areslib.ftc.FtcBaseRobot)?.let { baseRobot ->
                    if (com.areslib.util.PoseStorage.hasValidPose) {
                        baseRobot.resetPose(com.areslib.util.PoseStorage.currentPose)
                    } else {
                        baseRobot.resetPoseForAlliance()
                    }
                }
            }

            // NOTE: Hardware specific init code (like vision tracker flags) should be handled by the team's buildRobot/wrapper logic
            com.areslib.ftc.telemetry.LimelightProxyAutoStart.stop()
            
            val g1State = GamepadState()
            val g2State = GamepadState()
            
            while (opModeIsActive() && !Thread.currentThread().isInterrupted) {
                g1State.update(gamepad1)
                g2State.update(gamepad2)
                
                try {
                    val webVx = com.areslib.telemetry.SimInputBridge.webVx
                    val webVy = com.areslib.telemetry.SimInputBridge.webVy
                    val webOmega = com.areslib.telemetry.SimInputBridge.webOmega
                    
                    if (kotlin.math.abs(webVx) > 0.01 || kotlin.math.abs(webVy) > 0.01 || kotlin.math.abs(webOmega) > 0.01) {
                        if (kotlin.math.abs(g1State.leftStickY) < 0.05f) {
                            g1State.leftStickY = (-webVx / 4.0).coerceIn(-1.0, 1.0).toFloat()
                        }
                        if (kotlin.math.abs(g1State.leftStickX) < 0.05f) {
                            g1State.leftStickX = (-webVy / 4.0).coerceIn(-1.0, 1.0).toFloat()
                        }
                        if (kotlin.math.abs(g1State.rightStickX) < 0.05f) {
                            g1State.rightStickX = (-webOmega / 2.0).coerceIn(-1.0, 1.0).toFloat()
                        }
                    }
                } catch (_: Throwable) {}

                
                driver.update(g1State)
                
                // Allow the user DSL loop to dispatch inputs
                builder.onLoopBlock?.invoke(robot, driver, telemetry)
                
                // Update physical hardware and sensors via the provided robot interface
                updateRobot(robot, g1State, g2State)
            }
        } finally {
            closeRobot(robot)
            try {
                com.areslib.ftc.photon.AresPhotonCore.disable()
            } catch (e: Exception) {}
        }
    }
}

