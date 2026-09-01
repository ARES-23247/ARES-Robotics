package com.areslib.e2e.tier2.hardware

import com.areslib.control.safety.BrownoutGuard
import com.areslib.control.safety.CurrentBudgetManager
import com.areslib.ftc.MockDcMotorEx
import com.areslib.ftc.hardware.AnalogVoltageInput
import com.areslib.ftc.hardware.FtcFloodgateCurrentSensor
import com.areslib.util.RobotClock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MockAnalogInput : AnalogVoltageInput {
    var mockVoltage: Double = 0.0
    override val voltage: Double
        get() = mockVoltage
}

class HardwareBoundsTier2Test {

    @Test
    fun testBrownoutGuardBatteryVoltageCompensationLimits() {
        val brownout = BrownoutGuard.ftcDefaults()

        // 1. Extreme high voltage (e.g. 15.0V) -> scale should be 1.0 (uncut)
        brownout.update(15.0)
        assertEquals(1.0, brownout.powerScale, 1e-6)

        // 2. Exact warning boundary -> warning voltage is 10.0V.
        // Voltage just above warning (10.1V) -> scale should be 1.0
        brownout.update(10.1)
        assertEquals(1.0, brownout.powerScale, 1e-6)

        // Voltage just below warning (9.9V) -> scale should scale down linearly
        brownout.update(9.9)
        assertTrue(brownout.powerScale < 1.0)
        assertTrue(brownout.powerScale > 0.4)

        // 3. Extreme low voltage (e.g. 5.0V) -> scale should clamp to critical minimum (0.0)
        brownout.update(5.0)
        assertEquals(0.0, brownout.powerScale, 1e-6)
    }

    @Test
    fun testFloodgateThermalLoadCalculationsAtExactCurrentBorders() {
        RobotClock.useMockTime(0L)
        try {
            val analog = MockAnalogInput()
            val sensor = FtcFloodgateCurrentSensor(
                analogInput = analog,
                maxCurrentAmps = 80.0,
                filterAlpha = 1.0,
                fuseRatingAmps = 20.0
            )

            analog.mockVoltage = 0.825 // Exactly 20 A.
            sensor.update()
            RobotClock.useMockTime(10_000L)
            sensor.update()
            assertEquals(20.0, sensor.instantaneousCurrent, 1e-6)
            assertEquals(0.0, sensor.fuseThermalLoadPercent, 1e-6, "Rated current must not accumulate overload damage")

            sensor.resetTracker()
            analog.mockVoltage = 1.65 // 40 A: the default calibration point is 2 seconds.
            sensor.update()
            RobotClock.useMockTime(12_000L)
            sensor.update()
            assertEquals(100.0, sensor.fuseThermalLoadPercent, 1e-6)
            assertTrue(sensor.isOverloadWarning(18.0))
        } finally {
            RobotClock.useSystemTime()
        }
    }

    @Test
    fun `invalid floodgate sample does not poison filter and later sample recovers`() {
        val analog = MockAnalogInput()
        val sensor = FtcFloodgateCurrentSensor(analog, maxCurrentAmps = 80.0, filterAlpha = 1.0)

        analog.mockVoltage = Double.NaN
        sensor.update()
        assertFalse(sensor.isReadingValid)
        assertEquals(0.0, sensor.current, 1e-9)
        assertTrue(sensor.fuseThermalLoadPercent.isFinite())

        analog.mockVoltage = 0.825
        sensor.update()
        assertTrue(sensor.isReadingValid)
        assertEquals(20.0, sensor.current, 1e-9)
        assertTrue(sensor.fuseThermalLoadPercent.isFinite())
    }
}
