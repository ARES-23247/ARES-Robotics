package com.areslib.ftc.drivetrain

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class FtcOdometrySourceArbiterTest {
    @Test
    fun `primary fault switches immediately and recovery is debounced`() {
        val arbiter = FtcOdometrySourceArbiter(recoverySamplesRequired = 5)

        assertEquals(FtcOdometrySource.PINPOINT, arbiter.update(pinpointPresent = true, pinpointHealthy = true))
        assertEquals(FtcOdometrySource.DRIVETRAIN_FALLBACK, arbiter.update(true, false))

        repeat(4) {
            assertEquals(FtcOdometrySource.DRIVETRAIN_FALLBACK, arbiter.update(true, true))
        }
        assertEquals(FtcOdometrySource.PINPOINT, arbiter.update(true, true))
    }

    @Test
    fun `intermittent recovery sample restarts the debounce window`() {
        val arbiter = FtcOdometrySourceArbiter(recoverySamplesRequired = 3)
        arbiter.update(pinpointPresent = false, pinpointHealthy = false)

        assertEquals(FtcOdometrySource.DRIVETRAIN_FALLBACK, arbiter.update(true, true))
        assertEquals(FtcOdometrySource.DRIVETRAIN_FALLBACK, arbiter.update(true, false))
        assertEquals(0, arbiter.healthyRecoverySamples)
        assertEquals(FtcOdometrySource.DRIVETRAIN_FALLBACK, arbiter.update(true, true))
        assertEquals(FtcOdometrySource.DRIVETRAIN_FALLBACK, arbiter.update(true, true))
        assertEquals(FtcOdometrySource.PINPOINT, arbiter.update(true, true))
    }
}
