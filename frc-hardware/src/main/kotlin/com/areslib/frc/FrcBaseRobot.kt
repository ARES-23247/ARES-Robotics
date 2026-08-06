package com.areslib.frc

import com.areslib.action.RobotAction
import com.areslib.frc.power.FrcPowerManager
import com.areslib.frc.telemetry.FrcTelemetryManager
import com.areslib.reducer.rootReducer
import com.areslib.state.RobotState
import com.areslib.subsystem.AresRobot
import com.areslib.subsystem.VisionTracker
import com.areslib.telemetry.*

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
    baseTelemetry: ITelemetry = FRCTelemetry(),
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
) : AresRobot(initialState, reducer) {

    /** FRC telemetry manager composing NT4, CSV logging, and custom publishers. */
    open val telemetryManager: FrcTelemetryManager = FrcTelemetryManager(baseTelemetry, store)

    /** FRC power/brownout manager. */
    open val powerManager: FrcPowerManager = FrcPowerManager()

    /** Optional vision tracker for AprilTag pose correction. */
    open var visionTracker: VisionTracker? = null

    /** Shorthand access to the composite data-logging telemetry backend. */
    val telemetry: ITelemetry get() = telemetryManager.dataLoggingTelemetry

    /** Shorthand access to the brownout guard from [powerManager]. */
    val brownoutGuard get() = powerManager.brownoutGuard

    /** Configurable battery voltage supplier delegated to [powerManager]. */
    var batteryVoltageSupplier: () -> Double
        get() = powerManager.batteryVoltageSupplier
        set(value) { powerManager.batteryVoltageSupplier = value }

    private var lastUpdateTime = 0L

    init {
        RobotWebServer.start()
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
        try {
            com.areslib.hardware.HardwareRegistry.refreshAll()
            val isEnabled = isEnabledProvider()
            val mode = robotModeProvider()

            if (isEnabled) RobotWebServer.stop() else RobotWebServer.start()
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

            // 5. Write registered subsystem outputs
            writeAllOutputs(scale)

            // 6. Platform-specific hardware writes
            writeHardwareOutputs(scale, powerManager.batteryVoltage)

            // 7. Telemetry
            telemetryManager.publish(store.state, gamepad1, gamepad2, dtSeconds, powerManager.batteryVoltage)
            telemetryManager.logBrownout(powerManager.brownoutGuard, powerManager.batteryVoltage)
            com.areslib.hardware.HardwareRegistry.publishAll(telemetry)
            publishRobotTelemetry(timestamp)
            telemetryManager.dataLoggingTelemetry.update()

        } catch (e: Throwable) {
            System.err.println("FrcBaseRobot: Exception in update loop: ${e.message}")
            e.printStackTrace()
            safeHardware()
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
    open fun close() {
        RobotStatusTracker.isEnabled = false
        RobotWebServer.stop()
        telemetryManager.close()
        closeSubsystems()
        com.areslib.hardware.HardwareRegistry.closeAll()
    }
}
