package com.ares.analytics.service.hardware

import com.ares.analytics.service.drivebase.DriveHardwareRole
import com.ares.analytics.service.drivebase.DrivebaseProjectRepository
import com.ares.analytics.service.commissioning.CommissioningSimulationSummary
import com.ares.analytics.service.commissioning.CommissioningVerificationService
import com.ares.analytics.service.writeFileAtomically
import com.ares.analytics.shared.models.League
import com.ares.analytics.service.project.persistence.SubsystemProjectRepository
import com.ares.analytics.util.Sha256
import com.areslib.drivetrain.DrivetrainComponentRole
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemHardwareKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Clock

enum class HardwareInventoryOwner { DRIVEBASE, SUBSYSTEM }

enum class HardwareAddressKind(val label: String) {
    FTC_HARDWARE_MAP("FTC hardware-map name"),
    XRP_PORT("XRP built-in / expansion port"),
    CAN("CAN device"),
    PWM("PWM channel"),
    I2C("I2C device"),
    DIO("digital-input channel"),
    ANALOG("analog-input channel"),
    SPI("SPI device"),
    PNEUMATICS("pneumatics module/channel"),
    UNKNOWN("unclassified address"),
}

enum class HardwareIssueSeverity { INFO, WARNING, ERROR }

data class HardwareInventoryIssue(
    val severity: HardwareIssueSeverity,
    val message: String,
    val itemUid: String? = null,
)

data class HardwareInventoryItem(
    val uid: String,
    val displayName: String,
    val owner: HardwareInventoryOwner,
    val ownerDisplayName: String,
    val sourcePath: String,
    val role: String,
    /** Stable enum name used by commissioning tools; [role] remains the student-facing label. */
    val roleKey: String,
    val addressKind: HardwareAddressKind,
    val address: String,
    val bus: String? = null,
    val required: Boolean,
    val inverted: Boolean,
    /** Descriptor-owned details students must transfer or verify during physical setup. */
    val configurationDetails: List<String> = emptyList(),
    /** Advisory commissioning metadata. It never grants authority to command hardware. */
    val isMotionActuator: Boolean = false,
    val isFollower: Boolean = false,
    val safeOutput: Double? = null,
    val measurementFieldIds: List<String> = emptyList(),
    val controlStrategies: List<String> = emptyList(),
    val homingMethod: String? = null,
    val homingEvidence: List<String> = emptyList(),
    val requiresCalibration: Boolean = false,
    val requiresCurrentMonitoring: Boolean = false,
) {
    val addressDescription: String
        get() = when {
            address.isBlank() -> "Not configured"
            bus.isNullOrBlank() -> "${addressKind.label}: $address"
            else -> "${addressKind.label}: $address on $bus"
        }
}

enum class HardwareReviewStatus {
    NOT_REVIEWED,
    CURRENT,
    STALE,
    INVALID,
}

data class HardwareSetupSnapshot(
    val projectPath: String,
    val league: League,
    val inventoryHash: String,
    val items: List<HardwareInventoryItem>,
    val issues: List<HardwareInventoryIssue>,
    val reviewStatus: HardwareReviewStatus,
    val reviewedBy: String? = null,
    val simulationVerification: CommissioningSimulationSummary,
    val physicalValidation: HardwarePhysicalValidationEvidence? = null,
) {
    val errorIssues: List<HardwareInventoryIssue>
        get() = issues.filter { it.severity == HardwareIssueSeverity.ERROR }

    val canReview: Boolean
        get() = items.isNotEmpty() && errorIssues.isEmpty()
}

data class HardwareReviewRequest(
    val reviewerName: String,
    val wiringMatched: Boolean,
    val addressesChecked: Boolean,
    val directionsChecked: Boolean,
    val neutralOutputsChecked: Boolean,
    val limitsChecked: Boolean,
)

data class HardwarePhysicalValidationEvidence(
    val inventoryHash: String,
    val validatedBy: String,
    val evidenceSummary: String,
    val recordedAtEpochMillis: Long,
)

data class HardwarePhysicalValidationRequest(
    val validatedBy: String,
    val evidenceSummary: String,
    val directionsAndPolarityTested: Boolean,
    val unitsAndSensorsTested: Boolean,
    val disabledNeutralTested: Boolean,
    val limitsAndCurrentTested: Boolean,
    val faultRecoveryTested: Boolean,
)

@Serializable
private data class HardwareSourceFingerprint(
    val path: String,
    val sha256: String,
)

@Serializable
private data class HardwareReviewDocument(
    val schemaVersion: Int = 3,
    val league: String,
    val inventoryHash: String,
    val reviewedBy: String,
    val wiringMatched: Boolean,
    val addressesChecked: Boolean,
    val directionsChecked: Boolean,
    val neutralOutputsChecked: Boolean,
    val limitsChecked: Boolean,
    val sources: List<HardwareSourceFingerprint>,
    val recordedAtEpochMillis: Long,
)

@Serializable
private data class HardwarePhysicalValidationDocument(
    val inventoryHash: String,
    val validatedBy: String,
    val evidenceSummary: String,
    val recordedAtEpochMillis: Long,
    val directionsAndPolarityTested: Boolean,
    val unitsAndSensorsTested: Boolean,
    val disabledNeutralTested: Boolean,
    val limitsAndCurrentTested: Boolean,
    val faultRecoveryTested: Boolean,
)

private data class HardwareReviewReadResult(
    val status: HardwareReviewStatus,
    val reviewedBy: String?,
    val physicalValidation: HardwarePhysicalValidationEvidence?,
)

private val HARDWARE_REVIEW_JSON = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = false
}

/**
 * Aggregates physical identity from canonical drivetrain and subsystem documents.
 *
 * This service never scans Kotlin and never creates a competing hardware map. The existing
 * descriptor builders remain the only editors of addresses, inversion, safe output, and limits.
 * A review records the exact descriptor hashes and becomes stale after any later edit.
 */
class HardwareSetupService(
    private val drivebaseRepository: DrivebaseProjectRepository = DrivebaseProjectRepository(),
    private val subsystemRepository: SubsystemProjectRepository = SubsystemProjectRepository(),
    private val commissioningVerificationService: CommissioningVerificationService = CommissioningVerificationService(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun inspect(projectPath: String, league: League): HardwareSetupSnapshot {
        val root = File(projectPath).canonicalFile
        require(root.isDirectory) { "Project directory does not exist: ${root.path}" }

        val issues = mutableListOf<HardwareInventoryIssue>()
        val items = mutableListOf<HardwareInventoryItem>()
        val sources = mutableListOf<HardwareSourceFingerprint>()
        val subsystemDocuments = mutableListOf<com.areslib.subsystem.SubsystemDocument>()

        drivebaseRepository.load(root.path).fold(
            onSuccess = { drivebase ->
                if (drivebase == null) {
                    issues += HardwareInventoryIssue(
                        HardwareIssueSeverity.ERROR,
                        "Configure a drivebase before reviewing physical hardware.",
                    )
                } else {
                    val canonical = requireNotNull(drivebase.canonical) {
                        "The loaded drivebase lost its canonical document. Reload it in Drivebase Builder."
                    }
                    val sourceFile = File(root, ".ares/drivetrains")
                        .listFiles { file -> file.isFile && file.extension.equals("aresdrivetrain", ignoreCase = true) }
                        .orEmpty()
                        .singleOrNull { file ->
                            runCatching { DrivetrainDocumentCodec.decode(file.readText()).uid == canonical.uid }
                                .getOrDefault(false)
                        }
                    requireNotNull(sourceFile) {
                        "The canonical drivetrain source for '${canonical.uid}' is missing or duplicated."
                    }
                    val sourcePath = ".ares/drivetrains/${sourceFile.name}"
                    sources += HardwareSourceFingerprint(sourcePath, DrivetrainDocumentCodec.contentHash(canonical))
                    val physicalComponentIds = canonical.components
                        .filterNot { it.role == DrivetrainComponentRole.WHEEL_MODULE }
                        .mapTo(mutableSetOf()) { it.uid }
                    drivebase.hardware
                        .filter { it.id in physicalComponentIds }
                        .forEach { device ->
                            val address = device.canId?.toString() ?: device.hardwareName.trim()
                            val addressKind = when (league) {
                                League.FTC -> HardwareAddressKind.FTC_HARDWARE_MAP
                                League.FRC -> HardwareAddressKind.CAN
                                League.XRP -> HardwareAddressKind.XRP_PORT
                            }
                            items += HardwareInventoryItem(
                                uid = "drivebase:${device.id}",
                                displayName = device.displayName,
                                owner = HardwareInventoryOwner.DRIVEBASE,
                                ownerDisplayName = drivebase.displayName,
                                sourcePath = sourcePath,
                                role = device.role.readableName(),
                                roleKey = device.role.name,
                                addressKind = addressKind,
                                address = address,
                                bus = device.canBus?.takeIf(String::isNotBlank),
                                required = device.required,
                                inverted = device.inverted,
                                configurationDetails = emptyList(),
                            )
                        }
                }
            },
            onFailure = { error ->
                issues += HardwareInventoryIssue(
                    HardwareIssueSeverity.ERROR,
                    error.message ?: "The drivetrain hardware document could not be loaded.",
                )
            },
        )

        val subsystemListing = subsystemRepository.list(root.path)
        subsystemListing.diagnostics.forEach { diagnostic ->
            issues += HardwareInventoryIssue(
                HardwareIssueSeverity.ERROR,
                "${diagnostic.file.name}: ${diagnostic.message}",
            )
        }
        subsystemListing.documents.forEach { subsystem ->
            subsystemDocuments += subsystem
            val sourcePath = ".ares/subsystems/${subsystem.documentId}.aressubsystem"
            sources += HardwareSourceFingerprint(sourcePath, SubsystemDocumentCodec.contentHash(subsystem))
            subsystem.hardware.forEach { device ->
                val controlStrategies = subsystem.controlLoops
                    .filter { it.actuatorId == device.hardwareId }
                    .map { it.strategy.name }
                    .distinct()
                val homing = subsystem.safety.homing.takeIf { it.actuatorId == device.hardwareId }
                val address = when (league) {
                    League.FTC -> device.connection.hardwareMapName.orEmpty().trim()
                    League.XRP -> when (device.kind) {
                        SubsystemHardwareKind.DISTANCE_SENSOR -> "built-in rangefinder"
                        SubsystemHardwareKind.IMU -> "built-in IMU"
                        else -> device.connection.channel?.toString().orEmpty()
                    }
                    League.FRC -> when (device.kind) {
                        SubsystemHardwareKind.QUADRATURE_ENCODER -> listOfNotNull(
                            device.connection.channel,
                            device.connection.secondaryChannel,
                        ).joinToString("/")
                        SubsystemHardwareKind.SOLENOID -> listOfNotNull(
                            device.connection.canId,
                            device.connection.channel,
                        ).joinToString("/")
                        SubsystemHardwareKind.IMU -> "onboard"
                        else -> device.connection.canId?.toString()
                            ?: device.connection.channel?.toString()
                            ?: ""
                    }
                }
                val addressKind = when (league) {
                    League.FTC -> HardwareAddressKind.FTC_HARDWARE_MAP
                    League.XRP -> HardwareAddressKind.XRP_PORT
                    League.FRC -> device.addressKind()
                }
                val bus = when {
                    league == League.FRC && addressKind == HardwareAddressKind.CAN -> device.connection.canBus
                    league == League.FRC && addressKind == HardwareAddressKind.PNEUMATICS ->
                        device.connection.pneumaticsModuleType?.name
                    else -> null
                }
                items += HardwareInventoryItem(
                    uid = "subsystem:${subsystem.documentId}:${device.uid}",
                    displayName = device.displayName,
                    owner = HardwareInventoryOwner.SUBSYSTEM,
                    ownerDisplayName = subsystem.displayName,
                    sourcePath = sourcePath,
                    role = device.kind.readableName(),
                    roleKey = device.kind.name,
                    addressKind = addressKind,
                    address = address,
                    bus = bus?.takeIf(String::isNotBlank),
                    required = device.required,
                    inverted = device.inverted,
                    configurationDetails = device.configurationDetails(),
                    isMotionActuator = device.kind in MOTION_ACTUATOR_KINDS,
                    isFollower = device.following != null,
                    safeOutput = device.safeOutput,
                    measurementFieldIds = device.measurements.map { it.fieldId },
                    controlStrategies = controlStrategies,
                    homingMethod = homing?.method?.name?.takeUnless { it == "NONE" },
                    homingEvidence = homing?.evidence.orEmpty().map { evidence ->
                        buildString {
                            append(evidence.fieldId).append(' ').append(evidence.comparison.name.lowercase().replace('_', ' '))
                            evidence.threshold?.let { append(' ').append(formatSetupNumber(it)) }
                        }
                    },
                    requiresCalibration = subsystem.safety.requiresCalibration && device.kind in MOTION_ACTUATOR_KINDS,
                    requiresCurrentMonitoring = subsystem.safety.requiresCurrentMonitoring && device.kind in MOTION_ACTUATOR_KINDS,
                )
            }
        }

        items.filter { it.required && it.address.isBlank() }.forEach { item ->
            issues += HardwareInventoryIssue(
                HardwareIssueSeverity.ERROR,
                "${item.displayName} is required but has no ${item.addressKind.label}.",
                item.uid,
            )
        }
        items.filter { it.address.isNotBlank() }
            .groupBy(::collisionKey)
            .filterValues { it.size > 1 }
            .values
            .forEach { conflicts ->
                val address = conflicts.first().addressDescription
                issues += HardwareInventoryIssue(
                    HardwareIssueSeverity.ERROR,
                    "$address is claimed by ${conflicts.joinToString { "${it.ownerDisplayName} / ${it.displayName}" }}. Physical addresses must have one owner.",
                )
            }
        if (items.isEmpty() && issues.none { it.severity == HardwareIssueSeverity.ERROR }) {
            issues += HardwareInventoryIssue(
                HardwareIssueSeverity.ERROR,
                "No physical hardware is declared in the canonical drivetrain or subsystem documents.",
            )
        }

        val normalizedItems = items.sortedWith(
            compareBy<HardwareInventoryItem> { it.owner.ordinal }
                .thenBy { it.ownerDisplayName.lowercase() }
                .thenBy { it.displayName.lowercase() }
                .thenBy { it.uid },
        )
        val normalizedSources = sources.distinctBy(HardwareSourceFingerprint::path).sortedBy(HardwareSourceFingerprint::path)
        val inventoryHash = inventoryHash(league, normalizedSources, normalizedItems)
        val review = readReview(root, league, inventoryHash, normalizedSources, issues)
        val simulationVerification = commissioningVerificationService.verify(subsystemDocuments)

        return HardwareSetupSnapshot(
            projectPath = root.path,
            league = league,
            inventoryHash = inventoryHash,
            items = normalizedItems,
            issues = issues.distinct().sortedWith(
                compareByDescending<HardwareInventoryIssue> { it.severity.ordinal }.thenBy { it.message },
            ),
            reviewStatus = review.status,
            reviewedBy = review.reviewedBy,
            simulationVerification = simulationVerification,
            physicalValidation = review.physicalValidation,
        )
    }

    fun saveReview(projectPath: String, league: League, request: HardwareReviewRequest): HardwareSetupSnapshot {
        val snapshot = inspect(projectPath, league)
        require(snapshot.canReview) {
            snapshot.errorIssues.joinToString(" ") { it.message }.ifBlank { "Fix hardware mapping errors before recording a review." }
        }
        val reviewer = request.reviewerName.trim()
        require(reviewer.length in 2..80) { "Enter the name of the team member who compared the configuration with the robot." }
        require(
            request.wiringMatched && request.addressesChecked && request.directionsChecked &&
                request.neutralOutputsChecked && request.limitsChecked,
        ) { "Complete every hardware review check before recording the review." }

        val sourcePaths = snapshot.items.map(HardwareInventoryItem::sourcePath).distinct().sorted()
        val sources = sourcePaths.map { path ->
            val file = File(snapshot.projectPath, path).canonicalFile
            require(file.isFile && file.toPath().startsWith(File(snapshot.projectPath).canonicalFile.toPath())) {
                "Hardware source $path is missing or outside the project."
            }
            sourceFingerprint(path, file)
        }
        val review = HardwareReviewDocument(
            league = league.name,
            inventoryHash = snapshot.inventoryHash,
            reviewedBy = reviewer,
            wiringMatched = true,
            addressesChecked = true,
            directionsChecked = true,
            neutralOutputsChecked = true,
            limitsChecked = true,
            sources = sources,
            recordedAtEpochMillis = clock.millis(),
        )
        appendEvidence(configurationReviewDirectory(File(snapshot.projectPath)), review.recordedAtEpochMillis, review)
        return inspect(projectPath, league)
    }

    fun savePhysicalValidation(
        projectPath: String,
        league: League,
        request: HardwarePhysicalValidationRequest,
    ): HardwareSetupSnapshot {
        val snapshot = inspect(projectPath, league)
        require(snapshot.reviewStatus == HardwareReviewStatus.CURRENT) {
            "Record a current configuration review before physical validation."
        }
        require(snapshot.simulationVerification.verified) {
            "Resolve deterministic commissioning simulation failures before physical validation."
        }
        val validator = request.validatedBy.trim()
        val evidence = request.evidenceSummary.trim()
        require(validator.length in 2..80) { "Enter the team member who performed the physical checks." }
        require(evidence.length in 20..1_000) {
            "Describe the robot, procedure, observed result, and any remaining limitation (20–1,000 characters)."
        }
        require(
            request.directionsAndPolarityTested && request.unitsAndSensorsTested && request.disabledNeutralTested &&
                request.limitsAndCurrentTested && request.faultRecoveryTested,
        ) { "Complete every supervised physical-validation check before recording evidence." }

        val validation = HardwarePhysicalValidationDocument(
            inventoryHash = snapshot.inventoryHash,
            validatedBy = validator,
            evidenceSummary = evidence,
            recordedAtEpochMillis = clock.millis(),
            directionsAndPolarityTested = true,
            unitsAndSensorsTested = true,
            disabledNeutralTested = true,
            limitsAndCurrentTested = true,
            faultRecoveryTested = true,
        )
        appendEvidence(physicalValidationDirectory(File(snapshot.projectPath)), validation.recordedAtEpochMillis, validation)
        return inspect(projectPath, league)
    }

    /** Deployment requirement used only by templates that explicitly opt into reviewed hardware. */
    fun deploymentBlockReason(projectPath: String, league: League): String? {
        val snapshot = runCatching { inspect(projectPath, league) }.getOrElse { error ->
            return "Hardware configuration could not be inspected: ${error.message}. Deployment is blocked."
        }
        if (snapshot.errorIssues.isNotEmpty()) {
            return "Hardware configuration has ${snapshot.errorIssues.size} blocking issue(s). Open Hardware Setup and resolve them before deployment."
        }
        return when (snapshot.reviewStatus) {
            HardwareReviewStatus.CURRENT -> null
            HardwareReviewStatus.NOT_REVIEWED ->
                "Hardware mapping has not been compared with the physical robot. Complete Hardware Setup before deployment."
            HardwareReviewStatus.STALE ->
                "Hardware mapping changed after its last review. Review the current addresses, directions, neutral outputs, and limits again before deployment."
            HardwareReviewStatus.INVALID ->
                "The hardware review record is invalid. Open Hardware Setup and create a new reviewed record before deployment."
        }
    }

    private fun readReview(
        root: File,
        league: League,
        inventoryHash: String,
        currentSources: List<HardwareSourceFingerprint>,
        issues: MutableList<HardwareInventoryIssue>,
    ): HardwareReviewReadResult {
        val reviewFiles = configurationReviewDirectory(root).listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedByDescending(File::getName)
            .orEmpty()
        if (reviewFiles.isEmpty()) return HardwareReviewReadResult(HardwareReviewStatus.NOT_REVIEWED, null, null)
        val decodedReviews = reviewFiles.mapNotNull { file ->
            runCatching { HARDWARE_REVIEW_JSON.decodeFromString<HardwareReviewDocument>(file.readText()) }
                .onFailure { error ->
                    issues += HardwareInventoryIssue(
                        HardwareIssueSeverity.WARNING,
                        "${root.toPath().relativize(file.toPath())} is invalid: ${error.message}",
                    )
                }
                .getOrNull()
        }
        val validReviews = decodedReviews.filter { review ->
            review.schemaVersion == 3 && review.league == league.name && review.reviewedBy.isNotBlank() &&
                review.wiringMatched && review.addressesChecked && review.directionsChecked &&
                review.neutralOutputsChecked && review.limitsChecked && review.recordedAtEpochMillis > 0L
        }
        if (validReviews.isEmpty()) {
            issues += HardwareInventoryIssue(
                HardwareIssueSeverity.WARNING,
                "No append-only hardware configuration review contains complete evidence for ${league.name}.",
            )
            return HardwareReviewReadResult(HardwareReviewStatus.INVALID, null, null)
        }
        val currentReview = validReviews.firstOrNull { review ->
            review.inventoryHash == inventoryHash && review.sources.sortedBy(HardwareSourceFingerprint::path) == currentSources
        }
        return if (currentReview != null) {
            val physical = physicalValidationDirectory(root)
                .listFiles { file -> file.isFile && file.extension == "json" }
                ?.sortedByDescending(File::getName)
                .orEmpty()
                .asSequence()
                .mapNotNull { file ->
                    runCatching { HARDWARE_REVIEW_JSON.decodeFromString<HardwarePhysicalValidationDocument>(file.readText()) }
                        .getOrNull()
                }
                .firstOrNull { validation ->
                validation.inventoryHash == inventoryHash && validation.validatedBy.isNotBlank() &&
                    validation.evidenceSummary.length >= 20 && validation.recordedAtEpochMillis > 0L &&
                    validation.directionsAndPolarityTested && validation.unitsAndSensorsTested &&
                    validation.disabledNeutralTested && validation.limitsAndCurrentTested && validation.faultRecoveryTested
                }
                ?.let { validation ->
                HardwarePhysicalValidationEvidence(
                    inventoryHash = validation.inventoryHash,
                    validatedBy = validation.validatedBy,
                    evidenceSummary = validation.evidenceSummary,
                    recordedAtEpochMillis = validation.recordedAtEpochMillis,
                )
            }
            HardwareReviewReadResult(HardwareReviewStatus.CURRENT, currentReview.reviewedBy, physical)
        } else {
            HardwareReviewReadResult(HardwareReviewStatus.STALE, validReviews.first().reviewedBy, null)
        }
    }

    private fun collisionKey(item: HardwareInventoryItem): String = when (item.addressKind) {
        HardwareAddressKind.FTC_HARDWARE_MAP -> "ftc:${item.address.lowercase()}"
        HardwareAddressKind.XRP_PORT -> "xrp:${item.address.lowercase()}"
        HardwareAddressKind.CAN -> "can:${item.bus.orEmpty().lowercase()}:${item.address}"
        HardwareAddressKind.PWM -> "pwm:${item.address}"
        HardwareAddressKind.I2C -> "i2c:${item.address.lowercase()}"
        HardwareAddressKind.DIO -> "dio:${item.address}"
        HardwareAddressKind.ANALOG -> "analog:${item.address}"
        HardwareAddressKind.SPI -> "spi:${item.address.lowercase()}"
        HardwareAddressKind.PNEUMATICS -> "pneumatics:${item.bus.orEmpty().lowercase()}:${item.address}"
        HardwareAddressKind.UNKNOWN -> "unknown:${item.address.lowercase()}"
    }

    private fun inventoryHash(
        league: League,
        sources: List<HardwareSourceFingerprint>,
        items: List<HardwareInventoryItem>,
    ): String {
        val canonical = buildString {
            append("hardware-inventory-v1\n")
            append(league.name).append('\n')
            sources.forEach { append(it.path).append('=').append(it.sha256).append('\n') }
            items.forEach { item ->
                append(item.uid).append('|')
                append(item.owner.name).append('|')
                append(item.addressKind.name).append('|')
                append(item.roleKey).append('|')
                append(item.address).append('|')
                append(item.bus.orEmpty()).append('|')
                append(item.required).append('|')
                append(item.inverted).append('|')
                append(item.configurationDetails.joinToString(";")).append('\n')
            }
        }
        return Sha256.hex(canonical)
    }

    private fun configurationReviewDirectory(root: File): File = File(root, ".ares/evidence/hardware/configuration")

    private fun physicalValidationDirectory(root: File): File = File(root, ".ares/evidence/hardware/physical")

    private inline fun <reified T> appendEvidence(directory: File, recordedAtEpochMillis: Long, document: T) {
        val encoded = HARDWARE_REVIEW_JSON.encodeToString(document).trimEnd() + System.lineSeparator()
        val hash = Sha256.hex(encoded).take(12)
        val target = File(directory, "$recordedAtEpochMillis-$hash.json")
        require(!target.exists()) { "This exact evidence record already exists; append-only evidence is never replaced." }
        writeFileAtomically(target) { temporary ->
            Files.writeString(
                temporary.toPath(),
                encoded,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }
    }

    private fun sourceFingerprint(path: String, file: File): HardwareSourceFingerprint {
        val hash = when {
            file.extension.equals("aresdrivetrain", ignoreCase = true) ->
                DrivetrainDocumentCodec.contentHash(DrivetrainDocumentCodec.decode(file.readText()))
            file.extension.equals("aressubsystem", ignoreCase = true) ->
                SubsystemDocumentCodec.contentHash(SubsystemDocumentCodec.decode(file.readText()))
            else -> error("Unsupported hardware source $path")
        }
        return HardwareSourceFingerprint(path, hash)
    }

}
