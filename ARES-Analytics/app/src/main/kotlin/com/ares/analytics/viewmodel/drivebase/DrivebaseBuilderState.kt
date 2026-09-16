package com.ares.analytics.viewmodel.drivebase

import com.ares.analytics.service.DrivebaseDesignProposal
import com.ares.analytics.service.drivebase.DriveGeometry
import com.ares.analytics.service.drivebase.DriveHardwareDeclaration
import com.ares.analytics.service.drivebase.DriveHardwareRole
import com.ares.analytics.service.drivebase.DriveSafetyDeclaration
import com.ares.analytics.service.drivebase.DrivebaseChange
import com.ares.analytics.service.drivebase.DrivebaseDocument
import com.ares.analytics.service.drivebase.DrivebaseIssue
import com.ares.analytics.service.drivebase.DrivebaseIssueSeverity
import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.service.drivebase.LocalizationKind
import com.ares.analytics.service.drivebase.defaultDrivebase
import com.ares.analytics.service.drivebase.defaultNoCodeDrivebaseKind
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.shared.models.League

enum class DrivebaseBuilderStep { DRIVE_TYPE, HARDWARE, GEOMETRY, CONTROL, LOCALIZATION, REVIEW }
enum class DrivebaseDiscardAction { RELOAD, CHANGE_KIND }

enum class LocalizationFailureScenario { ALL_HEALTHY, PRIMARY_STALE, HEADING_INVALID, VISION_REJECTED }

data class LocalizationLabResult(val canDriveClosedLoop: Boolean, val usesVisionCorrection: Boolean, val message: String)

data class GeometryLabResult(
    val turningRadiusMeters: Double?,
    val trackCircleDiameterMeters: Double?,
    val maxLinearSpeedMps: Double? = null,
    val maxAngularSpeedRadPerSec: Double? = null,
    val explanation: String
)

data class DriveLabState(
    val forward: Double = 0.0,
    val strafe: Double = 0.0,
    val rotate: Double = 0.0,
    val headingDegrees: Double = 0.0,
    val fieldRelative: Boolean = true,
    val localizationScenario: LocalizationFailureScenario = LocalizationFailureScenario.ALL_HEALTHY
)

data class DriveLabResult(
    val robotForward: Double,
    val robotStrafe: Double,
    val wheelOutputs: Map<String, Double>,
    val moduleAnglesDegrees: Map<String, Double> = emptyMap(),
    val explanation: String
)

data class DrivebaseSaveReview(
    val changes: List<DrivebaseChange>,
    val confirmationToken: String,
    val baseContentHash: String?
)

data class DrivebaseAiProposalReview(
    val proposal: DrivebaseDesignProposal,
    val candidate: DrivebaseDocument,
    val changes: List<DrivebaseChange>,
    val issues: List<DrivebaseIssue>,
    val baseContentHash: String,
) {
    val canApply: Boolean get() = issues.none { it.severity == DrivebaseIssueSeverity.ERROR }
}

data class DrivebaseBuilderState(
    val projectPath: String,
    val projectId: String,
    val league: League,
    val projectRevision: ProjectSessionRevision? = null,
    val saved: DrivebaseDocument? = null,
    val draft: DrivebaseDocument = defaultDrivebase(projectId, defaultNoCodeDrivebaseKind(league), league),
    val step: DrivebaseBuilderStep = DrivebaseBuilderStep.DRIVE_TYPE,
    val selectedHardwareId: String? = null,
    val advanced: Boolean = false,
    val issues: List<DrivebaseIssue> = emptyList(),
    val lab: DriveLabState = DriveLabState(),
    val saveReview: DrivebaseSaveReview? = null,
    val importPath: String = "",
    val importWarnings: List<String> = emptyList(),
    val tuningProfileRepairIssues: List<String> = emptyList(),
    val status: String = "",
    val error: String? = null,
    val loading: Boolean = true,
    val dirty: Boolean = false,
    val pendingDiscardAction: DrivebaseDiscardAction? = null,
    val pendingKind: DrivebaseKind? = null,
    val aiProposalInProgress: Boolean = false,
    val aiProposal: DrivebaseAiProposalReview? = null,
    val aiProposalError: String? = null,
)

sealed interface DrivebaseBuilderIntent {
    data object Reload : DrivebaseBuilderIntent
    data class SelectStep(val step: DrivebaseBuilderStep) : DrivebaseBuilderIntent
    data class SelectKind(val kind: DrivebaseKind) : DrivebaseBuilderIntent
    data class SelectHardware(val id: String?) : DrivebaseBuilderIntent
    data class UpdateHardware(val device: DriveHardwareDeclaration) : DrivebaseBuilderIntent
    data object UseSimulationCanIds : DrivebaseBuilderIntent
    data class AddHardware(val role: DriveHardwareRole) : DrivebaseBuilderIntent
    data class RemoveHardware(val id: String) : DrivebaseBuilderIntent
    data class UpdateGeometry(val geometry: DriveGeometry) : DrivebaseBuilderIntent
    data class UpdateControl(
        val supported: List<com.areslib.drivetrain.DrivetrainControlKind>,
        val defaultMode: com.areslib.drivetrain.DrivetrainControlKind,
        val fieldRelative: Boolean,
    ) : DrivebaseBuilderIntent
    data class SetLocalization(val kind: LocalizationKind, val enabled: Boolean) : DrivebaseBuilderIntent
    data class UpdateSafety(val safety: DriveSafetyDeclaration) : DrivebaseBuilderIntent
    data class SetAdvanced(val enabled: Boolean) : DrivebaseBuilderIntent
    data class UpdateLab(val lab: DriveLabState) : DrivebaseBuilderIntent
    data class SetImportPath(val path: String) : DrivebaseBuilderIntent
    data object ImportCtre : DrivebaseBuilderIntent
    data object ReviewSave : DrivebaseBuilderIntent
    data class ConfirmSave(val token: String) : DrivebaseBuilderIntent
    data object ConfirmDiscard : DrivebaseBuilderIntent
    data object CancelDiscard : DrivebaseBuilderIntent
}
