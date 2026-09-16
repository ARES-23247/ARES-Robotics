package com.areslib.hardware

import com.areslib.telemetry.ITelemetry
import com.areslib.telemetry.schema.TopologyNodeType

/** Legacy logical-name classification used when CAN registration omits an explicit node type. */
internal object HardwareTopologyTypes {
    fun forName(name: String): TopologyNodeType {
        val lower = name.lowercase()
        return when {
            lower.contains("imu") || lower.contains("gyro") -> TopologyNodeType.IMU
            lower.contains("camera") || lower.contains("vision") -> TopologyNodeType.CAMERA
            lower.contains("pinpoint") || lower.contains("odometry") -> TopologyNodeType.ODOMETRY_COMPUTER
            lower.contains("color") -> TopologyNodeType.COLOR_SENSOR
            lower.contains("distance") -> TopologyNodeType.DISTANCE_SENSOR
            lower.contains("beam") -> TopologyNodeType.BEAM_BREAK
            else -> TopologyNodeType.ANALOG_SENSOR
        }
    }
}

/**
 * Unified interface for any hardware component or sensor that publishes diagnostics or telemetry keys.
 */
interface LoggableDevice {
    /**
     * Publishes telemetry metrics for this device to the provider using the specified prefix key.
     */
    fun logTelemetry(telemetry: ITelemetry, prefix: String) {}
}

/**
 * Common lifecycle interface for hardware wrappers supporting batch status updates and safety configurations.
 */
interface SubsystemIO : LoggableDevice {
    /**
     * Refreshes cached status signals or bulk registers from physical hardware.
     */
    fun refresh() {}

    /**
     * Commands this subsystem's actuator outputs to safe, zero-effort settings.
     */
    fun safe() {}
}

/**
 * Read-only simulator boundary for mechanism outputs that were actually applied by season IO.
 *
 * Season facades should expose cached post-safety outputs here after `writeOutputs`; dashboard
 * intent must never implement this contract because it can disagree with disabled, inhibited, or
 * failed hardware.
 */
interface SimMechanismOutputProvider {
    val intakeApplied: Boolean
    val flywheelApplied: Boolean
    val transferApplied: Boolean
}

/**
 * Hardware component whose blocking read is scheduled on the registry polling thread.
 *
 * Devices implementing this interface can be registered with [HardwareRegistry.registerSyncPolledDevice]
 * to be included in the centralized hardware polling loop. The registry polls at most one device
 * from each polling list per interval; the interval is therefore not a per-device sampling period.
 * Implementations must publish results through their own thread-safe cached fields so robot-loop
 * getters never touch hardware directly.
 */
interface SyncPolledDevice {
    /**
     * Performs one synchronous hardware transaction and updates cached state.
     *
     * Called serially by the registry-owned daemon thread. Implementations must not retain caller
     * state, block indefinitely, or throw; an uncaught exception terminates the shared polling loop.
     */
    fun pollSync()
}
