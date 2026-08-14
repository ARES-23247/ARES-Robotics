package com.areslib.ftc.dsl

import com.areslib.ftc.FtcBaseRobot
import com.areslib.ftc.update
import com.areslib.telemetry.AresGamepad
import com.areslib.telemetry.GamepadState
import com.areslib.telemetry.RobotStatusTracker
import com.areslib.telemetry.SimInputBridge
import com.areslib.util.PoseStorage
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import org.firstinspires.ftc.robotcore.external.Telemetry
import kotlin.math.abs

/** Prevents lifecycle declarations from accidentally resolving against the wrong nested receiver. */
@DslMarker
annotation class AresOpModeDsl

/**
 * Stable receiver exposed to every TeleOp lifecycle block.
 *
 * [driver] maps Driver Station gamepad 1 and [operator] maps gamepad 2. The same receiver instance is
 * reused throughout the OpMode; callbacks should keep their own small state in the surrounding class
 * or DSL block when needed.
 */
@AresOpModeDsl
class FtcTeleOpContext<R> internal constructor(
    val robot: R,
    val driver: AresGamepad,
    val operator: AresGamepad,
    val telemetry: Telemetry
)

/**
 * Declarative FTC TeleOp definition with lifecycle names that match when each block executes.
 *
 * A typical mode needs [controls] and [everyLoop]. [duringInit] and [onStart] are optional. Registering
 * the same phase twice is a configuration error instead of silently discarding the first block.
 */
@AresOpModeDsl
class FtcTeleOpBuilder<R> {
    internal var setupBlock: (FtcTeleOpContext<R>.() -> Unit)? = null
        private set
    internal var controlsBlock: (FtcTeleOpContext<R>.() -> Unit)? = null
        private set
    internal var duringInitBlock: (FtcTeleOpContext<R>.() -> Unit)? = null
        private set
    internal var onStartBlock: (FtcTeleOpContext<R>.() -> Unit)? = null
        private set
    internal var everyLoopBlock: (FtcTeleOpContext<R>.() -> Unit)? = null
        private set

    /** Runs once immediately after the robot is built. Configure initial robot state here. */
    fun setup(block: FtcTeleOpContext<R>.() -> Unit) {
        check(setupBlock == null) { "TeleOp setup { } may only be declared once" }
        setupBlock = block
    }

    /** Runs once after [setup]. Bind driver and operator buttons here. */
    fun controls(block: FtcTeleOpContext<R>.() -> Unit) {
        check(controlsBlock == null) { "TeleOp controls { } may only be declared once" }
        controlsBlock = block
    }

    /** Runs repeatedly while the Driver Station shows INIT. Keep this non-blocking. */
    fun duringInit(block: FtcTeleOpContext<R>.() -> Unit) {
        check(duringInitBlock == null) { "TeleOp duringInit { } may only be declared once" }
        duringInitBlock = block
    }

    /** Runs exactly once after pose restoration and immediately before the active loop. */
    fun onStart(block: FtcTeleOpContext<R>.() -> Unit) {
        check(onStartBlock == null) { "TeleOp onStart { } may only be declared once" }
        onStartBlock = block
    }

    /** Runs once per active robot loop and is required for a usable TeleOp. */
    fun everyLoop(block: FtcTeleOpContext<R>.() -> Unit) {
        check(everyLoopBlock == null) { "TeleOp everyLoop { } may only be declared once" }
        everyLoopBlock = block
    }

    internal fun validate() {
        require(everyLoopBlock != null) {
            "TeleOp definition is missing everyLoop { ... }; add the robot's periodic driver behavior there"
        }
    }
}

/** Creates and validates a student-facing TeleOp definition. */
fun <R> teleOp(block: FtcTeleOpBuilder<R>.() -> Unit): FtcTeleOpBuilder<R> =
    FtcTeleOpBuilder<R>().apply(block).also { it.validate() }

/**
 * Generic lifecycle runner for declarative FTC TeleOps.
 *
 * Hardware is refreshed during INIT and the active phase. Pose restoration is typed through
 * [getBaseRobot], so wrappers never require reflection. Cleanup always disables Photon after the
 * team robot has been closed.
 */
abstract class FtcTeleOpBase<R> : OpMode() {
    abstract fun define(): FtcTeleOpBuilder<R>
    abstract fun buildRobot(): R
    abstract fun getBaseRobot(robot: R): FtcBaseRobot?
    abstract fun updateRobot(robot: R, g1: GamepadState, g2: GamepadState)
    abstract fun closeRobot(robot: R)

    /**
     * Runs generated project controls only during an active TeleOp frame, after hand-authored
     * callbacks and before [updateRobot] writes outputs. Season shells may override this hook;
     * INIT never invokes it, so controller input cannot arm a mechanism before Start.
     */
    open fun updateProjectControls(robot: R, g1: GamepadState, g2: GamepadState) = Unit

    /** Cancels generated bindings/tasks before the robot is closed. */
    open fun cancelProjectControls(robot: R) = Unit

    private var definition: FtcTeleOpBuilder<R>? = null
    private var robot: R? = null
    private var context: FtcTeleOpContext<R>? = null
    private val driver = AresGamepad()
    private val operator = AresGamepad()
    private val g1State = GamepadState()
    private val g2State = GamepadState()
    private var closed = false

    /** Builds the robot and evaluates the one-time DSL setup phases. */
    final override fun init() {
        val builtDefinition = define().also { it.validate() }
        com.areslib.math.estimation.PoseEstimator.activeTags =
            com.areslib.state.RobotFieldManager.activeConfig.apriltags.associate { tag ->
                tag.id to com.areslib.math.geometry.Pose3d(
                    com.areslib.math.geometry.Translation3d(tag.x, tag.y, tag.z),
                    com.areslib.math.geometry.Rotation3d(0.0, 0.0, Math.toRadians(tag.yaw))
                )
            }
        val builtRobot = buildRobot()
        val builtContext = FtcTeleOpContext(builtRobot, driver, operator, telemetry)
        definition = builtDefinition
        robot = builtRobot
        context = builtContext

        builtDefinition.setupBlock?.invoke(builtContext)
        labelDefaultDriverControls(driver)
        builtDefinition.controlsBlock?.invoke(builtContext)
    }

    /** Refreshes hardware and the optional INIT callback once per SDK init frame. */
    final override fun init_loop() {
        val activeRobot = robot ?: return
        refreshGamepadStates()
        driver.prime(g1State)
        operator.prime(g2State)
        updateRobot(activeRobot, g1State, g2State)
        context?.let { definition?.duringInitBlock?.invoke(it) }
    }

    /** Restores autonomous pose and invokes the one-time start callback. */
    final override fun start() {
        val activeRobot = robot ?: return
        RobotStatusTracker.activeOpMode = "TeleOp"
        restoreStartingPose(getBaseRobot(activeRobot))
        com.areslib.ftc.telemetry.LimelightProxyAutoStart.stop()
        refreshGamepadStates()
        driver.prime(g1State)
        operator.prime(g2State)
        context?.let { definition?.onStartBlock?.invoke(it) }
    }

    /** Executes one bounded driver-control frame. */
    final override fun loop() {
        val activeRobot = robot ?: return
        val activeContext = context ?: return
        refreshGamepadStates()
        applySimulationDriveInput(g1State)
        driver.update(g1State)
        operator.update(g2State)
        definition?.everyLoopBlock?.invoke(activeContext)
        updateProjectControls(activeRobot, g1State, g2State)
        updateRobot(activeRobot, g1State, g2State)
    }

    /** Closes the robot and optional Photon transport exactly once. */
    final override fun stop() {
        if (closed) return
        closed = true
        val activeRobot = robot
        try {
            if (activeRobot != null) {
                try {
                    cancelProjectControls(activeRobot)
                } finally {
                    closeRobot(activeRobot)
                }
            }
        } finally {
            robot = null
            context = null
            try {
                com.areslib.ftc.photon.AresPhotonCore.disable()
            } catch (_: Exception) {
                // Robot shutdown must continue when the optional Photon layer was never enabled.
            }
        }
    }

    private fun refreshGamepadStates() {
        g1State.update(gamepad1)
        g2State.update(gamepad2)
    }

    private fun restoreStartingPose(baseRobot: FtcBaseRobot?) {
        if (baseRobot == null) return
        val hasValidStoredPose = PoseStorage.hasValidPose
        val restoredAlliance = allianceForTeleOpRestore(hasValidStoredPose, PoseStorage.alliance)
        baseRobot.store.dispatch(com.areslib.action.RobotAction.SetAlliance(restoredAlliance))
        if (hasValidStoredPose) {
            baseRobot.resetPose(PoseStorage.currentPose)
        } else {
            baseRobot.resetPoseForAlliance()
        }
    }

    private fun applySimulationDriveInput(g1State: GamepadState) {
        val commandFrame = SimInputBridge.currentFrame()
        val webVx = commandFrame.vx
        val webVy = commandFrame.vy
        val webOmega = commandFrame.omega
        if (!webVx.isFinite() || !webVy.isFinite() || !webOmega.isFinite()) return
        if (abs(webVx) <= 0.01 && abs(webVy) <= 0.01 && abs(webOmega) <= 0.01) return

        if (abs(g1State.leftStickY) < 0.05f) {
            g1State.leftStickY = (-webVx / 4.0).coerceIn(-1.0, 1.0).toFloat()
        }
        if (abs(g1State.leftStickX) < 0.05f) {
            g1State.leftStickX = (-webVy / 4.0).coerceIn(-1.0, 1.0).toFloat()
        }
        if (abs(g1State.rightStickX) < 0.05f) {
            g1State.rightStickX = (-webOmega / 2.0).coerceIn(-1.0, 1.0).toFloat()
        }
    }

    private fun labelDefaultDriverControls(driver: AresGamepad) {
        driver.leftStick.label("Field-centric translation")
        driver.rightStickX.label("Robot rotation")
    }
}

/** Invalid pose storage must not leak an alliance retained by an older autonomous run. */
internal fun allianceForTeleOpRestore(
    hasValidStoredPose: Boolean,
    storedAlliance: com.areslib.state.Alliance
): com.areslib.state.Alliance = if (hasValidStoredPose) storedAlliance else com.areslib.state.Alliance.RED
