package com.areslib.control.assist

import com.areslib.hardware.actuator.FlywheelIO
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlywheelSysIdAdapterTest {
    private class CachedFlywheel : FlywheelIO {
        override var velocityRpm = 60.0
        override var velocityValid = true
        var volts = 0.0
        var writes = 0
        var refreshes = 0
        override fun refresh() { refreshes++ }
        override fun setVelocityRpm(rpm: Double, maxEffortScale: Double) = Unit
        override fun setAppliedVoltage(volts: Double) { this.volts = volts; writes++ }
    }

    @Test fun `cached signed RPM becomes radians per second without hardware refresh`() {
        val io = CachedFlywheel()
        val adapter = FlywheelSysIdAdapter(io)
        assertEquals(SysIdMechanism.FLYWHEEL, adapter.mechanism)
        assertEquals(2.0 * PI, adapter.velocity, 1e-12)
        io.velocityRpm = -120.0
        assertEquals(-4.0 * PI, adapter.velocity, 1e-12)
        assertTrue(adapter.measurementValid)
        assertEquals(0, io.refreshes)
        assertEquals(0, io.writes)
    }

    @Test fun `validity requires finite cached RPM and a valid acquisition`() {
        val io = CachedFlywheel()
        val adapter = FlywheelSysIdAdapter(io)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            io.velocityRpm = invalid
            assertFalse(adapter.measurementValid)
        }
        io.velocityRpm = Double.MAX_VALUE
        assertTrue(adapter.measurementValid)
        assertTrue(adapter.velocity.isFinite())
        io.velocityValid = false
        assertFalse(adapter.measurementValid)
        io.velocityRpm = 0.0
        io.velocityValid = true
        assertTrue(adapter.measurementValid)
    }

    @Test fun `forward voltage bounds invalid requests and stop reach IO exactly once`() {
        val io = CachedFlywheel()
        val adapter = FlywheelSysIdAdapter(io)
        val requests = doubleArrayOf(-12.0, 0.0, 6.0, 12.0, 20.0, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        val expected = doubleArrayOf(0.0, 0.0, 6.0, 12.0, 12.0, 0.0, 0.0, 0.0)
        for (i in requests.indices) {
            adapter.setCharacterizationVoltage(requests[i])
            assertEquals(expected[i], io.volts)
            assertEquals(i + 1, io.writes)
        }
        adapter.setCharacterizationVoltage(6.0)
        adapter.stop()
        assertEquals(0.0, io.volts)
        assertEquals(requests.size + 2, io.writes)
        assertEquals(0, io.refreshes)
    }
}
