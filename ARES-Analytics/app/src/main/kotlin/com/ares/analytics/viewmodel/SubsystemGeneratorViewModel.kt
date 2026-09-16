package com.ares.analytics.viewmodel

import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.AresProjectGenerator
import com.ares.analytics.service.SubsystemDesignAssistant
import com.ares.analytics.service.SubsystemDesignProposal
import com.ares.analytics.service.sanitizeSubsystemDesignCandidate
import com.ares.analytics.viewmodel.subsystem.ensureXrpExtensionScaffold
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.shared.models.League
import com.areslib.project.schema.ProjectDocumentKind
import com.areslib.project.AresLeague
import com.areslib.project.requireXrpRuntimeOptions
import com.ares.analytics.viewmodel.subsystem.SubsystemDocumentGraphEditor
import com.ares.analytics.viewmodel.subsystem.SubsystemDocumentAuthoring
import com.ares.analytics.viewmodel.subsystem.SubsystemProjectPersistence
import com.ares.analytics.viewmodel.subsystem.SubsystemRemovalCoordinator
import com.ares.analytics.viewmodel.subsystem.SubsystemAiProposalCoordinator
import com.ares.analytics.viewmodel.subsystem.SubsystemStarterGeneratorOps
import com.ares.analytics.viewmodel.subsystem.SubsystemCreationOps
import com.areslib.subsystem.SubsystemControlLoopDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemFollowerTransform
import com.areslib.subsystem.SubsystemHardwareDocument
import com.areslib.subsystem.SubsystemHardwareKind
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
    private val documents: com.ares.analytics.service.project.ProjectDocumentGateway = AresProjectDocuments(),
    private val projectGenerator: AresProjectGenerator? = null,
    private val designAssistant: SubsystemDesignAssistant? = null,
    private val checkpointRecorder: ProjectCheckpointRecorder = ProjectCheckpointRecorder.NONE,
    private val projectSession: ProjectSession? = null,
    loadOnStart: Boolean = true,
) : AutoCloseable {
    private val reviewGson = GsonBuilder().setPrettyPrinting().create()
    private val platform = when (league) {
        League.FTC -> SubsystemPlatform.FTC
        League.FRC -> SubsystemPlatform.FRC
        League.XRP -> SubsystemPlatform.XRP
    }
    private val basePackage = when (league) {
        League.FTC -> "org.firstinspires.ftc.teamcode.subsystems"
        League.FRC -> "com.areslib.frc.subsystems"
        League.XRP -> "xrp.subsystems"
    }
    private val previewPlanner = SubsystemBuilderPreviewPlanner(league, platform, basePackage)
    private val persistence = SubsystemProjectPersistence(documents, projectSession)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val aiProposalGeneration = java.util.concurrent.atomic.AtomicLong()
    private val reloadGeneration = java.util.concurrent.atomic.AtomicLong()
    private val _state = MutableStateFlow(SubsystemGeneratorState(projectPath, league))
    val state: StateFlow<SubsystemGeneratorState> = _state.asStateFlow()

    private val removalCoordinator = SubsystemRemovalCoordinator(
        documents = documents,
        persistence = persistence,
        projectGenerator = projectGenerator,
        checkpointRecorder = checkpointRecorder,
        scope = scope,
        aiProposalGeneration = aiProposalGeneration,
        getState = { _state.value },
        updateState = { updater -> _state.update(updater) },
        revalidate = { it.revalidated() },
    )
    private val aiProposalCoordinator = SubsystemAiProposalCoordinator(
        designAssistant = designAssistant,
        reviewGson = reviewGson,
        scope = scope,
        aiProposalGeneration = aiProposalGeneration,
        getState = { _state.value },
        updateState = { updater -> _state.update(updater) },
        revalidate = { it.revalidated() },
    )
    private val starterGeneratorOps = SubsystemStarterGeneratorOps(
        projectGenerator = projectGenerator,
        getState = { _state.value },
        updateState = { updater -> _state.update(updater) },
    )
    private val creationOps = SubsystemCreationOps(
        league = league,
        platform = platform,
        basePackage = basePackage,
        aiProposalGeneration = aiProposalGeneration,
        getState = { _state.value },
        updateState = { updater -> _state.update(updater) },
        revalidate = { it.revalidated() },
        blockDraftReplacement = { blockDraftReplacement() },
    )

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
        if (loadOnStart) reload()
    }

    fun reloadAsync(): kotlinx.coroutines.Job {
        val current = _state.value
        val generation = reloadGeneration.incrementAndGet()
        return scope.launch(Dispatchers.IO) { reload(current, generation) }
    }

    private fun blockDraftReplacement(): Boolean {
        if (!_state.value.projectLoaded) {
            _state.update { it.copy(status = "Load the project successfully before adding a subsystem.") }
            return true
        }
        if (!_state.value.dirty) return false
        _state.update { it.copy(status = "Save or reload the current draft before adding another subsystem.") }
        return true
    }

    fun reload() = reload(_state.value, reloadGeneration.incrementAndGet())

    private fun reload(current: SubsystemGeneratorState, generation: Long) {
        aiProposalGeneration.incrementAndGet()
        if (current.projectPath.isBlank()) {
            commitReload(generation, current, current.copy(loadError = "Choose a robot project directory to edit subsystems."))
            return
        }
        val target = when (league) {
            League.FTC -> com.areslib.controls.ControllerInputPlatform.FTC
            League.FRC -> com.areslib.controls.ControllerInputPlatform.FRC
            League.XRP -> com.areslib.controls.ControllerInputPlatform.XRP
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
                if (generation != reloadGeneration.get()) return@onSuccess
                commitReload(generation, current, current.copy(
                    xrpControllerModel = snapshot.query.metadata
                        ?.takeIf { it.league == AresLeague.XRP }
                        ?.requireXrpRuntimeOptions()
                        ?.controllerModel,
                    projectLoaded = true,
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
                ).revalidated(projectProblems))
            }
            .onFailure { error ->
                if (generation == reloadGeneration.get())
                    commitReload(generation, current, current.copy(loadError = error.message ?: "Subsystem documents could not be loaded."))
            }
    }

    private fun commitReload(generation: Long, expected: SubsystemGeneratorState, loaded: SubsystemGeneratorState) {
        _state.update { latest ->
            val comparable = latest.copy(generationPhase = expected.generationPhase,
                generationMessage = expected.generationMessage, generatedContentHash = expected.generatedContentHash)
            val initialLoad = !latest.projectLoaded && latest.draft == null
            if (generation != reloadGeneration.get() || (!initialLoad && comparable != expected)) latest else loaded.copy(generationPhase = latest.generationPhase,
                generationMessage = latest.generationMessage, generatedContentHash = latest.generatedContentHash)
        }
    }

    fun newSubsystem(template: SubsystemTemplate = _state.value.selectedTemplate) = creationOps.newSubsystem(template)

    fun setTemplatePickerVisible(visible: Boolean) = _state.update { it.copy(showTemplatePicker = visible) }

    fun selectTemplate(template: SubsystemTemplate) = _state.update { it.copy(selectedTemplate = template) }

    fun applyTemplate(template: SubsystemTemplate) = creationOps.applyTemplate(template)

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

    fun registerHandAuthoredSubsystem() = creationOps.registerHandAuthoredSubsystem()

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
            aiProposalGeneration.incrementAndGet()
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
        aiProposalGeneration.incrementAndGet()
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
        aiProposalGeneration.incrementAndGet()
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
        aiProposalGeneration.incrementAndGet()
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
    fun requestAiProposal(studentRequest: String) = aiProposalCoordinator.requestAiProposal(studentRequest)

    fun dismissAiProposal() = aiProposalCoordinator.dismissAiProposal()

    fun applyAiProposal() = aiProposalCoordinator.applyAiProposal()

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
        val id = uniqueId("device", _state.value.draft?.document?.hardware.orEmpty().map { it.hardwareId })
        edit { document ->
            SubsystemDocumentAuthoring.addHardware(document, kind, id, platform)
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
        runCatching {
            ensureXrpExtensionScaffold(current.projectPath, draft)
            persistence.save(current.projectPath, current.projectRevision, draft)
        }
            .onSuccess { saved ->
                _state.update { state ->
                    val persisted = saved.document
                    state.copy(
                        documents = state.documents.filterNot { it.documentId == persisted.documentId } + persisted,
                        selectedDocumentId = persisted.documentId,
                        draft = SubsystemEditorDraft(persisted),
                        dirty = false,
                        projectRevision = persistence.currentRevision(state.projectRevision),
                        status = "Saved revision ${persisted.revision} (${saved.contentHash.take(12)}â€¦).",
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

    fun requestRemoveSubsystem() = removalCoordinator.requestRemoveSubsystem()

    fun cancelRemoveSubsystem() = removalCoordinator.cancelRemoveSubsystem()

    fun confirmRemoveSubsystem() = removalCoordinator.confirmRemoveSubsystem()

    fun restoreRemovedSubsystem() = removalCoordinator.restoreRemovedSubsystem()

    fun dismissRecoveryNotice() = removalCoordinator.dismissRecoveryNotice()

    fun generate() = starterGeneratorOps.generate { if (_state.value.dirty) save() }

    fun cancelStarterReplacement() = starterGeneratorOps.cancelStarterReplacement()

    fun confirmStarterReplacement() = starterGeneratorOps.confirmStarterReplacement()

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

    }
}
