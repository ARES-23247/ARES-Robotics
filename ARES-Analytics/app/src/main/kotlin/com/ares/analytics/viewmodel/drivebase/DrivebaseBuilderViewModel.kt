package com.ares.analytics.viewmodel.drivebase

import com.ares.analytics.service.DrivebaseDesignAssistant
import com.ares.analytics.service.DrivebaseDesignProposal
import com.ares.analytics.service.drivebase.*
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.shared.models.League
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.project.AresProjectMetadataCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class DrivebaseBuilderStep { DRIVE_TYPE, HARDWARE, GEOMETRY, CONTROL, LOCALIZATION, REVIEW }
enum class DrivebaseDiscardAction { RELOAD, CHANGE_KIND }

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
    val draft: DrivebaseDocument = defaultDrivebase(projectId, defaultNoCodeDrivebaseKind(league)),
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

class DrivebaseBuilderViewModel(
    projectPath: String,
    projectId: String,
    league: League,
    private val scope: CoroutineScope,
    private val repository: DrivebaseProjectRepository = DrivebaseProjectRepository(),
    private val designAssistant: DrivebaseDesignAssistant? = null,
    private val checkpointRecorder: ProjectCheckpointRecorder = ProjectCheckpointRecorder.NONE,
    private val projectSession: ProjectSession? = null,
) {
    private val canonicalProjectId = canonicalRuntimeProjectUid(projectPath, projectId, league)
    private val _state = MutableStateFlow(DrivebaseBuilderState(projectPath, canonicalProjectId, league))
    val state: StateFlow<DrivebaseBuilderState> = _state.asStateFlow()

    init { onIntent(DrivebaseBuilderIntent.Reload) }

    fun onIntent(intent: DrivebaseBuilderIntent) {
        when (intent) {
            DrivebaseBuilderIntent.Reload -> if (_state.value.dirty) requestDiscard(DrivebaseDiscardAction.RELOAD) else load()
            is DrivebaseBuilderIntent.SelectStep -> _state.update { it.copy(step = intent.step) }
            is DrivebaseBuilderIntent.SelectKind -> when {
                intent.kind !in drivebaseKindsForLeague(_state.value.league) -> _state.update {
                    it.copy(error = "${intent.kind} is not available for this ${it.league.name} project.")
                }
                _state.value.dirty && intent.kind != _state.value.draft.kind -> {
                    _state.update { it.copy(pendingDiscardAction = DrivebaseDiscardAction.CHANGE_KIND, pendingKind = intent.kind) }
                }
                intent.kind != _state.value.draft.kind -> edit(drivebaseForKind(_state.value, intent.kind))
            }
            is DrivebaseBuilderIntent.SelectHardware -> _state.update { it.copy(selectedHardwareId = intent.id) }
            is DrivebaseBuilderIntent.UpdateHardware -> edit(_state.value.draft.copy(
                hardware = _state.value.draft.hardware.map { if (it.id == intent.device.id) intent.device else it }
            ))
            DrivebaseBuilderIntent.UseSimulationCanIds -> useSimulationCanIds()
            is DrivebaseBuilderIntent.AddHardware -> addHardware(intent.role)
            is DrivebaseBuilderIntent.RemoveHardware -> removeHardware(intent.id)
            is DrivebaseBuilderIntent.UpdateGeometry -> edit(_state.value.draft.copy(geometry = intent.geometry))
            is DrivebaseBuilderIntent.UpdateControl -> edit(_state.value.draft.copy(
                supportedControlModes = intent.supported.distinct(),
                defaultControlMode = intent.defaultMode,
                fieldRelativeEnabled = intent.fieldRelative,
            ))
            is DrivebaseBuilderIntent.SetLocalization -> {
                val existing = _state.value.draft.localization
                val updated = when {
                    !intent.enabled -> existing - intent.kind
                    intent.kind == LocalizationKind.VISION_FUSION -> (existing + intent.kind).distinct()
                    else -> (existing.filter { it == LocalizationKind.VISION_FUSION } + intent.kind).distinct()
                }
                edit(_state.value.draft.copy(localization = updated))
            }
            is DrivebaseBuilderIntent.UpdateSafety -> edit(_state.value.draft.copy(safety = intent.safety))
            is DrivebaseBuilderIntent.SetAdvanced -> _state.update { it.copy(advanced = intent.enabled) }
            is DrivebaseBuilderIntent.UpdateLab -> _state.update { it.copy(lab = intent.lab) }
            is DrivebaseBuilderIntent.SetImportPath -> _state.update { it.copy(importPath = intent.path) }
            DrivebaseBuilderIntent.ImportCtre -> importCtre()
            DrivebaseBuilderIntent.ReviewSave -> reviewSave()
            is DrivebaseBuilderIntent.ConfirmSave -> confirmSave(intent.token)
            DrivebaseBuilderIntent.ConfirmDiscard -> confirmDiscard()
            DrivebaseBuilderIntent.CancelDiscard -> _state.update { it.copy(pendingDiscardAction = null, pendingKind = null) }
        }
    }

    private fun requestDiscard(action: DrivebaseDiscardAction) = _state.update { it.copy(pendingDiscardAction = action, pendingKind = null) }

    private fun useSimulationCanIds() {
        val state = _state.value
        if (state.league != League.FRC || state.draft.kind != DrivebaseKind.FRC_CTRE_SWERVE) {
            _state.update { it.copy(error = "Simulation CAN placeholders are available only for an FRC CTRE swerve draft.") }
            return
        }
        val candidate = state.draft.copy(
            hardware = state.draft.hardware.mapIndexed { index, device ->
                device.copy(canId = index + 1, canBus = "rio")
            },
        )
        edit(candidate)
        _state.update {
            it.copy(
                status = "Assigned unique simulation-only CAN IDs 1–${candidate.hardware.size}. Replace every address with the robot's verified CTRE configuration before physical deployment.",
                error = null,
            )
        }
    }

    private fun confirmDiscard() {
        val action = _state.value.pendingDiscardAction
        val kind = _state.value.pendingKind
        _state.update { it.copy(pendingDiscardAction = null, pendingKind = null, dirty = false) }
        when (action) {
            DrivebaseDiscardAction.RELOAD -> load()
            DrivebaseDiscardAction.CHANGE_KIND -> kind?.let { edit(drivebaseForKind(_state.value, it)) }
            null -> Unit
        }
    }

    private fun load() = scope.launch {
        _state.update { it.copy(loading = true, error = null) }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val state = _state.value
                val sessionSnapshot = projectSession?.snapshot(
                    state.projectPath,
                    state.league.targetPlatform(),
                    forceReload = true,
                )
                val saved = sessionSnapshot?.documents?.query?.drivetrains
                    ?.also { require(it.size <= 1) { "This project has multiple drivetrain documents. Choose one explicitly before editing." } }
                    ?.singleOrNull()
                    ?.toUiDrivebase()
                    ?: repository.load(state.projectPath).getOrThrow()
                saved to sessionSnapshot?.revision
            }
        }
        val result = loaded.map { it.first }
        val sessionRevision = loaded.getOrNull()?.second
        result.fold(
            onSuccess = { saved ->
                val draftResult = runCatching {
                    (saved ?: defaultDrivebase(
                        _state.value.projectId,
                        defaultNoCodeDrivebaseKind(_state.value.league),
                    )).withRuntimeRequirements().withCanonicalProjectIdentity(_state.value.projectId)
                }
                val draft = draftResult.getOrElse { failure ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = failure.message ?: "Could not prepare the drivebase runtime contract.",
                        )
                    }
                    return@fold
                }
                val runtimeRepairReady = saved != null && diffDrivebase(saved, draft).isNotEmpty()
                val tuningProfileRepairs = if (saved != null) {
                    withContext(Dispatchers.IO) { repository.tuningProfileRepairIssues(_state.value.projectPath, draft) }
                } else emptyList()
                _state.update {
                    it.copy(
                        saved = saved,
                        projectRevision = sessionRevision,
                        draft = draft,
                        issues = validateDrivebaseForLeague(draft, it.league),
                        loading = false,
                        dirty = runtimeRepairReady || tuningProfileRepairs.isNotEmpty(),
                        tuningProfileRepairIssues = tuningProfileRepairs,
                        status = if (runtimeRepairReady || tuningProfileRepairs.isNotEmpty()) {
                            "ARES prepared missing runtime parameters, hardware wiring, and/or tuning ownership repairs for review. Open Safety & Review to inspect and save them."
                        } else "",
                        error = null,
                        selectedHardwareId = null,
                    )
                }
            },
            onFailure = { failure -> _state.update { it.copy(loading = false, error = failure.message ?: "Could not load the drivebase document.") } }
        )
    }

    private fun edit(candidate: DrivebaseDocument) = _state.update {
        val hasChanges = diffDrivebase(it.saved, candidate).isNotEmpty()
        it.copy(
            draft = candidate,
            issues = validateDrivebaseForLeague(candidate, it.league),
            saveReview = null,
            status = "",
            dirty = hasChanges,
            aiProposal = null,
            aiProposalError = null,
        )
    }

    fun requestAiProposal(studentRequest: String) {
        val request = studentRequest.trim()
        val assistant = designAssistant
        val base = _state.value.draft.canonical
        when {
            request.isBlank() -> _state.update { it.copy(aiProposalError = "Describe the drivebase or change you want first.") }
            assistant == null -> _state.update { it.copy(aiProposalError = "Gemini is not available in this app session.") }
            base == null -> _state.update { it.copy(aiProposalError = "The current drivebase is not a canonical document yet.") }
            else -> {
                val baseHash = DrivetrainDocumentCodec.contentHash(base)
                _state.update { it.copy(aiProposalInProgress = true, aiProposal = null, aiProposalError = null) }
                scope.launch {
                    runCatching { assistant.propose(base, request) }
                        .onSuccess { proposal ->
                            val candidate = proposal.candidate.toUiDrivebase()
                            val review = DrivebaseAiProposalReview(
                                proposal = proposal,
                                candidate = candidate,
                                changes = diffDrivebase(_state.value.draft, candidate),
                                issues = validateDrivebaseForLeague(candidate, _state.value.league),
                                baseContentHash = baseHash,
                            )
                            _state.update { current ->
                                val currentHash = current.draft.canonical?.let(DrivetrainDocumentCodec::contentHash)
                                if (currentHash != baseHash) current.copy(
                                    aiProposalInProgress = false,
                                    aiProposalError = "The drivebase changed while Gemini was working. Request a fresh proposal.",
                                ) else current.copy(aiProposalInProgress = false, aiProposal = review)
                            }
                        }
                        .onFailure { error -> _state.update {
                            it.copy(aiProposalInProgress = false, aiProposalError = error.message ?: "Gemini could not create a drivebase proposal.")
                        } }
                }
            }
        }
    }

    fun dismissAiProposal() = _state.update { it.copy(aiProposal = null, aiProposalError = null) }

    fun applyAiProposal() = _state.update { current ->
        val review = current.aiProposal ?: return@update current
        val currentHash = current.draft.canonical?.let(DrivetrainDocumentCodec::contentHash)
        when {
            !review.canApply -> current.copy(aiProposalError = "Gemini's proposal has blocking validation errors.")
            currentHash != review.baseContentHash -> current.copy(aiProposal = null, aiProposalError = "The drivebase changed. Request a fresh proposal.")
            else -> current.copy(
                draft = review.candidate,
                issues = review.issues,
                dirty = true,
                saveReview = null,
                aiProposal = null,
                aiProposalError = null,
                status = "Applied Gemini's proposal locally. Review every step before saving.",
            )
        }
    }

    private fun addHardware(role: DriveHardwareRole) {
        val existing = _state.value.draft.hardware
        val next = generateSequence(1) { it + 1 }.map { "drive.user-$it" }.first { id -> existing.none { it.id == id } }
        val leader = when (role) {
            DriveHardwareRole.LEFT_FOLLOWER -> existing.firstOrNull { it.role == DriveHardwareRole.LEFT_LEADER }?.id
            DriveHardwareRole.RIGHT_FOLLOWER -> existing.firstOrNull { it.role == DriveHardwareRole.RIGHT_LEADER }?.id
            else -> null
        }
        val countOfSameRole = existing.count { it.role == role } + 1
        val defaultName = when (role) {
            DriveHardwareRole.LIMELIGHT -> if (countOfSameRole == 1) "Limelight Vision Camera" else "Limelight $countOfSameRole"
            DriveHardwareRole.ODOMETRY -> if (countOfSameRole == 1) "goBILDA Pinpoint" else "Odometry Pod $countOfSameRole"
            DriveHardwareRole.GYRO -> if (countOfSameRole == 1) "Control Hub IMU" else "IMU / Gyro $countOfSameRole"
            DriveHardwareRole.DISTANCE_SENSOR -> if (countOfSameRole == 1) "Distance Sensor" else "Distance Sensor $countOfSameRole"
            else -> "New ${role.name.lowercase().replace('_', ' ')}"
        }
        val defaultHwName = when (role) {
            DriveHardwareRole.LIMELIGHT -> if (countOfSameRole == 1) "limelight" else "limelight$countOfSameRole"
            DriveHardwareRole.ODOMETRY -> if (countOfSameRole == 1) "pinpoint" else "pinpoint$countOfSameRole"
            DriveHardwareRole.GYRO -> if (countOfSameRole == 1) "imu" else "imu$countOfSameRole"
            DriveHardwareRole.DISTANCE_SENSOR -> if (countOfSameRole == 1) "distance" else "distance$countOfSameRole"
            else -> ""
        }
        val defaultX = when (role) {
            DriveHardwareRole.LIMELIGHT -> 0.15
            DriveHardwareRole.DISTANCE_SENSOR -> 0.18
            else -> 0.0
        }
        val defaultZ = when (role) {
            DriveHardwareRole.LIMELIGHT -> 0.20
            DriveHardwareRole.DISTANCE_SENSOR -> 0.10
            DriveHardwareRole.ODOMETRY -> 0.02
            else -> 0.0
        }
        val device = DriveHardwareDeclaration(
            id = next,
            displayName = defaultName,
            role = role,
            hardwareName = defaultHwName,
            leaderId = leader,
            xMeters = defaultX,
            yMeters = 0.0,
            zMeters = defaultZ,
            pitchDegrees = 0.0,
            yawDegrees = 0.0,
            rollDegrees = 0.0,
        )
        edit(_state.value.draft.copy(hardware = existing + device))
        _state.update { it.copy(selectedHardwareId = next) }
    }

    private fun removeHardware(id: String) {
        val remaining = _state.value.draft.hardware.filterNot { it.id == id }.map { if (it.leaderId == id) it.copy(leaderId = null) else it }
        edit(_state.value.draft.copy(hardware = remaining))
        _state.update { it.copy(selectedHardwareId = null) }
    }

    private fun importCtre() = scope.launch {
        val state = _state.value
        if (state.league != League.FRC) {
            _state.update { it.copy(error = "CTRE swerve import is available only in an FRC project.") }
            return@launch
        }
        val result = withContext(Dispatchers.IO) { repository.importCtreTunerConstants(File(state.importPath)) }
        result.fold(onSuccess = { imported ->
            val projectRoot = File(state.projectPath).canonicalFile.toPath()
            val source = File(imported.sourcePath).canonicalFile.toPath()
            if (!source.startsWith(projectRoot)) {
                _state.update { it.copy(error = "TunerConstants.java must be inside the selected project so provenance remains project-relative.") }
                return@fold
            }
            val relativeSource = projectRoot.relativize(source).toString().replace('\\', '/')
            val calibration = DriveCalibrationRecord(
                id = "ctre-${imported.sourceHash.take(12)}",
                source = CalibrationSource.CTRE_TUNER_IMPORT,
                sourcePath = relativeSource,
                sourceHash = imported.sourceHash,
                notes = "Read-only CTRE TunerConstants import; review every value before saving.",
                values = imported.values
            )
            val base = defaultDrivebase(state.projectId, DrivebaseKind.FRC_CTRE_SWERVE).canonical!!
            val cornerByRole = mapOf(
                DriveHardwareRole.FRONT_LEFT_DRIVE to "front-left", DriveHardwareRole.FRONT_LEFT_STEER to "front-left", DriveHardwareRole.FRONT_LEFT_ENCODER to "front-left",
                DriveHardwareRole.FRONT_RIGHT_DRIVE to "front-right", DriveHardwareRole.FRONT_RIGHT_STEER to "front-right", DriveHardwareRole.FRONT_RIGHT_ENCODER to "front-right",
                DriveHardwareRole.REAR_LEFT_DRIVE to "rear-left", DriveHardwareRole.REAR_LEFT_STEER to "rear-left", DriveHardwareRole.REAR_LEFT_ENCODER to "rear-left",
                DriveHardwareRole.REAR_RIGHT_DRIVE to "rear-right", DriveHardwareRole.REAR_RIGHT_STEER to "rear-right", DriveHardwareRole.REAR_RIGHT_ENCODER to "rear-right"
            )
            val components = imported.hardware.map { device ->
                if (device.role == DriveHardwareRole.GYRO) {
                    return@map com.areslib.drivetrain.DrivetrainComponentDocument(
                        uid = "drive.gyro", displayName = device.displayName,
                        role = com.areslib.drivetrain.DrivetrainComponentRole.GYRO,
                        hardwareId = device.canId.toString(), controllerModel = "Pigeon2"
                    )
                }
                val corner = cornerByRole.getValue(device.role)
                val sharedRole = when {
                    device.role.name.endsWith("DRIVE") -> com.areslib.drivetrain.DrivetrainComponentRole.DRIVE_MOTOR
                    device.role.name.endsWith("STEER") -> com.areslib.drivetrain.DrivetrainComponentRole.STEER_MOTOR
                    else -> com.areslib.drivetrain.DrivetrainComponentRole.ABSOLUTE_ENCODER
                }
                val kindPrefix = when (sharedRole) {
                    com.areslib.drivetrain.DrivetrainComponentRole.DRIVE_MOTOR -> "drive"
                    com.areslib.drivetrain.DrivetrainComponentRole.STEER_MOTOR -> "steer"
                    else -> "encoder"
                }
                com.areslib.drivetrain.DrivetrainComponentDocument(
                    uid = "$kindPrefix.$corner", displayName = device.displayName, role = sharedRole,
                    hardwareId = device.canId.toString(), moduleUid = "module.$corner",
                    controllerModel = if (sharedRole == com.areslib.drivetrain.DrivetrainComponentRole.ABSOLUTE_ENCODER) null else "TalonFX",
                    encoderModel = if (sharedRole == com.areslib.drivetrain.DrivetrainComponentRole.ABSOLUTE_ENCODER) "CANcoder" else null,
                    currentMeasurementRequired = sharedRole == com.areslib.drivetrain.DrivetrainComponentRole.DRIVE_MOTOR,
                    currentMeasurementAvailable = sharedRole == com.areslib.drivetrain.DrivetrainComponentRole.DRIVE_MOTOR,
                    // CTRE's slip-current characterization is evidence, not proof that the motor
                    // controller has an enforced current limit. Keep it in calibration provenance.
                    currentLimitAmps = null,
                    xMeters = imported.values["${corner.substringBefore('-')}${corner.substringAfter('-').replaceFirstChar(Char::uppercase)}X"],
                    yMeters = imported.values["${corner.substringBefore('-')}${corner.substringAfter('-').replaceFirstChar(Char::uppercase)}Y"],
                    inverted = device.inverted
                )
            }
            val modules = listOf("front-left", "front-right", "rear-left", "rear-right").map { corner ->
                val compact = corner.substringBefore('-') + corner.substringAfter('-').replaceFirstChar(Char::uppercase)
                com.areslib.drivetrain.DrivetrainModuleDocument(
                    "module.$corner", corner.replace('-', ' ').replaceFirstChar(Char::uppercase),
                    listOf("drive.$corner", "steer.$corner", "encoder.$corner"),
                    imported.values["${compact}X"] ?: base.modules.first { it.uid == "module.$corner" }.xMeters,
                    imported.values["${compact}Y"] ?: base.modules.first { it.uid == "module.$corner" }.yMeters
                )
            }
            val canonical = base.copy(
                components = components, modules = modules,
                geometry = base.geometry.copy(
                    wheelDiameterMeters = imported.geometry!!.wheelRadiusMeters * 2.0,
                    trackWidthMeters = imported.geometry.trackWidthMeters,
                    wheelBaseMeters = imported.geometry.wheelBaseMeters,
                    driveGearRatio = requireNotNull(imported.values["driveGearRatio"]),
                    steerGearRatio = requireNotNull(imported.values["steerGearRatio"]),
                    maxLinearSpeedMetersPerSecond = requireNotNull(imported.values["speedAt12Volts"])
                ),
                localization = base.localization.copy(primaryOdometry = base.localization.primaryOdometry.copy(componentUids = components.map { it.uid }), headingSourceUid = components.first { it.role == com.areslib.drivetrain.DrivetrainComponentRole.GYRO }.uid),
                ctreImport = com.areslib.drivetrain.CtreSwerveImportDocument(
                    relativeSource, imported.sourceHash, "CTRE Tuner", "imported", "frc.robot.generated.TunerConstants",
                    imported.hardware.mapNotNull { it.canBus }.distinct().singleOrNull()
                        ?: error("CTRE CAN bus was not recognized or was inconsistent; import is fail-closed.")
                ),
                calibrationProvenance = listOf(com.areslib.drivetrain.CalibrationProvenanceDocument("calibration.ctre-import", com.areslib.drivetrain.CalibrationProvenanceKind.VENDOR_GENERATED, emptyList(), relativeSource, imported.sourceHash, calibration.notes))
            )
            val candidate = canonical.toUiDrivebase()
            _state.update { it.copy(draft = candidate, issues = validateDrivebaseForLeague(candidate, it.league), importWarnings = imported.warnings, status = "Imported a read-only CTRE snapshot. Vendor code was not changed.", saveReview = null, dirty = true) }
        }, onFailure = { failure -> _state.update { it.copy(error = failure.message ?: "CTRE import failed.") } })
    }

    private fun reviewSave() {
        val state = _state.value
        val normalized = runCatching { state.draft.withRuntimeRequirements() }.getOrElse { failure ->
            _state.update { it.copy(error = failure.message ?: "Could not prepare the runtime contract for review.") }
            return
        }
        val issues = validateDrivebaseForLeague(normalized, state.league)
        if (issues.any { it.severity == DrivebaseIssueSeverity.ERROR }) {
            _state.update { it.copy(issues = issues, error = "Fix the blocking checks before reviewing the save.") }
            return
        }
        val changes = diffDrivebase(state.saved, normalized).toMutableList().apply {
            if (state.tuningProfileRepairIssues.isNotEmpty()) {
                add(
                    DrivebaseChange(
                        "tuningProfiles",
                        state.tuningProfileRepairIssues.joinToString(" "),
                        "Reconcile checked-in profiles to this drivebase and remove only obsolete assignments; preserve every prior file in .ares/history.",
                    ),
                )
            }
        }
        if (changes.isEmpty()) {
            _state.update {
                it.copy(
                    step = DrivebaseBuilderStep.REVIEW,
                    saveReview = null,
                    dirty = false,
                    status = "This drivebase already matches the saved canonical document.",
                    error = null,
                )
            }
            return
        }
        val baseHash = state.saved?.canonical?.let(com.areslib.drivetrain.DrivetrainDocumentCodec::contentHash)
        val token = reviewToken(baseHash, changes)
        _state.update {
            it.copy(
                draft = normalized,
                issues = issues,
                step = DrivebaseBuilderStep.REVIEW,
                saveReview = DrivebaseSaveReview(changes, token, baseHash),
                error = null,
                dirty = true,
            )
        }
    }

    private fun confirmSave(token: String) = scope.launch {
        val state = _state.value
        val review = state.saveReview
        val currentHash = state.saved?.canonical?.let(com.areslib.drivetrain.DrivetrainDocumentCodec::contentHash)
        if (review == null || review.confirmationToken != token || review.baseContentHash != currentHash) {
            _state.update { it.copy(error = "The reviewed drivebase changed. Review a fresh diff before saving.") }
            return@launch
        }
        runCatching {
            withContext(Dispatchers.IO) {
                val session = projectSession
                val revision = state.projectRevision
                if (session != null && revision != null) {
                    when (val result = session.saveDrivebase(revision, currentHash, state.draft)) {
                        is ProjectSessionMutationResult.Applied -> result.value to result.snapshot.revision
                        is ProjectSessionMutationResult.Stale -> error("The project changed after this drivebase loaded. Reload before saving.")
                        is ProjectSessionMutationResult.Conflict -> error(result.message)
                        is ProjectSessionMutationResult.Failed -> error(result.message)
                    }
                } else {
                    repository.saveReviewed(state.projectPath, currentHash, state.draft) to null
                }
            }
        }.fold(
            onSuccess = { (saved, revision) ->
                _state.update { it.copy(saved = saved, draft = saved, projectRevision = revision ?: it.projectRevision, saveReview = null, tuningProfileRepairIssues = emptyList(), status = "Saved reviewed drivebase ${saved.canonical?.let(com.areslib.drivetrain.DrivetrainDocumentCodec::contentHash)?.take(12)}. No robot or vendor source was written.", error = null, dirty = false) }
                scope.launch {
                    runCatching {
                        checkpointRecorder.checkpoint(
                            state.projectPath,
                            "Saved ${saved.displayName} drivebase",
                            setOf(".ares/drivetrains", ".ares/history/drivetrains", ".ares/tuning"),
                        )
                    }.onFailure { failure ->
                        _state.update { it.copy(status = "Drivebase saved, but automatic Project History checkpoint failed: ${failure.message}") }
                    }
                }
            },
            onFailure = { failure -> _state.update { it.copy(error = failure.message ?: "Could not save the drivebase." ) } }
        )
    }

}

private fun League.targetPlatform() = when (this) {
    League.FTC -> com.areslib.controls.ControllerInputPlatform.FTC
    League.FRC -> com.areslib.controls.ControllerInputPlatform.FRC
}

internal fun canonicalRuntimeProjectUid(projectPath: String, fallback: String, league: League): String {
    val identity = File(projectPath, ".ares/project.json")
    return runCatching { AresProjectMetadataCodec.decode(identity.readText()) }
        .getOrNull()
        ?.identity
        ?.let { config ->
            listOf(
                "team${config.teamId.filter(Char::isDigit)}",
                league.name.lowercase(),
                uidSegment("season${config.seasonId}", "seasonunknown"),
                uidSegment(config.robotId, "robot"),
            ).joinToString(".")
        }
        ?: fallback.lowercase().replace(Regex("[^a-z0-9]+"), ".").trim('.').ifBlank { "robot.project" }
}

private fun uidSegment(value: String, fallback: String): String =
    value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { fallback }

private fun drivebaseForKind(state: DrivebaseBuilderState, kind: DrivebaseKind): DrivebaseDocument {
    val replacement = defaultDrivebase(state.projectId, kind)
    val replacementCanonical = replacement.canonical ?: return replacement
    val currentCanonical = state.draft.canonical ?: return replacement
    val reboundUid = currentCanonical.uid
    return replacement.copy(
        canonical = replacementCanonical.copy(
            uid = reboundUid,
            canonicalProfileUid = currentCanonical.canonicalProfileUid,
            parameters = replacementCanonical.parameters.map { parameter ->
                if (parameter.componentUid == replacementCanonical.uid) parameter.copy(componentUid = reboundUid) else parameter
            },
        ),
    )
}

internal fun DrivebaseDocument.withCanonicalProjectIdentity(projectId: String): DrivebaseDocument {
    val canonicalDocument = canonical ?: return copy(projectId = projectId)
    val profileId = canonicalDocument.canonicalProfileUid.substringAfter(".profile.", "competition")
    val expectedProfileUid = "$projectId.profile.$profileId"
    return if (this.projectId == projectId && canonicalDocument.canonicalProfileUid == expectedProfileUid) {
        this
    } else {
        copy(
            projectId = projectId,
            canonical = canonicalDocument.copy(canonicalProfileUid = expectedProfileUid),
        )
    }
}

data class GeometryLabResult(
    val turningRadiusMeters: Double?,
    val trackCircleDiameterMeters: Double?,
    val maxLinearSpeedMps: Double? = null,
    val maxAngularSpeedRadPerSec: Double? = null,
    val explanation: String
)
enum class LocalizationFailureScenario { ALL_HEALTHY, PRIMARY_STALE, HEADING_INVALID, VISION_REJECTED }
data class LocalizationLabResult(val canDriveClosedLoop: Boolean, val usesVisionCorrection: Boolean, val message: String)

fun evaluateDriveLab(
    kind: DrivebaseKind,
    input: DriveLabState,
    geometry: DriveGeometry = DriveGeometry(),
    hardware: List<DriveHardwareDeclaration> = emptyList()
): DriveLabResult {
    val heading = input.headingDegrees * PI / 180.0
    val forward = if (input.fieldRelative) input.forward * cos(heading) + input.strafe * sin(heading) else input.forward
    val strafe = if (input.fieldRelative) -input.forward * sin(heading) + input.strafe * cos(heading) else input.strafe
    var moduleAngles = emptyMap<String, Double>()
    val raw = when (kind) {
        DrivebaseKind.FTC_MECANUM, DrivebaseKind.CUSTOM -> linkedMapOf(
            "frontLeft" to forward + strafe + input.rotate,
            "frontRight" to forward - strafe - input.rotate,
            "rearLeft" to forward - strafe + input.rotate,
            "rearRight" to forward + strafe - input.rotate
        )
        DrivebaseKind.FRC_CTRE_SWERVE -> {
            val positions = linkedMapOf(
                "frontLeft" to (geometry.wheelBaseMeters / 2.0 to geometry.trackWidthMeters / 2.0),
                "frontRight" to (geometry.wheelBaseMeters / 2.0 to -geometry.trackWidthMeters / 2.0),
                "rearLeft" to (-geometry.wheelBaseMeters / 2.0 to geometry.trackWidthMeters / 2.0),
                "rearRight" to (-geometry.wheelBaseMeters / 2.0 to -geometry.trackWidthMeters / 2.0)
            )
            val vectors = positions.mapValues { (_, position) ->
                val (x, y) = position
                val moduleForward = forward - input.rotate * y
                val moduleStrafe = strafe + input.rotate * x
                moduleForward to moduleStrafe
            }
            moduleAngles = vectors.mapValues { (_, vector) -> Math.toDegrees(kotlin.math.atan2(vector.second, vector.first)) }
            vectors.mapValues { (_, vector) -> kotlin.math.hypot(vector.first, vector.second) }.toMap(LinkedHashMap())
        }
        DrivebaseKind.DIFFERENTIAL -> linkedMapOf("left" to forward + input.rotate, "right" to forward - input.rotate)
    }
    val inversionRoles = mapOf(
        "frontLeft" to setOf(DriveHardwareRole.FRONT_LEFT, DriveHardwareRole.FRONT_LEFT_DRIVE),
        "frontRight" to setOf(DriveHardwareRole.FRONT_RIGHT, DriveHardwareRole.FRONT_RIGHT_DRIVE),
        "rearLeft" to setOf(DriveHardwareRole.REAR_LEFT, DriveHardwareRole.REAR_LEFT_DRIVE),
        "rearRight" to setOf(DriveHardwareRole.REAR_RIGHT, DriveHardwareRole.REAR_RIGHT_DRIVE),
        "left" to setOf(DriveHardwareRole.LEFT_LEADER),
        "right" to setOf(DriveHardwareRole.RIGHT_LEADER)
    )
    val afterInversion = raw.mapValues { (name, output) ->
        val inverted = hardware.any { it.inverted && it.role in inversionRoles[name].orEmpty() }
        if (inverted) -output else output
    }.toMutableMap()
    hardware.filter { it.role in setOf(DriveHardwareRole.LEFT_FOLLOWER, DriveHardwareRole.RIGHT_FOLLOWER) }.forEach { follower ->
        val side = if (follower.role == DriveHardwareRole.LEFT_FOLLOWER) "left" else "right"
        val leaderOutput = afterInversion[side] ?: 0.0
        afterInversion["follower:${follower.id}"] = if (follower.inverted) -leaderOutput else leaderOutput
    }
    val scale = afterInversion.values.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1.0) ?: 1.0
    return DriveLabResult(
        robotForward = forward,
        robotStrafe = strafe,
        wheelOutputs = afterInversion.mapValues { it.value / scale },
        moduleAnglesDegrees = moduleAngles,
        explanation = "Simulation only: text includes each declared drive inversion after field-to-robot transformation. This does not connect to or move hardware."
    )
}

fun evaluateGeometryLab(
    geometry: DriveGeometry,
    linearCommand: Double,
    angularCommand: Double,
    configuredMaxLinearSpeedMps: Double,
    useCornerModuleRadius: Boolean
): GeometryLabResult {
    val radius = if (kotlin.math.abs(angularCommand) < 1e-9) null else kotlin.math.abs(linearCommand / angularCommand)
    val maxLinearSpeed = configuredMaxLinearSpeedMps.takeIf { it.isFinite() && it > 0.0 }
    val rotationalRadius = if (useCornerModuleRadius) {
        hypot(geometry.trackWidthMeters / 2.0, geometry.wheelBaseMeters / 2.0)
    } else {
        geometry.trackWidthMeters / 2.0
    }
    val maxAngularSpeed = maxLinearSpeed?.takeIf { rotationalRadius > 0.005 }?.div(rotationalRadius)
    return GeometryLabResult(
        turningRadiusMeters = radius,
        trackCircleDiameterMeters = radius?.let { 2.0 * (it + geometry.trackWidthMeters / 2.0) },
        maxLinearSpeedMps = maxLinearSpeed,
        maxAngularSpeedRadPerSec = maxAngularSpeed,
        explanation = if (radius == null) {
            "Zero turn command predicts a straight path. The configured safety limit is ${maxLinearSpeed?.let { "%.2f m/s".format(it) } ?: "not valid"}."
        } else {
            "The chassis center follows a ${"%.2f".format(radius)} m radius. The configured linear limit implies ${maxAngularSpeed?.let { "%.1f rad/s".format(it) } ?: "no valid angular estimate"}; the outside wheel/module travels a larger circle."
        }
    )
}

fun evaluateLocalizationFailure(scenario: LocalizationFailureScenario): LocalizationLabResult = when (scenario) {
    LocalizationFailureScenario.ALL_HEALTHY -> LocalizationLabResult(true, true, "Primary odometry and heading are fresh. Valid vision may correct drift.")
    LocalizationFailureScenario.PRIMARY_STALE -> LocalizationLabResult(false, false, "Primary odometry is stale, so closed-loop driving fails closed. Vision is not a substitute for fresh primary motion feedback.")
    LocalizationFailureScenario.HEADING_INVALID -> LocalizationLabResult(false, false, "Heading is invalid, so field-relative and closed-loop rotation are blocked.")
    LocalizationFailureScenario.VISION_REJECTED -> LocalizationLabResult(true, false, "Primary odometry and heading remain usable. Rejected vision is ignored rather than snapping the pose.")
}

private fun reviewToken(contentHash: String?, changes: List<DrivebaseChange>): String {
    val content = "$contentHash|" + changes.joinToString("|") { "${it.path}:${it.before}->${it.after}" }
    return MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}
