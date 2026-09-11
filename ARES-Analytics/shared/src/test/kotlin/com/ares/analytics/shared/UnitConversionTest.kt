package com.ares.analytics.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnitConversionTest {

    @Test
    fun `identity conversion preserves extreme values and signed zero`() {
        for (unit in RobotUnit.entries) {
            for (value in listOf(Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE, -0.0)) {
                assertEquals(value.toBits(), UnitConversion.convert(value, unit, unit).toBits(),
                    "Identity conversion must preserve $value in $unit")
            }
        }
    }

    @Test
    fun `temperature conversion avoids intermediate overflow`() {
        assertEquals(5e307, UnitConversion.convert(9e307, RobotUnit.FAHRENHEIT, RobotUnit.CELSIUS), 1e292)
        assertEquals(9e307, UnitConversion.convert(5e307, RobotUnit.CELSIUS, RobotUnit.FAHRENHEIT), 1e292)
        assertEquals(100.0, UnitConversion.convert(212.0, RobotUnit.FAHRENHEIT, RobotUnit.CELSIUS), 1e-12)
        assertEquals(32.0, UnitConversion.convert(273.15, RobotUnit.KELVIN, RobotUnit.FAHRENHEIT), 1e-12)
    }

    @Test
    fun `subnormal conversion does not underflow through the base unit`() {
        // One inch is 2.54 centimeters; the representable result must not become zero.
        assertEquals(Double.MIN_VALUE * 2.54,
            UnitConversion.convert(Double.MIN_VALUE, RobotUnit.INCH, RobotUnit.CENTIMETER))
    }

    @Test
    fun `linear velocity is dimensionally distinct from length`() {
        assertEquals(3.280839895, UnitConversion.convert(1.0, RobotUnit.METER_PER_SEC, RobotUnit.FOOT_PER_SEC), 1e-8)
        assertFailsWith<IllegalArgumentException> {
            UnitConversion.convert(1.0, RobotUnit.METER_PER_SEC, RobotUnit.METER)
        }
    }

    @Test
    fun `canonical telemetry key units use internal radians and milliseconds`() {
        assertEquals(RobotUnit.METER_PER_SEC, UnitConversion.detectUnitFromKey("Drive/Velocity"))
        assertEquals(RobotUnit.RADIAN, UnitConversion.detectUnitFromKey("Drive/Pose_Heading"))
        assertEquals(RobotUnit.RADIAN, UnitConversion.detectUnitFromKey("Vision/YawRad"))
        assertEquals(RobotUnit.MILLISECOND, UnitConversion.detectUnitFromKey("Profiling/LoopTimeMs"))
        assertEquals(RobotUnit.METER, UnitConversion.detectUnitFromKey("Drive/Pose_X"))
        assertEquals(null, UnitConversion.detectUnitFromKey("SysId/Command"))
        assertEquals(null, UnitConversion.detectUnitFromKey("System/Status"))
    }
}
