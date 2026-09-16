package com.areslib.input

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RumblePacerTest {

    @Test
    fun testPacerPacesBurstAndCooldown() {
        val pacer = RumblePacer(activeDurationMs = 1000L, cooldownDurationMs = 2000L)
        assertFalse(pacer.isRumbleActive)
        assertFalse(pacer.isCoolingDown)

        // Idle when no trigger
        assertEquals(0.0, pacer.update(nowMs = 10_000L, triggerRisingEdge = false, alertConditionActive = false))
        assertFalse(pacer.isRumbleActive)

        // Trigger rising edge starts 1000ms pulse
        assertEquals(1.0, pacer.update(nowMs = 10_000L, triggerRisingEdge = true, alertConditionActive = true))
        assertTrue(pacer.isRumbleActive)
        assertFalse(pacer.isCoolingDown)

        // Mid-pulse remains active
        assertEquals(1.0, pacer.update(nowMs = 10_500L, triggerRisingEdge = false, alertConditionActive = true))
        assertTrue(pacer.isRumbleActive)

        // Pulse expires at 1000ms, transitions to cooldown
        assertEquals(0.0, pacer.update(nowMs = 11_000L, triggerRisingEdge = false, alertConditionActive = true))
        assertFalse(pacer.isRumbleActive)
        assertTrue(pacer.isCoolingDown)

        // Cooldown rejects re-trigger during 2000ms quiet period
        assertEquals(0.0, pacer.update(nowMs = 12_000L, triggerRisingEdge = true, alertConditionActive = true))
        assertFalse(pacer.isRumbleActive)
        assertTrue(pacer.isCoolingDown)

        // After 2000ms cooldown expires, new trigger is accepted
        assertEquals(1.0, pacer.update(nowMs = 13_001L, triggerRisingEdge = true, alertConditionActive = true))
        assertTrue(pacer.isRumbleActive)
        assertFalse(pacer.isCoolingDown)
    }

    @Test
    fun `expired cooldown clears without another alert and stale triggers cannot rumble`() {
        val pacer = RumblePacer(100, 200)
        assertEquals(0.0, pacer.update(1_000, true, false))
        assertFalse(pacer.isRumbleActive)
        assertEquals(1.0, pacer.update(1_000, true, true))
        assertEquals(0.0, pacer.update(1_100, false, true))
        assertTrue(pacer.isCoolingDown)
        assertEquals(0.0, pacer.update(1_300, false, false))
        assertFalse(pacer.isCoolingDown)
        assertEquals(0.0, pacer.update(1_301, true, false))
        assertEquals(1.0, pacer.update(1_302, true, true))
    }

    @Test
    fun `invalid durations are rejected before polling`() {
        assertThrows(IllegalArgumentException::class.java) { RumblePacer(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { RumblePacer(-1, 100) }
        assertThrows(IllegalArgumentException::class.java) { RumblePacer(100, -1) }
    }

    @Test
    fun testEarlyAlertDeactivationCutsOffRumble() {
        val pacer = RumblePacer(activeDurationMs = 1000L, cooldownDurationMs = 2000L)
        pacer.update(nowMs = 1_000L, triggerRisingEdge = true, alertConditionActive = true)
        assertTrue(pacer.isRumbleActive)

        // Alert drops early at 200ms -> rumble immediately cuts off to 0.0 and starts cooldown
        assertEquals(0.0, pacer.update(nowMs = 1_200L, triggerRisingEdge = false, alertConditionActive = false))
        assertFalse(pacer.isRumbleActive)
        assertTrue(pacer.isCoolingDown)
    }

    @Test
    fun testClockRewindDoesNotHang() {
        val pacer = RumblePacer(activeDurationMs = 1000L, cooldownDurationMs = 2000L)
        pacer.update(nowMs = 5_000L, triggerRisingEdge = true, alertConditionActive = true)
        assertTrue(pacer.isRumbleActive)

        // Clock rewinds during active pulse -> safely deactivates
        assertEquals(0.0, pacer.update(nowMs = 4_000L, triggerRisingEdge = false, alertConditionActive = true))
        assertFalse(pacer.isRumbleActive)
        assertTrue(pacer.isCoolingDown)
    }

    @Test
    fun testResetClearsAllState() {
        val pacer = RumblePacer(activeDurationMs = 1000L, cooldownDurationMs = 2000L)
        pacer.update(nowMs = 1_000L, triggerRisingEdge = true, alertConditionActive = true)
        assertTrue(pacer.isRumbleActive)

        pacer.reset()
        assertFalse(pacer.isRumbleActive)
        assertFalse(pacer.isCoolingDown)
    }
}
