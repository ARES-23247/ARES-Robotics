package com.ares.analytics.ui.screens

import com.ares.analytics.viewmodel.OnboardingState
import com.ares.analytics.viewmodel.OnboardingStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnboardingPresentationTest {
    @Test fun `Java readiness cannot be displayed as Python readiness after changing leagues`() {
        val state = OnboardingState(league = com.ares.analytics.shared.models.League.XRP,
            javaEnvLeague = com.ares.analytics.shared.models.League.FTC, javaEnvValid = true, javaEnvMsg = "JDK 21 is ready")
        assertEquals("Python host tools have not been checked yet.", onboardingBuildToolsMessage(state))
        kotlin.test.assertFalse(state.areBuildToolsCurrent)
    }

    @Test fun `saving status has a fallback and hides stale progress after completion or failure`() {
        assertEquals("Saving your workspace…", onboardingProgressMessage(OnboardingState(isSaving = true)))
        assertEquals("Extracting verified starter…", onboardingProgressMessage(OnboardingState(isSaving = true, projectCreationMessage = "Extracting verified starter…")))
        assertEquals("Saving your workspace…", onboardingProgressMessage(OnboardingState(isSaving = true, projectCreationMessage = "  ")))
        assertNull(onboardingProgressMessage(OnboardingState(projectCreationMessage = "Old attempt")))
    }

    @Test fun `finish action describes the selected project mode`() {
        assertEquals("Open project", onboardingFinishLabel(com.ares.analytics.viewmodel.ProjectSetupMode.OPEN_EXISTING))
        assertEquals("Create standalone project", onboardingFinishLabel(com.ares.analytics.viewmodel.ProjectSetupMode.CREATE_NEW))
        assertEquals("Create Lightbot copy", onboardingFinishLabel(com.ares.analytics.viewmodel.ProjectSetupMode.EXPLORE_LIGHTBOT))
        assertEquals("Create BIOBUZZ copy", onboardingFinishLabel(com.ares.analytics.viewmodel.ProjectSetupMode.EXPLORE_BIOBUZZ))
    }

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
