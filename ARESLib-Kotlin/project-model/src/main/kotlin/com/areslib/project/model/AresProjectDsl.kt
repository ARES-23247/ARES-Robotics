package com.areslib.project.model

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.ConditionDescriptor
import com.areslib.catalog.ResourceClaim
import com.areslib.controls.ControlSchemeBuilder
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileDocument
import com.areslib.controls.controlScheme as buildControlScheme
import com.areslib.controls.toKotlinDsl
import com.areslib.drivetrain.DrivetrainBuilder
import com.areslib.drivetrain.DrivetrainDocument
import com.areslib.drivetrain.frcCtreSwerveDrivetrain as buildFrcCtreSwerveDrivetrain
import com.areslib.drivetrain.ftcMecanumDrivetrain as buildFtcMecanumDrivetrain
import com.areslib.drivetrain.toKotlinDsl
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresFtcHubCommandTransport
import com.areslib.project.AresFtcRuntimeOptionsDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectAuthoringModel
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresRuntimeOptionsDocument
import com.areslib.project.AresXrpControllerModel
import com.areslib.project.AresXrpRuntimeOptionsDocument
import com.areslib.routine.AutonomousCatalogBuilder
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.RoutineBuilder
import com.areslib.routine.RoutineDocument
import com.areslib.routine.autonomousCatalog as buildAutonomousCatalog
import com.areslib.routine.routine as buildRoutine
import com.areslib.routine.toKotlinDsl
import com.areslib.state.RobotFieldConfig
import com.areslib.subsystem.SubsystemBuilder
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.subsystem as buildSubsystem
import com.areslib.subsystem.toKotlinDsl
import com.areslib.superstructure.SuperstructureBuilder
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.TransitionTriggerKind
import com.areslib.superstructure.superstructure as buildSuperstructure
import com.areslib.superstructure.toKotlinDsl
import com.areslib.tuning.TuningComponentDocument
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.toKotlinDsl

@DslMarker
public annotation class AresProjectDsl

/**
 * Assembles and cross-document-validates an entire [EffectiveRobotProject] in pure Kotlin using the
 * ARES Text DSL.
 *
 * Cross-document validation runs via [RobotProjectAssembler.assemble], checking that:
 * - Every subsystem, drivetrain, tuning component, and tuning profile matches [league] and [projectId].
 * - Every superstructure port references a real subsystem and state field.
 * - Every routine action, condition, and sub-routine call exists in the effective project catalog.
 * - Every control scheme binding targets a valid action, routine, or drive axis.
 */
public fun aresProject(
    projectId: String,
    league: AresLeague,
    projectRoot: String = ".",
    inputPlatform: ControllerInputPlatform? = null,
    block: AresProjectBuilder.() -> Unit,
): EffectiveRobotProject =
    AresProjectBuilder(projectId, league, projectRoot).apply(block).build(inputPlatform)

@AresProjectDsl
public class AresProjectBuilder internal constructor(
    public val projectId: String,
    public val league: AresLeague,
    public val projectRoot: String,
) {
    private var identity = AresProjectIdentityDocument("0000", "2026", projectId, projectId)
    private var coordinateConvention = when (league) {
        AresLeague.FTC, AresLeague.XRP -> AresCoordinateConvention.CENTER_ORIGIN_CCW
        AresLeague.FRC -> AresCoordinateConvention.BLUE_CORNER_ORIGIN_CCW
    }
    private var robotLengthMeters = when (league) {
        AresLeague.FTC -> 0.4572
        AresLeague.FRC -> 0.85
        AresLeague.XRP -> 0.19
    }
    private var robotWidthMeters = robotLengthMeters
    private var fieldLengthMeters = when (league) {
        AresLeague.FTC -> 3.6576
        AresLeague.FRC -> 16.541
        AresLeague.XRP -> 1.80
    }
    private var fieldWidthMeters = when (league) {
        AresLeague.FTC -> 3.6576
        AresLeague.FRC -> 8.211
        AresLeague.XRP -> 1.80
    }
    public var authoringModel: AresProjectAuthoringModel = AresProjectAuthoringModel.CODE_FIRST

    private var runtimeOptions = when (league) {
        AresLeague.FTC -> AresRuntimeOptionsDocument(ftc = AresFtcRuntimeOptionsDocument())
        AresLeague.FRC -> AresRuntimeOptionsDocument()
        AresLeague.XRP -> AresRuntimeOptionsDocument(
            xrp = AresXrpRuntimeOptionsDocument(AresXrpControllerModel.SPARKFUN_XRP_RP2350)
        )
    }

    private var catalogRevision = 1
    private val actions = mutableListOf<ActionDescriptor>()
    private val conditions = mutableListOf<ConditionDescriptor>()
    private var autonomousCatalog: AutonomousCatalogDocument? = null
    private val routines = mutableListOf<RoutineDocument>()
    private val controlSchemes = mutableListOf<ControlSchemeDocument>()
    private val controllerProfiles = mutableListOf<ControllerProfileDocument>()
    private val subsystems = mutableListOf<SubsystemDocument>()
    private val superstructures = mutableListOf<SuperstructureDocument>()
    private val drivetrains = mutableListOf<DrivetrainDocument>()
    private var fieldConfig: RobotFieldConfig? = null
    private val tuningComponents = mutableListOf<TuningComponentDocument>()
    private val tuningProfiles = mutableListOf<TuningProfileDocument>()

    /** Imports canonical metadata without resetting geometry or runtime options. */
    public fun metadata(document: AresProjectMetadataDocument) {
        require(document.projectId == projectId && document.league == league)
        require(document.schemaVersion == com.areslib.project.ARES_PROJECT_METADATA_SCHEMA_VERSION)
        identity = document.identity
        coordinateConvention = document.coordinateConvention
        robotLengthMeters = document.robotLengthMeters
        robotWidthMeters = document.robotWidthMeters
        fieldLengthMeters = document.fieldLengthMeters
        fieldWidthMeters = document.fieldWidthMeters
        authoringModel = document.authoringModel
        runtimeOptions = document.runtimeOptions
    }

    /** Imports the authored catalog, retaining explicit action parameters and its revision. */
    public fun capabilityCatalog(document: CapabilityCatalogDocument) {
        require(document.projectId == projectId)
        require(document.schemaVersion == com.areslib.catalog.ARES_CAPABILITY_CATALOG_SCHEMA_VERSION)
        catalogRevision = document.revision
        actions.clear()
        actions.addAll(document.actions)
        conditions.clear()
        conditions.addAll(document.conditions)
    }

    /** Sets the robot and team competition identity stored in `.ares/project.json`. */
    public fun identity(
        teamId: String,
        seasonId: String,
        robotId: String = projectId,
        displayName: String = robotId,
    ) {
        identity = AresProjectIdentityDocument(teamId, seasonId, robotId, displayName)
    }

    /** Overrides the robot footprint and field dimensions for collision/boundary checking. */
    public fun geometry(
        robotLengthMeters: Double,
        robotWidthMeters: Double,
        fieldLengthMeters: Double = this.fieldLengthMeters,
        fieldWidthMeters: Double = this.fieldWidthMeters,
        coordinateConvention: AresCoordinateConvention = this.coordinateConvention,
    ) {
        this.robotLengthMeters = robotLengthMeters
        this.robotWidthMeters = robotWidthMeters
        this.fieldLengthMeters = fieldLengthMeters
        this.fieldWidthMeters = fieldWidthMeters
        this.coordinateConvention = coordinateConvention
    }

    /** Configures FTC-specific runtime options. */
    public fun ftcRuntime(
        hubCommandTransport: AresFtcHubCommandTransport = AresFtcHubCommandTransport.STANDARD_SDK,
        limelightProxyEnabled: Boolean = false,
    ) {
        runtimeOptions = AresRuntimeOptionsDocument(
            ftc = AresFtcRuntimeOptionsDocument(hubCommandTransport, limelightProxyEnabled)
        )
    }

    /** Configures XRP-specific runtime options. */
    public fun xrpRuntime(
        controllerModel: AresXrpControllerModel = AresXrpControllerModel.SPARKFUN_XRP_RP2350,
        wifiMode: String = "AP",
        ssid: String = "ARES-XRP-AUTO",
        port: Int = 5811,
        deadmanTimeoutMs: Int = 200,
        brownoutThresholdVolts: Double = 4.3,
    ) {
        runtimeOptions = AresRuntimeOptionsDocument(
            xrp = AresXrpRuntimeOptionsDocument(controllerModel, wifiMode, ssid, port, deadmanTimeoutMs, brownoutThresholdVolts)
        )
    }

    /** Declares an explicit capability action in the project's base capability catalog. */
    public fun action(
        key: String,
        displayName: String = key,
        description: String = "Action $key",
        category: String = "General",
        parameters: List<CapabilityParameterDescriptor> = emptyList(),
        resources: List<ResourceClaim> = emptyList(),
    ) {
        actions += ActionDescriptor(key, displayName, description, category, parameters, resources)
    }

    /** Declares an explicit boolean condition in the project's base capability catalog. */
    public fun condition(
        key: String,
        displayName: String = key,
        description: String = "Condition $key",
        category: String = "Conditions",
    ) {
        conditions += ConditionDescriptor(key, displayName, description, category)
    }

    /** Registers a pre-built [DrivetrainDocument] in this project. */
    public fun drivetrain(document: DrivetrainDocument): DrivetrainDocument = document.also { drivetrains += it }

    /** Builds and registers an FTC 4-motor mecanum [DrivetrainDocument] in this project. */
    public fun ftcMecanumDrivetrain(
        id: String = "main-drive",
        displayName: String? = null,
        block: DrivetrainBuilder.() -> Unit = {},
    ): DrivetrainDocument = drivetrain(
        if (displayName == null) buildFtcMecanumDrivetrain(id = id, block = block)
        else buildFtcMecanumDrivetrain(id = id, displayName = displayName, block = block)
    )

    /** Builds and registers an FRC 4-module CTRE swerve [DrivetrainDocument] in this project. */
    public fun frcCtreSwerveDrivetrain(
        id: String = "swerve",
        vendorSourcePath: String,
        sourceSha256: String,
        drivetrainConstantsClassName: String,
        canBusName: String = "CANivore",
        block: DrivetrainBuilder.() -> Unit = {},
    ): DrivetrainDocument = drivetrain(
        buildFrcCtreSwerveDrivetrain(
            id = id,
            vendorSourcePath = vendorSourcePath,
            sourceSha256 = sourceSha256,
            drivetrainConstantsClassName = drivetrainConstantsClassName,
            canBusName = canBusName,
            block = block,
        )
    )

    /** Registers a pre-built [SubsystemDocument] in this project. */
    public fun subsystem(document: SubsystemDocument): SubsystemDocument = document.also { subsystems += it }

    /** Builds and registers a [SubsystemDocument] in this project. */
    public fun subsystem(
        documentId: String,
        platform: SubsystemPlatform = defaultSubsystemPlatform(),
        block: SubsystemBuilder.() -> Unit,
    ): SubsystemDocument = subsystem(buildSubsystem(documentId = documentId, platform = platform, block = block))

    /** Registers a pre-built [SuperstructureDocument] in this project. */
    public fun superstructure(document: SuperstructureDocument): SuperstructureDocument = document.also { superstructures += it }

    /** Builds and registers a [SuperstructureDocument] in this project. */
    public fun superstructure(
        id: String,
        initialStateId: String,
        faultStateId: String,
        displayName: String? = null,
        block: SuperstructureBuilder.() -> Unit,
    ): SuperstructureDocument = superstructure(
        if (displayName == null) buildSuperstructure(id = id, initialStateId = initialStateId, faultStateId = faultStateId, block = block)
        else buildSuperstructure(id = id, initialStateId = initialStateId, faultStateId = faultStateId, displayName = displayName, block = block)
    )

    /** Registers a pre-built [RoutineDocument] in this project. */
    public fun routine(document: RoutineDocument): RoutineDocument = document.also { routines += it }

    /** Builds and registers a [RoutineDocument] in this project. */
    public fun routine(
        id: String,
        name: String? = null,
        block: RoutineBuilder.() -> Unit,
    ): RoutineDocument = routine(
        if (name == null) buildRoutine(id = id, block = block) else buildRoutine(id = id, name = name, block = block)
    )

    /** Registers a pre-built [AutonomousCatalogDocument] in this project. */
    public fun autonomousCatalog(document: AutonomousCatalogDocument): AutonomousCatalogDocument = document.also { autonomousCatalog = it }

    /** Builds and registers the project's [AutonomousCatalogDocument]. */
    public fun autonomousCatalog(
        defaultEntryId: String? = null,
        block: AutonomousCatalogBuilder.() -> Unit,
    ): AutonomousCatalogDocument = autonomousCatalog(
        buildAutonomousCatalog(projectId = projectId, defaultEntryId = defaultEntryId, block = block)
    )

    /** Registers a pre-built [ControlSchemeDocument] in this project. */
    public fun controlScheme(document: ControlSchemeDocument): ControlSchemeDocument = document.also { controlSchemes += it }

    /** Builds and registers a [ControlSchemeDocument] in this project. */
    public fun controlScheme(
        id: String,
        name: String? = null,
        block: ControlSchemeBuilder.() -> Unit,
    ): ControlSchemeDocument = controlScheme(
        if (name == null) buildControlScheme(id = id, block = block)
        else buildControlScheme(id = id, name = name, block = block)
    )

    /** Registers a custom [ControllerProfileDocument] in this project. */
    public fun controllerProfile(document: ControllerProfileDocument): ControllerProfileDocument = document.also { controllerProfiles += it }

    /** Registers a [TuningComponentDocument] in this project. */
    public fun tuningComponent(document: TuningComponentDocument): TuningComponentDocument = document.also { tuningComponents += it }

    /** Registers a [TuningProfileDocument] in this project. */
    public fun tuningProfile(document: TuningProfileDocument): TuningProfileDocument = document.also { tuningProfiles += it }

    /** Sets the optional [RobotFieldConfig] for this project. */
    public fun field(config: RobotFieldConfig) {
        fieldConfig = config
    }

    private fun defaultSubsystemPlatform(): SubsystemPlatform = when (league) {
        AresLeague.FTC -> SubsystemPlatform.FTC
        AresLeague.FRC -> SubsystemPlatform.FRC
        AresLeague.XRP -> SubsystemPlatform.XRP
    }

    internal fun build(inputPlatform: ControllerInputPlatform?): EffectiveRobotProject {
        val metadata = AresProjectMetadataDocument(
            projectId = projectId,
            identity = identity,
            league = league,
            coordinateConvention = coordinateConvention,
            robotLengthMeters = robotLengthMeters,
            robotWidthMeters = robotWidthMeters,
            fieldLengthMeters = fieldLengthMeters,
            fieldWidthMeters = fieldWidthMeters,
            authoringModel = authoringModel,
            runtimeOptions = runtimeOptions,
        )

        val explicitActionKeys = actions.mapTo(linkedSetOf()) { it.key }
        val superstructureActionDescriptors = superstructures
            .flatMap { doc ->
                doc.transitions
                    .filter { it.triggerKind == TransitionTriggerKind.ACTION_REQUEST }
                    .mapNotNull { it.actionKey }
            }
            .distinct()
            .filter { it !in explicitActionKeys }
            .map { key ->
                ActionDescriptor(
                    key = key,
                    displayName = key,
                    description = "Superstructure transition request $key",
                    category = "Superstructure",
                )
            }

        val catalog = CapabilityCatalogDocument(
            projectId = projectId,
            revision = catalogRevision,
            actions = actions + superstructureActionDescriptors,
            conditions = conditions.toList(),
        )

        val snapshot = RobotProjectSnapshot(
            projectRoot = projectRoot,
            metadata = metadata,
            baseCapabilityCatalog = catalog,
            autonomousCatalog = autonomousCatalog,
            routines = routines.toList(),
            controlSchemes = controlSchemes.toList(),
            controllerProfiles = controllerProfiles.toList(),
            subsystems = subsystems.toList(),
            superstructures = superstructures.toList(),
            drivetrains = drivetrains.toList(),
            field = fieldConfig,
            tuningComponents = tuningComponents.toList(),
            tuningProfiles = tuningProfiles.toList(),
        )
        return RobotProjectAssembler.assemble(snapshot, inputPlatform)
    }
}

/**
 * Formats all cross-document validation issues in this [EffectiveRobotProject] into a clear,
 * human-readable diagnostic report suitable for console output or unit test assertions.
 */
public fun EffectiveRobotProject.formatValidationReport(): String = buildString {
    val errorCount = issues.count { it.severity == ProjectModelSeverity.ERROR }
    val warningCount = issues.count { it.severity == ProjectModelSeverity.WARNING }
    val pid = projectId?.value ?: raw.metadata?.projectId ?: "unknown-project"
    if (issues.isEmpty()) {
        append("ARES Project '$pid' is valid (0 errors, 0 warnings).")
        return@buildString
    }
    appendLine("ARES Project '$pid' validation report ($errorCount error(s), $warningCount warning(s)):")
    issues.forEach { issue ->
        val docPart = issue.documentId?.let { "($it)" } ?: ""
        appendLine("  - [${issue.severity}] ${issue.kind}$docPart at '${issue.path}' [${issue.code}]: ${issue.message}")
    }
}.trimEnd()

/**
 * Renders this entire [EffectiveRobotProject] into a self-contained, copy-pasteable Kotlin Text DSL
 * specification (`aresProject(...) { ... }`).
 */
public fun EffectiveRobotProject.toKotlinDsl(): String = buildString {
    val meta = raw.metadata
    val pid = projectId?.value ?: meta?.projectId ?: "robot-project"
    val league = meta?.league ?: AresLeague.FTC
    appendLine("aresProject(${pid.quote()}, AresLeague.${league.name}, projectRoot = ${raw.projectRoot.quote()}) {")
    if (meta != null) {
        appendLine("    metadata(com.areslib.project.AresProjectMetadataCodec.decode(${com.areslib.project.AresProjectMetadataCodec.encode(meta).quote()}))")
    }
    raw.baseCapabilityCatalog?.let { doc ->
        appendLine("    capabilityCatalog(com.areslib.catalog.CapabilityCatalogCodec.decode(${com.areslib.catalog.CapabilityCatalogCodec.encode(doc).quote()}))")
    }
    raw.controllerProfiles.forEach { doc ->
        appendLine("    controllerProfile(com.areslib.controls.ControllerProfileCodec.decode(${com.areslib.controls.ControllerProfileCodec.encode(doc).quote()}))")
    }
    raw.tuningComponents.forEach { doc ->
        appendLine("    tuningComponent(com.areslib.tuning.TuningComponentDocumentCodec.decode(${com.areslib.tuning.TuningComponentDocumentCodec.encode(doc).quote()}))")
    }
    raw.field?.let { doc ->
        appendLine("    field(com.areslib.state.RobotFieldDocument.decode(${com.areslib.state.RobotFieldDocument.encode(doc).quote()}))")
    }
    drivetrains.values.forEach { doc ->
        val rendered = doc.toKotlinDsl()
        val expression = if (rendered.startsWith("com.")) rendered else "com.areslib.drivetrain.$rendered"
        appendLine().appendLine("    drivetrain(\n${expression.prependIndent("        ")}\n    )")
    }
    subsystems.values.forEach { doc -> appendLine().appendLine("    subsystem(\n${("com.areslib.subsystem." + doc.toKotlinDsl()).prependIndent("        ")}\n    )") }
    superstructures.values.forEach { doc -> appendLine().appendLine("    superstructure(\n${("com.areslib.superstructure." + doc.toKotlinDsl()).prependIndent("        ")}\n    )") }
    routines.values.forEach { doc -> appendLine().appendLine("    routine(\n${("com.areslib.routine." + doc.toKotlinDsl()).prependIndent("        ")}\n    )") }
    raw.autonomousCatalog?.let { doc -> appendLine().appendLine("    autonomousCatalog(\n${("com.areslib.routine." + doc.toKotlinDsl()).prependIndent("        ")}\n    )") }
    controlSchemes.values.forEach { doc -> appendLine().appendLine("    controlScheme(\n${("com.areslib.controls." + doc.toKotlinDsl()).prependIndent("        ")}\n    )") }
    raw.tuningProfiles.forEach { doc -> appendLine().appendLine("    tuningProfile(\n${("com.areslib.tuning." + doc.toKotlinDsl()).prependIndent("        ")}\n    )") }
    append("}")
}

private fun String.quote(): String = buildString {
    append('"')
    for (ch in this@quote) {
        when (ch) {
            '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n")
            '\r' -> append("\\r"); '\t' -> append("\\t"); '$' -> append("\\$")
            else -> append(ch)
        }
    }
    append('"')
}
