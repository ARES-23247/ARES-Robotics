package com.areslib.subsystem

import com.areslib.tuning.TuningParameterDeclaration

const val ARES_SUBSYSTEM_SCHEMA_VERSION: Int = 11

enum class SubsystemPlatform { FTC, FRC, XRP }

/** Whether ARES owns runtime plumbing, creates an editable starter, or integrates project code. */
enum class SubsystemImplementationKind {
    /** Mechanical runtime and verification stay under Gradle generated-source directories. */
    DECLARATIVE_GENERATED,
    /** Documented Kotlin is created in normal source directories and may become a teaching example. */
    GENERATED_STARTER,
    /** The project owns the implementation and declares its integration points explicitly. */
    HAND_AUTHORED,
}

/** Ownership is explicit so regeneration can never infer permission to replace Kotlin source. */
enum class SubsystemSourceOwnership { GENERATED_DO_NOT_EDIT, GENERATED_STARTER, USER_OWNED }

/** True when ARES deterministically creates the runtime from the canonical descriptor. */
fun SubsystemImplementationKind.isAresGenerated(): Boolean = this != SubsystemImplementationKind.HAND_AUTHORED

enum class SubsystemSimulationSupport {
    GENERATED_MOCK,
    HAND_AUTHORED_MOCK,
    HAND_AUTHORED_SIMULATOR,
    UNAVAILABLE,
}

enum class SubsystemTeachingLevel { BEGINNER, INTERMEDIATE, ADVANCED }

enum class SimInteractionRole {
    NONE,
    INTAKE_COLLECTOR,
    PROJECTILE_LAUNCHER,
    CONVEYOR_INDEXER,
}

data class SubsystemSimInteractionDocument(
    val role: SimInteractionRole = SimInteractionRole.NONE,
    /** Actuator whose accepted simulated output activates this interaction. */
    val triggerActuatorId: String? = null,
    val triggerThreshold: Double = 1.0,
    val storageCapacity: Int = 1,
    val intakeDistanceMeters: Double = 0.35,
    val captureRadiusMeters: Double = 0.15,
    val launchSpeedMps: Double = 8.0,
    val launchElevationDeg: Double = 45.0,
    val beamBreakFieldId: String? = null,
)

/** Simulator/mock implementation advertised by a hand-authored or generated subsystem. */
data class SubsystemSimulationDocument(
    val support: SubsystemSimulationSupport = SubsystemSimulationSupport.GENERATED_MOCK,
    val adapterClassName: String? = null,
    val interaction: SubsystemSimInteractionDocument = SubsystemSimInteractionDocument(),
)

/** Optional teaching information surfaced by the builder without inspecting Kotlin source. */
data class SubsystemTeachingDocument(
    val level: SubsystemTeachingLevel = SubsystemTeachingLevel.INTERMEDIATE,
    val summary: String = "",
    val documentationPath: String? = null,
    val concepts: List<String> = emptyList(),
)

/**
 * Explicit source contract for a subsystem implementation.
 *
 * Hand-authored implementations name their Gradle module, user-owned files, and runtime types.
 * ARES reads this metadata instead of scanning or interpreting Kotlin. Generated starters leave
 * project-specific source locations to the selected code-generation target.
 */
data class SubsystemImplementationDocument(
    val kind: SubsystemImplementationKind = SubsystemImplementationKind.GENERATED_STARTER,
    val ownership: SubsystemSourceOwnership = SubsystemSourceOwnership.GENERATED_STARTER,
    val modulePath: String? = null,
    val sourceFiles: List<String> = emptyList(),
    val subsystemClassName: String? = null,
    val ioContractClassName: String? = null,
    val hardwareAdapterClassName: String? = null,
    /** Importable module for a user-owned XRP Python subsystem (for example extensions.arm). */
    val pythonModuleName: String? = null,
    /** Physical-runtime factory accepting one hardware factory argument. */
    val pythonFactoryName: String? = null,
    /** Optional simulation factory accepting one hardware factory argument. */
    val pythonSimulationFactoryName: String? = null,
    val simulation: SubsystemSimulationDocument = SubsystemSimulationDocument(),
    val teaching: SubsystemTeachingDocument = SubsystemTeachingDocument(),
)

/** Hardware categories supported by the generated, cached IO boundary. */
enum class SubsystemHardwareKind {
    MOTOR,
    POSITIONAL_SERVO,
    CONTINUOUS_SERVO,
    /** Standalone duty-cycle or analog absolute encoder. */
    ABSOLUTE_ENCODER,
    /** Standalone incremental A/B encoder. */
    QUADRATURE_ENCODER,
    DIGITAL_INPUT,
    DIGITAL_OUTPUT,
    ANALOG_INPUT,
    PWM_OUTPUT,
    DISTANCE_SENSOR,
    IMU,
    COLOR_SENSOR,
    /** FRC pneumatic binary actuator. */
    SOLENOID,
    INDICATOR_LIGHT,
    BUZZER,
    PRISM_DRIVER,
}

/** True only when the generated physical adapter has an implemented, tested platform binding. */
fun SubsystemHardwareKind.supportsPlatform(platform: SubsystemPlatform): Boolean = when (platform) {
    SubsystemPlatform.FTC -> this !in setOf(
        SubsystemHardwareKind.SOLENOID,
        SubsystemHardwareKind.DIGITAL_OUTPUT,
        SubsystemHardwareKind.PWM_OUTPUT,
        SubsystemHardwareKind.BUZZER,
    )
    SubsystemPlatform.FRC -> this !in setOf(
        SubsystemHardwareKind.COLOR_SENSOR,
        SubsystemHardwareKind.DIGITAL_OUTPUT,
        SubsystemHardwareKind.PWM_OUTPUT,
        SubsystemHardwareKind.BUZZER,
    )
    SubsystemPlatform.XRP -> this in setOf(
        SubsystemHardwareKind.MOTOR,
        SubsystemHardwareKind.POSITIONAL_SERVO,
        SubsystemHardwareKind.DIGITAL_INPUT,
        SubsystemHardwareKind.DIGITAL_OUTPUT,
        SubsystemHardwareKind.ANALOG_INPUT,
        SubsystemHardwareKind.PWM_OUTPUT,
        SubsystemHardwareKind.DISTANCE_SENSOR,
        SubsystemHardwareKind.IMU,
        SubsystemHardwareKind.INDICATOR_LIGHT,
        SubsystemHardwareKind.BUZZER,
    )
}

/** Explicit cached signal read from one hardware device. */
enum class SubsystemMeasurementSource {
    MOTOR_POSITION_NATIVE,
    MOTOR_VELOCITY_NATIVE_PER_SECOND,
    MOTOR_CURRENT_AMPS,
    /** Standalone encoder position expressed in turns before descriptor scale/offset. */
    ENCODER_POSITION_TURNS,
    /** Standalone encoder velocity expressed in turns per second before descriptor conversion. */
    ENCODER_VELOCITY_TURNS_PER_SECOND,
    DIGITAL_STATE,
    ANALOG_VOLTAGE,
    REFLECTANCE_NORMALIZED,
    DISTANCE_METERS,
    IMU_YAW_RADIANS,
    IMU_YAW_RATE_RADIANS_PER_SECOND,
    IMU_PITCH_RADIANS,
    IMU_ROLL_RADIANS,
    IMU_GYRO_X_RADIANS_PER_SECOND,
    IMU_GYRO_Y_RADIANS_PER_SECOND,
    IMU_ACCEL_X_METERS_PER_SECOND_SQUARED,
    IMU_ACCEL_Y_METERS_PER_SECOND_SQUARED,
    IMU_ACCEL_Z_METERS_PER_SECOND_SQUARED,
    COLOR_ARGB,
}

enum class SubsystemValueType { DOUBLE, BOOLEAN, INT, STRING }

enum class SubsystemFieldRole { TARGET, MEASUREMENT, STATUS, CONFIGURATION }

enum class SubsystemControlStrategy {
    /** Target value is applied directly after clamping. */
    DIRECT,

    POSITION_PID,
    /** Position PID whose setpoint is constrained by a trapezoidal velocity/acceleration profile. */
    PROFILED_POSITION_PID,
    VELOCITY_PID,
    BANG_BANG,
    SERVO_POSITION,
}

/** Capability-first starting points. Templates configure safety; they never collapse boundaries. */
enum class SubsystemTemplate {
    SIMPLE_ACTUATOR,
    POSITION_CONTROLLED_MECHANISM,
    VELOCITY_CONTROLLED_MECHANISM,
    ELEVATOR_LIFT,
    ARM_PIVOT,
    FLYWHEEL_SHOOTER,
    INTAKE_CONVEYOR,
    DUAL_MOTOR_FOLLOWER,
    POSITIONAL_SERVO,
    CONTINUOUS_SERVO,
    SENSOR_ONLY_SUBSYSTEM,
    LIMIT_SWITCH_SENSOR,
    BEAM_BREAK_SENSOR,
    POTENTIOMETER_SENSOR,
    REFLECTANCE_SENSOR,
    ABSOLUTE_ENCODER_SENSOR,
    QUADRATURE_ENCODER_SENSOR,
    DISTANCE_SENSOR,
    IMU_SENSOR,
    PNEUMATIC_ACTUATOR,
    HOMED_MECHANISM,
    CURRENT_HOMED_MECHANISM,
    VELOCITY_HOMED_MECHANISM,
    COMPOSITE_MECHANISM,
    TWO_DOF_ARM,
    INDICATOR_LIGHT_PWM,
    DIGITAL_OUTPUT,
    PWM_OUTPUT,
    BUZZER_NOTE,
    PRISM_LED_DRIVER,
    ADVANCED_CUSTOM,
}

/** Whether the generated physical adapter for this starter exists on [platform]. */
fun SubsystemTemplate.supportsPlatform(platform: SubsystemPlatform): Boolean = when (platform) {
    SubsystemPlatform.FTC -> this !in setOf(
        SubsystemTemplate.PNEUMATIC_ACTUATOR,
        SubsystemTemplate.REFLECTANCE_SENSOR,
        SubsystemTemplate.DIGITAL_OUTPUT,
        SubsystemTemplate.PWM_OUTPUT,
        SubsystemTemplate.BUZZER_NOTE,
    )
    SubsystemPlatform.FRC -> this !in setOf(
        SubsystemTemplate.REFLECTANCE_SENSOR,
        SubsystemTemplate.DIGITAL_OUTPUT,
        SubsystemTemplate.PWM_OUTPUT,
        SubsystemTemplate.BUZZER_NOTE,
    )
    SubsystemPlatform.XRP -> this in setOf(
        SubsystemTemplate.SIMPLE_ACTUATOR,
        SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
        SubsystemTemplate.VELOCITY_CONTROLLED_MECHANISM,
        SubsystemTemplate.POSITIONAL_SERVO,
        SubsystemTemplate.LIMIT_SWITCH_SENSOR,
        SubsystemTemplate.BEAM_BREAK_SENSOR,
        SubsystemTemplate.POTENTIOMETER_SENSOR,
        SubsystemTemplate.REFLECTANCE_SENSOR,
        SubsystemTemplate.DISTANCE_SENSOR,
        SubsystemTemplate.IMU_SENSOR,
        SubsystemTemplate.INDICATOR_LIGHT_PWM,
        SubsystemTemplate.DIGITAL_OUTPUT,
        SubsystemTemplate.PWM_OUTPUT,
        SubsystemTemplate.BUZZER_NOTE,
        SubsystemTemplate.ADVANCED_CUSTOM,
    )
}

/** WPILib pneumatic module used by a generated FRC solenoid adapter. */
enum class SubsystemPneumaticsModuleType { REV_PH, CTRE_PCM }

/** Physical Control Hub orientation used to initialize a generated FTC IMU adapter. */
enum class SubsystemHubFacingDirection { UP, DOWN, FORWARD, BACKWARD, LEFT, RIGHT }

/** Unit-aware feedforward model combined with feedback before output clamping. */
enum class SubsystemFeedforwardKind {
    NONE,
    SIMPLE_MOTOR,
    ELEVATOR,
    ARM,
    TWO_DOF_ARM,
    FOUR_BAR_LINKAGE,
}

data class SubsystemLinkageDocument(
    val enabled: Boolean = false,
    val link1LengthMeters: Double = 0.35,
    val link2LengthMeters: Double = 0.25,
    val link1MassKg: Double = 0.5,
    val link2MassKg: Double = 0.3,
    val link1CenterOfMassMeters: Double = link1LengthMeters / 2.0,
    val link2CenterOfMassMeters: Double = link2LengthMeters / 2.0,
    val joint1MinRad: Double = -Math.PI,
    val joint1MaxRad: Double = Math.PI,
    val joint2MinRad: Double = -Math.PI,
    val joint2MaxRad: Double = Math.PI,
    val joint1ActuatorId: String? = null,
    val joint2ActuatorId: String? = null,
    val joint1AngleFieldId: String? = null,
    val joint2AngleFieldId: String? = null,
    /** Output torque at joint 1 per accepted motor volt, including gearing and efficiency. */
    val joint1TorquePerVoltNm: Double = 0.5,
    /** Output torque at joint 2 per accepted motor volt, including gearing and efficiency. */
    val joint2TorquePerVoltNm: Double = 0.35,
    val joint1DampingNmPerRadPerSec: Double = 0.08,
    val joint2DampingNmPerRadPerSec: Double = 0.05,
)

data class SubsystemFeedforwardDocument(
    val kind: SubsystemFeedforwardKind = SubsystemFeedforwardKind.NONE,
    /** Static friction compensation in output volts. */
    val kS: Double = 0.0,
    /** Velocity gain in output volts per selected velocity unit/second. */
    val kV: Double = 0.0,
    /** Acceleration gain in output volts per selected velocity unit/second². */
    val kA: Double = 0.0,
    /** Elevator constant or arm cosine gravity compensation in output volts. */
    val kG: Double = 0.0,
    /** Desired velocity; null uses the loop target for velocity-control loops and zero otherwise. */
    val velocityFieldId: String? = null,
    /** Desired acceleration; null means zero acceleration feedforward. */
    val accelerationFieldId: String? = null,
    /** Arm angle measurement in radians; required for ARM gravity compensation. */
    val gravityAngleFieldId: String? = null,
    /** Controlled serial-linkage joint (1 or 2); required only for TWO_DOF_ARM. */
    val linkageJoint: Int? = null,
)

/** Allocation-free trapezoidal setpoint constraints for profiled position control. */
data class SubsystemMotionProfileDocument(
    val maximumVelocity: Double = 1.0,
    val maximumAcceleration: Double = 2.0,
)

/**
 * How a mechanism establishes its physical reference.
 *
 * Stall methods are intentionally distinct from passive sensors: they require an explicit homing
 * request, bounded search output, fresh evidence for a dwell period, and a hard timeout.
 */
enum class SubsystemHomingMethod {
    NONE,
    DIGITAL_SENSOR,
    CURRENT_STALL,
    VELOCITY_STALL,
    CURRENT_AND_VELOCITY_STALL,
    CUSTOM_MEASUREMENT,
}

/** Comparison applied to one cached, typed measurement while establishing home. */
enum class SubsystemHomingComparison {
    TRUE,
    FALSE,
    AT_OR_ABOVE,
    AT_OR_BELOW,
    ABS_AT_OR_ABOVE,
    ABS_AT_OR_BELOW,
}

/** One item of independently cached evidence; every item must remain true for [dwellMs]. */
data class SubsystemHomingEvidenceDocument(
    val fieldId: String,
    val comparison: SubsystemHomingComparison,
    val threshold: Double? = null,
)

/**
 * Declarative homing state-machine contract shared by physical and mock adapters.
 *
 * [searchOutput] uses the selected actuator's command unit (volts for a motor). Every item of
 * [evidence] must remain true for [dwellMs]; [timeoutMs] stops and faults an unsuccessful attempt.
 */
data class SubsystemHomingDocument(
    val method: SubsystemHomingMethod = SubsystemHomingMethod.NONE,
    val actuatorId: String? = null,
    val searchOutput: Double? = null,
    val evidence: List<SubsystemHomingEvidenceDocument> = emptyList(),
    val dwellMs: Long = 250L,
    val timeoutMs: Long = 3_000L,
    val zeroPosition: Double = 0.0,
)

enum class FaultRecoveryActionKind {
    NONE,
    REVERSE_BRIEFLY,
    HOLD_POSITION,
    NEUTRAL_STOP,
}

data class SubsystemFaultRecoveryDocument(
    val enabled: Boolean = false,
    /** Independently controlled actuator used for the bounded recovery command. */
    val actuatorId: String? = null,
    /** Cached motor-current measurement used as jam evidence. */
    val currentFieldId: String? = null,
    val currentThresholdAmps: Double = 18.0,
    val currentDurationMs: Long = 250L,
    val recoveryAction: FaultRecoveryActionKind = FaultRecoveryActionKind.REVERSE_BRIEFLY,
    val reverseDurationMs: Long = 400L,
    val reverseDutyCycle: Double = -0.40,
    val maxRetries: Int = 3,
)

enum class InterlockComparison {
    LESS_THAN,
    GREATER_THAN,
    EQUALS_STATE,
    NOT_EQUALS_STATE,
}

data class SubsystemInterlockDocument(
    val interlockId: String,
    val targetSubsystemUid: String,
    val targetFieldId: String,
    val comparison: InterlockComparison = InterlockComparison.LESS_THAN,
    val thresholdValue: Double = 0.0,
    val targetStateName: String? = null,
    val forbiddenZoneDescription: String = "",
    val safeFallbackValue: Double? = null,
)

/**
 * Cross-platform safety requirements consumed by generated starters and verification.
 *
 * These values describe a contract, not an implementation shortcut. A custom adapter may use
 * vendor-specific mechanisms, but it must preserve the same observable fail-closed behavior.
 */
data class SubsystemSafetyDocument(
    /** Maximum accepted age for control feedback. Null is permitted only for sensor-free control. */
    val feedbackTimeoutMs: Long? = 250L,
    /** Physical-reference strategy. NONE means the mechanism does not require homing. */
    val homing: SubsystemHomingDocument = SubsystemHomingDocument(),
    /** Automatic fault recovery and anti-jam policies. */
    val faultRecovery: SubsystemFaultRecoveryDocument = SubsystemFaultRecoveryDocument(),
    /** Calibration must be explicitly established before non-neutral output is accepted. */
    val requiresCalibration: Boolean = false,
    /** Device configuration health participates in the output permit. */
    val requiresConfigurationHealth: Boolean = true,
    /** At least one finite, fresh current measurement is required for actuator mechanisms. */
    val requiresCurrentMonitoring: Boolean = false,
    /** Failed non-neutral and neutral writes latch a fault until an explicit successful neutral. */
    val latchOutputFaults: Boolean = true,
    val requiresExplicitNeutralRecovery: Boolean = true,
    val telemetryEnabled: Boolean = true,
    /** Periodic generated control/read/write paths must remain allocation-free after warmup. */
    val zeroAllocationPeriodic: Boolean = true,
)

/** Platform connection data. Only the fields required by the selected platform are populated. */
data class SubsystemHardwareConnection(
    val hardwareMapName: String? = null,
    val canId: Int? = null,
    val canBus: String = "rio",
    val channel: Int? = null,
    /** B channel for a generated FRC quadrature encoder. */
    val secondaryChannel: Int? = null,
    /** Required for a generated FRC solenoid; [canId] identifies the module. */
    val pneumaticsModuleType: SubsystemPneumaticsModuleType? = null,
)

/** One cached signal sampled from a device during the subsystem read phase. */
data class SubsystemMeasurementDocument(
    val fieldId: String,
    val source: SubsystemMeasurementSource,
    /** `stateValue = rawHardwareValue * scale + offset`. */
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    /** Optional per-signal freshness lease; null inherits the subsystem feedback timeout. */
    val maxAgeMs: Long? = null,
    /** Optional validity bounds checked after scale/offset conversion. */
    val validMinimum: Double? = null,
    val validMaximum: Double? = null,
)

/**
 * One actuator that receives the command of another actuator instead of owning a controller.
 * This relationship is explicit so physical IO, mocks, simulation, and verification cannot drift.
 */
data class SubsystemFollowerDocument(
    val leaderId: String,
    val transform: SubsystemFollowerTransform = SubsystemFollowerTransform.SAME_DIRECTION,
)

enum class SubsystemFollowerTransform {
    SAME_DIRECTION,
    INVERTED,
    /** Positional-servo mirror: follower = 1 - leader. */
    MIRRORED_POSITION,
}

/** Beginner-facing robot placement used only by simulator/dashboard visualization. */
enum class SubsystemVisualAnchor {
    UNSPECIFIED,
    LEFT_SIDE,
    RIGHT_SIDE,
    FRONT,
    REAR,
    CENTER,
    UNDERBODY,
}

/**
 * Location within the robot footprint. +X is forward and +Y is the robot's left side; values are
 * normalized so ±0.5 reaches the footprint edge. Physical output behavior never depends on this.
 */
data class SubsystemVisualPlacementDocument(
    val anchor: SubsystemVisualAnchor = SubsystemVisualAnchor.UNSPECIFIED,
    val forwardFraction: Double = 0.0,
    val leftFraction: Double = 0.0,
)

data class SubsystemHardwareDocument(
    val hardwareId: String,
    val displayName: String,
    val kind: SubsystemHardwareKind,
    val connection: SubsystemHardwareConnection = SubsystemHardwareConnection(),
    val required: Boolean = true,
    val inverted: Boolean = false,
    /** State fields populated by this device during the single cached sensor-read phase. */
    val measurements: List<SubsystemMeasurementDocument> = emptyList(),
    val currentLimitAmps: Double? = null,
    /** Required neutral command for actuators. Sensors leave this null. */
    val safeOutput: Double? = null,
    val description: String = "",
    /** Immutable editor identity; code ID renames do not change this value. */
    val uid: String = hardwareId,
    /** Null means independently controlled; otherwise this actuator follows exactly one leader. */
    val following: SubsystemFollowerDocument? = null,
    /** Counts per mechanical revolution for standalone incremental encoders. */
    val encoderCountsPerRevolution: Double? = null,
    /** FRC analog-distance conversion. FTC distance adapters already report meters. */
    val distanceMetersPerVolt: Double? = null,
    /** FTC Control Hub logo direction; meaningful only for an IMU. */
    val imuLogoFacingDirection: SubsystemHubFacingDirection? = null,
    /** FTC Control Hub USB direction; meaningful only for an IMU. */
    val imuUsbFacingDirection: SubsystemHubFacingDirection? = null,
    /** Optional footprint location for simulator/dashboard rendering of visible hardware. */
    val visualPlacement: SubsystemVisualPlacementDocument? = null,
)

/** A typed state value. Raw Kotlin expressions are deliberately not accepted. */
data class SubsystemStateFieldDocument(
    val fieldId: String,
    val displayName: String,
    val type: SubsystemValueType,
    val role: SubsystemFieldRole,
    val unit: String? = null,
    val defaultNumber: Double? = null,
    val defaultBoolean: Boolean? = null,
    val defaultInt: Int? = null,
    val defaultText: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val description: String = "",
    /** Immutable editor identity; code ID renames do not change this value. */
    val uid: String = fieldId,
)

/**
 * Periodic position-input contract for mechanisms such as turrets and continuous azimuth axes.
 *
 * Runtime angles remain radians. [minimumInput] and [maximumInput] describe one complete turn and
 * must span exactly 2π radians. Generated controllers wrap both position error and derivative
 * deltas so crossing the configured boundary follows the shortest angular path.
 */
data class SubsystemContinuousInputDocument(
    val enabled: Boolean = false,
    val minimumInput: Double = -Math.PI,
    val maximumInput: Double = Math.PI,
)

data class SubsystemControlLoopDocument(
    val loopId: String,
    val displayName: String,
    val strategy: SubsystemControlStrategy,
    val actuatorId: String,
    val targetFieldId: String,
    val measurementFieldId: String? = null,
    val kP: Double = 0.0,
    val kI: Double = 0.0,
    val kD: Double = 0.0,
    /** Used only by [SubsystemControlStrategy.PROFILED_POSITION_PID]. */
    val motionProfile: SubsystemMotionProfileDocument = SubsystemMotionProfileDocument(),
    /** Typed feedforward combined with feedback; NONE disables feedforward. */
    val feedforward: SubsystemFeedforwardDocument = SubsystemFeedforwardDocument(),
    /** First-order derivative filter time constant; zero disables filtering. */
    val derivativeFilterTimeConstantSeconds: Double = 0.02,
    /** Shortest-path angular error handling for position PID strategies. Radians only. */
    val continuousInput: SubsystemContinuousInputDocument = SubsystemContinuousInputDocument(),
    val tolerance: Double = 0.0,
    /** Extra error beyond [tolerance] required to restart a stopped bang-bang controller. */
    val hysteresis: Double = 0.0,
    val minimumOutput: Double = -12.0,
    val maximumOutput: Double = 12.0,
    val description: String = "",
    /** Immutable editor identity; code ID renames do not change this value. */
    val uid: String = loopId,
)

/**
 * Canonical subsystem authoring document stored in `.ares/subsystems`.
 *
 * The document describes hardware ownership, immutable Redux state, and output control. Generated
 * Kotlin remains deterministic and platform-specific while this model stays independent of the
 * FTC SDK, WPILib, and vendor libraries.
 */
data class SubsystemDocument(
    val schemaVersion: Int = ARES_SUBSYSTEM_SCHEMA_VERSION,
    val documentId: String,
    /** Friendly name shown to students; it may contain spaces and does not affect Kotlin symbols. */
    val displayName: String,
    /** PascalCase root used for generated Kotlin types and filenames. */
    val kotlinTypeName: String,
    val description: String = "",
    val platform: SubsystemPlatform,
    val revision: Int = 1,
    val parentContentHash: String? = null,
    val hardware: List<SubsystemHardwareDocument> = emptyList(),
    val stateFields: List<SubsystemStateFieldDocument> = emptyList(),
    val controlLoops: List<SubsystemControlLoopDocument> = emptyList(),
    /** Component-owned typed tuning declarations resolved by robot-owned named profiles. */
    val tuningParameters: List<TuningParameterDeclaration> = emptyList(),
    val template: SubsystemTemplate = SubsystemTemplate.ADVANCED_CUSTOM,
    val implementation: SubsystemImplementationDocument = SubsystemImplementationDocument(),
    /** Existing catalog actions exposed by a hand-authored implementation. */
    val capabilityActionKeys: List<String> = emptyList(),
    val safety: SubsystemSafetyDocument = SubsystemSafetyDocument(),
    /** Declarative safety interlocks between this subsystem and other mechanisms. */
    val interlocks: List<SubsystemInterlockDocument> = emptyList(),
    /** Multi-joint linkage geometry and non-linear kinematics. */
    val linkage: SubsystemLinkageDocument = SubsystemLinkageDocument(),
    /** Stable resource owned while an autonomous action commands this subsystem. */
    val autonomousResourceKey: String? = null,
    /** Required failures abort robot initialization; optional failures are reported and skipped. */
    val requiredAtStartup: Boolean = true,
    val generateMockIo: Boolean = true,
    val generateTest: Boolean = true,
    /** Immutable editor identity; document ID/Kotlin renames do not change this value. */
    val uid: String = documentId,
)

data class SubsystemValidationIssue(val path: String, val message: String)

fun SubsystemHardwareKind.compatibleMeasurementSources(): List<SubsystemMeasurementSource> = when (this) {
    SubsystemHardwareKind.MOTOR -> listOf(
        SubsystemMeasurementSource.MOTOR_POSITION_NATIVE,
        SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND,
        SubsystemMeasurementSource.MOTOR_CURRENT_AMPS,
    )
    SubsystemHardwareKind.ABSOLUTE_ENCODER -> listOf(SubsystemMeasurementSource.ENCODER_POSITION_TURNS)
    SubsystemHardwareKind.QUADRATURE_ENCODER -> listOf(
        SubsystemMeasurementSource.ENCODER_POSITION_TURNS,
        SubsystemMeasurementSource.ENCODER_VELOCITY_TURNS_PER_SECOND,
    )
    SubsystemHardwareKind.DIGITAL_INPUT -> listOf(SubsystemMeasurementSource.DIGITAL_STATE)
    SubsystemHardwareKind.ANALOG_INPUT -> listOf(
        SubsystemMeasurementSource.ANALOG_VOLTAGE,
        SubsystemMeasurementSource.REFLECTANCE_NORMALIZED,
    )
    SubsystemHardwareKind.DISTANCE_SENSOR -> listOf(SubsystemMeasurementSource.DISTANCE_METERS)
    SubsystemHardwareKind.IMU -> listOf(
        SubsystemMeasurementSource.IMU_YAW_RADIANS,
        SubsystemMeasurementSource.IMU_YAW_RATE_RADIANS_PER_SECOND,
        SubsystemMeasurementSource.IMU_PITCH_RADIANS,
        SubsystemMeasurementSource.IMU_ROLL_RADIANS,
        SubsystemMeasurementSource.IMU_GYRO_X_RADIANS_PER_SECOND,
        SubsystemMeasurementSource.IMU_GYRO_Y_RADIANS_PER_SECOND,
        SubsystemMeasurementSource.IMU_ACCEL_X_METERS_PER_SECOND_SQUARED,
        SubsystemMeasurementSource.IMU_ACCEL_Y_METERS_PER_SECOND_SQUARED,
        SubsystemMeasurementSource.IMU_ACCEL_Z_METERS_PER_SECOND_SQUARED,
    )
    SubsystemHardwareKind.COLOR_SENSOR -> listOf(SubsystemMeasurementSource.COLOR_ARGB)
    SubsystemHardwareKind.POSITIONAL_SERVO,
    SubsystemHardwareKind.CONTINUOUS_SERVO,
    SubsystemHardwareKind.DIGITAL_OUTPUT,
    SubsystemHardwareKind.PWM_OUTPUT,
    SubsystemHardwareKind.INDICATOR_LIGHT,
    SubsystemHardwareKind.BUZZER,
    SubsystemHardwareKind.PRISM_DRIVER,
    SubsystemHardwareKind.SOLENOID -> emptyList()
}

fun SubsystemMeasurementSource.valueType(): SubsystemValueType = when (this) {
    SubsystemMeasurementSource.MOTOR_POSITION_NATIVE,
    SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND,
    SubsystemMeasurementSource.MOTOR_CURRENT_AMPS,
    SubsystemMeasurementSource.ENCODER_POSITION_TURNS,
    SubsystemMeasurementSource.ENCODER_VELOCITY_TURNS_PER_SECOND,
    SubsystemMeasurementSource.ANALOG_VOLTAGE,
    SubsystemMeasurementSource.REFLECTANCE_NORMALIZED,
    SubsystemMeasurementSource.DISTANCE_METERS,
    SubsystemMeasurementSource.IMU_YAW_RADIANS,
    SubsystemMeasurementSource.IMU_YAW_RATE_RADIANS_PER_SECOND,
    SubsystemMeasurementSource.IMU_PITCH_RADIANS,
    SubsystemMeasurementSource.IMU_ROLL_RADIANS,
    SubsystemMeasurementSource.IMU_GYRO_X_RADIANS_PER_SECOND,
    SubsystemMeasurementSource.IMU_GYRO_Y_RADIANS_PER_SECOND,
    SubsystemMeasurementSource.IMU_ACCEL_X_METERS_PER_SECOND_SQUARED,
    SubsystemMeasurementSource.IMU_ACCEL_Y_METERS_PER_SECOND_SQUARED,
    SubsystemMeasurementSource.IMU_ACCEL_Z_METERS_PER_SECOND_SQUARED -> SubsystemValueType.DOUBLE
    SubsystemMeasurementSource.DIGITAL_STATE -> SubsystemValueType.BOOLEAN
    SubsystemMeasurementSource.COLOR_ARGB -> SubsystemValueType.INT
}

/** Canonical state-field unit required after measurement scale/offset conversion. */
fun SubsystemMeasurementSource.canonicalUnit(): String? = when (this) {
    SubsystemMeasurementSource.ENCODER_POSITION_TURNS,
    SubsystemMeasurementSource.IMU_YAW_RADIANS,
    SubsystemMeasurementSource.IMU_PITCH_RADIANS,
    SubsystemMeasurementSource.IMU_ROLL_RADIANS -> "rad"
    SubsystemMeasurementSource.ENCODER_VELOCITY_TURNS_PER_SECOND,
    SubsystemMeasurementSource.IMU_YAW_RATE_RADIANS_PER_SECOND,
    SubsystemMeasurementSource.IMU_GYRO_X_RADIANS_PER_SECOND,
    SubsystemMeasurementSource.IMU_GYRO_Y_RADIANS_PER_SECOND -> "rad/s"
    SubsystemMeasurementSource.IMU_ACCEL_X_METERS_PER_SECOND_SQUARED,
    SubsystemMeasurementSource.IMU_ACCEL_Y_METERS_PER_SECOND_SQUARED,
    SubsystemMeasurementSource.IMU_ACCEL_Z_METERS_PER_SECOND_SQUARED -> "m/s²"
    SubsystemMeasurementSource.DISTANCE_METERS -> "m"
    SubsystemMeasurementSource.MOTOR_CURRENT_AMPS -> "A"
    SubsystemMeasurementSource.ANALOG_VOLTAGE -> "V"
    SubsystemMeasurementSource.REFLECTANCE_NORMALIZED -> "normalized"
    else -> null
}
