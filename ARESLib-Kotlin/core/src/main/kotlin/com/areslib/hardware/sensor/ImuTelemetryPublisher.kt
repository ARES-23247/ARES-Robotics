package com.areslib.hardware.sensor

import com.areslib.telemetry.ITelemetry

/**
 * Reusable IMU telemetry storage for one serialized publishing owner.
 *
 * The owner retains this publisher for its lifetime. Prefix changes rebuild topic strings;
 * a stable prefix reuses both topics and the input buffer. Each call samples cached inputs
 * once, then captures primitive values before invoking telemetry callbacks.
 *
 * Calls must not overlap or recurse from updateInputs. Reentrant telemetry callbacks are safe.
 * The IO must copy a coherent cache snapshot and must not retain the supplied input buffer.
 */
class ImuTelemetryPublisher(private val imu: ImuIO) {
    private val inputs = ImuInputs()
    private var cachedPrefix: String? = null
    private var headingKey = ""
    private var pitchKey = ""
    private var rollKey = ""
    private var yawVelocityKey = ""
    private var pitchVelocityKey = ""
    private var rollVelocityKey = ""

    /** Publishes six cached orientation/rate topics without steady-state buffer or key allocation. */
    fun publish(telemetry: ITelemetry, prefix: String) {
        if (cachedPrefix != prefix) {
            val heading = "$prefix/HeadingRad"
            val pitch = "$prefix/PitchRad"
            val roll = "$prefix/RollRad"
            val yawVelocity = "$prefix/YawVelocityRadPerSec"
            val pitchVelocity = "$prefix/PitchVelocityRadPerSec"
            val rollVelocity = "$prefix/RollVelocityRadPerSec"
            headingKey = heading
            pitchKey = pitch
            rollKey = roll
            yawVelocityKey = yawVelocity
            pitchVelocityKey = pitchVelocity
            rollVelocityKey = rollVelocity
            cachedPrefix = prefix
        }
        // Preserve the fresh-buffer defaults for partial providers and retries after a failed copy.
        inputs.headingRadians = 0.0
        inputs.pitchRadians = 0.0
        inputs.rollRadians = 0.0
        inputs.yawVelocityRadPerSec = 0.0
        inputs.pitchVelocityRadPerSec = 0.0
        inputs.rollVelocityRadPerSec = 0.0
        inputs.timestampMs = 0L
        imu.updateInputs(inputs)
        publishImuSnapshot(telemetry, inputs, headingKey, pitchKey, rollKey,
            yawVelocityKey, pitchVelocityKey, rollVelocityKey)
    }
}

internal fun publishImuSnapshot(
    telemetry: ITelemetry,
    inputs: ImuInputs,
    headingKey: String,
    pitchKey: String,
    rollKey: String,
    yawVelocityKey: String,
    pitchVelocityKey: String,
    rollVelocityKey: String
) {
    // Snapshot before the first callback: a sink may synchronously publish another IMU frame.
    val heading = inputs.headingRadians
    val pitch = inputs.pitchRadians
    val roll = inputs.rollRadians
    val yawVelocity = inputs.yawVelocityRadPerSec
    val pitchVelocity = inputs.pitchVelocityRadPerSec
    val rollVelocity = inputs.rollVelocityRadPerSec
    telemetry.putNumber(headingKey, heading)
    telemetry.putNumber(pitchKey, pitch)
    telemetry.putNumber(rollKey, roll)
    telemetry.putNumber(yawVelocityKey, yawVelocity)
    telemetry.putNumber(pitchVelocityKey, pitchVelocity)
    telemetry.putNumber(rollVelocityKey, rollVelocity)
}
