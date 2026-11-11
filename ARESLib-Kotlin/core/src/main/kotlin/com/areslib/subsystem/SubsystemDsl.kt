package com.areslib.subsystem

import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningValue

/** Keeps nested subsystem builders from accidentally mutating a parent receiver. */
@DslMarker
annotation class AresSubsystemDsl

// Retain the historical public copy() ABI even though construction is limited to the DSL.
@ExposedCopyVisibility
data class SubsystemFieldRef internal constructor(val id: String)

// Retain the historical public copy() ABI even though construction is limited to the DSL.
@ExposedCopyVisibility
data class SubsystemHardwareRef internal constructor(val id: String)

/**
 * Converts a camelCase, snake_case, or kebab-case identifier into a human-readable display name.
 *
 * Examples:
 * - `"targetMeters"` -> `"Target Meters"`
 * - `"leader_motor"` -> `"Leader Motor"`
 */
internal fun defaultSubsystemDisplayName(id: String): String {
    val spaced = id
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
    if (spaced.isEmpty()) return id
    return spaced.split(Regex("\\s+")).joinToString(" ") { token ->
        token.replaceFirstChar { it.uppercase() }
    }
}

/** Converts a kebab-case or snake_case document ID (e.g. `"intake-wrist"`) into PascalCase (`"IntakeWrist"`). */
internal fun defaultSubsystemKotlinTypeName(documentId: String): String =
    documentId.split('-', '_').filter { it.isNotEmpty() }.joinToString("") { token ->
        token.replaceFirstChar { it.uppercase() }
    }

/**
 * Declarative entry point for defining a validated [SubsystemDocument] (Schema v11) in pure Kotlin.
 *
 * Used by both Studio code generation and text-first mechanism authoring. Every call validates the
 * resulting [SubsystemDocument] against [SubsystemSchema] before returning, failing fast with clear
 * path-prefixed diagnostics if a hardware connection, unit contract, or safety invariant is invalid.
 *
 * ### Concise Text-First Example
 * ```kotlin
 * val elevator = subsystem("elevator", SubsystemPlatform.FTC) {
 *     val target = state.targetDouble("targetMeters", default = 0.0, unit = "m", minimum = 0.0, maximum = 1.2)
 *     val position = state.measurementDouble("positionMeters", default = 0.0, unit = "m")
 *     val leader = hardware.motor("elevator") {
 *         measurement(position, SubsystemMeasurementSource.MOTOR_POSITION_NATIVE, scale = 0.0005)
 *     }
 *     control.profiledPositionPid("liftPid", leader, target, position) {
 *         kP = 8.0
 *         maximumVelocity = 0.8
 *         maximumAcceleration = 1.5
 *     }
 * }
 * ```
 *
 * ### Explicit Display-Name Example (Studio / Codegen Compatible)
 * ```kotlin
 * val elevator = subsystem("elevator", "Elevator", SubsystemPlatform.FTC) {
 *     val target = state.double("targetMeters", "Target", SubsystemFieldRole.TARGET, 0.0, "m")
 *     val position = state.double("positionMeters", "Position", SubsystemFieldRole.MEASUREMENT, 0.0, "m")
 *     val leader = hardware.motor("leader", "Leader motor") { hardwareMapName = "elevator" }
 *     control.positionPid("position", "Position", leader, target, position) { kP = 8.0 }
 * }
 * ```
 */
fun subsystem(
    documentId: String,
    kotlinTypeName: String,
    platform: SubsystemPlatform,
    block: SubsystemBuilder.() -> Unit,
): SubsystemDocument = SubsystemBuilder(documentId, kotlinTypeName, platform).apply(block).build()

/**
 * Convenience overload for text-first authoring that derives `kotlinTypeName` in PascalCase from [documentId]
 * (for example, `"arm-pivot"` -> `"ArmPivot"`).
 */
fun subsystem(
    documentId: String,
    platform: SubsystemPlatform,
    kotlinTypeName: String = defaultSubsystemKotlinTypeName(documentId),
    block: SubsystemBuilder.() -> Unit,
): SubsystemDocument = SubsystemBuilder(documentId, kotlinTypeName, platform).apply(block).build()

@AresSubsystemDsl
class SubsystemBuilder internal constructor(
    private val documentId: String,
    private val kotlinTypeName: String,
    private val platform: SubsystemPlatform,
) {
    var displayName: String = kotlinTypeName.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
    var description: String = ""
    var template: SubsystemTemplate = SubsystemTemplate.ADVANCED_CUSTOM
    var requiredAtStartup: Boolean = true
    var generateMockIo: Boolean = true
    var generateTest: Boolean = true
    var autonomousResourceKey: String? = null
    private val capabilityActionKeys = mutableListOf<String>()
    private val interlockEntries = mutableListOf<SubsystemInterlockDocument>()
    private val tuningParameterEntries = mutableListOf<TuningParameterDeclaration>()

    val state = SubsystemStateBuilder()
    val hardware = SubsystemHardwareBuilder(platform)
    val control = SubsystemControlBuilder()
    val safety = SubsystemSafetyBuilder()
    val implementation = SubsystemImplementationBuilder()
    val linkage = SubsystemLinkageBuilder()

    /** Configures multi-joint linkage geometry and dynamics for 2-DOF mechanisms. */
    fun linkage(block: SubsystemLinkageBuilder.() -> Unit) {
        linkage.enabled = true
        linkage.apply(block)
    }

    /** Configures safety timeouts, homing, and automatic jam/fault recovery. */
    fun safety(block: SubsystemSafetyBuilder.() -> Unit) {
        safety.apply(block)
    }

    /** Configures implementation ownership, user-owned source paths, and simulator interaction. */
    fun implementation(block: SubsystemImplementationBuilder.() -> Unit) {
        implementation.apply(block)
    }

    /** Declares a cross-subsystem safety interlock that blocks unsafe actuation zones. */
    fun interlock(
        id: String,
        targetSubsystemUid: String,
        targetFieldId: String,
        comparison: InterlockComparison = InterlockComparison.LESS_THAN,
        thresholdValue: Double = 0.0,
        block: SubsystemInterlockBuilder.() -> Unit = {},
    ) {
        val builder = SubsystemInterlockBuilder(
            interlockId = id,
            targetSubsystemUid = targetSubsystemUid,
            targetFieldId = targetFieldId,
            comparison = comparison,
            thresholdValue = thresholdValue,
        ).apply(block)
        interlockEntries += builder.build()
    }

    /** Registers a pre-built [TuningParameterDeclaration] owned by this subsystem or one of its components. */
    fun tuningParameter(declaration: TuningParameterDeclaration) {
        tuningParameterEntries += declaration
    }

    /** Declares a numeric double tuning parameter owned by a component in this subsystem. */
    fun tuningDouble(
        key: String,
        defaultValue: Double,
        componentUid: String = documentId,
        uid: String = key.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase(),
        displayName: String = defaultSubsystemDisplayName(key.substringAfterLast('.')),
        description: String = "$displayName tuning parameter for physical and simulation runtimes.",
        unit: String? = null,
        minimum: Double? = null,
        maximum: Double? = null,
        applyPolicy: TuningApplyPolicy = TuningApplyPolicy.LIVE_SAFE,
    ) {
        tuningParameterEntries += TuningParameterDeclaration(
            uid = uid,
            key = key,
            componentUid = componentUid,
            displayName = displayName,
            description = description,
            type = TuningParameterType.DOUBLE,
            unit = unit,
            minimum = minimum,
            maximum = maximum,
            defaultValue = TuningValue(doubleValue = defaultValue),
            applyPolicy = applyPolicy,
        )
    }

    /** Declares an arbitrary typed [TuningParameterDeclaration] owned by this subsystem. */
    fun tuningParam(declaration: TuningParameterDeclaration) {
        tuningParameterEntries += declaration
    }

    internal fun build(): SubsystemDocument {
        val document = SubsystemDocument(
            documentId = documentId,
            displayName = displayName,
            kotlinTypeName = kotlinTypeName,
            description = description,
            platform = platform,
            template = template,
            hardware = hardware.entries.toList(),
            stateFields = state.entries.toList(),
            controlLoops = control.entries.toList(),
            tuningParameters = tuningParameterEntries.toList(),
            implementation = implementation.build(generateMockIo),
            capabilityActionKeys = capabilityActionKeys.toList(),
            safety = safety.build(),
            interlocks = interlockEntries.toList(),
            linkage = linkage.build(),
            autonomousResourceKey = autonomousResourceKey,
            requiredAtStartup = requiredAtStartup,
            generateMockIo = generateMockIo,
            generateTest = generateTest,
        )
        val issues = SubsystemSchema.validate(document)
        require(issues.isEmpty()) { issues.joinToString("; ") { "${it.path}: ${it.message}" } }
        return document
    }

    /** References an action implemented by hand and declared in `action-catalog.json`. */
    fun capabilityAction(actionKey: String) {
        capabilityActionKeys += actionKey
    }
}

/** Explicit project-owned source metadata; ARES never discovers hand-authored code by scanning. */
@AresSubsystemDsl
class SubsystemImplementationBuilder internal constructor() {
    var kind: SubsystemImplementationKind = SubsystemImplementationKind.GENERATED_STARTER
    var ownership: SubsystemSourceOwnership = SubsystemSourceOwnership.GENERATED_STARTER
    var modulePath: String? = null
    var subsystemClassName: String? = null
    var ioContractClassName: String? = null
    var hardwareAdapterClassName: String? = null
    var pythonModuleName: String? = null
    var pythonFactoryName: String? = null
    var pythonSimulationFactoryName: String? = null
    var simulationSupport: SubsystemSimulationSupport = SubsystemSimulationSupport.GENERATED_MOCK
    var simulationAdapterClassName: String? = null
    var teachingLevel: SubsystemTeachingLevel = SubsystemTeachingLevel.INTERMEDIATE
    var teachingSummary: String = ""
    var documentationPath: String? = null
    internal var simInteraction = SubsystemSimInteractionDocument()
    internal val sourceFiles = mutableListOf<String>()
    internal val teachingConcepts = mutableListOf<String>()

    fun sourceFile(projectRelativePath: String) {
        sourceFiles += projectRelativePath
    }

    fun teaches(concept: String) {
        teachingConcepts += concept
    }

    /** Configures simulator field-piece interaction (intake, indexer, or projectile launcher). */
    fun simulationInteraction(
        role: SimInteractionRole,
        triggerActuator: SubsystemHardwareRef,
        block: SubsystemSimInteractionBuilder.() -> Unit = {},
    ) {
        val builder = SubsystemSimInteractionBuilder(role, triggerActuator.id).apply(block)
        simInteraction = builder.build()
    }

    internal fun build(generateMockIo: Boolean): SubsystemImplementationDocument {
        val effectiveSimulation = if (
            kind.isAresGenerated() &&
            simulationSupport == SubsystemSimulationSupport.GENERATED_MOCK &&
            !generateMockIo
        ) {
            SubsystemSimulationSupport.UNAVAILABLE
        } else {
            simulationSupport
        }
        return SubsystemImplementationDocument(
            kind = kind,
            ownership = ownership,
            modulePath = modulePath,
            sourceFiles = sourceFiles.toList(),
            subsystemClassName = subsystemClassName,
            ioContractClassName = ioContractClassName,
            hardwareAdapterClassName = hardwareAdapterClassName,
            pythonModuleName = pythonModuleName,
            pythonFactoryName = pythonFactoryName,
            pythonSimulationFactoryName = pythonSimulationFactoryName,
            simulation = SubsystemSimulationDocument(
                support = effectiveSimulation,
                adapterClassName = simulationAdapterClassName,
                interaction = simInteraction,
            ),
            teaching = SubsystemTeachingDocument(
                level = teachingLevel,
                summary = teachingSummary,
                documentationPath = documentationPath,
                concepts = teachingConcepts.toList(),
            ),
        )
    }
}

/** Configures how a simulated mechanism interacts with field game pieces. */
@AresSubsystemDsl
class SubsystemSimInteractionBuilder internal constructor(
    var role: SimInteractionRole,
    var triggerActuatorId: String?,
) {
    var triggerThreshold: Double = 1.0
    var storageCapacity: Int = 1
    var intakeDistanceMeters: Double = 0.35
    var captureRadiusMeters: Double = 0.15
    var launchSpeedMps: Double = 8.0
    var launchElevationDeg: Double = 45.0
    var beamBreakField: SubsystemFieldRef? = null

    internal fun build() = SubsystemSimInteractionDocument(
        role = role,
        triggerActuatorId = triggerActuatorId,
        triggerThreshold = triggerThreshold,
        storageCapacity = storageCapacity,
        intakeDistanceMeters = intakeDistanceMeters,
        captureRadiusMeters = captureRadiusMeters,
        launchSpeedMps = launchSpeedMps,
        launchElevationDeg = launchElevationDeg,
        beamBreakFieldId = beamBreakField?.id,
    )
}

/**
 * Builder for immutable Redux state fields owned by a subsystem.
 *
 * Supports both explicit role declarations (`state.double("targetMeters", "Target", SubsystemFieldRole.TARGET, ...)`)
 * and concise role-specific shorthands (`state.targetDouble("targetMeters", 0.0, unit = "m")`,
 * `state.measurementDouble("positionMeters", 0.0, unit = "m")`, `state.statusBoolean("homed")`).
 */
@AresSubsystemDsl
class SubsystemStateBuilder internal constructor() {
    internal val entries = mutableListOf<SubsystemStateFieldDocument>()

    fun double(
        id: String,
        displayName: String = defaultSubsystemDisplayName(id),
        role: SubsystemFieldRole,
        default: Double = 0.0,
        unit: String? = null,
        minimum: Double? = null,
        maximum: Double? = null,
    ): SubsystemFieldRef = add(
        SubsystemStateFieldDocument(
            fieldId = id, displayName = displayName, type = SubsystemValueType.DOUBLE,
            role = role, unit = unit, defaultNumber = default, minimum = minimum, maximum = maximum,
        )
    )

    fun double(
        id: String, role: SubsystemFieldRole, default: Double = 0.0,
        unit: String? = null, minimum: Double? = null, maximum: Double? = null,
    ): SubsystemFieldRef = double(id, defaultSubsystemDisplayName(id), role, default, unit, minimum, maximum)

    fun boolean(
        id: String, displayName: String = defaultSubsystemDisplayName(id),
        role: SubsystemFieldRole, default: Boolean = false,
    ): SubsystemFieldRef = add(
        SubsystemStateFieldDocument(
            fieldId = id, displayName = displayName, type = SubsystemValueType.BOOLEAN,
            role = role, defaultBoolean = default,
        )
    )

    fun boolean(id: String, role: SubsystemFieldRole, default: Boolean = false): SubsystemFieldRef =
        boolean(id, defaultSubsystemDisplayName(id), role, default)

    fun int(
        id: String, displayName: String = defaultSubsystemDisplayName(id),
        role: SubsystemFieldRole, default: Int = 0,
        unit: String? = null, minimum: Double? = null, maximum: Double? = null,
    ): SubsystemFieldRef = add(
        SubsystemStateFieldDocument(
            fieldId = id, displayName = displayName, type = SubsystemValueType.INT,
            role = role, unit = unit, defaultInt = default, minimum = minimum, maximum = maximum,
        )
    )

    fun int(
        id: String, role: SubsystemFieldRole, default: Int = 0,
        unit: String? = null, minimum: Double? = null, maximum: Double? = null,
    ): SubsystemFieldRef = int(id, defaultSubsystemDisplayName(id), role, default, unit, minimum, maximum)

    fun text(
        id: String, displayName: String = defaultSubsystemDisplayName(id),
        role: SubsystemFieldRole, default: String = "",
    ): SubsystemFieldRef = add(
        SubsystemStateFieldDocument(
            fieldId = id, displayName = displayName, type = SubsystemValueType.STRING,
            role = role, defaultText = default,
        )
    )

    fun text(id: String, role: SubsystemFieldRole, default: String = ""): SubsystemFieldRef =
        text(id, defaultSubsystemDisplayName(id), role, default)

    // --- Role-specific shorthands for text-first authoring ---

    /** Declares a `DOUBLE` state field with [SubsystemFieldRole.TARGET]. */
    fun targetDouble(
        id: String, default: Double = 0.0, unit: String? = null,
        minimum: Double? = null, maximum: Double? = null, displayName: String = defaultSubsystemDisplayName(id),
    ): SubsystemFieldRef = double(id, displayName, SubsystemFieldRole.TARGET, default, unit, minimum, maximum)

    /** Declares a `BOOLEAN` state field with [SubsystemFieldRole.TARGET]. */
    fun targetBoolean(
        id: String, default: Boolean = false, displayName: String = defaultSubsystemDisplayName(id),
    ): SubsystemFieldRef = boolean(id, displayName, SubsystemFieldRole.TARGET, default)

    /** Declares an `INT` state field with [SubsystemFieldRole.TARGET]. */
    fun targetInt(
        id: String, default: Int = 0, unit: String? = null,
        minimum: Double? = null, maximum: Double? = null, displayName: String = defaultSubsystemDisplayName(id),
    ): SubsystemFieldRef = int(id, displayName, SubsystemFieldRole.TARGET, default, unit, minimum, maximum)

    /** Declares a `STRING` state field with [SubsystemFieldRole.TARGET]. */
    fun targetText(
        id: String, default: String = "", displayName: String = defaultSubsystemDisplayName(id),
    ): SubsystemFieldRef = text(id, displayName, SubsystemFieldRole.TARGET, default)

    /** Declares a `DOUBLE` state field with [SubsystemFieldRole.MEASUREMENT]. */
    fun measurementDouble(
        id: String, default: Double = 0.0, unit: String? = null,
        minimum: Double? = null, maximum: Double? = null, displayName: String = defaultSubsystemDisplayName(id),
    ): SubsystemFieldRef = double(id, displayName, SubsystemFieldRole.MEASUREMENT, default, unit, minimum, maximum)

    /** Declares a `BOOLEAN` state field with [SubsystemFieldRole.MEASUREMENT]. */
    fun measurementBoolean(
        id: String, default: Boolean = false, displayName: String = defaultSubsystemDisplayName(id),
    ): SubsystemFieldRef = boolean(id, displayName, SubsystemFieldRole.MEASUREMENT, default)

    /** Declares an `INT` state field with [SubsystemFieldRole.MEASUREMENT]. */
    fun measurementInt(
        id: String, default: Int = 0, unit: String? = null,
        minimum: Double? = null, maximum: Double? = null, displayName: String = defaultSubsystemDisplayName(id),
    ): SubsystemFieldRef = int(id, displayName, SubsystemFieldRole.MEASUREMENT, default, unit, minimum, maximum)

    /** Declares a `BOOLEAN` state field with [SubsystemFieldRole.STATUS]. */
    fun statusBoolean(
        id: String, default: Boolean = false, displayName: String = defaultSubsystemDisplayName(id),
    ): SubsystemFieldRef = boolean(id, displayName, SubsystemFieldRole.STATUS, default)

    /** Declares a `DOUBLE` state field with [SubsystemFieldRole.STATUS]. */
    fun statusDouble(
        id: String, default: Double = 0.0, unit: String? = null,
        minimum: Double? = null, maximum: Double? = null, displayName: String = defaultSubsystemDisplayName(id),
    ): SubsystemFieldRef = double(id, displayName, SubsystemFieldRole.STATUS, default, unit, minimum, maximum)

    /** Declares an `INT` state field with [SubsystemFieldRole.STATUS]. */
    fun statusInt(
        id: String, default: Int = 0, unit: String? = null,
        minimum: Double? = null, maximum: Double? = null, displayName: String = defaultSubsystemDisplayName(id),
    ): SubsystemFieldRef = int(id, displayName, SubsystemFieldRole.STATUS, default, unit, minimum, maximum)

    /** Declares a `STRING` state field with [SubsystemFieldRole.STATUS]. */
    fun statusText(
        id: String, default: String = "", displayName: String = defaultSubsystemDisplayName(id),
    ): SubsystemFieldRef = text(id, displayName, SubsystemFieldRole.STATUS, default)

    /** Declares a `DOUBLE` state field with [SubsystemFieldRole.CONFIGURATION]. */
    fun configDouble(
        id: String, default: Double = 0.0, unit: String? = null,
        minimum: Double? = null, maximum: Double? = null, displayName: String = defaultSubsystemDisplayName(id),
    ): SubsystemFieldRef = double(id, displayName, SubsystemFieldRole.CONFIGURATION, default, unit, minimum, maximum)

    /** Declares an `INT` state field with [SubsystemFieldRole.CONFIGURATION]. */
    fun configInt(
        id: String, default: Int = 0, unit: String? = null,
        minimum: Double? = null, maximum: Double? = null, displayName: String = defaultSubsystemDisplayName(id),
    ): SubsystemFieldRef = int(id, displayName, SubsystemFieldRole.CONFIGURATION, default, unit, minimum, maximum)

    /** Declares a `BOOLEAN` state field with [SubsystemFieldRole.CONFIGURATION]. */
    fun configBoolean(
        id: String, default: Boolean = false, displayName: String = defaultSubsystemDisplayName(id),
    ): SubsystemFieldRef = boolean(id, displayName, SubsystemFieldRole.CONFIGURATION, default)

    /** Declares a `STRING` state field with [SubsystemFieldRole.CONFIGURATION]. */
    fun configText(
        id: String, default: String = "", displayName: String = defaultSubsystemDisplayName(id),
    ): SubsystemFieldRef = text(id, displayName, SubsystemFieldRole.CONFIGURATION, default)

    private fun add(document: SubsystemStateFieldDocument): SubsystemFieldRef {
        entries += document
        return SubsystemFieldRef(document.fieldId)
    }
}
