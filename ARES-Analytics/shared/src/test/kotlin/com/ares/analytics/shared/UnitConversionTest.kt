package com.ares.analytics.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnitConversionTest {

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
