package com.ares.analytics.service

import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.service.drivebase.DrivebaseIssueSeverity
import com.ares.analytics.service.drivebase.FtcMecanumRuntimeParameters
import com.ares.analytics.service.drivebase.LocalizationKind
import com.ares.analytics.service.drivebase.validateDrivebase
import com.ares.analytics.service.hardware.HardwareReviewStatus
import com.ares.analytics.service.hardware.HardwareSetupService
import com.ares.analytics.service.project.templateDeploymentBlockReason
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.drivebase.toUiDrivebase
import com.ares.analytics.service.tuning.TuningWorkspaceDocuments
import com.ares.analytics.shared.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.util.ProjectLayout
import com.ares.analytics.service.project.persistence.ProjectDocumentKind
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.AresLeague
import com.areslib.simulation.SimulationProductId
import com.ares.analytics.viewmodel.controls.controlsCoverage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Canonical project evidence used by Robot Studio. No field represents physical validation. */
data class RobotProjectReadinessEvidence(
    val projectPath: String,
    val league: League,
    val projectError: String? = null,
    val metadataPresent: Boolean = false,
    val metadataLeagueMatches: Boolean = false,
    val metadataErrors: List<String> = emptyList(),
    val documentErrors: List<String> = emptyList(),
    val hardwareItemCount: Int = 0,
    val hardwareErrors: List<String> = emptyList(),
    val hardwareReviewStatus: HardwareReviewStatus = HardwareReviewStatus.NOT_REVIEWED,
    val hardwareReviewedBy: String? = null,
    val physicalDeploymentBlockReason: String? = null,
    val drivebaseKind: DrivebaseKind? = null,
    val drivebaseErrors: List<String> = emptyList(),
    val drivebaseNoCodeSupported: Boolean = false,
    val localizationConfigured: Boolean = false,
    val subsystemCount: Int = 0,
    val subsystemErrors: List<String> = emptyList(),
    val simulationProduct: SimulationProductId? = null,
    val simulationErrors: List<String> = emptyList(),
    val superstructureCount: Int = 0,
    val superstructureErrors: List<String> = emptyList(),
    val capabilityActionCount: Int = 0,
    val capabilityErrors: List<String> = emptyList(),
    val controlSchemeCount: Int = 0,
    val controllerProfileCount: Int = 0,
    val controlErrors: List<String> = emptyList(),
    val controlTeleopActionCount: Int = 0,
    val controlBoundActionCount: Int = 0,
    val controlSafetyActionCount: Int = 0,
    val controlBoundSafetyActionCount: Int = 0,
    val routineCount: Int = 0,
    val autonomousCatalogPresent: Boolean = false,
    val autonomousErrors: List<String> = emptyList(),
    val tuningDeclarationCount: Int = 0,
    val tuningProfileCount: Int = 0,
    val tuningError: String? = null,
    val generatedProjectSourcePresent: Boolean = false,
    val importedRunCount: Int = 0,
)

/**
 * Reads the existing canonical project documents and local run database without changing either.
 * The Studio consumes this evidence through its own pure stage evaluator.
 */
class RobotProjectReadinessService(
    private val databaseService: DatabaseService,
    private val projectSession: ProjectSession = ProjectSession(),
    private val hardwareSetupService: HardwareSetupService = HardwareSetupService(),
) {
    suspend fun inspect(config: WorkspaceConfig): RobotProjectReadinessEvidence = withContext(Dispatchers.IO) {
        val projectError = ProjectLayout.validationError(config.projectPath, config.league)
        if (projectError != null) {
            return@withContext RobotProjectReadinessEvidence(
                projectPath = config.projectPath,
                league = config.league,
                projectError = projectError,
            )
        }

        val targetPlatform = when (config.league) {
            League.FTC -> ControllerInputPlatform.FTC
            League.FRC -> ControllerInputPlatform.FRC
        }
        val projectSnapshot = runCatching {
            projectSession.snapshot(config.projectPath, targetPlatform, forceReload = true)
        }
        val snapshot = projectSnapshot.getOrNull()?.documents
        val snapshotFailure = projectSnapshot.exceptionOrNull()?.message
        val diagnostics = snapshot?.diagnostics.orEmpty()

        val rawProject = snapshot?.effectiveProject?.raw
        val drivebaseDiagnostics = diagnostics.filter { it.kind == ProjectDocumentKind.DRIVETRAIN }
        val drivebaseResult = runCatching {
            require(drivebaseDiagnostics.isEmpty()) {
                drivebaseDiagnostics.joinToString("; ") { "${it.file.name}: ${it.message}" }
            }
            val drivetrains = requireNotNull(rawProject) { snapshotFailure ?: "The canonical project did not load." }.drivetrains
            require(drivetrains.size <= 1) { "This project has multiple drivetrain documents." }
            drivetrains.singleOrNull()?.toUiDrivebase()
        }
        val drivebase = drivebaseResult.getOrNull()
        val drivebaseIssues = drivebase?.let(::validateDrivebase).orEmpty()
        val drivebaseErrors = buildList {
            drivebaseResult.exceptionOrNull()?.message?.let(::add)
            addAll(drivebaseIssues.filter { it.severity == DrivebaseIssueSeverity.ERROR }.map { it.message })
            drivebase?.canonical?.let(FtcMecanumRuntimeParameters::repairMessage)?.let(::add)
        }.distinct()

        val tuningDiagnostics = diagnostics.filter {
            it.kind == ProjectDocumentKind.TUNING_COMPONENT || it.kind == ProjectDocumentKind.TUNING_PROFILE
        }
        val tuningResult = runCatching {
            require(tuningDiagnostics.isEmpty()) {
                tuningDiagnostics.joinToString("; ") { "${it.file.name}: ${it.message}" }
            }
            val raw = requireNotNull(rawProject) { snapshotFailure ?: "The canonical project did not load." }
            TuningWorkspaceDocuments(
                catalog = raw.drivetrains.flatMap { it.parameters } +
                    raw.subsystems.flatMap { it.tuningParameters } +
                    raw.tuningComponents.flatMap { it.parameters },
                profiles = raw.tuningProfiles,
            )
        }
        val tuning = tuningResult.getOrNull()
        val hardwareResult = runCatching { hardwareSetupService.inspect(config.projectPath, config.league) }
        val hardware = hardwareResult.getOrNull()
        val hardwareErrors = buildList {
            hardwareResult.exceptionOrNull()?.message?.let(::add)
            addAll(hardware?.errorIssues.orEmpty().map { it.message })
        }.distinct()
        val matchingRuns = databaseService.getSessions().count { session ->
            session.teamId == config.teamId &&
                session.seasonId == config.seasonId &&
                session.robotId == config.robotId
        }
        val expectedLeague = when (config.league) {
            League.FTC -> AresLeague.FTC
            League.FRC -> AresLeague.FRC
        }
        val subsystemErrors = diagnostics.filter { it.kind == ProjectDocumentKind.SUBSYSTEM }
            .map { "${it.file.name}: ${it.message}" }
        val superstructureErrors = diagnostics.filter { it.kind == ProjectDocumentKind.SUPERSTRUCTURE }
            .map { "${it.file.name}: ${it.message}" }
        val metadataFilePresent = projectSession.metadataFile(config.projectPath).isFile
        val metadataErrors = diagnostics.filter {
            it.kind == ProjectDocumentKind.PROJECT_METADATA && metadataFilePresent
        }.map { "${it.file.name}: ${it.message}" }
        val capabilityErrors = diagnostics.filter { it.kind == ProjectDocumentKind.CAPABILITY_CATALOG }
            .map { "${it.file.name}: ${it.message}" }
        val controlErrors = diagnostics.filter {
            it.kind == ProjectDocumentKind.CONTROL_SCHEME || it.kind == ProjectDocumentKind.CONTROLLER_PROFILE
        }.map { "${it.file.name}: ${it.message}" }
        val autonomousErrors = diagnostics.filter {
            it.kind == ProjectDocumentKind.ROUTINE || it.kind == ProjectDocumentKind.AUTONOMOUS_CATALOG
        }.map { "${it.file.name}: ${it.message}" }
        val project = snapshot?.query
        val catalogActions = project?.actions.orEmpty()
        val controlCoverages = project?.controlSchemes.orEmpty().map { scheme ->
            controlsCoverage(catalogActions, scheme)
        }

        RobotProjectReadinessEvidence(
            projectPath = config.projectPath,
            league = config.league,
            projectError = snapshotFailure,
            metadataPresent = project?.metadata != null,
            metadataLeagueMatches = project?.metadata?.league == expectedLeague,
            metadataErrors = metadataErrors,
            documentErrors = diagnostics.map { "${it.file.name}: ${it.message}" },
            hardwareItemCount = hardware?.items?.size ?: 0,
            hardwareErrors = hardwareErrors,
            hardwareReviewStatus = hardware?.reviewStatus ?: HardwareReviewStatus.NOT_REVIEWED,
            hardwareReviewedBy = hardware?.reviewedBy,
            physicalDeploymentBlockReason = templateDeploymentBlockReason(File(config.projectPath)),
            drivebaseKind = drivebase?.kind,
            drivebaseErrors = drivebaseErrors,
            drivebaseNoCodeSupported = when (config.league) {
                League.FTC -> drivebase?.kind == DrivebaseKind.FTC_MECANUM
                League.FRC -> drivebase?.kind == DrivebaseKind.FRC_CTRE_SWERVE
            },
            localizationConfigured = drivebase != null && drivebaseErrors.isEmpty() &&
                drivebase.localization.count { it != LocalizationKind.VISION_FUSION } == 1,
            subsystemCount = project?.subsystems?.size ?: 0,
            subsystemErrors = subsystemErrors,
            simulationProduct = project?.simulationPlan?.product?.id,
            simulationErrors = project?.simulationPlan?.issues.orEmpty().map { it.message },
            superstructureCount = project?.superstructures?.size ?: 0,
            superstructureErrors = superstructureErrors,
            capabilityActionCount = project?.actions?.size ?: 0,
            capabilityErrors = capabilityErrors,
            controlSchemeCount = project?.controlSchemes?.size ?: 0,
            controllerProfileCount = project?.controllerProfiles?.size ?: 0,
            controlErrors = controlErrors,
            controlTeleopActionCount = controlCoverages.sumOf { it.totalCount },
            controlBoundActionCount = controlCoverages.sumOf { it.boundCount },
            // Count each safety action once per scheme: every selectable TeleOp scheme needs its
            // own direct recovery path, not merely a binding that happens to exist elsewhere.
            controlSafetyActionCount = controlCoverages.sumOf { it.safetyActions.size },
            controlBoundSafetyActionCount = controlCoverages.sumOf {
                it.safetyActions.size - it.missingSafetyActions.size
            },
            routineCount = project?.routines?.size ?: 0,
            autonomousCatalogPresent = project?.autonomousCatalog != null,
            autonomousErrors = autonomousErrors,
            tuningDeclarationCount = tuning?.catalog?.size ?: 0,
            tuningProfileCount = tuning?.profiles?.size ?: 0,
            tuningError = tuningResult.exceptionOrNull()?.message,
            generatedProjectSourcePresent = generatedProjectFile(config).isFile,
            importedRunCount = matchingRuns,
        )
    }

    private fun generatedProjectFile(config: WorkspaceConfig): File = File(
        config.projectPath,
        when (config.league) {
            League.FTC -> "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/generated/GeneratedAresProject.kt"
            League.FRC -> "src/main/kotlin/com/areslib/frc/generated/GeneratedAresProject.kt"
        },
    )
}
