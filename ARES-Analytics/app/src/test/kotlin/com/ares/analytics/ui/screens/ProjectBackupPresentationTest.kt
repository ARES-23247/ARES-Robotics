package com.ares.analytics.ui.screens

import com.ares.analytics.service.versioncontrol.ProjectBackupPlan
import com.ares.analytics.service.versioncontrol.ProjectChange
import com.ares.analytics.service.versioncontrol.ProjectChangeKind
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectBackupPresentationTest {
    @Test fun `history status distinguishes unsaved projects from a saved clean version`() {
        val plan = ProjectBackupPlan("robot", false, null, emptyList(), emptyList(), null, null, null, null)
        assertEquals("LOCAL • Version history has not been started", projectLocalHistoryStatus(plan))
        assertEquals("LOCAL • Save your first local version", projectLocalHistoryStatus(plan.copy(initialized = true)))
        val saved = plan.copy(initialized = true, lastCommit = "12345678abcdef")
        assertEquals("LOCAL • Saved — working files match version 12345678", projectLocalHistoryStatus(saved))
        assertEquals("LOCAL • 1 unsaved file change", projectLocalHistoryStatus(saved.copy(changes = listOf(ProjectChange("robot.ares", ProjectChangeKind.MODIFIED)))))
    }
}
