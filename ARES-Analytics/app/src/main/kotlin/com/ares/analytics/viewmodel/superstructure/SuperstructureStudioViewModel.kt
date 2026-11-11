package com.ares.analytics.viewmodel.superstructure

import com.ares.analytics.service.project.AresProjectDocumentSnapshot
import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.service.project.persistence.ProjectDocumentDiagnostic
import com.ares.analytics.service.project.persistence.SuperstructureProjectRepository
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.areslib.catalog.ActionDescriptor
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.isAresGenerated
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.SubsystemValueType
import com.areslib.superstructure.LutControlPoint
import com.areslib.superstructure.LutInterpolationMethod
import com.areslib.superstructure.StateTransitionEdge
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureDocumentCodec
import com.areslib.superstructure.SuperstructureDynamicLut
import com.areslib.superstructure.SuperstructureFieldReference
import com.areslib.superstructure.SuperstructureInterlockRule
import com.areslib.superstructure.SuperstructureIssueSeverity
import com.areslib.superstructure.SuperstructureStatePreset
import com.areslib.superstructure.SuperstructureSubsystemTarget
import com.ares.analytics.service.project.persistence.SavedSuperstructureDocument
import com.areslib.superstructure.SuperstructureTargetMode
import com.areslib.superstructure.TransitionTriggerKind
import com.areslib.superstructure.validateSuperstructureProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext


class SuperstructureStudioViewModel(
    projectPath: String,
    private val scope: CoroutineScope,
    private val projectDocuments: AresProjectDocuments = AresProjectDocuments(),
    private val repository: SuperstructureProjectRepository = projectDocuments.superstructures,
    private val checkpointRecorder: ProjectCheckpointRecorder = ProjectCheckpointRecorder.NONE,
    private val targetPlatform: com.areslib.controls.ControllerInputPlatform? = null,
    private val projectSession: ProjectSession? = null,
) {
    private val _state = MutableStateFlow(SuperstructureStudioState(projectPath = projectPath))
    val state: StateFlow<SuperstructureStudioState> = _state.asStateFlow()
    private var previewSession: SuperstructurePreviewSession? = null
    private val reloadGeneration = AtomicLong()

    init {
        reload()
    }

    fun reload(force: Boolean = false) {
        val starting = _state.value
        if (starting.dirty && !force) {
            _state.update { it.copy(pendingSelectionId = it.selectedId) }
            return
        }
        val generation = reloadGeneration.incrementAndGet()
        scope.launch {
            if (!scope.isActive || generation != reloadGeneration.get()) return@launch
            previewSession = null
            _state.update { it.copy(loading = true, error = null, status = "") }
            val checkActive = {
                if (!scope.isActive || generation != reloadGeneration.get()) {
                    throw kotlinx.coroutines.CancellationException("Superstructure reload superseded or scope cancelled.")
                }
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    checkActive()
                    val loaded = loadProjectData(_state.value.projectPath, forceReload = true, checkRequest = checkActive)
                    checkActive()
                    Result.success(loaded)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (t: Exception) {
                    Result.failure(t)
                }
            }
            result.onSuccess { loaded ->
                if (!scope.isActive || generation != reloadGeneration.get()) return@onSuccess
                val snapshot = loaded.snapshot
                val revision = loaded.revision
                val project = snapshot.query
                _state.update { current ->
                    if (!scope.isActive || generation != reloadGeneration.get()) return@update current
                    if (current.projectRevision != null && revision != null && revision.sequence < current.projectRevision.sequence) {
                        return@update current.copy(loading = false)
                    }
                    val selected = project.superstructures.firstOrNull { it.superstructureId == current.selectedId }
                        ?: project.superstructures.firstOrNull()
                    if (current.draft != starting.draft || current.selectedId != starting.selectedId ||
                        current.editorErrors != starting.editorErrors || current.review != starting.review) {
                        return@update current.copy(loading = false)
                    }
                    validate(
                        current.copy(
                            documents = project.superstructures,
                            selectedId = selected?.superstructureId,
                            saved = selected,
                            savedContentHash = selected?.let(SuperstructureDocumentCodec::contentHash),
                            projectRevision = revision ?: current.projectRevision,
                            draft = selected,
                            subsystems = project.subsystems,
                            actions = project.actions,
                            diagnostics = snapshot.diagnostics,
                            selectedStateId = selected?.initialStateId,
                            loading = false, dirty = false,
                            review = null,
                            pendingSelectionId = null, editorErrors = emptyMap(), preview = null,
                        )
                    )
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) return@onFailure
                if (!scope.isActive || generation != reloadGeneration.get()) return@onFailure
                _state.update { it.copy(loading = false, error = error.message ?: "Project documents could not be loaded") }
            }
        }
    }

    fun create(rawId: String, displayName: String) {
        val id = rawId.trim().lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-')
        if (_state.value.dirty) {
            _state.update { it.copy(error = "Save or discard the current draft before creating another coordinator.") }
            return
        }
        if (!id.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) {
            _state.update { it.copy(error = "Use a short ID containing lowercase letters, numbers, and hyphens.") }
            return
        }
        if (_state.value.documents.any { it.superstructureId == id }) {
            select(id)
            return
        }
        val initial = SuperstructureStatePreset("idle", "Idle", "Safe neutral starting posture")
        val fault = SuperstructureStatePreset("fault", "Fault / neutral", "Fail-closed neutral posture")
        val draft = SuperstructureDocument(
            superstructureId = id,
            displayName = displayName.trim().ifBlank { id.replace('-', ' ').replaceFirstChar(Char::uppercase) },
            description = "Coordinates generated mechanisms through Redux without writing hardware directly.",
            initialStateId = initial.stateId,
            states = listOf(initial, fault),
            faultStateId = fault.stateId,
        )
        previewSession = null
        _state.value = validate(
            _state.value.copy(
                selectedId = id,
                saved = null,
                savedContentHash = null,
                draft = draft,
                selectedStateId = initial.stateId,
                step = SuperstructureStudioStep.POSTURES,
                dirty = true,
                status = "New draft created. Nothing has been written yet.",
                error = null,
                review = null,
                editorErrors = emptyMap(),
                preview = null,
            )
        )
    }

    fun select(id: String, force: Boolean = false) {
        if (_state.value.dirty && !force && id != _state.value.selectedId) {
            _state.update { it.copy(pendingSelectionId = id) }
            return
        }
        val selected = _state.value.documents.singleOrNull { it.superstructureId == id } ?: return
        previewSession = null
        _state.value = validate(
            _state.value.copy(
                selectedId = id,
                saved = selected,
                savedContentHash = SuperstructureDocumentCodec.contentHash(selected),
                draft = selected,
                selectedStateId = selected.initialStateId,
                dirty = false,
                review = null,
                pendingSelectionId = null,
                status = "Loaded ${selected.displayName}.",
                error = null,
                editorErrors = emptyMap(),
                preview = null,
            )
        )
    }

    fun confirmDiscard() {
        val target = _state.value.pendingSelectionId
        _state.update { it.copy(review = null, pendingSelectionId = null, error = null) }
        if (target != null && target != _state.value.selectedId) select(target, force = true) else reload(force = true)
    }

    fun cancelDiscard() = _state.update { it.copy(pendingSelectionId = null) }

    fun selectStep(step: SuperstructureStudioStep) = _state.update { it.copy(step = step) }
    fun selectState(stateId: String) = _state.update { it.copy(selectedStateId = stateId) }
    fun setEditorError(key: String, message: String?) = _state.update { state ->
        state.copy(editorErrors = if (message == null) state.editorErrors - key else state.editorErrors + (key to message))
    }

    fun updateMetadata(displayName: String, description: String) = edit { document ->
        document.copy(displayName = displayName.take(80), description = description.take(500))
    }

    fun addState(rawId: String, displayName: String) = edit { document ->
        val id = rawId.trim().replace(Regex("[^A-Za-z0-9_]+"), "_").trim('_')
        require(id.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}"))) { "State ID must start with a letter and contain only letters, digits, or underscores." }
        require(document.states.none { it.stateId == id }) { "State '$id' already exists." }
        val targets = document.states.firstOrNull()?.subsystemTargets.orEmpty().map { target ->
            neutralTarget(target.target)
        }
        document.copy(states = document.states + SuperstructureStatePreset(id, displayName.ifBlank { id }, subsystemTargets = targets))
            .also { _state.update { state -> state.copy(selectedStateId = id) } }
    }

    fun removeSelectedState() = edit { document ->
        val id = _state.value.selectedStateId ?: return@edit document
        require(id != document.initialStateId) { "Choose a different initial state before removing this state." }
        require(id != document.faultStateId) { "Choose a different fault state before removing this state." }
        require(id != document.disabledStateId) { "Choose a different disabled state before removing this state." }
        val remaining = document.states.filterNot { it.stateId == id }
        _state.update { it.copy(selectedStateId = remaining.firstOrNull()?.stateId) }
        document.copy(
            states = remaining.map { state ->
                if (state.timeoutTargetStateId == id) state.copy(timeoutSeconds = null, timeoutTargetStateId = null) else state
            },
            transitions = document.transitions.filterNot {
                it.sourceStateId == id || it.targetStateId == id || it.timeoutTargetStateId == id
            },
        )
    }

    fun setInitialState(id: String) = edit { it.copy(initialStateId = id) }
    fun updateSelectedStateDetails(displayName: String, description: String) = edit { document ->
        val id = _state.value.selectedStateId ?: return@edit document
        document.copy(states = document.states.map { state ->
            if (state.stateId == id) state.copy(displayName = displayName.take(80), description = description.take(500)) else state
        })
    }
    fun setFaultState(id: String) = edit { document ->
        val safeState = document.states.single { it.stateId == id }.copy(
            subsystemTargets = document.states.single { it.stateId == id }.subsystemTargets.map { target ->
                neutralTarget(target.target)
            }
        )
        document.copy(faultStateId = id, states = document.states.map { if (it.stateId == id) safeState else it })
    }

    fun startPreview() {
        val state = validate(_state.value)
        val draft = state.draft ?: return
        if (state.validationErrors.isNotEmpty() || state.editorErrors.isNotEmpty()) {
            _state.value = state.copy(error = "Resolve project errors before running the deterministic preview.")
            return
        }
        runCatching { SuperstructurePreviewSession(draft, state.generatedSubsystems) }
            .onSuccess { session -> previewSession = session; _state.value = state.copy(preview = session.snapshot(), error = null) }
            .onFailure { error -> _state.value = state.copy(preview = null, error = error.message ?: "Preview could not start") }
    }

    fun advancePreview(deltaMs: Long) = updatePreview { it.tick(deltaMs) }
    fun requestPreviewAction(actionKey: String) = updatePreview { it.request(actionKey) }
    fun setPreviewEnabled(enabled: Boolean) = updatePreview { session -> session.enabled = enabled; session.tick(0L) }
    fun injectPreview(reference: SuperstructureFieldReference, condition: PreviewPortCondition) = updatePreview { it.inject(reference, condition) }
    fun setPreviewNumeric(reference: SuperstructureFieldReference, value: Double) = updatePreview { it.setNumeric(reference, value) }
    fun setPreviewBoolean(reference: SuperstructureFieldReference, value: Boolean) = updatePreview { it.setBoolean(reference, value) }
    fun setPreviewString(reference: SuperstructureFieldReference, value: String) = updatePreview { it.setString(reference, value) }

    private fun updatePreview(update: (SuperstructurePreviewSession) -> SuperstructurePreviewSnapshot) {
        val session = previewSession ?: return startPreview()
        runCatching { update(session) }
            .onSuccess { snapshot -> _state.update { it.copy(preview = snapshot, error = null) } }
            .onFailure { error -> _state.update { it.copy(error = error.message ?: "Preview action was rejected") } }
    }

    fun setDisabledState(id: String) = edit { document ->
        val safeState = document.states.single { it.stateId == id }.copy(
            subsystemTargets = document.states.single { it.stateId == id }.subsystemTargets.map { neutralTarget(it.target) },
        )
        document.copy(disabledStateId = id, states = document.states.map { if (it.stateId == id) safeState else it })
    }

    fun setDisabledPolicy(policy: com.areslib.superstructure.SuperstructureDisabledPolicy) = edit {
        it.copy(disabledPolicy = policy)
    }

    fun updateSelectedStateTimeout(seconds: Double?, targetStateId: String?) = edit { document ->
        val id = _state.value.selectedStateId ?: return@edit document
        document.copy(states = document.states.map { state ->
            if (state.stateId == id) state.copy(timeoutSeconds = seconds, timeoutTargetStateId = targetStateId) else state
        })
    }

    fun addSelectedStateLifecycleAction(onEntry: Boolean, actionKey: String) = edit { document ->
        require(actionKey in _state.value.parameterlessActions.mapTo(hashSetOf()) { it.key }) {
            "Lifecycle actions must be parameterless actions from the current project catalog."
        }
        val id = _state.value.selectedStateId ?: return@edit document
        document.copy(states = document.states.map { state ->
            if (state.stateId != id) state else if (onEntry) {
                require(actionKey !in state.onEntryActionKeys) { "That entry action is already selected." }
                state.copy(onEntryActionKeys = state.onEntryActionKeys + actionKey)
            } else {
                require(actionKey !in state.onExitActionKeys) { "That exit action is already selected." }
                state.copy(onExitActionKeys = state.onExitActionKeys + actionKey)
            }
        })
    }

    fun removeSelectedStateLifecycleAction(onEntry: Boolean, actionKey: String) = edit { document ->
        val id = _state.value.selectedStateId ?: return@edit document
        document.copy(states = document.states.map { state ->
            if (state.stateId != id) state else if (onEntry) {
                state.copy(onEntryActionKeys = state.onEntryActionKeys - actionKey)
            } else {
                state.copy(onExitActionKeys = state.onExitActionKeys - actionKey)
            }
        })
    }

    fun addTarget(reference: SuperstructureFieldReference) = edit { document ->
        require(document.states.none { state -> state.subsystemTargets.any { it.target == reference } }) {
            "That target is already part of every state."
        }
        document.copy(states = document.states.map { state ->
            state.copy(subsystemTargets = state.subsystemTargets + neutralTarget(reference))
        })
    }

    fun removeTarget(reference: SuperstructureFieldReference) {
        _state.update { state -> state.copy(editorErrors = state.editorErrors.filterKeys { !it.contains(":${reference.subsystemUid}.${reference.fieldUid}") }) }
        edit { document ->
        document.copy(states = document.states.map { state ->
            state.copy(subsystemTargets = state.subsystemTargets.filterNot { it.target == reference })
        })
        }
    }

    fun updateSelectedTarget(target: SuperstructureSubsystemTarget) = edit { document ->
        val stateId = _state.value.selectedStateId ?: return@edit document
        if (target.targetMode != SuperstructureTargetMode.CONSTANT) {
            _state.update { state -> state.copy(editorErrors = state.editorErrors - "target:$stateId:${target.target.subsystemUid}.${target.target.fieldUid}") }
        }
        require(stateId !in setOf(document.faultStateId, document.disabledStateId) || target == neutralTarget(target.target)) {
            "The fault state must retain each subsystem's declared safe neutral value."
        }
        document.copy(states = document.states.map { state ->
            if (state.stateId != stateId) state else state.copy(
                subsystemTargets = state.subsystemTargets.map { existing ->
                    if (existing.target == target.target) target else existing
                }
            )
        })
    }

    fun addActionTransition(source: String, target: String, actionKey: String) = addTransition(
        StateTransitionEdge(
            transitionId = uniqueId("request-${source.lowercase()}-${target.lowercase()}"),
            sourceStateId = source,
            targetStateId = target,
            triggerKind = TransitionTriggerKind.ACTION_REQUEST,
            actionKey = actionKey,
        )
    )

    fun addSensorTransition(source: String, target: String, field: SuperstructureFieldOption) {
        val guard = typedGuard(field)
        addTransition(
            StateTransitionEdge(
                transitionId = uniqueId("auto-${source.lowercase()}-${target.lowercase()}"),
                sourceStateId = source,
                targetStateId = target,
                triggerKind = TransitionTriggerKind.SENSOR_CONDITION_AUTO,
                guards = listOf(guard),
                debounceMs = 100,
            )
        )
    }

    fun addTimedTransition(source: String, target: String, seconds: Double) = addTransition(
        StateTransitionEdge(
            transitionId = uniqueId("wait-${source.lowercase()}-${target.lowercase()}"),
            sourceStateId = source,
            targetStateId = target,
            triggerKind = TransitionTriggerKind.TIME_ELAPSED,
            timeoutSeconds = seconds,
        )
    )

    fun updateTransition(edge: StateTransitionEdge) = edit { document ->
        document.copy(transitions = document.transitions.map { if (it.transitionId == edge.transitionId) edge else it })
    }
    fun addGuard(transitionId: String, field: SuperstructureFieldOption) = edit { document ->
        document.copy(transitions = document.transitions.map { edge ->
            if (edge.transitionId != transitionId) edge else {
                require(edge.guards.none { it.source == field.reference }) { "That evidence field already guards this transition." }
                edge.copy(
                    guards = edge.guards + typedGuard(field),
                    timeoutSeconds = if (edge.triggerKind == TransitionTriggerKind.ACTION_REQUEST) edge.timeoutSeconds ?: 1.0 else edge.timeoutSeconds,
                    timeoutTargetStateId = if (edge.triggerKind == TransitionTriggerKind.ACTION_REQUEST) edge.timeoutTargetStateId ?: document.faultStateId else edge.timeoutTargetStateId,
                )
            }
        })
    }
    fun removeGuard(transitionId: String, guardId: String) = edit { document ->
        document.copy(transitions = document.transitions.map { edge ->
            if (edge.transitionId != transitionId) edge else edge.copy(guards = edge.guards.filterNot { it.guardId == guardId })
        })
    }
    fun removeTransition(id: String) {
        _state.update { state -> state.copy(editorErrors = state.editorErrors.filterKeys { !it.startsWith("transition:$id:") }) }
        edit { it.copy(transitions = it.transitions.filterNot { edge -> edge.transitionId == id }) }
    }

    fun moveStateNode(stateId: String, x: Double, y: Double) = edit { document ->
        val updatedLayouts = document.nodeLayouts.toMutableMap()
        updatedLayouts[stateId] = com.areslib.superstructure.StateNodeLayout(x = x, y = y)
        document.copy(nodeLayouts = updatedLayouts)
    }

    fun setStateflowGraphMode(enabled: Boolean) {
        _state.update { it.copy(stateflowGraphMode = enabled) }
    }

    fun updateGraphPanAndZoom(panX: Float, panY: Float, zoom: Float) {
        _state.update { it.copy(graphPanX = panX, graphPanY = panY, graphZoom = zoom) }
    }

    fun addInterlock(source: SuperstructureFieldOption, constrained: SuperstructureFieldOption) = edit { document ->
        SuperstructureDocumentMutators.appendInterlock(
            document = document,
            source = source,
            constrained = constrained,
            sourceFields = _state.value.sourceFields,
            targetFields = _state.value.targetFields,
            uniqueId = ::uniqueId,
        )
    }
    fun updateInterlock(rule: SuperstructureInterlockRule) = edit { document ->
        SuperstructureDocumentMutators.updateInterlockPreservingDescription(
            document = document,
            rule = rule,
            sourceFields = _state.value.sourceFields,
            targetFields = _state.value.targetFields,
        )
    }
    fun removeInterlock(id: String) {
        _state.update { state -> state.copy(editorErrors = state.editorErrors.filterKeys { !it.startsWith("interlock:$id:") }) }
        edit { it.copy(interlocks = it.interlocks.filterNot { rule -> rule.ruleId == id }) }
    }

    fun addHealthFallback(source: SuperstructureFieldOption) = edit { document ->
        SuperstructureDocumentMutators.appendHealthFallback(document, source, ::uniqueId)
    }

    fun updateHealthFallback(policy: com.areslib.superstructure.SuperstructureHealthFallbackPolicy) = edit { document ->
        document.copy(healthFallbacks = document.healthFallbacks.map { if (it.policyId == policy.policyId) policy else it })
    }

    fun removeHealthFallback(id: String) = edit { document ->
        document.copy(healthFallbacks = document.healthFallbacks.filterNot { it.policyId == id })
    }

    fun addLut() = edit { document -> SuperstructureDocumentMutators.appendLut(document, ::uniqueId) }
    fun updateLut(lut: SuperstructureDynamicLut) = edit { document ->
        document.copy(luts = document.luts.map { if (it.lutId == lut.lutId) lut else it })
    }
    fun removeLut(id: String) {
        _state.update { state -> state.copy(editorErrors = state.editorErrors.filterKeys { !it.startsWith("lut:$id:") }) }
        edit { it.copy(luts = it.luts.filterNot { lut -> lut.lutId == id }) }
    }

    fun reviewSave() {
        _state.value = SuperstructureDocumentMutators.evaluateReviewSave(_state.value)
    }


    fun applyCoordinationPreset(preset: SuperstructureCoordinationPreset, clearance: ClearanceInterlockConfiguration? = null) {
        val error = clearance?.let { clearanceInterlockError(_state.value, it) }
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        edit { document -> applySuperstructureCoordinationPreset(_state.value, document, preset, clearance) }
    }

    fun dismissReview() = _state.update { it.copy(review = null) }

    fun confirmSave(token: String) {
        val state = _state.value
        val draft = state.draft ?: return
        val review = state.review
        if (review == null || review.confirmationToken != token || review.candidateContentHash != SuperstructureDocumentCodec.contentHash(draft)) {
            _state.update { it.copy(review = null, error = "The draft changed after review. Review it again before saving.") }
            return
        }
        SuperstructurePersistenceCoordinator.executeConfirmSave(
            scope = scope,
            stateFlow = _state,
            reloadGeneration = reloadGeneration,
            projectSession = projectSession,
            repository = repository,
            checkpointRecorder = checkpointRecorder,
            loadData = { path, force -> loadProjectData(path, force) },
            validateState = ::validate,
            state = state,
            draft = draft,
            review = review,
        )
    }

    private suspend fun loadProjectData(
        projectPath: String,
        forceReload: Boolean,
        checkRequest: (() -> Unit)? = null,
    ): SuperstructureProjectLoadResult = SuperstructurePersistenceCoordinator.loadProjectData(
        scope = scope,
        projectPath = projectPath,
        forceReload = forceReload,
        targetPlatform = targetPlatform,
        projectSession = projectSession,
        projectDocuments = projectDocuments,
        checkRequest = checkRequest,
    )

    private fun addTransition(edge: StateTransitionEdge) = edit { document ->
        SuperstructureDocumentMutators.addPrioritizedTransition(document, edge)
    }

    private fun edit(transform: (SuperstructureDocument) -> SuperstructureDocument) {
        val current = _state.value.draft ?: return
        runCatching { transform(current) }
            .onSuccess { draft ->
                previewSession = null
                _state.value = validate(_state.value.copy(draft = draft, dirty = draft != _state.value.saved, review = null, preview = null, status = "", error = null))
            }
            .onFailure { error -> _state.update { it.copy(error = error.message ?: "That edit is not valid") } }
    }

    private fun validate(state: SuperstructureStudioState): SuperstructureStudioState =
        SuperstructureDocumentMutators.validateStudioState(state)

    private fun neutralTarget(reference: SuperstructureFieldReference): SuperstructureSubsystemTarget =
        SuperstructureDocumentMutators.neutralTarget(_state.value.subsystems, reference)

    private fun typedGuard(field: SuperstructureFieldOption) =
        SuperstructureDocumentMutators.typedGuard(field, ::uniqueId)

    private fun uniqueId(base: String): String =
        SuperstructureDocumentMutators.uniqueId(_state.value.draft, base)
}

