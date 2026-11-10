package org.firstinspires.ftc.teamcode.sdk

import com.areslib.ftc.drivetrain.SparkFunOtosIO
import com.areslib.hardware.drive.OdometryInputs
import com.areslib.math.geometry.Pose2d
import com.areslib.util.RobotClock
import com.qualcomm.hardware.sparkfun.SparkFunOTOS
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/** Actual SDK 12 driver/pose types with mocked device calls; no physical OTOS or I2C access. */
class FtcSdk12OtosLifecycleIntegrationTest {
    @Before fun clock() = RobotClock.useMockTime(1_000L)
    @After fun restoreClock() = RobotClock.useSystemTime()

    private fun driver(): SparkFunOTOS = Mockito.mock(SparkFunOTOS::class.java).apply {
        Mockito.`when`(setLinearScalar(1.0)).thenReturn(true)
        Mockito.`when`(setAngularScalar(1.0)).thenReturn(true)
        Mockito.`when`(calibrateImu()).thenReturn(true)
        Mockito.`when`(getPosition()).thenReturn(SparkFunOTOS.Pose2D(0.0, 0.0, 0.0))
        Mockito.`when`(getVelocity()).thenReturn(SparkFunOTOS.Pose2D(1.0, 0.5, 0.2))
    }

    @Test fun failedFirstReadAfterResetDoesNotExposePreviousFreshMotion() {
        val driver = driver()
        val io = SparkFunOtosIO(driver)
        val inputs = OdometryInputs()
        io.updateInputs(inputs)
        assertEquals(1.0, inputs.velX, 0.0)
        RobotClock.useMockTime(1_020L)
        io.initialize(Pose2d(2.0, 3.0))
        Mockito.`when`(driver.getPosition()).thenThrow(IllegalStateException("read failed"))
        io.updateInputs(inputs)
        assertEquals(0L, inputs.timestampMs)
        assertEquals(0.0, inputs.velX, 0.0)
        assertEquals(0.0, inputs.velY, 0.0)
        assertEquals(0.0, inputs.headingVelocity, 0.0)
    }

    @Test fun failedResetSuspendsSdkReadsUntilExplicitSuccessfulInitialization() {
        val driver = driver()
        val io = SparkFunOtosIO(driver)
        Mockito.doThrow(IllegalStateException("reset acknowledgement lost")).`when`(driver).resetTracking()
        try { io.initialize(Pose2d()); fail("Reset must throw") } catch (_: IllegalStateException) {}
        Mockito.clearInvocations(driver)
        val inputs = OdometryInputs()
        io.updateInputs(inputs)
        Mockito.verifyNoInteractions(driver)
        assertEquals(0L, inputs.timestampMs)
        Mockito.doNothing().`when`(driver).resetTracking()
        io.initialize(Pose2d(2.0, 3.0))
        io.updateInputs(inputs)
        assertEquals(1_000L, inputs.timestampMs)
        assertEquals(2.0, inputs.posX, 0.0)
    }

    @Test fun closeStopsSdkCallsWithoutTakingOwnershipOfThePassedDriver() {
        val driver = driver()
        val io = SparkFunOtosIO(driver)
        Mockito.clearInvocations(driver)
        io.close()
        io.close()
        val inputs = OdometryInputs()
        io.updateInputs(inputs)
        assertEquals(0L, io.getPoseUpdate().timestampMs)
        try { io.initialize(Pose2d()); fail("Closed adapter must reject initialize") } catch (_: IllegalStateException) {}
        Mockito.verifyNoInteractions(driver)
    }
}
