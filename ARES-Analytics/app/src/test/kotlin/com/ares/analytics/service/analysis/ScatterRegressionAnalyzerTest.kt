package com.ares.analytics.service.analysis

import com.ares.analytics.shared.models.League
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScatterRegressionAnalyzerTest {

    @Test
    fun `battery internal resistance regression estimates R_int in milliohms and open circuit voltage`() {
        // V_batt = 12.75 - 0.0215 * I_total  => R_int = 21.5 mOhm, V_oc = 12.75 V
        val currentSamples = (0..120).map { i ->
            val current = 5.0 + (i % 40) * 3.0 // 5 A .. 122 A
            TimedScalarSample(timestampMs = i * 20L, value = current)
        }
        val voltageSamples = currentSamples.map { sample ->
            TimedScalarSample(
                timestampMs = sample.timestampMs,
                value = 12.75 - 0.0215 * sample.value,
            )
        }

        val analysis = ScatterRegressionAnalyzer.analyzeBatteryInternalResistance(
            currentSamples = currentSamples,
            voltageSamples = voltageSamples,
            league = League.FRC,
        )
        assertNotNull(analysis)
        assertEquals(21.5, analysis.internalResistanceMilliOhms, 0.2)
        assertEquals(12.75, analysis.openCircuitVoltage, 0.02)
        assertTrue(analysis.regression.rSquared > 0.999)
        assertEquals(BatteryResistanceGrade.FAIR, analysis.grade)
    }

    @Test
    fun `xy scatter aligns offset timestamps and computes negative torque speed trendline`() {
        // Motor free speed 6000 RPM at 2 A, stall 0 RPM at 122 A => slope = -50 RPM/A
        val current = (0..60).map { i ->
            TimedScalarSample(timestampMs = i * 20L, value = 2.0 + i * 2.0)
        }
        val rpm = (0..60).map { i ->
            TimedScalarSample(timestampMs = i * 20L + 5L, value = 6000.0 - (i * 2.0) * 50.0)
        }

        val scatter = ScatterRegressionAnalyzer.analyzePair(
            xSamples = current,
            ySamples = rpm,
            maxToleranceMs = 25L,
        )
        assertTrue(scatter.points.size >= 58)
        assertEquals(-50.0, scatter.slope, 0.5)
        assertEquals(6100.0, scatter.intercept, 25.0)
        assertTrue(scatter.rSquared > 0.995)
    }
}
