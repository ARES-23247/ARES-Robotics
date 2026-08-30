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
import com.ares.analytics.shared.League
import com.ares.analytics.service.project.persistence.ProjectDocumentKind
import com.ares.analytics.service.project.persistence.ProjectDocumentRemovalPlan
import com.ares.analytics.viewmodel.subsystem.SubsystemDocumentGraphEditor
import com.areslib.codegen.GeneratedSubsystemSourceSet
import com.areslib.codegen.SubsystemArtifact
import com.areslib.codegen.SubsystemArtifactGroup
import com.areslib.codegen.SubsystemArtifactOwnership
import com.areslib.codegen.SubsystemKotlinCodegenTarget
import com.areslib.codegen.SubsystemKotlinGenerator
import com.areslib.codegen.SubsystemStarterReconciler
import com.areslib.codegen.SubsystemStarterChangeKind
import com.areslib.subsystem.FaultRecoveryActionKind
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemControlLoopDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemContinuousInputDocument
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFaultRecoveryDocument
import com.areslib.subsystem.SubsystemFeedforwardDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemFollowerTransform
import com.areslib.subsystem.SubsystemHardwareConnection
import com.areslib.subsystem.SubsystemHardwareDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemHardwareScaffolding
import com.areslib.subsystem.SubsystemHomingComparison
import com.areslib.subsystem.SubsystemHomingDocument
import com.areslib.subsystem.SubsystemHomingEvidenceDocument
import com.areslib.subsystem.SubsystemHomingMethod
import com.areslib.subsystem.SubsystemImplementationDocument
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.isAresGenerated
import com.areslib.subsystem.SubsystemInterlockDocument
import com.areslib.subsystem.SubsystemMeasurementSource
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
import com.areslib.subsystem.SubsystemUnits
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

enum class SubsystemProblemSeverity { WARNING, ERROR }

/** Novice-facing authoring stages. The order mirrors the questions a student can answer safely. */
enum class SubsystemBuilderStage(
    val displayName: String,
    val shortDescription: String,
) {
    PURPOSE("Purpose", "Choose what the subsystem does and how its source is owned."),
    HARDWARE("Hardware", "Describe each motor, servo, sensor, or other device."),
    STATE_AND_BEHAVIOR("State & behavior", "Define cached inputs, targets, and controller rules."),
    TUNING("Tuning", "Declare typed values students may adjust through named robot profiles."),
    SAFETY("Safety", "Decide when outputs are permitted and how faults recover."),
    CAPABILITIES("Capabilities", "Review what drivers and autonomous routines can command."),
    SIMULATION_AND_TESTING("Simulation & testing", "Choose mock support and generated verification."),
    REVIEW("Review", "Check warnings, ownership, and generated files before saving."),
}

data class SubsystemProblem(
    val severity: SubsystemProblemSeverity,
    val path: String,
    val message: String,
)

/** Review model for a whole-subsystem removal. Kotlin source is intentionally never included. */
data class SubsystemRemovalRequest(
    val documentId: String,
    val displayName: String,
    val persisted: Boolean,
    val contentHash: String? = null,
    val canonicalPath: String? = null,
    val recoveryPath: String? = null,
    val sourceFilesPreserved: List<String> = emptyList(),
    val discardsUnsavedChanges: Boolean = false,
)

/** Session-local recovery action for the exact descriptor most recently removed. */
data class SubsystemRecoveryNotice(
    val documentId: String,
    val displayName: String,
    val contentHash: String,
    val recoveryPath: String,
)

data class SubsystemPreviewFile(
    val path: String,
    val sourceSet: GeneratedSubsystemSourceSet,
    val content: String,
    val artifact: SubsystemArtifact,
    val group: SubsystemArtifactGroup,
    val ownership: SubsystemArtifactOwnership,
    val description: String,
    val moduleName: String,
    val projectRelativePath: String,
    val change: SubsystemFileChange,
    val diff: List<SubsystemDiffLine> = emptyList(),
)

enum class SubsystemFileChange { CREATE, UNCHANGED, UPDATE_GENERATED, REPLACE_STARTER, PROTECTED_USER_OWNED }

enum class SubsystemDiffLineKind { CONTEXT, ADDED, REMOVED }

data class SubsystemDiffLine(val kind: SubsystemDiffLineKind, val text: String)

data class SubsystemAiProposalReview(
    val base: SubsystemDocument,
    val proposal: SubsystemDesignProposal,
    val diff: List<SubsystemDiffLine>,
    val problems: List<SubsystemProblem>,
) {
    val canApply: Boolean get() = problems.none { it.severity == SubsystemProblemSeverity.ERROR }
}

data class SubsystemTemplateOption(
    val template: SubsystemTemplate,
    val label: String,
    val description: String,
    val category: String,
    val beginnerRecommended: Boolean = false,
)

val subsystemTemplateOptions = listOf(
    SubsystemTemplateOption(
        SubsystemTemplate.SIMPLE_ACTUATOR,
        "Simple actuator",
        "Bounded open-loop motor output with a declared neutral state and fault monitoring.",
        "Motors",
        true,
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
        "Position-controlled mechanism",
        "Closed-loop position control with cached feedback, soft limits, and stale-signal handling.",
        "Motors",
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.VELOCITY_CONTROLLED_MECHANISM,
        "Velocity-controlled mechanism",
        "Closed-loop velocity control with current monitoring and a safe spin-down path.",
        "Motors",
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.ELEVATOR_LIFT,
        "Elevator or lift",
        "Profiled height control, limit-switch homing, gravity feedforward, and soft limits.",
        "Common mechanisms",
        true,
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.ARM_PIVOT,
        "Arm or pivot",
        "Profiled angular control, homing, cosine gravity feedforward, and angular limits.",
        "Common mechanisms",
        true,
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.FLYWHEEL_SHOOTER,
        "Flywheel or shooter",
        "Velocity PID plus feedforward, ready tolerance, and current monitoring.",
        "Common mechanisms",
        true,
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.INTAKE_CONVEYOR,
        "Intake, conveyor, or indexer",
        "Open-loop motion with current-based jam detection, bounded recovery, and game-piece simulation.",
        "Common mechanisms",
        true,
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.DUAL_MOTOR_FOLLOWER,
        "Dual-motor leader/follower",
        "One controller drives a leader and an explicitly inverted follower with group-safe neutral behavior.",
        "Motors",
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.POSITIONAL_SERVO,
        "Positional servo",
        "Normalized 0–1 position commands, a reviewed safe position, inversion, and mirrored followers.",
        "Servos and indicators",
        true,
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.CONTINUOUS_SERVO,
        "Continuous-rotation servo",
        "Normalized bidirectional power with neutral, inversion, and follower support.",
        "Servos and indicators",
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.SENSOR_ONLY_SUBSYSTEM,
        "Sensor-only subsystem",
        "A cached, validity-aware input snapshot with telemetry and no actuator output.",
        "Sensors",
    ),
    SubsystemTemplateOption(SubsystemTemplate.LIMIT_SWITCH_SENSOR, "Limit switch", "A cached digital end-stop with explicit active polarity and freshness.", "Sensors", true),
    SubsystemTemplateOption(SubsystemTemplate.BEAM_BREAK_SENSOR, "Beam-break sensor", "A cached presence sensor for game pieces, indexing, and interlocks.", "Sensors", true),
    SubsystemTemplateOption(SubsystemTemplate.POTENTIOMETER_SENSOR, "Potentiometer", "An analog position input with documented voltage-to-state conversion.", "Sensors"),
    SubsystemTemplateOption(SubsystemTemplate.ABSOLUTE_ENCODER_SENSOR, "Absolute encoder", "An absolute angular measurement published in canonical radians; controller wrap is not inferred.", "Sensors"),
    SubsystemTemplateOption(SubsystemTemplate.QUADRATURE_ENCODER_SENSOR, "Quadrature encoder", "Position and velocity feedback with explicit counts-per-revolution calibration.", "Sensors"),
    SubsystemTemplateOption(SubsystemTemplate.DISTANCE_SENSOR, "Distance sensor", "A cached metric distance signal with validity bounds and freshness.", "Sensors"),
    SubsystemTemplateOption(SubsystemTemplate.IMU_SENSOR, "IMU or gyroscope", "Cached yaw and yaw-rate feedback in radians for orientation-aware mechanisms.", "Sensors"),
    SubsystemTemplateOption(SubsystemTemplate.PNEUMATIC_ACTUATOR, "Pneumatic actuator", "An FRC solenoid with explicit module, channel, safe-off output, and generated mock.", "Actuators"),
    SubsystemTemplateOption(
        SubsystemTemplate.HOMED_MECHANISM,
        "Homed mechanism",
        "Position control gated on an explicit home reference, calibration health, and soft limits.",
        "Homing",
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.CURRENT_HOMED_MECHANISM,
        "Current-stall homing",
        "Bounded sensorless homing using fresh current evidence, dwell, timeout, and neutral-before-zero.",
        "Homing",
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.VELOCITY_HOMED_MECHANISM,
        "Velocity-stall homing",
        "Bounded sensorless homing using fresh low-velocity evidence, dwell, and timeout.",
        "Homing",
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.COMPOSITE_MECHANISM,
        "Composite mechanism",
        "Coordinated devices with one atomic snapshot, neutral policy, and partial-failure handling.",
        "Advanced mechanisms",
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.TWO_DOF_ARM,
        "Two-joint arm",
        "Two profiled joints with explicit geometry, coupled gravity feedforward, and generated linkage simulation.",
        "Advanced mechanisms",
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.INDICATOR_LIGHT_PWM,
        "PWM indicator light",
        "Color-safe named lighting output with a declared off state.",
        "Servos and indicators",
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.PRISM_LED_DRIVER,
        "goBILDA Prism LED driver",
        "Pattern pulse-width control, brightness metadata, and a safe off preset.",
        "Servos and indicators",
    ),
    SubsystemTemplateOption(
        SubsystemTemplate.ADVANCED_CUSTOM,
        "Advanced/custom",
        "An explicit starting point that requires every applicable hardware and safety choice.",
        "Advanced mechanisms",
    ),
)

/**
 * UI editing session kept separate from the strict persisted descriptor.
 *
 * The canonical document remains the generation contract; this layer owns reversible student
 * edits and exposes a single compile boundary through [document].
 */
data class SubsystemEditorDraft(
    val document: SubsystemDocument,
    val undo: List<SubsystemDocument> = emptyList(),
    val redo: List<SubsystemDocument> = emptyList(),
) {
    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun edit(transform: (SubsystemDocument) -> SubsystemDocument): SubsystemEditorDraft {
        val next = transform(document)
        if (next == document) return this
        return copy(document = next, undo = (undo + document).takeLast(50), redo = emptyList())
    }

    fun undo(): SubsystemEditorDraft = undo.lastOrNull()?.let { previous ->
        copy(document = previous, undo = undo.dropLast(1), redo = (redo + document).takeLast(50))
    } ?: this

    fun redo(): SubsystemEditorDraft = redo.lastOrNull()?.let { next ->
        copy(document = next, undo = (undo + document).takeLast(50), redo = redo.dropLast(1))
    } ?: this
}

data class SubsystemGeneratorState(
    val projectPath: String,
    val league: League,
    val documents: List<SubsystemDocument> = emptyList(),
    val selectedDocumentId: String? = null,
    val draft: SubsystemEditorDraft? = null,
    val selectedHardwareUid: String? = null,
    val selectedFieldUid: String? = null,
    val selectedLoopUid: String? = null,
    val selectedInterlockId: String? = null,
    val selectedTuningParameterUid: String? = null,
    val activeStage: SubsystemBuilderStage = SubsystemBuilderStage.PURPOSE,
    /** Session-local navigation evidence used by guided learning; never persisted as safety proof. */
    val visitedStages: Set<SubsystemBuilderStage> = setOf(SubsystemBuilderStage.PURPOSE),
    val selectedTemplate: SubsystemTemplate = SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
    val previewFiles: List<SubsystemPreviewFile> = emptyList(),
    val generatedPlumbingExpanded: Boolean = false,
    val pendingStarterReplacements: List<SubsystemPreviewFile> = emptyList(),
    val starterConfirmationToken: String? = null,
    val problems: List<SubsystemProblem> = emptyList(),
    val dirty: Boolean = false,
    val generationPhase: AresGenerationPhase = AresGenerationPhase.IDLE,
    val generationMessage: String? = null,
    val generatedContentHash: String? = null,
    val status: String? = null,
    val loadError: String? = null,
    val projectRevision: ProjectSessionRevision? = null,
    val aiProposalInProgress: Boolean = false,
    val aiProposal: SubsystemAiProposalReview? = null,
    val aiProposalError: String? = null,
    val showTemplatePicker: Boolean = false,
    val pendingRemoval: SubsystemRemovalRequest? = null,
    val recentRecovery: SubsystemRecoveryNotice? = null,
) {
    val canSave: Boolean
        get() = dirty && loadError == null && problems.none { it.severity == SubsystemProblemSeverity.ERROR }

    val canGenerate: Boolean
        get() = !dirty && draft != null && loadError == null &&
            generationPhase != AresGenerationPhase.RUNNING &&
            problems.none { it.severity == SubsystemProblemSeverity.ERROR }

    val hasProtectedUserOwnedConflict: Boolean
        get() = previewFiles.any { it.change == SubsystemFileChange.PROTECTED_USER_OWNED }

    val canUndo: Boolean get() = draft?.canUndo == true
    val canRedo: Boolean get() = draft?.canRedo == true
}

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
        if (method == SubsystemHomingMethod.NONE) {
            return@edit document.copy(safety = document.safety.copy(homing = SubsystemHomingDocument()))
        }
        val motor = document.hardware.firstOrNull { it.kind == SubsystemHardwareKind.MOTOR }
        val measurements = document.hardware.flatMap { it.measurements }
        val digital = measurements.firstOrNull { it.source == SubsystemMeasurementSource.DIGITAL_STATE }
        val current = measurements.firstOrNull { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }
        val velocity = measurements.firstOrNull { it.source == SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND }
        val evidence = when (method) {
            SubsystemHomingMethod.DIGITAL_SENSOR -> listOfNotNull(
                digital?.let { SubsystemHomingEvidenceDocument(it.fieldId, SubsystemHomingComparison.TRUE) }
            )
            SubsystemHomingMethod.CURRENT_STALL -> listOfNotNull(
                current?.let { SubsystemHomingEvidenceDocument(it.fieldId, SubsystemHomingComparison.AT_OR_ABOVE, 5.0) }
            )
            SubsystemHomingMethod.VELOCITY_STALL -> listOfNotNull(
                velocity?.let { SubsystemHomingEvidenceDocument(it.fieldId, SubsystemHomingComparison.ABS_AT_OR_BELOW, 0.5) }
            )
            SubsystemHomingMethod.CURRENT_AND_VELOCITY_STALL -> listOfNotNull(
                current?.let { SubsystemHomingEvidenceDocument(it.fieldId, SubsystemHomingComparison.AT_OR_ABOVE, 5.0) },
                velocity?.let { SubsystemHomingEvidenceDocument(it.fieldId, SubsystemHomingComparison.ABS_AT_OR_BELOW, 0.5) },
            )
            SubsystemHomingMethod.CUSTOM_MEASUREMENT -> emptyList()
            SubsystemHomingMethod.NONE -> emptyList()
        }
        document.copy(
            safety = document.safety.copy(
                homing = SubsystemHomingDocument(
                    method = method,
                    actuatorId = motor?.hardwareId,
                    searchOutput = -2.0,
                    evidence = evidence,
                ),
                requiresCurrentMonitoring = document.safety.requiresCurrentMonitoring ||
                    method == SubsystemHomingMethod.CURRENT_STALL ||
                    method == SubsystemHomingMethod.CURRENT_AND_VELOCITY_STALL,
            )
        )
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
        require(kind.supportsPlatform(platform)) {
            "Generated ${kind.name.lowercase().replace('_', ' ')} hardware is not supported for $platform projects"
        }
        val existing = document.hardware.firstOrNull { it.hardwareId == id } ?: return@edit document
        if (existing.kind == kind) return@edit document
        val ownedLoops = document.controlLoops.filter { it.actuatorId == id }
        val providedFieldIds = existing.measurements.mapTo(linkedSetOf()) { it.fieldId }.apply {
            addAll(ownedLoops.map { it.targetFieldId })
        }
        val scaffold = SubsystemHardwareScaffolding.create(
            kind,
            id,
            existing.displayName,
            platform,
            hardwareMapName = existing.connection.hardwareMapName ?: id,
            canId = existing.connection.canId ?: nextCanId(document),
            channel = existing.connection.channel ?: nextChannel(document),
        )
        document.copy(
            hardware = document.hardware.map {
                when {
                    it.hardwareId == id -> scaffold.hardware.copy(uid = existing.uid)
                    it.following?.leaderId == id -> it.copy(following = null)
                    else -> it
                }
            },
            stateFields = document.stateFields.filterNot { it.fieldId in providedFieldIds } + scaffold.stateFields,
            controlLoops = document.controlLoops.filterNot { it.actuatorId == id } + scaffold.controlLoops,
            safety = if (document.safety.homing.actuatorId == id) {
                document.safety.copy(homing = SubsystemHomingDocument())
            } else document.safety,
        )
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

    fun changeStateFieldType(id: String, type: SubsystemValueType) = updateStateField(id) { field ->
        field.copy(
            type = type,
            defaultNumber = if (type == SubsystemValueType.DOUBLE) field.defaultNumber ?: 0.0 else null,
            defaultBoolean = if (type == SubsystemValueType.BOOLEAN) field.defaultBoolean ?: false else null,
            defaultInt = if (type == SubsystemValueType.INT) field.defaultInt ?: 0 else null,
            defaultText = if (type == SubsystemValueType.STRING) field.defaultText.orEmpty() else null,
            unit = field.unit.takeIf { type == SubsystemValueType.DOUBLE || type == SubsystemValueType.INT },
            minimum = field.minimum.takeIf { type == SubsystemValueType.DOUBLE || type == SubsystemValueType.INT },
            maximum = field.maximum.takeIf { type == SubsystemValueType.DOUBLE || type == SubsystemValueType.INT },
        )
    }

    fun renameStateFieldId(id: String, newId: String) {
        if (newId == id) return
        edit { document -> SubsystemDocumentGraphEditor.renameStateField(document, id, newId) }
        selectField(_state.value.draft?.document?.stateFields?.firstOrNull { it.fieldId == newId }?.uid)
    }

    fun addControlLoop() {
        val current = _state.value.draft?.document ?: return
        val controlledActuators = current.controlLoops.mapTo(mutableSetOf()) { it.actuatorId }
        val actuator = current.hardware.firstOrNull {
            it.kind.isActuator() && it.following == null && it.hardwareId !in controlledActuators
        }
        if (actuator == null) {
            _state.update { it.copy(status = "Every independent actuator already has a controller. Edit the existing rule instead of adding a conflicting output.") }
            return
        }
        val target = current.stateFields.firstOrNull { it.role == SubsystemFieldRole.TARGET && it.type.isNumeric() }
        if (target == null) {
            _state.update { it.copy(status = "Add a numeric target state value before creating a controller.") }
            return
        }
        val id = uniqueId("control", current.controlLoops.map { it.loopId })
        edit { document ->
        val owned = document.controlLoops.mapTo(mutableSetOf()) { it.actuatorId }
        val actuator = document.hardware.firstOrNull {
            it.kind.isActuator() && it.following == null && it.hardwareId !in owned
        } ?: return@edit document
        val target = document.stateFields.firstOrNull { it.role == SubsystemFieldRole.TARGET && it.type.isNumeric() }
            ?: return@edit document
        val measurement = document.stateFields.firstOrNull {
            it.role == SubsystemFieldRole.MEASUREMENT && it.type.isNumeric() &&
                SubsystemUnits.controlUnitsCompatible(target.unit, it.unit)
        }
        val strategy = when {
            actuator.kind == SubsystemHardwareKind.POSITIONAL_SERVO -> SubsystemControlStrategy.SERVO_POSITION
            measurement != null -> SubsystemControlStrategy.POSITION_PID
            else -> SubsystemControlStrategy.DIRECT
        }
        val loop = SubsystemControlLoopDocument(
            loopId = id,
            displayName = "New control",
            strategy = strategy,
            actuatorId = actuator.hardwareId,
            targetFieldId = target.fieldId,
            measurementFieldId = if (strategy.requiresMeasurement()) measurement?.fieldId else null,
            minimumOutput = if (actuator.kind == SubsystemHardwareKind.MOTOR) -12.0 else -1.0,
            maximumOutput = if (actuator.kind == SubsystemHardwareKind.MOTOR) 12.0 else 1.0,
        )
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
        val currentLoop = document.controlLoops.firstOrNull { it.loopId == id } ?: return@edit document
        val currentActuator = document.hardware.firstOrNull { it.hardwareId == currentLoop.actuatorId } ?: return@edit document
        val claimedByAnother = document.controlLoops.any { it.loopId != id && it.actuatorId == actuatorId }
        val actuator = document.hardware.firstOrNull {
            it.hardwareId == actuatorId && it.kind == currentActuator.kind && it.kind.isActuator() && it.following == null
        }
        if (claimedByAnother || actuator == null) return@edit document
        document.copy(controlLoops = document.controlLoops.map { loop ->
            if (loop.loopId != id) loop else loop.copy(actuatorId = actuatorId)
        })
    }

    fun changeControlLoopTarget(id: String, targetFieldId: String) = edit { document ->
        val target = document.stateFields.firstOrNull {
            it.fieldId == targetFieldId &&
                it.role in setOf(SubsystemFieldRole.TARGET, SubsystemFieldRole.CONFIGURATION) &&
                it.type.isNumeric()
        } ?: return@edit document
        document.copy(controlLoops = document.controlLoops.map { loop ->
            if (loop.loopId != id) loop else {
                val currentMeasurement = loop.measurementFieldId?.let { measurementId ->
                    document.stateFields.firstOrNull { it.fieldId == measurementId }
                }
                val compatibleMeasurement = currentMeasurement?.takeIf {
                    SubsystemUnits.controlUnitsCompatible(target.unit, it.unit)
                } ?: document.stateFields.firstOrNull {
                    it.role == SubsystemFieldRole.MEASUREMENT && it.type.isNumeric() &&
                        SubsystemUnits.controlUnitsCompatible(target.unit, it.unit)
                }
                loop.copy(
                    targetFieldId = targetFieldId,
                    measurementFieldId = if (loop.strategy.requiresMeasurement()) compatibleMeasurement?.fieldId else null,
                    continuousInput = loop.continuousInput.copy(
                        enabled = loop.continuousInput.enabled && SubsystemUnits.isCanonicalAngle(target.unit) &&
                            SubsystemUnits.isCanonicalAngle(compatibleMeasurement?.unit),
                    ),
                )
            }
        })
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
        val loop = document.controlLoops.firstOrNull { it.loopId == id } ?: return@edit document
        val actuator = document.hardware.firstOrNull { it.hardwareId == loop.actuatorId }
        val preferredSource = when (strategy) {
            SubsystemControlStrategy.VELOCITY_PID -> SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
            SubsystemControlStrategy.BANG_BANG -> SubsystemMeasurementSource.MOTOR_POSITION_NATIVE
            else -> null
        }
        val target = document.stateFields.firstOrNull { it.fieldId == loop.targetFieldId }
        val preferredMeasurement = preferredSource?.let { source ->
            actuator?.measurements?.firstOrNull { it.source == source }?.fieldId
        }?.let { fieldId -> document.stateFields.firstOrNull { it.fieldId == fieldId } }
            ?.takeIf { target == null || SubsystemUnits.controlUnitsCompatible(target.unit, it.unit) }
            ?.fieldId
            ?: document.stateFields.firstOrNull {
            it.role == SubsystemFieldRole.MEASUREMENT && it.type in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT) &&
                (target == null || SubsystemUnits.controlUnitsCompatible(target.unit, it.unit))
        }?.fieldId
        val supportsFeedforward = strategy in setOf(
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
            SubsystemControlStrategy.VELOCITY_PID,
        )
        val supportsContinuousInput = strategy in setOf(
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
        )
        document.copy(controlLoops = document.controlLoops.map { candidate ->
            if (candidate.loopId != id) candidate else candidate.copy(
                strategy = strategy,
                measurementFieldId = preferredMeasurement.takeIf { strategy.requiresMeasurement() },
                feedforward = candidate.feedforward.takeIf { supportsFeedforward } ?: SubsystemFeedforwardDocument(),
                continuousInput = candidate.continuousInput.takeIf { supportsContinuousInput }
                    ?: SubsystemContinuousInputDocument(),
                hysteresis = candidate.hysteresis.takeIf { strategy == SubsystemControlStrategy.BANG_BANG } ?: 0.0,
            )
        })
    }

    fun selectInterlock(id: String?) = _state.update { it.copy(selectedInterlockId = id) }

    fun addInterlock() {
        val snapshot = _state.value
        val current = snapshot.draft?.document ?: return
        val target = snapshot.documents
            .asSequence()
            .filter { it.uid != current.uid }
            .filter { it.implementation.kind.isAresGenerated() }
            .filter { it.stateFields.isNotEmpty() }
            .sortedBy { it.displayName.lowercase() }
            .firstOrNull()
        if (target == null) {
            _state.update {
                it.copy(status = "Add another generated subsystem with state values before creating a cross-mechanism interlock.")
            }
            return
        }
        val field = target.stateFields.first()
        val id = uniqueId("interlock", current.interlocks.map { it.interlockId })
        val interlock = SubsystemInterlockDocument(
            interlockId = id,
            targetSubsystemUid = target.uid,
            targetFieldId = field.fieldId,
            comparison = if (field.type in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) {
                InterlockComparison.LESS_THAN
            } else {
                InterlockComparison.EQUALS_STATE
            },
            thresholdValue = 0.0,
            targetStateName = when (field.type) {
                SubsystemValueType.BOOLEAN -> "false"
                SubsystemValueType.STRING -> ""
                else -> null
            },
            forbiddenZoneDescription = "Prevent mechanism collision",
        )
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
            val session = projectSession
            val revision = current.projectRevision
            if (session != null && revision != null) {
                when (val result = session.saveSubsystem(revision, draft)) {
                    is ProjectSessionMutationResult.Applied -> result.value.revision
                    is ProjectSessionMutationResult.Stale -> error("The project changed after this subsystem loaded. Reload before saving.")
                    is ProjectSessionMutationResult.Conflict -> error(result.message)
                    is ProjectSessionMutationResult.Failed -> error(result.message)
                }
            } else {
                documents.subsystems.save(current.projectPath, draft)
            }
        }
            .onSuccess { saved ->
                _state.update { state ->
                    val persisted = saved.document
                    state.copy(
                        documents = state.documents.filterNot { it.documentId == persisted.documentId } + persisted,
                        selectedDocumentId = persisted.documentId,
                        draft = SubsystemEditorDraft(persisted),
                        dirty = false,
                        projectRevision = projectSession?.state?.value?.revision ?: state.projectRevision,
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
            runCatching {
                val session = projectSession
                val revision = current.projectRevision
                if (session != null && revision != null) {
                    when (val result = session.removalPlan(
                        revision,
                        com.ares.analytics.service.project.RemovableProjectDocumentKind.SUBSYSTEM,
                        draft.documentId,
                    )) {
                        is ProjectSessionMutationResult.Applied -> result.value
                        is ProjectSessionMutationResult.Stale -> error(
                            "The project changed after this subsystem loaded. Reload before reviewing removal.",
                        )
                        is ProjectSessionMutationResult.Conflict -> error(result.message)
                        is ProjectSessionMutationResult.Failed -> error(result.message)
                    }
                } else {
                    documents.subsystems.removalPlan(current.projectPath, draft.documentId)
                }
            }
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
            val session = projectSession
            val revision = current.projectRevision
            if (session != null && revision != null) {
                when (val result = session.remove(
                    revision,
                    com.ares.analytics.service.project.RemovableProjectDocumentKind.SUBSYSTEM,
                    request.documentId,
                    expectedHash,
                )) {
                    is ProjectSessionMutationResult.Applied -> result.value
                    is ProjectSessionMutationResult.Stale -> error("The project changed after removal review. Reload before removing this subsystem.")
                    is ProjectSessionMutationResult.Conflict -> error(result.message)
                    is ProjectSessionMutationResult.Failed -> error(result.message)
                }
            } else {
                documents.subsystems.remove(current.projectPath, request.documentId, expectedHash)
            }
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
            val session = projectSession
            val revision = current.projectRevision
            if (session != null && revision != null) {
                when (val result = session.restoreRemovedSubsystem(
                    revision,
                    recovery.documentId,
                    recovery.contentHash,
                    recovery.recoveryPath,
                )) {
                    is ProjectSessionMutationResult.Applied -> result.value
                    is ProjectSessionMutationResult.Stale -> error("The project changed after this recovery was offered. Reload before restoring.")
                    is ProjectSessionMutationResult.Conflict -> error(result.message)
                    is ProjectSessionMutationResult.Failed -> error(result.message)
                }
            } else {
                documents.subsystems.restoreRemoved(
                    current.projectPath,
                    recovery.documentId,
                    recovery.contentHash,
                    recovery.recoveryPath,
                )
            }
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
                    projectRevision = projectSession?.state?.value?.revision ?: it.projectRevision,
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
        val target = when (league) {
            League.FTC -> com.areslib.controls.ControllerInputPlatform.FTC
            League.FRC -> com.areslib.controls.ControllerInputPlatform.FRC
        }
        projectSession?.snapshot(projectPath, target, forceReload = true)
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
                projectRevision = projectSession?.state?.value?.revision ?: current.projectRevision,
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
    ): SubsystemGeneratorState {
        val document = draft?.document ?: return copy(previewFiles = emptyList(), problems = external)
        val validation = SubsystemSchema.validate(document).map {
            SubsystemProblem(SubsystemProblemSeverity.ERROR, it.path, it.message)
        } + projectConnectionProblems(document, documents)
        val generated = if (validation.isEmpty() && document.implementation.kind.isAresGenerated()) {
            val sourceFiles = SubsystemKotlinGenerator.generate(document, SubsystemKotlinCodegenTarget(platform, basePackage))
            val starterPlan = SubsystemStarterReconciler.plan(starterRoot().toPath(), sourceFiles)
            val starterChanges = starterPlan.changes.associateBy { it.relativePath }
            sourceFiles.map { file ->
                val destination = artifactDestination(file.relativePath, file.sourceSet, file.ownership)
                val existing = safeExistingFile(destination)?.takeIf(File::isFile)?.readText()
                val planned = starterChanges[file.relativePath.replace('\\', '/')]
                val change = when (planned?.kind) {
                    SubsystemStarterChangeKind.ADD -> SubsystemFileChange.CREATE
                    SubsystemStarterChangeKind.UNCHANGED -> SubsystemFileChange.UNCHANGED
                    SubsystemStarterChangeKind.REPLACE -> SubsystemFileChange.REPLACE_STARTER
                    SubsystemStarterChangeKind.PROTECTED -> SubsystemFileChange.PROTECTED_USER_OWNED
                    null -> when {
                        existing == null -> SubsystemFileChange.CREATE
                        existing == file.content -> SubsystemFileChange.UNCHANGED
                        file.ownership == SubsystemArtifactOwnership.USER_OWNED -> SubsystemFileChange.PROTECTED_USER_OWNED
                        else -> SubsystemFileChange.UPDATE_GENERATED
                    }
                }
                SubsystemPreviewFile(
                    path = file.relativePath,
                    sourceSet = file.sourceSet,
                    content = file.content,
                    artifact = file.artifact,
                    group = file.group,
                    ownership = file.ownership,
                    description = file.description,
                    moduleName = if (league == League.FTC) "ARES-FTC · :TeamCode" else "ARES-FRC · root",
                    projectRelativePath = destination,
                    change = change,
                    diff = planned?.diff?.takeIf(String::isNotBlank)?.let(::parseUnifiedDiff)
                        ?: existing?.takeIf { it != file.content }?.let { structuredLineDiff(it, file.content) }.orEmpty(),
                )
            }
        } else emptyList()
        val token = if (validation.isEmpty() && document.implementation.kind == SubsystemImplementationKind.GENERATED_STARTER) {
            val sources = SubsystemKotlinGenerator.generate(document, SubsystemKotlinCodegenTarget(platform, basePackage))
            SubsystemStarterReconciler.plan(starterRoot().toPath(), sources).confirmationToken
        } else null
        return copy(
            previewFiles = generated,
            starterConfirmationToken = token,
            problems = (external + validation + safetyWarnings(document))
                .distinctBy { Triple(it.severity, it.path, it.message) },
        )
    }

    private fun artifactDestination(
        relativePath: String,
        sourceSet: GeneratedSubsystemSourceSet,
        ownership: SubsystemArtifactOwnership,
    ): String {
        val packagePath = basePackage.replace('.', '/')
        val sourceKind = if (sourceSet == GeneratedSubsystemSourceSet.TEST) "test" else "main"
        val root = when {
            ownership == SubsystemArtifactOwnership.GENERATED_DO_NOT_EDIT && league == League.FTC ->
                "TeamCode/build/generated/ares/$sourceKind/kotlin/$packagePath"
            ownership == SubsystemArtifactOwnership.GENERATED_DO_NOT_EDIT ->
                "build/generated/ares/$sourceKind/kotlin/$packagePath"
            league == League.FTC && sourceSet == GeneratedSubsystemSourceSet.TEST -> "TeamCode/src/test/java/$packagePath"
            league == League.FTC -> "TeamCode/src/main/java/$packagePath"
            sourceSet == GeneratedSubsystemSourceSet.TEST -> "src/test/kotlin/$packagePath"
            else -> "src/main/kotlin/$packagePath"
        }
        return "$root/${relativePath.replace('\\', '/')}"
    }

    private fun safeExistingFile(projectRelativePath: String): File? {
        val root = File(_state.value.projectPath).canonicalFile
        val candidate = File(root, projectRelativePath).canonicalFile
        return candidate.takeIf { it.toPath().startsWith(root.toPath()) }
    }

    private fun starterRoot(): File {
        val relative = if (league == League.FTC) {
            "TeamCode/src/main/java/${basePackage.replace('.', '/')}"
        } else {
            "src/main/kotlin/${basePackage.replace('.', '/')}"
        }
        val root = File(_state.value.projectPath).canonicalFile
        return File(root, relative).canonicalFile.also {
            require(it.toPath().startsWith(root.toPath())) { "Subsystem starter root escaped the project" }
        }
    }

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

private fun safetyWarnings(document: SubsystemDocument): List<SubsystemProblem> = buildList {
    fun warn(path: String, message: String) = add(SubsystemProblem(SubsystemProblemSeverity.WARNING, path, message))
    val hasActuators = document.hardware.any { it.kind.isActuator() }
    if (!hasActuators) return@buildList
    if (!document.safety.requiresConfigurationHealth) {
        warn("safety.requiresConfigurationHealth", "Configuration health is not gating actuator output.")
    }
    if (!document.safety.latchOutputFaults) {
        warn("safety.latchOutputFaults", "Failed output writes will not latch a fault; verify this is intentional.")
    }
    if (!document.safety.requiresExplicitNeutralRecovery) {
        warn("safety.requiresExplicitNeutralRecovery", "Fault recovery does not require a successful explicit neutral command.")
    }
    if (!document.safety.zeroAllocationPeriodic) {
        warn("safety.zeroAllocationPeriodic", "The periodic-path zero-allocation contract is disabled.")
    }
    if (!document.safety.telemetryEnabled) {
        warn("safety.telemetryEnabled", "Safety telemetry is disabled, reducing pit-side fault visibility.")
    }
    if (document.safety.requiresCurrentMonitoring && document.hardware.none { device ->
            device.measurements.any { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }
        }
    ) {
        warn("safety.requiresCurrentMonitoring", "Current monitoring is required but no cached current measurement is configured.")
    }
    document.hardware.forEachIndexed { hardwareIndex, device ->
        if (device.kind != SubsystemHardwareKind.MOTOR) return@forEachIndexed
        device.measurements.forEachIndexed { measurementIndex, measurement ->
            if (measurement.source !in setOf(
                    SubsystemMeasurementSource.MOTOR_POSITION_NATIVE,
                    SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND,
                ) || measurement.scale != 1.0
            ) return@forEachIndexed
            val field = document.stateFields.firstOrNull { it.fieldId == measurement.fieldId }
            if (!field?.unit.isNullOrBlank()) {
                warn(
                    "hardware[$hardwareIndex].measurements[$measurementIndex].scale",
                    "${field.displayName} is labeled '${field.unit}' but still uses a 1:1 native motor scale. Review gearing/encoder conversion before tuning or physical use.",
                )
            }
        }
    }
}

private fun SubsystemHardwareKind.isActuator(): Boolean = this == SubsystemHardwareKind.MOTOR ||
    this == SubsystemHardwareKind.POSITIONAL_SERVO || this == SubsystemHardwareKind.CONTINUOUS_SERVO ||
    this == SubsystemHardwareKind.INDICATOR_LIGHT || this == SubsystemHardwareKind.PRISM_DRIVER ||
    this == SubsystemHardwareKind.SOLENOID

private fun SubsystemValueType.isNumeric(): Boolean = this == SubsystemValueType.DOUBLE || this == SubsystemValueType.INT

private fun SubsystemControlStrategy.requiresMeasurement(): Boolean = this == SubsystemControlStrategy.POSITION_PID ||
    this == SubsystemControlStrategy.PROFILED_POSITION_PID || this == SubsystemControlStrategy.VELOCITY_PID ||
    this == SubsystemControlStrategy.BANG_BANG

/**
 * Gives a newly applied GUI template addresses that do not immediately collide with another
 * subsystem. FTC keeps a familiar short default until it is already owned; FRC mechanism CAN
 * devices start in the intentionally separate 20-62 range because the drivetrain commonly owns
 * the low IDs. Every value remains an editable draft and still goes through Hardware Setup.
 */
private fun SubsystemDocument.withAvailableTemplateConnections(
    existingDocuments: Collection<SubsystemDocument>,
): SubsystemDocument {
    val existingHardware = existingDocuments
        .filterNot { it.documentId == documentId }
        .flatMap { it.hardware }
    val usedNames = existingHardware.mapNotNullTo(linkedSetOf()) {
        it.connection.hardwareMapName?.trim()?.takeIf(String::isNotEmpty)
    }
    val usedCan = existingHardware.mapNotNullTo(linkedSetOf()) { device ->
        device.connection.canId?.let { device.connection.canBus.trim().lowercase() to it }
    }
    val usedChannels = existingHardware.flatMapTo(linkedSetOf()) { device ->
        buildList {
            device.connection.channel?.let { add(device.kind.channelNamespace() to it) }
            device.connection.secondaryChannel?.let { add(device.kind.channelNamespace() to it) }
        }
    }
    val namePrefix = documentId
        .lowercase()
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
        .ifBlank { "subsystem" }

    return copy(hardware = hardware.map { device ->
        var connection = device.connection
        if (platform == SubsystemPlatform.FTC) {
            val currentName = connection.hardwareMapName?.trim()
            if (!currentName.isNullOrEmpty()) {
                val chosen = if (usedNames.add(currentName)) {
                    currentName
                } else {
                    uniqueTextValue("${namePrefix}_${device.hardwareId}", usedNames)
                }
                connection = connection.copy(hardwareMapName = chosen)
            }
        } else {
            connection.canId?.let { requested ->
                val bus = connection.canBus.trim().lowercase()
                val chosen = requested.takeIf { it in 20..62 && (bus to it) !in usedCan }
                    ?: (20..62).firstOrNull { (bus to it) !in usedCan }
                    ?: requested
                usedCan += bus to chosen
                connection = connection.copy(canId = chosen)
            }
            connection.channel?.let { requested ->
                val namespace = device.kind.channelNamespace()
                val chosen = requested.takeIf { (namespace to it) !in usedChannels }
                    ?: (0..31).firstOrNull { (namespace to it) !in usedChannels }
                    ?: requested
                usedChannels += namespace to chosen
                connection = connection.copy(channel = chosen)
            }
            connection.secondaryChannel?.let { requested ->
                val namespace = device.kind.channelNamespace()
                val chosen = requested.takeIf { (namespace to it) !in usedChannels }
                    ?: (0..31).firstOrNull { (namespace to it) !in usedChannels }
                    ?: requested
                usedChannels += namespace to chosen
                connection = connection.copy(secondaryChannel = chosen)
            }
        }
        device.copy(connection = connection)
    })
}

private fun uniqueTextValue(base: String, used: MutableSet<String>): String {
    var candidate = base
    var suffix = 2
    while (!used.add(candidate)) candidate = "${base}_${suffix++}"
    return candidate
}

private fun SubsystemHardwareKind.channelNamespace(): String = when (this) {
    SubsystemHardwareKind.POSITIONAL_SERVO,
    SubsystemHardwareKind.CONTINUOUS_SERVO,
    SubsystemHardwareKind.INDICATOR_LIGHT,
    SubsystemHardwareKind.PRISM_DRIVER -> "pwm"
    SubsystemHardwareKind.DIGITAL_INPUT,
    SubsystemHardwareKind.QUADRATURE_ENCODER -> "dio"
    SubsystemHardwareKind.ANALOG_INPUT,
    SubsystemHardwareKind.ABSOLUTE_ENCODER,
    SubsystemHardwareKind.DISTANCE_SENSOR -> "analog"
    SubsystemHardwareKind.SOLENOID -> "solenoid"
    else -> name.lowercase()
}

/** Cross-document ownership is a builder error, not a surprise deferred to Verify & build. */
private fun projectConnectionProblems(
    document: SubsystemDocument,
    savedDocuments: Collection<SubsystemDocument>,
): List<SubsystemProblem> {
    val others = savedDocuments.filterNot { it.documentId == document.documentId }
    val nameOwners = mutableMapOf<String, String>()
    val canOwners = mutableMapOf<Pair<String, Int>, String>()
    val channelOwners = mutableMapOf<Pair<String, Int>, String>()
    others.forEach { owner ->
        owner.hardware.forEach { device ->
            val label = "${owner.displayName} / ${device.displayName}"
            device.connection.hardwareMapName?.trim()?.takeIf(String::isNotEmpty)?.let { nameOwners.putIfAbsent(it, label) }
            device.connection.canId?.let { canOwners.putIfAbsent(device.connection.canBus.trim().lowercase() to it, label) }
            val namespace = device.kind.channelNamespace()
            device.connection.channel?.let { channelOwners.putIfAbsent(namespace to it, label) }
            device.connection.secondaryChannel?.let { channelOwners.putIfAbsent(namespace to it, label) }
        }
    }
    return buildList {
        document.hardware.forEachIndexed { index, device ->
            device.connection.hardwareMapName?.trim()?.takeIf(String::isNotEmpty)?.let { name ->
                nameOwners[name]?.let { owner ->
                    add(SubsystemProblem(
                        SubsystemProblemSeverity.ERROR,
                        "hardware[$index].connection.hardwareMapName",
                        "Hardware-map name '$name' is already owned by $owner. Give every subsystem device a unique configured name.",
                    ))
                }
            }
            device.connection.canId?.let { canId ->
                val bus = device.connection.canBus.trim().lowercase()
                canOwners[bus to canId]?.let { owner ->
                    add(SubsystemProblem(
                        SubsystemProblemSeverity.ERROR,
                        "hardware[$index].connection.canId",
                        "CAN ID $canId on ${device.connection.canBus} is already owned by $owner. Choose an unused device ID.",
                    ))
                }
            }
            val namespace = device.kind.channelNamespace()
            listOfNotNull(device.connection.channel, device.connection.secondaryChannel).forEach { channel ->
                channelOwners[namespace to channel]?.let { owner ->
                    add(SubsystemProblem(
                        SubsystemProblemSeverity.ERROR,
                        "hardware[$index].connection.channel",
                        "${namespace.uppercase()} channel $channel is already owned by $owner. Choose an unused channel.",
                    ))
                }
            }
        }
    }
}

/**
 * Small deterministic line diff for starter replacement review. Common context is intentionally
 * bounded so a large generated file cannot bury the customization that would be replaced.
 */
internal fun structuredLineDiff(existing: String, proposed: String, contextLines: Int = 3): List<SubsystemDiffLine> {
    val before = existing.lines()
    val after = proposed.lines()
    var prefix = 0
    while (prefix < before.size && prefix < after.size && before[prefix] == after[prefix]) prefix++
    var suffix = 0
    while (
        suffix < before.size - prefix && suffix < after.size - prefix &&
        before[before.lastIndex - suffix] == after[after.lastIndex - suffix]
    ) suffix++

    val leadingStart = (prefix - contextLines.coerceAtLeast(0)).coerceAtLeast(0)
    val trailingCount = suffix.coerceAtMost(contextLines.coerceAtLeast(0))
    return buildList {
        before.subList(leadingStart, prefix).forEach { add(SubsystemDiffLine(SubsystemDiffLineKind.CONTEXT, it)) }
        before.subList(prefix, before.size - suffix).forEach { add(SubsystemDiffLine(SubsystemDiffLineKind.REMOVED, it)) }
        after.subList(prefix, after.size - suffix).forEach { add(SubsystemDiffLine(SubsystemDiffLineKind.ADDED, it)) }
        if (trailingCount > 0) {
            after.subList(after.size - suffix, after.size - suffix + trailingCount)
                .forEach { add(SubsystemDiffLine(SubsystemDiffLineKind.CONTEXT, it)) }
        }
    }
}

internal fun parseUnifiedDiff(diff: String): List<SubsystemDiffLine> = diff.lineSequence()
    .filterNot { it.startsWith("@@") }
    .map { line ->
        when {
            line.startsWith("+") -> SubsystemDiffLine(SubsystemDiffLineKind.ADDED, line.drop(1))
            line.startsWith("-") -> SubsystemDiffLine(SubsystemDiffLineKind.REMOVED, line.drop(1))
            else -> SubsystemDiffLine(SubsystemDiffLineKind.CONTEXT, line.removePrefix(" "))
        }
    }
    .toList()
