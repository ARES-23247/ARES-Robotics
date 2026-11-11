package com.areslib.subsystem

/**
 * Builder for hardware devices owned by a subsystem.
 *
 * `displayName` defaults to a humanized version of `id`, and on FTC `hardwareMapName` defaults to `id`
 * unless overridden inside the configuration block.
 */
@AresSubsystemDsl
class SubsystemHardwareBuilder internal constructor(private val platform: SubsystemPlatform) {
    internal val entries = mutableListOf<SubsystemHardwareDocument>()

    fun motor(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.MOTOR, block)

    fun positionalServo(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.POSITIONAL_SERVO, block)

    fun continuousServo(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.CONTINUOUS_SERVO, block)

    fun absoluteEncoder(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.ABSOLUTE_ENCODER, block)

    fun quadratureEncoder(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.QUADRATURE_ENCODER, block)

    fun digitalInput(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.DIGITAL_INPUT, block)

    fun digitalOutput(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.DIGITAL_OUTPUT, block)

    fun analogInput(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.ANALOG_INPUT, block)

    fun pwmOutput(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.PWM_OUTPUT, block)

    fun distanceSensor(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.DISTANCE_SENSOR, block)

    fun imu(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.IMU, block)

    fun colorSensor(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.COLOR_SENSOR, block)

    fun solenoid(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.SOLENOID, block)

    fun indicatorLight(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.INDICATOR_LIGHT, block)

    fun buzzer(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.BUZZER, block)

    fun prismDriver(id: String, displayName: String = defaultSubsystemDisplayName(id), block: HardwareDeviceBuilder.() -> Unit = {}): SubsystemHardwareRef =
        add(id, displayName, SubsystemHardwareKind.PRISM_DRIVER, block)

    private fun add(
        id: String,
        displayName: String,
        kind: SubsystemHardwareKind,
        block: HardwareDeviceBuilder.() -> Unit,
    ): SubsystemHardwareRef {
        val builder = HardwareDeviceBuilder(platform, id).apply(block)
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
                SubsystemHardwareKind.MOTOR,
                SubsystemHardwareKind.CONTINUOUS_SERVO,
                SubsystemHardwareKind.DIGITAL_OUTPUT,
                SubsystemHardwareKind.PWM_OUTPUT,
                SubsystemHardwareKind.SOLENOID,
                SubsystemHardwareKind.INDICATOR_LIGHT,
                SubsystemHardwareKind.BUZZER,
                SubsystemHardwareKind.PRISM_DRIVER -> 0.0
                SubsystemHardwareKind.POSITIONAL_SERVO -> 0.5
                else -> null
            },
            following = builder.following,
            encoderCountsPerRevolution = builder.encoderCountsPerRevolution,
            distanceMetersPerVolt = builder.distanceMetersPerVolt,
            imuLogoFacingDirection = builder.imuLogoFacingDirection,
            imuUsbFacingDirection = builder.imuUsbFacingDirection,
            visualPlacement = builder.visualPlacement,
        )
        return SubsystemHardwareRef(id)
    }
}

@AresSubsystemDsl
class HardwareDeviceBuilder internal constructor(
    platform: SubsystemPlatform,
    defaultHardwareId: String = "",
) {
    var hardwareMapName: String? = if (platform == SubsystemPlatform.FTC && defaultHardwareId.isNotEmpty()) {
        defaultHardwareId
    } else {
        null
    }
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
    var visualPlacement: SubsystemVisualPlacementDocument? = null
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

    /** Convenience shorthand for [follow] using a boolean [inverted] flag. */
    fun follows(
        leader: SubsystemHardwareRef,
        inverted: Boolean = false,
    ) {
        follow(
            leader,
            if (inverted) SubsystemFollowerTransform.INVERTED else SubsystemFollowerTransform.SAME_DIRECTION,
        )
    }

    /** Configures visual footprint placement for indicator lights or Prism LED drivers. */
    fun visualPlacement(
        anchor: SubsystemVisualAnchor = SubsystemVisualAnchor.UNSPECIFIED,
        forwardFraction: Double = 0.0,
        leftFraction: Double = 0.0,
    ) {
        visualPlacement = SubsystemVisualPlacementDocument(anchor, forwardFraction, leftFraction)
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

/** Reference to a declared control loop in [SubsystemControlBuilder]. */
data class SubsystemControlLoopRef(val id: String, val uid: String = id)

@AresSubsystemDsl
class SubsystemControlBuilder internal constructor() {
    internal val entries = mutableListOf<SubsystemControlLoopDocument>()

    fun direct(
        id: String,
        displayName: String = defaultSubsystemDisplayName(id),
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ): SubsystemControlLoopRef = add(id, displayName, SubsystemControlStrategy.DIRECT, actuator, target, null, block)

    fun direct(
        id: String,
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ): SubsystemControlLoopRef = direct(id, defaultSubsystemDisplayName(id), actuator, target, block)

    /** Alias for [direct] matching open-loop actuator control terminology. */
    fun openLoop(
        id: String,
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        displayName: String = defaultSubsystemDisplayName(id),
        block: ControlLoopBuilder.() -> Unit = {},
    ): SubsystemControlLoopRef = direct(id, displayName, actuator, target, block)

    fun positionPid(
        id: String,
        displayName: String = defaultSubsystemDisplayName(id),
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        measurement: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ): SubsystemControlLoopRef = add(id, displayName, SubsystemControlStrategy.POSITION_PID, actuator, target, measurement, block)

    fun positionPid(
        id: String,
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        measurement: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ): SubsystemControlLoopRef = positionPid(id, defaultSubsystemDisplayName(id), actuator, target, measurement, block)

    /** Position PID with an allocation-free trapezoidal setpoint profile. */
    fun profiledPositionPid(
        id: String,
        displayName: String = defaultSubsystemDisplayName(id),
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        measurement: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ): SubsystemControlLoopRef = add(id, displayName, SubsystemControlStrategy.PROFILED_POSITION_PID, actuator, target, measurement, block)

    /** Position PID with an allocation-free trapezoidal setpoint profile. */
    fun profiledPositionPid(
        id: String,
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        measurement: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ): SubsystemControlLoopRef = profiledPositionPid(id, defaultSubsystemDisplayName(id), actuator, target, measurement, block)

    fun velocityPid(
        id: String,
        displayName: String = defaultSubsystemDisplayName(id),
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        measurement: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ): SubsystemControlLoopRef = add(id, displayName, SubsystemControlStrategy.VELOCITY_PID, actuator, target, measurement, block)

    fun velocityPid(
        id: String,
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        measurement: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ): SubsystemControlLoopRef = velocityPid(id, defaultSubsystemDisplayName(id), actuator, target, measurement, block)

    fun bangBang(
        id: String,
        displayName: String = defaultSubsystemDisplayName(id),
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        measurement: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ): SubsystemControlLoopRef = add(id, displayName, SubsystemControlStrategy.BANG_BANG, actuator, target, measurement, block)

    fun bangBang(
        id: String,
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        measurement: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ): SubsystemControlLoopRef = bangBang(id, defaultSubsystemDisplayName(id), actuator, target, measurement, block)

    fun servoPosition(
        id: String,
        displayName: String = defaultSubsystemDisplayName(id),
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ): SubsystemControlLoopRef = add(id, displayName, SubsystemControlStrategy.SERVO_POSITION, actuator, target, null, block)

    fun servoPosition(
        id: String,
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        block: ControlLoopBuilder.() -> Unit = {},
    ): SubsystemControlLoopRef = servoPosition(id, defaultSubsystemDisplayName(id), actuator, target, block)

    private fun add(
        id: String,
        displayName: String,
        strategy: SubsystemControlStrategy,
        actuator: SubsystemHardwareRef,
        target: SubsystemFieldRef,
        measurement: SubsystemFieldRef?,
        block: ControlLoopBuilder.() -> Unit,
    ): SubsystemControlLoopRef {
        val builder = ControlLoopBuilder().apply(block)
        val doc = SubsystemControlLoopDocument(
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
        entries += doc
        return SubsystemControlLoopRef(id = doc.loopId, uid = doc.uid)
    }
}

@AresSubsystemDsl
class ControlLoopBuilder internal constructor() {
    var kP: Double = 0.0
    var kI: Double = 0.0
    var kD: Double = 0.0
    var maximumVelocity: Double = 1.0
    var maximumAcceleration: Double = 2.0
    val feedforward = SubsystemFeedforwardBuilder()
    var derivativeFilterTimeConstantSeconds: Double = 0.02
    var continuousInputEnabled: Boolean = false
    var continuousInputMinimum: Double = -Math.PI
    var continuousInputMaximum: Double = Math.PI
    var tolerance: Double = 0.0
    var hysteresis: Double = 0.0
    var minimumOutput: Double = -12.0
    var maximumOutput: Double = 12.0

    /** Configures unit-aware feedforward gains and field bindings for this control loop. */
    fun feedforward(
        kind: SubsystemFeedforwardKind = SubsystemFeedforwardKind.SIMPLE_MOTOR,
        block: SubsystemFeedforwardBuilder.() -> Unit = {},
    ) {
        feedforward.kind = kind
        feedforward.apply(block)
    }
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

    /** Configures a `SIMPLE_MOTOR` feedforward (`kS * sign(v) + kV * v + kA * a`). */
    fun simpleMotor(
        kS: Double = 0.0,
        kV: Double = 0.0,
        kA: Double = 0.0,
        velocityField: SubsystemFieldRef? = null,
        accelerationField: SubsystemFieldRef? = null,
    ) {
        this.kind = SubsystemFeedforwardKind.SIMPLE_MOTOR
        this.kS = kS
        this.kV = kV
        this.kA = kA
        this.velocityField = velocityField
        this.accelerationField = accelerationField
    }

    /** Configures an `ELEVATOR` feedforward (`kG + kS * sign(v) + kV * v + kA * a`). */
    fun elevator(
        kS: Double = 0.0,
        kV: Double = 0.0,
        kG: Double = 0.0,
        kA: Double = 0.0,
        velocityField: SubsystemFieldRef? = null,
        accelerationField: SubsystemFieldRef? = null,
    ) {
        this.kind = SubsystemFeedforwardKind.ELEVATOR
        this.kS = kS
        this.kV = kV
        this.kG = kG
        this.kA = kA
        this.velocityField = velocityField
        this.accelerationField = accelerationField
    }

    /** Configures an `ARM` cosine-gravity feedforward (`kG * cos(theta) + kS * sign(v) + kV * v + kA * a`). */
    fun arm(
        kS: Double = 0.0,
        kV: Double = 0.0,
        kG: Double = 0.0,
        kA: Double = 0.0,
        gravityAngleField: SubsystemFieldRef? = null,
        velocityField: SubsystemFieldRef? = null,
        accelerationField: SubsystemFieldRef? = null,
        linkageJoint: Int? = null,
    ) {
        this.kind = SubsystemFeedforwardKind.ARM
        this.kS = kS
        this.kV = kV
        this.kG = kG
        this.kA = kA
        this.gravityAngleField = gravityAngleField
        this.velocityField = velocityField
        this.accelerationField = accelerationField
        this.linkageJoint = linkageJoint
    }

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
