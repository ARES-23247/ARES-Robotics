package com.ares.analytics.ui.components.robotstudio

import com.ares.analytics.viewmodel.robotstudio.RobotStudioStage
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageId
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageStatus
import com.ares.analytics.viewmodel.robotstudio.RobotStudioState

internal enum class RobotStudioPresentationTone {
    SUCCESS,
    INFO,
    WARNING,
    ERROR,
    MUTED,
}

internal data class RobotStudioStatusPresentation(
    val label: String,
    val tone: RobotStudioPresentationTone,
)

internal data class RobotStudioValidationPresentation(
    val status: RobotStudioStatusPresentation,
    val explanation: String,
    val issues: List<String>,
)

internal data class RobotStudioPanePresentation(
    val collapseTree: Boolean,
    val collapseInspector: Boolean,
)

internal fun robotStudioPanePresentation(
    availableWidthDp: Float,
    largeText: Boolean,
): RobotStudioPanePresentation = RobotStudioPanePresentation(
    collapseTree = availableWidthDp < if (largeText) 1_350f else 1_200f,
    collapseInspector = availableWidthDp < if (largeText) 1_700f else 1_550f,
)

internal fun robotStudioPersistedRevision(
    loading: Boolean,
    hasUnsavedChanges: Boolean,
    fingerprints: List<Any?>,
): String? = if (loading || hasUnsavedChanges) {
    null
} else {
    fingerprints.joinToString("|") { it?.toString() ?: "missing" }
}

internal fun RobotStudioState.progressPresentation(): RobotStudioStatusPresentation = when {
    loading -> RobotStudioStatusPresentation("Checking", RobotStudioPresentationTone.INFO)
    error != null -> RobotStudioStatusPresentation("Unavailable", RobotStudioPresentationTone.ERROR)
    stages.isEmpty() -> RobotStudioStatusPresentation("Not checked", RobotStudioPresentationTone.MUTED)
    structureStages().size < STRUCTURE_STAGE_IDS.size -> RobotStudioStatusPresentation(
        "${structureStages().size}/${STRUCTURE_STAGE_IDS.size} checked",
        RobotStudioPresentationTone.WARNING,
    )
    structureStages().count { it.status.isBlockingStructureStatus() } > 0 -> RobotStudioStatusPresentation(
        "${structureStages().count { it.status.isBlockingStructureStatus() }} blocked",
        RobotStudioPresentationTone.ERROR,
    )
    structureStages().any { it.status == RobotStudioStageStatus.NEEDS_ACTION } -> RobotStudioStatusPresentation(
        "${structureStages().count { it.status == RobotStudioStageStatus.NEEDS_ACTION }} needs action",
        RobotStudioPresentationTone.WARNING,
    )
    structureStages().all { it.status == RobotStudioStageStatus.READY || it.status == RobotStudioStageStatus.OPTIONAL } ->
        RobotStudioStatusPresentation("Ready", RobotStudioPresentationTone.SUCCESS)
    else -> RobotStudioStatusPresentation(
        "${structureStages().count { it.status == RobotStudioStageStatus.READY }}/${structureStages().size} ready",
        RobotStudioPresentationTone.WARNING,
    )
}

/**
 * The hierarchy header describes the canonical robot structure shown directly below it. Build,
 * simulation, deployment, and analysis have their own controls and must not make a complete
 * structure appear blocked merely because those later actions have not run yet.
 */
private fun RobotStudioState.structureStages(): List<RobotStudioStage> = stages.filter { stage ->
    stage.id in STRUCTURE_STAGE_IDS
}

private val STRUCTURE_STAGE_IDS = setOf(
    RobotStudioStageId.PROJECT_IDENTITY,
    RobotStudioStageId.HARDWARE,
    RobotStudioStageId.COORDINATION,
    RobotStudioStageId.AUTONOMOUS,
    RobotStudioStageId.CONTROLS,
)

private fun RobotStudioStageStatus.isBlockingStructureStatus(): Boolean =
    this == RobotStudioStageStatus.BLOCKED ||
        this == RobotStudioStageStatus.INVALID ||
        this == RobotStudioStageStatus.CODE_REQUIRED

internal fun RobotStudioState.nodePresentation(
    status: RobotStudioStageStatus?,
): RobotStudioStatusPresentation = when {
    loading -> RobotStudioStatusPresentation("Checking", RobotStudioPresentationTone.INFO)
    error != null -> RobotStudioStatusPresentation("Unavailable", RobotStudioPresentationTone.ERROR)
    status == null -> RobotStudioStatusPresentation("Not checked", RobotStudioPresentationTone.MUTED)
    else -> status.presentation()
}

internal fun RobotStudioState.stageFor(selection: RobotStudioSelection): RobotStudioStage? {
    val id = when (selection) {
        RobotStudioSelection.Identity -> RobotStudioStageId.PROJECT_IDENTITY
        RobotStudioSelection.Drivetrain,
        is RobotStudioSelection.Subsystem,
        RobotStudioSelection.PortMap -> RobotStudioStageId.HARDWARE
        RobotStudioSelection.Superstructure -> RobotStudioStageId.COORDINATION
        RobotStudioSelection.Autonomous -> RobotStudioStageId.AUTONOMOUS
        RobotStudioSelection.Controls -> RobotStudioStageId.CONTROLS
        RobotStudioSelection.Verification -> RobotStudioStageId.GENERATE_VERIFY
    }
    return stages.firstOrNull { it.id == id }
}

internal fun RobotStudioState.validationPresentation(
    selection: RobotStudioSelection,
): RobotStudioValidationPresentation = when {
    loading -> RobotStudioValidationPresentation(
        status = RobotStudioStatusPresentation("CHECKING", RobotStudioPresentationTone.INFO),
        explanation = "Inspecting the selected project's canonical documents. No readiness claim is available yet.",
        issues = emptyList(),
    )
    error != null -> RobotStudioValidationPresentation(
        status = RobotStudioStatusPresentation("UNAVAILABLE", RobotStudioPresentationTone.ERROR),
        explanation = error,
        issues = listOf(error),
    )
    selection == RobotStudioSelection.Drivetrain && hardwareReadiness != null ->
        hardwareReadiness.drivetrain.toValidationPresentation()
    selection == RobotStudioSelection.PortMap && hardwareReadiness != null ->
        hardwareReadiness.portMap.toValidationPresentation()
    else -> {
        val stage = stageFor(selection)
        if (stage == null) {
            RobotStudioValidationPresentation(
                status = RobotStudioStatusPresentation("NOT CHECKED", RobotStudioPresentationTone.MUTED),
                explanation = "No validated readiness result is available for this section. Refresh Robot Studio before continuing.",
                issues = emptyList(),
            )
        } else {
            RobotStudioValidationPresentation(
                status = stage.status.presentation(uppercase = true),
                explanation = stage.explanation,
                issues = stage.issues,
            )
        }
    }
}

private fun com.ares.analytics.viewmodel.robotstudio.RobotStudioSectionReadiness.toValidationPresentation() =
    RobotStudioValidationPresentation(
        status = status.presentation(uppercase = true),
        explanation = explanation,
        issues = issues,
    )

private fun RobotStudioStageStatus.presentation(uppercase: Boolean = false): RobotStudioStatusPresentation {
    val text = when (this) {
        RobotStudioStageStatus.READY -> "Ready"
        RobotStudioStageStatus.NEEDS_ACTION -> "Needs action"
        RobotStudioStageStatus.BLOCKED -> "Blocked"
        RobotStudioStageStatus.INVALID -> "Invalid"
        RobotStudioStageStatus.OPTIONAL -> "Optional"
        RobotStudioStageStatus.CODE_REQUIRED -> "Code required"
        RobotStudioStageStatus.RUNNING -> "Running"
    }
    val tone = when (this) {
        RobotStudioStageStatus.READY -> RobotStudioPresentationTone.SUCCESS
        RobotStudioStageStatus.RUNNING -> RobotStudioPresentationTone.INFO
        RobotStudioStageStatus.NEEDS_ACTION,
        RobotStudioStageStatus.CODE_REQUIRED -> RobotStudioPresentationTone.WARNING
        RobotStudioStageStatus.BLOCKED,
        RobotStudioStageStatus.INVALID -> RobotStudioPresentationTone.ERROR
        RobotStudioStageStatus.OPTIONAL -> RobotStudioPresentationTone.MUTED
    }
    return RobotStudioStatusPresentation(if (uppercase) text.uppercase() else text, tone)
}
