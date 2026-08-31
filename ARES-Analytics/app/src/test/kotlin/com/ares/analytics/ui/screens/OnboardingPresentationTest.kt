package com.ares.analytics.ui.screens

import com.ares.analytics.viewmodel.OnboardingState
import com.ares.analytics.viewmodel.OnboardingStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnboardingPresentationTest {
    @Test
    fun `project and robot steps explain their hidden required fields`() {
        assertEquals(
            "Required next: choose a parent folder and enter a new project folder name.",
            onboardingReadinessHint(OnboardingState()),
        )
        assertEquals(
            "Required next: enter the team, season, and robot ID.",
            onboardingReadinessHint(OnboardingState(currentStep = OnboardingStep.ROBOT)),
        )
        assertNull(
            onboardingReadinessHint(
                OnboardingState(
                    currentStep = OnboardingStep.ROBOT,
                    teamId = "23247",
                    seasonId = "2026",
                    robotId = "Lightbot",
                ),
            ),
        )
    }
}
