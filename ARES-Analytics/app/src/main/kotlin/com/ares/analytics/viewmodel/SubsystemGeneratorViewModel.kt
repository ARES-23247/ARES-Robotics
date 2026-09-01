package com.ares.analytics.viewmodel

import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.AresProjectGenerator
import com.ares.analytics.service.SubsystemDesignAssistant
import com.ares.analytics.service.SubsystemDesignProposal
import com.ares.analytics.service.sanitizeSubsystemDesignCandidate
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.shared.models.League
import com.areslib.project.schema.ProjectDocumentKind
import com.ares.analytics.service.project.persistence.ProjectDocumentRemovalPlan
import com.ares.analytics.viewmodel.subsystem.SubsystemDocumentGraphEditor
import com.ares.analytics.viewmodel.subsystem.SubsystemDocumentAuthoring
import com.ares.analytics.viewmodel.subsystem.SubsystemProjectPersistence
import com.areslib.subsystem.SubsystemControlLoopDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemFollowerTransform
import com.areslib.subsystem.SubsystemHardwareConnection
import com.areslib.subsystem.SubsystemHardwareDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemHardwareScaffolding
import com.areslib.subsystem.SubsystemHomingMethod
import com.areslib.subsystem.SubsystemImplementationDocument
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.SubsystemInterlockDocument
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemSimulationDocument
import com.areslib.subsystem.SubsystemSimulationSupport
import com.areslib.subsystem.SubsystemSourceOwnership
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.SubsystemTeachingDocument
import com.areslib.subsystem.SubsystemTeachingLevel
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.subsystem.SubsystemValueType
import com.areslib.subsystem.SubsystemSchema
import com.areslib.subsystem.supportsPlatform
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File


/**
 * Project-backed subsystem editor. GUI documents and hand-authored subsystem DSL use the same
 * shared model, so students can move between visual, DSL, and fully custom IO levels safely.
 */
class SubsystemGeneratorViewModel(
    projectPath: String,
    private val league: League,
    private val documents: AresProjectDocuments = AresProjectDocuments(),
    private val projectGenerator: AresProjectGenerator? = null,
    private val designAssistant: SubsystemDesignAssistant? = null,
    private val checkpointRecorder: ProjectCheckpointRecorder = ProjectCheckpointRecorder.NONE,
    private val projectSession: ProjectSession? = null,
) : AutoCloseable {
    private val reviewGson = GsonBuilder().setPrettyPrinting().create()
    private val platform = when (league) {
        League.FTC -> SubsystemPlatform.FTC
        League.FRC -> SubsystemPlatform.FRC
    }
    private val basePackage = when (league) {
        League.FTC -> "org.firstinspires.ftc.teamcode.subsystems"
        League.FRC -> "com.areslib.frc.subsystems"
    }
    private val previewPlanner = SubsystemBuilderPreviewPlanner(league, platform, basePackage)
    private val persistence = SubsystemProjectPersistence(documents, projectSession)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var aiProposalGeneration = 0L
    private val _state = MutableStateFlow(SubsystemGeneratorState(projectPath, league))
    val state: StateFlow<SubsystemGeneratorState> = _state.asStateFlow()

    init {
        projectGenerator?.let { generator ->
            scope.launch {
                generator.aresGenerationState.collect { generation ->
                    _state.update {
                        it.copy(
                            generationPhase = generation.phase,
                            generationMessage = generation.message.ifBlank { null },
                            generatedContentHash = generation.contentHash,
                        )
                    }
                }
            }
        }
        reload()
    }

    fun reload() {
        aiProposalGeneration++
        val current = _state.value
        if (current.projectPath.isBlank()) {
            _state.value = current.copy(loadError = "Choose a robot project directory to edit subsystems.")
            return
        }
        val target = when (league) {
            League.FTC -> com.areslib.controls.ControllerInputPlatform.FTC
            League.FRC -> com.areslib.controls.ControllerInputPlatform.FRC
        }
        runCatching {
            val sessionSnapshot = projectSession?.snapshot(current.projectPath, target, forceReload = true)
            (sessionSnapshot?.documents ?: documents.load(current.projectPath, target)) to sessionSnapshot?.revision
        }
            .onSuccess { (snapshot, revision) ->
                val matching = snapshot.query.subsystems.filter { it.platform == platform }
                val first = matching.firstOrNull()
                val projectProblems = snapshot.diagnostics.filter {
                    it.kind == ProjectDocumentKind.SUBSYSTEM || it.kind == ProjectDocumentKind.PROJECT_METADATA
                }.map { SubsystemProblem(SubsystemProblemSeverity.WARNING, "project:${it.file.name}", it.message) }
                _state.value = current.copy(
                    documents = matching,
                    selectedDocumentId = first?.documentId,
                    draft = first?.let(::SubsystemEditorDraft),
                    selectedHardwareUid = null,
                    selectedFieldUid = null,
                    selectedLoopUid = null,
                    selectedTuningParameterUid = null,
                    selectedTemplate = first?.template ?: current.selectedTemplate,
                    dirty = false,
                    status = null,
                    loadError = null,
                    projectRevision = revision,
                    aiProposalInProgress = false,
                    aiProposal = null,
                    aiProposalError = null,
                ).revalidated(projectProblems)
            }
            .onFailure { error ->
                _state.value = current.copy(loadError = error.message ?: "Subsystem documents could not be loaded.")
            }
    }

    fun newSubsystem(template: SubsystemTemplate = _state.value.selectedTemplate) {
        require(template.supportsPlatform(platform)) { "${template.name} is not supported for $platform projects" }
        aiProposalGeneration++
        val used = _state.value.documents.mapTo(hashSetOf()) { it.documentId }
        var suffix = 1
        var id = "new-subsystem"
        while (id in used) id = "new-subsystem-${++suffix}"
        val name = if (suffix == 1) "NewSubsystem" else "NewSubsystem$suffix"
        val document = SubsystemTemplates.create(template, id, name, platform)
            .withAvailableTemplateConnections(_state.value.documents)
        _state.update { current ->
            current.copy(
                documents = current.documents + document,
                selectedDocumentId = document.documentId,
                draft = SubsystemEditorDraft(document),
                selectedHardwareUid = null,
                selectedFieldUid = null,
                selectedLoopUid = null,
                selectedTuningParameterUid = null,
                activeStage = SubsystemBuilderStage.PURPOSE,
                visitedStages = setOf(SubsystemBuilderStage.PURPOSE),
                selectedTemplate = document.template,
                dirty = true,
                status = "New ${template.name.lowercase().replace('_', ' ')} draft created.",
                aiProposalInProgress = false,
                aiProposal = null,
                aiProposalError = null,
                showTemplatePicker = false,
            ).revalidated()
        }
    }

    fun setTemplatePickerVisible(visible: Boolean) = _state.update { it.copy(showTemplatePicker = visible) }

    fun selectTemplate(template: SubsystemTemplate) = _state.update { it.copy(selectedTemplate = template) }

    fun applyTemplate(template: SubsystemTemplate) {
        require(template.supportsPlatform(platform)) { "${template.name} is not supported for $platform projects" }
        val currentDocument = _state.value.draft?.document ?: return
        val templateDocument = SubsystemTemplates.create(
            template = template,
            documentId = currentDocument.documentId,
            kotlinTypeName = currentDocument.kotlinTypeName,
            platform = currentDocument.platform,
            displayName = currentDocument.displayName,
        ).withAvailableTemplateConnections(
            _state.value.documents.filterNot { it.documentId == currentDocument.documentId },
        ).copy(
            revision = currentDocument.revision,
            parentContentHash = currentDocument.parentContentHash,
            uid = currentDocument.uid,
        )

        _state.update { current ->
            val draft = current.draft ?: return@update current
            current.copy(
                draft = draft.edit { templateDocument },
                selectedHardwareUid = null,
                selectedFieldUid = null,
                selectedLoopUid = null,
                selectedTuningParameterUid = null,
                selectedTemplate = template,
                dirty = true,
                status = "Applied ${template.name.lowercase().replace('_', ' ')} starter template.",
            ).revalidated()
        }
    }

    fun selectStage(stage: SubsystemBuilderStage) = _state.update {
        it.copy(activeStage = stage, visitedStages = it.visitedStages + stage)
    }

    fun navigateToProblem(path: String) = _state.update { current ->
        val document = current.draft?.document ?: return@update current
        val index = Regex("\\[(\\d+)]").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
        when {
            path.startsWith("hardware") -> current.copy(
                activeStage = SubsystemBuilderStage.HARDWARE,
                visitedStages = current.visitedStages + SubsystemBuilderStage.HARDWARE,
                selectedHardwareUid = index?.let { document.hardware.getOrNull(it)?.uid },
                selectedFieldUid = null,
                selectedLoopUid = null,
            )
            path.startsWith("stateFields") -> current.copy(
                activeStage = SubsystemBuilderStage.STATE_AND_BEHAVIOR,
                visitedStages = current.visitedStages + SubsystemBuilderStage.STATE_AND_BEHAVIOR,
                selectedFieldUid = index?.let { document.stateFields.getOrNull(it)?.uid },
                selectedHardwareUid = null,
                selectedLoopUid = null,
            )
            path.startsWith("controlLoops") -> current.copy(
                activeStage = SubsystemBuilderStage.STATE_AND_BEHAVIOR,
                visitedStages = current.visitedStages + SubsystemBuilderStage.STATE_AND_BEHAVIOR,
                selectedLoopUid = index?.let { document.controlLoops.getOrNull(it)?.uid },
                selectedHardwareUid = null,
                selectedFieldUid = null,
            )
            path.startsWith("tuningParameters") -> current.copy(
                activeStage = SubsystemBuilderStage.TUNING,
                visitedStages = current.visitedStages + SubsystemBuilderStage.TUNING,
                selectedTuningParameterUid = index?.let { document.tuningParameters.getOrNull(it)?.uid },
                selectedHardwareUid = null,
                selectedFieldUid = null,
                selectedLoopUid = null,
            )
            path.startsWith("safety") -> current.copy(
                activeStage = SubsystemBuilderStage.SAFETY,
                visitedStages = current.visitedStages + SubsystemBuilderStage.SAFETY,
            )
            path.startsWith("implementation") || path == "displayName" || path == "kotlinTypeName" || path == "documentId" ->
                current.copy(
                    activeStage = SubsystemBuilderStage.PURPOSE,
                    visitedStages = current.visitedStages + SubsystemBuilderStage.PURPOSE,
                )
            else -> current.copy(
                activeStage = SubsystemBuilderStage.REVIEW,
                visitedStages = current.visitedStages + SubsystemBuilderStage.REVIEW,
            )
        }
    }

    fun previousStage() = _state.update { state ->
        val stages = SubsystemBuilderStage.entries
        val stage = stages[(state.activeStage.ordinal - 1).coerceAtLeast(0)]
        state.copy(activeStage = stage, visitedStages = state.visitedStages + stage)
    }

    fun registerHandAuthoredSubsystem() {
        aiProposalGeneration++
        val used = _state.value.documents.mapTo(hashSetOf()) { it.documentId }
        var suffix = 1
        var id = "existing-subsystem"
        while (id in used) id = "existing-subsystem-${++suffix}"
        val name = if (suffix == 1) "ExistingSubsystem" else "ExistingSubsystem$suffix"
        val packageName = "$basePackage.${id.replace('-', '_')}"
        val sourceRoot = when (league) {
            League.FTC -> "TeamCode/src/main/java"
            League.FRC -> "src/main/kotlin"
        }
        val document = SubsystemTemplates.create(SubsystemTemplate.ADVANCED_CUSTOM, id, name, platform).copy(
            generateMockIo = false,
            generateTest = false,
            implementation = SubsystemImplementationDocument(
                kind = SubsystemImplementationKind.HAND_AUTHORED,
                ownership = SubsystemSourceOwnership.USER_OWNED,
                modulePath = if (league == League.FTC) ":TeamCode" else ":",
                sourceFiles = listOf("$sourceRoot/${packageName.replace('.', '/')}/${name}Subsystem.kt"),
                subsystemClassName = "$packageName.${name}Subsystem",
                ioContractClassName = "$packageName.${name}IO",
                hardwareAdapterClassName = "$packageName.${if (league == League.FTC) "Ftc" else "Frc"}${name}IO",
                simulation = SubsystemSimulationDocument(SubsystemSimulationSupport.UNAVAILABLE),
                teaching = SubsystemTeachingDocument(
                    level = SubsystemTeachingLevel.INTERMEDIATE,
                    summary = "Existing team-owned subsystem registered with ARES.",
                ),
            ),
        )
        _state.update { current ->
            current.copy(
                documents = current.documents + document,
                selectedDocumentId = id,
                draft = SubsystemEditorDraft(document),
                selectedHardwareUid = null,
                selectedFieldUid = null,
                selectedLoopUid = null,
                selectedTuningParameterUid = document.tuningParameters.firstOrNull()?.uid,
                activeStage = SubsystemBuilderStage.PURPOSE,
                visitedStages = setOf(SubsystemBuilderStage.PURPOSE),
                selectedTemplate = SubsystemTemplate.ADVANCED_CUSTOM,
                dirty = true,
                status = "Hand-authored subsystem registration created. Review its source and runtime contract.",
                aiProposalInProgress = false,
                aiProposal = null,
                aiProposalError = null,
            ).revalidated()
        }
    }

    fun nextStage() = _state.update { state ->
        val stages = SubsystemBuilderStage.entries
        val stage = stages[(state.activeStage.ordinal + 1).coerceAtMost(stages.lastIndex)]
        state.copy(activeStage = stage, visitedStages = state.visitedStages + stage)
    }

    fun setGeneratedPlumbingExpanded(expanded: Boolean) = _state.update {
        it.copy(generatedPlumbingExpanded = expanded)
    }

    fun selectDocument(documentId: String) {
        _state.update { current ->
            if (current.dirty) return@update current.copy(status = "Save or reload the current draft before switching subsystems.")
            val document = current.documents.firstOrNull { it.documentId == documentId } ?: return@update current
            aiProposalGeneration++
            current.copy(
                selectedDocumentId = document.documentId,
                draft = SubsystemEditorDraft(document),
                selectedHardwareUid = null,
                selectedFieldUid = null,
                selectedLoopUid = null,
                selectedTuningParameterUid = null,
                activeStage = SubsystemBuilderStage.PURPOSE,
                visitedStages = setOf(SubsystemBuilderStage.PURPOSE),
                selectedTemplate = document.template,
                status = null,
                aiProposalInProgress = false,
                aiProposal = null,
                aiProposalError = null,
            ).revalidated()
        }
    }

    fun edit(transform: (SubsystemDocument) -> SubsystemDocument) {
        aiProposalGeneration++
        _state.update { current ->
            val draft = current.draft ?: return@update current
            current.copy(
                draft = draft.edit(transform),
                dirty = true,
                status = null,
                aiProposalInProgress = false,
                aiProposal = null,
                aiProposalError = null,
            ).revalidated()
        }
    }

    fun undo() = _state.update { current ->
        aiProposalGeneration++
        val draft = current.draft ?: return@update current
        current.copy(
            draft = draft.undo(),
            dirty = true,
            status = "Undid the last edit.",
            aiProposalInProgress = false,
            aiProposal = null,
            aiProposalError = null,
        ).revalidated()
    }

    fun redo() = _state.update { current ->
        aiProposalGeneration++
        val draft = current.draft ?: return@update current
        current.copy(
            draft = draft.redo(),
            dirty = true,
            status = "Redid the edit.",
            aiProposalInProgress = false,
            aiProposal = null,
            aiProposalError = null,
        ).revalidated()
    }

    /** Requests form edits only. The assistant cannot save, generate, or write project source. */
    fun requestAiProposal(studentRequest: String) {
        val request = studentRequest.trim()
        val base = _state.value.draft?.document ?: return
        val assistant = designAssistant
        if (request.isBlank()) {
            _state.update { it.copy(aiProposalError = "Describe the mechanism or the change you want first.") }
            return
        }
        if (assistant == null) {
            _state.update { it.copy(aiProposalError = "The AI form assistant is not available in this app session.") }
            return
        }
        _state.update {
            it.copy(aiProposalInProgress = true, aiProposal = null, aiProposalError = null)
        }
        val requestGeneration = ++aiProposalGeneration
        scope.launch {
            runCatching {
                val rawProposal = assistant.propose(base, request)
                val candidate = sanitizeSubsystemDesignCandidate(base, rawProposal.candidate)
                val proposal = rawProposal.copy(candidate = candidate)
                val problems = SubsystemSchema.validate(candidate).map {
                    SubsystemProblem(SubsystemProblemSeverity.ERROR, it.path, it.message)
                } + safetyWarnings(candidate)
                SubsystemAiProposalReview(
                    base = base,
                    proposal = proposal,
                    diff = structuredLineDiff(
                        reviewGson.toJson(base),
                        reviewGson.toJson(candidate),
                        contextLines = 2,
                    ),
                    problems = problems.distinctBy { Triple(it.severity, it.path, it.message) },
                )
            }
                .onSuccess { review ->
                    _state.update { current ->
                        if (requestGeneration != aiProposalGeneration) {
                            current
                        } else if (current.draft?.document != base) {
                            current.copy(
                                aiProposalInProgress = false,
                                aiProposalError = "The form changed while Gemini was working. Request a fresh proposal.",
                            )
                        } else {
                            current.copy(aiProposalInProgress = false, aiProposal = review, aiProposalError = null)
                        }
                    }
                }
                .onFailure { error ->
                    _state.update {
                        if (requestGeneration != aiProposalGeneration) it else it.copy(
                            aiProposalInProgress = false,
                            aiProposal = null,
                            aiProposalError = error.message ?: "Gemini could not create a subsystem proposal.",
                        )
                    }
                }
        }
    }

    fun dismissAiProposal() = _state.update { it.copy(aiProposal = null, aiProposalError = null) }

    fun applyAiProposal() = _state.update { current ->
        val review = current.aiProposal ?: return@update current
        val draft = current.draft ?: return@update current
        when {
            !review.canApply -> current.copy(aiProposalError = "Fix the proposal's validation errors before applying it.")
            draft.document != review.base -> current.copy(
                aiProposal = null,
                aiProposalError = "The form changed after this proposal was created. Request a fresh proposal.",
            )
            else -> current.copy(
                draft = draft.edit { review.proposal.candidate },
                dirty = true,
                aiProposal = null,
                aiProposalError = null,
                status = "Applied Gemini's form proposal. Review it, then Save when you are satisfied.",
            ).revalidated()
        }
    }

    fun setHomingMethod(method: SubsystemHomingMethod) = edit { document ->
        SubsystemDocumentAuthoring.setHomingMethod(document, method)
    }

    fun selectHardware(id: String?) = _state.update { it.copy(selectedHardwareUid = id, selectedFieldUid = null, selectedLoopUid = null, selectedTuningParameterUid = null) }
    fun selectField(id: String?) = _state.update { it.copy(selectedFieldUid = id, selectedHardwareUid = null, selectedLoopUid = null, selectedTuningParameterUid = null) }
    fun selectLoop(id: String?) = _state.update { it.copy(selectedLoopUid = id, selectedHardwareUid = null, selectedFieldUid = null, selectedTuningParameterUid = null) }
    fun selectTuningParameter(uid: String?) = _state.update {
        it.copy(selectedTuningParameterUid = uid, selectedHardwareUid = null, selectedFieldUid = null, selectedLoopUid = null)
    }

    fun addTuningParameter() {
        val document = _state.value.draft?.document ?: return
        val declaration = SubsystemTuningAuthoring.newParameter(document)
        edit { it.copy(tuningParameters = it.tuningParameters + declaration) }
        selectTuningParameter(declaration.uid)
    }

    fun updateTuningParameter(
        uid: String,
        transform: (TuningParameterDeclaration) -> TuningParameterDeclaration,
    ) = edit { document ->
        document.copy(tuningParameters = document.tuningParameters.map { if (it.uid == uid) transform(it) else it })
    }

    fun changeTuningParameterType(uid: String, type: TuningParameterType) = updateTuningParameter(uid) {
        SubsystemTuningAuthoring.changeType(it, type)
    }

    fun removeTuningParameter(uid: String) = edit { document ->
        document.copy(tuningParameters = document.tuningParameters.filterNot { it.uid == uid })
    }.also { selectTuningParameter(null) }

    fun moveTuningParameter(uid: String, offset: Int) = edit { document ->
        document.copy(tuningParameters = SubsystemTuningAuthoring.moveByUid(document.tuningParameters, uid, offset))
    }

    fun applyTuningPreset(loopUid: String, preset: SubsystemTuningPreset) = edit { document ->
        SubsystemTuningAuthoring.applyPreset(document, loopUid, preset)
    }

    fun addHardware(kind: SubsystemHardwareKind = SubsystemHardwareKind.MOTOR) {
        require(kind.supportsPlatform(platform)) {
            "Generated ${kind.name.lowercase().replace('_', ' ')} hardware is not supported for $platform projects"
        }
        val id = uniqueId("device", _state.value.draft?.document?.hardware.orEmpty().map { it.hardwareId })
        edit { document ->
            val scaffold = SubsystemHardwareScaffolding.create(
                kind = kind,
                hardwareId = id,
                displayName = kind.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase),
                platform = platform,
                canId = nextCanId(document),
                channel = nextChannel(document),
            )
            document.copy(
                hardware = document.hardware + scaffold.hardware,
                stateFields = document.stateFields + scaffold.stateFields,
                controlLoops = document.controlLoops + scaffold.controlLoops,
            )
        }
        selectHardware(id)
    }

    fun removeHardware(id: String) = edit { document ->
        SubsystemDocumentGraphEditor.removeHardware(document, id)
    }.also { selectHardware(null) }

    fun updateHardware(id: String, transform: (SubsystemHardwareDocument) -> SubsystemHardwareDocument) = edit { document ->
        document.copy(hardware = document.hardware.map { if (it.hardwareId == id) transform(it) else it })
    }

    fun setHardwareFollower(
        id: String,
        leaderId: String?,
        transform: SubsystemFollowerTransform = SubsystemFollowerTransform.SAME_DIRECTION,
    ) = edit { document ->
        SubsystemDocumentGraphEditor.setFollower(document, id, leaderId, transform)
    }

    fun changeHardwareKind(id: String, kind: SubsystemHardwareKind) = edit { document ->
        SubsystemDocumentAuthoring.changeHardwareKind(document, id, kind, platform)
    }

    fun renameHardwareId(id: String, newId: String) {
        if (newId == id) return
        edit { document -> SubsystemDocumentGraphEditor.renameHardware(document, id, newId) }
        selectHardware(_state.value.draft?.document?.hardware?.firstOrNull { it.hardwareId == newId }?.uid)
    }

    fun addStateField(
        displayName: String = "Additional value",
        role: SubsystemFieldRole = SubsystemFieldRole.STATUS,
        type: SubsystemValueType = SubsystemValueType.DOUBLE,
    ) {
        val id = uniqueId("value", _state.value.draft?.document?.stateFields.orEmpty().map { it.fieldId })
        edit { document ->
        val field = SubsystemStateFieldDocument(
            fieldId = id,
            displayName = displayName,
            type = type,
            role = role,
            defaultNumber = 0.0.takeIf { type == SubsystemValueType.DOUBLE },
            defaultBoolean = false.takeIf { type == SubsystemValueType.BOOLEAN },
            defaultInt = 0.takeIf { type == SubsystemValueType.INT },
            defaultText = "".takeIf { type == SubsystemValueType.STRING },
        )
        document.copy(stateFields = document.stateFields + field)
        }
        selectField(id)
    }

    fun removeStateField(id: String) = edit { document ->
        SubsystemDocumentGraphEditor.removeStateField(document, id)
    }.also { selectField(null) }

    fun updateStateField(id: String, transform: (SubsystemStateFieldDocument) -> SubsystemStateFieldDocument) = edit { document ->
        document.copy(stateFields = document.stateFields.map { if (it.fieldId == id) transform(it) else it })
    }

    fun changeStateFieldType(id: String, type: SubsystemValueType) = edit { document ->
        SubsystemDocumentAuthoring.changeStateFieldType(document, id, type)
    }

    fun renameStateFieldId(id: String, newId: String) {
        if (newId == id) return
        edit { document -> SubsystemDocumentGraphEditor.renameStateField(document, id, newId) }
        selectField(_state.value.draft?.document?.stateFields?.firstOrNull { it.fieldId == newId }?.uid)
    }

    fun addControlLoop() {
        val current = _state.value.draft?.document ?: return
        val controlledActuators = current.controlLoops.mapTo(mutableSetOf()) { it.actuatorId }
        if (current.hardware.none { it.kind.isActuator() && it.following == null && it.hardwareId !in controlledActuators }) {
            _state.update {
                it.copy(status = "Every independent actuator already has a controller. Edit the existing rule instead of adding a conflicting output.")
            }
            return
        }
        if (current.stateFields.none { it.role == SubsystemFieldRole.TARGET && it.type.isNumeric() }) {
            _state.update { it.copy(status = "Add a numeric target state value before creating a controller.") }
            return
        }
        val id = uniqueId("control", current.controlLoops.map { it.loopId })
        edit { document ->
            val loop = SubsystemDocumentAuthoring.createControlLoop(document, id) ?: return@edit document
            document.copy(controlLoops = document.controlLoops + loop)
        }
        selectLoop(id)
    }

    fun removeControlLoop(id: String) = edit { document ->
        document.copy(controlLoops = document.controlLoops.filterNot { it.loopId == id })
    }.also { selectLoop(null) }

    fun updateControlLoop(id: String, transform: (SubsystemControlLoopDocument) -> SubsystemControlLoopDocument) = edit { document ->
        document.copy(controlLoops = document.controlLoops.map { if (it.loopId == id) transform(it) else it })
    }

    fun changeControlLoopActuator(id: String, actuatorId: String) = edit { document ->
        SubsystemDocumentAuthoring.changeControlLoopActuator(document, id, actuatorId)
    }

    fun changeControlLoopTarget(id: String, targetFieldId: String) = edit { document ->
        SubsystemDocumentAuthoring.changeControlLoopTarget(document, id, targetFieldId)
    }

    fun renameControlLoopId(id: String, newId: String) {
        if (newId == id) return
        edit { document ->
            document.copy(
                controlLoops = document.controlLoops.map { loop ->
                    if (loop.loopId == id) loop.copy(loopId = newId) else loop
                },
            )
        }
    }

    fun changeControlLoopStrategy(id: String, strategy: SubsystemControlStrategy) = edit { document ->
        SubsystemDocumentAuthoring.changeControlLoopStrategy(document, id, strategy)
    }

    fun selectInterlock(id: String?) = _state.update { it.copy(selectedInterlockId = id) }

    fun addInterlock() {
        val snapshot = _state.value
        val current = snapshot.draft?.document ?: return
        val id = uniqueId("interlock", current.interlocks.map { it.interlockId })
        val interlock = SubsystemDocumentAuthoring.createInterlock(current, snapshot.documents, id)
        if (interlock == null) {
            _state.update {
                it.copy(status = "Add another generated subsystem with state values before creating a cross-mechanism interlock.")
            }
            return
        }
        edit { it.copy(interlocks = it.interlocks + interlock) }
        selectInterlock(id)
    }

    fun removeInterlock(id: String) = edit { document ->
        document.copy(interlocks = document.interlocks.filterNot { it.interlockId == id })
    }.also { selectInterlock(null) }

    fun updateInterlock(id: String, transform: (SubsystemInterlockDocument) -> SubsystemInterlockDocument) = edit { document ->
        document.copy(interlocks = document.interlocks.map { if (it.interlockId == id) transform(it) else it })
    }

    fun applyControlLoopGains(
        id: String,
        kp: Double,
        ki: Double,
        kd: Double,
        ks: Double,
        kv: Double,
        kg: Double,
    ) {
        require(listOf(kp, ki, kd, ks, kv, kg).all(Double::isFinite)) {
            "Control and feedforward gains must be finite."
        }
        updateControlLoop(id) { loop ->
            loop.copy(
                kP = kp,
                kI = ki,
                kD = kd,
                feedforward = loop.feedforward.copy(
                    kS = ks,
                    kV = kv,
                    kG = kg
                )
            )
        }
    }

    fun save(generateAfterSave: Boolean = false) {
        val current = _state.value
        val draft = current.draft?.document ?: return
        if (!current.canSave) {
            _state.update { it.copy(status = "Fix validation errors before saving.") }
            return
        }
        runCatching { persistence.save(current.projectPath, current.projectRevision, draft) }
            .onSuccess { saved ->
                _state.update { state ->
                    val persisted = saved.document
                    state.copy(
                        documents = state.documents.filterNot { it.documentId == persisted.documentId } + persisted,
                        selectedDocumentId = persisted.documentId,
                        draft = SubsystemEditorDraft(persisted),
                        dirty = false,
                        projectRevision = persistence.currentRevision(state.projectRevision),
                        status = "Saved revision ${persisted.revision} (${saved.contentHash.take(12)}…).",
                    ).revalidated()
                }
                if (generateAfterSave) projectGenerator?.generateAresProject(current.projectPath, current.league)
                scope.launch {
                    runCatching {
                        val root = File(current.projectPath).canonicalFile
                        checkpointRecorder.checkpoint(
                            current.projectPath,
                            "Saved ${saved.document.displayName} subsystem",
                            setOf(
                                saved.currentFile.relativeTo(root).invariantSeparatorsPath,
                                saved.historyFile.relativeTo(root).invariantSeparatorsPath,
                            ),
                        )
                    }.onFailure { failure ->
                        _state.update { it.copy(status = "Subsystem saved, but automatic Project History checkpoint failed: ${failure.message}") }
                    }
                }
            }
            .onFailure { error -> _state.update { it.copy(status = error.message ?: "Subsystem could not be saved.") } }
    }

    fun requestRemoveSubsystem() {
        val current = _state.value
        val draft = current.draft?.document ?: return
        val root = runCatching { File(current.projectPath).canonicalFile }.getOrNull()
        val canonicalFile = runCatching { documents.subsystems.file(current.projectPath, draft.documentId) }
            .getOrElse { error ->
                _state.update { it.copy(status = error.message ?: "The subsystem location is invalid.") }
                return
            }
        val plan: ProjectDocumentRemovalPlan? = if (canonicalFile.isFile) {
            runCatching { persistence.removalPlan(current.projectPath, current.projectRevision, draft.documentId) }
                .getOrElse { error ->
                    _state.update {
                        it.copy(status = error.message ?: "The saved subsystem could not be reviewed for removal.")
                    }
                    return
                }
        } else null
        _state.update { state ->
            state.copy(
                pendingRemoval = SubsystemRemovalRequest(
                    documentId = draft.documentId,
                    displayName = draft.displayName,
                    persisted = plan != null,
                    contentHash = plan?.contentHash,
                    canonicalPath = plan?.currentFile?.projectRelativeTo(root),
                    recoveryPath = plan?.recoveryFile?.projectRelativeTo(root),
                    sourceFilesPreserved = draft.implementation.sourceFiles.sorted(),
                    discardsUnsavedChanges = current.dirty,
                ),
                status = null,
            )
        }
    }

    fun cancelRemoveSubsystem() = _state.update { it.copy(pendingRemoval = null) }

    fun confirmRemoveSubsystem() {
        val current = _state.value
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
                    _state.update {
                        it.copy(status = "Subsystem removed safely, but automatic Project History checkpoint failed: ${failure.message}")
                    }
                }
            }
        }.onFailure { error ->
            _state.update {
                it.copy(
                    pendingRemoval = null,
                    status = error.message ?: "The subsystem could not be removed.",
                )
            }
        }
    }

    fun restoreRemovedSubsystem() {
        val current = _state.value
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
            aiProposalGeneration++
            val restoredDocuments = (current.documents + restored)
                .distinctBy(SubsystemDocument::documentId)
                .sortedWith(compareBy<SubsystemDocument> { it.displayName.lowercase() }.thenBy { it.documentId })
            _state.update {
                it.copy(
                    documents = restoredDocuments,
                    selectedDocumentId = restored.documentId,
                    draft = SubsystemEditorDraft(restored),
                    selectedHardwareUid = restored.hardware.firstOrNull()?.uid,
                    selectedFieldUid = restored.stateFields.firstOrNull()?.uid,
                    selectedLoopUid = restored.controlLoops.firstOrNull()?.uid,
                    selectedInterlockId = restored.interlocks.firstOrNull()?.interlockId,
                    selectedTuningParameterUid = restored.tuningParameters.firstOrNull()?.uid,
                    activeStage = SubsystemBuilderStage.PURPOSE,
                    visitedStages = setOf(SubsystemBuilderStage.PURPOSE),
                    selectedTemplate = restored.template,
                    dirty = false,
                    projectRevision = persistence.currentRevision(it.projectRevision),
                    recentRecovery = null,
                    status = "Restored ${restored.displayName} from the reviewed recovery copy. Kotlin source was unchanged.",
                ).revalidated()
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
                    _state.update {
                        it.copy(status = "Subsystem restored, but automatic Project History checkpoint failed: ${failure.message}")
                    }
                }
            }
        }.onFailure { error ->
            _state.update {
                it.copy(status = error.message ?: "The subsystem recovery copy could not be restored.")
            }
        }
    }

    fun dismissRecoveryNotice() = _state.update { it.copy(recentRecovery = null) }

    private fun refreshProjectSession(projectPath: String, league: League) {
        persistence.refresh(projectPath, league)
    }

    private fun removeDocumentFromSession(
        documentId: String,
        message: String,
        recovery: SubsystemRecoveryNotice? = null,
    ) {
        aiProposalGeneration++
        _state.update { current ->
            val remaining = current.documents.filterNot { it.documentId == documentId }
            val next = remaining.firstOrNull()
            current.copy(
                documents = remaining,
                selectedDocumentId = next?.documentId,
                draft = next?.let(::SubsystemEditorDraft),
                selectedHardwareUid = null,
                selectedFieldUid = null,
                selectedLoopUid = null,
                selectedInterlockId = null,
                selectedTuningParameterUid = null,
                activeStage = SubsystemBuilderStage.PURPOSE,
                visitedStages = setOf(SubsystemBuilderStage.PURPOSE),
                selectedTemplate = next?.template ?: current.selectedTemplate,
                dirty = false,
                projectRevision = persistence.currentRevision(current.projectRevision),
                pendingRemoval = null,
                recentRecovery = recovery,
                status = message,
                aiProposalInProgress = false,
                aiProposal = null,
                aiProposalError = null,
            ).revalidated()
        }
    }

    private fun File.projectRelativeTo(root: File?): String? = root?.let { projectRoot ->
        runCatching { relativeTo(projectRoot).invariantSeparatorsPath }.getOrNull()
    }

    fun generate() {
        if (_state.value.dirty) save()
        val current = _state.value
        if (current.dirty) return
        if (current.hasProtectedUserOwnedConflict) {
            _state.update {
                it.copy(status = "Generation stopped: a USER-OWNED file differs from the preview and cannot be replaced.")
            }
            return
        }
        val replacements = current.previewFiles.filter { it.change == SubsystemFileChange.REPLACE_STARTER }
        if (replacements.isNotEmpty()) {
            _state.update { it.copy(pendingStarterReplacements = replacements, status = null) }
            return
        }
        projectGenerator?.applySubsystemStarters(current.projectPath, current.league)
    }

    fun cancelStarterReplacement() = _state.update { it.copy(pendingStarterReplacements = emptyList()) }

    fun confirmStarterReplacement() {
        val current = _state.value
        val token = current.starterConfirmationToken
        if (current.pendingStarterReplacements.isEmpty() || token == null) return
        _state.update { it.copy(pendingStarterReplacements = emptyList(), starterConfirmationToken = null) }
        runCatching { projectGenerator?.applySubsystemStarters(current.projectPath, current.league, token) }
            .onFailure { error ->
                _state.update { it.copy(status = error.message ?: "The starter proposal changed; review it again.") }
            }
    }

    private fun SubsystemGeneratorState.revalidated(
        external: List<SubsystemProblem> = problems.filter { it.path.startsWith("project:") },
    ): SubsystemGeneratorState = previewPlanner.plan(this, external)

    override fun close() = scope.cancel()

    private companion object {
        fun uniqueId(base: String, used: List<String>): String {
            if (base !in used) return base
            var suffix = 2
            while ("$base$suffix" in used) suffix++
            return "$base$suffix"
        }

        fun nextChannel(document: SubsystemDocument): Int {
            val used = document.hardware.mapNotNullTo(hashSetOf()) { it.connection.channel }
            return (0..31).firstOrNull { it !in used } ?: 0
        }

        fun nextCanId(document: SubsystemDocument): Int {
            val used = document.hardware.mapNotNullTo(hashSetOf()) { it.connection.canId }
            return (1..62).firstOrNull { it !in used } ?: 1
        }
    }
}
