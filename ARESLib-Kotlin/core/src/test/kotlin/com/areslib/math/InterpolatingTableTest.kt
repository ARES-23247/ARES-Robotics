package com.areslib.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InterpolatingTableTest {

    data class ShotParameters(val rpm: Double, val cowlAngle: Double) : Interpolatable<ShotParameters> {
        override fun interpolate(other: ShotParameters, ratio: Double): ShotParameters {
            return ShotParameters(
                rpm = this.rpm + (other.rpm - this.rpm) * ratio,
                cowlAngle = this.cowlAngle + (other.cowlAngle - this.cowlAngle) * ratio
            )
        }
    }

    @Test
    fun `test interpolation table operations`() {
        val table = InterpolatingTable<Double, ShotParameters>()
        
        // Setup table with calibrated data points
        table.put(1.0, ShotParameters(1000.0, 10.0))
        table.put(2.0, ShotParameters(2000.0, 20.0))
        table.put(3.0, ShotParameters(3000.0, 30.0))

        // Exact match
        val exact = table.get(2.0)
        assertEquals(2000.0, exact?.rpm)
        assertEquals(20.0, exact?.cowlAngle)

        // Interpolated midpoint (1.5)
        val mid = table.get(1.5)
        assertEquals(1500.0, mid?.rpm)
        assertEquals(15.0, mid?.cowlAngle)

        // Extrapolate floor (clamp to closest)
        val under = table.get(0.5)
        assertEquals(1000.0, under?.rpm)
        assertEquals(10.0, under?.cowlAngle)

        // Extrapolate ceiling (clamp to closest)
        val over = table.get(4.0)
        assertEquals(3000.0, over?.rpm)
        assertEquals(30.0, over?.cowlAngle)
    }

    @Test
    fun `test empty table returns null`() {
        val table = InterpolatingTable<Double, ShotParameters>()
        assertNull(table.get(2.5))
    }
}
