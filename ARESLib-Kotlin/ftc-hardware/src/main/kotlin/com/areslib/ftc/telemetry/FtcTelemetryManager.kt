package com.areslib.ftc.telemetry

import com.areslib.Store
import com.areslib.telemetry.NT4Telemetry
import com.areslib.logging.DataLoggingTelemetry
import com.areslib.telemetry.ARESNetworkStatePublisher
import com.areslib.action.ActionLogger
import com.areslib.state.RobotState
import com.areslib.telemetry.GamepadState
import com.areslib.telemetry.ITelemetry
import com.areslib.telemetry.RobotTelemetryManager
import com.areslib.hardware.HardwareRegistry
import com.areslib.control.safety.BrownoutGuard

import org.firstinspires.ftc.robotcore.external.Telemetry
import com.areslib.ftc.vision.FtcVisionTracker
import com.areslib.telemetry.logPose2d
import com.areslib.telemetry.logPoseArray2d
import com.areslib.telemetry.TelemetryTopicConstants
import com.areslib.math.geometry.toFormattedString

/**
 * High-performance telemetry orchestrator for FTC target platforms.
 *
 * Coordinates NetworkTables 4 (NT4) real-time data streaming over [NT4Telemetry], structured disk file logging via [DataLoggingTelemetry]
 * and [ActionLogger], brownout protection monitoring via [com.areslib.control.safety.BrownoutGuard], and non-blocking Driver Station updates
 * driven by a dedicated 4Hz background thread (`ARES-DriverStation-Thread`).
 *
 * ### Telemetry Network Topics & Physical Units:
 * - `Drive/Pose_X`: EKF X position in meters ($m$).
 * - `Drive/Pose_Y`: EKF Y position in meters ($m$).
 * - `Drive/Pose_Heading`: EKF heading in radians ($rad$), **CCW-positive** standard.
 * - `Hardware/Motors/{name}/Power`: Motor duty-cycle output power $[-1.0, 1.0]$.
 * - `Hardware/Motors/{name}/CurrentAmps`: Motor current draw in Amperes ($A$).
 * - `ARES/DriverStation/Telemetry/{i}`: Driver station text console lines.
 *
 * ### Performance Guarantees:
 * Sends Driver Station console updates through a bounded latest-snapshot handoff. SDK console
 * writes run on one background worker; serialization, callbacks and NT4 still consume loop time.
 *
 * @param store Redux state store instance.
 *
 * @see RobotTelemetryManager
 * @see NT4Telemetry
 * @see ActionLogger
 * @see DataLoggingTelemetry
 */
class FtcTelemetryManager(
    private val store: Store,
    private val hardwareRegistry: HardwareRegistry,
) : RobotTelemetryManager {
    /** Unique UUID string identifying this match execution run. */
    val runId = java.util.UUID.randomUUID().toString()
    /** Standard robot identifier string (`"ares_robot"`). */
    val robotId = "ares_robot"

    /** Core NT4 network tables client interface. */
    val nt4 = NT4Telemetry()
    /** Integrated disk and NT4 network telemetry logger. */
    override val dataLoggingTelemetry = DataLoggingTelemetry(nt4, runId)
    /** Network state publisher translating Redux [RobotState] into NT4 topics. */
    val publisher = ARESNetworkStatePublisher(dataLoggingTelemetry)
    private var activeBrownoutGuard = BrownoutGuard.ftcDefaults()
    /** Guard currently used for both enforcement telemetry and compatibility publishing. */
    val brownoutGuard: BrownoutGuard get() = activeBrownoutGuard

    /** List of custom telemetry publisher callbacks executed every frame. */
    override val customPublishers = mutableListOf<(RobotState, ITelemetry) -> Unit>()

    /** Active action logger recording Redux actions into disk storage. */
    val actionLogger = ActionLogger(runId, robotId, 0, "BLUE", "Init")
        
    // Timestamp tracking for local Driver Station telemetry throttling
    private var lastLocalTelemetryUpdateMs = 0L
    private var hasLocalTelemetryTime = false
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var isRunning = true
    private class DriverStationFrame(val target: Telemetry,val lines: List<Pair<String,String>>)
    private val latestDriverStationFrame = java.util.concurrent.atomic.AtomicReference<DriverStationFrame?>()
    private val driverStationReady = java.util.concurrent.Semaphore(0)
    private val previousActionListener = store.actionListener
    private val ownedActionListener: (com.areslib.action.RobotAction) -> Unit = { action ->
        if (!closed.get()) actionLogger.logAction(action,com.areslib.telemetry.RobotStatusTracker.activeOpMode)
    }

    /** Thread-safe map storing custom telemetry strings displayed on the Driver Station console. */
    val customDriverStationText = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val driverStationThread = kotlin.concurrent.thread(start = true, isDaemon = true, name = "ARES-DriverStation-Thread") {
        while (isRunning) {
            try {
                driverStationReady.acquire()
                if (!isRunning) break
                val snapshot = latestDriverStationFrame.getAndSet(null) ?: continue
                // Select the literal-value SDK overload, not String.format with no arguments.
                snapshot.lines.forEach { (key,value) -> snapshot.target.addData(key,value as Any) }
                snapshot.target.update()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                // Ignore background telemetry formatting errors
            }
        }
    }

    private var telemetryFrameCounter = 0

    /** Toggle controlling whether live NT4 network streaming is active (can be disabled during official matches). */
    var enableNetworkStreaming: Boolean = true

    init {
        // Intercept and record all dispatched store actions asynchronously
        store.actionListener = ownedActionListener
        hardwareRegistry.registerCloseable(this)
    }

    /**
     * Standard telemetry publish pass updating NT4 streams, disk logs, and custom telemetry sinks.
     *
     * @param state Current [RobotState] snapshot.
     * @param gamepad1 Driver 1 [GamepadState] input snapshot (or `null`).
     * @param gamepad2 Driver 2 [GamepadState] input snapshot (or `null`).
     * @param dtSeconds Loop time step interval in seconds ($s$).
     * @param batteryVoltage Measured main battery voltage in Volts ($V$).
     */
    override fun publish(
        state: RobotState,
        gamepad1: GamepadState?,
        gamepad2: GamepadState?,
        dtSeconds: Double,
        batteryVoltage: Double
    ) {
        check(!closed.get()) { "Telemetry manager is closed" }
        val detectedMode = com.areslib.telemetry.RobotStatusTracker.activeOpMode
        actionLogger.beginMode(detectedMode)

        activeBrownoutGuard.update(batteryVoltage)

        publisher.publish(state, gamepad1, gamepad2, dtSeconds, batteryVoltage, activeBrownoutGuard)

        // Global custom hardware telemetry
        hardwareRegistry.publishAll(dataLoggingTelemetry)

        // Invoke all registered custom publishers
        for (i in 0 until customPublishers.size) {
            customPublishers[i](state, dataLoggingTelemetry)
        }

        // Finalize frame and flush to loggers/network
        publishFtcRuntimeStatus()
        dataLoggingTelemetry.putNumber("Diagnostics/DroppedActions", actionLogger.droppedActionCount.toDouble())
        dataLoggingTelemetry.update()
    }

    /**
     * Extended FTC publish pass incorporating vision tracking telemetry, custom subclass hooks, and non-blocking Driver Station console output.
     *
     * @param state Current [RobotState] snapshot.
     * @param gamepad1 Driver 1 [GamepadState] input snapshot (or `null`).
     * @param gamepad2 Driver 2 [GamepadState] input snapshot (or `null`).
     * @param dtSeconds Loop time step interval in seconds ($s$).
     * @param batteryVoltage Measured main battery voltage in Volts ($V$).
     * @param visionTracker Active [FtcVisionTracker] instance.
     * @param powerBrownoutGuard Guard used by the actuator power manager. Passing the same instance
     * keeps telemetry and the enforced output scale on one authoritative state machine.
     * @param timestamp System time in milliseconds ($ms$).
     * @param localTelemetry FTC SDK [Telemetry] console instance.
     * @param onSubclassPublish Custom lambda hook executed prior to flushing telemetry.
     */
    fun publishFull(
        state: RobotState,
        gamepad1: GamepadState?,
        gamepad2: GamepadState?,
        dtSeconds: Double,
        batteryVoltage: Double,
        powerBrownoutGuard: BrownoutGuard,
        visionTracker: FtcVisionTracker,
        timestamp: Long,
        localTelemetry: Telemetry?,
        onSubclassPublish: () -> Unit = {}
    ) {
        check(!closed.get()) { "Telemetry manager is closed" }
        activeBrownoutGuard = powerBrownoutGuard
        val detectedMode = com.areslib.telemetry.RobotStatusTracker.activeOpMode
        actionLogger.beginMode(detectedMode)

        // Throttle NT4 network writes dynamically if enabled.
        // Disk logging keeps accumulating the latest value and commits at the selected logging
        // profile's rate. FORENSIC is per-frame; SIMULATION is intentionally slower.
        telemetryFrameCounter++
        val divisor = kotlin.math.max(1, state.tuning.telemetry.telemetryRateDivisor)
        val isNtFrame = enableNetworkStreaming && (telemetryFrameCounter % divisor == 0)
        dataLoggingTelemetry.ntEnabled = isNtFrame
        try {
            val estPose = state.drive.poseEstimator.estimatedPose
            // Subclass-specific telemetry (motor powers, currents, custom subsystems)
            onSubclassPublish()

            publisher.publish(state, gamepad1, gamepad2, dtSeconds, batteryVoltage, powerBrownoutGuard)

            // Vision telemetry status
            dataLoggingTelemetry.putString("Vision/Status", visionTracker.lastVisionStatus)
            dataLoggingTelemetry.putString("Drive/Odometry_Source", com.areslib.telemetry.RobotStatusTracker.odometrySource)
            dataLoggingTelemetry.putString("Drive/Pinpoint_Status", com.areslib.telemetry.RobotStatusTracker.odometryStatus)

            // Global custom hardware telemetry (also governed by ntEnabled flag)
            hardwareRegistry.publishAll(dataLoggingTelemetry)

            // Invoke all registered custom publishers
            for (i in 0 until customPublishers.size) {
                customPublishers[i](state, dataLoggingTelemetry)
            }

            // Human-readable local driver station console printouts
            // Non-blocking architecture: string updates are handed to the background worker.
            val localElapsed = timestamp-lastLocalTelemetryUpdateMs
            if (!hasLocalTelemetryTime || timestamp < lastLocalTelemetryUpdateMs || localElapsed < 0L || localElapsed >= 250L) {
                val snapshot = mutableListOf(
                    "EKF Pose (X, Y, Deg)" to estPose.toFormattedString(),
                    "Raw Pinpoint (X, Y, Deg)" to com.areslib.math.geometry.Pose2d(
                        state.drive.odometryX,
                        state.drive.odometryY,
                        com.areslib.math.geometry.Rotation2d(state.drive.odometryHeading)
                    ).toFormattedString(),
                    "Odometry Source" to com.areslib.telemetry.RobotStatusTracker.odometrySource,
                    "Pinpoint Status" to com.areslib.telemetry.RobotStatusTracker.odometryStatus,
                    "Limelight Pose (X, Y, Deg)" to (visionTracker.lastLimelightPose?.let { pose ->
                        val ageSec = (timestamp - visionTracker.lastLimelightTimeMs) / 1000.0
                        "${pose.toFormattedString()} (${String.format("%.1f", ageSec)}s ago)"
                    } ?: "NO TARGET"),
                    "Vision Status" to visionTracker.lastVisionStatus
                )
                customDriverStationText.forEach { (k, v) -> snapshot.add(k to v) }
                if (localTelemetry != null) {
                    val frame = DriverStationFrame(localTelemetry,snapshot)
                    if (latestDriverStationFrame.getAndSet(frame) == null) driverStationReady.release()
                }
            
                // Publish text console lines to NT4 for ARES-Analytics Driver Station widget
                for (i in snapshot.indices) {
                    val (k, v) = snapshot[i]
                    dataLoggingTelemetry.putString("ARES/DriverStation/Telemetry/$i", "$k: $v")
                }
                lastLocalTelemetryUpdateMs = timestamp
                hasLocalTelemetryTime = true
            }

            // Finalize frame: disk log always, NT4 flush only on NT frames
            publishFtcRuntimeStatus()
            dataLoggingTelemetry.putNumber("Diagnostics/DroppedActions", actionLogger.droppedActionCount.toDouble())
            dataLoggingTelemetry.update()

        } finally {
            // Fatal diagnostics and other out-of-band puts must not inherit a failed frame's throttle.
            dataLoggingTelemetry.ntEnabled = true
        }
    }

    private fun publishFtcRuntimeStatus() {
        val status = com.areslib.telemetry.RobotStatusTracker
        dataLoggingTelemetry.putString(
            TelemetryTopicConstants.FTC_HUB_COMMAND_TRANSPORT,
            status.ftcHubCommandTransport,
        )
        dataLoggingTelemetry.putBoolean(TelemetryTopicConstants.FTC_PHOTON_ACTIVE, status.ftcPhotonActive)
        dataLoggingTelemetry.putBoolean(
            TelemetryTopicConstants.FTC_LIMELIGHT_PROXY_CONFIGURED,
            status.ftcLimelightProxyConfigured,
        )
        dataLoggingTelemetry.putBoolean(
            TelemetryTopicConstants.FTC_LIMELIGHT_PROXY_ACTIVE,
            status.ftcLimelightProxyActive,
        )
    }

    /**
     * Stops background Driver Station thread and flushes active log files.
     */
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        isRunning = false
        if (store.actionListener === ownedActionListener) store.actionListener = previousActionListener
        latestDriverStationFrame.set(null)
        driverStationThread.interrupt()
        var failure: Throwable? = null
        try { dataLoggingTelemetry.close() } catch (error: Throwable) { failure=error }
        try { actionLogger.stop() } catch (error: Throwable) {
            if(failure==null) failure=error else failure.addSuppressed(error)
        }
        if (Thread.currentThread() !== driverStationThread) {
            try { driverStationThread.join(1000) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        failure?.let { throw it }
    }
}


