package com.ares.analytics.viewmodel.controls

import com.ares.analytics.service.GamepadState
import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.AresProjectGenerator
import com.ares.analytics.service.ControlsDesignAssistant
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.shared.models.League
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerSurfaceDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Offline-first controller editor. It only reads and writes the selected repository's `.ares`
 * documents; a robot connection and cloud account are intentionally irrelevant.
 */
class ControlsEditorViewModel(
    projectPath: String,
    league: League,
    private val documents: AresProjectDocuments = AresProjectDocuments(),
    private val projectGenerator: AresProjectGenerator? = null,
    private val designAssistant: ControlsDesignAssistant? = null,
    private val checkpointRecorder: ProjectCheckpointRecorder = ProjectCheckpointRecorder.NONE,
    private val projectSession: ProjectSession = ProjectSession(documents),
) : AutoCloseable {
    private val targetPlatform = when (league) {
        League.FTC -> ControllerInputPlatform.FTC
        League.FRC -> ControllerInputPlatform.FRC
        League.XRP -> ControllerInputPlatform.XRP
    }
    private val _state = MutableStateFlow(
        ControlsEditorState(projectPath = projectPath, league = league, targetPlatform = targetPlatform)
    )
    val state: StateFlow<ControlsEditorState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val aiProposalCoordinator = ControlsAiProposalCoordinator(designAssistant, scope)

    init {
        projectGenerator?.let { generator ->
            scope.launch {
                generator.aresGenerationState.collect { generation ->
                    _state.update { current ->
                        current.copy(
                            generationPhase = generation.phase,
                            generationMessage = generation.message.ifBlank { null },
                            generatedContentHash = generation.contentHash
                        )
                    }
                }
            }
        }
        reload()
    }

    fun reload() {
        val current = _state.value
        if (current.projectPath.isBlank()) {
            _state.value = current.copy(loadError = "Choose a robot project directory to edit controls.")
            return
        }
        runCatching { projectSession.snapshot(current.projectPath, targetPlatform, forceReload = true) }
            .onSuccess { sessionSnapshot ->
                val snapshot = sessionSnapshot.documents
                val project = snapshot.query
                val profiles = mergeControllerProfiles(project.controllerProfiles)
                val migratedProfileIds = project.controllerProfiles.mapNotNull { stored ->
                    profiles.firstOrNull { it.documentId == stored.documentId }
                        ?.takeIf { it != stored }
                        ?.documentId
                }.toSet()
                val schemes = project.controlSchemes.ifEmpty {
                    listOf(newControlScheme(profiles.first().documentId))
                }
                val selectedScheme = schemes.first()
                val isNewScheme = project.controlSchemes.isEmpty()
                val projectProblems = snapshot.diagnostics.map { diagnostic ->
                    ControlsProblem(
                        if (diagnostic.kind == com.areslib.project.schema.ProjectDocumentKind.PROJECT_METADATA) {
                            ControlsProblemSeverity.ERROR
                        } else ControlsProblemSeverity.WARNING,
                        diagnostic.message
                    )
                }
                _state.value = current.copy(
                    profiles = profiles,
                    schemes = schemes,
                    routineIds = project.routines.map { it.documentId },
                    actions = project.actions.sortedBy { it.displayName.lowercase() },
                    projectMetadata = project.metadata,
                    projectRevision = sessionSnapshot.revision,
                    projectProblems = projectProblems,
                    selectedSchemeId = selectedScheme.documentId,
                    selectedControllerSlot = selectedScheme.controllers.firstOrNull()?.slot,
                    selectedControlId = null,
                    selectedBindingId = null,
                    draftBinding = null,
                    dirty = isNewScheme || migratedProfileIds.isNotEmpty(),
                    dirtySchemeIds = if (isNewScheme) setOf(selectedScheme.documentId) else emptySet(),
                    dirtyProfileIds = buildSet {
                        addAll(migratedProfileIds)
                        if (isNewScheme) selectedScheme.controllers.mapTo(this) { it.profileId }
                    },
                    draftHasUnappliedChanges = false,
                    status = if (migratedProfileIds.isNotEmpty()) {
                        "Standard controller mappings were upgraded. Save to keep the update."
                    } else if (project.capabilityCatalog == null) {
                        "No action catalog found. Rebuild the robot project to discover typed actions."
                    } else null,
                    loadError = null
                ).withProblems(projectProblems)
            }
            .onFailure { error ->
                _state.value = current.copy(loadError = error.message ?: "The project controls could not be loaded.")
            }
    }

    fun selectScheme(documentId: String) = mutateSelection {
        val scheme = it.schemes.firstOrNull { scheme -> scheme.documentId == documentId } ?: return@mutateSelection it
        if (documentId != it.selectedSchemeId && it.draftHasUnappliedChanges) {
            return@mutateSelection it.copy(status = "Apply or discard the binding draft before changing schemes.")
        }
        it.copy(
            selectedSchemeId = scheme.documentId,
            selectedControllerSlot = scheme.controllers.firstOrNull()?.slot,
            selectedControlId = null,
            selectedBindingId = null,
            draftBinding = null,
            learning = null,
            draftHasUnappliedChanges = false
        )
    }

    fun selectController(slot: String) = mutateSelection {
        if (it.selectedScheme?.controllers?.none { controller -> controller.slot == slot } != false) return@mutateSelection it
        if (slot != it.selectedControllerSlot && it.draftHasUnappliedChanges) {
            return@mutateSelection it.copy(status = "Apply or discard the binding draft before changing controllers.")
        }
        it.copy(
            selectedControllerSlot = slot,
            selectedControlId = null,
            selectedBindingId = null,
            draftBinding = null,
            draftHasUnappliedChanges = false,
            status = null
        )
    }

    fun assignProfile(profileId: String) {
        if (_state.value.profiles.none { it.documentId == profileId }) return
        editScheme { scheme, state ->
            scheme.copy(controllers = scheme.controllers.map { assignment ->
                if (assignment.slot == state.selectedControllerSlot) assignment.copy(profileId = profileId) else assignment
            })
        }
        _state.update { current ->
            current.copy(
                dirty = true,
                dirtyProfileIds = current.dirtyProfileIds + profileId
            ).revalidated()
        }
    }

    fun showSurface(surface: ControllerSurfaceDocument) = mutateSelection {
        it.copy(surface = surface, learning = null)
    }

    fun setSearch(search: String) = mutateSelection { it.copy(search = search) }

    fun selectControl(controlId: String, appendToChord: Boolean = false) = mutateSelection { current ->
        val control = current.selectedProfile?.controls?.firstOrNull { it.controlId == controlId }
            ?: return@mutateSelection current
        val draft = current.draftBinding
        if (appendToChord && draft?.source?.kind == ControlSourceKind.CHORD) {
            val ids = if (controlId in draft.source.controlIds) {
                draft.source.controlIds - controlId
            } else {
                draft.source.controlIds + controlId
            }
            current.copy(
                selectedControlId = controlId,
                surface = control.surface,
                draftBinding = draft.copy(source = draft.source.copy(controlIds = ids)),
                draftHasUnappliedChanges = true
            ).revalidated()
        } else {
            current.copy(selectedControlId = controlId, surface = control.surface, learning = null)
        }
    }

    fun createBinding() = mutateSelection { createDefaultBindingDraft(it).revalidated() }

    /** Starts a reviewed two-or-more-button chord without saving or generating code. */
    fun createChordBinding() = mutateSelection { createChordBindingDraft(it).revalidated() }

    /** Assigns an existing trigger-neutral routine as a controller macro. */
    fun createRoutineMacroBinding() = mutateSelection { createRoutineMacroBindingDraft(it).revalidated() }

    /**
     * Starts a normal reviewed binding draft for a missing catalog action on the selected control.
     * It never chooses a physical control, applies the draft, saves a file, or runs generation.
     */
    fun createBindingForAction(actionKey: String) = mutateSelection {
        createBindingForActionDraft(it, actionKey).revalidated()
    }

    fun editBinding(bindingId: String) = mutateSelection { current ->
        if (bindingId != current.selectedBindingId && current.draftHasUnappliedChanges) {
            return@mutateSelection current.copy(status = "Apply or discard the current binding draft before editing another.")
        }
        val binding = current.selectedScheme?.bindings?.firstOrNull { it.bindingId == bindingId }
            ?: return@mutateSelection current
        val controlId = binding.source.controlIds.firstOrNull()
        val control = current.selectedProfile?.controls?.firstOrNull { it.controlId == controlId }
        current.copy(
            selectedBindingId = bindingId,
            selectedControlId = controlId,
            surface = control?.surface ?: current.surface,
            draftBinding = binding,
            learning = null,
            draftHasUnappliedChanges = false
        ).revalidated()
    }

    fun discardDraft() = mutateSelection {
        it.copy(
            draftBinding = null,
            selectedBindingId = null,
            learning = null,
            draftHasUnappliedChanges = false
        ).revalidated()
    }

    fun updateDraft(transform: (ControlBindingDocument) -> ControlBindingDocument) = mutateSelection { current ->
        val draft = current.draftBinding ?: return@mutateSelection current
        current.copy(
            draftBinding = transform(draft),
            draftHasUnappliedChanges = true,
            status = null
        ).revalidated()
    }

    fun setSourceKind(kind: ControlSourceKind) = updateDraft { draft ->
        setDraftSourceKind(draft, kind, _state.value.selectedControlId)
    }

    fun setTarget(kind: ControlTargetKind, key: String) = updateDraft { draft ->
        setDraftTarget(
            draft = draft,
            actions = _state.value.actions,
            selectedControlDisplayName = _state.value.selectedControl?.displayName,
            kind = kind,
            key = key
        )
    }

    /**
     * Applies the common safe momentary-output pattern as one reviewed edit: command the chosen
     * voltage/duty-cycle while held and explicitly command zero when the button is released.
     */
    fun addSafeMomentaryPair() = mutateSelection { addSafeMomentaryPairDraft(it).revalidated() }

    fun setTargetArgument(key: String, value: String) = updateDraft { draft ->
        draft.copy(target = draft.target.copy(arguments = draft.target.arguments + (key to value)))
    }

    fun applyDraft() = mutateSelection { applyDraftBinding(it).revalidated() }

    fun requestAiProposal(studentRequest: String) {
        aiProposalCoordinator.request(_state, studentRequest)
    }

    fun dismissAiProposal() = _state.update { it.copy(aiProposal = null, aiProposalError = null) }

    fun applyAiProposal() = _state.update { aiProposalCoordinator.apply(it) }

    fun deleteBinding(bindingId: String) = editScheme { scheme, _ ->
        scheme.copy(bindings = scheme.bindings.filterNot { it.bindingId == bindingId })
    }.also {
        _state.update { current ->
            if (current.selectedBindingId == bindingId) {
                current.copy(selectedBindingId = null, draftBinding = null, status = "Binding deleted locally.").revalidated()
            } else current
        }
    }

    fun beginDesktopLearning(state: GamepadState) = mutateSelection {
        ControlsHardwareBindingCoordinator.beginDesktopLearning(it, state)
    }

    fun observeDesktopInput(state: GamepadState) {
        _state.update { ControlsHardwareBindingCoordinator.observeDesktopInput(it, state) }
    }

    /** Explicit target-platform entry; desktop observations are never copied here. */
    fun setMapping(controlId: String, platform: ControllerInputPlatform, index: Int?) = mutateSelection {
        ControlsHardwareBindingCoordinator.setMapping(it, controlId, platform, index)
    }

    fun save() {
        saveDocuments(generateAfterSave = false)
    }

    /** Saves every edited document, then starts deterministic offline Kotlin generation. */
    fun saveAndGenerate() {
        saveDocuments(generateAfterSave = true)
    }

    private fun saveDocuments(generateAfterSave: Boolean) {
        val current = _state.value.revalidated()
        if (current.draftHasUnappliedChanges) {
            _state.value = current.copy(status = "Apply or discard the binding draft before saving.")
            return
        }
        if ((generateAfterSave && !current.canGenerate) || (!generateAfterSave && !current.canSave)) {
            _state.value = current.copy(status = "Fix editor errors before saving.")
            return
        }
        val revision = current.projectRevision
        if (revision == null) {
            _state.value = current.copy(status = "Reload the project before saving controls.")
            return
        }
        when (val result = projectSession.saveControls(
            expectedRevision = revision,
            profiles = current.profiles.filter { it.documentId in current.dirtyProfileIds },
            schemes = current.schemes.filter { it.documentId in current.dirtySchemeIds },
        )) {
        is ProjectSessionMutationResult.Applied -> {
            val checkpointPaths = result.value.changedRelativePaths
            reload()
            _state.update {
                it.copy(
                    status = if (generateAfterSave) "Saved all changes. Starting Kotlin generation..." else
                        "Saved all changed control schemes and controller profiles.",
                    dirty = false,
                    dirtySchemeIds = emptySet(),
                    dirtyProfileIds = emptySet()
                )
            }
            if (generateAfterSave) {
                val generator = projectGenerator
                if (generator == null) {
                    _state.update { it.copy(status = "Saved, but project generation is not available in this screen.") }
                } else {
                    _state.update {
                        it.copy(
                            generationPhase = AresGenerationPhase.RUNNING,
                            generationMessage = "Starting local Gradle generation...",
                            generatedContentHash = null
                        )
                    }
                    generator.generateAresProject(current.projectPath, current.league)
                }
            }
            scope.launch {
                runCatching {
                    checkpointRecorder.checkpoint(
                        current.projectPath,
                        "Saved controller bindings",
                        checkpointPaths,
                    )
                }.onFailure { failure ->
                    _state.update { it.copy(status = "Controls saved, but automatic Project History checkpoint failed: ${failure.message}") }
                }
            }
        }
        is ProjectSessionMutationResult.Stale -> {
            _state.update { it.copy(status = "The project changed after this form loaded. Reload before saving.") }
        }
        is ProjectSessionMutationResult.Conflict -> {
            _state.update { it.copy(status = result.message) }
        }
        is ProjectSessionMutationResult.Failed -> {
            _state.update { it.copy(status = result.message) }
        }
        }
    }

    private fun editScheme(transform: (ControlSchemeDocument, ControlsEditorState) -> ControlSchemeDocument) {
        _state.update { current ->
            val scheme = current.selectedScheme ?: return@update current
            val updated = transform(scheme, current)
            current.replaceScheme(updated).copy(
                dirty = true,
                dirtySchemeIds = current.dirtySchemeIds + updated.documentId,
                status = null
            ).revalidated()
        }
    }

    private fun mutateSelection(transform: (ControlsEditorState) -> ControlsEditorState) {
        _state.update(transform)
    }

    override fun close() {
        scope.cancel()
    }
}
