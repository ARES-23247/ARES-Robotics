package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.shared.models.League
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformHealthPresentationTest {
    @Test
    fun `XRP battery policy uses configured brownout boundary`() {
        val policy = batteryVoltagePolicy(League.XRP, xrpBrownoutThresholdVolts = 4.3)

        assertEquals(HealthMetricTone.NORMAL, policy.tone(6.0))
        assertEquals(HealthMetricTone.CAUTION, policy.tone(4.5))
        assertEquals(HealthMetricTone.CRITICAL, policy.tone(4.2))
    }

    @Test
    fun `twelve volt platforms retain their battery policy`() {
        val policy = batteryVoltagePolicy(League.FTC)

        assertEquals(HealthMetricTone.NORMAL, policy.tone(12.6))
        assertEquals(HealthMetricTone.CAUTION, policy.tone(12.0))
        assertEquals(HealthMetricTone.CRITICAL, policy.tone(11.2))
    }

    @Test
    fun `controller titles are platform specific`() {
        assertEquals("Control Hub Health", controllerHealthTitle(League.FTC))
        assertEquals("RoboRIO Health", controllerHealthTitle(League.FRC))
        assertEquals("XRP Controller Health", controllerHealthTitle(League.XRP))
    }
    @Test
    fun `invalid XRP configuration uses the declared default`() {
        for (invalid in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 2.99, 6.01)) {
            val policy=batteryVoltagePolicy(League.XRP, invalid)
            assertEquals(4.3,policy.criticalBelowVolts)
            assertEquals(4.7,policy.cautionBelowVolts,0.000000000001)
        }
    }

    @Test
    fun `XRP configured endpoints and caution ceiling remain bounded`() {
        val lower=batteryVoltagePolicy(League.XRP,3.0)
        assertEquals(3.0,lower.criticalBelowVolts); assertEquals(3.4,lower.cautionBelowVolts)
        val upper=batteryVoltagePolicy(League.XRP,6.0)
        assertEquals(6.0,upper.criticalBelowVolts); assertEquals(6.0,upper.cautionBelowVolts)
        assertEquals(HealthMetricTone.NORMAL,upper.tone(6.0))
        assertEquals(HealthMetricTone.CRITICAL,upper.tone(5.999))
    }

    @Test
    fun `threshold equality and both twelve volt platforms are explicit`() {
        for (league in listOf(League.FTC,League.FRC)) {
            val policy=batteryVoltagePolicy(league)
            assertEquals(HealthMetricTone.CAUTION,policy.tone(11.5))
            assertEquals(HealthMetricTone.NORMAL,policy.tone(12.2))
        }
    }

    @Test
    fun `absent and nonfinite voltage has unknown tone on every platform`() {
        for (league in League.entries) {
            for (unknown in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
                assertEquals(HealthMetricTone.UNKNOWN,batteryVoltagePolicy(league).tone(unknown))
            }
        }
    }
}
