package com.areslib.hardware.actuator

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.PI

class GoBildaMotorTest {

    @Test
    fun testGoBildaMotorConversions() {
        val motor = GoBildaMotor.RPM_312

        // ticksToRotations & rotationsToTicks
        assertEquals(1.0, motor.ticksToRotations(537.7), 1e-6)
        assertEquals(537.7, motor.rotationsToTicks(1.0), 1e-6)

        // ticksToRadians & radiansToTicks
        assertEquals(2.0 * PI, motor.ticksToRadians(537.7), 1e-6)
        assertEquals(537.7, motor.radiansToTicks(2.0 * PI), 1e-6)

        // ticksToDegrees & degreesToTicks
        assertEquals(360.0, motor.ticksToDegrees(537.7), 1e-6)
        assertEquals(537.7, motor.degreesToTicks(360.0), 1e-6)

        // ticksToMeters & metersToTicks
        val diameter = 0.096 // 96mm wheel
        val expectedMeters = PI * diameter
        assertEquals(expectedMeters, motor.ticksToMeters(537.7, diameter), 1e-6)
        assertEquals(537.7, motor.metersToTicks(expectedMeters, diameter), 1e-6)
    }

    @Test
    fun testAllGearRatiosExist() {
        assertEquals("3.7:1", GoBildaMotor.RPM_1150.ratioName)
        assertEquals("5.2:1", GoBildaMotor.RPM_712.ratioName)
        assertEquals("13.7:1", GoBildaMotor.RPM_435.ratioName)
        assertEquals("19.2:1", GoBildaMotor.RPM_312.ratioName)
        assertEquals("26.9:1", GoBildaMotor.RPM_223.ratioName)
        assertEquals("50.9:1", GoBildaMotor.RPM_117.ratioName)
        assertEquals("99.5:1", GoBildaMotor.RPM_60.ratioName)
        assertEquals("139:1", GoBildaMotor.RPM_43.ratioName)
    }
}
