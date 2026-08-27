package com.ares.analytics.ui

import org.junit.Assert.*
import org.junit.Test

class MatchStrategyAnalyticsTest {

    data class DriverCoachingCard(
        val title: String,
        val detail: String,
        val category: String,
        val severity: String
    )

    @Test
    fun testCoachingCardSeverityFilter() {
        val cards = listOf(
            DriverCoachingCard("Voltage Drop", "Aggressive accel caused 1.4V drop", "VOLTAGE", "WARNING"),
            DriverCoachingCard("Position Hold", "Position hold precision 0.012m", "POSITION_HOLD", "EXCELLENT"),
            DriverCoachingCard("Vision", "AprilTag alignment 320ms", "PATHING", "TIP")
        )

        val warnings = cards.filter { it.severity == "WARNING" }
        val excellents = cards.filter { it.severity == "EXCELLENT" }

        assertEquals(1, warnings.size)
        assertEquals(1, excellents.size)
        assertEquals("Voltage Drop", warnings[0].title)
    }
}
