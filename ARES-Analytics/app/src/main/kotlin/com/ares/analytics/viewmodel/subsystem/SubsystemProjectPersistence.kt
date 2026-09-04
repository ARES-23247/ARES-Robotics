package com.ares.analytics.viewmodel.subsystem

import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.service.project.RemovableProjectDocumentKind
import com.ares.analytics.service.project.persistence.ProjectDocumentRemovalPlan
import com.ares.analytics.service.project.persistence.RemovedProjectDocument
import com.ares.analytics.service.project.persistence.SavedProjectRevision
import com.ares.analytics.shared.models.League
import com.areslib.controls.ControllerInputPlatform
import com.areslib.subsystem.SubsystemDocument

/**
 * One persistence boundary for subsystem save, removal, and recovery operations.
 *
 * A selected [ProjectSession] supplies optimistic revision checks when available. Standalone unit
 * tests and callers without a long-lived session use the same document repositories directly.
 */
internal class SubsystemProjectPersistence(
    private val documents: AresProjectDocuments,
    private val projectSession: ProjectSession?,
) {
    fun save(
        projectPath: String,
        revision: ProjectSessionRevision?,
        document: SubsystemDocument,
    ): SavedProjectRevision<SubsystemDocument> {
        val session = projectSession
        if (session == null || revision == null) return documents.subsystems.save(projectPath, document)
        return requireApplied(
            result = session.saveSubsystem(revision, document),
            staleMessage = "The project changed after this subsystem loaded. Reload before saving.",
        ).revision
    }

    fun removalPlan(
        projectPath: String,
        revision: ProjectSessionRevision?,
        documentId: String,
    ): ProjectDocumentRemovalPlan {
        val session = projectSession
        if (session == null || revision == null) return documents.subsystems.removalPlan(projectPath, documentId)
        return requireApplied(
            result = session.removalPlan(revision, RemovableProjectDocumentKind.SUBSYSTEM, documentId),
            staleMessage = "The project changed after this subsystem loaded. Reload before reviewing removal.",
        )
    }

    fun remove(
        projectPath: String,
        revision: ProjectSessionRevision?,
        documentId: String,
        expectedHash: String,
    ): RemovedProjectDocument {
        val session = projectSession
        if (session == null || revision == null) {
            return documents.subsystems.remove(projectPath, documentId, expectedHash)
        }
        return requireApplied(
            result = session.remove(
                revision,
                RemovableProjectDocumentKind.SUBSYSTEM,
                documentId,
                expectedHash,
            ),
            staleMessage = "The project changed after removal review. Reload before removing this subsystem.",
        )
    }

    fun restore(
        projectPath: String,
        revision: ProjectSessionRevision?,
        documentId: String,
        expectedHash: String,
        recoveryPath: String,
    ): SubsystemDocument {
        val session = projectSession
        if (session == null || revision == null) {
            return documents.subsystems.restoreRemoved(
                projectPath,
                documentId,
                expectedHash,
                recoveryPath,
            )
        }
        return requireApplied(
            result = session.restoreRemovedSubsystem(
                revision,
                documentId,
                expectedHash,
                recoveryPath,
            ),
            staleMessage = "The project changed after this recovery was offered. Reload before restoring.",
        )
    }

    fun refresh(projectPath: String, league: League) {
        val target = when (league) {
            League.FTC -> ControllerInputPlatform.FTC
            League.FRC -> ControllerInputPlatform.FRC
            League.XRP -> ControllerInputPlatform.XRP
        }
        projectSession?.snapshot(projectPath, target, forceReload = true)
    }

    fun currentRevision(fallback: ProjectSessionRevision?): ProjectSessionRevision? =
        projectSession?.state?.value?.revision ?: fallback

    private fun <T> requireApplied(
        result: ProjectSessionMutationResult<T>,
        staleMessage: String,
    ): T = when (result) {
        is ProjectSessionMutationResult.Applied -> result.value
        is ProjectSessionMutationResult.Stale -> error(staleMessage)
        is ProjectSessionMutationResult.Conflict -> error(result.message)
        is ProjectSessionMutationResult.Failed -> error(result.message)
    }
}
