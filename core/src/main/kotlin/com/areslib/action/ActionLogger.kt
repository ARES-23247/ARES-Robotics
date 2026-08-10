package com.areslib.action

import com.areslib.util.RobotClock
import com.google.gson.Gson
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe, non-blocking asynchronous JSONL (JSON Lines) recorder for [RobotAction] streams.
 *
 * Captures Redux-style action dispatches in sequence and writes them to local disk storage on a
 * dedicated single-threaded daemon executor. Known reusable mutable actions are copied into pooled
 * logger-owned snapshots before enqueueing, so later producer mutations cannot rewrite history.
 * Enqueueing remains non-blocking; rejected actions are observable through [droppedActionCount].
 * Files use a `.jsonl.active` suffix until [stop] has drained every accepted action.
 *
 * ### Output File Storage Path:
 * - **Android FTC Control Hub**: `/sdcard/FIRST/telemetry_logs/action_log_<timestamp>_<mode>.jsonl`
 * - **Desktop Simulation / FRC RoboRIO**: `./logs/action_log_<timestamp>_<mode>.jsonl`
 *
 * ### JSONL Output Schema:
 * ```json
 * {
 *   "run_id": "UUID-or-empty",
 *   "robot_id": "Robot-01",
 *   "match_number": 12,
 *   "alliance": "BLUE",
 *   "op_mode": "Teleop",
 *   "type": "JoystickDriveIntent",
 *   "payload": { "x": 0.5, "y": 0.0, "rotation": 0.1, "isFieldCentric": true }
 * }
 * ```
 *
 * @param runId Unique telemetry run identifier string.
 * @param robotId Target robot hardware identifier.
 * @param matchNumber Competition match number (0 for practice/testing).
 * @param alliance Active alliance color ("RED" or "BLUE").
 * @param mode Current operational mode ("Auto", "Teleop", or "Init").
 * @param logDirectory optional explicit output directory, primarily for hermetic tests
 */
/**
 * Class implementation for Action Logger.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
class ActionLogger(
    val runId: String = "",
    val robotId: String = "",
    val matchNumber: Int = 0,
    val alliance: String = "BLUE",
    val mode: String = "Init",
    private val logDirectory: File? = null
) {
    private val gson = Gson()
    private val queue = LinkedBlockingQueue<RobotAction>(1000)
    private var writer: BufferedWriter? = null
    private var activeLogFile: File? = null
    private var completedLogFile: File? = null
    @Volatile private var isRunning = false
    private val queueStateLock = Any()
    private val workerDone = CountDownLatch(1)
    private val droppedActions = AtomicLong(0L)
    private val joystickSnapshotPool = ConcurrentLinkedQueue<RobotAction.JoystickDriveIntent>()
    private val poseSnapshotPool = ConcurrentLinkedQueue<RobotAction.PoseUpdate>()

    /** Number of actions rejected during shutdown/queue saturation or lost to a write failure. */
    val droppedActionCount: Long
        get() = droppedActions.get()

    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        { thread -> Thread(thread, "ARES-ActionLogger-Thread").apply { isDaemon = true } }
    )

    init {
        try {
            val javaVendor = System.getProperty("java.vendor") ?: ""
            val isAndroid = javaVendor.contains("Android", ignoreCase = true) || File("/sdcard").exists()

            val logDir = logDirectory ?: if (isAndroid) {
                File("/sdcard/FIRST/telemetry_logs/")
            } else {
                File("./logs/")
            }

            if (!logDir.exists()) {
                logDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.getDefault()).format(Date(RobotClock.currentTimeMillis()))
            val safeMode = mode.map { character ->
                if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
            }.joinToString("").ifBlank { "Unknown" }
            val finalFile = File(logDir, "action_log_${timestamp}_$safeMode.jsonl")
            val logFile = File(logDir, "${finalFile.name}.active")

            activeLogFile = logFile
            completedLogFile = finalFile
            writer = BufferedWriter(FileWriter(logFile))
            isRunning = true
            startLoggingLoop()
        } catch (e: Exception) {
            System.err.println("ActionLogger: Failed to initialize! ${e.message}")
            isRunning = false
            workerDone.countDown()
        }
    }

    /**
     * Enqueues a [RobotAction] for background asynchronous serialization and disk writing.
     * Non-blocking call returning immediately to maintain main loop performance.
     *
     * @param action The Redux action object dispatched to the store.
     */
    fun logAction(action: RobotAction) {
        synchronized(queueStateLock) {
            if (!isRunning) {
                droppedActions.incrementAndGet()
                return
            }
            val snapshot = snapshotForQueue(action)
            if (!queue.offer(snapshot)) {
                droppedActions.incrementAndGet()
                recycleSnapshot(snapshot)
            }
        }
    }

    private fun snapshotForQueue(action: RobotAction): RobotAction = when (action) {
        is RobotAction.JoystickDriveIntent -> {
            val snapshot = joystickSnapshotPool.poll() ?: RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0)
            snapshot.targetXVelocity = action.targetXVelocity
            snapshot.targetYVelocity = action.targetYVelocity
            snapshot.targetAngularVelocity = action.targetAngularVelocity
            snapshot.timestampMs = action.timestampMs
            snapshot.isFieldCentric = action.isFieldCentric
            snapshot.fromHeadingHold = action.fromHeadingHold
            snapshot.isXLock = action.isXLock
            snapshot
        }
        is RobotAction.PoseUpdate -> {
            val snapshot = poseSnapshotPool.poll() ?: RobotAction.PoseUpdate(0.0, 0.0, 0.0, 0L)
            snapshot.xMeters = action.xMeters
            snapshot.yMeters = action.yMeters
            snapshot.headingRadians = action.headingRadians
            snapshot.timestampMs = action.timestampMs
            snapshot.pitchDegrees = action.pitchDegrees
            snapshot.rollDegrees = action.rollDegrees
            snapshot.xAccelerationG = action.xAccelerationG
            snapshot.yAccelerationG = action.yAccelerationG
            snapshot.zAccelerationG = action.zAccelerationG
            snapshot.isReset = action.isReset
            snapshot.angularVelocityRadiansPerSecond = action.angularVelocityRadiansPerSecond
            snapshot.xVelocityMetersPerSecond = action.xVelocityMetersPerSecond
            snapshot.yVelocityMetersPerSecond = action.yVelocityMetersPerSecond
            snapshot.isExternalEstimate = action.isExternalEstimate
            snapshot
        }
        else -> action
    }

    private fun recycleSnapshot(action: RobotAction) {
        when (action) {
            is RobotAction.JoystickDriveIntent -> joystickSnapshotPool.offer(action)
            is RobotAction.PoseUpdate -> poseSnapshotPool.offer(action)
            else -> Unit
        }
    }

    private fun startLoggingLoop() {
        executor.submit {
            var wasInterrupted = false
            try {
                while (isRunning || queue.isNotEmpty()) {
                    try {
                        val action = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        try {
                            writeAction(action)
                        } finally {
                            recycleSnapshot(action)
                        }
                    } catch (_: InterruptedException) {
                        wasInterrupted = true
                    } catch (e: Exception) {
                        droppedActions.incrementAndGet()
                        System.err.println("ActionLogger: Error logging action: ${e.message}")
                    }
                }
            } finally {
                closeWriter()
                finalizeLogFile()
                workerDone.countDown()
                if (wasInterrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private val adapterCache = java.util.concurrent.ConcurrentHashMap<Class<*>, com.google.gson.TypeAdapter<RobotAction>>()

    private fun writeAction(action: RobotAction) {
        val w = writer ?: return
        val clazz = action.javaClass
        val typeName = clazz.simpleName
        
        @Suppress("UNCHECKED_CAST")
        val adapter = adapterCache.getOrPut(clazz) { gson.getAdapter(clazz) as com.google.gson.TypeAdapter<RobotAction> }

        try {
            w.write("{\"run_id\":")
            w.write(gson.toJson(runId))
            w.write(",\"robot_id\":")
            w.write(gson.toJson(robotId))
            w.write(",\"match_number\":")
            w.write(matchNumber.toString())
            w.write(",\"alliance\":")
            w.write(gson.toJson(alliance))
            w.write(",\"op_mode\":")
            w.write(gson.toJson(mode))
            w.write(",\"type\":")
            w.write(gson.toJson(typeName))
            w.write(",\"payload\":")
            adapter.toJson(w, action)
            w.write("}")
            w.newLine()
        } catch (e: IOException) {
            droppedActions.incrementAndGet()
            System.err.println("ActionLogger: Failed to write JSONL: ${e.message}")
        }
    }

    private fun closeWriter() {
        try {
            writer?.flush()
            writer?.close()
        } catch (e: IOException) {
            System.err.println("ActionLogger: Failed to close: ${e.message}")
        } finally {
            writer = null
        }
    }

    private fun finalizeLogFile() {
        val active = activeLogFile ?: return
        val completed = completedLogFile ?: return
        if (!active.exists()) return
        if (completed.exists() && !completed.delete()) {
            System.err.println("ActionLogger: Could not replace completed log ${completed.absolutePath}")
            return
        }
        if (!active.renameTo(completed)) {
            System.err.println("ActionLogger: Could not finalize active log ${active.absolutePath}")
            return
        }
        activeLogFile = null
    }

    /**
     * Flushes remaining queued actions, closes disk file handles, and shuts down the background logging worker thread.
     */
    fun stop() {
        synchronized(queueStateLock) {
            isRunning = false
        }
        executor.shutdown()
        var wasInterrupted = false
        while (workerDone.count > 0L) {
            try {
                workerDone.await()
            } catch (_: InterruptedException) {
                wasInterrupted = true
            }
        }
        if (wasInterrupted) Thread.currentThread().interrupt()
    }
}
