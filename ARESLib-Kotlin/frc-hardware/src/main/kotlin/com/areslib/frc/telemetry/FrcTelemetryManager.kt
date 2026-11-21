package com.areslib.frc.telemetry

import com.areslib.control.safety.BrownoutGuard
import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.state.RobotState
import com.areslib.Store
import com.areslib.telemetry.*
import com.areslib.logging.DataLoggingTelemetry
import com.ctre.phoenix6.CANBus

/**
 * FRC telemetry orchestrator for AdvantageScope topics, CAN-bus diagnostics, and asynchronous CSV
 * output through [DataLoggingTelemetry].
 *
 * Reuses array buffers for four-module state/fault telemetry. Publishing still delegates to general
 * telemetry and logging backends, so the manager does not promise end-to-end zero allocation.
 *
 * ### Telemetry Network Topics & Physical Units:
 * - `Robot/SwerveStates`: 8-element array $[heading_0, speed_0, \dots, heading_3, speed_3]$ ($rad, m/s$).
 * - `Diagnostics/CANBus/CAN2/Utilization`: CTRE utilization ratio as reported by Phoenix.
 * - `Diagnostics/CANBus/CAN2/{ErrorCount,TxErrors,RxErrors,BusOffCount,SignalLatencyMs}`: bus diagnostics.
 * - `Diagnostics/Motor/Swerve_{i}/Faults`: Motor fault code bitmask.
 *
 * @param baseTelemetry Platform telemetry backend ([ITelemetry]).
 * @param store Redux store instance holding [RobotState].
 * @param swerveIO Optional CTRE swerve hardware IO instance ([SwerveHardwareIO]).
 *
 * @see RobotTelemetryManager
 * @see DataLoggingTelemetry
 * @see ARESNetworkStatePublisher
 */
open class FrcTelemetryManager(
    baseTelemetry: ITelemetry,
    private val store: Store,
    private val swerveIO: SwerveHardwareIO? = null
) : RobotTelemetryManager {

    private var closed = false

    // Unified telemetry pipeline: base telemetry → CSV wrapper → publisher
    override val dataLoggingTelemetry = DataLoggingTelemetry(baseTelemetry)
    val publisher = ARESNetworkStatePublisher(dataLoggingTelemetry)

    /**
     * Registered custom telemetry publishers invoked during each [publish] call.
     * Season-specific code registers callbacks here instead of modifying this class.
     */
    override val customPublishers = mutableListOf<(RobotState, ITelemetry) -> Unit>()

    // Pre-allocated buffers to prevent high-frequency GC allocations in update loop
    private val swerveStates = DoubleArray(8)
    private val swerveEncoders = DoubleArray(4)
    private val swerveModuleSpeeds = DoubleArray(4)
    private val swerveFaults = IntArray(4)
    private var cachedCanStatus: CANBus.CANBusStatus? = null

    private fun isRealRobot(): Boolean {
        return try {
            edu.wpi.first.wpilibj.RobotBase.isReal()
        } catch (_: Throwable) {
            false
        }
    }

    // CANBus instance created once to avoid allocations in update loop, wrapped in try-catch for simulation/test safety
    private val canBus = if (isRealRobot()) {
        try { CANBus("CAN2") } catch (_: Throwable) { null }
    } else {
        null
    }

    private var activeBrownoutGuard: BrownoutGuard? = null

    /** Refreshes cached CAN-bus diagnostics during the robot hardware-refresh phase. */
    internal fun refreshDiagnostics() {
        cachedCanStatus = try {
            canBus?.status
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Publishes core robot state, custom sub-state publishers, and AdvantageScope 3D visualization
     * topics into the current frame. [com.areslib.frc.FrcBaseRobot] owns the single flush after its
     * power, registry, and platform-specific topics have also been appended.
     *
     * @param state The current immutable robot state snapshot.
     * @param gamepad1 Driver 1 gamepad input state (or `null`).
     * @param gamepad2 Operator gamepad input state (or `null`).
     * @param dtSeconds Loop cycle delta time in seconds; forwarded to the shared publisher.
     * @param batteryVoltage Current robot supply voltage forwarded to the shared publisher.
     */
    override fun publish(
        state: RobotState,
        gamepad1: GamepadState?,
        gamepad2: GamepadState?,
        dtSeconds: Double,
        batteryVoltage: Double
    ) {
        publisher.publish(
            state,
            gamepad1,
            gamepad2,
            dtSeconds,
            batteryVoltage,
            activeBrownoutGuard,
            flush = false
        )

        // Invoke all registered custom publishers (season-specific subsystem dashboards)
        for (i in 0 until customPublishers.size) {
            customPublishers[i](state, dataLoggingTelemetry)
        }

        // Publish swerve module states from cached hardware feedback when available, falling back
        // to robot-frame kinematics rotated by the estimated heading.
        var usedMeasuredModules = false
        if (swerveIO != null && swerveIO.getEncoderPositionsIfValid(swerveEncoders)) {
            try {
                swerveIO.getModuleSpeeds(swerveModuleSpeeds)
                var allFinite = true
                for (i in 0..3) {
                    if (!swerveModuleSpeeds[i].isFinite()) {
                        allFinite = false
                        break
                    }
                }
                if (allFinite) {
                    for (i in 0..3) {
                        swerveStates[i * 2] = swerveEncoders[i] * TWO_PI
                        swerveStates[i * 2 + 1] = swerveModuleSpeeds[i]
                    }
                    usedMeasuredModules = true
                }
            } catch (_: Throwable) {
                // Fall back to chassis kinematics below if module speed read fails.
            }
        }
        if (!usedMeasuredModules) {
            if (state.drive.isXLock || state.drive.driveMode == com.areslib.state.DriveMode.X_BRAKE) {
                swerveStates[0] = Math.PI / 4.0; swerveStates[1] = 0.0
                swerveStates[2] = -Math.PI / 4.0; swerveStates[3] = 0.0
                swerveStates[4] = -Math.PI / 4.0; swerveStates[5] = 0.0
                swerveStates[6] = Math.PI / 4.0; swerveStates[7] = 0.0
            } else {
                val rawVx = state.drive.xVelocityMetersPerSecond
                val rawVy = state.drive.yVelocityMetersPerSecond
                val robotVx: Double
                val robotVy: Double
                if (state.drive.isFieldCentric) {
                    val heading = state.drive.poseEstimator.estimatedPoseHeading
                    val cosH = kotlin.math.cos(heading)
                    val sinH = kotlin.math.sin(heading)
                    robotVx = rawVx * cosH + rawVy * sinH
                    robotVy = -rawVx * sinH + rawVy * cosH
                } else {
                    robotVx = rawVx
                    robotVy = rawVy
                }
                val omega = state.drive.angularVelocityRadiansPerSecond
                for (i in 0..3) {
                    val wvx = robotVx - omega * SWERVE_OFFSETS[i].second
                    val wvy = robotVy + omega * SWERVE_OFFSETS[i].first
                    swerveStates[i * 2] = Math.atan2(wvy, wvx)
                    swerveStates[i * 2 + 1] = Math.hypot(wvx, wvy)
                }
            }
        }
        dataLoggingTelemetry.putDoubleArray("Robot/SwerveStates", swerveStates)

        // --- Log FRC CANbus Diagnostics & Latency ---
        try {
            val canStatus = cachedCanStatus
            if (canStatus != null) {
                val latencyMs = swerveIO?.signalLatencyMs ?: 0.0

                dataLoggingTelemetry.logCanBusStatus(
                    busName = "CAN2",
                    busUtilization = canStatus.BusUtilization.toDouble(),
                    errorCount = canStatus.REC + canStatus.TEC,
                    txErrors = canStatus.TEC,
                    rxErrors = canStatus.REC,
                    busOffs = canStatus.BusOffCount,
                    signalLatencyMs = latencyMs
                )
            }

            // Log Swerve Motor Active Faults
            if (swerveIO != null) {
                swerveIO.getFaults(swerveFaults)
                for (i in 0..3) {
                    dataLoggingTelemetry.putNumber("Diagnostics/Motor/Swerve_$i/Faults", swerveFaults[i].toDouble())
                }
            }
        } catch (_: Throwable) {
            // Graceful fallback if CANBus API fails (e.g. in simulation)
        }
    }

    /**
     * Updates internal reference to active [BrownoutGuard] for brownout logging.
     *
     * @param brownoutGuard Active brownout guard instance.
     */
    fun setBrownoutGuard(brownoutGuard: BrownoutGuard) {
        this.activeBrownoutGuard = brownoutGuard
    }

    /**
     * Gracefully closes telemetry log files and output streams.
     */
    override fun close() {
        if (closed) return
        closed = true
        dataLoggingTelemetry.close()
    }


    companion object {
        private const val TWO_PI = 2.0 * Math.PI
        private val SWERVE_OFFSETS = arrayOf(
            Pair(0.35, 0.35), Pair(0.35, -0.35),
            Pair(-0.35, 0.35), Pair(-0.35, -0.35)
        )
    }
}
