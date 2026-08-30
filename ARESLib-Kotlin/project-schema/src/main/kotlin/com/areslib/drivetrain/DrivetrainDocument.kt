package com.areslib.drivetrain

import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.validateTuningParameterDeclarations
import com.google.gson.GsonBuilder
import com.areslib.util.parseJsonElement
import com.areslib.util.sha256Hex

const val ARES_DRIVETRAIN_SCHEMA_VERSION: Int = 1

enum class DrivetrainKind { FTC_MECANUM, FRC_CTRE_SWERVE, DIFFERENTIAL, ADVANCED_CUSTOM }
enum class DrivetrainPlatform { FTC, FRC }
enum class DrivetrainComponentRole {
    DRIVE_MOTOR, STEER_MOTOR, ABSOLUTE_ENCODER, GYRO, ODOMETRY_SENSOR, WHEEL_MODULE, OTHER,
}
enum class LocalizationSourceKind { PINPOINT, WHEEL_ENCODERS_IMU, CTRE_VENDOR, EXTERNAL, CUSTOM }
enum class DrivetrainControlKind { OPEN_LOOP, WHEEL_VELOCITY, CHASSIS_VELOCITY, TRAJECTORY }
enum class DrivetrainNeutralMode { BRAKE, COAST }
enum class DisabledDrivePolicy { FORCE_NEUTRAL_BRAKE, FORCE_NEUTRAL_COAST }
enum class VendorSourceOwnership { READ_ONLY_VENDOR }
enum class CalibrationProvenanceKind { MEASURED, SYSID, VENDOR_GENERATED, MANUFACTURER, REVIEWED_MANUAL }

data class DrivetrainComponentDocument(
    val uid: String,
    val displayName: String,
    val role: DrivetrainComponentRole,
    val hardwareId: String,
    val moduleUid: String? = null,
    val controllerModel: String? = null,
    val encoderModel: String? = null,
    /** Whether control/safety requires a finite cached current sample from this device. */
    val currentMeasurementRequired: Boolean = false,
    /** Whether the selected adapter can actually provide that cached sample. */
    val currentMeasurementAvailable: Boolean = false,
    /** Configured controller-enforced limit; null means no enforceable per-device limit is claimed. */
    val currentLimitAmps: Double? = null,
    val xMeters: Double? = null,
    val yMeters: Double? = null,
    val zMeters: Double? = null,
    val pitchDegrees: Double? = null,
    val yawDegrees: Double? = null,
    val rollDegrees: Double? = null,
    val inverted: Boolean = false,
    val required: Boolean = true,
    /** Optional direct leader for a hardware-controller follower; follower inversion remains [inverted]. */
    val leaderUid: String? = null,
)

/** Physical module/group association, required for swerve and available to custom drivebases. */
data class DrivetrainModuleDocument(
    val uid: String,
    val displayName: String,
    val componentUids: List<String>,
    val xMeters: Double,
    val yMeters: Double,
)

data class DrivetrainGeometryDocument(
    val wheelDiameterMeters: Double,
    val trackWidthMeters: Double,
    val wheelBaseMeters: Double,
    val driveGearRatio: Double,
    val steerGearRatio: Double? = null,
    val maxLinearSpeedMetersPerSecond: Double,
    val maxAngularSpeedRadiansPerSecond: Double,
)

data class DrivetrainLocalizationSourceDocument(
    val uid: String,
    val source: LocalizationSourceKind,
    val componentUids: List<String>,
    val implementationClassName: String? = null,
)

data class DrivetrainLocalizationDocument(
    val primaryOdometry: DrivetrainLocalizationSourceDocument,
    val headingSourceUid: String,
    val visionFusion: List<DrivetrainLocalizationSourceDocument> = emptyList(),
    val headingCcwPositive: Boolean = true,
    val cachedInputsRequired: Boolean = true,
)

data class DrivetrainControlDocument(
    val supported: List<DrivetrainControlKind>,
    val defaultControl: DrivetrainControlKind,
    val fieldCentric: Boolean = true,
)

data class DrivetrainSafetyDocument(
    val safeNeutralRequired: Boolean = true,
    val configurationHealthRequired: Boolean = true,
    val staleFeedbackTimeoutMs: Long = 250L,
    val currentValidityRequired: Boolean = true,
    val faultLatchingRequired: Boolean = true,
    val explicitNeutralRecoveryRequired: Boolean = true,
    val disabledOutputIsNeutral: Boolean = true,
    val enabledNeutralMode: DrivetrainNeutralMode = DrivetrainNeutralMode.BRAKE,
    val disabledPolicy: DisabledDrivePolicy = DisabledDrivePolicy.FORCE_NEUTRAL_BRAKE,
    val zeroAllocationPeriodicRequired: Boolean = true,
)

data class DrivetrainSimulationDocument(
    val modelClassName: String,
    val adapterClassName: String,
    val usesPhysicalGeometry: Boolean = true,
    val usesCanonicalProfile: Boolean = true,
    val behavioralParityRequired: Boolean = true,
)

data class CalibrationProvenanceDocument(
    val uid: String,
    val kind: CalibrationProvenanceKind,
    val parameterUids: List<String>,
    val evidencePath: String,
    val evidenceSha256: String,
    val notes: String,
)

/** Metadata imported from CTRE's generator. The referenced vendor source remains read-only. */
data class CtreSwerveImportDocument(
    val vendorSourcePath: String,
    val sourceSha256: String,
    val generatorName: String,
    val generatorVersion: String,
    val drivetrainConstantsClassName: String,
    /** Named CAN bus used by the generated CTRE drivetrain (for example, `CAN2`). */
    val canBusName: String,
    val ownership: VendorSourceOwnership = VendorSourceOwnership.READ_ONLY_VENDOR,
)

/** Dedicated drivebase contract stored as an `.aresdrivetrain` file under `.ares/drivetrains`. */
data class DrivetrainDocument(
    val schemaVersion: Int = ARES_DRIVETRAIN_SCHEMA_VERSION,
    val uid: String,
    val drivebaseId: String,
    val displayName: String,
    val description: String,
    val kind: DrivetrainKind,
    val platform: DrivetrainPlatform,
    val components: List<DrivetrainComponentDocument>,
    val modules: List<DrivetrainModuleDocument> = emptyList(),
    val geometry: DrivetrainGeometryDocument,
    val localization: DrivetrainLocalizationDocument,
    val control: DrivetrainControlDocument,
    val safety: DrivetrainSafetyDocument = DrivetrainSafetyDocument(),
    val simulation: DrivetrainSimulationDocument,
    val parameters: List<TuningParameterDeclaration>,
    val calibrationProvenance: List<CalibrationProvenanceDocument> = emptyList(),
    val ctreImport: CtreSwerveImportDocument? = null,
    val canonicalProfileUid: String,
)

data class DrivetrainValidationIssue(val path: String, val message: String)

fun validateDrivetrainDocument(document: DrivetrainDocument): List<DrivetrainValidationIssue> = buildList {
    fun issue(path: String, message: String) { add(DrivetrainValidationIssue(path, message)) }
    if (document.schemaVersion != ARES_DRIVETRAIN_SCHEMA_VERSION) issue("schemaVersion", "Unsupported drivetrain schema ${document.schemaVersion}")
    if (!document.uid.matches(UID)) issue("uid", "UID must be a stable lowercase dotted identifier")
    if (!document.drivebaseId.matches(ID)) issue("drivebaseId", "Drivebase ID must be a stable lowercase key")
    if (document.displayName.isBlank()) issue("displayName", "Display name is required")
    if (document.description.isBlank()) issue("description", "Description is required")
    if (!document.canonicalProfileUid.matches(UID)) issue("canonicalProfileUid", "Canonical profile UID is invalid")
    if (document.components.isEmpty()) issue("components", "At least one physical component is required")
    duplicate(document.components.map { it.uid }).forEach { issue("components", "Component UID '$it' is duplicated") }
    duplicate(document.components.map { it.hardwareId }).forEach { issue("components", "Hardware ID '$it' is duplicated") }
    document.components.forEachIndexed { index, component ->
        val path = "components[$index]"
        if (!component.uid.matches(UID)) issue("$path.uid", "Component UID is invalid")
        if (component.displayName.isBlank()) issue("$path.displayName", "Component display name is required")
        if (component.hardwareId.isBlank()) issue("$path.hardwareId", "Hardware ID is required")
        if (component.controllerModel?.isBlank() == true) issue("$path.controllerModel", "Controller model must be omitted or non-blank")
        if (component.encoderModel?.isBlank() == true) issue("$path.encoderModel", "Encoder model must be omitted or non-blank")
        component.currentLimitAmps?.let { if (!it.isFinite() || it <= 0.0) issue("$path.currentLimitAmps", "Current limit must be finite and positive") }
        if (component.currentMeasurementRequired && !component.currentMeasurementAvailable) {
            issue("$path.currentMeasurementAvailable", "Required current measurement is not available from the adapter")
        }
        listOf(component.xMeters, component.yMeters).filterNotNull().forEach {
            if (!it.isFinite()) issue(path, "Component positions must be finite")
        }
        component.leaderUid?.let { leaderUid ->
            val leader = document.components.firstOrNull { it.uid == leaderUid }
            if (leader == null) issue("$path.leaderUid", "Unknown leader '$leaderUid'")
            else {
                if (leader.uid == component.uid) issue("$path.leaderUid", "A component cannot follow itself")
                if (component.role != DrivetrainComponentRole.DRIVE_MOTOR || leader.role != DrivetrainComponentRole.DRIVE_MOTOR) {
                    issue("$path.leaderUid", "Leader/follower relationships are supported only between drive motors")
                }
                if (leader.leaderUid != null) issue("$path.leaderUid", "Follower chains are not supported; choose a direct leader")
            }
        }
    }
    val componentUids = document.components.map { it.uid }.toSet()
    duplicate(document.modules.map { it.uid }).forEach { issue("modules", "Module UID '$it' is duplicated") }
    val moduleUids = document.modules.map { it.uid }.toSet()
    document.modules.forEachIndexed { index, module ->
        val path = "modules[$index]"
        if (!module.uid.matches(UID)) issue("$path.uid", "Module UID is invalid")
        if (module.displayName.isBlank()) issue("$path.displayName", "Module display name is required")
        if (!module.xMeters.isFinite() || !module.yMeters.isFinite()) issue(path, "Module positions must be finite")
        if (module.componentUids.isEmpty()) issue("$path.componentUids", "A module must own components")
        duplicate(module.componentUids).forEach { issue("$path.componentUids", "Component '$it' is duplicated in the module") }
        module.componentUids.filterNot(componentUids::contains).forEach { issue("$path.componentUids", "Unknown component '$it'") }
        if (document.kind == DrivetrainKind.FRC_CTRE_SWERVE) {
            val roles = module.componentUids.mapNotNull { uid -> document.components.firstOrNull { it.uid == uid }?.role }
            listOf(DrivetrainComponentRole.DRIVE_MOTOR, DrivetrainComponentRole.STEER_MOTOR, DrivetrainComponentRole.ABSOLUTE_ENCODER).forEach { requiredRole ->
                if (roles.count { it == requiredRole } != 1) issue("$path.componentUids", "A CTRE swerve module requires exactly one ${requiredRole.name.lowercase().replace('_', ' ')}")
            }
        }
    }
    document.components.forEachIndexed { index, component ->
        component.moduleUid?.let { if (it !in moduleUids) issue("components[$index].moduleUid", "Unknown module '$it'") }
    }
    document.modules.forEachIndexed { index, module ->
        module.componentUids.forEach { componentUid ->
            val component = document.components.firstOrNull { it.uid == componentUid }
            if (component != null && component.moduleUid != module.uid) issue("modules[$index].componentUids", "Component '$componentUid' must name module '${module.uid}'")
        }
    }
    document.components.forEachIndexed { index, component ->
        component.moduleUid?.let { owner ->
            val memberships = document.modules.count { component.uid in it.componentUids }
            if (memberships != 1) issue("components[$index].moduleUid", "Component '${component.uid}' must appear exactly once in its owning module '$owner'")
        }
    }
    val localizationSources = listOf(document.localization.primaryOdometry) + document.localization.visionFusion
    duplicate(localizationSources.map { it.uid }).forEach { issue("localization", "Localization source UID '$it' is duplicated") }
    localizationSources.forEachIndexed { index, source ->
        val path = if (index == 0) "localization.primaryOdometry" else "localization.visionFusion[${index - 1}]"
        if (!source.uid.matches(UID)) issue("$path.uid", "Localization source UID is invalid")
        duplicate(source.componentUids).forEach { issue("$path.componentUids", "Component '$it' is duplicated") }
        source.componentUids.filterNot(componentUids::contains).forEach { issue("$path.componentUids", "Unknown localization component '$it'") }
        source.implementationClassName?.let { if (!it.matches(CLASS_NAME)) issue("$path.implementationClassName", "Implementation class must be fully qualified") }
    }
    if (document.localization.headingSourceUid !in componentUids && document.localization.headingSourceUid !in localizationSources.map { it.uid }) {
        issue("localization.headingSourceUid", "Heading source must reference a component or localization source")
    }
    if (!document.localization.headingCcwPositive) issue("localization.headingCcwPositive", "ARES localization must be CCW-positive")
    if (!document.localization.cachedInputsRequired) issue("localization.cachedInputsRequired", "Localization hardware reads must be cached")
    val geometryValues = listOf(
        document.geometry.wheelDiameterMeters, document.geometry.trackWidthMeters,
        document.geometry.wheelBaseMeters, document.geometry.driveGearRatio,
        document.geometry.maxLinearSpeedMetersPerSecond, document.geometry.maxAngularSpeedRadiansPerSecond,
    ) + listOfNotNull(document.geometry.steerGearRatio)
    if (geometryValues.any { !it.isFinite() || it <= 0.0 }) issue("geometry", "Geometry and limits must be finite and positive")
    if (document.control.supported.isEmpty()) issue("control.supported", "At least one control mode is required")
    if (document.control.defaultControl !in document.control.supported) issue("control.defaultControl", "Default control must be supported")
    duplicate(document.control.supported.map(Enum<*>::name)).forEach { issue("control.supported", "Control '$it' is duplicated") }
    with(document.safety) {
        if (!safeNeutralRequired || !configurationHealthRequired || !faultLatchingRequired ||
            !explicitNeutralRecoveryRequired || !disabledOutputIsNeutral || !zeroAllocationPeriodicRequired
        ) issue("safety", "Drivebase safety must fail closed, latch faults, recover explicitly, and remain zero-allocation")
        if (staleFeedbackTimeoutMs !in 20L..10_000L) issue("safety.staleFeedbackTimeoutMs", "Feedback timeout must be 20..10000 ms")
        if (currentValidityRequired && document.components.any {
                it.role == DrivetrainComponentRole.DRIVE_MOTOR &&
                    (!it.currentMeasurementRequired || !it.currentMeasurementAvailable)
            }
        ) {
            issue("components.currentMeasurementRequired", "Every drive motor requires a valid cached current measurement")
        }
    }
    listOf("modelClassName" to document.simulation.modelClassName, "adapterClassName" to document.simulation.adapterClassName).forEach { (field, value) ->
        if (!value.matches(CLASS_NAME)) issue("simulation.$field", "Simulation class must be fully qualified")
    }
    if (!document.simulation.usesPhysicalGeometry || !document.simulation.usesCanonicalProfile || !document.simulation.behavioralParityRequired) {
        issue("simulation", "Simulation must use the same geometry, profile, and observable behavior as the robot")
    }
    validateTuningParameterDeclarations(document.parameters).forEach { issue("parameters.${it.path}", it.message) }
    val parameterUids = document.parameters.map { it.uid }.toSet()
    val parameterOwners = componentUids + moduleUids + document.uid
    document.parameters.map { it.componentUid }.toSet().filterNot(parameterOwners::contains).forEach { issue("parameters.componentUid", "Unknown component/module/drivebase '$it'") }
    duplicate(document.calibrationProvenance.map { it.uid }).forEach { issue("calibrationProvenance", "Provenance UID '$it' is duplicated") }
    document.calibrationProvenance.forEachIndexed { index, provenance ->
        val path = "calibrationProvenance[$index]"
        if (!provenance.uid.matches(UID)) issue("$path.uid", "Provenance UID is invalid")
        if (!provenance.evidencePath.safeRelative()) issue("$path.evidencePath", "Evidence path must be project-relative")
        if (!provenance.evidenceSha256.matches(SHA)) issue("$path.evidenceSha256", "Evidence hash must be SHA-256")
        if (provenance.notes.isBlank()) issue("$path.notes", "Provenance notes are required")
        provenance.parameterUids.filterNot(parameterUids::contains).forEach { issue("$path.parameterUids", "Unknown parameter '$it'") }
    }
    when (document.kind) {
        DrivetrainKind.FTC_MECANUM -> {
            if (document.platform != DrivetrainPlatform.FTC || document.ctreImport != null) issue("kind", "FTC mecanum cannot use FRC/CTRE metadata")
            val driveMotors = document.components.filter { it.role == DrivetrainComponentRole.DRIVE_MOTOR }
            if (driveMotors.size != 4) issue("components", "FTC mecanum requires exactly four drive motors")
            if (driveMotors.any { !it.required }) issue("components", "Every FTC mecanum drive motor must be required at startup")
            if (document.modules.isNotEmpty()) issue("modules", "FTC mecanum wheel positions are defined by geometry, not swerve modules")
        }
        DrivetrainKind.FRC_CTRE_SWERVE -> {
            if (document.platform != DrivetrainPlatform.FRC) issue("platform", "CTRE swerve requires FRC")
            val imported = document.ctreImport
            if (imported == null) issue("ctreImport", "CTRE swerve requires vendor import metadata") else validateCtre(imported, ::issue)
            if (document.modules.size != 4) issue("modules", "CTRE swerve requires four explicitly associated modules")
            document.modules.forEachIndexed { index, module ->
                val roles = module.componentUids.mapNotNull { uid -> document.components.firstOrNull { it.uid == uid }?.role }.toSet()
                if (!roles.containsAll(setOf(DrivetrainComponentRole.DRIVE_MOTOR, DrivetrainComponentRole.STEER_MOTOR, DrivetrainComponentRole.ABSOLUTE_ENCODER))) {
                    issue("modules[$index]", "Each CTRE module requires drive, steer, and absolute-encoder components")
                }
            }
        }
        DrivetrainKind.DIFFERENTIAL, DrivetrainKind.ADVANCED_CUSTOM -> if (document.ctreImport != null) issue("ctreImport", "Only CTRE swerve accepts CTRE import metadata")
    }
}

private fun validateCtre(value: CtreSwerveImportDocument, issue: (String, String) -> Unit) {
    if (!value.vendorSourcePath.safeRelative() || !value.vendorSourcePath.endsWith(".java")) issue("ctreImport.vendorSourcePath", "Vendor source must be a project-relative Java file")
    if (!value.sourceSha256.matches(SHA)) issue("ctreImport.sourceSha256", "Vendor source hash must be SHA-256")
    if (value.generatorName.isBlank() || value.generatorVersion.isBlank()) issue("ctreImport", "Generator name and version are required")
    if (!value.drivetrainConstantsClassName.matches(CLASS_NAME)) issue("ctreImport.drivetrainConstantsClassName", "CTRE constants class must be fully qualified")
    if (value.canBusName.isBlank()) issue("ctreImport.canBusName", "CTRE CAN bus name is required")
}

object DrivetrainDocumentCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    fun encode(document: DrivetrainDocument): String {
        requireValid(document)
        return gson.toJson(document.copy(
            components = document.components.sortedBy { it.uid },
            modules = document.modules.sortedBy { it.uid }.map { module ->
                module.copy(componentUids = module.componentUids.sorted())
            },
            localization = document.localization.copy(
                primaryOdometry = document.localization.primaryOdometry.copy(
                    componentUids = document.localization.primaryOdometry.componentUids.sorted(),
                ),
                visionFusion = document.localization.visionFusion.sortedBy { it.uid }.map { source ->
                    source.copy(componentUids = source.componentUids.sorted())
                },
            ),
            control = document.control.copy(supported = document.control.supported.sortedBy { it.name }),
            parameters = document.parameters.sortedBy { it.uid },
            calibrationProvenance = document.calibrationProvenance.sortedBy { it.uid }.map { provenance ->
                provenance.copy(parameterUids = provenance.parameterUids.sorted())
            },
        ))
    }
    fun decode(json: String): DrivetrainDocument {
        val root = runCatching { parseJsonElement(json).asJsonObject }.getOrElse { throw IllegalArgumentException("Drivetrain document is not valid JSON", it) }
        require(root.get("schemaVersion")?.asInt == ARES_DRIVETRAIN_SCHEMA_VERSION) { "Unsupported drivetrain schema ${root.get("schemaVersion")}" }
        REQUIRED_TOP_LEVEL.forEach { require(root.has(it)) { "Drivetrain field '$it' is required" } }
        val document = runCatching { gson.fromJson(root, DrivetrainDocument::class.java) }.getOrElse { throw IllegalArgumentException("Invalid drivetrain document: ${it.message}", it) }
        requireValid(document)
        return document
    }
    fun contentHash(document: DrivetrainDocument): String = sha256Hex(encode(document))
    private fun requireValid(document: DrivetrainDocument) {
        val issues = validateDrivetrainDocument(document)
        require(issues.isEmpty()) { issues.joinToString("; ") { "${it.path}: ${it.message}" } }
    }
}

private val REQUIRED_TOP_LEVEL = setOf("schemaVersion", "uid", "drivebaseId", "displayName", "description", "kind", "platform", "components", "geometry", "localization", "control", "safety", "simulation", "parameters", "canonicalProfileUid")
private val UID = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
private val ID = Regex("[a-z][a-z0-9-]{0,63}")
private val CLASS_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
private val SHA = Regex("[a-f0-9]{64}")
private fun duplicate(values: List<String>) = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
private fun String.safeRelative() = isNotBlank() && !startsWith('/') && '\\' !in this && split('/').none { it.isBlank() || it == "." || it == ".." }
