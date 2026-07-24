package com.areslib.logging

import com.areslib.telemetry.ITelemetry
import com.areslib.telemetry.RobotStatusTracker
import java.util.concurrent.ConcurrentHashMap

/**
 * Composite telemetry wrapper that automatically saves all published data
 * to an offline file-based CSV log on update() while forwarding data
 * to the NT4 server if alive.
 */
class DataLoggingTelemetry(private val ntTelemetry: ITelemetry? = null) : ITelemetry {
    
    private var logger = ARESDataLogger("Init")
    private val currentFrame = java.util.HashMap<String, Any>()
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
     * Defaults to 20ms (maximum 50Hz logging rate) to prevent storage and CPU congestion.
     */
    var minLogIntervalMs: Long = 20L
    
    private var lastLogTimeMs = 0L

    /**
     * putNumber declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun putNumber(key: String, value: Double) {
        currentFrame[key] = value
        if (ntEnabled) ntTelemetry?.putNumber(key, value)
    }

    /**
     * putBoolean declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun putBoolean(key: String, value: Boolean) {
        currentFrame[key] = if (value) 1.0 else 0.0
        if (ntEnabled) ntTelemetry?.putBoolean(key, value)
    }

    /**
     * putString declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun putString(key: String, value: String) {
        currentFrame[key] = value
        if (ntEnabled) ntTelemetry?.putString(key, value)
    }

    /**
     * putDoubleArray declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun putDoubleArray(key: String, value: DoubleArray) {
        arrayBuilder.setLength(0)
        for (i in value.indices) {
            if (i > 0) arrayBuilder.append('|')
            arrayBuilder.append(value[i])
        }
        currentFrame[key] = arrayBuilder.toString()
        if (ntEnabled) ntTelemetry?.putDoubleArray(key, value)
    }

    /**
     * getNumber declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun getNumber(key: String, defaultValue: Double): Double {
        return ntTelemetry?.getNumber(key, defaultValue) ?: defaultValue
    }

    /**
     * getBoolean declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return ntTelemetry?.getBoolean(key, defaultValue) ?: defaultValue
    }

    /**
     * getString declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun getString(key: String, defaultValue: String): String {
        return ntTelemetry?.getString(key, defaultValue) ?: defaultValue
    }

    /**
     * update declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun update() {
        val now = com.areslib.util.RobotClock.currentTimeMillis()

        // Check if mode transitioned
        val detectedMode = RobotStatusTracker.activeOpMode
        if (detectedMode != currentMode) {
            logger.stop()
            currentMode = detectedMode
            logger = ARESDataLogger(currentMode)
            ntTelemetry?.putString("OpMode", currentMode)
        }
        
        // Log the complete frame asynchronously using the GC-free map pool only if interval elapsed
        if (now - lastLogTimeMs >= minLogIntervalMs) {
            lastLogTimeMs = now
            currentFrame["TimestampMs"] = now
            currentFrame["OpMode"] = currentMode
            
            val map = logger.obtainMap()
            map.putAll(currentFrame)
            logger.logFrame(map)
            currentFrame.clear()
        }
        
        // Forward the update trigger to live streaming network tables (only on NT-enabled frames)
        if (ntEnabled) ntTelemetry?.update()
    }

    /**
     * Shuts down background logging workers and standard telemetry server gracefully.
     */
    override fun close() {
        logger.stop()
        ntTelemetry?.close()
    }
}
