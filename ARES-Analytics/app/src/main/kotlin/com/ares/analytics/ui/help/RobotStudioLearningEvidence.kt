package com.ares.analytics.ui.help

import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageId
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageStatus
import com.ares.analytics.viewmodel.robotstudio.RobotStudioState

/** Converts the project-matched Robot Studio stage graph to narrow graduation evidence. */
fun RobotStudioState.toAcademyGraduationSnapshot(): AcademyGraduationSnapshot {
    val byId = stages.associateBy { it.id }
    val projectIdentity = byId[RobotStudioStageId.PROJECT_IDENTITY]?.status == RobotStudioStageStatus.READY
    val build = byId[RobotStudioStageId.GENERATE_VERIFY]
    return AcademyGraduationSnapshot(
        isAvailable = !loading && error == null && projectPath.isNotBlank(),
        hasProjectIdentity = projectIdentity,
        hasAuthoringStagesReady = projectIdentity && build != null &&
            build.status != RobotStudioStageStatus.BLOCKED && build.status != RobotStudioStageStatus.INVALID,
        hasVerifiedBuild = build?.status == RobotStudioStageStatus.READY,
        hasRunningProjectSimulator = byId[RobotStudioStageId.SIMULATE]?.status == RobotStudioStageStatus.RUNNING,
        hasImportedRunEvidence = byId[RobotStudioStageId.ANALYZE]?.status == RobotStudioStageStatus.READY,
    )
}
