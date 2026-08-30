package com.ares.analytics.viewmodel.controls

import com.ares.analytics.service.GamepadState
import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.AresProjectGenerator
import com.ares.analytics.service.ControlsDesignAssistant
import com.ares.analytics.service.ControlsDesignContext
import com.ares.analytics.service.ControlsDesignProposal
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.shared.models.League
import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.CapabilityParameterType
import com.areslib.controls.AnalogControlPolicyDocument
import com.areslib.controls.AxisTransformDocument
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControlSourceDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetDocument
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControlThresholdDirection
import com.areslib.controls.ControlTimingDocument
import com.areslib.controls.ControlValidationContext
import com.areslib.controls.ControlValidationSeverity
import com.areslib.controls.ControllerAnchorDocument
import com.areslib.controls.ControllerAssignment
import com.areslib.controls.ControllerControlDocument
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.ControllerDeviceMatcherDocument
import com.areslib.controls.ControllerInputMappingDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileDocument
import com.areslib.controls.ControllerSurfaceDocument
import com.areslib.controls.RoutineInvocationPolicy
import com.areslib.controls.validateControlScheme
import com.areslib.controls.validateControllerProfile
import com.areslib.project.AresProjectMetadataDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class ControlsProblemSeverity { INFO, WARNING, ERROR }

data class ControlsProblem(
    val severity: ControlsProblemSeverity,
    val message: String,
    val bindingId: String? = null
)

data class ControlLearningSession(
    val controlId: String,
    val baselineButtons: List<Boolean>,
    val baselineAxes: List<Float>
)

data class ControlsAiProposalReview(
    val proposal: ControlsDesignProposal,
    val changes: List<String>,
    val problems: List<ControlsProblem>,
    val baseContentHash: String,
) {
    val canApply: Boolean get() = problems.none { it.severity == ControlsProblemSeverity.ERROR }
}

data class ControlsEditorState(
    val projectPath: String,
    val league: League,
    val targetPlatform: ControllerInputPlatform,
    val profiles: List<ControllerProfileDocument> = emptyList(),
    val schemes: List<ControlSchemeDocument> = emptyList(),
    val routineIds: List<String> = emptyList(),
    val actions: List<ActionDescriptor> = emptyList(),
    val selectedSchemeId: String? = null,
    val selectedControllerSlot: String? = null,
    val selectedControlId: String? = null,
    val selectedBindingId: String? = null,
    val surface: ControllerSurfaceDocument = ControllerSurfaceDocument.FRONT,
    val search: String = "",
    val draftBinding: ControlBindingDocument? = null,
    val learning: ControlLearningSession? = null,
    val problems: List<ControlsProblem> = emptyList(),
    val projectProblems: List<ControlsProblem> = emptyList(),
    val dirty: Boolean = false,
    val dirtySchemeIds: Set<String> = emptySet(),
    val dirtyProfileIds: Set<String> = emptySet(),
    val draftHasUnappliedChanges: Boolean = false,
    val generationPhase: AresGenerationPhase = AresGenerationPhase.IDLE,
    val generationMessage: String? = null,
    val generatedContentHash: String? = null,
    val projectMetadata: AresProjectMetadataDocument? = null,
    val projectRevision: ProjectSessionRevision? = null,
    val status: String? = null,
    val loadError: String? = null,
    val aiProposalInProgress: Boolean = false,
    val aiProposal: ControlsAiProposalReview? = null,
    val aiProposalError: String? = null,
) {
    val selectedScheme: ControlSchemeDocument?
        get() = schemes.firstOrNull { it.documentId == selectedSchemeId }

    val selectedController: ControllerAssignment?
        get() = selectedScheme?.controllers?.firstOrNull { it.slot == selectedControllerSlot }

    val selectedProfile: ControllerProfileDocument?
        get() = profiles.firstOrNull { it.documentId == selectedController?.profileId }

    val selectedControl: ControllerControlDocument?
        get() = selectedProfile?.controls?.firstOrNull { it.controlId == selectedControlId }

    val selectedAction: ActionDescriptor?
        get() = draftBinding?.target?.takeIf { it.kind == ControlTargetKind.ACTION }
            ?.let { target -> actions.firstOrNull { it.key == target.key } }

    val coverage: ControlsCoverage
        get() = controlsCoverage(actions, selectedScheme)

    val canSave: Boolean
        get() = dirty && !draftHasUnappliedChanges && canGenerate

    val canGenerate: Boolean
        get() = loadError == null && generationPhase != AresGenerationPhase.RUNNING &&
            problems.none { it.severity == ControlsProblemSeverity.ERROR }
}

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
    }
    private val _state = MutableStateFlow(
        ControlsEditorState(projectPath = projectPath, league = league, targetPlatform = targetPlatform)
    )
    val state: StateFlow<ControlsEditorState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
                val profiles = mergeProfiles(project.controllerProfiles)
                val migratedProfileIds = project.controllerProfiles.mapNotNull { stored ->
                    profiles.firstOrNull { it.documentId == stored.documentId }
                        ?.takeIf { it != stored }
                        ?.documentId
                }.toSet()
                val schemes = project.controlSchemes.ifEmpty {
                    listOf(newScheme(profiles.first().documentId))
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
        // Built-in templates do not exist in the project until assigned; saving is idempotent for
        // an already-persisted profile and guarantees codegen never sees a dangling profile ID.
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

    fun createBinding() = mutateSelection { current ->
        if (current.draftHasUnappliedChanges) {
            return@mutateSelection current.copy(status = "Apply or discard the current binding draft before creating another.")
        }
        val control = current.selectedControl ?: return@mutateSelection current
        val scheme = current.selectedScheme ?: return@mutateSelection current
        val slot = current.selectedControllerSlot ?: return@mutateSelection current
        val id = uniqueBindingId(scheme, control.controlId)
        val axis = control.type == ControllerControlTypeDocument.AXIS
        val source = if (axis) {
            ControlSourceDocument(
                kind = ControlSourceKind.AXIS_THRESHOLD,
                controllerSlot = slot,
                controlIds = listOf(control.controlId),
                transform = AxisTransformDocument(),
                pressThreshold = 0.65,
                releaseThreshold = 0.50
            )
        } else {
            ControlSourceDocument(ControlSourceKind.BUTTON, slot, listOf(control.controlId))
        }
        val target = current.actions.firstOrNull()?.let { descriptor ->
            ControlTargetDocument(ControlTargetKind.ACTION, descriptor.key, defaultArguments(descriptor))
        } ?: current.routineIds.firstOrNull()?.let { routine ->
            ControlTargetDocument(ControlTargetKind.ROUTINE, routine)
        } ?: ControlTargetDocument(ControlTargetKind.ACTION, "choose.action")
        current.copy(
            selectedBindingId = null,
            draftBinding = ControlBindingDocument(
                bindingId = id,
                displayName = "${control.displayName} binding",
                source = source,
                event = ControlEvent.PRESS,
                target = target
            ),
            draftHasUnappliedChanges = true
        ).revalidated()
    }

    /**
     * Starts a normal reviewed binding draft for a missing catalog action on the selected control.
     * It never chooses a physical control, applies the draft, saves a file, or runs generation.
     */
    fun createBindingForAction(actionKey: String) = mutateSelection { current ->
        if (current.draftHasUnappliedChanges) {
            return@mutateSelection current.copy(status = "Apply or discard the current binding draft first.")
        }
        val action = current.actions.firstOrNull { it.key == actionKey }
            ?: return@mutateSelection current.copy(status = "That action is no longer in the project catalog. Reload controls.")
        val control = current.selectedControl
            ?: return@mutateSelection current.copy(status = "Select the button or axis that should run '${action.displayName}', then choose Bind.")
        if (control.type != ControllerControlTypeDocument.BUTTON) {
            return@mutateSelection current.copy(
                status = "Select a button for '${action.displayName}'. Continuous axis actions require an explicit value contract.",
            )
        }
        val scheme = current.selectedScheme ?: return@mutateSelection current
        val slot = current.selectedControllerSlot ?: return@mutateSelection current
        val draft = ControlBindingDocument(
            bindingId = uniqueBindingId(scheme, "${control.controlId}-${action.key}"),
            displayName = action.displayName,
            source = ControlSourceDocument(ControlSourceKind.BUTTON, slot, listOf(control.controlId)),
            event = ControlEvent.PRESS,
            target = ControlTargetDocument(ControlTargetKind.ACTION, action.key, defaultArguments(action)),
        )
        current.copy(
            selectedBindingId = null,
            draftBinding = draft,
            draftHasUnappliedChanges = true,
            status = "Review the input event and arguments, then add the binding. Nothing has been saved yet.",
        ).revalidated()
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
        val selected = _state.value.selectedControlId ?: draft.source.controlIds.firstOrNull().orEmpty()
        val source = when (kind) {
            ControlSourceKind.BUTTON -> ControlSourceDocument(kind, draft.source.controllerSlot, listOf(selected))
            ControlSourceKind.CHORD -> ControlSourceDocument(kind, draft.source.controllerSlot, listOf(selected), chordWindowSeconds = .075)
            ControlSourceKind.AXIS_THRESHOLD -> ControlSourceDocument(
                kind, draft.source.controllerSlot, listOf(selected), AxisTransformDocument(), .65, .50
            )
            ControlSourceKind.AXIS_VALUE -> ControlSourceDocument(
                kind, draft.source.controllerSlot, listOf(selected), AxisTransformDocument()
            )
            ControlSourceKind.AXIS_ZONE -> ControlSourceDocument(
                kind, draft.source.controllerSlot, listOf(selected), AxisTransformDocument(),
                zoneMinimum = -.25, zoneMaximum = .25, zoneHysteresis = .05
            )
        }
        draft.copy(
            source = source,
            event = defaultEvent(kind),
            analogPolicy = if (kind == ControlSourceKind.AXIS_VALUE || kind == ControlSourceKind.AXIS_ZONE) {
                AnalogControlPolicyDocument()
            } else null
        )
    }

    fun setTarget(kind: ControlTargetKind, key: String) = updateDraft { draft ->
        val action = _state.value.actions.firstOrNull { it.key == key }
        val previousAction = _state.value.actions.firstOrNull { it.key == draft.target.key }
        val defaultControlName = _state.value.selectedControl?.let { "${it.displayName} binding" }
        draft.copy(
            displayName = if (
                action != null &&
                (draft.displayName == defaultControlName || draft.displayName == previousAction?.displayName)
            ) {
                action.displayName
            } else {
                draft.displayName
            },
            target = ControlTargetDocument(
                kind = kind,
                key = key,
                arguments = if (kind == ControlTargetKind.ACTION && action != null) defaultArguments(action) else emptyMap(),
                routinePolicy = draft.target.routinePolicy
            )
        )
    }

    /**
     * Applies the common safe momentary-output pattern as one reviewed edit: command the chosen
     * voltage/duty-cycle while held and explicitly command zero when the button is released.
     */
    fun addSafeMomentaryPair() = mutateSelection { current ->
        val draft = current.draftBinding
            ?: return@mutateSelection current.copy(status = "Create a binding draft first.")
        if (current.selectedBindingId != null) {
            return@mutateSelection current.copy(status = "Safe pairs can be added from a new binding. Edit existing bindings individually.")
        }
        if (draft.source.kind != ControlSourceKind.BUTTON) {
            return@mutateSelection current.copy(status = "A safe hold-and-release pair requires one button input.")
        }
        val action = current.actions.firstOrNull { it.key == draft.target.key }
            ?: return@mutateSelection current.copy(status = "Choose a project action first.")
        val outputParameter = momentaryOutputParameter(action)
            ?: return@mutateSelection current.copy(status = "That action is not a momentary voltage or duty-cycle output.")
        if (current.problems.any { it.severity == ControlsProblemSeverity.ERROR && (it.bindingId == null || it.bindingId == draft.bindingId) }) {
            return@mutateSelection current.copy(status = "Fix the highlighted binding errors before adding the safe pair.")
        }
        val scheme = current.selectedScheme ?: return@mutateSelection current
        val runBinding = draft.copy(
            displayName = "${action.displayName} while held",
            event = ControlEvent.HELD,
        )
        val stopBinding = draft.copy(
            bindingId = uniqueBindingId(scheme.copy(bindings = scheme.bindings + runBinding), "${draft.bindingId}-release"),
            displayName = "Stop ${action.category.ifBlank { "output" }.lowercase()} on release",
            event = ControlEvent.RELEASE,
            target = draft.target.copy(arguments = draft.target.arguments + (outputParameter.key to "0")),
        )
        val updated = scheme.copy(bindings = scheme.bindings + runBinding + stopBinding)
        current.replaceScheme(updated).copy(
            selectedBindingId = runBinding.bindingId,
            draftBinding = null,
            draftHasUnappliedChanges = false,
            dirty = true,
            dirtySchemeIds = current.dirtySchemeIds + updated.documentId,
            status = "Added a safe pair: hold to command output and release to command zero.",
        ).revalidated()
    }

    fun setTargetArgument(key: String, value: String) = updateDraft { draft ->
        draft.copy(target = draft.target.copy(arguments = draft.target.arguments + (key to value)))
    }

    fun applyDraft() = mutateSelection { current ->
        val draft = current.draftBinding ?: return@mutateSelection current
        if (current.problems.any { it.severity == ControlsProblemSeverity.ERROR && (it.bindingId == null || it.bindingId == draft.bindingId) }) {
            return@mutateSelection current.copy(status = "Fix the highlighted binding errors before applying.")
        }
        val scheme = current.selectedScheme ?: return@mutateSelection current
        val updated = if (current.selectedBindingId == null) {
            scheme.copy(bindings = scheme.bindings + draft)
        } else {
            scheme.copy(bindings = scheme.bindings.map { if (it.bindingId == current.selectedBindingId) draft else it })
        }
        current.replaceScheme(updated).copy(
            selectedBindingId = draft.bindingId,
            draftBinding = draft,
            dirty = true,
            dirtySchemeIds = current.dirtySchemeIds + updated.documentId,
            draftHasUnappliedChanges = false,
            status = "Binding applied locally. Save to create a project revision."
        ).revalidated()
    }

    fun requestAiProposal(studentRequest: String) {
        val current = _state.value
        val request = studentRequest.trim()
        val scheme = current.selectedScheme
        val assistant = designAssistant
        when {
            request.isBlank() -> _state.update { it.copy(aiProposalError = "Describe the bindings you want first.") }
            assistant == null -> _state.update { it.copy(aiProposalError = "Gemini is not available in this app session.") }
            scheme == null -> _state.update { it.copy(aiProposalError = "Select a control scheme first.") }
            current.draftHasUnappliedChanges -> _state.update { it.copy(aiProposalError = "Apply or discard the current binding draft before asking Gemini.") }
            else -> {
                val context = ControlsDesignContext(
                    actionKeys = current.actions.mapTo(linkedSetOf()) { it.key },
                    routineIds = current.routineIds.toSet(),
                    profileControls = current.profiles.associate { profile ->
                        profile.documentId to profile.controls.filter { control ->
                            control.mappings.any { it.platform == current.targetPlatform }
                        }.mapTo(linkedSetOf()) { it.controlId }
                    },
                )
                val baseHash = ControlSchemeCodec.contentHash(scheme)
                _state.update { it.copy(aiProposalInProgress = true, aiProposal = null, aiProposalError = null) }
                scope.launch {
                    runCatching { assistant.propose(scheme, context, request) }
                        .onSuccess { proposal ->
                            val problems = proposal.candidate.bindings.flatMap { binding ->
                                if (binding.target.kind != ControlTargetKind.ACTION) emptyList() else {
                                    val action = current.actions.firstOrNull { it.key == binding.target.key }
                                    if (action == null) listOf(ControlsProblem(ControlsProblemSeverity.ERROR, "Unknown action '${binding.target.key}'.", binding.bindingId))
                                    else validateArguments(action, binding.target.arguments).map {
                                        ControlsProblem(ControlsProblemSeverity.ERROR, it, binding.bindingId)
                                    }
                                }
                            }
                            val review = ControlsAiProposalReview(
                                proposal = proposal,
                                changes = describeControlsChanges(scheme, proposal.candidate),
                                problems = problems,
                                baseContentHash = baseHash,
                            )
                            _state.update { latest ->
                                val latestScheme = latest.selectedScheme
                                if (latestScheme == null || ControlSchemeCodec.contentHash(latestScheme) != baseHash) latest.copy(
                                    aiProposalInProgress = false,
                                    aiProposalError = "The bindings changed while Gemini was working. Request a fresh proposal.",
                                ) else latest.copy(aiProposalInProgress = false, aiProposal = review)
                            }
                        }
                        .onFailure { error -> _state.update {
                            it.copy(aiProposalInProgress = false, aiProposalError = error.message ?: "Gemini could not create a controls proposal.")
                        } }
                }
            }
        }
    }

    fun dismissAiProposal() = _state.update { it.copy(aiProposal = null, aiProposalError = null) }

    fun applyAiProposal() = _state.update { current ->
        val review = current.aiProposal ?: return@update current
        val scheme = current.selectedScheme ?: return@update current
        when {
            !review.canApply -> current.copy(aiProposalError = "Gemini's proposal has blocking validation errors.")
            ControlSchemeCodec.contentHash(scheme) != review.baseContentHash -> current.copy(
                aiProposal = null,
                aiProposalError = "The bindings changed. Request a fresh proposal.",
            )
            else -> current.replaceScheme(review.proposal.candidate).copy(
                dirty = true,
                dirtySchemeIds = current.dirtySchemeIds + scheme.documentId,
                selectedBindingId = null,
                draftBinding = null,
                draftHasUnappliedChanges = false,
                aiProposal = null,
                aiProposalError = null,
                status = "Applied Gemini's proposal locally. Review the bindings and Save when ready.",
            ).revalidated()
        }
    }

    fun deleteBinding(bindingId: String) = editScheme { scheme, _ ->
        scheme.copy(bindings = scheme.bindings.filterNot { it.bindingId == bindingId })
    }.also {
        _state.update { current ->
            if (current.selectedBindingId == bindingId) {
                current.copy(selectedBindingId = null, draftBinding = null, status = "Binding deleted locally.").revalidated()
            } else current
        }
    }

    fun beginDesktopLearning(state: GamepadState) = mutateSelection { current ->
        val control = current.selectedControl ?: return@mutateSelection current
        if (!state.connected) return@mutateSelection current.copy(status = "Connect the controller to learn its desktop input.")
        current.copy(
            learning = ControlLearningSession(control.controlId, state.rawButtons, state.rawAxes),
            status = "Move or press ${control.displayName}; only the Desktop GLFW mapping will change."
        )
    }

    fun observeDesktopInput(state: GamepadState) {
        val learning = _state.value.learning ?: return
        if (!state.connected) return
        val control = _state.value.selectedProfile?.controls?.firstOrNull { it.controlId == learning.controlId }
            ?: return
        when (control.type) {
            ControllerControlTypeDocument.BUTTON -> {
                val newButtons = state.rawButtons.indices.filter { index ->
                    state.rawButtons[index] && !learning.baselineButtons.getOrElse(index) { false }
                }
                if (newButtons.size == 1) {
                    setMapping(learning.controlId, ControllerInputPlatform.DESKTOP_GLFW, newButtons.single())
                }
            }
            ControllerControlTypeDocument.AXIS -> {
                val movedAxes = state.rawAxes.indices.filter { index ->
                    abs(state.rawAxes[index] - learning.baselineAxes.getOrElse(index) { 0f }) >= .35f
                }
                if (movedAxes.size == 1) {
                    setMapping(learning.controlId, ControllerInputPlatform.DESKTOP_GLFW, movedAxes.single())
                }
            }
        }
    }

    /** Explicit target-platform entry; desktop observations are never copied here. */
    fun setMapping(controlId: String, platform: ControllerInputPlatform, index: Int?) = mutateSelection { current ->
        if (index != null && index < 0) return@mutateSelection current.copy(status = "Input indexes cannot be negative.")
        val profile = current.selectedProfile ?: return@mutateSelection current
        val control = profile.controls.firstOrNull { it.controlId == controlId } ?: return@mutateSelection current
        val mapping = index?.let {
            ControllerInputMappingDocument(
                platform = platform,
                buttonIndex = it.takeIf { control.type == ControllerControlTypeDocument.BUTTON },
                axisIndex = it.takeIf { control.type == ControllerControlTypeDocument.AXIS }
            )
        }
        val updated = profile.copy(controls = profile.controls.map { candidate ->
            if (candidate.controlId != controlId) candidate else candidate.copy(
                mappings = candidate.mappings.filterNot { it.platform == platform } + listOfNotNull(mapping)
            )
        })
        current.copy(
            profiles = current.profiles.map { if (it.documentId == updated.documentId) updated else it },
            dirty = true,
            dirtyProfileIds = current.dirtyProfileIds + updated.documentId,
            learning = null,
            status = when (platform) {
                ControllerInputPlatform.DESKTOP_GLFW -> "Learned Desktop GLFW index $index. FTC/FRC mappings were not changed."
                else -> "Set ${platform.name} index $index. Verify it on that platform before competition."
            }
        ).revalidated()
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

    private fun ControlsEditorState.replaceScheme(scheme: ControlSchemeDocument): ControlsEditorState = copy(
        schemes = schemes.map { if (it.documentId == scheme.documentId) scheme else it }
    )

    private fun ControlsEditorState.withProblems(external: List<ControlsProblem>): ControlsEditorState {
        if (schemes.isEmpty()) return copy(problems = external)
        val profileControls = profiles.associate { profile ->
            profile.documentId to profile.controls.mapTo(linkedSetOf()) { it.controlId }
        }
        val context = ControlValidationContext(
            actionKeys = actions.mapTo(linkedSetOf()) { it.key },
            routineIds = routineIds.toSet(),
            profileControls = profileControls
        )
        val shared = schemes.flatMap { scheme ->
            validateControlScheme(scheme, context).map { issue ->
                ControlsProblem(
                    severity = if (issue.severity == ControlValidationSeverity.ERROR) ControlsProblemSeverity.ERROR else ControlsProblemSeverity.WARNING,
                    message = "${scheme.name}: ${issue.message}",
                    bindingId = bindingIdFromPath(issue.path, scheme)
                )
            }
        }
        val profileProblems = profiles.flatMap { profile ->
            validateControllerProfile(profile).map { issue ->
                ControlsProblem(
                    if (issue.severity == ControlValidationSeverity.ERROR) ControlsProblemSeverity.ERROR else ControlsProblemSeverity.WARNING,
                    "${profile.displayName}: ${issue.message}"
                )
            }
        }
        val mappingProblems = schemes.flatMap { scheme ->
            val profileBySlot = scheme.controllers.associate { assignment ->
                assignment.slot to profiles.firstOrNull { profile -> profile.documentId == assignment.profileId }
            }
            scheme.bindings.filter { it.enabled }.flatMap { binding ->
                val profile = profileBySlot[binding.source.controllerSlot]
                binding.source.controlIds.mapNotNull { controlId ->
                    val mapped = profile?.controls?.firstOrNull { it.controlId == controlId }
                        ?.mappings?.any { it.platform == targetPlatform } == true
                    if (mapped) null else ControlsProblem(
                        ControlsProblemSeverity.ERROR,
                        "${scheme.name} / ${binding.displayName}: '$controlId' has no ${targetPlatform.name} mapping. Desktop indexes are intentionally not reused.",
                        binding.bindingId
                    )
                }
            }
        }
        val draftProblems = draftBinding?.let { binding -> validateDraft(binding) }.orEmpty()
        return copy(problems = (external + shared + profileProblems + mappingProblems + draftProblems).distinct())
    }

    private fun ControlsEditorState.revalidated(): ControlsEditorState = withProblems(projectProblems)

    private fun ControlsEditorState.validateDraft(binding: ControlBindingDocument): List<ControlsProblem> {
        val scheme = selectedScheme ?: return emptyList()
        val temporary = scheme.copy(
            bindings = scheme.bindings.filterNot { it.bindingId == selectedBindingId } + binding
        )
        val profileControls = profiles.associate { it.documentId to it.controls.mapTo(linkedSetOf()) { control -> control.controlId } }
        val issues = validateControlScheme(
            temporary,
            ControlValidationContext(actions.mapTo(linkedSetOf()) { it.key }, routineIds.toSet(), profileControls)
        ).filter { it.path.contains("bindings") }
            .map { ControlsProblem(
                if (it.severity == ControlValidationSeverity.ERROR) ControlsProblemSeverity.ERROR else ControlsProblemSeverity.WARNING,
                it.message,
                binding.bindingId
            ) }.toMutableList()
        if (binding.target.kind == ControlTargetKind.ACTION) {
            val descriptor = actions.firstOrNull { it.key == binding.target.key }
            if (descriptor == null) {
                issues += ControlsProblem(ControlsProblemSeverity.ERROR, "Choose an action from the project catalog.", binding.bindingId)
            } else {
                issues += validateArguments(descriptor, binding.target.arguments).map {
                    ControlsProblem(ControlsProblemSeverity.ERROR, it, binding.bindingId)
                }
            }
        }
        val profileId = selectedScheme?.controllers?.firstOrNull { it.slot == binding.source.controllerSlot }?.profileId
        val profile = profiles.firstOrNull { it.documentId == profileId }
        val referenced = binding.source.controlIds.mapNotNull { id -> profile?.controls?.firstOrNull { it.controlId == id } }
        binding.source.controlIds.filter { controlId ->
            profile?.controls?.firstOrNull { it.controlId == controlId }
                ?.mappings?.none { it.platform == targetPlatform } != false
        }.forEach { controlId ->
            issues += ControlsProblem(
                ControlsProblemSeverity.ERROR,
                "'$controlId' needs a ${targetPlatform.name} mapping before this binding can be applied.",
                binding.bindingId
            )
        }
        val needsAxis = binding.source.kind == ControlSourceKind.AXIS_THRESHOLD ||
            binding.source.kind == ControlSourceKind.AXIS_VALUE || binding.source.kind == ControlSourceKind.AXIS_ZONE
        if (needsAxis && referenced.any { it.type != ControllerControlTypeDocument.AXIS }) {
            issues += ControlsProblem(ControlsProblemSeverity.ERROR, "Analog bindings require axis controls.", binding.bindingId)
        }
        if (!needsAxis && referenced.any { it.type != ControllerControlTypeDocument.BUTTON }) {
            issues += ControlsProblem(ControlsProblemSeverity.ERROR, "Button and chord bindings require button controls.", binding.bindingId)
        }
        return issues
    }

    private fun validateArguments(
        action: ActionDescriptor,
        values: Map<String, String>
    ): List<String> = action.parameters.mapNotNull { parameter ->
        val raw = values[parameter.key].orEmpty()
        when {
            raw.isBlank() && parameter.required -> "${parameter.displayName} is required."
            raw.isBlank() -> null
            parameter.type == CapabilityParameterType.NUMBER -> {
                val number = raw.toDoubleOrNull()
                val minimum = parameter.minimum
                val maximum = parameter.maximum
                when {
                    number == null || !number.isFinite() -> "${parameter.displayName} must be a finite number."
                    minimum != null && number < minimum -> "${parameter.displayName} must be at least $minimum."
                    maximum != null && number > maximum -> "${parameter.displayName} must be at most $maximum."
                    else -> null
                }
            }
            parameter.type == CapabilityParameterType.BOOLEAN && raw != "true" && raw != "false" ->
                "${parameter.displayName} must be true or false."
            parameter.type == CapabilityParameterType.ENUM && raw !in parameter.options ->
                "${parameter.displayName} must be one of ${parameter.options.joinToString()}."
            else -> null
        }
    }

    companion object {
        private fun newScheme(profileId: String) = ControlSchemeDocument(
            documentId = "competition-controls",
            name = "Competition controls",
            controllers = listOf(
                ControllerAssignment("driver", "Driver", profileId, devicePort = 0),
                ControllerAssignment("operator", "Operator", profileId, devicePort = 1)
            ),
            bindings = emptyList()
        )

        private fun uniqueBindingId(scheme: ControlSchemeDocument, raw: String): String {
            val base = raw.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "binding" }
            var candidate = base
            var suffix = 2
            val used = scheme.bindings.mapTo(hashSetOf()) { it.bindingId }
            while (candidate in used) candidate = "$base-${suffix++}"
            return candidate
        }

        private fun defaultEvent(kind: ControlSourceKind) = when (kind) {
            ControlSourceKind.AXIS_VALUE -> ControlEvent.VALUE
            ControlSourceKind.AXIS_ZONE -> ControlEvent.ZONE_ENTER
            else -> ControlEvent.PRESS
        }

        private fun defaultArguments(action: ActionDescriptor): Map<String, String> = action.parameters.mapNotNull { parameter ->
            parameter.defaultValue()?.let { parameter.key to it }
                ?: if (parameter.required) parameter.key to "" else null
        }.toMap()

        private fun CapabilityParameterDescriptor.defaultValue(): String? = when (type) {
            CapabilityParameterType.NUMBER -> defaultNumber?.toString()
            CapabilityParameterType.BOOLEAN -> defaultBoolean?.toString()
            CapabilityParameterType.TEXT, CapabilityParameterType.ENUM -> defaultText
        }

        private fun bindingIdFromPath(path: String, scheme: ControlSchemeDocument): String? {
            val index = Regex("bindings\\[(\\d+)]").find(path)?.groupValues?.get(1)?.toIntOrNull()
            return index?.let { scheme.bindings.getOrNull(it)?.bindingId }
        }

        private fun mergeProfiles(projectProfiles: List<ControllerProfileDocument>): List<ControllerProfileDocument> {
            val builtIns = builtInProfiles()
            val projectIds = projectProfiles.mapTo(hashSetOf()) { it.documentId }
            return (projectProfiles + builtIns.filterNot { it.documentId in projectIds })
                .sortedBy { it.displayName.lowercase() }
        }

        private fun builtInProfiles() = listOf(vader5ProProfile(), xboxProfile(), genericProfile())

        private fun genericProfile() = ControllerProfileDocument(
            documentId = "generic-gamepad",
            displayName = "Generic gamepad",
            controls = standardControls()
        )

        private fun xboxProfile() = ControllerProfileDocument(
            documentId = "xbox-standard",
            displayName = "Xbox standard controller",
            deviceMatchers = listOf(
                ControllerDeviceMatcherDocument(nameContains = "Xbox"),
                ControllerDeviceMatcherDocument(nameContains = "XInput"),
            ),
            controls = standardControls(),
        )

        private fun vader5ProProfile() = ControllerProfileDocument(
            documentId = "flydigi-vader-5-pro",
            displayName = "Flydigi Vader 5 Pro",
            deviceMatchers = listOf(ControllerDeviceMatcherDocument(nameContains = "Vader 5 Pro")),
            controls = standardControls() + listOf(
                button("c", "C", .73, .54, ftcIndex = 18), button("z", "Z", .87, .54, ftcIndex = 19),
                button("lm", "LM", .23, .08), button("rm", "RM", .77, .08),
                button("m1", "M1", .32, .34, ControllerSurfaceDocument.REAR, ftcIndex = 20),
                button("m2", "M2", .68, .34, ControllerSurfaceDocument.REAR, ftcIndex = 21),
                button("m3", "M3", .36, .66, ControllerSurfaceDocument.REAR, ftcIndex = 22),
                button("m4", "M4", .64, .66, ControllerSurfaceDocument.REAR, ftcIndex = 23)
            )
        )

        private fun standardControls() = listOf(
            button("a", "A", .81, .44, ftcIndex = 0, frcIndex = 0, desktopIndex = 0),
            button("b", "B", .88, .35, ftcIndex = 1, frcIndex = 1, desktopIndex = 1),
            button("x", "X", .74, .35, ftcIndex = 2, frcIndex = 2, desktopIndex = 2),
            button("y", "Y", .81, .26, ftcIndex = 3, frcIndex = 3, desktopIndex = 3),
            button("left_bumper", "LB", .23, .10, ftcIndex = 4, frcIndex = 4, desktopIndex = 4),
            button("right_bumper", "RB", .77, .10, ftcIndex = 5, frcIndex = 5, desktopIndex = 5),
            button("back", "Back", .43, .40, ftcIndex = 6, frcIndex = 6, desktopIndex = 6),
            button("start", "Start", .57, .40, ftcIndex = 7, frcIndex = 7, desktopIndex = 7),
            button("left_stick_button", "L3", .35, .66, ftcIndex = 9, frcIndex = 8, desktopIndex = 9),
            button("right_stick_button", "R3", .65, .66, ftcIndex = 10, frcIndex = 9, desktopIndex = 10),
            button("dpad_up", "D-pad up", .23, .43, ftcIndex = 11, frcIndex = 120, desktopIndex = 11),
            button("dpad_down", "D-pad down", .23, .57, ftcIndex = 13, frcIndex = 122, desktopIndex = 13),
            button("dpad_left", "D-pad left", .16, .50, ftcIndex = 14, frcIndex = 123, desktopIndex = 14),
            button("dpad_right", "D-pad right", .30, .50, ftcIndex = 12, frcIndex = 121, desktopIndex = 12),
            axis("left_stick_x", "Left stick X", .35, .62, ftcIndex = 0, frcIndex = 0, desktopIndex = 0),
            axis("left_stick_y", "Left stick Y", .35, .72, ftcIndex = 1, frcIndex = 1, desktopIndex = 1),
            axis("right_stick_x", "Right stick X", .65, .62, ftcIndex = 2, frcIndex = 4, desktopIndex = 2),
            axis("right_stick_y", "Right stick Y", .65, .72, ftcIndex = 3, frcIndex = 5, desktopIndex = 3),
            axis("left_trigger", "LT", .17, .02, ftcIndex = 4, frcIndex = 2, desktopIndex = 4),
            axis("right_trigger", "RT", .83, .02, ftcIndex = 5, frcIndex = 3, desktopIndex = 5)
        )

        private fun button(
            id: String,
            name: String,
            x: Double,
            y: Double,
            surface: ControllerSurfaceDocument = ControllerSurfaceDocument.FRONT,
            ftcIndex: Int? = null,
            frcIndex: Int? = null,
            desktopIndex: Int? = null
        ) = ControllerControlDocument(
            id, name, ControllerControlTypeDocument.BUTTON, surface, ControllerAnchorDocument(x, y),
            listOfNotNull(
                ftcIndex?.let { ControllerInputMappingDocument(ControllerInputPlatform.FTC, buttonIndex = it) },
                frcIndex?.let { ControllerInputMappingDocument(ControllerInputPlatform.FRC, buttonIndex = it) },
                desktopIndex?.let { ControllerInputMappingDocument(ControllerInputPlatform.DESKTOP_GLFW, buttonIndex = it) }
            )
        )

        private fun axis(
            id: String,
            name: String,
            x: Double,
            y: Double,
            ftcIndex: Int? = null,
            frcIndex: Int? = null,
            desktopIndex: Int? = null
        ) =
            ControllerControlDocument(
                id, name, ControllerControlTypeDocument.AXIS, ControllerSurfaceDocument.FRONT,
                ControllerAnchorDocument(x, y),
                listOfNotNull(
                    ftcIndex?.let { ControllerInputMappingDocument(ControllerInputPlatform.FTC, axisIndex = it) },
                    frcIndex?.let { ControllerInputMappingDocument(ControllerInputPlatform.FRC, axisIndex = it) },
                    desktopIndex?.let { ControllerInputMappingDocument(ControllerInputPlatform.DESKTOP_GLFW, axisIndex = it) }
                )
            )
    }

    override fun close() {
        scope.cancel()
    }
}

internal fun momentaryOutputParameter(action: ActionDescriptor): CapabilityParameterDescriptor? =
    action.parameters.firstOrNull { parameter ->
        if (parameter.type != CapabilityParameterType.NUMBER) return@firstOrNull false
        val normalizedUnit = parameter.unit.orEmpty().trim().lowercase()
        normalizedUnit in setOf(
            "v",
            "volt",
            "volts",
            "%",
            "percent",
            "power",
            "duty cycle",
            "duty_cycle",
        )
    }

internal fun describeControlsChanges(
    before: ControlSchemeDocument,
    after: ControlSchemeDocument,
): List<String> = buildList {
    if (before.name != after.name) add("Rename scheme '${before.name}' → '${after.name}'")
    if (before.description != after.description) add("Update the scheme description")
    val beforeById = before.bindings.associateBy { it.bindingId }
    val afterById = after.bindings.associateBy { it.bindingId }
    (afterById.keys - beforeById.keys).sorted().forEach { id ->
        add("Add binding: ${afterById.getValue(id).displayName}")
    }
    (beforeById.keys - afterById.keys).sorted().forEach { id ->
        add("Remove binding: ${beforeById.getValue(id).displayName}")
    }
    (beforeById.keys intersect afterById.keys).sorted().forEach { id ->
        if (beforeById.getValue(id) != afterById.getValue(id)) {
            add("Change binding: ${afterById.getValue(id).displayName}")
        }
    }
    if (isEmpty()) add("No form changes")
}
