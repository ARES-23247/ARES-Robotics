package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.RobotProfile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncEngineManifestConvergenceTest {
    private val original = RobotProfile("lightbot", League.FTC, "2026", "Lightbot")
    private val added = RobotProfile("practice", League.FTC, "2026", "Practice Robot")

    @Test
    fun `unrelated concurrent profile remains while requested addition is verified`() {
        val concurrent = RobotProfile("frc-demo", League.FRC, "2027", "FRC Demo")

        assertTrue(
            robotProfileMutationApplied(
                before = listOf(original),
                desired = listOf(original, added),
                published = listOf(original, concurrent, added),
            ),
        )
    }

    @Test
    fun `missing requested addition requires retry`() {
        assertFalse(
            robotProfileMutationApplied(
                before = listOf(original),
                desired = listOf(original, added),
                published = listOf(original),
            ),
        )
    }

    @Test
    fun `requested deletion must remain absent`() {
        assertTrue(
            robotProfileMutationApplied(
                before = listOf(original, added),
                desired = listOf(original),
                published = listOf(original),
            ),
        )
        assertFalse(
            robotProfileMutationApplied(
                before = listOf(original, added),
                desired = listOf(original),
                published = listOf(original, added),
            ),
        )
    }
}
