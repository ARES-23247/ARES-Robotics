package com.areslib.frc

import com.areslib.action.RobotAction
import com.areslib.frc.power.FrcPowerManager
import com.areslib.frc.telemetry.FrcTelemetryManager
import com.areslib.reducer.rootReducer
import com.areslib.state.RobotState
import com.areslib.subsystem.AresRobot
import com.areslib.subsystem.VisionTracker
import com.areslib.telemetry.*
import java.util.function.BooleanSupplier
import java.util.function.DoubleSupplier
import com.areslib.hardware.HardwareRegistry

/**
 * Abstract base container class for all FRC robots built on ARESLib.
 *
 * Coordinates the complete 50Hz FRC robot control loop:
 * 1. Hardware refresh and status tracking via [HardwareRegistry]
 * 2. Platform-specific sensor reads ([updateHardwareInputs])
 * 3. Vision tracking via pluggable [VisionTracker]
 * 4. Subsystem sensor reads ([readAllSensors])
 * 5. Power/brownout scaling via [FrcPowerManager]
 * 6. Subsystem output writes ([writeAllOutputs])
 * 7. Hardware output writes ([writeHardwareOutputs])
 * 8. Telemetry publishing via [FrcTelemetryManager]
 *
 * @param initialState Initial immutable [RobotState] snapshot.
 * @param reducer Root Redux reducer function composing state transitions.
 * @param baseTelemetry Platform telemetry backend (defaults to NT4 via [FRCTelemetry]).
 * @param isEnabledProvider Lambda returning active DriverStation enable state (`true`/`false`).
 * @param robotModeProvider Lambda returning active FRC match mode string (`"Auto"`, `"Teleop"`, `"Test"`, `"Disabled"`).
 *
 * @see AresRobot
 * @see FrcTelemetryManager
 * @see FrcPowerManager
 */
abstract class FrcBaseRobot(

    initialState: RobotState = RobotState(),
    reducer: (RobotState, RobotAction) -> RobotState = ::rootReducer,
    hardwareRegistry: HardwareRegistry = HardwareRegistry(),
    baseTelemetry: ITelemetry = FRCTelemetry(),
    telemetryManagerFactory: (com.areslib.Store, ITelemetry) -> FrcTelemetryManager = { store, telemetry ->
        FrcTelemetryManager(telemetry, store)
    },
    private val isEnabledProvider: () -> Boolean = {
        try {
            edu.wpi.first.wpilibj.DriverStation.isEnabled()
        } catch (_: Throwable) {
            false
        }
    },
    private val robotModeProvider: () -> String = {
        try {
            when {
                edu.wpi.first.wpilibj.DriverStation.isAutonomous() -> "Auto"
                edu.wpi.first.wpilibj.DriverStation.isTeleop() -> "Teleop"
                edu.wpi.first.wpilibj.DriverStation.isTest() -> "Test"
                else -> "Disabled"
            }
        } catch (_: Throwable) {
            "Active"
        }
    }
) : AresRobot(initialState, reducer, hardwareRegistry) {

    /**
     * The single FRC telemetry manager owned by this robot instance. Subclasses provide their
     * platform dependencies through [telemetryManagerFactory] instead of overriding this property;
     * that prevents an eager base logger from being constructed and abandoned during subclass init.
     */
    val telemetryManager: FrcTelemetryManager = telemetryManagerFactory(store, baseTelemetry)

    /** FRC power/brownout manager. */
    open val powerManager: FrcPowerManager = FrcPowerManager(hardwareRegistry)

    /** Optional vision tracker for AprilTag pose correction. */
    open var visionTracker: VisionTracker? = null

    /** Shorthand access to the composite data-logging telemetry backend. */
    val telemetry: ITelemetry get() = telemetryManager.dataLoggingTelemetry

    /** Shorthand access to the brownout guard from [powerManager]. */
    val brownoutGuard get() = powerManager.brownoutGuard

    /** Configurable battery voltage supplier delegated to [powerManager]. */
    var batteryVoltageSupplier: DoubleSupplier
        get() = powerManager.batteryVoltageSupplier
        set(value) { powerManager.batteryVoltageSupplier = value }

    /** Configurable total-current supplier, normally backed by WPILib PowerDistribution. */
    var totalCurrentSupplier: DoubleSupplier
        get() = powerManager.totalCurrentSupplier
        set(value) { powerManager.totalCurrentSupplier = value }

    /** Configurable roboRIO brownout-state supplier. */
    var brownedOutSupplier: BooleanSupplier
        get() = powerManager.brownedOutSupplier
        set(value) { powerManager.brownedOutSupplier = value }

    private var lastUpdateTime = 0L
    private var previousEnabled: Boolean? = null
    private var topologyPublished = false
    private var closed = false

    /** First fatal loop failure. A robot instance remains inhibited after this is set. */
    @Volatile
    var fatalUpdateFailure: Throwable? = null
        private set

    init {
        RobotStatusTracker.isEnabled = false
        RobotStatusTracker.activeOpMode = "Init"
    }

    /**
     * Coordinated frame update for the FRC robot lifecycle.
     *
     * Executes the full sensor-read → state-update → output-write → telemetry pipeline
     * in a single deterministic cycle. Called once per scheduler tick (~50 Hz).
     *
     * @param gamepad1 Optional driver gamepad state.
     * @param gamepad2 Optional operator gamepad state.
     */
    fun update(gamepad1: GamepadState? = null, gamepad2: GamepadState? = null) {
        fatalUpdateFailure?.let { failure ->
            safeHardware()
            throw failure
        }
        try {
            hardwareRegistry.refreshAll()
            val isEnabled = isEnabledProvider()
            val mode = robotModeProvider()

            RobotStatusTracker.isEnabled = isEnabled
            RobotStatusTracker.activeOpMode = mode

            val timestamp = com.areslib.util.RobotClock.currentTimeMillis()
            val dtSeconds = if (lastUpdateTime == 0L) 0.02 else (timestamp - lastUpdateTime) / 1000.0
            lastUpdateTime = timestamp

            // 1. Platform-specific hardware reads
            updateHardwareInputs(timestamp)

            // 2. Vision
            visionTracker?.update(timestamp)

            // 3. Read registered subsystem sensors
            readAllSensors(timestamp)

            // 4. Power scaling
            val scale = powerManager.update(dtSeconds, timestamp)

            // 5-6. WPILib disables vendor outputs, but the ARES loop must also avoid issuing stale
            // desired commands while disabled. On the first disabled frame (and every enabled ->
            // disabled transition), clear shared drive intent and invoke physical safety exactly
            // once. Season shells clear their mechanism slice from disabledInit.
            if (isEnabled) {
                writeAllOutputs(scale)
                writeHardwareOutputs(scale, powerManager.batteryVoltage)
            } else if (previousEnabled != false) {
                clearDriveIntentForDisable(timestamp)
                safeHardware()
            }
            previousEnabled = isEnabled

            // 7. Telemetry
            telemetryManager.logBrownout(powerManager.brownoutGuard, powerManager.batteryVoltage)
            telemetryManager.publish(store.state, gamepad1, gamepad2, dtSeconds, powerManager.batteryVoltage)
            telemetry.putNumber("Robot/TotalCurrentAmps", powerManager.currentAmps)
            telemetry.putBoolean("Robot/CurrentMeasurementValid", powerManager.currentMeasurementValid)
            telemetry.putNumber("Robot/CurrentPowerScale", powerManager.currentPowerScale)
            telemetry.putString("Robot/CurrentBudgetState", powerManager.currentBudgetState.name)
            telemetry.putBoolean("Robot/RioBrownedOut", powerManager.isBrownedOut)
            hardwareRegistry.publishAll(telemetry)
            publishRobotTelemetry(timestamp)
            telemetryManager.dataLoggingTelemetry.update()

        } catch (e: Throwable) {
            fatalUpdateFailure = e
            System.err.println("FrcBaseRobot: Exception in update loop: ${e.message}")
            e.printStackTrace()
            try {
                safeHardware()
            } catch (safetyFailure: Throwable) {
                e.addSuppressed(safetyFailure)
            }
            throw e
        }
    }

    /**
     * Reads platform-specific hardware sensors and dispatches observations to the store.
     * Called once per update cycle before vision and subsystem reads.
     *
     * @param timestampMs Current timestamp from [com.areslib.util.RobotClock].
     */
    protected abstract fun updateHardwareInputs(timestampMs: Long)

    /**
     * Writes platform-specific hardware outputs from the current store state.
     * Called once per update cycle after power scaling is computed.
     *
     * @param powerScale Global power scaling factor (0.0 to 1.0).
     * @param batteryVoltage Current filtered battery voltage.
     */
    protected abstract fun writeHardwareOutputs(powerScale: Double, batteryVoltage: Double)

    /**
     * Hook for subclasses to publish additional telemetry each cycle.
     * Default implementation is a no-op.
     *
     * @param timestampMs Current timestamp from [com.areslib.util.RobotClock].
     */
    protected open fun publishRobotTelemetry(timestampMs: Long) {}

    /**
     * Publishes the completed registry topology at most once for this robot instance.
     *
     * The value is added to the in-progress FRC telemetry frame without flushing; [update] performs
     * the single explicit flush after every core, season, power, registry, and platform topic has
     * been written.
     */
    fun publishHardwareTopology(robotId: String) {
        if (topologyPublished) return
        val json = hardwareRegistry.getTopologyJson(robotId)
        telemetryManager.publisher.publishTopology(json, flush = false)
        topologyPublished = true
    }

    private fun clearDriveIntentForDisable(timestampMs: Long) {
        store.dispatch(
            RobotAction.JoystickDriveIntent(
                targetXVelocity = 0.0,
                targetYVelocity = 0.0,
                targetAngularVelocity = 0.0,
                timestampMs = timestampMs,
                isFieldCentric = false,
                isXLock = true
            )
        )
        store.dispatch(RobotAction.SetHeadingLockTarget(null, timestampMs))
        store.dispatch(RobotAction.SetPositionLockTarget(null, null, timestampMs))
        store.dispatch(RobotAction.SetDriveMode(com.areslib.state.DriveMode.X_BRAKE, timestampMs))
    }

    /**
     * Emergency-stops all hardware by zeroing registered subsystem outputs
     * and invoking [com.areslib.hardware.HardwareRegistry.safeAll].
     */
    open fun safeHardware() {
        try {
            safeAll()
        } catch (ex: Throwable) {
            System.err.println("FrcBaseRobot: Safety stop failed: ${ex.message}")
        }
    }

    /**
     * Gracefully shuts down the robot: stops web server, closes telemetry,
     * releases all registered subsystems and hardware resources.
     */
    @Synchronized
    open fun close() {
        if (closed) return
        closed = true
        RobotStatusTracker.isEnabled = false
        closeBestEffort(
            { safeHardware() },
            { telemetryManager.close() },
            { closeSubsystems() },
            { hardwareRegistry.closeAll() }
        )
    }

    private fun closeBestEffort(vararg actions: () -> Unit) {
        var firstFailure: Throwable? = null
        for (action in actions) {
            try {
                action()
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }
}
