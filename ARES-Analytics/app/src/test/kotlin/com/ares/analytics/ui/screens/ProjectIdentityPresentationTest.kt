package com.ares.analytics.ui.screens

import com.ares.analytics.viewmodel.project.ProjectIdentityDraft
import com.ares.analytics.viewmodel.project.ProjectIdentityEditorState
import com.ares.analytics.viewmodel.project.ProjectIdentityField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectIdentityPresentationTest {
    @Test
    fun `missing robot source explains why identity saving is unavailable`() {
        val guidance = projectIdentityReviewGuidance(
            ProjectIdentityEditorState(
                loading = false,
                projectSourceError = "No robot source",
            ),
        )

        assertEquals(
            "Saving is unavailable because the selected folder has no robot source. " +
                "Switch to a real project or create an official starter first.",
            guidance,
        )
    }

    @Test
    fun `missing dimensions explain how to enable reviewed save`() {
        val guidance = projectIdentityReviewGuidance(
            ProjectIdentityEditorState(
                loading = false,
                draft = ProjectIdentityDraft(projectId = "test-project"),
                fieldErrors = mapOf(ProjectIdentityField.ROBOT_LENGTH to "Required"),
            ),
        )

        assertEquals("Enter valid measured robot length and width above to enable review.", guidance)
    }

    @Test
    fun `ready identity does not render blocker guidance`() {
        assertNull(projectIdentityReviewGuidance(ProjectIdentityEditorState(loading = false)))
    }

    @Test
    fun `old project format explains reviewed replacement without deletion`() {
        val explanation = protectedProjectIdentityExplanation(
            "Project metadata is missing required field: authoringModel",
        )

        assertTrue(explanation.orEmpty().contains("older project format"))
        assertTrue(explanation.orEmpty().contains("do not need to delete the robot"))
    }
}
