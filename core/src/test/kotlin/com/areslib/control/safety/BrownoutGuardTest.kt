package com.areslib.control.safety

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class BrownoutGuardTest {

    private lateinit var guard: BrownoutGuard

    @BeforeEach
    fun setUp() {
        guard = BrownoutGuard.ftcDefaults()
    }

    @Test
    fun `healthy voltage produces full power scale`() {
        guard.update(12.5)
        assertEquals(BrownoutState.HEALTHY, guard.state)
        assertEquals(1.0, guard.powerScale, 0.001)
    }

    @Test
    fun `warning voltage produces graduated power reduction`() {
        guard.update(8.75) // Between 7.5 (critical) and 10.0 (warning)
        assertEquals(BrownoutState.WARNING, guard.state)
        // 8.75 is halfway between 7.5 and 10.0 → scale should be 0.65
        // minPowerScale + ((8.75 - 7.5) / (10.0 - 7.5)) * (1.0 - 0.3) = 0.3 + 0.5 * 0.7 = 0.65
        assertEquals(0.65, guard.powerScale, 0.01)
    }

    @Test
    fun `critical voltage disables all motors`() {
        guard.update(7.0) // Below 7.5 critical threshold
        assertEquals(BrownoutState.CRITICAL, guard.state)
        assertEquals(0.0, guard.powerScale, 0.001)
    }

    @Test
    fun `normal driving voltage stays healthy`() {
        // 11V is typical under-load voltage — should NOT trigger warning
        guard.update(11.0)
        assertEquals(BrownoutState.HEALTHY, guard.state)
        assertEquals(1.0, guard.powerScale, 0.001)

        // 10.5V is aggressive driving — still healthy
        guard.update(10.5)
        assertEquals(BrownoutState.HEALTHY, guard.state)
    }

    @Test
    fun `hysteresis prevents oscillation at warning boundary`() {
        // Drop into warning
        guard.update(9.5)
        assertEquals(BrownoutState.WARNING, guard.state)

        // Recover to exactly warningVoltage — should stay WARNING due to hysteresis
        guard.update(10.0)
        assertEquals(BrownoutState.WARNING, guard.state)

        // Recover above warningVoltage + hysteresis (10.0 + 0.4 = 10.4)
        guard.update(10.5)
        assertEquals(BrownoutState.HEALTHY, guard.state)
    }

    @Test
    fun `hysteresis prevents oscillation at critical boundary`() {
        // Drop into critical
        guard.update(7.0)
        assertEquals(BrownoutState.CRITICAL, guard.state)

        // Recover to exactly criticalVoltage — should stay CRITICAL due to hysteresis
        guard.update(7.5)
        assertEquals(BrownoutState.CRITICAL, guard.state)

        // Recover above criticalVoltage + hysteresis (7.5 + 0.4 = 7.9)
        guard.update(8.0)
        assertEquals(BrownoutState.WARNING, guard.state)
    }

    @Test
    fun `trip counter increments on transitions to non-healthy`() {
        assertEquals(0, guard.tripCount)

        guard.update(9.5) // HEALTHY → WARNING
        assertEquals(1, guard.tripCount)

        guard.update(10.3) // stays WARNING (hysteresis: need > 10.4)
        guard.update(10.5) // recovers to HEALTHY
        assertEquals(1, guard.tripCount)

        guard.update(9.0) // HEALTHY → WARNING again
        assertEquals(2, guard.tripCount)
    }

    @Test
    fun `NaN voltage is rejected and state unchanged`() {
        guard.update(12.0) // establish healthy
        guard.update(Double.NaN)
        assertEquals(BrownoutState.HEALTHY, guard.state)
        assertEquals(1.0, guard.powerScale, 0.001)
        assertEquals(12.0, guard.lastVoltage, 0.001)
    }

    @Test
    fun `negative voltage is rejected`() {
        guard.update(12.0)
        guard.update(-5.0)
        assertEquals(BrownoutState.HEALTHY, guard.state)
        assertEquals(12.0, guard.lastVoltage, 0.001)
    }

    @Test
    fun `battery percent is calculated correctly`() {
        guard.update(13.0)
        assertEquals(100.0, guard.batteryPercent, 0.1)

        guard.update(6.5) // 50% of 13.0
        assertEquals(50.0, guard.batteryPercent, 0.1)
    }

    @Test
    fun `reset clears state and counters`() {
        guard.update(7.0) // critical
        assertEquals(BrownoutState.CRITICAL, guard.state)
        assertEquals(1, guard.tripCount)

        guard.reset()
        assertEquals(BrownoutState.HEALTHY, guard.state)
        assertEquals(1.0, guard.powerScale, 0.001)
        assertEquals(0, guard.tripCount)
    }

    @Test
    fun `frc defaults have lower thresholds`() {
        val frcGuard = BrownoutGuard.frcDefaults()
        assertEquals(8.5, frcGuard.warningVoltage, 0.001)
        assertEquals(6.8, frcGuard.criticalVoltage, 0.001)

        // 10V should be healthy for FRC
        frcGuard.update(10.0)
        assertEquals(BrownoutState.HEALTHY, frcGuard.state)
        assertEquals(1.0, frcGuard.powerScale, 0.001)
    }

    @Test
    fun `ftc defaults use realistic thresholds`() {
        assertEquals(10.0, guard.warningVoltage, 0.001)
        assertEquals(7.5, guard.criticalVoltage, 0.001)
    }

    @Test
    fun `warning power scale at boundary edges`() {
        // Just below warningVoltage → should enter WARNING with scale near 1.0
        guard.update(9.99)
        assertEquals(BrownoutState.WARNING, guard.state)
        assertTrue(guard.powerScale > 0.9)

        // Just above criticalVoltage → should be near minPowerScale
        val guard2 = BrownoutGuard.ftcDefaults()
        guard2.update(7.51)
        assertEquals(BrownoutState.WARNING, guard2.state)
        assertTrue(guard2.powerScale < 0.35)
    }

    @Test
    fun `graduated scaling is linear across warning band`() {
        // Test multiple points across the warning band (7.5 to 10.0)
        val voltages = listOf(8.0, 8.75, 9.5)
        val scales = voltages.map { v ->
            val g = BrownoutGuard.ftcDefaults()
            g.update(v)
            g.powerScale
        }

        // Should be monotonically increasing
        for (i in 1 until scales.size) {
            assertTrue(scales[i] > scales[i - 1],
                "Scale at ${voltages[i]}V (${scales[i]}) should be > scale at ${voltages[i-1]}V (${scales[i-1]})")
        }
    }
}
