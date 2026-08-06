package com.areslib.ftc.dsl

import com.areslib.telemetry.AresGamepad
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.areslib.telemetry.GamepadState
import org.firstinspires.ftc.robotcore.external.Telemetry
import com.areslib.ftc.update

/**
 * Generic DSL Builder class for configuring declarative FTC TeleOp OpModes.
 *
 * Configures initialization callbacks ([onInit]), driver gamepad mappings ([onConfigure]), and main teleoperated loops ([onLoop]).
 *
 * @param R Type of team robot facade class.
 *
 * @see FtcTeleOpBase
 */
class FtcTeleOpBuilder<R> {
    internal var onInitBlock: ((R, Telemetry) -> Unit)? = null
    internal var onConfigureBlock: ((R, AresGamepad) -> Unit)? = null
    internal var onLoopBlock: ((R, AresGamepad, Telemetry) -> Unit)? = null

    /**
     * Registers a callback executed repeatedly while the OpMode is in the `"Init"` state.
     *
     * @param block Initialization logic lambda receiving the team robot wrapper [R] and FTC [Telemetry].
     */
    fun onInit(block: (robot: R, telemetry: Telemetry) -> Unit) {
        onInitBlock = block
    }
    
    /**
     * Registers a callback executed once during initialization to configure Driver Station controls and button labels.
     *
     * @param block Configuration logic lambda receiving team robot wrapper [R] and driver [AresGamepad].
     */
    fun onConfigure(block: (robot: R, driver: AresGamepad) -> Unit) {
        onConfigureBlock = block
    }

    /**
     * Registers the main TeleOp execution loop callback executed every frame (50Hz–100Hz frequency).
     *
     * @param block Loop logic lambda receiving team robot wrapper [R], driver [AresGamepad], and FTC [Telemetry].
     */
    fun onLoop(block: (robot: R, driver: AresGamepad, telemetry: Telemetry) -> Unit) {
        onLoopBlock = block
    }
}

/**
 * Generic base class for student-facing declarative FTC TeleOp OpModes.
 *
 * Manages OpMode lifecycle transitions, proxy background servers, pose restoration from Autonomous ([com.areslib.util.PoseStorage]),
 * web dashboard simulation input bridges ([com.areslib.telemetry.SimInputBridge]), gamepad polling, and safe hardware shutdowns.
 *
 * ### TeleOp Lifecycle Flow:
 * 1. **Init Phase**: Evaluates [FtcTeleOpBuilder.onInit] blocks and refreshes EKF state with zero gamepad input.
 * 2. **Start Phase**: Restores previous autonomous pose from [com.areslib.util.PoseStorage] or sets alliance default starting pose.
 * 3. **Active Loop Phase**:
 *    - Updates [GamepadState] snapshots for Driver 1 and Driver 2 in-place (zero-GC).
 *    - Bridges web simulation inputs if active.
 *    - Invokes [FtcTeleOpBuilder.onLoop] DSL blocks.
 *    - Updates physical robot actuators via [updateRobot].
 * 4. **Cleanup Phase**: Safely halts motor outputs and releases resources via [closeRobot].
 *
 * @param R Type of team robot facade class.
 *
 * @see LinearOpMode
 * @see FtcTeleOpBuilder
 * @see GamepadState
 */
abstract class FtcTeleOpBase<R> : LinearOpMode() {
    /**
     * Constructs the DSL configuration layout for this TeleOp OpMode.
     *
     * @return [FtcTeleOpBuilder] containing registered `onInit`, `onConfigure`, and `onLoop` blocks.
     */
    abstract fun define(): FtcTeleOpBuilder<R>

    /**
     * Factory hook constructing the team robot facade instance.
     *
     * @return Initialized team robot wrapper instance [R].
     */
    abstract fun buildRobot(): R

    /**
     * Periodic update hook executing hardware sensor reads and subsystem updates for the team robot facade.
     *
     * @param robot Team robot wrapper instance.
     * @param g1 In-place updated Driver 1 [GamepadState] snapshot.
     * @param g2 In-place updated Driver 2 [GamepadState] snapshot.
     */
    abstract fun updateRobot(robot: R, g1: GamepadState, g2: GamepadState)
    
    /**
     * Shutdown hook releasing active hardware resources upon TeleOp completion.
     *
     * @param robot Team robot wrapper instance.
     */
    abstract fun closeRobot(robot: R)

    /**
     * Main execution entry point for FTC LinearOpMode lifecycle.
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


