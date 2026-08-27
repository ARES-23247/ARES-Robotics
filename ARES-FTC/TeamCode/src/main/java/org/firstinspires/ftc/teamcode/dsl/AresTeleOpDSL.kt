package org.firstinspires.ftc.teamcode.dsl

import com.areslib.ftc.dsl.FtcTeleOpBase
import com.areslib.ftc.dsl.FtcTeleOpBuilder
import com.areslib.ftc.input.FtcInputFrameAdapter
import com.areslib.ftc.photon.AresFtcRuntimeOptions
import com.areslib.ftc.photon.AresFtcRuntimeOptionsProvider
import com.areslib.input.InputFrame
import com.areslib.telemetry.GamepadState
import org.firstinspires.ftc.teamcode.opmodes.AresRobot
import org.firstinspires.ftc.teamcode.config.AresRuntimePolicy

/**
 * Bridges ARESLib's declarative FTC lifecycle to the season [AresRobot] facade.
 * The shared base snapshots gamepads, invokes callbacks, runs [AresRobot.update], and
 * guarantees [AresRobot.close] on exit.
 */
abstract class AresTeleOpBase : FtcTeleOpBase<AresRobot>(), AresFtcRuntimeOptionsProvider {
    final override val aresFtcRuntimeOptions: AresFtcRuntimeOptions
        get() = AresRuntimePolicy.options

    private val driverFrame = InputFrame()
    private val operatorFrame = InputFrame()
    private var driverAdapter: FtcInputFrameAdapter? = null
    private var operatorAdapter: FtcInputFrameAdapter? = null
    private var generatedRuntime: FtcGeneratedProjectRuntime? = null

    /** True when the checked-in control scheme binds drivetrain axes through generated bindings. */
    protected val usesGeneratedDriveBindings: Boolean
        get() = generatedRuntime?.hasGeneratedDriveBindings == true

    /**
     * True when scheme-authored drive is actually emitted this frame: the OpMode opted in AND
     * the scheme binds drive axes. Hand-written drive fallbacks should key off THIS flag, not
     * usesGeneratedDriveBindings alone - an opted-out OpMode with drive bindings checked in
     * must keep its hand-written drive.
     */
    protected val generatedDriveActive: Boolean
        get() = allowGeneratedDrive && usesGeneratedDriveBindings

    /** Heading lock used by scheme-authored drive; toggling affects the generated sink. */
    protected var generatedHeadingLock: Boolean
        get() = generatedRuntime?.headingLockEnabled ?: true
        set(value) {
            generatedRuntime?.let { it.headingLockEnabled = value }
        }

    /**
     * OpModes that want scheme-authored drivetrain control opt in. Default false keeps tuning,
     * calibration, and diagnostic OpModes on their hand-written gamepad drive even when a scheme
     * with drive bindings is checked in.
     */
    protected open val allowGeneratedDrive: Boolean = false

    override fun buildRobot() = AresRobot(hardwareMap, telemetry).also { robot ->
        generatedRuntime = FtcGeneratedProjectRuntime(robot)
        robot.addTelemetry("ARES/Controls/Source", requireNotNull(generatedRuntime).controlsSource)
    }

    override fun getBaseRobot(robot: AresRobot) = robot.base

    override fun updateRobot(robot: AresRobot, g1: GamepadState, g2: GamepadState) = robot.update(g1, g2)

    override fun updateProjectControls(robot: AresRobot, g1: GamepadState, g2: GamepadState) {
        val driver = driverAdapter ?: FtcInputFrameAdapter(gamepad1, g1).also { driverAdapter = it }
        val operator = operatorAdapter ?: FtcInputFrameAdapter(gamepad2, g2).also { operatorAdapter = it }
        val nowNanos = com.areslib.util.RobotClock.nanoTime()
        driver.sampleInto(driverFrame, nowNanos)
        operator.sampleInto(operatorFrame, nowNanos)
        requireNotNull(generatedRuntime).updateControls(
            driverFrame,
            operatorFrame,
            nowNanos,
            emitDriveCommand = allowGeneratedDrive,
        )
    }

    override fun cancelProjectControls(robot: AresRobot) {
        generatedRuntime?.cancelAll("FTC TeleOp stopped")
    }

    override fun closeRobot(robot: AresRobot) {
        generatedRuntime = null
        driverAdapter = null
        operatorAdapter = null
        robot.close()
    }

    /** Builds a validated definition whose receiver exposes the concrete season facade. */
    fun teleOp(block: FtcTeleOpBuilder<AresRobot>.() -> Unit): FtcTeleOpBuilder<AresRobot> =
        com.areslib.ftc.dsl.teleOp(block)
}
