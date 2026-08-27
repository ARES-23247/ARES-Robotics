package com.areslib.ftc.telemetry

/**
 * Single-loop accumulator for FTC phase timings and overrun counts.
 *
 * Callers supply monotonic nanosecond boundaries, normally from `RobotClock.nanoTime`. Published
 * durations are milliseconds. A total loop strictly greater than 25 ms increments the cumulative
 * overrun counter; the counter resets only with a new profiler instance.
 */
class FtcLoopProfiler {
    var profBulkCacheMs = 0.0
        private set
    var profHardwareInputsMs = 0.0
        private set
    var profPinpointMs = 0.0
        private set
    var profVisionMs = 0.0
        private set

    private var loopOverrunCount = 0

    /** Replaces the latest already-converted sensor phase durations in milliseconds. */
    fun recordSensorsProfiling(bulkMs: Double, inputsMs: Double, pinpointMs: Double, visionMs: Double) {
        profBulkCacheMs = bulkMs
        profHardwareInputsMs = inputsMs
        profPinpointMs = pinpointMs
        profVisionMs = visionMs
    }

    /** Publishes the latest sensor phase durations without reading hardware. */
    fun publishSensorsProfiling(telemetryManager: FtcTelemetryManager) {
        val dl = telemetryManager.dataLoggingTelemetry
        dl.putNumber("Profiling/BulkCacheClear_ms", profBulkCacheMs)
        dl.putNumber("Profiling/HardwareInputs_ms", profHardwareInputsMs)
        dl.putNumber("Profiling/Pinpoint_ms", profPinpointMs)
        dl.putNumber("Profiling/Vision_ms", profVisionMs)
    }

    /** Converts ordered nanosecond boundaries [t0] through [t4] and publishes phase durations. */
    fun recordAndPublishLoopDiagnostics(
        telemetryManager: FtcTelemetryManager,
        t0: Long,
        t1: Long,
        t2: Long,
        t3: Long,
        t4: Long
    ) {
        val dl = telemetryManager.dataLoggingTelemetry
        val totalTimeMs = (t4 - t0) / 1_000_000.0
        if (totalTimeMs > 25.0) {
            loopOverrunCount++
        }
        dl.putNumber("Diagnostics/LoopOverruns", loopOverrunCount.toDouble())
        dl.putNumber("Profiling/ReadSensors_ms", (t1 - t0) / 1_000_000.0)
        dl.putNumber("Profiling/PowerManager_ms", (t2 - t1) / 1_000_000.0)
        dl.putNumber("Profiling/Subsystems_ms", (t3 - t2) / 1_000_000.0)
        dl.putNumber("Profiling/Telemetry_ms", (t4 - t3) / 1_000_000.0)
        dl.putNumber("Profiling/Total_ms", totalTimeMs)
    }
}
