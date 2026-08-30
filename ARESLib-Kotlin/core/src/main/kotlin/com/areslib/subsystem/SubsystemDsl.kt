package com.areslib.subsystem

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
 * Hand-code entry point used by the GUI-generated source as well as student-written mechanisms.
 *
 * Example:
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

    val state = SubsystemStateBuilder()
    val hardware = SubsystemHardwareBuilder(platform)
    val control = SubsystemControlBuilder()
    val safety = SubsystemSafetyBuilder()
    val implementation = SubsystemImplementationBuilder()

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
            implementation = implementation.build(generateMockIo),
            capabilityActionKeys = capabilityActionKeys.toList(),
            safety = safety.build(),
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
    var simulationSupport: SubsystemSimulationSupport = SubsystemSimulationSupport.GENERATED_MOCK
    var simulationAdapterClassName: String? = null
    var teachingLevel: SubsystemTeachingLevel = SubsystemTeachingLevel.INTERMEDIATE
    var teachingSummary: String = ""
    var documentationPath: String? = null
    internal val sourceFiles = mutableListOf<String>()
    internal val teachingConcepts = mutableListOf<String>()

    fun sourceFile(projectRelativePath: String) {
        sourceFiles += projectRelativePath
    }

    fun teaches(concept: String) {
        teachingConcepts += concept
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
            simulation = SubsystemSimulationDocument(effectiveSimulation, simulationAdapterClassName),
            teaching = SubsystemTeachingDocument(
                level = teachingLevel,
                summary = teachingSummary,
                documentationPath = documentationPath,
                concepts = teachingConcepts.toList(),
            ),
        )
    }
}

/** Declarative fail-closed requirements used by codegen, preview warnings, and verification. */
@AresSubsystemDsl
class SubsystemSafetyBuilder internal constructor() {
    var feedbackTimeoutMs: Long? = 250L
    val homing = SubsystemHomingBuilder()
    var requiresCalibration: Boolean = false
    var requiresConfigurationHealth: Boolean = true
    var requiresCurrentMonitoring: Boolean = false
    var latchOutputFaults: Boolean = true
    var requiresExplicitNeutralRecovery: Boolean = true
    var telemetryEnabled: Boolean = true
    var zeroAllocationPeriodic: Boolean = true

    internal fun build() = SubsystemSafetyDocument(
        feedbackTimeoutMs = feedbackTimeoutMs,
        homing = homing.build(),
        requiresCalibration = requiresCalibration,
        requiresConfigurationHealth = requiresConfigurationHealth,
        requiresCurrentMonitoring = requiresCurrentMonitoring,
        latchOutputFaults = latchOutputFaults,
        requiresExplicitNeutralRecovery = requiresExplicitNeutralRecovery,
        telemetryEnabled = telemetryEnabled,
        zeroAllocationPeriodic = zeroAllocationPeriodic,
    )
}

/** Typed homing recipes; stall methods always include bounded effort, dwell, and timeout. */
@AresSubsystemDsl
class SubsystemHomingBuilder internal constructor() {
    private var document = SubsystemHomingDocument()

    fun none() {
        document = SubsystemHomingDocument()
    }

    fun digitalSensor(
        actuator: SubsystemHardwareRef,
        sensor: SubsystemFieldRef,
        searchOutput: Double,
        activeWhen: Boolean = true,
        dwellMs: Long = 80L,
        timeoutMs: Long = 3_000L,
        zeroPosition: Double = 0.0,
    ) {
        document = SubsystemHomingDocument(
            method = SubsystemHomingMethod.DIGITAL_SENSOR,
            actuatorId = actuator.id,
            searchOutput = searchOutput,
            evidence = listOf(
                SubsystemHomingEvidenceDocument(
                    sensor.id,
                    if (activeWhen) SubsystemHomingComparison.TRUE else SubsystemHomingComparison.FALSE,
                )
            ),
            dwellMs = dwellMs,
            timeoutMs = timeoutMs,
            zeroPosition = zeroPosition,
        )
    }

    fun currentStall(
        actuator: SubsystemHardwareRef,
        current: SubsystemFieldRef,
        searchOutput: Double,
        minimumCurrentAmps: Double,
        dwellMs: Long = 250L,
        timeoutMs: Long = 3_000L,
        zeroPosition: Double = 0.0,
    ) {
        document = stallDocument(
            SubsystemHomingMethod.CURRENT_STALL,
            actuator,
            searchOutput,
            listOf(SubsystemHomingEvidenceDocument(current.id, SubsystemHomingComparison.AT_OR_ABOVE, minimumCurrentAmps)),
            dwellMs,
            timeoutMs,
            zeroPosition,
        )
    }

    fun velocityStall(
        actuator: SubsystemHardwareRef,
        velocity: SubsystemFieldRef,
        searchOutput: Double,
        maximumAbsoluteVelocity: Double,
        dwellMs: Long = 250L,
        timeoutMs: Long = 3_000L,
        zeroPosition: Double = 0.0,
    ) {
        document = stallDocument(
            SubsystemHomingMethod.VELOCITY_STALL,
            actuator,
            searchOutput,
            listOf(SubsystemHomingEvidenceDocument(velocity.id, SubsystemHomingComparison.ABS_AT_OR_BELOW, maximumAbsoluteVelocity)),
            dwellMs,
            timeoutMs,
            zeroPosition,
        )
    }

    fun currentAndVelocityStall(
        actuator: SubsystemHardwareRef,
        current: SubsystemFieldRef,
        velocity: SubsystemFieldRef,
        searchOutput: Double,
        minimumCurrentAmps: Double,
        maximumAbsoluteVelocity: Double,
        dwellMs: Long = 250L,
        timeoutMs: Long = 3_000L,
        zeroPosition: Double = 0.0,
    ) {
        document = stallDocument(
            SubsystemHomingMethod.CURRENT_AND_VELOCITY_STALL,
            actuator,
            searchOutput,
            listOf(
                SubsystemHomingEvidenceDocument(current.id, SubsystemHomingComparison.AT_OR_ABOVE, minimumCurrentAmps),
                SubsystemHomingEvidenceDocument(velocity.id, SubsystemHomingComparison.ABS_AT_OR_BELOW, maximumAbsoluteVelocity),
            ),
            dwellMs,
            timeoutMs,
            zeroPosition,
        )
    }

    fun custom(
        actuator: SubsystemHardwareRef,
        searchOutput: Double,
        evidence: List<SubsystemHomingEvidenceDocument>,
        dwellMs: Long = 250L,
        timeoutMs: Long = 3_000L,
        zeroPosition: Double = 0.0,
    ) {
        document = stallDocument(
            SubsystemHomingMethod.CUSTOM_MEASUREMENT,
            actuator,
            searchOutput,
            evidence,
            dwellMs,
            timeoutMs,
            zeroPosition,
        )
    }

    internal fun build(): SubsystemHomingDocument = document

    private fun stallDocument(
        method: SubsystemHomingMethod,
        actuator: SubsystemHardwareRef,
        searchOutput: Double,
        evidence: List<SubsystemHomingEvidenceDocument>,
        dwellMs: Long,
        timeoutMs: Long,
        zeroPosition: Double,
    ) = SubsystemHomingDocument(method, actuator.id, searchOutput, evidence, dwellMs, timeoutMs, zeroPosition)
}

@AresSubsystemDsl
class SubsystemStateBuilder internal constructor() {
    internal val entries = mutableListOf<SubsystemStateFieldDocument>()

    fun double(
        id: String,
        displayName: String,
        role: SubsystemFieldRole,
        default: Double = 0.0,
        unit: String? = null,
        minimum: Double? = null,
        maximum: Double? = null,
    ): SubsystemFieldRef = add(
        SubsystemStateFieldDocument(
            fieldId = id,
            displayName = displayName,
            type = SubsystemValueType.DOUBLE,
            role = role,
            unit = unit,
            defaultNumber = default,
            minimum = minimum,
            maximum = maximum,
        )
    )

    fun boolean(
        id: String,
        displayName: String,
        role: SubsystemFieldRole,
        default: Boolean = false,
    ): SubsystemFieldRef = add(
        SubsystemStateFieldDocument(
            fieldId = id,
            displayName = displayName,
            type = SubsystemValueType.BOOLEAN,
            role = role,
            defaultBoolean = default,
        )
    )

    fun int(
        id: String,
        displayName: String,
        role: SubsystemFieldRole,
        default: Int = 0,
        unit: String? = null,
        minimum: Double? = null,
        maximum: Double? = null,
    ): SubsystemFieldRef = add(
        SubsystemStateFieldDocument(
            fieldId = id,
            displayName = displayName,
            type = SubsystemValueType.INT,
            role = role,
            unit = unit,
            defaultInt = default,
            minimum = minimum,
            maximum = maximum,
        )
    )

    fun text(
        id: String,
        displayName: String,
        role: SubsystemFieldRole,
        default: String = "",
    ): SubsystemFieldRef = add(
        SubsystemStateFieldDocument(
            fieldId = id,
            displayName = displayName,
            type = SubsystemValueType.STRING,
            role = role,
            defaultText = default,
        )
    )

    private fun add(document: SubsystemStateFieldDocument): SubsystemFieldRef {
        entries += document
        return SubsystemFieldRef(document.fieldId)
    }
}

@AresSubsystemDsl
class SubsystemHardwareBuilder internal constructor(private val platform: SubsystemPlatform) {
    internal val entries = mutableListOf<SubsystemHardwareDocument>()

    fun motor(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.MOTOR, block)

    fun positionalServo(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.POSITIONAL_SERVO, block)

    fun continuousServo(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.CONTINUOUS_SERVO, block)

    fun absoluteEncoder(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.ABSOLUTE_ENCODER, block)

    fun quadratureEncoder(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.QUADRATURE_ENCODER, block)

    fun digitalInput(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.DIGITAL_INPUT, block)

    fun analogInput(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.ANALOG_INPUT, block)

    fun distanceSensor(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.DISTANCE_SENSOR, block)

    fun imu(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.IMU, block)

    fun colorSensor(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.COLOR_SENSOR, block)

    fun solenoid(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.SOLENOID, block)

    fun indicatorLight(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.INDICATOR_LIGHT, block)

    fun prismDriver(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.PRISM_DRIVER, block)

    private fun add(
        id: String,
        displayName: String,
        kind: SubsystemHardwareKind,
        block: HardwareDeviceBuilder.() -> Unit,
    ): SubsystemHardwareRef {
        val builder = HardwareDeviceBuilder(platform).apply(block)
        entries += SubsystemHardwareDocument(
            hardwareId = id,
            displayName = displayName,
            kind = kind,
            connection = SubsystemHardwareConnection(
                hardwareMapName = builder.hardwareMapName,
                canId = builder.canId,
                canBus = builder.canBus,
                channel = builder.channel,
                secondaryChannel = builder.secondaryChannel,
                pneumaticsModuleType = builder.pneumaticsModuleType,
            ),
            required = builder.required,
            inverted = builder.inverted,
            measurements = builder.measurements.map { measurement ->
                SubsystemMeasurementDocument(
                    measurement.field.id,
                    measurement.source ?: kind.compatibleMeasurementSources().first(),
                    measurement.scale,
                    measurement.offset,
                    measurement.maxAgeMs,
                    measurement.validMinimum,
                    measurement.validMaximum,
                )
            },
            currentLimitAmps = builder.currentLimitAmps,
            safeOutput = builder.safeOutput ?: when (kind) {
                SubsystemHardwareKind.MOTOR, SubsystemHardwareKind.CONTINUOUS_SERVO -> 0.0
                SubsystemHardwareKind.POSITIONAL_SERVO -> 0.5
                SubsystemHardwareKind.SOLENOID -> 0.0
                else -> null
            },
            following = builder.following,
            encoderCountsPerRevolution = builder.encoderCountsPerRevolution,
            distanceMetersPerVolt = builder.distanceMetersPerVolt,
            imuLogoFacingDirection = builder.imuLogoFacingDirection,
            imuUsbFacingDirection = builder.imuUsbFacingDirection,
        )
        return SubsystemHardwareRef(id)
    }
}

@AresSubsystemDsl
class HardwareDeviceBuilder internal constructor(platform: SubsystemPlatform) {
    var hardwareMapName: String? = null
    var canId: Int? = null
    var canBus: String = "rio"
    var channel: Int? = null
    var secondaryChannel: Int? = null
    var pneumaticsModuleType: SubsystemPneumaticsModuleType? = null
    var required: Boolean = true
    var inverted: Boolean = false
    var currentLimitAmps: Double? = null
    var safeOutput: Double? = null
    var encoderCountsPerRevolution: Double? = null
    var distanceMetersPerVolt: Double? = null
    var imuLogoFacingDirection: SubsystemHubFacingDirection? = null
    var imuUsbFacingDirection: SubsystemHubFacingDirection? = null
    internal var following: SubsystemFollowerDocument? = null
    internal val measurements = mutableListOf<MeasurementBuilderValue>()

    init {
        if (platform == SubsystemPlatform.FRC) hardwareMapName = null
    }

    fun measurement(
        field: SubsystemFieldRef,
        source: SubsystemMeasurementSource? = null,
        scale: Double = 1.0,
        offset: Double = 0.0,
        maxAgeMs: Long? = null,
        validMinimum: Double? = null,
        validMaximum: Double? = null,
    ) {
        measurements += MeasurementBuilderValue(field, source, scale, offset, maxAgeMs, validMinimum, validMaximum)
    }

    /** Routes this actuator from [leader] instead of creating a second competing control loop. */
    fun follow(
        leader: SubsystemHardwareRef,
        transform: SubsystemFollowerTransform = SubsystemFollowerTransform.SAME_DIRECTION,
    ) {
        following = SubsystemFollowerDocument(leader.id, transform)
    }
}

internal data class MeasurementBuilderValue(
    val field: SubsystemFieldRef,
    val source: SubsystemMeasurementSource?,
    val scale: Double,
    val offset: Double,
    val maxAgeMs: Long?,
    val validMinimum: Double?,
    val validMaximum: Double?,
)

@AresSubsystemDsl
class SubsystemControlBuilder internal constructor() {
    internal val entries = mutableListOf<SubsystemControlLoopDocument>()

    fun direct(
        id: String,
        displayName: String,
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ) = add(id, displayName, SubsystemControlStrategy.DIRECT, actuator, target, null, block)

    fun positionPid(
        id: String,
        displayName: String,
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        measurement: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ) = add(id, displayName, SubsystemControlStrategy.POSITION_PID, actuator, target, measurement, block)

    /** Position PID with an allocation-free trapezoidal setpoint profile. */
    fun profiledPositionPid(
        id: String,
        displayName: String,
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        measurement: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ) = add(id, displayName, SubsystemControlStrategy.PROFILED_POSITION_PID, actuator, target, measurement, block)

    fun velocityPid(
        id: String,
        displayName: String,
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        measurement: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ) = add(id, displayName, SubsystemControlStrategy.VELOCITY_PID, actuator, target, measurement, block)

    fun bangBang(
        id: String,
        displayName: String,
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        measurement: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ) = add(id, displayName, SubsystemControlStrategy.BANG_BANG, actuator, target, measurement, block)

    fun servoPosition(
        id: String,
        displayName: String,
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ) = add(id, displayName, SubsystemControlStrategy.SERVO_POSITION, actuator, target, null, block)

    private fun add(
        id: String,
        displayName: String,
        strategy: SubsystemControlStrategy,
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        measurement: SubsystemFieldRef?,
        block: ControlLoopBuilder.() -> Unit,
    ) {
        val builder = ControlLoopBuilder().apply(block)
        entries += SubsystemControlLoopDocument(
            loopId = id,
            displayName = displayName,
            strategy = strategy,
            actuatorId = actuator.id,
            targetFieldId = target.id,
            measurementFieldId = measurement?.id,
            kP = builder.kP,
            kI = builder.kI,
            kD = builder.kD,
            motionProfile = SubsystemMotionProfileDocument(
                maximumVelocity = builder.maximumVelocity,
                maximumAcceleration = builder.maximumAcceleration,
            ),
            feedforward = builder.feedforward.build(),
            derivativeFilterTimeConstantSeconds = builder.derivativeFilterTimeConstantSeconds,
            continuousInput = SubsystemContinuousInputDocument(
                enabled = builder.continuousInputEnabled,
                minimumInput = builder.continuousInputMinimum,
                maximumInput = builder.continuousInputMaximum,
            ),
            tolerance = builder.tolerance,
            hysteresis = builder.hysteresis,
            minimumOutput = builder.minimumOutput,
            maximumOutput = builder.maximumOutput,
        )
    }
}

@AresSubsystemDsl
class ControlLoopBuilder internal constructor() {
    var kP: Double = 0.0
    var kI: Double = 0.0
    var kD: Double = 0.0
    /** Maximum profiled setpoint velocity in the target field's unit per second. */
    var maximumVelocity: Double = 1.0
    /** Maximum profiled setpoint acceleration in the target field's unit per second squared. */
    var maximumAcceleration: Double = 2.0
    val feedforward = SubsystemFeedforwardBuilder()
    var derivativeFilterTimeConstantSeconds: Double = 0.02
    /** Enables shortest-path wrapping for a position target and measurement expressed in radians. */
    var continuousInputEnabled: Boolean = false
    /** Lower boundary of one complete angular turn in radians. */
    var continuousInputMinimum: Double = -Math.PI
    /** Upper boundary of one complete angular turn in radians. */
    var continuousInputMaximum: Double = Math.PI
    var tolerance: Double = 0.0
    /** Additional error required to restart bang-bang output after entering the stop band. */
    var hysteresis: Double = 0.0
    var minimumOutput: Double = -12.0
    var maximumOutput: Double = 12.0
}

/** Unit-aware feedforward configuration; use NONE when feedback alone is intentional. */
@AresSubsystemDsl
class SubsystemFeedforwardBuilder internal constructor() {
    var kind: SubsystemFeedforwardKind = SubsystemFeedforwardKind.NONE
    var kS: Double = 0.0
    var kV: Double = 0.0
    var kA: Double = 0.0
    var kG: Double = 0.0
    var velocityField: SubsystemFieldRef? = null
    var accelerationField: SubsystemFieldRef? = null
    var gravityAngleField: SubsystemFieldRef? = null
    var linkageJoint: Int? = null

    internal fun build() = SubsystemFeedforwardDocument(
        kind = kind,
        kS = kS,
        kV = kV,
        kA = kA,
        kG = kG,
        velocityFieldId = velocityField?.id,
        accelerationFieldId = accelerationField?.id,
        gravityAngleFieldId = gravityAngleField?.id,
        linkageJoint = linkageJoint,
    )
}
