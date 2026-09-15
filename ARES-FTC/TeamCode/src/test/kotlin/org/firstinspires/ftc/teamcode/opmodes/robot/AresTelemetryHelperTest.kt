package org.firstinspires.ftc.teamcode.opmodes.robot

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.After
import com.areslib.ftc.FtcMecanumRobot
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import org.mockito.Mockito.*
import java.util.concurrent.ConcurrentHashMap

class AresTelemetryHelperTest {
    @After fun restoreClock() = RobotClock.useSystemTime()

    private fun fixture(): Pair<FtcMecanumRobot, ConcurrentHashMap<String, String>> {
        val base = mock(FtcMecanumRobot::class.java, RETURNS_DEEP_STUBS)
        val text = ConcurrentHashMap<String, String>()
        `when`(base.telemetryManager.customDriverStationText).thenReturn(text)
        `when`(base.store.state).thenReturn(RobotState())
        `when`(base.powerManager.batteryVoltage).thenReturn(12.4)
        `when`(base.powerManager.powerScale).thenReturn(0.8)
        return base to text
    }

    @Test fun `first update publishes immediately and throttled calls read no robot state`() {
        val (base, text) = fixture()
        val helper = AresTelemetryHelper(base)
        RobotClock.useMockTime(0L)
        helper.updateTelemetry()
        assertEquals("12.4", text["Battery V"])
        assertEquals("0.8", text["Power Scale"])
        clearInvocations(base)
        RobotClock.useMockTime(99L)
        helper.updateTelemetry()
        verifyNoInteractions(base)
        RobotClock.useMockTime(100L)
        `when`(base.powerManager.batteryVoltage).thenReturn(11.41)
        helper.updateTelemetry()
        assertEquals(formatLowBatteryVoltage(11.41), text["Battery V"])
    }

    @Test fun `rewind and elapsed overflow publish then restart the throttle window`() {
        val (base, text) = fixture()
        val helper = AresTelemetryHelper(base)
        for (time in listOf(1000L, 10L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            text.clear()
            RobotClock.useMockTime(time)
            helper.updateTelemetry()
            assertEquals("at $time", 6, text.size)
            text.clear()
            helper.updateTelemetry()
            assertEquals(0, text.size)
        }
    }

    @Test fun `summary uses one immutable state snapshot`() {
        val (base, text) = fixture()
        val state = RobotState()
        `when`(base.store.state).thenReturn(state.copy(drive = state.drive.copy(
            poseEstimator = state.drive.poseEstimator.copy(estimatedPoseX = 2.0, estimatedPoseY = -3.0, estimatedPoseHeading = Math.PI / 2))))
        val store = base.store
        clearInvocations(store)
        RobotClock.useMockTime(1000L)
        AresTelemetryHelper(base).updateTelemetry()
        verify(store, times(1)).state
        assertEquals("2.0", text["EKF Pose X"])
        assertEquals("-3.0", text["EKF Pose Y"])
        assertEquals("90.0", text["EKF Pose Deg"])
        assertEquals(state.drive.alliance.name, text["Alliance"])
    }

    @Test fun `invalid voltage is explicit and custom values are capped and replaced`() {
        val (base, text) = fixture()
        for (voltage in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0)) {
            `when`(base.powerManager.batteryVoltage).thenReturn(voltage)
            RobotClock.useMockTime(1000L)
            AresTelemetryHelper(base).updateTelemetry()
            assertEquals("<font color='red'><b>INVALID</b></font>", text["Battery V"])
        }
        val helper = AresTelemetryHelper(base)
        helper.addTelemetry("custom", "x".repeat(151))
        assertEquals("x".repeat(150), text["custom"])
        helper.addTelemetry("custom", 42)
        assertEquals("42", text["custom"])
    }
    @Test
    fun `low battery warning rounds to one decimal without locale-sensitive formatting`() {
        assertEquals("<font color='red'><b>11.4V (LOW)</b></font>", formatLowBatteryVoltage(11.41))
        assertEquals("<font color='red'><b>11.5V (LOW)</b></font>", formatLowBatteryVoltage(11.46))
    }
}
