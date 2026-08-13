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
 * Pushes Driver Station console updates asynchronously to an [ArrayBlockingQueue], completely eliminating 15–30ms WiFi socket pauses
 * on main 50Hz control loops.
 *
 * @param store Redux state store instance.
 *
 * @see RobotTelemetryManager
 * @see NT4Telemetry
 * @see ActionLogger
 * @see DataLoggingTelemetry
 */
class FtcTelemetryManager(private val store: Store) : RobotTelemetryManager {
    /** Unique UUID string identifying this match execution run. */
    val runId = java.util.UUID.randomUUID().toString()
    /** Standard robot identifier string (`"ares_robot"`). */
    val robotId = "ares_robot"

    /** Core NT4 network tables client interface. */
    val nt4 = NT4Telemetry()
    /** Integrated disk and NT4 network telemetry logger. */
    override val dataLoggingTelemetry = DataLoggingTelemetry(nt4)
    /** Network state publisher translating Redux [RobotState] into NT4 topics. */
    val publisher = ARESNetworkStatePublisher(dataLoggingTelemetry)
    private var activeBrownoutGuard = BrownoutGuard.ftcDefaults()
    /** Guard currently used for both enforcement telemetry and compatibility publishing. */
    val brownoutGuard: BrownoutGuard get() = activeBrownoutGuard

    /** List of custom telemetry publisher callbacks executed every frame. */
    override val customPublishers = mutableListOf<(RobotState, ITelemetry) -> Unit>()

    /** Active action logger recording Redux actions into disk storage. */
    var actionLogger = ActionLogger(runId, robotId, 0, "BLUE", "Init")
        private set
        
    // Timestamp tracking for local Driver Station telemetry throttling
    private var lastLocalTelemetryUpdateMs = 0L

    @Volatile private var isRunning = true
    private val telemetryQueue = java.util.concurrent.ArrayBlockingQueue<List<Pair<String, String>>>(3)
    @Volatile private var currentLocalTelemetry: Telemetry? = null

    /** Thread-safe map storing custom telemetry strings displayed on the Driver Station console. */
    val customDriverStationText = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val driverStationThread = kotlin.concurrent.thread(start = true, name = "ARES-DriverStation-Thread") {
        while (isRunning) {
            try {
                val snapshot = telemetryQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                val t = currentLocalTelemetry
                if (snapshot != null && t != null) {
                    // Drain the queue to the latest snapshot to avoid falling behind
                    var latest = snapshot
                    while (telemetryQueue.isNotEmpty()) {
                        latest = telemetryQueue.poll() ?: latest
                    }
                    latest.forEach { (key, value) ->
                        t.addData(key, value)
                    }
                    t.update()
                }
            } catch (e: InterruptedException) {
                // Thread interrupted
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
        store.actionListener = { action -> actionLogger.logAction(action) }
        HardwareRegistry.registerCloseable(this)
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
        val detectedMode = com.areslib.telemetry.RobotStatusTracker.activeOpMode
        if (detectedMode != actionLogger.mode) {
            actionLogger.stop()
            actionLogger = ActionLogger(runId, robotId, 0, "BLUE", detectedMode)
        }

        activeBrownoutGuard.update(batteryVoltage)

        publisher.publish(state, gamepad1, gamepad2, dtSeconds, batteryVoltage, activeBrownoutGuard)

        // Global custom hardware telemetry
        HardwareRegistry.publishAll(dataLoggingTelemetry)

        // Invoke all registered custom publishers
        for (i in 0 until customPublishers.size) {
            customPublishers[i](state, dataLoggingTelemetry)
        }

        // Finalize frame and flush to loggers/network
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
        activeBrownoutGuard = powerBrownoutGuard
        val detectedMode = com.areslib.telemetry.RobotStatusTracker.activeOpMode
        if (detectedMode != actionLogger.mode) {
            actionLogger.stop()
            actionLogger = ActionLogger(runId, robotId, 0, "BLUE", detectedMode)
        }

        // Throttle NT4 network writes dynamically if enabled.
        // Disk logging still runs every frame via currentFrame accumulation.
        telemetryFrameCounter++
        val divisor = kotlin.math.max(1, state.tuning.telemetry.telemetryRateDivisor)
        val isNtFrame = enableNetworkStreaming && (telemetryFrameCounter % divisor == 0)
        dataLoggingTelemetry.ntEnabled = isNtFrame

        val estPose = state.drive.poseEstimator.estimatedPose
        // Subclass-specific telemetry (motor powers, currents, custom subsystems)
        onSubclassPublish()

        publisher.publish(state, gamepad1, gamepad2, dtSeconds, batteryVoltage, powerBrownoutGuard)

        // Vision telemetry status
        dataLoggingTelemetry.putString("Vision/Status", visionTracker.lastVisionStatus)
        dataLoggingTelemetry.putString("Drive/Odometry_Source", com.areslib.telemetry.RobotStatusTracker.odometrySource)
        dataLoggingTelemetry.putString("Drive/Pinpoint_Status", com.areslib.telemetry.RobotStatusTracker.odometryStatus)

        // Global custom hardware telemetry (also governed by ntEnabled flag)
        HardwareRegistry.publishAll(dataLoggingTelemetry)

        // Invoke all registered custom publishers
        for (i in 0 until customPublishers.size) {
            customPublishers[i](state, dataLoggingTelemetry)
        }

        currentLocalTelemetry = localTelemetry

        // Human-readable local driver station console printouts
        // Non-blocking architecture: string updates are pushed to a background thread queue. 
        // This completely eliminates the 15-30ms synchronous WiFi socket stalls from `Telemetry.update()`.
        if (timestamp - lastLocalTelemetryUpdateMs >= 250L) { // 4Hz real-time updates!
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
                telemetryQueue.offer(snapshot)
            }
            
            // Publish text console lines to NT4 for ARES-Analytics Driver Station widget
            for (i in snapshot.indices) {
                val (k, v) = snapshot[i]
                dataLoggingTelemetry.putString("ARES/DriverStation/Telemetry/$i", "$k: $v")
            }
            lastLocalTelemetryUpdateMs = timestamp
        }

        // Finalize frame: disk log always, NT4 flush only on NT frames
        dataLoggingTelemetry.putNumber("Diagnostics/DroppedActions", actionLogger.droppedActionCount.toDouble())
        dataLoggingTelemetry.update()

        // Reset NT4 enabled for any out-of-band puts between frames
        dataLoggingTelemetry.ntEnabled = true
    }

    /**
     * Source-compatible overload for the original FTC telemetry API. Once the actuator power
     * manager has supplied its guard, this path retains that same authoritative instance.
     */
    fun publishFull(
        state: RobotState,
        gamepad1: GamepadState?,
        gamepad2: GamepadState?,
        dtSeconds: Double,
        batteryVoltage: Double,
        visionTracker: FtcVisionTracker,
        timestamp: Long,
        localTelemetry: Telemetry?,
        onSubclassPublish: () -> Unit = {}
    ) = publishFull(
        state = state,
        gamepad1 = gamepad1,
        gamepad2 = gamepad2,
        dtSeconds = dtSeconds,
        batteryVoltage = batteryVoltage,
        powerBrownoutGuard = activeBrownoutGuard,
        visionTracker = visionTracker,
        timestamp = timestamp,
        localTelemetry = localTelemetry,
        onSubclassPublish = onSubclassPublish
    )

    /** Legacy motor telemetry hook (obsolete, retained for compatibility). */
    @Suppress("UNUSED_PARAMETER")
    fun publishMotors(batteryVoltage: Double) {
        // Obsolete: Handled by Unified ARESDataLogger
    }

    /**
     * Stops background Driver Station thread and flushes active log files.
     */
    override fun close() {
        isRunning = false
        driverStationThread.interrupt()
        dataLoggingTelemetry.close()
        actionLogger.stop()
    }
}


