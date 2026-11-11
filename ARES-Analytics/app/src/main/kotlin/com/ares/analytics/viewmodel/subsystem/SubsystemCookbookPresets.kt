package com.ares.analytics.viewmodel.subsystem

import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.SubsystemBuilderStage
import com.ares.analytics.viewmodel.SubsystemEditorDraft
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.withAvailableTemplateConnections
import com.areslib.subsystem.SubsystemControlLoopDocument
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFaultRecoveryDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemFollowerDocument
import com.areslib.subsystem.SubsystemFollowerTransform
import com.areslib.subsystem.SubsystemHomingDocument
import com.areslib.subsystem.SubsystemImplementationDocument
import com.areslib.subsystem.SubsystemImplementationKind
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
import com.areslib.subsystem.supportsPlatform
import java.util.concurrent.atomic.AtomicLong

/**
 * Real-world 1-click mechanism presets connecting the Student Cookbook (`docs/examples/`)
 * directly to the Subsystem Builder.
 */
enum class SubsystemCookbookPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val targetLeague: League,
    val template: SubsystemTemplate,
    val defaultDocumentId: String,
    val defaultTypeName: String,
    val defaultDisplayName: String,
    val summary: String,
) {
    FTC_ROLLER_INTAKE(
        id = "ftc_roller_intake",
        title = "FTC Roller Intake",
        subtitle = "Open-loop voltage roller + beam break",
        targetLeague = League.FTC,
        template = SubsystemTemplate.INTAKE_CONVEYOR,
        defaultDocumentId = "roller-intake",
        defaultTypeName = "RollerIntake",
        defaultDisplayName = "Roller Intake",
        summary = "Cookbook preset: Open-loop intake roller with safe voltage clamping (-12 V to +12 V) and game-piece detection.",
    ),
    FTC_SCORING_ARM(
        id = "ftc_scoring_arm",
        title = "FTC Scoring Arm (0–115°)",
        subtitle = "Closed-loop PID + gravity FF arm",
        targetLeague = League.FTC,
        template = SubsystemTemplate.ARM_PIVOT,
        defaultDocumentId = "scoring-arm",
        defaultTypeName = "ScoringArm",
        defaultDisplayName = "Scoring Arm",
        summary = "Cookbook preset: Gravity-compensated scoring arm with Stow (0°), Intake (15°), and Score High (115°) presets.",
    ),
    FRC_FLYWHEEL_SHOOTER(
        id = "frc_flywheel_shooter",
        title = "FRC Flywheel Shooter",
        subtitle = "Velocity kV + kS + kP shooter wheel",
        targetLeague = League.FRC,
        template = SubsystemTemplate.FLYWHEEL_SHOOTER,
        defaultDocumentId = "flywheel-shooter",
        defaultTypeName = "FlywheelShooter",
        defaultDisplayName = "Flywheel Shooter",
        summary = "Cookbook preset: High-inertia flywheel shooter with velocity feedforward and at-speed readiness gate.",
    ),
    XRP_ULTRASONIC_RANGER(
        id = "xrp_ultrasonic_ranger",
        title = "XRP Ultrasonic Ranger",
        subtitle = "Distance & reflectance sensor reader",
        targetLeague = League.XRP,
        template = SubsystemTemplate.DISTANCE_SENSOR,
        defaultDocumentId = "ultrasonic-ranger",
        defaultTypeName = "UltrasonicRanger",
        defaultDisplayName = "Ultrasonic Ranger",
        summary = "Cookbook preset: Read-only XRP sensor subsystem for obstacle stopping distance and line following.",
    ),
}

fun availableCookbookPresets(league: League): List<SubsystemCookbookPreset> =
    SubsystemCookbookPreset.entries.filter { it.targetLeague == league }

fun availableCookbookPresets(platform: SubsystemPlatform): List<SubsystemCookbookPreset> {
    val league = when (platform) {
        SubsystemPlatform.FTC -> League.FTC
        SubsystemPlatform.FRC -> League.FRC
        SubsystemPlatform.XRP -> League.XRP
    }
    return availableCookbookPresets(league)
}

internal fun createCookbookPresetDocument(
    preset: SubsystemCookbookPreset,
    platform: SubsystemPlatform,
    existingDocuments: List<SubsystemDocument>,
): SubsystemDocument {
    val usedIds = existingDocuments.mapTo(hashSetOf()) { it.documentId }
    var suffix = 1
    var docId = preset.defaultDocumentId
    while (docId in usedIds) {
        docId = "${preset.defaultDocumentId}-${++suffix}"
    }
    val typeName = if (suffix == 1) preset.defaultTypeName else "${preset.defaultTypeName}$suffix"
    val displayName = if (suffix == 1) preset.defaultDisplayName else "${preset.defaultDisplayName} $suffix"
    val base = SubsystemTemplates.create(
        template = preset.template,
        documentId = docId,
        kotlinTypeName = typeName,
        platform = platform,
        displayName = displayName,
    ).withAvailableTemplateConnections(existingDocuments)
    return base.copy(
        description = preset.summary,
    )
}

internal fun navigateToSubsystemProblemState(
    current: SubsystemGeneratorState,
    path: String,
): SubsystemGeneratorState {
    val document = current.draft?.document ?: return current
    val index = Regex("\\[(\\d+)]").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return when {
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

internal fun selectSubsystemDocumentState(
    current: SubsystemGeneratorState,
    documentId: String,
    aiProposalGeneration: AtomicLong,
    revalidate: (SubsystemGeneratorState) -> SubsystemGeneratorState,
): SubsystemGeneratorState {
    if (current.dirty) return current.copy(status = "Save or reload the current draft before switching subsystems.")
    val document = current.documents.firstOrNull { it.documentId == documentId } ?: return current
    aiProposalGeneration.incrementAndGet()
    return revalidate(
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
        ),
    )
}

internal fun createNewSubsystemStateField(
    id: String,
    displayName: String,
    role: SubsystemFieldRole,
    type: SubsystemValueType,
): SubsystemStateFieldDocument = SubsystemStateFieldDocument(
    fieldId = id,
    displayName = displayName,
    type = type,
    role = role,
    defaultNumber = 0.0.takeIf { type == SubsystemValueType.DOUBLE },
    defaultBoolean = false.takeIf { type == SubsystemValueType.BOOLEAN },
    defaultInt = 0.takeIf { type == SubsystemValueType.INT },
    defaultText = "".takeIf { type == SubsystemValueType.STRING },
)

internal fun applyControlLoopGainsToLoop(
    loop: SubsystemControlLoopDocument,
    kp: Double,
    ki: Double,
    kd: Double,
    ks: Double,
    kv: Double,
    kg: Double,
): SubsystemControlLoopDocument {
    require(listOf(kp, ki, kd, ks, kv, kg).all(Double::isFinite)) {
        "Control and feedforward gains must be finite."
    }
    return loop.copy(
        kP = kp,
        kI = ki,
        kD = kd,
        feedforward = loop.feedforward.copy(
            kS = ks,
            kV = kv,
            kG = kg,
        ),
    )
}

/**
 * Pure, referentially safe edits for the canonical subsystem document graph.
 *
 * The Compose ViewModel owns selection and persistence. This editor owns the harder invariant that
 * renaming or removing a node must update every descriptor reference in the same transaction.
 */
