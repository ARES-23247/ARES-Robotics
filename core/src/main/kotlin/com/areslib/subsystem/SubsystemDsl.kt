package com.areslib.subsystem

/** Keeps nested subsystem builders from accidentally mutating a parent receiver. */
@DslMarker
annotation class AresSubsystemDsl

data class SubsystemFieldRef internal constructor(val id: String)
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
    name: String,
    platform: SubsystemPlatform,
    block: SubsystemBuilder.() -> Unit,
): SubsystemDocument = SubsystemBuilder(documentId, name, platform).apply(block).build()

@AresSubsystemDsl
class SubsystemBuilder internal constructor(
    private val documentId: String,
    private val name: String,
    private val platform: SubsystemPlatform,
) {
    var description: String = ""
    var template: SubsystemTemplate = SubsystemTemplate.ADVANCED_CUSTOM
    var requiredAtStartup: Boolean = true
    var generateMockIo: Boolean = true
    var generateTest: Boolean = true
    var autonomousResourceKey: String? = null

    val state = SubsystemStateBuilder()
    val hardware = SubsystemHardwareBuilder(platform)
    val control = SubsystemControlBuilder()
    val safety = SubsystemSafetyBuilder()

    internal fun build(): SubsystemDocument {
        val document = SubsystemDocument(
            documentId = documentId,
            name = name,
            description = description,
            platform = platform,
            template = template,
            hardware = hardware.entries.toList(),
            stateFields = state.entries.toList(),
            controlLoops = control.entries.toList(),
            safety = safety.build(),
            autonomousResourceKey = autonomousResourceKey,
            requiredAtStartup = requiredAtStartup,
            generateMockIo = generateMockIo,
            generateTest = generateTest,
        )
        val issues = validateSubsystemDocument(document)
        require(issues.isEmpty()) { issues.joinToString("; ") { "${it.path}: ${it.message}" } }
        return document
    }
}

/** Declarative fail-closed requirements used by codegen, preview warnings, and verification. */
@AresSubsystemDsl
class SubsystemSafetyBuilder internal constructor() {
    var feedbackTimeoutMs: Long? = 250L
    var requiresHoming: Boolean = false
    var homingSensorId: String? = null
    var requiresCalibration: Boolean = false
    var requiresConfigurationHealth: Boolean = true
    var requiresCurrentMonitoring: Boolean = false
    var latchOutputFaults: Boolean = true
    var requiresExplicitNeutralRecovery: Boolean = true
    var telemetryEnabled: Boolean = true
    var zeroAllocationPeriodic: Boolean = true

    internal fun build() = SubsystemSafetyDocument(
        feedbackTimeoutMs = feedbackTimeoutMs,
        requiresHoming = requiresHoming,
        homingSensorId = homingSensorId,
        requiresCalibration = requiresCalibration,
        requiresConfigurationHealth = requiresConfigurationHealth,
        requiresCurrentMonitoring = requiresCurrentMonitoring,
        latchOutputFaults = latchOutputFaults,
        requiresExplicitNeutralRecovery = requiresExplicitNeutralRecovery,
        telemetryEnabled = telemetryEnabled,
        zeroAllocationPeriodic = zeroAllocationPeriodic,
    )
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

    fun digitalInput(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.DIGITAL_INPUT, block)

    fun analogInput(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.ANALOG_INPUT, block)

    fun colorSensor(id: String, displayName: String, block: HardwareDeviceBuilder.() -> Unit): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.COLOR_SENSOR, block)

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
            ),
            required = builder.required,
            inverted = builder.inverted,
            measurements = builder.measurements.map { measurement ->
                SubsystemMeasurementDocument(
                    measurement.field.id,
                    measurement.source ?: kind.compatibleMeasurementSources().first(),
                    measurement.scale,
                    measurement.offset,
                )
            },
            currentLimitAmps = builder.currentLimitAmps,
            safeOutput = builder.safeOutput ?: when (kind) {
                SubsystemHardwareKind.MOTOR, SubsystemHardwareKind.CONTINUOUS_SERVO -> 0.0
                SubsystemHardwareKind.POSITIONAL_SERVO -> 0.5
                else -> null
            },
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
    var required: Boolean = true
    var inverted: Boolean = false
    var currentLimitAmps: Double? = null
    var safeOutput: Double? = null
    internal val measurements = mutableListOf<MeasurementBuilderValue>()

    init {
        if (platform == SubsystemPlatform.FRC) hardwareMapName = null
    }

    fun measurement(
        field: SubsystemFieldRef,
        source: SubsystemMeasurementSource? = null,
        scale: Double = 1.0,
        offset: Double = 0.0,
    ) {
        measurements += MeasurementBuilderValue(field, source, scale, offset)
    }
}

internal data class MeasurementBuilderValue(
    val field: SubsystemFieldRef,
    val source: SubsystemMeasurementSource?,
    val scale: Double,
    val offset: Double,
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
            kS = builder.kS,
            kV = builder.kV,
            derivativeFilterTimeConstantSeconds = builder.derivativeFilterTimeConstantSeconds,
            tolerance = builder.tolerance,
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
    var kS: Double = 0.0
    var kV: Double = 0.0
    var derivativeFilterTimeConstantSeconds: Double = 0.02
    var tolerance: Double = 0.0
    var minimumOutput: Double = -12.0
    var maximumOutput: Double = 12.0
}
