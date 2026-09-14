package com.areslib.ftc

import com.areslib.Store
import com.areslib.hardware.sensor.ImuIO
import com.areslib.hardware.sensor.ImuInputs

/** Samples once at the sensor boundary and owns the validated IMU cache's fallback policy. */
internal class FtcImuCache(private val store: Store, private val cachedImuInputs: ImuInputs) {
    private val imuSampleBuffer = ImuInputs()

    fun refresh(imu: ImuIO?) {
        if (imu == null) {
            invalidateCachedImu()
            return
        }

        try {
            imu.updateInputs(imuSampleBuffer)
            // A new asynchronous sample can arrive after the frame timestamp was captured.
            // Validate against the time of consumption, not the earlier frame boundary.
            val sampleAgeMs = com.areslib.util.RobotClock.currentTimeMillis() - imuSampleBuffer.timestampMs
            val valid = imuSampleBuffer.timestampMs > 0L && sampleAgeMs in 0..IMU_MAX_SAMPLE_AGE_MS &&
                imuSampleBuffer.headingRadians.isFinite() && imuSampleBuffer.pitchRadians.isFinite() &&
                imuSampleBuffer.rollRadians.isFinite() && imuSampleBuffer.yawVelocityRadPerSec.isFinite() &&
                imuSampleBuffer.pitchVelocityRadPerSec.isFinite() && imuSampleBuffer.rollVelocityRadPerSec.isFinite()
            if (!valid) {
                invalidateCachedImu()
                return
            }
            cachedImuInputs.headingRadians = imuSampleBuffer.headingRadians
            cachedImuInputs.pitchRadians = imuSampleBuffer.pitchRadians
            cachedImuInputs.rollRadians = imuSampleBuffer.rollRadians
            cachedImuInputs.yawVelocityRadPerSec = imuSampleBuffer.yawVelocityRadPerSec
            cachedImuInputs.pitchVelocityRadPerSec = imuSampleBuffer.pitchVelocityRadPerSec
            cachedImuInputs.rollVelocityRadPerSec = imuSampleBuffer.rollVelocityRadPerSec
            cachedImuInputs.timestampMs = imuSampleBuffer.timestampMs
        } catch (_: Throwable) {
            invalidateCachedImu()
        }
    }

    private fun invalidateCachedImu() {
        cachedImuInputs.headingRadians = store.state.drive.poseEstimator.estimatedPoseHeading
        cachedImuInputs.pitchRadians = 0.0
        cachedImuInputs.rollRadians = 0.0
        cachedImuInputs.yawVelocityRadPerSec = 0.0
        cachedImuInputs.pitchVelocityRadPerSec = 0.0
        cachedImuInputs.rollVelocityRadPerSec = 0.0
        cachedImuInputs.timestampMs = 0L
    }

    private companion object {
        const val IMU_MAX_SAMPLE_AGE_MS = 100L
    }
}
