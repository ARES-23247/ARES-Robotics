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
}
