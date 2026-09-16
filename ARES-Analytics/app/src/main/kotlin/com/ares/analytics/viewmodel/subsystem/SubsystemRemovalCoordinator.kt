package com.ares.analytics.viewmodel.subsystem

import com.ares.analytics.service.AresProjectGenerator
import com.ares.analytics.service.project.ProjectDocumentGateway
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.SubsystemRecoveryNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

internal class SubsystemRemovalCoordinator(
    private val documents: ProjectDocumentGateway,
    private val persistence: SubsystemProjectPersistence,
    private val projectGenerator: AresProjectGenerator?,
    private val checkpointRecorder: ProjectCheckpointRecorder,
    private val scope: CoroutineScope,
    private val aiProposalGeneration: AtomicLong,
    private val getState: () -> SubsystemGeneratorState,
    private val updateState: ((SubsystemGeneratorState) -> SubsystemGeneratorState) -> Unit,
    private val revalidate: (SubsystemGeneratorState) -> SubsystemGeneratorState,
) {
    fun requestRemoveSubsystem() {
        val current = getState()
        val draft = current.draft?.document ?: return
        val root = runCatching { File(current.projectPath).canonicalFile }.getOrNull()
        val canonicalFile = runCatching { documents.subsystems.file(current.projectPath, draft.documentId) }
            .getOrElse { error ->
                updateState { it.copy(status = error.message ?: "The subsystem location is invalid.") }
                return
            }
        val plan = if (canonicalFile.isFile) {
            runCatching { persistence.removalPlan(current.projectPath, current.projectRevision, draft.documentId) }
                .getOrElse { error ->
                    updateState {
                        it.copy(status = error.message ?: "The saved subsystem could not be reviewed for removal.")
                    }
                    return
                }
        } else null
        updateState { state -> SubsystemRemovalStateTransitions.prepareRemoval(state, draft, plan, root) }
    }

    fun cancelRemoveSubsystem() = updateState { it.copy(pendingRemoval = null) }

    fun confirmRemoveSubsystem() {
        val current = getState()
        val request = current.pendingRemoval ?: return
        if (!request.persisted) {
            removeDocumentFromSession(request.documentId, "Discarded the unsaved ${request.displayName} draft.")
            return
        }
        val expectedHash = request.contentHash ?: return
        runCatching {
            persistence.remove(current.projectPath, current.projectRevision, request.documentId, expectedHash)
        }.onSuccess { removed ->
            val root = File(current.projectPath).canonicalFile
            val recoveryPath = removed.recoveryFile.relativeTo(root).invariantSeparatorsPath
            removeDocumentFromSession(
                request.documentId,
                "Removed ${removed.displayName}. Kotlin source was preserved.",
                SubsystemRecoveryNotice(
                    documentId = removed.documentId,
                    displayName = removed.displayName,
                    contentHash = removed.contentHash,
                    recoveryPath = recoveryPath,
                ),
            )
            projectGenerator?.generateAresProject(current.projectPath, current.league)
            scope.launch {
                runCatching {
                    checkpointRecorder.checkpoint(
                        current.projectPath,
                        "Removed ${removed.displayName} subsystem",
                        setOf(
                            removed.removedFile.relativeTo(root).invariantSeparatorsPath,
                            removed.recoveryFile.relativeTo(root).invariantSeparatorsPath,
                        ),
                    )
                }.onFailure { failure ->
                    updateState {
                        it.copy(status = "Subsystem removed safely, but automatic Project History checkpoint failed: ${failure.message}")
                    }
                }
            }
        }.onFailure { error ->
            updateState {
                it.copy(
                    pendingRemoval = null,
                    status = error.message ?: "The subsystem could not be removed.",
                )
            }
        }
    }

    fun restoreRemovedSubsystem() {
        val current = getState()
        val recovery = current.recentRecovery ?: return
        runCatching {
            persistence.restore(
                current.projectPath,
                current.projectRevision,
                recovery.documentId,
                recovery.contentHash,
                recovery.recoveryPath,
            )
        }.onSuccess { restored ->
            aiProposalGeneration.incrementAndGet()
            updateState {
                revalidate(
                    SubsystemRemovalStateTransitions.restoreDocument(
                        current = it,
                        restored = restored,
                        revision = persistence.currentRevision(it.projectRevision),
                    )
                )
            }
            projectGenerator?.generateAresProject(current.projectPath, current.league)
            scope.launch {
                runCatching {
                    val root = File(current.projectPath).canonicalFile
                    checkpointRecorder.checkpoint(
                        current.projectPath,
                        "Restored ${restored.displayName} subsystem",
                        setOf(documents.subsystems.file(current.projectPath, restored.documentId).relativeTo(root).invariantSeparatorsPath),
                    )
                }.onFailure { failure ->
                    updateState {
                        it.copy(status = "Subsystem restored, but automatic Project History checkpoint failed: ${failure.message}")
                    }
                }
            }
        }.onFailure { error ->
            updateState {
                it.copy(status = error.message ?: "The subsystem recovery copy could not be restored.")
            }
        }
    }

    fun dismissRecoveryNotice() = updateState { it.copy(recentRecovery = null) }

    private fun removeDocumentFromSession(
        documentId: String,
        message: String,
        recovery: SubsystemRecoveryNotice? = null,
    ) {
        aiProposalGeneration.incrementAndGet()
        updateState { current ->
            revalidate(
                SubsystemRemovalStateTransitions.removeDocument(
                    current = current,
                    documentId = documentId,
                    message = message,
                    revision = persistence.currentRevision(current.projectRevision),
                    recovery = recovery,
                )
            )
        }
    }
}
