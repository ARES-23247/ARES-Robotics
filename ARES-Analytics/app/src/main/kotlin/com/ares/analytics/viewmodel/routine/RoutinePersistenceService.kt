package com.ares.analytics.viewmodel.routine

import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.service.project.persistence.AutonomousCatalogProjectRepository
import com.ares.analytics.service.project.persistence.BiobuzzAutoBundle
import com.ares.analytics.service.project.persistence.ProjectMetadataRepository
import com.ares.analytics.service.project.persistence.ProjectRevisionSummary
import com.ares.analytics.service.project.persistence.RoutineProjectRepository
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.safeProjectDocumentId
import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutinePose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class RoutineRefresh(
    val routines: List<RoutineDocument>,
    val diagnostics: List<String>,
    val catalog: CapabilityCatalogDocument?,
    val autonomous: AutonomousCatalogDocument?,
    val metadata: AresProjectMetadataDocument?,
    val projectRevision: ProjectSessionRevision?,
)

data class RoutineSave(
    val routine: RoutineDocument,
    val createdRevision: Boolean,
    val autonomous: AutonomousCatalogDocument,
)

fun AresLeague.toAnalyticsLeague(): League = when (this) {
    AresLeague.FTC -> League.FTC
    AresLeague.FRC -> League.FRC
    AresLeague.XRP -> League.XRP
}

fun Waypoint.toRoutinePose(): RoutinePose = RoutinePose(
    xMeters = x,
    yMeters = y,
    headingRadians = rotationDeg?.let(Math::toRadians) ?: headingRad ?: 0.0
)

internal class RoutinePersistenceService(
    private val checkpointRecorder: ProjectCheckpointRecorder = ProjectCheckpointRecorder.NONE,
    private val projectSession: ProjectSession? = null,
) {
    private val routineRepository = RoutineProjectRepository()
    private val autonomousRepository = AutonomousCatalogProjectRepository(routineRepository)
    private val metadataRepository = ProjectMetadataRepository()
    private val projectDocuments = AresProjectDocuments(
        routines = routineRepository,
        metadata = metadataRepository,
        autonomous = autonomousRepository,
    )

    suspend fun refreshProject(projectPath: String, league: League): RoutineRefresh = withContext(Dispatchers.IO) {
        val target = when (league) {
            League.FTC -> ControllerInputPlatform.FTC
            League.FRC -> ControllerInputPlatform.FRC
            League.XRP -> ControllerInputPlatform.XRP
        }
        val sessionSnapshot = projectSession?.snapshot(projectPath, target, forceReload = true)
        val snapshot = sessionSnapshot?.documents ?: projectDocuments.load(projectPath, target)
        val project = snapshot.query
        RoutineRefresh(
            project.routines,
            snapshot.diagnostics.map { it.message },
            project.capabilityCatalog,
            project.autonomousCatalog,
            project.metadata,
            sessionSnapshot?.revision,
        )
    }

    suspend fun loadRoutine(
        projectPath: String,
        documentId: String,
    ): Triple<RoutineDocument, List<ProjectRevisionSummary>, AutonomousCatalogDocument?> = withContext(Dispatchers.IO) {
        val routine = routineRepository.load(projectPath, documentId)
        val revisions = routineRepository.listRevisions(projectPath, documentId)
        val autonomous = autonomousRepository.load(projectPath).getOrNull()
        Triple(routine, revisions, autonomous)
    }

    suspend fun saveRoutine(
        projectPath: String,
        routine: RoutineDocument,
        autonomousEntry: AutonomousCatalogEntry?,
        projectRevision: ProjectSessionRevision?,
    ): Pair<RoutineSave, List<ProjectRevisionSummary>> {
        val saved = withContext(Dispatchers.IO) {
            val session = projectSession
            if (session != null && projectRevision != null) {
                when (val result = session.saveRoutine(projectRevision, routine, autonomousEntry)) {
                    is ProjectSessionMutationResult.Applied -> RoutineSave(
                        result.value.routine.document,
                        result.value.routine.createdRevision,
                        result.value.autonomousCatalog.document,
                    )
                    is ProjectSessionMutationResult.Stale -> error("The project changed after this routine loaded. Reload before saving.")
                    is ProjectSessionMutationResult.Conflict -> error(result.message)
                    is ProjectSessionMutationResult.Failed -> error(result.message)
                }
            } else {
                val savedRoutine = routineRepository.save(projectPath, routine)
                val oldCatalog = autonomousRepository.load(projectPath).getOrNull()
                val entry = autonomousEntry?.copy(routineId = savedRoutine.document.documentId)
                val entries = oldCatalog?.entries.orEmpty()
                    .filterNot { it.routineId == savedRoutine.document.documentId || it.entryId == entry?.entryId }
                    .let { remaining -> if (entry == null) remaining else remaining + entry }
                val projectId = oldCatalog?.projectId ?: safeProjectDocumentId(File(projectPath).name)
                val defaultEntryId = oldCatalog?.defaultEntryId?.takeIf { id -> entries.any { it.entryId == id && it.enabled } }
                    ?: entries.firstOrNull { it.enabled }?.entryId
                val catalogDraft = AutonomousCatalogDocument(
                    projectId = projectId,
                    revision = oldCatalog?.revision ?: 1,
                    defaultEntryId = defaultEntryId,
                    entries = entries
                )
                val savedCatalog = autonomousRepository.save(projectPath, catalogDraft)
                RoutineSave(savedRoutine.document, savedRoutine.createdRevision, savedCatalog.document)
            }
        }
        val revisions = withContext(Dispatchers.IO) {
            routineRepository.listRevisions(projectPath, saved.routine.documentId)
        }
        runCatching {
            checkpointRecorder.checkpoint(
                projectPath,
                "Saved ${saved.routine.name} autonomous routine",
                setOf(".ares/routines", ".ares/history/routines", ".ares/autonomous-catalog.json", ".ares/history/autonomous"),
            )
        }
        return saved to revisions
    }

    suspend fun restoreRoutine(
        projectPath: String,
        documentId: String,
        contentHash: String,
        projectRevision: ProjectSessionRevision?,
    ): Triple<RoutineDocument, List<ProjectRevisionSummary>, ProjectSessionRevision?> = withContext(Dispatchers.IO) {
        val session = projectSession
        val restored = if (session != null && projectRevision != null) {
            when (val result = session.restoreRoutineRevision(projectRevision, documentId, contentHash)) {
                is ProjectSessionMutationResult.Applied -> result.value.document to result.snapshot.revision
                is ProjectSessionMutationResult.Stale -> error(
                    "The project changed after this routine loaded. Reload before restoring.",
                )
                is ProjectSessionMutationResult.Conflict -> error(result.message)
                is ProjectSessionMutationResult.Failed -> error(result.message)
            }
        } else {
            val restoredDoc = routineRepository.restore(projectPath, documentId, contentHash)
            restoredDoc.document to null
        }
        Triple(
            restored.first,
            routineRepository.listRevisions(projectPath, documentId),
            restored.second,
        )
    }

    suspend fun importBiobuzzAuto(zipPath: String) = withContext(Dispatchers.IO) {
        BiobuzzAutoBundle.read(File(zipPath))
    }

    suspend fun saveMetadata(
        projectPath: String,
        expectedContentHash: String?,
        metadata: AresProjectMetadataDocument,
        projectRevision: ProjectSessionRevision?,
    ): ProjectSessionRevision? = withContext(Dispatchers.IO) {
        val session = projectSession
        if (session != null && projectRevision != null) {
            when (val result = session.saveProjectIdentity(projectRevision, metadata)) {
                is ProjectSessionMutationResult.Applied -> result.snapshot.revision
                is ProjectSessionMutationResult.Stale -> error(
                    "The project changed after the autonomous editor loaded. Reload before changing the robot footprint.",
                )
                is ProjectSessionMutationResult.Conflict -> error(result.message)
                is ProjectSessionMutationResult.Failed -> error(result.message)
            }
        } else {
            metadataRepository.saveReviewed(
                projectPath,
                expectedContentHash,
                metadata,
            )
            null
        }
    }
}
