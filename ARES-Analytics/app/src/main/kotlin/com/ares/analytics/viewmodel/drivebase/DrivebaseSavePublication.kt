package com.ares.analytics.viewmodel.drivebase

import com.ares.analytics.service.drivebase.DrivebaseDocument
import com.ares.analytics.service.drivebase.DrivebaseIssue
import com.ares.analytics.service.drivebase.DrivebaseProjectRepository
import com.ares.analytics.service.drivebase.diffDrivebase
import com.ares.analytics.service.drivebase.validateDrivebaseForLeague
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.areslib.drivetrain.DrivetrainDocumentCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Cohesive publication helper for reviewed drivebase saves.
 *
 * Ensures completed disk writes are accurately recorded in [DrivebaseBuilderState.saved] and
 * [DrivebaseBuilderState.projectRevision], while preserving subsequent operator edits,
 * validation errors, and newer reload/save outcomes.
 */
internal object DrivebaseSavePublication {

    suspend fun coordinateSave(
        scope: CoroutineScope,
        stateFlow: MutableStateFlow<DrivebaseBuilderState>,
        session: ProjectSession?,
        repository: DrivebaseProjectRepository,
        checkpointRecorder: ProjectCheckpointRecorder,
        loadGeneration: AtomicLong,
        saveRequestId: AtomicLong,
        token: String,
    ) {
        val state = stateFlow.value
        val review = state.saveReview
        val currentHash = state.saved?.canonical?.let(DrivetrainDocumentCodec::contentHash)
        if (review == null || review.confirmationToken != token || review.baseContentHash != currentHash) {
            stateFlow.update { it.copy(error = "The reviewed drivebase changed. Review a fresh diff before saving.") }
            return
        }
        val startLoadGen = loadGeneration.get()
        val requestId = saveRequestId.incrementAndGet()
        val startDraft = state.draft
        val startIssues = state.issues
        val startError = state.error
        val startRevision = state.projectRevision
        val projectPath = state.projectPath

        val saveResult = runCatching {
            executeSave(
                session = session,
                repository = repository,
                projectPath = projectPath,
                revision = startRevision,
                currentHash = currentHash,
                startDraft = startDraft,
            )
        }
        saveResult.fold(
            onSuccess = { (saved, revision) ->
                val savedStatus = "Saved reviewed drivebase ${
                    saved.canonical?.let(DrivetrainDocumentCodec::contentHash)?.take(12)
                }. No robot or vendor source was written."

                stateFlow.update { current ->
                    publishSuccess(
                        current = current,
                        saved = saved,
                        revision = revision,
                        token = token,
                        startDraft = startDraft,
                        startIssues = startIssues,
                        startError = startError,
                        savedStatus = savedStatus,
                        isSuperseded = loadGeneration.get() != startLoadGen || saveRequestId.get() != requestId,
                    )
                }

                // Ancillary checkpoint runs for any completed real write, independent of UI publication
                scope.launch {
                    runCatching {
                        recordCheckpoint(checkpointRecorder, projectPath, saved)
                    }.onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        stateFlow.update { current ->
                            publishCheckpointFailure(
                                current = current,
                                errorMessage = failure.message ?: "Unknown error",
                                expectedStatus = savedStatus,
                                isSuperseded = loadGeneration.get() != startLoadGen || saveRequestId.get() != requestId,
                            )
                        }
                    }
                }

            },
            onFailure = { failure ->
                if (failure is CancellationException) throw failure
                stateFlow.update { current ->
                    publishFailure(
                        current = current,
                        errorMessage = failure.message ?: "Could not save the drivebase.",
                        previousError = startError,
                        isSuperseded = loadGeneration.get() != startLoadGen || saveRequestId.get() != requestId,
                    )
                }
            }
        )
    }

    private suspend fun executeSave(
        session: ProjectSession?,
        repository: DrivebaseProjectRepository,
        projectPath: String,
        revision: ProjectSessionRevision?,
        currentHash: String?,
        startDraft: DrivebaseDocument,
    ): Pair<DrivebaseDocument, ProjectSessionRevision?> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        if (session != null && revision != null) {
            when (val result = session.saveDrivebase(revision, currentHash, startDraft)) {
                is ProjectSessionMutationResult.Applied -> result.value to result.snapshot.revision
                is ProjectSessionMutationResult.Stale -> error("The project changed after this drivebase loaded. Reload before saving.")
                is ProjectSessionMutationResult.Conflict -> error(result.message)
                is ProjectSessionMutationResult.Failed -> error(result.message)
            }
        } else {
            repository.saveReviewed(projectPath, currentHash, startDraft) to null
        }
    }

    private suspend fun recordCheckpoint(
        checkpointRecorder: ProjectCheckpointRecorder,
        projectPath: String,
        saved: DrivebaseDocument,
    ) {
        checkpointRecorder.checkpoint(
            projectPath,
            "Saved ${saved.displayName} drivebase",
            setOf(".ares/drivetrains", ".ares/history/drivetrains", ".ares/tuning"),
        )
    }

    private fun publishSuccess(
        current: DrivebaseBuilderState,
        saved: DrivebaseDocument,
        revision: ProjectSessionRevision?,
        token: String,
        startDraft: DrivebaseDocument,
        startIssues: List<DrivebaseIssue>,
        startError: String?,
        savedStatus: String,
        isSuperseded: Boolean,
    ): DrivebaseBuilderState {
        if (isSuperseded) {
            return current
        }

        val newBaseHash = saved.canonical?.let(DrivetrainDocumentCodec::contentHash)

        val nextSaveReview = current.saveReview?.takeIf { review ->
            review.confirmationToken != token && review.baseContentHash == newBaseHash
        }

        val draftChanged = current.draft != startDraft
        val nextDraft = if (draftChanged) current.draft else saved
        val nextDirty = if (draftChanged) {
            diffDrivebase(saved, current.draft).isNotEmpty()
        } else {
            false
        }

        val nextIssues = if (current.issues != startIssues || draftChanged) {
            current.issues
        } else {
            validateDrivebaseForLeague(saved, current.league)
        }

        val nextError = if (current.error != null && current.error != startError) {
            current.error
        } else {
            null
        }

        return current.copy(
            saved = saved,
            projectRevision = revision ?: current.projectRevision,
            draft = nextDraft,
            dirty = nextDirty,
            issues = nextIssues,
            saveReview = nextSaveReview,
            tuningProfileRepairIssues = emptyList(),
            status = savedStatus,
            error = nextError,
        )
    }

    private fun publishFailure(
        current: DrivebaseBuilderState,
        errorMessage: String,
        previousError: String?,
        isSuperseded: Boolean,
    ): DrivebaseBuilderState {
        if (isSuperseded || (current.error != null && current.error != previousError)) {
            return current
        }
        return current.copy(error = errorMessage)
    }

    private fun publishCheckpointFailure(
        current: DrivebaseBuilderState,
        errorMessage: String,
        expectedStatus: String,
        isSuperseded: Boolean,
    ): DrivebaseBuilderState {
        if (isSuperseded || current.status != expectedStatus) {
            return current
        }
        return current.copy(status = "Drivebase saved, but automatic Project History checkpoint failed: $errorMessage")
    }
}
