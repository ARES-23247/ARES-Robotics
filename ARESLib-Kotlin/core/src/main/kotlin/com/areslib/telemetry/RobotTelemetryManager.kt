package com.areslib.telemetry

import com.areslib.state.RobotState
import com.areslib.logging.DataLoggingTelemetry

/**
 * Platform-independent interface for the robot's unified telemetry pipeline.
 *
 * Manages the composition of:
 * - NetworkTables (NT4) live streaming for AdvantageScope
 * - CSV/JSONL file-based data logging for offline replay
 * - State publishing via [ARESNetworkStatePublisher]
 * - Custom per-subsystem telemetry publishers
 *
 * Both FTC and FRC platforms implement this interface with their
 * respective SDK-specific telemetry backends.
 */
interface RobotTelemetryManager : AutoCloseable {

    /**
     * The composite telemetry backend that writes to both NT4 and file logs.
     * Subsystems and team code use this to publish custom keys.
     */
    val dataLoggingTelemetry: DataLoggingTelemetry

    /**
     * Registered custom telemetry publishers invoked during each [publish] call.
     * Team-specific code (e.g., superstructure dashboards) registers callbacks here
     * rather than modifying the base robot class.
     */
    val customPublishers: MutableList<(RobotState, ITelemetry) -> Unit>

    /**
     * Publishes the current robot state, gamepad states, and all registered
     * custom publishers to the telemetry pipeline.
     *
     * @param state The current immutable robot state snapshot.
     * @param gamepad1 Optional driver gamepad state.
     * @param gamepad2 Optional operator gamepad state.
     * @param dtSeconds Loop cycle delta time in seconds.
     * @param batteryVoltage Current battery voltage for display.
     */
    fun publish(
        state: RobotState,
        gamepad1: GamepadState? = null,
        gamepad2: GamepadState? = null,
        dtSeconds: Double = 0.02,
        batteryVoltage: Double = 12.0
    )
}

/** Process-local robot status shared by platform telemetry publishers. */
object RobotStatusTracker {
    @Volatile var isEnabled: Boolean = false
    @Volatile var activeOpMode: String = "Disabled"
    @Volatile var visionConnected: Boolean = false
    @Volatile var visionStatus: String = "OFFLINE"
    @Volatile var odometrySource: String = "UNINITIALIZED"
    @Volatile var odometryStatus: String = "UNKNOWN"
    @Volatile var resolvedLimelightIp: String? = null
    @Volatile var activeLimelightIps: List<String> = emptyList()
    @Volatile var uploadProgress: Double = 0.0
    @Volatile var activeUploadFile: String? = null
    /** Canonical FTC hub transport selected by generated project metadata. */
    @Volatile var ftcHubCommandTransport: String = "STANDARD_SDK"
    /** True only when Photon successfully wrapped at least one real REV hub for this OpMode. */
    @Volatile var ftcPhotonActive: Boolean = false
    /** Whether this project requested the bounded Limelight HTTP proxy. */
    @Volatile var ftcLimelightProxyConfigured: Boolean = false
    /** Whether the requested Limelight proxy currently owns its listeners. */
    @Volatile var ftcLimelightProxyActive: Boolean = false
}

