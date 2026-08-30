package com.ares.analytics.service.drivebase

import com.ares.analytics.shared.models.League
import com.areslib.drivetrain.*

enum class DrivebaseKind { FTC_MECANUM, FRC_CTRE_SWERVE, DIFFERENTIAL, CUSTOM }

/** Whether the selected season shell can execute this drivebase without team-written runtime code. */
enum class DrivebaseRuntimeSupport { NO_CODE_RUNNABLE, CODE_REQUIRED, UNAVAILABLE_FOR_LEAGUE }

fun DrivebaseKind.runtimeSupport(league: League): DrivebaseRuntimeSupport = when (this) {
    DrivebaseKind.FTC_MECANUM -> if (league == League.FTC) {
        DrivebaseRuntimeSupport.NO_CODE_RUNNABLE
    } else {
        DrivebaseRuntimeSupport.UNAVAILABLE_FOR_LEAGUE
    }
    DrivebaseKind.FRC_CTRE_SWERVE -> if (league == League.FRC) {
        DrivebaseRuntimeSupport.NO_CODE_RUNNABLE
    } else {
        DrivebaseRuntimeSupport.UNAVAILABLE_FOR_LEAGUE
    }
    DrivebaseKind.DIFFERENTIAL, DrivebaseKind.CUSTOM -> DrivebaseRuntimeSupport.CODE_REQUIRED
}

fun drivebaseKindsForLeague(league: League): List<DrivebaseKind> = when (league) {
    League.FTC -> listOf(DrivebaseKind.FTC_MECANUM, DrivebaseKind.DIFFERENTIAL, DrivebaseKind.CUSTOM)
    League.FRC -> listOf(DrivebaseKind.FRC_CTRE_SWERVE, DrivebaseKind.DIFFERENTIAL, DrivebaseKind.CUSTOM)
}

/** Beginner mode shows only executable no-code choices; a selected advanced draft remains visible. */
fun visibleDrivebaseKinds(
    league: League,
    advanced: Boolean,
    selected: DrivebaseKind,
): List<DrivebaseKind> = drivebaseKindsForLeague(league).filter { kind ->
    advanced || kind == selected || kind.runtimeSupport(league) == DrivebaseRuntimeSupport.NO_CODE_RUNNABLE
}

fun defaultNoCodeDrivebaseKind(league: League): DrivebaseKind = when (league) {
    League.FTC -> DrivebaseKind.FTC_MECANUM
    League.FRC -> DrivebaseKind.FRC_CTRE_SWERVE
}

fun DrivebaseKind.runtimeSupportLabel(league: League): String = when (runtimeSupport(league)) {
    DrivebaseRuntimeSupport.NO_CODE_RUNNABLE -> "NO-CODE RUNNABLE"
    DrivebaseRuntimeSupport.CODE_REQUIRED -> "CODE REQUIRED"
    DrivebaseRuntimeSupport.UNAVAILABLE_FOR_LEAGUE -> "NOT AVAILABLE FOR ${league.name}"
}

enum class DriveHardwareRole {
    FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT,
    LEFT_LEADER, LEFT_FOLLOWER, RIGHT_LEADER, RIGHT_FOLLOWER,
    FRONT_LEFT_DRIVE, FRONT_LEFT_STEER, FRONT_LEFT_ENCODER,
    FRONT_RIGHT_DRIVE, FRONT_RIGHT_STEER, FRONT_RIGHT_ENCODER,
    REAR_LEFT_DRIVE, REAR_LEFT_STEER, REAR_LEFT_ENCODER,
    REAR_RIGHT_DRIVE, REAR_RIGHT_STEER, REAR_RIGHT_ENCODER,
    GYRO, ODOMETRY, LIMELIGHT, DISTANCE_SENSOR, DRIVE_MOTOR, OTHER, CUSTOM
}

enum class LocalizationKind {
    FTC_PINPOINT, WHEEL_ODOMETRY_GYRO, CTRE_POSE_ESTIMATOR, VISION_FUSION, CUSTOM
}

enum class CalibrationSource { MANUAL, SIMULATION, ROBOT_MEASURED, CTRE_TUNER_IMPORT }

data class DriveHardwareDeclaration(
    val id: String,
    val displayName: String,
    val role: DriveHardwareRole,
    val hardwareName: String = "",
    val canId: Int? = null,
    val canBus: String? = null,
    val controllerModel: String? = null,
    val encoderModel: String? = null,
    val currentMeasurementRequired: Boolean = false,
    val currentMeasurementAvailable: Boolean = false,
    val currentLimitAmps: Double? = null,
    val inverted: Boolean = false,
    val required: Boolean = true,
    /** Stable ID of a direct leader. [inverted] independently controls follower direction. */
    val leaderId: String? = null,
    /** Longitudinal physical offset from robot center of rotation in meters (forward is positive). */
    val xMeters: Double? = null,
    /** Lateral physical offset from robot center of rotation in meters (left is positive). */
    val yMeters: Double? = null,
    /** Vertical physical mounting height from ground/robot origin in meters (up is positive). */
    val zMeters: Double? = null,
    /** Camera pitch angle in degrees (up is positive). */
    val pitchDegrees: Double? = null,
    /** Camera yaw angle in degrees (facing forward is 0°, left is +90°, right is -90°, back is 180°). */
    val yawDegrees: Double? = null,
    /** Camera roll angle in degrees. */
    val rollDegrees: Double? = null,
)

data class DriveGeometry(
    val wheelRadiusMeters: Double = 0.048,
    val trackWidthMeters: Double = 0.36,
    val wheelBaseMeters: Double = 0.36
)

data class DriveSafetyDeclaration(
    val safeNeutralRequired: Boolean = true,
    val configurationHealthRequired: Boolean = true,
    val feedbackFreshnessTimeoutMs: Int = 100,
    val maxLinearSpeedMetersPerSecond: Double = 3.0,
    val maxAngularSpeedRadiansPerSecond: Double = 6.0,
    val currentMonitoringRequired: Boolean = true,
    val faultLatchingRequired: Boolean = true,
    val explicitNeutralRecoveryRequired: Boolean = true,
    val enabledNeutralMode: DrivetrainNeutralMode = DrivetrainNeutralMode.BRAKE,
    val disabledPolicy: DisabledDrivePolicy = DisabledDrivePolicy.FORCE_NEUTRAL_BRAKE,
)

data class DriveCalibrationRecord(
    val id: String,
    val source: CalibrationSource,
    val sourcePath: String? = null,
    val sourceHash: String? = null,
    val notes: String,
    val values: Map<String, Double> = emptyMap()
)

data class DrivebaseDocument(
    val schemaVersion: Int = 1,
    /** Stable editor identity; renames never change this or the canonical filename. */
    val documentId: String = "primary-drivebase",
    val projectId: String,
    val kind: DrivebaseKind,
    val displayName: String,
    val hardware: List<DriveHardwareDeclaration>,
    val geometry: DriveGeometry = DriveGeometry(),
    val localization: List<LocalizationKind> = emptyList(),
    val safety: DriveSafetyDeclaration = DriveSafetyDeclaration(),
    val supportedControlModes: List<DrivetrainControlKind> = listOf(
        DrivetrainControlKind.OPEN_LOOP,
        DrivetrainControlKind.CHASSIS_VELOCITY,
    ),
    val defaultControlMode: DrivetrainControlKind = DrivetrainControlKind.OPEN_LOOP,
    val calibrations: List<DriveCalibrationRecord> = emptyList(),
    val fieldRelativeEnabled: Boolean = true,
    val vendorSourceReadOnly: Boolean = true,
    /** Complete shared document retained so UI edits cannot silently drop unrepresented fields. */
    val canonical: DrivetrainDocument? = null
)

enum class DrivebaseIssueSeverity { INFO, WARNING, ERROR }

data class DrivebaseIssue(
    val severity: DrivebaseIssueSeverity,
    val path: String,
    val message: String
)

data class DrivebaseChange(val path: String, val before: String, val after: String)

fun validateDrivebase(document: DrivebaseDocument): List<DrivebaseIssue> = buildList {
    if (document.displayName.isBlank()) add(error("displayName", "Give this drivebase a name."))
    if (document.hardware.isEmpty()) add(error("hardware", "Add the hardware that makes the robot move."))
    val duplicateIds = document.hardware.groupBy { it.id }.filterValues { it.size > 1 }.keys
    duplicateIds.forEach { add(error("hardware.$it", "Hardware IDs must be unique.")) }
    document.hardware.forEach { device ->
        if (device.id.isBlank()) add(error("hardware", "Every device needs a stable ID."))
        if (device.required && device.hardwareName.isBlank() && device.canId == null) {
            add(error("hardware.${device.id}", "${device.displayName} needs a hardware-map name or CAN ID."))
        }
        if (device.canId != null && device.canId !in 0..62) {
            add(error("hardware.${device.id}.canId", "CAN IDs must be between 0 and 62."))
        }
        if (device.currentMeasurementRequired && !device.currentMeasurementAvailable) {
            add(error("hardware.${device.id}.currentMeasurementAvailable", "${device.displayName} requires current monitoring, but the selected adapter cannot provide it."))
        }
        device.currentLimitAmps?.let { limit ->
            if (!limit.isFinite() || limit <= 0.0) add(error("hardware.${device.id}.currentLimitAmps", "Current limit must be finite and positive."))
        }
        val follower = device.role == DriveHardwareRole.LEFT_FOLLOWER || device.role == DriveHardwareRole.RIGHT_FOLLOWER
        if (follower && device.leaderId.isNullOrBlank()) add(error("hardware.${device.id}.leaderId", "Choose a direct leader for ${device.displayName}."))
        if (!follower && device.leaderId != null) add(error("hardware.${device.id}.leaderId", "Only follower motors may name a leader."))
        device.leaderId?.let { leaderId ->
            val leader = document.hardware.firstOrNull { it.id == leaderId }
            if (leader == null) add(error("hardware.${device.id}.leaderId", "Leader '$leaderId' does not exist."))
            else if (leader.role !in setOf(DriveHardwareRole.LEFT_LEADER, DriveHardwareRole.RIGHT_LEADER, DriveHardwareRole.DRIVE_MOTOR)) {
                add(error("hardware.${device.id}.leaderId", "Followers must reference a drive-motor leader."))
            }
        }
    }
    if (document.supportedControlModes.isEmpty()) add(error("control.supported", "Choose at least one drive control mode."))
    if (document.defaultControlMode !in document.supportedControlModes) add(error("control.default", "The default drive mode must also be enabled."))
    with(document.geometry) {
        if (wheelRadiusMeters !in 0.01..0.25) add(error("geometry.wheelRadiusMeters", "Wheel radius must be 1–25 cm."))
        if (trackWidthMeters !in 0.1..2.0) add(error("geometry.trackWidthMeters", "Track width must be 0.1–2.0 m."))
        if (wheelBaseMeters !in 0.1..2.0) add(error("geometry.wheelBaseMeters", "Wheelbase must be 0.1–2.0 m."))
    }
    val primaryLocalization = document.localization.filter { it != LocalizationKind.VISION_FUSION }
    if (primaryLocalization.size != 1) add(error("localization", "Choose exactly one primary odometry source. Vision may be added only as fusion."))
    primaryLocalization.singleOrNull()?.let { source ->
        val compatible = when (document.kind) {
            DrivebaseKind.FTC_MECANUM -> source in setOf(LocalizationKind.FTC_PINPOINT, LocalizationKind.WHEEL_ODOMETRY_GYRO, LocalizationKind.CUSTOM)
            DrivebaseKind.FRC_CTRE_SWERVE -> source in setOf(LocalizationKind.CTRE_POSE_ESTIMATOR, LocalizationKind.WHEEL_ODOMETRY_GYRO, LocalizationKind.CUSTOM)
            DrivebaseKind.DIFFERENTIAL -> source in setOf(LocalizationKind.WHEEL_ODOMETRY_GYRO, LocalizationKind.CUSTOM)
            DrivebaseKind.CUSTOM -> true
        }
        if (!compatible) add(error("localization", "$source is not compatible with ${document.kind}. Choose a matching primary source."))
    }
    if (!document.safety.safeNeutralRequired) add(error("safety.safeNeutralRequired", "Drive outputs must neutralize when disabled, stopped, faulted, or closed."))
    if (!document.safety.configurationHealthRequired) add(error("safety.configurationHealthRequired", "Nonzero drive output must require healthy configuration."))
    if (!document.safety.explicitNeutralRecoveryRequired) add(error("safety.explicitNeutralRecoveryRequired", "Fault recovery must prove a successful neutral write before motion resumes."))
    if (document.safety.feedbackFreshnessTimeoutMs !in 20..1_000) add(error("safety.feedbackFreshnessTimeoutMs", "Feedback timeout must be 20–1000 ms."))
    if (document.kind == DrivebaseKind.FRC_CTRE_SWERVE && document.calibrations.none { it.source == CalibrationSource.CTRE_TUNER_IMPORT }) {
        add(warning("calibrations", "Import and validate CTRE TunerConstants before deployment. ARES never overwrites that vendor file."))
    }
    runCatching { document.toCanonicalDrivebase() }.fold(
        onSuccess = { canonical ->
            validateDrivetrainDocument(canonical).forEach { issue -> add(error("canonical.${issue.path}", issue.message)) }
        },
        onFailure = { failure -> add(error("canonical", failure.message ?: "Could not adapt the drivebase to the shared canonical contract.")) }
    )
}

/** Adds season-runtime truth to the schema-level checks used by the no-code builder. */
fun validateDrivebaseForLeague(document: DrivebaseDocument, league: League): List<DrivebaseIssue> =
    validateDrivebase(document) + when (document.kind.runtimeSupport(league)) {
        DrivebaseRuntimeSupport.NO_CODE_RUNNABLE -> emptyList()
        DrivebaseRuntimeSupport.CODE_REQUIRED -> listOf(
            DrivebaseIssue(
                DrivebaseIssueSeverity.ERROR,
                "runtime",
                "${document.kind.runtimeSupportLabel(league)}: this architecture needs a team-written ${league.name} adapter and lifecycle wiring. The no-code builder will not save it as runnable.",
            )
        )
        DrivebaseRuntimeSupport.UNAVAILABLE_FOR_LEAGUE -> listOf(
            DrivebaseIssue(
                DrivebaseIssueSeverity.ERROR,
                "runtime",
                "${document.kind} belongs to a different competition platform and cannot run in this ${league.name} project.",
            )
        )
    }

fun diffDrivebase(before: DrivebaseDocument?, after: DrivebaseDocument): List<DrivebaseChange> = buildList {
    if (before == null) {
        add(DrivebaseChange("document", "Not configured", "Create ${after.displayName}"))
        return@buildList
    }
    fun change(path: String, old: Any?, new: Any?) {
        if (old != new) add(DrivebaseChange(path, old.toString(), new.toString()))
    }
    change("documentId", before.documentId, after.documentId)
    change("projectId", before.projectId, after.projectId)
    change("canonicalProfileUid", before.canonical?.canonicalProfileUid, after.canonical?.canonicalProfileUid)
    change("kind", before.kind, after.kind)
    change("displayName", before.displayName, after.displayName)
    change("geometry", before.geometry, after.geometry)
    change("localization", before.localization, after.localization)
    change("safety", before.safety, after.safety)
    change("supportedControlModes", before.supportedControlModes, after.supportedControlModes)
    change("defaultControlMode", before.defaultControlMode, after.defaultControlMode)
    change("fieldRelativeEnabled", before.fieldRelativeEnabled, after.fieldRelativeEnabled)
    change(
        "runtimeParameters",
        before.canonical?.parameters?.associate { it.key to it.defaultValue },
        after.canonical?.parameters?.associate { it.key to it.defaultValue },
    )
    (before.hardware.associateBy { it.id }.keys + after.hardware.associateBy { it.id }.keys).sorted().forEach { id ->
        change("hardware.$id", before.hardware.firstOrNull { it.id == id }, after.hardware.firstOrNull { it.id == id })
    }
    change("calibrations", before.calibrations, after.calibrations)
}

/** Wheel or module drive devices in physical front-left, front-right, rear-left, rear-right order. */
fun DrivebaseDocument.cornerDriveHardware(): List<DriveHardwareDeclaration?> {
    val rolesByCorner = listOf(
        setOf(DriveHardwareRole.FRONT_LEFT, DriveHardwareRole.FRONT_LEFT_DRIVE),
        setOf(DriveHardwareRole.FRONT_RIGHT, DriveHardwareRole.FRONT_RIGHT_DRIVE),
        setOf(DriveHardwareRole.REAR_LEFT, DriveHardwareRole.REAR_LEFT_DRIVE),
        setOf(DriveHardwareRole.REAR_RIGHT, DriveHardwareRole.REAR_RIGHT_DRIVE),
    )
    return rolesByCorner.map { roles -> hardware.firstOrNull { it.role in roles } }
}

/** Lossless UI projection: all canonical fields survive in [DrivebaseDocument.canonical]. */
fun DrivetrainDocument.toUiDrivebase(): DrivebaseDocument = DrivebaseDocument(
    schemaVersion = schemaVersion,
    documentId = uid,
    projectId = canonicalProfileUid.substringBefore(".profile.", canonicalProfileUid),
    kind = when (kind) {
        DrivetrainKind.FTC_MECANUM -> DrivebaseKind.FTC_MECANUM
        DrivetrainKind.FRC_CTRE_SWERVE -> DrivebaseKind.FRC_CTRE_SWERVE
        DrivetrainKind.DIFFERENTIAL -> DrivebaseKind.DIFFERENTIAL
        DrivetrainKind.ADVANCED_CUSTOM -> DrivebaseKind.CUSTOM
    },
    displayName = displayName,
    hardware = components.map { component ->
        DriveHardwareDeclaration(
            id = component.uid,
            displayName = component.displayName,
            role = component.toUiRole(kind),
            hardwareName = component.hardwareId,
            canId = component.hardwareId.toIntOrNull(),
            canBus = ctreImport?.canBusName,
            controllerModel = component.controllerModel,
            encoderModel = component.encoderModel,
            currentMeasurementRequired = component.currentMeasurementRequired,
            currentMeasurementAvailable = component.currentMeasurementAvailable,
            currentLimitAmps = component.currentLimitAmps,
            inverted = component.inverted,
            required = component.required,
            leaderId = component.leaderUid,
            xMeters = component.xMeters,
            yMeters = component.yMeters,
            zMeters = component.zMeters,
            pitchDegrees = component.pitchDegrees,
            yawDegrees = component.yawDegrees,
            rollDegrees = component.rollDegrees,
        )
    },
    geometry = DriveGeometry(
        wheelRadiusMeters = geometry.wheelDiameterMeters / 2.0,
        trackWidthMeters = geometry.trackWidthMeters,
        wheelBaseMeters = geometry.wheelBaseMeters
    ),
    localization = (listOf(localization.primaryOdometry) + localization.visionFusion).map { source ->
        when (source.source) {
            LocalizationSourceKind.PINPOINT -> LocalizationKind.FTC_PINPOINT
            LocalizationSourceKind.WHEEL_ENCODERS_IMU -> LocalizationKind.WHEEL_ODOMETRY_GYRO
            LocalizationSourceKind.CTRE_VENDOR -> LocalizationKind.CTRE_POSE_ESTIMATOR
            LocalizationSourceKind.EXTERNAL -> LocalizationKind.VISION_FUSION
            LocalizationSourceKind.CUSTOM -> LocalizationKind.CUSTOM
        }
    },
    safety = DriveSafetyDeclaration(
        safeNeutralRequired = safety.safeNeutralRequired,
        configurationHealthRequired = safety.configurationHealthRequired,
        feedbackFreshnessTimeoutMs = safety.staleFeedbackTimeoutMs.toInt(),
        maxLinearSpeedMetersPerSecond = geometry.maxLinearSpeedMetersPerSecond,
        maxAngularSpeedRadiansPerSecond = geometry.maxAngularSpeedRadiansPerSecond,
        currentMonitoringRequired = safety.currentValidityRequired,
        faultLatchingRequired = safety.faultLatchingRequired,
        explicitNeutralRecoveryRequired = safety.explicitNeutralRecoveryRequired,
        enabledNeutralMode = safety.enabledNeutralMode,
        disabledPolicy = safety.disabledPolicy,
    ),
    supportedControlModes = control.supported,
    defaultControlMode = control.defaultControl,
    calibrations = calibrationProvenance.map { provenance ->
        DriveCalibrationRecord(
            id = provenance.uid,
            source = when (provenance.kind) {
                CalibrationProvenanceKind.MEASURED, CalibrationProvenanceKind.REVIEWED_MANUAL -> CalibrationSource.MANUAL
                CalibrationProvenanceKind.SYSID -> CalibrationSource.ROBOT_MEASURED
                CalibrationProvenanceKind.VENDOR_GENERATED, CalibrationProvenanceKind.MANUFACTURER -> CalibrationSource.CTRE_TUNER_IMPORT
            },
            sourcePath = provenance.evidencePath,
            sourceHash = provenance.evidenceSha256,
            notes = provenance.notes
        )
    },
    fieldRelativeEnabled = control.fieldCentric,
    vendorSourceReadOnly = ctreImport?.ownership == VendorSourceOwnership.READ_ONLY_VENDOR || ctreImport == null,
    canonical = this
)

fun DrivebaseDocument.toCanonicalDrivebase(): DrivetrainDocument {
    val base = canonical ?: canonicalTemplate(projectId, kind)
    val originalUi = canonical?.toUiDrivebase()
    val baseByUid = base.components.associateBy { it.uid }
    val components = hardware.map { edit ->
        val existing = baseByUid[edit.id]
        val role = edit.role.toCanonicalRole()
        val corner = edit.role.name.substringBeforeLast('_').lowercase().replace('_', '-')
        val inferredModule = if (kind == DrivebaseKind.FRC_CTRE_SWERVE && role in setOf(DrivetrainComponentRole.DRIVE_MOTOR, DrivetrainComponentRole.STEER_MOTOR, DrivetrainComponentRole.ABSOLUTE_ENCODER)) "module.$corner" else null
        (existing ?: DrivetrainComponentDocument(
            uid = edit.id,
            displayName = edit.displayName,
            role = role,
            hardwareId = edit.canId?.toString() ?: edit.hardwareName,
            controllerModel = edit.controllerModel,
            encoderModel = edit.encoderModel,
            currentMeasurementRequired = edit.currentMeasurementRequired,
            currentMeasurementAvailable = edit.currentMeasurementAvailable,
            currentLimitAmps = edit.currentLimitAmps,
            moduleUid = inferredModule,
        )).copy(
            displayName = edit.displayName,
            role = role,
            hardwareId = edit.canId?.toString() ?: edit.hardwareName,
            controllerModel = edit.controllerModel,
            encoderModel = edit.encoderModel,
            currentMeasurementRequired = edit.currentMeasurementRequired,
            currentMeasurementAvailable = edit.currentMeasurementAvailable,
            currentLimitAmps = edit.currentLimitAmps,
            inverted = edit.inverted,
            required = edit.required,
            leaderUid = edit.leaderId,
            xMeters = edit.xMeters,
            yMeters = edit.yMeters,
            zMeters = edit.zMeters,
            pitchDegrees = edit.pitchDegrees,
            yawDegrees = edit.yawDegrees,
            rollDegrees = edit.rollDegrees,
        )
    }
    val editedCanBuses = hardware.mapNotNull { it.canBus?.takeIf(String::isNotBlank) }.distinct()
    require(editedCanBuses.size <= 1) { "A CTRE drivetrain document has one named CAN bus. Resolve conflicting component bus names." }
    val ctreImport = base.ctreImport?.let { metadata ->
        metadata.copy(canBusName = editedCanBuses.singleOrNull() ?: metadata.canBusName)
    }
    val selectedSources = localization.toSet()
    fun source(kind: LocalizationKind): DrivetrainLocalizationSourceDocument = when (kind) {
        LocalizationKind.FTC_PINPOINT -> DrivetrainLocalizationSourceDocument("localization.pinpoint", LocalizationSourceKind.PINPOINT, components.filter { it.role == DrivetrainComponentRole.ODOMETRY_SENSOR }.map { it.uid })
        LocalizationKind.WHEEL_ODOMETRY_GYRO -> DrivetrainLocalizationSourceDocument("localization.wheel-imu", LocalizationSourceKind.WHEEL_ENCODERS_IMU, components.filter { it.role == DrivetrainComponentRole.DRIVE_MOTOR || it.role == DrivetrainComponentRole.GYRO }.map { it.uid })
        LocalizationKind.CTRE_POSE_ESTIMATOR -> DrivetrainLocalizationSourceDocument("localization.ctre", LocalizationSourceKind.CTRE_VENDOR, components.map { it.uid })
        LocalizationKind.VISION_FUSION -> DrivetrainLocalizationSourceDocument("localization.vision", LocalizationSourceKind.EXTERNAL, emptyList(), "com.areslib.vision.VisionTracker")
        LocalizationKind.CUSTOM -> DrivetrainLocalizationSourceDocument("localization.custom", LocalizationSourceKind.CUSTOM, emptyList(), "com.areslib.localization.CustomLocalization")
    }
    val localizationChanged = originalUi == null || localization != originalUi.localization
    val primaryKind = selectedSources.filter { it != LocalizationKind.VISION_FUSION }.single()
    val primary = if (localizationChanged) {
        val desired = source(primaryKind)
        (listOf(base.localization.primaryOdometry) + base.localization.visionFusion).firstOrNull { it.source == desired.source } ?: desired
    } else base.localization.primaryOdometry
    val vision = if (localizationChanged) selectedSources.filter { it == LocalizationKind.VISION_FUSION }.map { kind ->
        val desired = source(kind)
        base.localization.visionFusion.firstOrNull { it.source == desired.source } ?: desired
    } else base.localization.visionFusion
    return base.copy(
        uid = documentId,
        drivebaseId = if (documentId == base.uid) base.drivebaseId else documentId.substringAfterLast('.').replace('_', '-'),
        displayName = displayName,
        kind = when (kind) { DrivebaseKind.FTC_MECANUM -> DrivetrainKind.FTC_MECANUM; DrivebaseKind.FRC_CTRE_SWERVE -> DrivetrainKind.FRC_CTRE_SWERVE; DrivebaseKind.DIFFERENTIAL -> DrivetrainKind.DIFFERENTIAL; DrivebaseKind.CUSTOM -> DrivetrainKind.ADVANCED_CUSTOM },
        components = components,
        geometry = base.geometry.copy(
            wheelDiameterMeters = geometry.wheelRadiusMeters * 2.0,
            trackWidthMeters = geometry.trackWidthMeters,
            wheelBaseMeters = geometry.wheelBaseMeters,
            maxLinearSpeedMetersPerSecond = safety.maxLinearSpeedMetersPerSecond,
            maxAngularSpeedRadiansPerSecond = safety.maxAngularSpeedRadiansPerSecond
        ),
        localization = base.localization.copy(
            primaryOdometry = primary,
            headingSourceUid = if (localizationChanged) components.firstOrNull { it.role == DrivetrainComponentRole.GYRO }?.uid ?: primary.uid else base.localization.headingSourceUid,
            visionFusion = vision
        ),
        control = base.control.copy(
            supported = supportedControlModes,
            defaultControl = defaultControlMode,
            fieldCentric = fieldRelativeEnabled,
        ),
        safety = base.safety.copy(
            safeNeutralRequired = safety.safeNeutralRequired,
            configurationHealthRequired = safety.configurationHealthRequired,
            staleFeedbackTimeoutMs = safety.feedbackFreshnessTimeoutMs.toLong(),
            currentValidityRequired = safety.currentMonitoringRequired,
            faultLatchingRequired = safety.faultLatchingRequired,
            explicitNeutralRecoveryRequired = safety.explicitNeutralRecoveryRequired,
            enabledNeutralMode = safety.enabledNeutralMode,
            disabledPolicy = safety.disabledPolicy,
        ),
        ctreImport = ctreImport
    )
}

private fun DrivetrainComponentDocument.toUiRole(kind: DrivetrainKind): DriveHardwareRole {
    val directLeaderUid = leaderUid
    val normalizedUid = uid.lowercase()
    val normalizedHardwareId = hardwareId.lowercase()
    fun isCorner(longName: String, shortName: String): Boolean =
        longName in normalizedUid || normalizedUid.endsWith(".$shortName") || normalizedHardwareId == shortName
    return when (role) {
    DrivetrainComponentRole.DRIVE_MOTOR -> when {
        directLeaderUid != null && directLeaderUid.contains("left") -> DriveHardwareRole.LEFT_FOLLOWER
        directLeaderUid != null && directLeaderUid.contains("right") -> DriveHardwareRole.RIGHT_FOLLOWER
        kind == DrivetrainKind.DIFFERENTIAL && uid.contains("left") -> DriveHardwareRole.LEFT_LEADER
        kind == DrivetrainKind.DIFFERENTIAL && uid.contains("right") -> DriveHardwareRole.RIGHT_LEADER
        isCorner("front-left", "fl") -> DriveHardwareRole.FRONT_LEFT_DRIVE
        isCorner("front-right", "fr") -> DriveHardwareRole.FRONT_RIGHT_DRIVE
        isCorner("rear-left", "rl") -> DriveHardwareRole.REAR_LEFT_DRIVE
        isCorner("rear-right", "rr") -> DriveHardwareRole.REAR_RIGHT_DRIVE
        else -> DriveHardwareRole.DRIVE_MOTOR
    }
    DrivetrainComponentRole.STEER_MOTOR -> when {
        uid.contains("front-left") -> DriveHardwareRole.FRONT_LEFT_STEER
        uid.contains("front-right") -> DriveHardwareRole.FRONT_RIGHT_STEER
        uid.contains("rear-left") -> DriveHardwareRole.REAR_LEFT_STEER
        uid.contains("rear-right") -> DriveHardwareRole.REAR_RIGHT_STEER
        else -> DriveHardwareRole.CUSTOM
    }
    DrivetrainComponentRole.ABSOLUTE_ENCODER -> when {
        uid.contains("front-left") -> DriveHardwareRole.FRONT_LEFT_ENCODER
        uid.contains("front-right") -> DriveHardwareRole.FRONT_RIGHT_ENCODER
        uid.contains("rear-left") -> DriveHardwareRole.REAR_LEFT_ENCODER
        uid.contains("rear-right") -> DriveHardwareRole.REAR_RIGHT_ENCODER
        else -> DriveHardwareRole.OTHER
    }
    DrivetrainComponentRole.GYRO -> DriveHardwareRole.GYRO
    DrivetrainComponentRole.ODOMETRY_SENSOR -> DriveHardwareRole.ODOMETRY
    DrivetrainComponentRole.WHEEL_MODULE, DrivetrainComponentRole.OTHER -> when {
        normalizedUid.contains("limelight") || normalizedHardwareId.contains("limelight") || displayName.contains("limelight", ignoreCase = true) || displayName.contains("camera", ignoreCase = true) -> DriveHardwareRole.LIMELIGHT
        normalizedUid.contains("distance") || normalizedHardwareId.contains("distance") || displayName.contains("distance", ignoreCase = true) -> DriveHardwareRole.DISTANCE_SENSOR
        else -> DriveHardwareRole.OTHER
    }
}
}

private fun DriveHardwareRole.toCanonicalRole(): DrivetrainComponentRole = when {
    name.endsWith("STEER") -> DrivetrainComponentRole.STEER_MOTOR
    name.endsWith("ENCODER") -> DrivetrainComponentRole.ABSOLUTE_ENCODER
    this == DriveHardwareRole.GYRO -> DrivetrainComponentRole.GYRO
    this == DriveHardwareRole.ODOMETRY -> DrivetrainComponentRole.ODOMETRY_SENSOR
    this == DriveHardwareRole.LIMELIGHT || this == DriveHardwareRole.DISTANCE_SENSOR || this == DriveHardwareRole.OTHER || this == DriveHardwareRole.CUSTOM -> DrivetrainComponentRole.OTHER
    else -> DrivetrainComponentRole.DRIVE_MOTOR
}

internal fun canonicalTemplate(projectId: String, kind: DrivebaseKind): DrivetrainDocument {
    val projectUid = projectId.lowercase().replace(Regex("[^a-z0-9]+"), ".").trim('.').ifBlank { "robot.project" }
    fun drive(
        uid: String,
        hardware: String,
        inverted: Boolean = false,
        module: String? = null,
        xMeters: Double? = null,
        yMeters: Double? = null,
    ) = DrivetrainComponentDocument(
        uid,
        uid.substringAfterLast('.').replace('-', ' ').replaceFirstChar(Char::uppercase),
        DrivetrainComponentRole.DRIVE_MOTOR,
        hardware,
        moduleUid = module,
        currentMeasurementRequired = true,
        currentMeasurementAvailable = true,
        inverted = inverted,
        xMeters = xMeters,
        yMeters = yMeters,
    )
    val components = when (kind) {
        DrivebaseKind.FTC_MECANUM -> listOf(
            drive("drive.front-left", "fl", xMeters = .18, yMeters = .18),
            drive("drive.front-right", "fr", inverted = true, xMeters = .18, yMeters = -.18),
            drive("drive.rear-left", "rl", xMeters = -.18, yMeters = .18),
            drive("drive.rear-right", "rr", inverted = true, xMeters = -.18, yMeters = -.18),
            DrivetrainComponentDocument("drive.pinpoint", "goBILDA Pinpoint", DrivetrainComponentRole.ODOMETRY_SENSOR, "pinpoint"),
        )
        DrivebaseKind.DIFFERENTIAL -> listOf(drive("drive.left", "leftLeader"), drive("drive.right", "rightLeader", true), DrivetrainComponentDocument("drive.gyro", "Gyro", DrivetrainComponentRole.GYRO, "gyro"))
        DrivebaseKind.FRC_CTRE_SWERVE -> listOf("front-left", "front-right", "rear-left", "rear-right").flatMapIndexed { moduleIndex, corner ->
            val module = "module.$corner"
            val firstSimulationCanId = moduleIndex * 3 + 1
            listOf(
                drive("drive.$corner", firstSimulationCanId.toString(), module = module),
                DrivetrainComponentDocument("steer.$corner", "${corner.replace('-', ' ')} steer", DrivetrainComponentRole.STEER_MOTOR, (firstSimulationCanId + 1).toString(), moduleUid = module),
                DrivetrainComponentDocument("encoder.$corner", "${corner.replace('-', ' ')} encoder", DrivetrainComponentRole.ABSOLUTE_ENCODER, (firstSimulationCanId + 2).toString(), moduleUid = module)
            )
        } + DrivetrainComponentDocument("drive.gyro", "Pigeon gyro", DrivetrainComponentRole.GYRO, "13")
        DrivebaseKind.CUSTOM -> listOf(drive("drive.custom", "custom"), DrivetrainComponentDocument("drive.gyro", "Gyro", DrivetrainComponentRole.GYRO, "gyro"))
    }
    val modules = if (kind == DrivebaseKind.FRC_CTRE_SWERVE) listOf("front-left", "front-right", "rear-left", "rear-right").map { corner ->
        val x = if (corner.startsWith("front")) .28 else -.28; val y = if (corner.endsWith("left")) .28 else -.28
        DrivetrainModuleDocument("module.$corner", corner.replace('-', ' ').replaceFirstChar(Char::uppercase), listOf("drive.$corner", "steer.$corner", "encoder.$corner"), x, y)
    } else emptyList()
    val primary = when (kind) {
        DrivebaseKind.FTC_MECANUM -> DrivetrainLocalizationSourceDocument("localization.pinpoint", LocalizationSourceKind.PINPOINT, listOf("drive.pinpoint"))
        DrivebaseKind.FRC_CTRE_SWERVE -> DrivetrainLocalizationSourceDocument("localization.ctre", LocalizationSourceKind.CTRE_VENDOR, components.map { it.uid })
        else -> DrivetrainLocalizationSourceDocument("localization.wheel-imu", LocalizationSourceKind.WHEEL_ENCODERS_IMU, components.map { it.uid })
    }
    val diameter = .096
    val document = DrivetrainDocument(
        uid = "drive.primary", drivebaseId = "primary", displayName = "Primary drivebase", description = "Robot-owned drivebase contract.",
        kind = when (kind) { DrivebaseKind.FTC_MECANUM -> DrivetrainKind.FTC_MECANUM; DrivebaseKind.FRC_CTRE_SWERVE -> DrivetrainKind.FRC_CTRE_SWERVE; DrivebaseKind.DIFFERENTIAL -> DrivetrainKind.DIFFERENTIAL; DrivebaseKind.CUSTOM -> DrivetrainKind.ADVANCED_CUSTOM },
        platform = if (kind == DrivebaseKind.FTC_MECANUM) DrivetrainPlatform.FTC else DrivetrainPlatform.FRC,
        components = components, modules = modules,
        geometry = DrivetrainGeometryDocument(
            diameter,
            .36,
            .36,
            1.0,
            if (kind == DrivebaseKind.FRC_CTRE_SWERVE) 1.0 else null,
            if (kind == DrivebaseKind.FTC_MECANUM) 1.0 else 3.0,
            if (kind == DrivebaseKind.FTC_MECANUM) 1.0 / .36 else 6.0,
        ),
        localization = DrivetrainLocalizationDocument(primary, components.firstOrNull { it.role == DrivetrainComponentRole.GYRO }?.uid ?: primary.uid),
        control = DrivetrainControlDocument(
            listOf(DrivetrainControlKind.OPEN_LOOP, DrivetrainControlKind.CHASSIS_VELOCITY),
            if (kind == DrivebaseKind.FTC_MECANUM) DrivetrainControlKind.CHASSIS_VELOCITY else DrivetrainControlKind.OPEN_LOOP,
        ),
        simulation = DrivetrainSimulationDocument("com.areslib.simulator.DrivetrainModel", "com.areslib.simulator.DrivetrainAdapter"),
        // Physical geometry is authoritative here. It must never be duplicated as a tuning value.
        parameters = emptyList(),
        ctreImport = if (kind == DrivebaseKind.FRC_CTRE_SWERVE) CtreSwerveImportDocument("src/main/java/frc/robot/generated/TunerConstants.java", "0".repeat(64), "CTRE Tuner", "unknown", "frc.robot.generated.TunerConstants", "rio") else null,
        canonicalProfileUid = "$projectUid.profile.competition"
    )
    return if (kind == DrivebaseKind.FTC_MECANUM) {
        FtcMecanumRuntimeParameters.reconcile(document)
    } else {
        document
    }
}

private fun error(path: String, message: String) = DrivebaseIssue(DrivebaseIssueSeverity.ERROR, path, message)
private fun warning(path: String, message: String) = DrivebaseIssue(DrivebaseIssueSeverity.WARNING, path, message)
