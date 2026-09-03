package com.ares.analytics.service.project

import com.ares.analytics.service.ProjectBuildService
import com.ares.analytics.service.RobotDeploymentService
import com.ares.analytics.service.SimulatorProcessService
import com.ares.analytics.service.drivebase.DrivebaseDocument
import com.ares.analytics.service.drivebase.DrivebaseProjectRepository
import com.ares.analytics.service.tuning.TuningProfileChange
import com.ares.analytics.service.tuning.TuningProfileRepository
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.util.ProjectLayout
import com.ares.analytics.util.Sha256
import com.ares.analytics.service.project.persistence.ProjectDocumentRemovalPlan
import com.ares.analytics.service.project.persistence.ProjectMutationTransaction
import com.ares.analytics.service.project.persistence.RemovedProjectDocument
import com.ares.analytics.service.project.persistence.SavedProjectRevision
import com.ares.analytics.service.project.persistence.SavedProjectMetadata
import com.ares.analytics.service.project.persistence.SavedSuperstructureDocument
import com.ares.analytics.service.project.persistence.FieldDocumentStore
import com.ares.analytics.service.project.persistence.VersionedProjectDocumentStore
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.model.ProjectModelSeverity
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineDocument
import com.areslib.simulation.SimulationProductId
import com.areslib.subsystem.SubsystemDocument
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.state.RobotFieldConfig
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningProfileDocument
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ProjectSessionPhase { EMPTY, LOADING, READY, ERROR }

data class ProjectSessionSelection(
    val projectRoot: String,
    val targetPlatform: ControllerInputPlatform,
)

data class ProjectSessionRevision(
    val sequence: Long,
    val canonicalContentSha256: String,
)

data class ProjectSessionSnapshot(
    val selection: ProjectSessionSelection,
    val revision: ProjectSessionRevision,
    val documents: AresProjectDocumentSnapshot,
)

data class ProjectSessionState(
    val phase: ProjectSessionPhase = ProjectSessionPhase.EMPTY,
    val selection: ProjectSessionSelection? = null,
    val revision: ProjectSessionRevision? = null,
    val snapshot: ProjectSessionSnapshot? = null,
    val operation: String? = null,
    val error: String? = null,
)

sealed interface ProjectSessionMutationResult<out T> {
    data class Applied<T>(val value: T, val snapshot: ProjectSessionSnapshot) : ProjectSessionMutationResult<T>
    data class Stale(val expected: ProjectSessionRevision, val actual: ProjectSessionRevision?) :
        ProjectSessionMutationResult<Nothing>
    data class Conflict(val message: String) : ProjectSessionMutationResult<Nothing>
    data class Failed(val message: String) : ProjectSessionMutationResult<Nothing>
}

data class SavedControlDocuments(
    val changedRelativePaths: Set<String>,
)

data class SavedRoutineDocuments(
    val routine: SavedProjectRevision<RoutineDocument>,
    val autonomousCatalog: SavedProjectRevision<AutonomousCatalogDocument>,
    val changedRelativePaths: Set<String>,
)

data class SavedSubsystemDocument(
    val revision: SavedProjectRevision<SubsystemDocument>,
    val changedRelativePaths: Set<String>,
)

enum class RemovableProjectDocumentKind {
    ROUTINE,
    CONTROL_SCHEME,
    CONTROLLER_PROFILE,
    SUBSYSTEM,
}

/**
 * Long-lived owner of the selected canonical robot project.
 *
 * Repositories continue to own codecs, atomic writes, history, and recovery. This session owns the
 * cross-feature selection, one immutable effective snapshot, stable content identity, and
 * revision-bound mutation boundary. It deliberately performs no robot or cloud IO.
 */
class ProjectSession(
    private val projectDocuments: ProjectDocumentGateway = AresProjectDocuments(),
    private val drivebaseRepository: DrivebaseProjectRepository = DrivebaseProjectRepository(),
    private val tuningRepository: TuningProfileRepository = TuningProfileRepository(),
) {
    private val lock = ReentrantLock()
    private var nextRevisionSequence = 0L
    private val _state = MutableStateFlow(ProjectSessionState())
    val state: StateFlow<ProjectSessionState> = _state.asStateFlow()

    fun snapshot(
        projectPath: String,
        targetPlatform: ControllerInputPlatform,
        forceReload: Boolean = false,
    ): ProjectSessionSnapshot = lock.withLock {
        val selection = selection(projectPath, targetPlatform)
        val current = _state.value.snapshot
        if (!forceReload && current?.selection == selection) return current
        loadLocked(selection, operation = if (forceReload) "Reloading project" else "Opening project")
    }

    fun reload(expectedRevision: ProjectSessionRevision? = null): ProjectSessionMutationResult<Unit> = lock.withLock {
        val current = _state.value.snapshot
            ?: return ProjectSessionMutationResult.Failed("No robot project is selected.")
        if (expectedRevision != null && expectedRevision != current.revision) {
            return ProjectSessionMutationResult.Stale(expectedRevision, current.revision)
        }
        runCatching { loadLocked(current.selection, "Reloading project") }
            .fold(
                onSuccess = { ProjectSessionMutationResult.Applied(Unit, it) },
                onFailure = { ProjectSessionMutationResult.Failed(it.message ?: "Project reload failed.") },
            )
    }

    fun clear() = lock.withLock {
        _state.value = ProjectSessionState()
    }

    fun metadataFile(projectPath: String): File = projectDocuments.metadata.file(projectPath)

    /**
     * Representative typed mutation used by the controller editor. Both document families commit
     * under one session revision, and any external byte change is rejected before the first write.
     */
    fun saveControls(
        expectedRevision: ProjectSessionRevision,
        profiles: Collection<ControllerProfileDocument>,
        schemes: Collection<ControlSchemeDocument>,
    ): ProjectSessionMutationResult<SavedControlDocuments> = mutate(expectedRevision, "Saving controller bindings") {
        val root = File(it.selection.projectRoot).canonicalFile
        ProjectMutationTransaction.run(
            root,
            "controller-bindings",
            listOf(".ares/controllers", ".ares/controls", ".ares/history/controllers", ".ares/history/controls"),
        ) {
            val paths = linkedSetOf<String>()
            profiles.sortedBy(ControllerProfileDocument::documentId).forEach { profile ->
                val saved = projectDocuments.controllers.save(root.path, profile)
                paths += saved.currentFile.relativeTo(root).invariantSeparatorsPath
                paths += saved.historyFile.relativeTo(root).invariantSeparatorsPath
            }
            schemes.sortedBy(ControlSchemeDocument::documentId).forEach { scheme ->
                val saved = projectDocuments.controls.save(root.path, scheme)
                paths += saved.currentFile.relativeTo(root).invariantSeparatorsPath
                paths += saved.historyFile.relativeTo(root).invariantSeparatorsPath
            }
            SavedControlDocuments(paths)
        }
    }

    /** Saves a routine and its chooser entry as one revision-bound project command. */
    fun saveRoutine(
        expectedRevision: ProjectSessionRevision,
        routine: RoutineDocument,
        autonomousEntry: AutonomousCatalogEntry?,
    ): ProjectSessionMutationResult<SavedRoutineDocuments> = mutate(expectedRevision, "Saving autonomous routine") { current ->
        val root = File(current.selection.projectRoot).canonicalFile
        ProjectMutationTransaction.run(
            root,
            "routine-${routine.documentId}",
            listOf(".ares/routines", ".ares/autonomous-catalog.json", ".ares/history/routines", ".ares/history/autonomous-catalog"),
        ) {
            val savedRoutine = projectDocuments.routines.save(root.path, routine)
            val previousCatalog = current.documents.query.autonomousCatalog
            val entry = autonomousEntry?.copy(routineId = savedRoutine.document.documentId)
            val entries = previousCatalog?.entries.orEmpty()
                .filterNot { it.routineId == savedRoutine.document.documentId || it.entryId == entry?.entryId }
                .let { remaining -> if (entry == null) remaining else remaining + entry }
            val defaultEntryId = previousCatalog?.defaultEntryId
                ?.takeIf { id -> entries.any { it.entryId == id && it.enabled } }
                ?: entries.firstOrNull { it.enabled }?.entryId
            val savedCatalog = projectDocuments.autonomous.save(
                root.path,
                AutonomousCatalogDocument(
                    projectId = previousCatalog?.projectId
                        ?: requireNotNull(current.documents.query.metadata).projectId,
                    revision = previousCatalog?.revision ?: 1,
                    defaultEntryId = defaultEntryId,
                    entries = entries,
                ),
            )
            SavedRoutineDocuments(
                routine = savedRoutine,
                autonomousCatalog = savedCatalog,
                changedRelativePaths = setOf(
                    savedRoutine.currentFile.relativeTo(root).invariantSeparatorsPath,
                    savedRoutine.historyFile.relativeTo(root).invariantSeparatorsPath,
                    savedCatalog.currentFile.relativeTo(root).invariantSeparatorsPath,
                    savedCatalog.historyFile.relativeTo(root).invariantSeparatorsPath,
                ),
            )
        }
    }

    /** Restores one reviewed routine revision as a new canonical revision. */
    fun restoreRoutineRevision(
        expectedRevision: ProjectSessionRevision,
        documentId: String,
        contentHash: String,
    ): ProjectSessionMutationResult<SavedProjectRevision<RoutineDocument>> =
        mutate(expectedRevision, "Restoring autonomous routine") { current ->
            projectDocuments.routines.restore(
                current.selection.projectRoot,
                documentId,
                contentHash,
            )
        }

    fun saveSubsystem(
        expectedRevision: ProjectSessionRevision,
        document: SubsystemDocument,
    ): ProjectSessionMutationResult<SavedSubsystemDocument> = mutate(expectedRevision, "Saving subsystem") { current ->
        val root = File(current.selection.projectRoot).canonicalFile
        val saved = projectDocuments.subsystems.save(root.path, document)
        SavedSubsystemDocument(
            revision = saved,
            changedRelativePaths = setOf(
                saved.currentFile.relativeTo(root).invariantSeparatorsPath,
                saved.historyFile.relativeTo(root).invariantSeparatorsPath,
            ),
        )
    }

    fun restoreRemovedSubsystem(
        expectedRevision: ProjectSessionRevision,
        documentId: String,
        expectedContentHash: String,
        recoveryRelativePath: String,
    ): ProjectSessionMutationResult<SubsystemDocument> = mutate(expectedRevision, "Restoring subsystem") { current ->
        projectDocuments.subsystems.restoreRemoved(
            current.selection.projectRoot,
            documentId,
            expectedContentHash,
            recoveryRelativePath,
        )
    }

    fun saveProjectIdentity(
        expectedRevision: ProjectSessionRevision,
        document: AresProjectMetadataDocument,
    ): ProjectSessionMutationResult<SavedProjectMetadata> = mutate(expectedRevision, "Saving project identity") { current ->
        projectDocuments.metadata.saveReviewed(
            current.selection.projectRoot,
            current.documents.query.metadata?.let(com.areslib.project.AresProjectMetadataCodec::contentHash),
            document,
        )
    }

    /**
     * Repairs an unreadable identity document through the session boundary. An invalid project has
     * no assembled [ProjectSessionRevision], so the exact raw-byte hash is its fail-closed revision
     * token. The repository preserves the invalid bytes before replacement and the session only
     * becomes READY after the repaired project can be loaded as one stable snapshot.
     */
    fun repairProjectIdentity(
        projectPath: String,
        targetPlatform: ControllerInputPlatform,
        expectedRawContentHash: String,
        document: AresProjectMetadataDocument,
    ): ProjectSessionMutationResult<SavedProjectMetadata> = lock.withLock {
        val selected = selection(projectPath, targetPlatform)
        val currentSelection = _state.value.selection
        if (currentSelection != null && currentSelection != selected) {
            return ProjectSessionMutationResult.Conflict(
                "A different robot project is open. Reload this workspace before repairing its identity.",
            )
        }
        _state.value = _state.value.copy(
            selection = selected,
            operation = "Repairing project identity",
            error = null,
        )
        runCatching {
            val saved = projectDocuments.metadata.repairReviewed(
                selected.projectRoot,
                expectedRawContentHash,
                document,
            )
            saved to loadLocked(selected, "Refreshing project after identity repair")
        }.fold(
            onSuccess = { (saved, refreshed) -> ProjectSessionMutationResult.Applied(saved, refreshed) },
            onFailure = { error ->
                _state.value = ProjectSessionState(
                    phase = ProjectSessionPhase.ERROR,
                    selection = selected,
                    error = error.message ?: "Project identity repair failed.",
                )
                ProjectSessionMutationResult.Failed(error.message ?: "Project identity repair failed.")
            },
        )
    }

    fun saveSuperstructure(
        expectedRevision: ProjectSessionRevision,
        document: SuperstructureDocument,
        expectedContentHash: String?,
    ): ProjectSessionMutationResult<SavedSuperstructureDocument> = mutate(expectedRevision, "Saving superstructure") { current ->
        val project = current.documents.query
        projectDocuments.superstructures.save(
            current.selection.projectRoot,
            document,
            expectedContentHash,
            project.subsystems,
            project.actions.mapTo(linkedSetOf()) { it.key },
            project.actions.asSequence().filter { it.parameters.isEmpty() }.mapTo(linkedSetOf()) { it.key },
        )
    }

    fun saveDrivebase(
        expectedRevision: ProjectSessionRevision,
        expectedContentHash: String?,
        document: DrivebaseDocument,
    ): ProjectSessionMutationResult<DrivebaseDocument> = mutate(expectedRevision, "Saving drivebase") { current ->
        drivebaseRepository.saveReviewed(current.selection.projectRoot, expectedContentHash, document)
    }

    fun saveField(
        expectedRevision: ProjectSessionRevision,
        league: League,
        document: RobotFieldConfig,
    ): ProjectSessionMutationResult<Unit> = mutate(expectedRevision, "Saving field") { current ->
        val expectedTarget = when (league) {
            League.FTC -> ControllerInputPlatform.FTC
            League.FRC -> ControllerInputPlatform.FRC
            League.XRP -> ControllerInputPlatform.XRP
        }
        require(current.selection.targetPlatform == expectedTarget) {
            "The field league does not match the selected robot project."
        }
        FieldDocumentStore.save(current.selection.projectRoot, league, document)
    }

    fun promoteTuningProfile(
        expectedRevision: ProjectSessionRevision,
        current: TuningProfileDocument,
        expectedContentHash: String,
        declarations: List<TuningParameterDeclaration>,
        changes: List<TuningProfileChange>,
        reviewedBy: String,
        reviewSummary: String,
    ): ProjectSessionMutationResult<TuningProfileDocument> = mutate(expectedRevision, "Promoting tuning profile") { snapshot ->
        tuningRepository.promote(
            snapshot.selection.projectRoot,
            current,
            expectedContentHash,
            declarations,
            changes,
            reviewedBy,
            reviewSummary,
        )
    }

    fun removalPlan(
        expectedRevision: ProjectSessionRevision,
        kind: RemovableProjectDocumentKind,
        documentId: String,
    ): ProjectSessionMutationResult<ProjectDocumentRemovalPlan> = lock.withLock {
        val current = currentForMutation(expectedRevision)
            ?: return ProjectSessionMutationResult.Stale(expectedRevision, _state.value.revision)
        if (fingerprint(current.selection) != current.revision.canonicalContentSha256) {
            return ProjectSessionMutationResult.Conflict(
                "The project changed outside this Studio session. Reload before reviewing removal.",
            )
        }
        runCatching {
            removableStore(kind).removalPlan(current.selection.projectRoot, documentId)
        }.fold(
            onSuccess = { ProjectSessionMutationResult.Applied(it, current) },
            onFailure = { ProjectSessionMutationResult.Failed(it.message ?: "Removal review failed.") },
        )
    }

    fun remove(
        expectedRevision: ProjectSessionRevision,
        kind: RemovableProjectDocumentKind,
        documentId: String,
        expectedContentHash: String,
    ): ProjectSessionMutationResult<RemovedProjectDocument> = mutate(expectedRevision, "Removing project document") {
        removableStore(kind).remove(it.selection.projectRoot, documentId, expectedContentHash)
    }

    private fun <T> mutate(
        expectedRevision: ProjectSessionRevision,
        operation: String,
        mutation: (ProjectSessionSnapshot) -> T,
    ): ProjectSessionMutationResult<T> = lock.withLock {
        val current = currentForMutation(expectedRevision)
            ?: return ProjectSessionMutationResult.Stale(expectedRevision, _state.value.revision)
        if (fingerprint(current.selection) != current.revision.canonicalContentSha256) {
            return ProjectSessionMutationResult.Conflict(
                "The canonical project changed after this form was loaded. Reload before saving.",
            )
        }
        _state.value = _state.value.copy(operation = operation, error = null)
        runCatching {
            val value = mutation(current)
            value to loadLocked(current.selection, "Refreshing project after $operation")
        }.fold(
            onSuccess = { (value, refreshed) -> ProjectSessionMutationResult.Applied(value, refreshed) },
            onFailure = { error ->
                _state.value = _state.value.copy(operation = null, error = error.message)
                ProjectSessionMutationResult.Failed(error.message ?: "$operation failed.")
            },
        )
    }

    private fun currentForMutation(expected: ProjectSessionRevision): ProjectSessionSnapshot? =
        _state.value.snapshot?.takeIf { it.revision == expected }

    private fun loadLocked(selection: ProjectSessionSelection, operation: String): ProjectSessionSnapshot {
        val previousSnapshot = _state.value.snapshot
        _state.value = ProjectSessionState(
            phase = ProjectSessionPhase.LOADING,
            selection = selection,
            operation = operation,
        )
        return runCatching { loadStable(selection) }
            .onFailure { error ->
                _state.value = ProjectSessionState(
                    phase = ProjectSessionPhase.ERROR,
                    selection = selection,
                    error = error.message ?: "Project could not be loaded.",
                )
            }
            .getOrThrow()
            .let { (documents, contentHash) ->
                val revision = previousSnapshot
                    ?.takeIf { it.selection == selection && it.revision.canonicalContentSha256 == contentHash }
                    ?.revision
                    ?: ProjectSessionRevision(++nextRevisionSequence, contentHash)
                val snapshot = ProjectSessionSnapshot(
                    selection = selection,
                    revision = revision,
                    documents = documents,
                )
                _state.value = ProjectSessionState(
                    phase = ProjectSessionPhase.READY,
                    selection = selection,
                    revision = snapshot.revision,
                    snapshot = snapshot,
                )
                snapshot
            }
    }

    private fun loadStable(selection: ProjectSessionSelection): Pair<AresProjectDocumentSnapshot, String> {
        ProjectMutationTransaction.recover(File(selection.projectRoot))
        repeat(2) { attempt ->
            val before = fingerprint(selection)
            val loaded = projectDocuments.load(selection.projectRoot, selection.targetPlatform)
            val after = fingerprint(selection)
            if (before == after) return loaded to after
            if (attempt == 1) {
                error("The canonical project kept changing while ARES was loading it. Wait for the other edit to finish, then reload.")
            }
        }
        error("Project load did not produce a stable snapshot.")
    }

    private fun selection(projectPath: String, targetPlatform: ControllerInputPlatform): ProjectSessionSelection {
        require(projectPath.isNotBlank()) { "Choose a robot project directory." }
        val root = File(projectPath).canonicalFile
        require(root.isDirectory) { "Project directory does not exist: ${root.path}" }
        return ProjectSessionSelection(root.path, targetPlatform)
    }

    private fun fingerprint(selection: ProjectSessionSelection): String {
        val root = File(selection.projectRoot).canonicalFile
        val canonicalFiles = buildList {
            val aresRoot = File(root, ".ares")
            if (aresRoot.isDirectory) {
                addAll(
                    aresRoot.walkTopDown()
                        .filter(File::isFile)
                        .filterNot { file ->
                            val relative = file.relativeTo(aresRoot).invariantSeparatorsPath
                            relative.startsWith("history/") ||
                                relative.startsWith("recovery/") ||
                                relative.startsWith("drafts/") ||
                                relative.startsWith("backups/") ||
                                relative.startsWith("evidence/") ||
                                relative.startsWith("local/") ||
                                relative.startsWith("verification/")
                        },
                )
            }
            val league = when (selection.targetPlatform) {
                ControllerInputPlatform.FTC -> League.FTC
                ControllerInputPlatform.FRC -> League.FRC
                ControllerInputPlatform.XRP -> League.XRP
                ControllerInputPlatform.DESKTOP_GLFW -> error("Desktop input is not a robot project target.")
            }
            ProjectLayout.fieldDefinitionFile(root.path, league).takeIf(File::isFile)?.let(::add)
        }.distinctBy { it.canonicalPath }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }

        return Sha256.compositeHex {
            canonicalFiles.forEach { file ->
                update(file.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
                update(0)
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        update(buffer, 0, count)
                    }
                }
                update(0)
            }
        }
    }

    private fun removableStore(kind: RemovableProjectDocumentKind): VersionedProjectDocumentStore<*> = when (kind) {
        RemovableProjectDocumentKind.ROUTINE -> projectDocuments.routines
        RemovableProjectDocumentKind.CONTROL_SCHEME -> projectDocuments.controls
        RemovableProjectDocumentKind.CONTROLLER_PROFILE -> projectDocuments.controllers
        RemovableProjectDocumentKind.SUBSYSTEM -> projectDocuments.subsystems
    }
}

enum class ProjectExecutionCommand { GENERATE, VERIFY_AND_BUILD, SIMULATE, DEPLOY }

data class ProjectExecutionDecision(
    val accepted: Boolean,
    val message: String,
)

/**
 * One authorization boundary for project-owned external processes. It derives league and simulator
 * identity from the same effective snapshot used by authoring, never from an unrelated UI guess.
 */
class ProjectExecutionCoordinator(
    private val session: ProjectSession,
    private val processGateway: ProjectProcessGateway,
) {
    fun execute(
        workspace: WorkspaceConfig,
        command: ProjectExecutionCommand,
    ): ProjectExecutionDecision {
        val targetPlatform = when (workspace.league) {
            League.FTC -> ControllerInputPlatform.FTC
            League.FRC -> ControllerInputPlatform.FRC
            League.XRP -> ControllerInputPlatform.XRP
        }
        val snapshot = runCatching {
            session.snapshot(workspace.projectPath, targetPlatform, forceReload = true)
        }
            .getOrElse { return ProjectExecutionDecision(false, it.message ?: "Project could not be opened.") }
        val project = snapshot.documents.query
        val expectedLeague = when (workspace.league) {
            League.FTC -> AresLeague.FTC
            League.FRC -> AresLeague.FRC
            League.XRP -> AresLeague.XRP
        }
        if (project.metadata?.league != expectedLeague) {
            return ProjectExecutionDecision(false, "The selected workspace league does not match .ares/project.json.")
        }
        val errors = project.issues.filter { it.severity == ProjectModelSeverity.ERROR }
        if (errors.isNotEmpty()) {
            return ProjectExecutionDecision(false, errors.joinToString(" ") { it.message })
        }
        val simulationPlan = project.simulationPlan
        if (command == ProjectExecutionCommand.SIMULATE && simulationPlan?.isSupported != true) {
            return ProjectExecutionDecision(
                false,
                simulationPlan?.issues?.joinToString(" ") { it.message }
                    ?: "ARES could not select a simulator for this project.",
            )
        }

        when (command) {
            ProjectExecutionCommand.GENERATE ->
                processGateway.generate(workspace.projectPath, workspace.league)
            ProjectExecutionCommand.VERIFY_AND_BUILD ->
                processGateway.verifyAndBuild(workspace.projectPath, workspace.league)
            ProjectExecutionCommand.SIMULATE -> processGateway.simulate(
                workspace.projectPath,
                requireNotNull(simulationPlan).product.id,
                workspace.simulatorCommand,
            )
            ProjectExecutionCommand.DEPLOY ->
                processGateway.deploy(workspace.projectPath, workspace.league)
        }
        return ProjectExecutionDecision(
            true,
            when (command) {
                ProjectExecutionCommand.GENERATE -> "Project generation started."
                ProjectExecutionCommand.VERIFY_AND_BUILD -> "Verification and build started."
                ProjectExecutionCommand.SIMULATE -> "${requireNotNull(simulationPlan).product.id.displayName} started."
                ProjectExecutionCommand.DEPLOY -> "Robot deployment started."
            },
        )
    }
}

interface ProjectProcessGateway {
    fun generate(projectPath: String, league: League)
    fun verifyAndBuild(projectPath: String, league: League)
    fun simulate(projectPath: String, product: SimulationProductId, simulatorCommand: String?)
    fun deploy(projectPath: String, league: League)
}

class StudioProjectProcessGateway(
    private val projectBuild: ProjectBuildService,
    private val simulator: SimulatorProcessService,
    private val deployment: RobotDeploymentService,
) : ProjectProcessGateway {
    override fun generate(projectPath: String, league: League) =
        projectBuild.generateAresProject(projectPath, league)

    override fun verifyAndBuild(projectPath: String, league: League) =
        projectBuild.runBuild(projectPath, league)

    override fun simulate(projectPath: String, product: SimulationProductId, simulatorCommand: String?) =
        simulator.start(projectPath, product, simulatorCommand)

    override fun deploy(projectPath: String, league: League) =
        deployment.deploy(projectPath, league)
}
