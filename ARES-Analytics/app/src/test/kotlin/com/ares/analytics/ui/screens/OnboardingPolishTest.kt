package com.ares.analytics.ui.screens

import com.ares.analytics.viewmodel.OnboardingFieldErrors
import com.ares.analytics.viewmodel.OnboardingState
import com.ares.analytics.viewmodel.OnboardingStep
import com.ares.analytics.viewmodel.ProjectSetupMode
import com.ares.analytics.viewmodel.canSelectOnboardingStep
import com.ares.analytics.viewmodel.defaultProjectsParentDirectory
import com.ares.analytics.viewmodel.isCreatableProjectsParentDirectory
import com.ares.analytics.viewmodel.validateOnboardingFields
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingPolishTest {

    @Test
    fun `defaultProjectsParentDirectory resolves ARESStudioProjects under user home`() {
        val tmpHome = Files.createTempDirectory("ares-onboarding-home").toFile()
        try {
            val resolved = defaultProjectsParentDirectory(tmpHome.absolutePath)
            assertEquals(File(tmpHome, "ARESStudioProjects").path, resolved)
            assertEquals("", defaultProjectsParentDirectory("   "))
        } finally {
            tmpHome.deleteRecursively()
        }
    }

    @Test
    fun `isCreatableProjectsParentDirectory accepts existing folder or direct child of existing home`() {
        val tmpHome = Files.createTempDirectory("ares-onboarding-parent").toFile()
        try {
            assertTrue(isCreatableProjectsParentDirectory(tmpHome.absolutePath))

            val directChild = File(tmpHome, "ARESStudioProjects")
            assertFalse(directChild.exists())
            assertTrue(isCreatableProjectsParentDirectory(directChild.absolutePath))

            val deepMissing = File(File(tmpHome, "missingParent"), "ARESStudioProjects")
            assertFalse(isCreatableProjectsParentDirectory(deepMissing.absolutePath))

            val state = OnboardingState(
                projectSetupMode = ProjectSetupMode.CREATE_NEW,
                projectParentPath = directChild.absolutePath,
                projectFolderName = "SeasonRobot",
                projectPath = File(directChild, "SeasonRobot").path,
                teamId = "23247",
                seasonId = "2026",
                robotId = "alpha",
                robotName = "Alpha",
            )
            val errors = validateOnboardingFields(state, throughStep = OnboardingStep.ROBOT)
            assertNull(errors.projectPath)
        } finally {
            tmpHome.deleteRecursively()
        }
    }

    @Test
    fun `canSelectOnboardingStep gates forward jumps on readiness and respects example mode`() {
        val unready = OnboardingState(
            currentStep = OnboardingStep.PROJECT,
            projectPath = "",
            fieldErrors = OnboardingFieldErrors(projectPath = "Required"),
        )
        assertTrue(canSelectOnboardingStep(unready, OnboardingStep.PROJECT))
        assertFalse(canSelectOnboardingStep(unready, OnboardingStep.ROBOT))
        assertFalse(canSelectOnboardingStep(unready, OnboardingStep.REVIEW))

        val projectReady = OnboardingState(
            currentStep = OnboardingStep.PROJECT,
            projectPath = "/tmp/ARESStudioProjects/Bot",
            fieldErrors = OnboardingFieldErrors(),
        )
        assertTrue(canSelectOnboardingStep(projectReady, OnboardingStep.ROBOT))
        assertFalse(canSelectOnboardingStep(projectReady, OnboardingStep.OPTIONAL))

        val robotReady = projectReady.copy(
            currentStep = OnboardingStep.ROBOT,
            teamId = "23247",
            seasonId = "2026",
            robotId = "bot",
            robotName = "Bot",
        )
        assertTrue(canSelectOnboardingStep(robotReady, OnboardingStep.OPTIONAL))
        assertTrue(canSelectOnboardingStep(robotReady, OnboardingStep.REVIEW))

        val exampleMode = projectReady.copy(
            currentStep = OnboardingStep.REVIEW,
            projectSetupMode = ProjectSetupMode.EXPLORE_LIGHTBOT,
        )
        assertTrue(canSelectOnboardingStep(exampleMode, OnboardingStep.PROJECT))
        assertFalse(canSelectOnboardingStep(exampleMode, OnboardingStep.ROBOT))
        assertFalse(canSelectOnboardingStep(exampleMode, OnboardingStep.OPTIONAL))
        assertTrue(canSelectOnboardingStep(exampleMode, OnboardingStep.REVIEW))
    }
}
