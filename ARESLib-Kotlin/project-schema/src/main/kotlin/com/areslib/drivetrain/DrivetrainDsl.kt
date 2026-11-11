package com.areslib.drivetrain

import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningValue

@DslMarker
annotation class DrivetrainDsl

/**
 * Builds and validates a [DrivetrainDocument] using a concise, text-first Kotlin DSL.
 *
 * ### Example
 * ```kotlin
 * val drive = ftcMecanumDrivetrain("main-drive") {
 *     geometry(
 *         wheelDiameterMeters = 0.096,
 *         trackWidthMeters = 0.34,
 *         wheelBaseMeters = 0.32,
 *         driveGearRatio = 19.2,
 *         maxLinearSpeedMetersPerSecond = 2.2,
 *         maxAngularSpeedRadiansPerSecond = 5.0,
 *     )
 *     tuningDouble(
 *         key = "drive.heading.kp",
 *         defaultValue = 1.8,
 *         minimum = 0.0,
 *         maximum = 20.0,
 *         unit = "rad/s per rad",
 *     )
 * }
 * ```
 */
fun drivetrain(
    id: String,
    kind: DrivetrainKind,
    platform: DrivetrainPlatform,
    displayName: String = defaultDrivetrainDisplayName(id),
    uid: String = "drive.${id.lowercase().replace('_', '-')}",
    canonicalProfileUid: String = "profile.competition",
    block: DrivetrainBuilder.() -> Unit,
): DrivetrainDocument = DrivetrainBuilder(
    uid = uid,
    drivebaseId = id,
    displayName = displayName,
    kind = kind,
    platform = platform,
    canonicalProfileUid = canonicalProfileUid,
).apply(block).build()

/**
 * Builds a validated four-motor FTC mecanum [DrivetrainDocument] pre-seeded with standard
 * drive motors (`fl`, `fr`, `rl`, `rr`), Pinpoint odometry, and simulation classes.
 * Any section (`geometry`, `components`, `localization`, `control`, `safety`, `tuningDouble`)
 * can be customized inside [block].
 */
fun ftcMecanumDrivetrain(
    id: String = "main-drive",
    displayName: String = defaultDrivetrainDisplayName(id),
    uid: String = "drive.${id.lowercase().replace('_', '-')}",
    canonicalProfileUid: String = "profile.competition",
    block: DrivetrainBuilder.() -> Unit = {},
): DrivetrainDocument = drivetrain(
    id = id,
    kind = DrivetrainKind.FTC_MECANUM,
    platform = DrivetrainPlatform.FTC,
    displayName = displayName,
    uid = uid,
    canonicalProfileUid = canonicalProfileUid,
) {
    description = "Four-wheel FTC mecanum drivebase."
    listOf("fl", "fr", "rl", "rr").forEach { motorId ->
        driveMotor(motorId, displayName = motorId.uppercase())
    }
    val pinpoint = odometrySensor("pinpoint", displayName = "Pinpoint")
    localization(
        primarySource = LocalizationSourceKind.PINPOINT,
        primaryComponentUids = listOf(pinpoint.uid),
        headingSourceUid = pinpoint.uid,
        primaryUid = "localization.pinpoint",
        implementationClassName = "com.areslib.ftc.hardware.PinpointIO",
    )
    simulation(
        modelClassName = "com.areslib.sim.physics.MecanumPhysicsModel",
        adapterClassName = "com.areslib.ftc.mock.MockMecanumHardwareIO",
    )
    block()
}

/**
 * Builds a validated four-module FRC CTRE Swerve [DrivetrainDocument] pre-seeded with four
 * corner modules (`front-left`, `front-right`, `back-left`, `back-right`), CTRE vendor
 * localization, and read-only vendor import provenance.
 */
fun frcCtreSwerveDrivetrain(
    id: String = "swerve",
    vendorSourcePath: String,
    sourceSha256: String,
    drivetrainConstantsClassName: String,
    canBusName: String = "CANivore",
    generatorName: String = "CTRE Tuner X",
    generatorVersion: String = "2026.1",
    displayName: String = defaultDrivetrainDisplayName(id),
    uid: String = "drive.${id.lowercase().replace('_', '-')}",
    canonicalProfileUid: String = "profile.competition",
    halfTrackMeters: Double = 0.28,
    halfWheelBaseMeters: Double = 0.28,
    block: DrivetrainBuilder.() -> Unit = {},
): DrivetrainDocument = drivetrain(
    id = id,
    kind = DrivetrainKind.FRC_CTRE_SWERVE,
    platform = DrivetrainPlatform.FRC,
    displayName = displayName,
    uid = uid,
    canonicalProfileUid = canonicalProfileUid,
) {
    description = "Four-module FRC CTRE Phoenix 6 swerve drivebase."
    geometry(
        wheelDiameterMeters = 0.1016,
        trackWidthMeters = halfTrackMeters * 2.0,
        wheelBaseMeters = halfWheelBaseMeters * 2.0,
        driveGearRatio = 6.12,
        steerGearRatio = 21.42857,
        maxLinearSpeedMetersPerSecond = 4.8,
        maxAngularSpeedRadiansPerSecond = 9.5,
    )
    val fl = swerveModule("front-left", xMeters = halfWheelBaseMeters, yMeters = halfTrackMeters)
    swerveModule("front-right", xMeters = halfWheelBaseMeters, yMeters = -halfTrackMeters)
    swerveModule("back-left", xMeters = -halfWheelBaseMeters, yMeters = halfTrackMeters)
    swerveModule("back-right", xMeters = -halfWheelBaseMeters, yMeters = -halfTrackMeters)
    val flDriveUid = fl.componentUids.first()
    localization(
        primarySource = LocalizationSourceKind.CTRE_VENDOR,
        primaryComponentUids = listOf(flDriveUid),
        headingSourceUid = "localization.ctre",
        primaryUid = "localization.ctre",
    )
    simulation(
        modelClassName = "com.areslib.frc.sim.SwervePhysicsModel",
        adapterClassName = "com.areslib.frc.sim.MockSwerveHardwareIO",
    )
    ctreImport(
        vendorSourcePath = vendorSourcePath,
        sourceSha256 = sourceSha256,
        drivetrainConstantsClassName = drivetrainConstantsClassName,
        canBusName = canBusName,
        generatorName = generatorName,
        generatorVersion = generatorVersion,
    )
    block()
}

@DrivetrainDsl
class DrivetrainBuilder internal constructor(
    val uid: String,
    val drivebaseId: String,
    var displayName: String,
    val kind: DrivetrainKind,
    val platform: DrivetrainPlatform,
    var canonicalProfileUid: String,
) {
    var description: String = "$displayName drivetrain."
    private val componentEntries = mutableListOf<DrivetrainComponentDocument>()
    private val moduleEntries = mutableListOf<DrivetrainModuleDocument>()
    private val parameterEntries = mutableListOf<TuningParameterDeclaration>()
    private val provenanceEntries = mutableListOf<CalibrationProvenanceDocument>()
    private var geometryDoc = DrivetrainGeometryDocument(
        wheelDiameterMeters = 0.096,
        trackWidthMeters = 0.34,
        wheelBaseMeters = 0.32,
        driveGearRatio = 19.2,
        steerGearRatio = null,
        maxLinearSpeedMetersPerSecond = 2.2,
        maxAngularSpeedRadiansPerSecond = 5.0,
    )
    private var localizationDoc: DrivetrainLocalizationDocument? = null
    private var controlDoc = DrivetrainControlDocument(
        supported = listOf(
            DrivetrainControlKind.OPEN_LOOP,
            DrivetrainControlKind.CHASSIS_VELOCITY,
            DrivetrainControlKind.TRAJECTORY,
        ),
        defaultControl = DrivetrainControlKind.CHASSIS_VELOCITY,
        fieldCentric = true,
    )
    private var safetyDoc = DrivetrainSafetyDocument()
    private var simulationDoc = DrivetrainSimulationDocument(
        modelClassName = "com.areslib.sim.physics.DrivetrainPhysicsModel",
        adapterClassName = "com.areslib.sim.physics.MockDrivetrainIO",
    )
    private var ctreImportDoc: CtreSwerveImportDocument? = null

    /** Replaces all previously registered components (useful when overriding a template starter). */
    fun clearComponents() {
        componentEntries.clear()
        moduleEntries.clear()
    }

    /** Configures physical wheel geometry, gear ratios, and velocity limits. */
    fun geometry(
        wheelDiameterMeters: Double = geometryDoc.wheelDiameterMeters,
        trackWidthMeters: Double = geometryDoc.trackWidthMeters,
        wheelBaseMeters: Double = geometryDoc.wheelBaseMeters,
        driveGearRatio: Double = geometryDoc.driveGearRatio,
        steerGearRatio: Double? = geometryDoc.steerGearRatio,
        maxLinearSpeedMetersPerSecond: Double = geometryDoc.maxLinearSpeedMetersPerSecond,
        maxAngularSpeedRadiansPerSecond: Double = geometryDoc.maxAngularSpeedRadiansPerSecond,
    ) {
        geometryDoc = DrivetrainGeometryDocument(
            wheelDiameterMeters = wheelDiameterMeters, trackWidthMeters = trackWidthMeters,
            wheelBaseMeters = wheelBaseMeters, driveGearRatio = driveGearRatio,
            steerGearRatio = steerGearRatio, maxLinearSpeedMetersPerSecond = maxLinearSpeedMetersPerSecond,
            maxAngularSpeedRadiansPerSecond = maxAngularSpeedRadiansPerSecond,
        )
    }

    /** Declares a drive motor component and adds it to this drivetrain. */
    fun driveMotor(
        hardwareId: String,
        uid: String = "drive.motor.${hardwareId.toUidSegment()}",
        displayName: String = defaultDrivetrainDisplayName(hardwareId),
        controllerModel: String? = "REV HD Hex",
        encoderModel: String? = "Integrated encoder",
        currentMeasurementRequired: Boolean = safetyDoc.currentValidityRequired,
        currentMeasurementAvailable: Boolean = safetyDoc.currentValidityRequired,
        currentLimitAmps: Double? = null,
        inverted: Boolean = false,
        required: Boolean = true,
        moduleUid: String? = null,
        leaderUid: String? = null,
    ): DrivetrainComponentDocument = registerComponent(
        DrivetrainComponentDocument(
            uid = uid, displayName = displayName, role = DrivetrainComponentRole.DRIVE_MOTOR,
            hardwareId = hardwareId, moduleUid = moduleUid, controllerModel = controllerModel,
            encoderModel = encoderModel, currentMeasurementRequired = currentMeasurementRequired,
            currentMeasurementAvailable = currentMeasurementAvailable, currentLimitAmps = currentLimitAmps,
            inverted = inverted, required = required, leaderUid = leaderUid,
        )
    )

    /** Declares a swerve steer motor component. */
    fun steerMotor(
        hardwareId: String,
        uid: String = "drive.steer.${hardwareId.toUidSegment()}",
        displayName: String = defaultDrivetrainDisplayName(hardwareId),
        moduleUid: String? = null,
        controllerModel: String? = "TalonFX",
        encoderModel: String? = "Integrated Sensor",
    ): DrivetrainComponentDocument = registerComponent(
        DrivetrainComponentDocument(
            uid = uid, displayName = displayName, role = DrivetrainComponentRole.STEER_MOTOR,
            hardwareId = hardwareId, moduleUid = moduleUid,
            controllerModel = controllerModel, encoderModel = encoderModel,
        )
    )

    /** Declares an absolute encoder component for a swerve module or drivebase. */
    fun absoluteEncoder(
        hardwareId: String,
        uid: String = "drive.encoder.${hardwareId.toUidSegment()}",
        displayName: String = defaultDrivetrainDisplayName(hardwareId),
        moduleUid: String? = null,
        encoderModel: String? = "CANcoder",
    ): DrivetrainComponentDocument = registerComponent(
        DrivetrainComponentDocument(
            uid = uid, displayName = displayName, role = DrivetrainComponentRole.ABSOLUTE_ENCODER,
            hardwareId = hardwareId, moduleUid = moduleUid, encoderModel = encoderModel,
        )
    )

    /** Declares an odometry sensor component (for example, GoBILDA Pinpoint or OTOS). */
    fun odometrySensor(
        hardwareId: String,
        uid: String = "drive.odometry.${hardwareId.toUidSegment()}",
        displayName: String = defaultDrivetrainDisplayName(hardwareId),
    ): DrivetrainComponentDocument = registerComponent(
        DrivetrainComponentDocument(
            uid = uid, displayName = displayName,
            role = DrivetrainComponentRole.ODOMETRY_SENSOR, hardwareId = hardwareId,
        )
    )

    /** Declares a gyro/IMU component. */
    fun gyro(
        hardwareId: String,
        uid: String = "drive.gyro.${hardwareId.toUidSegment()}",
        displayName: String = defaultDrivetrainDisplayName(hardwareId),
    ): DrivetrainComponentDocument = registerComponent(
        DrivetrainComponentDocument(
            uid = uid, displayName = displayName,
            role = DrivetrainComponentRole.GYRO, hardwareId = hardwareId,
        )
    )

    private fun registerComponent(component: DrivetrainComponentDocument): DrivetrainComponentDocument {
        componentEntries.removeAll { it.uid == component.uid || it.hardwareId == component.hardwareId }
        componentEntries += component
        return component
    }

    /**
     * Declares a complete swerve module along with its owned drive motor, steer motor, and
     * absolute encoder components.
     */
    fun swerveModule(
        id: String,
        xMeters: Double,
        yMeters: Double,
        displayName: String = defaultDrivetrainDisplayName(id),
        uid: String = "module.${id.toUidSegment()}",
        driveHardwareId: String = "$id-drive",
        steerHardwareId: String = "$id-steer",
        encoderHardwareId: String = "$id-encoder",
    ): DrivetrainModuleDocument {
        val slug = id.toUidSegment()
        val drive = driveMotor(
            hardwareId = driveHardwareId, uid = "drive.motor.$slug", displayName = "$displayName Drive",
            controllerModel = "TalonFX", encoderModel = "Integrated Sensor", moduleUid = uid,
        )
        val steer = steerMotor(
            hardwareId = steerHardwareId, uid = "drive.steer.$slug",
            displayName = "$displayName Steer", moduleUid = uid,
        )
        val encoder = absoluteEncoder(
            hardwareId = encoderHardwareId, uid = "drive.encoder.$slug",
            displayName = "$displayName Encoder", moduleUid = uid,
        )
        val module = DrivetrainModuleDocument(
            uid = uid, displayName = displayName,
            componentUids = listOf(drive.uid, steer.uid, encoder.uid),
            xMeters = xMeters, yMeters = yMeters,
        )
        moduleEntries.removeAll { it.uid == uid }
        moduleEntries += module
        return module
    }

    /** Configures primary odometry, heading source, and optional vision fusion sources. */
    fun localization(
        primarySource: LocalizationSourceKind,
        primaryComponentUids: List<String>,
        headingSourceUid: String,
        primaryUid: String = "localization.${primarySource.name.lowercase().replace('_', '-')}",
        implementationClassName: String? = null,
        visionFusion: List<DrivetrainLocalizationSourceDocument> = emptyList(),
    ) {
        localizationDoc = DrivetrainLocalizationDocument(
            primaryOdometry = DrivetrainLocalizationSourceDocument(
                uid = primaryUid, source = primarySource,
                componentUids = primaryComponentUids, implementationClassName = implementationClassName,
            ),
            headingSourceUid = headingSourceUid, visionFusion = visionFusion,
            headingCcwPositive = true, cachedInputsRequired = true,
        )
    }

    /** Configures supported control modes, default mode, and field-centric orientation. */
    fun control(
        defaultControl: DrivetrainControlKind = DrivetrainControlKind.CHASSIS_VELOCITY,
        supported: List<DrivetrainControlKind> = listOf(
            DrivetrainControlKind.OPEN_LOOP,
            DrivetrainControlKind.CHASSIS_VELOCITY,
            DrivetrainControlKind.TRAJECTORY,
        ),
        fieldCentric: Boolean = true,
    ) {
        controlDoc = DrivetrainControlDocument(supported, defaultControl, fieldCentric)
    }

    /** Configures drivebase safety timeouts, current validity requirements, and neutral policies. */
    fun safety(
        staleFeedbackTimeoutMs: Long = 250L,
        currentValidityRequired: Boolean = true,
        enabledNeutralMode: DrivetrainNeutralMode = DrivetrainNeutralMode.BRAKE,
        disabledPolicy: DisabledDrivePolicy = DisabledDrivePolicy.FORCE_NEUTRAL_BRAKE,
    ) {
        safetyDoc = DrivetrainSafetyDocument(
            staleFeedbackTimeoutMs = staleFeedbackTimeoutMs,
            currentValidityRequired = currentValidityRequired,
            enabledNeutralMode = enabledNeutralMode,
            disabledPolicy = disabledPolicy,
        )
    }

    /** Configures simulation model and hardware adapter class names. */
    fun simulation(modelClassName: String, adapterClassName: String) {
        simulationDoc = DrivetrainSimulationDocument(modelClassName, adapterClassName)
    }

    /** Configures read-only CTRE Phoenix 6 Tuner X generator provenance metadata. */
    fun ctreImport(
        vendorSourcePath: String,
        sourceSha256: String,
        drivetrainConstantsClassName: String,
        canBusName: String = "CANivore",
        generatorName: String = "CTRE Tuner X",
        generatorVersion: String = "2026.1",
    ) {
        ctreImportDoc = CtreSwerveImportDocument(
            vendorSourcePath, sourceSha256, generatorName, generatorVersion,
            drivetrainConstantsClassName, canBusName,
        )
    }

    /** Declares a numeric double tuning parameter owned by this drivebase or one of its components. */
    fun tuningDouble(
        key: String,
        defaultValue: Double,
        componentUid: String = this.uid,
        uid: String = key.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase(),
        displayName: String = defaultDrivetrainDisplayName(key.substringAfterLast('.')),
        description: String = "$displayName drivetrain tuning parameter.",
        unit: String? = null,
        minimum: Double? = null,
        maximum: Double? = null,
        applyPolicy: TuningApplyPolicy = TuningApplyPolicy.LIVE_SAFE,
    ) {
        parameterEntries += TuningParameterDeclaration(
            uid = uid, key = key, componentUid = componentUid,
            displayName = displayName, description = description,
            type = TuningParameterType.DOUBLE, unit = unit,
            minimum = minimum, maximum = maximum,
            defaultValue = TuningValue(doubleValue = defaultValue),
            applyPolicy = applyPolicy,
        )
    }

    internal fun build(): DrivetrainDocument {
        val resolvedLocalization = localizationDoc ?: run {
            val firstComponent = componentEntries.firstOrNull()?.uid ?: "drive.motor.fl"
            DrivetrainLocalizationDocument(
                primaryOdometry = DrivetrainLocalizationSourceDocument(
                    uid = "localization.primary",
                    source = LocalizationSourceKind.WHEEL_ENCODERS_IMU,
                    componentUids = listOf(firstComponent),
                ),
                headingSourceUid = firstComponent,
            )
        }
        val document = DrivetrainDocument(
            uid = uid,
            drivebaseId = drivebaseId,
            displayName = displayName,
            description = description,
            kind = kind,
            platform = platform,
            components = componentEntries.toList(),
            modules = moduleEntries.toList(),
            geometry = geometryDoc,
            localization = resolvedLocalization,
            control = controlDoc,
            safety = safetyDoc,
            simulation = simulationDoc,
            parameters = parameterEntries.toList(),
            calibrationProvenance = provenanceEntries.toList(),
            ctreImport = ctreImportDoc,
            canonicalProfileUid = canonicalProfileUid,
        )
        val issues = validateDrivetrainDocument(document)
        require(issues.isEmpty()) {
            formatDrivetrainValidationReport(issues)
        }
        return document
    }
}

/** Formats [DrivetrainValidationIssue] entries into an actionable multi-line diagnostic report. */
fun formatDrivetrainValidationReport(issues: List<DrivetrainValidationIssue>): String {
    if (issues.isEmpty()) return "Drivetrain document is valid."
    return buildString {
        appendLine("Drivetrain validation found ${issues.size} error(s):")
        issues.forEach { issue ->
            appendLine("  [ERROR] ${issue.path}: ${issue.message}")
        }
    }.trimEnd()
}

private fun String.toUidSegment(): String =
    replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
        .lowercase()
        .replace(Regex("[^a-z0-9.-]+"), "-")
        .trim('-', '.')

internal fun defaultDrivetrainDisplayName(id: String): String =
    id.trim()
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .split(Regex("[^A-Za-z0-9]+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.lowercase().replaceFirstChar { it.titlecase() }
        }
        .ifBlank { id }
