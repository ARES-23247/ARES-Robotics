package com.ares.analytics.viewmodel.superstructure

import com.ares.analytics.service.project.AresProjectDocumentSnapshot
import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.service.project.persistence.SavedSuperstructureDocument
import com.ares.analytics.service.project.persistence.SuperstructureProjectRepository
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.areslib.controls.ControllerInputPlatform
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.superstructure.SuperstructureFieldReference
import com.areslib.subsystem.SubsystemValueType
import com.areslib.superstructure.StateTransitionEdge
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureInterlockRule
import com.areslib.superstructure.SuperstructureStatePreset
import com.areslib.superstructure.TransitionTriggerKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Curated 1-click multi-mechanism coordination recipes for Superstructure Studio.
 */
enum class SuperstructureCoordinationPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: String,
) {
    STOW_ALL_SAFELY(
        id = "stow_all_safely",
        title = "Stow All Safely",
        subtitle = "Bind all subsystem targets to safe neutral in a Stow posture",
        badge = "POSTURE + SAFE RETURN",
    ),
    INTAKE_HAND_OFF(
        id = "intake_hand_off",
        title = "Intake Hand-Off",
        subtitle = "Create Intake → Hand-Off states with automatic sensor/beam-break route",
        badge = "AUTO SENSOR ROUTE",
    ),
    RAISE_BEFORE_EXTEND_INTERLOCK(
        id = "raise_before_extend_interlock",
        title = "Raise Before Extending Arm",
        subtitle = "Add a collision interlock clamping extension until clearance height is reached",
        badge = "COLLISION GUARD",
    ),
}

fun applySuperstructureCoordinationPreset(
    state: SuperstructureStudioState,
    document: SuperstructureDocument,
    preset: SuperstructureCoordinationPreset,
    clearance: ClearanceInterlockConfiguration? = null,
): SuperstructureDocument {
    if (preset == SuperstructureCoordinationPreset.RAISE_BEFORE_EXTEND_INTERLOCK) {
        if (clearance == null) return document
        require(clearanceInterlockError(state, clearance) == null) { clearanceInterlockError(state, clearance).orEmpty() }
    }
    fun ensureAllTargetsBound(doc: SuperstructureDocument): SuperstructureDocument {
        val availableRefs = state.targetFields.map { it.reference }
        if (availableRefs.isEmpty()) return doc
        return doc.copy(
            states = doc.states.map { presetState ->
                val existingRefs = presetState.subsystemTargets.mapTo(hashSetOf()) { it.target }
                val missing = availableRefs.filterNot { it in existingRefs }
                    .map { ref -> SuperstructureDocumentMutators.neutralTarget(state.subsystems, ref) }
                presetState.copy(subsystemTargets = presetState.subsystemTargets + missing)
            },
        )
    }

    val baseDoc = ensureAllTargetsBound(document)
    val templateTargets = baseDoc.states.firstOrNull()?.subsystemTargets.orEmpty().map {
        SuperstructureDocumentMutators.neutralTarget(state.subsystems, it.target)
    }

    return when (preset) {
        SuperstructureCoordinationPreset.STOW_ALL_SAFELY -> {
            val hasStow = baseDoc.states.any { it.stateId == "stow" }
            val withStow = if (hasStow) {
                baseDoc.copy(
                    states = baseDoc.states.map { s ->
                        if (s.stateId == "stow") s.copy(subsystemTargets = templateTargets) else s
                    },
                )
            } else {
                baseDoc.copy(
                    states = baseDoc.states + SuperstructureStatePreset(
                        stateId = "stow",
                        displayName = "Stow All Safely",
                        description = "All mechanisms retracted to safe neutral inside frame perimeter.",
                        subsystemTargets = templateTargets,
                    ),
                )
            }
            withStow
        }

        SuperstructureCoordinationPreset.INTAKE_HAND_OFF -> {
            var updated = baseDoc
            if (updated.states.none { it.stateId == "intake" }) {
                updated = updated.copy(
                    states = updated.states + SuperstructureStatePreset(
                        stateId = "intake",
                        displayName = "Intaking Game Piece",
                        description = "Run intake rollers and position wrist/arm to collect game piece.",
                        subsystemTargets = templateTargets,
                    ),
                )
            }
            if (updated.states.none { it.stateId == "handoff" }) {
                updated = updated.copy(
                    states = updated.states + SuperstructureStatePreset(
                        stateId = "handoff",
                        displayName = "Holding / Hand-Off",
                        description = "Stop intake rollers and secure game piece for scoring.",
                        subsystemTargets = templateTargets,
                    ),
                )
            }
            val alreadyRouted = updated.transitions.any {
                it.sourceStateId == "intake" && it.targetStateId == "handoff"
            }
            if (!alreadyRouted) {
                val sensorField = state.sourceFields.firstOrNull { it.field.type == SubsystemValueType.BOOLEAN }
                    ?: state.sourceFields.firstOrNull()
                val edge = if (sensorField != null) {
                    val guard = SuperstructureDocumentMutators.typedGuard(sensorField) { raw ->
                        SuperstructureDocumentMutators.uniqueId(updated, raw)
                    }
                    StateTransitionEdge(
                        transitionId = SuperstructureDocumentMutators.uniqueId(updated, "auto-intake-handoff"),
                        sourceStateId = "intake",
                        targetStateId = "handoff",
                        triggerKind = TransitionTriggerKind.SENSOR_CONDITION_AUTO,
                        guards = listOf(guard),
                        debounceMs = 80,
                    )
                } else {
                    StateTransitionEdge(
                        transitionId = SuperstructureDocumentMutators.uniqueId(updated, "wait-intake-handoff"),
                        sourceStateId = "intake",
                        targetStateId = "handoff",
                        triggerKind = TransitionTriggerKind.TIME_ELAPSED,
                        timeoutSeconds = 0.6,
                    )
                }
                updated = SuperstructureDocumentMutators.addPrioritizedTransition(updated, edge)
            }
            updated
        }

        SuperstructureCoordinationPreset.RAISE_BEFORE_EXTEND_INTERLOCK -> {
            val reviewed = requireNotNull(clearance)
            val numericSource = state.sourceFields.single { it.reference == reviewed.primary }
            val numericTarget = state.targetFields.single { it.reference == reviewed.constrained }
            val ruleId = SuperstructureDocumentMutators.uniqueId(
                baseDoc,
                "interlock-clearance-${numericTarget.subsystem.documentId}-${numericTarget.field.fieldId}",
            )
            val rule = SuperstructureInterlockRule(
                ruleId = ruleId,
                description = "Keep ${numericTarget.label} stowed until ${numericSource.label} clears ${reviewed.threshold} ${numericSource.field.unit}.",
                primary = numericSource.reference,
                constrained = numericTarget.reference,
                conditionComparison = InterlockComparison.LESS_THAN,
                conditionThreshold = reviewed.threshold,
                clampMinimum = reviewed.clampMinimum,
                clampMaximum = reviewed.clampMaximum,
            )
            baseDoc.copy(interlocks = baseDoc.interlocks + rule)
        }
    }
}

/** Explicit physical clearance and reviewed target range; no field-order or unit assumptions. */
data class ClearanceInterlockConfiguration(
    val primary: SuperstructureFieldReference,
    val constrained: SuperstructureFieldReference,
    val threshold: Double,
    val clampMinimum: Double,
    val clampMaximum: Double,
)

fun clearanceInterlockError(state: SuperstructureStudioState, config: ClearanceInterlockConfiguration): String? {
    val source = state.sourceFields.singleOrNull { it.reference == config.primary }
        ?: return "Choose an available clearance measurement."
    val target = state.targetFields.singleOrNull { it.reference == config.constrained }
        ?: return "Choose an available extension target."
    val numeric = setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)
    if (source.field.role != SubsystemFieldRole.MEASUREMENT || source.field.type !in numeric || source.field.unit.isNullOrBlank()) {
        return "Clearance must use a numeric measurement with explicit units."
    }
    if (target.field.type != SubsystemValueType.DOUBLE || target.field.unit.isNullOrBlank()) return "This guard requires a DOUBLE target with explicit units."
    if (source.subsystem.uid == target.subsystem.uid) return "Choose a target on a different mechanism."
    if (!config.threshold.isFinite() || !config.clampMinimum.isFinite() || !config.clampMaximum.isFinite()) {
        return "Enter finite clearance and clamp values."
    }
    if (config.clampMinimum > config.clampMaximum) return "Clamp minimum must not exceed maximum."
    if (source.field.minimum?.let { config.threshold < it } == true || source.field.maximum?.let { config.threshold > it } == true) {
        return "Clearance must be within the measurement bounds."
    }
    if (target.field.minimum?.let { config.clampMinimum < it } == true || target.field.maximum?.let { config.clampMaximum > it } == true) {
        return "Clamp range must be within the target bounds."
    }
    return null
}

internal data class SuperstructureProjectLoadResult(
    val snapshot: AresProjectDocumentSnapshot,
    val revision: ProjectSessionRevision?,
)

internal data class SuperstructureSaveOutcome(
    val saved: SavedSuperstructureDocument,
    val revision: ProjectSessionRevision?,
)

internal object SuperstructurePersistenceCoordinator {
    suspend fun loadProjectData(
        scope: CoroutineScope,
        projectPath: String,
        forceReload: Boolean,
        targetPlatform: ControllerInputPlatform?,
        projectSession: ProjectSession?,
        projectDocuments: AresProjectDocuments,
        checkRequest: (() -> Unit)? = null,
    ): SuperstructureProjectLoadResult {
        val coroutineJob = coroutineContext[Job]
        val checkLiveness: () -> Unit = {
            if (!scope.isActive || coroutineJob?.isActive == false) {
                throw CancellationException("Superstructure document load cancelled.")
            }
            checkRequest?.invoke()
        }
        return if (projectSession != null && targetPlatform != null) {
            val sessionSnapshot = projectSession.snapshot(projectPath, targetPlatform, forceReload, checkLiveness)
            checkLiveness()
            SuperstructureProjectLoadResult(sessionSnapshot.documents, sessionSnapshot.revision)
        } else {
            checkLiveness()
            val loaded = projectDocuments.load(projectPath, targetPlatform)
            checkLiveness()
            SuperstructureProjectLoadResult(loaded, null)
        }
    }

    fun executeConfirmSave(
        scope: CoroutineScope,
        stateFlow: MutableStateFlow<SuperstructureStudioState>,
        reloadGeneration: AtomicLong,
        projectSession: ProjectSession?,
        repository: SuperstructureProjectRepository,
        checkpointRecorder: ProjectCheckpointRecorder,
        loadData: suspend (String, Boolean) -> SuperstructureProjectLoadResult,
        validateState: (SuperstructureStudioState) -> SuperstructureStudioState,
        state: SuperstructureStudioState,
        draft: SuperstructureDocument,
        review: SuperstructureSaveReview,
    ) {
        val saveGeneration = reloadGeneration.incrementAndGet()
        scope.launch {
            stateFlow.update { it.copy(loading = true, error = null) }
            val result = withContext(Dispatchers.IO) {
                try {
                    val session = projectSession
                    val revision = state.projectRevision
                    if (session != null && revision != null) {
                        when (val mutation = session.saveSuperstructure(revision, draft, review.expectedContentHash)) {
                            is ProjectSessionMutationResult.Applied ->
                                Result.success(SuperstructureSaveOutcome(mutation.value, mutation.snapshot.revision))
                            is ProjectSessionMutationResult.Stale ->
                                Result.failure(IllegalStateException("The project changed after this coordinator loaded. Reload before saving."))
                            is ProjectSessionMutationResult.Conflict ->
                                Result.failure(IllegalStateException(mutation.message))
                            is ProjectSessionMutationResult.Failed ->
                                Result.failure(IllegalStateException(mutation.message))
                        }
                    } else {
                        val project = loadData(state.projectPath, true).snapshot.query
                        val savedDoc = repository.save(
                            state.projectPath,
                            draft,
                            review.expectedContentHash,
                            project.subsystems,
                            project.actions.mapTo(linkedSetOf()) { it.key },
                            project.actions.asSequence().filter { it.parameters.isEmpty() }.mapTo(linkedSetOf()) { it.key },
                        )
                        Result.success(SuperstructureSaveOutcome(savedDoc, null))
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Exception) {
                    Result.failure(t)
                }
            }
            result.onSuccess { outcome ->
                val saved = outcome.saved
                val savedRevision = outcome.revision
                stateFlow.update { current ->
                    if (saveGeneration != reloadGeneration.get()) return@update current
                    val documents = (current.documents.filterNot { it.superstructureId == saved.document.superstructureId } + saved.document)
                        .sortedBy { it.displayName.lowercase() }
                    val savingSelected = current.selectedId == saved.document.superstructureId
                    val savedDoc = if (savingSelected) saved.document else current.saved
                    val savedHash = if (savingSelected) saved.contentHash else current.savedContentHash
                    val isDirty = if (savingSelected) current.draft != saved.document else current.dirty
                    val selectedState = if (savingSelected && current.selectedStateId != null) {
                        if (current.draft?.states?.any { it.stateId == current.selectedStateId } == true) current.selectedStateId else saved.document.initialStateId
                    } else {
                        current.selectedStateId
                    }
                    validateState(
                        current.copy(
                            documents = documents,
                            selectedId = current.selectedId,
                            saved = savedDoc,
                            savedContentHash = savedHash,
                            projectRevision = savedRevision ?: current.projectRevision,
                            draft = current.draft,
                            selectedStateId = selectedState,
                            loading = false,
                            dirty = isDirty,
                            review = if (savingSelected && current.draft == saved.document) null else current.review,
                            status = "Saved ${saved.currentFile.path} and retained immutable history ${saved.historyFile.name}.",
                            error = null,
                        ),
                    )
                }
                scope.launch {
                    runCatching {
                        val root = File(state.projectPath).canonicalFile
                        checkpointRecorder.checkpoint(
                            state.projectPath,
                            "Saved ${saved.document.displayName} superstructure",
                            setOf(
                                saved.currentFile.relativeTo(root).invariantSeparatorsPath,
                                saved.historyFile.relativeTo(root).invariantSeparatorsPath,
                            ),
                        )
                    }.onFailure { failure ->
                        if (saveGeneration == reloadGeneration.get()) {
                            stateFlow.update {
                                it.copy(status = "Superstructure saved, but automatic Project History checkpoint failed: ${failure.message}")
                            }
                        }
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (saveGeneration != reloadGeneration.get()) return@onFailure
                stateFlow.update {
                    it.copy(loading = false, review = null, error = error.message ?: "Superstructure could not be saved")
                }
            }
        }
    }
}
