package com.areslib.control.assist

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SysIdManagerTest {

    @Test
    fun testQuasistaticRampAndClamp() {
        val manager = SysIdManager()
        
        // Start Quasistatic
        manager.start(SysIdMechanism.LINEAR, SysIdRoutine.QUASISTATIC, 1000L, 0.0, 0.0, 0.0)
        assertTrue(manager.isActive())
        assertEquals(SysIdMechanism.LINEAR, manager.activeMechanism)
        
        // Update at t = 1s (elapsed: 1s). Voltage should be 1.2 * 1.0 = 1.2V
        val v1 = manager.update(2000L, 1.0)
        assertEquals(1.2, v1, 1e-6)
        
        // Update at t = 3.0s (elapsed: 3.0s). Voltage should be in reverse ramp: -1.2 * (3.0 - 2.5) = -0.6V
        val vRev = manager.update(4000L, -1.0)
        assertEquals(-0.6, vRev, 1e-6)
    }

    @Test
    fun testDynamicStep() {
        val manager = SysIdManager()
        
        // Dynamic Forward-then-reverse
        manager.start(SysIdMechanism.LINEAR, SysIdRoutine.DYNAMIC, 1000L, 0.0, 0.0, 0.0)
        val v1 = manager.update(2000L, 2.0)
        assertEquals(3.0, v1, 1e-6)
        
        // Dynamic Reverse part (elapsed: 2s)
        val v2 = manager.update(3000L, -2.0)
        assertEquals(-3.0, v2, 1e-6)
    }

    @Test
    fun testSafetyLinearLimit() {
        val manager = SysIdManager()
        manager.start(SysIdMechanism.LINEAR, SysIdRoutine.DYNAMIC, 1000L, 0.0, 0.0, 0.0)
        
        // Under limit (1.0m)
        assertTrue(manager.checkSafety(1.0, 0.0, 0.0, 2000L))
        
        // Over limit (1.6m)
        assertFalse(manager.checkSafety(1.6, 0.0, 0.0, 3000L))
    }

    @Test
    fun testSafetyAngularLimit() {
        val manager = SysIdManager()
        manager.start(SysIdMechanism.ANGULAR, SysIdRoutine.DYNAMIC, 1000L, 0.0, 0.0, 0.0)
        
        // Rotate 1 turn (2pi) - Safe
        assertTrue(manager.checkSafety(0.0, 0.0, Math.PI, 2000L))
        assertTrue(manager.checkSafety(0.0, 0.0, 0.0, 3000L))
        
        // Rotate more than 2 turns (accumulate delta)
        assertTrue(manager.checkSafety(0.0, 0.0, Math.PI, 4000L))
        assertTrue(manager.checkSafety(0.0, 0.0, 0.0, 5000L))
        
        // Accumulated delta should now exceed 4pi (each cycle is PI, so 5 * PI = 15.7 rad)
        assertFalse(manager.checkSafety(0.0, 0.0, Math.PI, 6000L))
    }

    @Test
    fun testSafetyTimeLimit() {
        val manager = SysIdManager()
        manager.start(SysIdMechanism.LINEAR, SysIdRoutine.DYNAMIC, 1000L, 0.0, 0.0, 0.0)
        
        // Under 5s
        assertTrue(manager.checkSafety(0.1, 0.0, 0.0, 5000L))
        
        // Over 5s
        assertFalse(manager.checkSafety(0.1, 0.0, 0.0, 6001L))
    }

    @Test
    fun testStopAndInactiveState() {
        val manager = SysIdManager()
        // Inactive safety should be true
        assertTrue(manager.checkSafety(1.0, 1.0, 1.0, 1000L))
        // Inactive update should be 0.0
        assertEquals(0.0, manager.update(1000L, 1.0))

        // Start and Stop
        manager.start(SysIdMechanism.LINEAR, SysIdRoutine.DYNAMIC, 1000L, 0.0, 0.0, 0.0)
        assertTrue(manager.isActive())
        manager.stop()
        assertFalse(manager.isActive())
        assertEquals(0.0, manager.currentVoltage)
    }

    @Test
    fun `nonfinite pose aborts active safety check`() {
        val manager = SysIdManager()
        manager.start(SysIdMechanism.LINEAR, SysIdRoutine.DYNAMIC, 1000L, 0.0, 0.0, 0.0)

        assertFalse(manager.checkSafety(Double.NaN, 0.0, 0.0, 1100L))
        assertFalse(manager.isActive())
        assertEquals(0.0, manager.currentVoltage)
    }

    @Test
    fun `nonfinite initial pose refuses to start routine`() {
        val manager = SysIdManager()
        manager.start(SysIdMechanism.LINEAR, SysIdRoutine.DYNAMIC, 1000L, Double.NaN, 0.0, 0.0)
        assertFalse(manager.isActive())
        assertEquals(0.0, manager.currentVoltage)
    }

    @Test
    fun `nonfinite velocity or decreasing timestamp aborts update`() {
        val manager = SysIdManager()
        manager.start(SysIdMechanism.LINEAR, SysIdRoutine.DYNAMIC, 1000L, 0.0, 0.0, 0.0)
        assertEquals(0.0, manager.update(1100L, Double.NaN))
        assertFalse(manager.isActive())

        manager.start(SysIdMechanism.LINEAR, SysIdRoutine.DYNAMIC, 2000L, 0.0, 0.0, 0.0)
        manager.update(2100L, 1.0)
        assertEquals(0.0, manager.update(2050L, 1.0))
        assertFalse(manager.isActive())
    }
}
