package com.ares.analytics.viewmodel

import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.SubsystemDesignProposal
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.shared.models.League
import com.areslib.codegen.GeneratedSubsystemSourceSet
import com.areslib.codegen.SubsystemArtifact
import com.areslib.codegen.SubsystemArtifactGroup
import com.areslib.codegen.SubsystemArtifactOwnership
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.project.AresXrpControllerModel

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
    SubsystemTemplateOption(SubsystemTemplate.REFLECTANCE_SENSOR, "XRP reflectance sensor", "One built-in left, middle, or right line-reflectance channel with a normalized reading.", "Sensors", true),
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
    SubsystemTemplateOption(SubsystemTemplate.DIGITAL_OUTPUT, "XRP digital output", "A fail-closed general-purpose GPIO output with an explicit safe-off state.", "Actuators"),
    SubsystemTemplateOption(SubsystemTemplate.PWM_OUTPUT, "XRP PWM output", "A bounded general-purpose PWM output for supported expansion hardware.", "Actuators"),
    SubsystemTemplateOption(SubsystemTemplate.BUZZER_NOTE, "XRP buzzer", "Non-blocking notes on the built-in buzzer, selected by standard MIDI note number.", "Servos and indicators", true),
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
    val xrpControllerModel: AresXrpControllerModel? = null,
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

