package com.areslib.util

import com.areslib.action.RobotAction
import com.areslib.state.DriveMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobotClockContractTest {

    @AfterEach
    fun restoreSystemClock() {
        RobotClock.useSystemTime()
    }

    @Test
    fun `mock milliseconds and nanoseconds share one exact timeline`() {
        RobotClock.useSystemTime()
        RobotClock.useMockTime(1_234L)

        assertTrue(RobotClock.isMocked)
        assertEquals(1_234L, RobotClock.currentTimeMillis())
        assertEquals(1_234_000_000L, RobotClock.nanoTime())

        RobotClock.setMockTimeMs(9_876L)
        assertEquals(9_876L, RobotClock.currentTimeMillis())
        assertEquals(9_876_000_000L, RobotClock.nanoTime())
    }

    @Test
    fun `default action timestamps are captured from RobotClock at construction`() {
        RobotClock.useMockTime(100L)
        val action = RobotAction.SetDriveMode(DriveMode.HEADING_HOLD)

        RobotClock.useMockTime(200L)

        assertEquals(100L, action.timestampMs)
        assertEquals(200L, RobotClock.currentTimeMillis())
    }

    @Test
    fun `useSystemTime exits mock mode`() {
        RobotClock.useMockTime(42L)
        RobotClock.useSystemTime()

        assertFalse(RobotClock.isMocked)
    }
}
