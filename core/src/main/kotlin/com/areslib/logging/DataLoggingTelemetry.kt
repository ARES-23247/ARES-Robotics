package com.areslib.logging

import com.areslib.telemetry.ITelemetry
import com.areslib.telemetry.RobotStatusTracker
import java.io.File

private const val LOG_PROFILE_TOPIC = "Diagnostics/Logging/Profile"
private const val LOG_ACCEPTED_TOPIC = "Diagnostics/Logging/AcceptedFrames"
private const val LOG_WRITTEN_TOPIC = "Diagnostics/Logging/WrittenFrames"
private const val LOG_DROPPED_TOPIC = "Diagnostics/Logging/DroppedFrames"
private const val LOG_QUEUE_DEPTH_TOPIC = "Diagnostics/Logging/QueueDepth"
private const val LOG_CURRENT_BYTES_TOPIC = "Diagnostics/Logging/CurrentFileBytes"
private const val LOG_COMPLETED_BYTES_TOPIC = "Diagnostics/Logging/CompletedBytes"
private const val LOG_ROTATIONS_TOPIC = "Diagnostics/Logging/Rotations"
private const val LOG_PRUNED_FILES_TOPIC = "Diagnostics/Logging/PrunedFiles"

/**
 * Single-owner telemetry accumulator that mirrors values to a live backend and asynchronous CSV.
 *
 * `put*` calls update the in-progress frame and, while [ntEnabled] is true, immediately forward the
 * value to [ntTelemetry]. [update] optionally snapshots the accumulated frame into [ARESDataLogger],
 * then flushes the live backend. When logging is throttled, values remain in the accumulator and the
 * most recent value for each key wins. Booleans are stored in CSV as `1.0`/`0.0`; double arrays are
 * stored as pipe-delimited strings.
 *
 * This class is designed for one robot-loop owner and is not thread-safe. Mode transitions close the
 * previous logger synchronously before creating the next mode-specific file. [close] drains disk
 * logging before closing the live backend.
 */
class DataLoggingTelemetry private constructor(
    private val ntTelemetry: ITelemetry?,
    internal val loggingPolicy: LoggingPolicy,
    private val logDirectory: File,
    private val runId: String?,
    @Suppress("UNUSED_PARAMETER") internalMarker: Unit
) : ITelemetry {

    constructor() : this(
        null,
        RobotLogEnvironment.loggingPolicy(),
        RobotLogEnvironment.logDirectory,
        null,
        Unit
    )

    /** Binary-compatible constructor retained for existing ARESLib consumers. */
    constructor(ntTelemetry: ITelemetry? = null) : this(
        ntTelemetry,
        RobotLogEnvironment.loggingPolicy(),
        RobotLogEnvironment.logDirectory,
        null,
        Unit
    )

    /** Associates every mode-specific telemetry file with the matching action-log run. */
    constructor(ntTelemetry: ITelemetry?, runId: String) : this(
        ntTelemetry,
        RobotLogEnvironment.loggingPolicy(),
        RobotLogEnvironment.logDirectory,
        runId,
        Unit
    )

    /** Internal policy injection used by deterministic logging tests. */
    internal constructor(
        ntTelemetry: ITelemetry? = null,
        loggingPolicy: LoggingPolicy,
        logDirectory: File = RobotLogEnvironment.logDirectory,
        runId: String? = null
    ) : this(ntTelemetry, loggingPolicy, logDirectory, runId, Unit)
    
    private var logger = ARESDataLogger("Init", logDirectory, loggingPolicy, runId)
    private val currentFrame = java.util.HashMap<String, Any>()
    private val frameLock = Any()
    private var currentMode = "Init"
    private val arrayBuilder = java.lang.StringBuilder(128)

    /**
     * When false, NT4 network forwarding is suppressed for this frame.
     * Disk logging still occurs every frame regardless of this flag.
     * Set by FtcTelemetryManager to throttle WiFi traffic.
     */
    var ntEnabled: Boolean = true

    init {
        ntTelemetry?.putString("OpMode", currentMode)
    }

    /**
     * The minimum time interval in milliseconds between file-based logging writes.
     * Defaults to the selected [loggingPolicy]. FORENSIC remains unthrottled; desktop SIMULATION
     * defaults to 20 Hz and physical COMPETITION defaults to 50 Hz.
     */
    var minLogIntervalMs: Long = loggingPolicy.minFrameIntervalMs
        set(value) {
            require(value >= 0L) { "Minimum log interval cannot be negative" }
            field = value
        }
    
    private var lastLogTimeMs = 0L

    /** Stores [value] in the current frame and forwards it when network output is enabled. */
    override fun putNumber(key: String, value: Double) {
        synchronized(frameLock) { currentFrame[key] = value }
        if (ntEnabled) ntTelemetry?.putNumber(key, value)
    }

    /** Stores [value] numerically in CSV while preserving boolean type on the live backend. */
    override fun putBoolean(key: String, value: Boolean) {
        synchronized(frameLock) { currentFrame[key] = if (value) 1.0 else 0.0 }
        if (ntEnabled) ntTelemetry?.putBoolean(key, value)
    }

    /** Stores and optionally forwards a string value. */
    override fun putString(key: String, value: String) {
        synchronized(frameLock) { currentFrame[key] = value }
        if (ntEnabled) ntTelemetry?.putString(key, value)
    }

    /** Serializes [value] for CSV and forwards the original array synchronously when enabled. */
    override fun putDoubleArray(key: String, value: DoubleArray) {
        synchronized(frameLock) {
            arrayBuilder.setLength(0)
            for (i in value.indices) {
                if (i > 0) arrayBuilder.append('|')
                arrayBuilder.append(value[i])
            }
            currentFrame[key] = arrayBuilder.toString()
        }
        if (ntEnabled) ntTelemetry?.putDoubleArray(key, value)
    }

    /** Delegates to live telemetry, or returns [defaultValue] when no backend exists. */
    override fun getNumber(key: String, defaultValue: Double): Double {
        return ntTelemetry?.getNumber(key, defaultValue) ?: defaultValue
    }

    /** Delegates to live telemetry, or returns [defaultValue] when no backend exists. */
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return ntTelemetry?.getBoolean(key, defaultValue) ?: defaultValue
    }

    /** Delegates to live telemetry, or returns [defaultValue] when no backend exists. */
    override fun getString(key: String, defaultValue: String): String {
        return ntTelemetry?.getString(key, defaultValue) ?: defaultValue
    }

    /** Commits a due disk frame, handles mode rollover, and flushes enabled live telemetry. */
    override fun update() {
        val now = com.areslib.util.RobotClock.currentTimeMillis()

        // Check if mode transitioned
        val detectedMode = RobotStatusTracker.activeOpMode
        if (detectedMode != currentMode) {
            logger.stop()
            currentMode = detectedMode
            logger = ARESDataLogger(currentMode, logDirectory, loggingPolicy, runId)
            ntTelemetry?.putString("OpMode", currentMode)
        }

        val logMetrics = logger.metricsSnapshot()
        putString(LOG_PROFILE_TOPIC, logMetrics.profile.name)
        putNumber(LOG_ACCEPTED_TOPIC, logMetrics.acceptedFrames.toDouble())
        putNumber(LOG_WRITTEN_TOPIC, logMetrics.writtenFrames.toDouble())
        putNumber(LOG_DROPPED_TOPIC, logMetrics.droppedFrames.toDouble())
        putNumber(LOG_QUEUE_DEPTH_TOPIC, logMetrics.queueDepth.toDouble())
        putNumber(LOG_CURRENT_BYTES_TOPIC, logMetrics.currentFileBytes.toDouble())
        putNumber(LOG_COMPLETED_BYTES_TOPIC, logMetrics.completedBytes.toDouble())
        putNumber(LOG_ROTATIONS_TOPIC, logMetrics.rotations.toDouble())
        putNumber(LOG_PRUNED_FILES_TOPIC, logMetrics.prunedFiles.toDouble())
        
        // Log the complete frame asynchronously using the GC-free map pool only if interval elapsed
        if (now - lastLogTimeMs >= minLogIntervalMs) {
            lastLogTimeMs = now
            val map = logger.obtainMap()
            synchronized(frameLock) {
                currentFrame["TimestampMs"] = now
                currentFrame["OpMode"] = currentMode
                map.putAll(currentFrame)
                currentFrame.clear()
            }
            logger.logFrame(map)
        }
        
        // Forward the update trigger to live streaming network tables (only on NT-enabled frames)
        if (ntEnabled) ntTelemetry?.update()
    }

    /**
     * Drains and closes disk logging, then closes the optional live backend.
     */
    override fun close() {
        logger.stop()
        ntTelemetry?.close()
    }

    /** Latest disk-writer metrics without waiting for the background queue to drain. */
    internal fun loggingMetrics(): ARESDataLoggerMetrics = logger.metricsSnapshot()
}
