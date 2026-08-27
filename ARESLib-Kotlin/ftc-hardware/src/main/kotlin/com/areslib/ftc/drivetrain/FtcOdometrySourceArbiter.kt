package com.areslib.ftc.drivetrain

/** Odometry source currently responsible for advancing the FTC pose estimator. */
enum class FtcOdometrySource {
    UNINITIALIZED,
    PINPOINT,
    DRIVETRAIN_FALLBACK
}

/**
 * Allocation-free source selector for FTC localization.
 *
 * A primary failure switches to drivetrain odometry immediately. Pinpoint must then
 * produce several consecutive healthy samples before it is allowed to resume, which
 * prevents an intermittent I2C connection from rapidly toggling the estimator source.
 */
class FtcOdometrySourceArbiter(
    private val recoverySamplesRequired: Int = 5
) {
    var activeSource: FtcOdometrySource = FtcOdometrySource.UNINITIALIZED
        private set

    var healthyRecoverySamples: Int = 0
        private set

    fun update(pinpointPresent: Boolean, pinpointHealthy: Boolean): FtcOdometrySource {
        if (!pinpointPresent) {
            healthyRecoverySamples = 0
            activeSource = FtcOdometrySource.DRIVETRAIN_FALLBACK
            return activeSource
        }

        when (activeSource) {
            FtcOdometrySource.UNINITIALIZED -> {
                activeSource = if (pinpointHealthy) {
                    FtcOdometrySource.PINPOINT
                } else {
                    FtcOdometrySource.DRIVETRAIN_FALLBACK
                }
            }

            FtcOdometrySource.PINPOINT -> {
                if (!pinpointHealthy) {
                    healthyRecoverySamples = 0
                    activeSource = FtcOdometrySource.DRIVETRAIN_FALLBACK
                }
            }

            FtcOdometrySource.DRIVETRAIN_FALLBACK -> {
                if (pinpointHealthy) {
                    healthyRecoverySamples++
                    if (healthyRecoverySamples >= recoverySamplesRequired.coerceAtLeast(1)) {
                        healthyRecoverySamples = 0
                        activeSource = FtcOdometrySource.PINPOINT
                    }
                } else {
                    healthyRecoverySamples = 0
                }
            }
        }

        return activeSource
    }

    fun forceFallback() {
        healthyRecoverySamples = 0
        activeSource = FtcOdometrySource.DRIVETRAIN_FALLBACK
    }

    fun reset() {
        healthyRecoverySamples = 0
        activeSource = FtcOdometrySource.UNINITIALIZED
    }
}
