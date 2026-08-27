package com.ares.analytics.ui.help

import com.ares.analytics.viewmodel.robotstudio.RobotStudioAction
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStage
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageId
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageStatus
import com.ares.analytics.viewmodel.robotstudio.RobotStudioState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobotStudioLearningEvidenceTest {
    @Test
    fun `project matched studio stages map to graduation evidence`() {
        val state = RobotStudioState(
            loading = false,
            projectName = "Practice robot",
            projectPath = "C:/practice",
            stages = listOf(
                stage(RobotStudioStageId.PROJECT_IDENTITY, RobotStudioStageStatus.READY),
                stage(RobotStudioStageId.GENERATE_VERIFY, RobotStudioStageStatus.READY),
                stage(RobotStudioStageId.SIMULATE, RobotStudioStageStatus.RUNNING),
                stage(RobotStudioStageId.ANALYZE, RobotStudioStageStatus.READY),
            ),
        )

        val snapshot = state.toAcademyGraduationSnapshot()

        assertTrue(snapshot.isAvailable)
        assertTrue(snapshot.hasProjectIdentity)
        assertTrue(snapshot.hasAuthoringStagesReady)
        assertTrue(snapshot.hasVerifiedBuild)
        assertTrue(snapshot.hasRunningProjectSimulator)
        assertTrue(snapshot.hasImportedRunEvidence)
    }

    @Test
    fun `blocked verification never earns authoring or build evidence`() {
        val snapshot = RobotStudioState(
            loading = false,
            projectName = "Practice robot",
            projectPath = "C:/practice",
            stages = listOf(
                stage(RobotStudioStageId.PROJECT_IDENTITY, RobotStudioStageStatus.READY),
                stage(RobotStudioStageId.GENERATE_VERIFY, RobotStudioStageStatus.BLOCKED),
            ),
        ).toAcademyGraduationSnapshot()

        assertTrue(snapshot.hasProjectIdentity)
        assertFalse(snapshot.hasAuthoringStagesReady)
        assertFalse(snapshot.hasVerifiedBuild)
        assertFalse(snapshot.hasRunningProjectSimulator)
        assertFalse(snapshot.hasImportedRunEvidence)
    }

    @Test
    fun `missing verification stage cannot be interpreted as ready`() {
        val snapshot = RobotStudioState(
            loading = false,
            projectName = "Practice robot",
            projectPath = "C:/practice",
            stages = listOf(
                stage(RobotStudioStageId.PROJECT_IDENTITY, RobotStudioStageStatus.READY),
            ),
        ).toAcademyGraduationSnapshot()

        assertTrue(snapshot.hasProjectIdentity)
        assertFalse(snapshot.hasAuthoringStagesReady)
        assertFalse(snapshot.hasVerifiedBuild)
    }

    private fun stage(id: RobotStudioStageId, status: RobotStudioStageStatus) = RobotStudioStage(
        id = id,
        title = id.name,
        outcome = "Evidence",
        status = status,
        explanation = status.label,
        issues = emptyList(),
        storage = ".ares",
        consumer = "test",
        action = RobotStudioAction.RUN_BUILD,
        actionLabel = "Open",
    )
}
