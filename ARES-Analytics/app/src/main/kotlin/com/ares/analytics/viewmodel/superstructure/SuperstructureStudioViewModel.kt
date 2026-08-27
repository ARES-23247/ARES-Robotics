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
import com.areslib.superstructure.SuperstructureTargetMode
import com.areslib.superstructure.TransitionTriggerKind
import com.areslib.superstructure.validateSuperstructureProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class SuperstructureStudioStep {
    POSTURES,
    TRANSITIONS,
    REVIEW,
}

data class SuperstructureFieldOption(
    val subsystem: SubsystemDocument,
    val field: SubsystemStateFieldDocument,
) {
    val reference: SuperstructureFieldReference = SuperstructureFieldReference(subsystem.uid, field.uid)
    val label: String = "${subsystem.displayName} · ${field.displayName}${field.unit?.let { " ($it)" }.orEmpty()}"
}

data class SuperstructureSaveReview(
    val expectedContentHash: String?,
    val candidateContentHash: String,
    val confirmationToken: String,
    val summary: List<String>,
)

data class SuperstructureStudioState(
    val projectPath: String,
    val documents: List<SuperstructureDocument> = emptyList(),
    val selectedId: String? = null,
    val saved: SuperstructureDocument? = null,
    val savedContentHash: String? = null,
    val projectRevision: ProjectSessionRevision? = null,
    val draft: SuperstructureDocument? = null,
    val subsystems: List<SubsystemDocument> = emptyList(),
    val actions: List<ActionDescriptor> = emptyList(),
    val diagnostics: List<ProjectDocumentDiagnostic> = emptyList(),
    val step: SuperstructureStudioStep = SuperstructureStudioStep.POSTURES,
    val selectedStateId: String? = null,
    val validationErrors: List<String> = emptyList(),
    val validationWarnings: List<String> = emptyList(),
    val editorErrors: Map<String, String> = emptyMap(),
    val review: SuperstructureSaveReview? = null,
    val loading: Boolean = true,
    val dirty: Boolean = false,
    val status: String = "",
    val error: String? = null,
    val pendingSelectionId: String? = null,
    val preview: SuperstructurePreviewSnapshot? = null,
    val stateflowGraphMode: Boolean = true,
    val graphPanX: Float = 0f,
    val graphPanY: Float = 0f,
    val graphZoom: Float = 1f,
) {
    val generatedSubsystems: List<SubsystemDocument>
        get() = subsystems.filter { it.implementation.kind.isAresGenerated() }
    val targetFields: List<SuperstructureFieldOption>
        get() = generatedSubsystems.flatMap { subsystem ->
            subsystem.stateFields.filter { it.role == SubsystemFieldRole.TARGET }
                .map { SuperstructureFieldOption(subsystem, it) }
        }
    val sourceFields: List<SuperstructureFieldOption>
        get() = generatedSubsystems.flatMap { subsystem ->
            subsystem.stateFields.map { SuperstructureFieldOption(subsystem, it) }
        }
    val parameterlessActions: List<ActionDescriptor>
        get() = actions.filter { it.parameters.isEmpty() }
    val canSave: Boolean
        get() = draft != null && dirty && validationErrors.isEmpty() && editorErrors.isEmpty() && review == null
}

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

    init {
        reload()
    }

    fun reload(force: Boolean = false) {
        if (_state.value.dirty && !force) {
            _state.update { it.copy(pendingSelectionId = it.selectedId, error = "Choose Reload again after discarding or save the current draft first.") }
            return
        }
        scope.launch {
            previewSession = null
            _state.update { it.copy(loading = true, error = null, status = "") }
            val result = withContext(Dispatchers.IO) { runCatching { loadProjectDocuments(forceReload = true) } }
            result.onSuccess { snapshot ->
                val project = snapshot.query
                val selected = project.superstructures.firstOrNull { it.superstructureId == _state.value.selectedId }
                    ?: project.superstructures.firstOrNull()
                _state.value = validate(
                    _state.value.copy(
                        documents = project.superstructures,
                        selectedId = selected?.superstructureId,
                        saved = selected,
                        savedContentHash = selected?.let(SuperstructureDocumentCodec::contentHash),
                        projectRevision = projectSession?.state?.value?.revision,
                        draft = selected,
                        subsystems = project.subsystems,
                        actions = project.actions,
                        diagnostics = snapshot.diagnostics,
                        selectedStateId = selected?.initialStateId,
                        loading = false,
                        dirty = false,
                        review = null,
                        pendingSelectionId = null,
                        editorErrors = emptyMap(),
                        preview = null,
                    )
                )
            }.onFailure { error ->
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
        _state.update { it.copy(dirty = false, pendingSelectionId = null, error = null) }
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
        require(source.field.type in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) { "Interlock evidence must be numeric." }
        require(constrained.field.role == SubsystemFieldRole.TARGET && constrained.field.type in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) {
            "Interlocks can clamp only numeric target fields."
        }
        val rule = SuperstructureInterlockRule(
            ruleId = uniqueId("limit-${constrained.subsystem.documentId}-${constrained.field.fieldId}"),
            description = "",
            primary = source.reference,
            constrained = constrained.reference,
            conditionThreshold = 0.0,
            clampMinimum = constrained.field.minimum,
            clampMaximum = constrained.field.defaultNumber ?: constrained.field.defaultInt?.toDouble() ?: 0.0,
        )
        document.copy(
            interlocks = document.interlocks + rule.copy(
                description = automaticInterlockDescription(rule, _state.value.sourceFields, _state.value.targetFields)
                    ?: "Clamp the selected target while the reviewed safety condition is active.",
            ),
        )
    }
    fun updateInterlock(rule: SuperstructureInterlockRule) = edit { document ->
        val existing = document.interlocks.firstOrNull { it.ruleId == rule.ruleId }
        val descriptionWasEdited = existing != null && rule.description != existing.description
        val preserveStudentDescription = descriptionWasEdited || existing?.let { current ->
            !isAutomaticInterlockDescription(current, _state.value.sourceFields, _state.value.targetFields)
        } == true
        val updated = if (preserveStudentDescription) {
            rule
        } else {
            rule.copy(
                description = automaticInterlockDescription(rule, _state.value.sourceFields, _state.value.targetFields)
                    ?: rule.description,
            )
        }
        document.copy(interlocks = document.interlocks.map { if (it.ruleId == rule.ruleId) updated else it })
    }
    fun removeInterlock(id: String) {
        _state.update { state -> state.copy(editorErrors = state.editorErrors.filterKeys { !it.startsWith("interlock:$id:") }) }
        edit { it.copy(interlocks = it.interlocks.filterNot { rule -> rule.ruleId == id }) }
    }

    fun addHealthFallback(source: SuperstructureFieldOption) = edit { document ->
        require(document.healthFallbacks.none { it.source == source.reference }) {
            "That cached port already has a health fallback."
        }
        document.copy(healthFallbacks = document.healthFallbacks + com.areslib.superstructure.SuperstructureHealthFallbackPolicy(
            policyId = uniqueId("health-${source.subsystem.documentId}-${source.field.fieldId}"),
            source = source.reference,
            fallbackStateId = document.faultStateId,
            description = "Enter the reviewed neutral fault posture when ${source.label} is unhealthy.",
        ))
    }

    fun updateHealthFallback(policy: com.areslib.superstructure.SuperstructureHealthFallbackPolicy) = edit { document ->
        document.copy(healthFallbacks = document.healthFallbacks.map { if (it.policyId == policy.policyId) policy else it })
    }

    fun removeHealthFallback(id: String) = edit { document ->
        document.copy(healthFallbacks = document.healthFallbacks.filterNot { it.policyId == id })
    }

    fun addLut() = edit { document ->
        document.copy(luts = document.luts + SuperstructureDynamicLut(
            lutId = uniqueId("lookup"),
            displayName = "New lookup table",
            interpolation = LutInterpolationMethod.LINEAR,
            controlPoints = listOf(LutControlPoint(0.0, 0.0), LutControlPoint(1.0, 1.0)),
        ))
    }
    fun updateLut(lut: SuperstructureDynamicLut) = edit { document ->
        document.copy(luts = document.luts.map { if (it.lutId == lut.lutId) lut else it })
    }
    fun removeLut(id: String) {
        _state.update { state -> state.copy(editorErrors = state.editorErrors.filterKeys { !it.startsWith("lut:$id:") }) }
        edit { it.copy(luts = it.luts.filterNot { lut -> lut.lutId == id }) }
    }

    fun reviewSave() {
        val state = validate(_state.value)
        val draft = state.draft ?: return
        if (state.validationErrors.isNotEmpty() || state.editorErrors.isNotEmpty()) {
            _state.value = state.copy(error = "Resolve the listed errors before reviewing a save.")
            return
        }
        val candidateHash = SuperstructureDocumentCodec.contentHash(draft)
        val token = "${state.savedContentHash.orEmpty()}:$candidateHash"
        val summary = listOf(
            "${draft.states.size} complete state presets",
            "${draft.transitions.size} transitions (${draft.transitions.count { it.triggerKind == TransitionTriggerKind.ACTION_REQUEST }} driver/autonomous actions)",
            "${draft.interlocks.size} cross-mechanism clamps",
            "${draft.luts.size} lookup tables",
            "${draft.healthFallbacks.size} cached-port health fallbacks",
            "${draft.states.sumOf { it.onEntryActionKeys.size + it.onExitActionKeys.size }} lifecycle actions",
            "Fault destination: ${draft.faultStateId}",
            "Disabled destination: ${draft.disabledStateId} (${draft.disabledPolicy.name.lowercase().replace('_', ' ')})",
        )
        _state.value = state.copy(review = SuperstructureSaveReview(state.savedContentHash, candidateHash, token, summary), error = null)
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
        scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val session = projectSession
                    val revision = state.projectRevision
                    if (session != null && revision != null) {
                        when (val result = session.saveSuperstructure(revision, draft, review.expectedContentHash)) {
                            is ProjectSessionMutationResult.Applied -> result.value
                            is ProjectSessionMutationResult.Stale -> error("The project changed after this coordinator loaded. Reload before saving.")
                            is ProjectSessionMutationResult.Conflict -> error(result.message)
                            is ProjectSessionMutationResult.Failed -> error(result.message)
                        }
                    } else {
                        val project = loadProjectDocuments(forceReload = true).query
                        repository.save(
                            state.projectPath,
                            draft,
                            review.expectedContentHash,
                            project.subsystems,
                            project.actions.mapTo(linkedSetOf()) { it.key },
                            project.actions.asSequence().filter { it.parameters.isEmpty() }.mapTo(linkedSetOf()) { it.key },
                        )
                    }
                }
            }
            result.onSuccess { saved ->
                val documents = (state.documents.filterNot { it.superstructureId == saved.document.superstructureId } + saved.document)
                    .sortedBy { it.displayName.lowercase() }
                _state.value = validate(
                    state.copy(
                        documents = documents,
                        selectedId = saved.document.superstructureId,
                        saved = saved.document,
                        savedContentHash = saved.contentHash,
                        projectRevision = projectSession?.state?.value?.revision ?: state.projectRevision,
                        draft = saved.document,
                        loading = false,
                        dirty = false,
                        review = null,
                        status = "Saved ${saved.currentFile.path} and retained immutable history ${saved.historyFile.name}.",
                        error = null,
                    )
                )
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
                        _state.update { it.copy(status = "Superstructure saved, but automatic Project History checkpoint failed: ${failure.message}") }
                    }
                }
            }.onFailure { error ->
                _state.update { it.copy(loading = false, review = null, error = error.message ?: "Superstructure could not be saved") }
            }
        }
    }

    private fun loadProjectDocuments(forceReload: Boolean): AresProjectDocumentSnapshot {
        val target = targetPlatform
        return if (projectSession != null && target != null) {
            projectSession.snapshot(_state.value.projectPath, target, forceReload).documents
        } else {
            projectDocuments.load(_state.value.projectPath, target)
        }
    }

    private fun addTransition(edge: StateTransitionEdge) = edit { document ->
        val prioritized = if (edge.triggerKind == TransitionTriggerKind.ACTION_REQUEST) edge else {
            val next = document.transitions.asSequence()
                .filter { it.sourceStateId == edge.sourceStateId && it.triggerKind != TransitionTriggerKind.ACTION_REQUEST }
                .map { it.priority }
                .maxOrNull()?.plus(10) ?: 0
            edge.copy(priority = next)
        }
        document.copy(transitions = document.transitions + prioritized)
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

    private fun validate(state: SuperstructureStudioState): SuperstructureStudioState {
        val document = state.draft ?: return state.copy(validationErrors = emptyList(), validationWarnings = emptyList())
        val issues = validateSuperstructureProject(
            document,
            state.subsystems,
            state.actions.mapTo(linkedSetOf()) { it.key },
            state.parameterlessActions.mapTo(linkedSetOf()) { it.key },
        )
        return state.copy(
            validationErrors = issues.filter { it.severity == SuperstructureIssueSeverity.ERROR }.map { "${it.path}: ${it.message}" }.distinct(),
            validationWarnings = issues.filter { it.severity == SuperstructureIssueSeverity.WARNING }.map { "${it.path}: ${it.message}" }.distinct(),
        )
    }

    private fun neutralTarget(reference: SuperstructureFieldReference): SuperstructureSubsystemTarget {
        val subsystem = _state.value.subsystems.single { it.uid == reference.subsystemUid }
        val field = subsystem.stateFields.single { it.uid == reference.fieldUid }
        return when (field.type) {
            SubsystemValueType.DOUBLE -> SuperstructureSubsystemTarget(reference, constantDoubleValue = field.defaultNumber ?: 0.0)
            SubsystemValueType.INT -> SuperstructureSubsystemTarget(reference, constantDoubleValue = (field.defaultInt ?: 0).toDouble())
            SubsystemValueType.BOOLEAN -> SuperstructureSubsystemTarget(reference, constantBooleanValue = field.defaultBoolean ?: false)
            SubsystemValueType.STRING -> SuperstructureSubsystemTarget(reference, constantStringValue = field.defaultText.orEmpty())
        }
    }

    private fun typedGuard(field: SuperstructureFieldOption) = when (field.field.type) {
        SubsystemValueType.DOUBLE, SubsystemValueType.INT -> com.areslib.superstructure.TransitionGuard(
            guardId = uniqueId("guard-${field.field.fieldId}"), source = field.reference, comparison = InterlockComparison.GREATER_THAN, expectedDoubleValue = 0.0
        )
        SubsystemValueType.BOOLEAN -> com.areslib.superstructure.TransitionGuard(
            guardId = uniqueId("guard-${field.field.fieldId}"), source = field.reference, expectedBooleanValue = true
        )
        SubsystemValueType.STRING -> com.areslib.superstructure.TransitionGuard(
            guardId = uniqueId("guard-${field.field.fieldId}"), source = field.reference, expectedStringValue = field.field.defaultText.orEmpty()
        )
    }

    private fun uniqueId(base: String): String {
        val normalized = base.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifBlank { "item" }
        val document = _state.value.draft
        val used = buildSet {
            document?.transitions?.forEach { add(it.transitionId) }
            document?.interlocks?.forEach { add(it.ruleId) }
            document?.healthFallbacks?.forEach { add(it.policyId) }
            document?.luts?.forEach { add(it.lutId) }
            document?.transitions?.flatMap { it.guards }?.forEach { add(it.guardId) }
        }
        if (normalized !in used) return normalized
        var suffix = 2
        while ("$normalized-$suffix" in used) suffix++
        return "$normalized-$suffix"
    }
}

internal fun automaticInterlockDescription(
    rule: SuperstructureInterlockRule,
    sourceFields: List<SuperstructureFieldOption>,
    targetFields: List<SuperstructureFieldOption>,
): String? {
    val source = sourceFields.firstOrNull { it.reference == rule.primary }?.label ?: return null
    val constrained = targetFields.firstOrNull { it.reference == rule.constrained }?.label ?: return null
    val comparison = when (rule.conditionComparison) {
        InterlockComparison.LESS_THAN -> "below"
        InterlockComparison.GREATER_THAN -> "above"
        InterlockComparison.EQUALS_STATE -> "equal to"
        InterlockComparison.NOT_EQUALS_STATE -> "not equal to"
    }
    return "Clamp $constrained when $source is $comparison ${rule.conditionThreshold}."
}

internal fun isAutomaticInterlockDescription(
    rule: SuperstructureInterlockRule,
    sourceFields: List<SuperstructureFieldOption>,
    targetFields: List<SuperstructureFieldOption>,
): Boolean {
    if (rule.description.isBlank()) return true
    val automatic = automaticInterlockDescription(rule, sourceFields, targetFields)
    if (rule.description == automatic) return true
    val source = sourceFields.firstOrNull { it.reference == rule.primary }?.label ?: return false
    val constrained = targetFields.firstOrNull { it.reference == rule.constrained }?.label ?: return false
    return rule.description == "Clamp $constrained while $source is below the reviewed threshold."
}
