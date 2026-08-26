package com.areslib.subsystem

import com.google.gson.GsonBuilder
import com.areslib.util.parseJsonElement
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.validateTuningParameterDeclarations
import java.security.MessageDigest

const val ARES_SUBSYSTEM_SCHEMA_VERSION: Int = 11

enum class SubsystemPlatform { FTC, FRC }

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
    ANALOG_INPUT,
    DISTANCE_SENSOR,
    IMU,
    COLOR_SENSOR,
    /** FRC pneumatic binary actuator. */
    SOLENOID,
    INDICATOR_LIGHT,
    PRISM_DRIVER,
}

/** True only when the generated physical adapter has an implemented, tested platform binding. */
fun SubsystemHardwareKind.supportsPlatform(platform: SubsystemPlatform): Boolean = when (platform) {
    SubsystemPlatform.FTC -> this != SubsystemHardwareKind.SOLENOID
    SubsystemPlatform.FRC -> this != SubsystemHardwareKind.COLOR_SENSOR
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
    DISTANCE_METERS,
    IMU_YAW_RADIANS,
    IMU_YAW_RATE_RADIANS_PER_SECOND,
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
    PRISM_LED_DRIVER,
    ADVANCED_CUSTOM,
}

/** Whether the generated physical adapter for this starter exists on [platform]. */
fun SubsystemTemplate.supportsPlatform(platform: SubsystemPlatform): Boolean = when (this) {
    SubsystemTemplate.PNEUMATIC_ACTUATOR -> platform == SubsystemPlatform.FRC
    else -> true
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

/**
 * Returns true when two controller-facing state fields use the same declared numeric unit.
 *
 * Blank units remain compatible for advanced/native-unit mechanisms. When both fields declare a
 * unit, however, the controller must not silently subtract values expressed in different units.
 * Common spelling aliases are normalized; this function intentionally does not perform conversion.
 */
fun subsystemControlUnitsCompatible(first: String?, second: String?): Boolean {
    if (first.isNullOrBlank() || second.isNullOrBlank()) return true
    return normalizedSubsystemUnit(first) == normalizedSubsystemUnit(second)
}

/** Unit predicates used by both validation and catalog-backed editors. Blank means advanced/native. */
fun subsystemUnitCanRepresentVelocity(unit: String?): Boolean = unit.isNullOrBlank() ||
    normalizedSubsystemUnit(unit) in setOf("m/s", "rad/s", "rot/s")

fun subsystemUnitCanRepresentAcceleration(unit: String?): Boolean = unit.isNullOrBlank() ||
    normalizedSubsystemUnit(unit) in setOf("m/s^2", "rad/s^2", "rot/s^2")

fun subsystemUnitIsCanonicalAngle(unit: String?): Boolean = !unit.isNullOrBlank() &&
    normalizedSubsystemUnit(unit) == "rad"

/**
 * Converts a native motor position/velocity sample into mechanism state units.
 *
 * The same scale applies to position and velocity because all three factors are ratios. Examples:
 * FTC encoder counts/rev, motor rev/output rev, and metres/output rev; or FRC rotor turns/rev,
 * motor rev/arm rev, and 2π radians/arm rev.
 */
fun subsystemMotorMeasurementScale(
    nativeUnitsPerMotorRevolution: Double,
    motorRevolutionsPerMechanismRevolution: Double,
    stateUnitsPerMechanismRevolution: Double,
): Double {
    require(nativeUnitsPerMotorRevolution.isFinite() && nativeUnitsPerMotorRevolution > 0.0) {
        "Native units per motor revolution must be finite and positive"
    }
    require(motorRevolutionsPerMechanismRevolution.isFinite() && motorRevolutionsPerMechanismRevolution > 0.0) {
        "Motor revolutions per mechanism revolution must be finite and positive"
    }
    require(stateUnitsPerMechanismRevolution.isFinite() && stateUnitsPerMechanismRevolution > 0.0) {
        "State units per mechanism revolution must be finite and positive"
    }
    return stateUnitsPerMechanismRevolution /
        (nativeUnitsPerMotorRevolution * motorRevolutionsPerMechanismRevolution)
}

private fun normalizedSubsystemUnit(unit: String): String = when (unit.trim().lowercase()) {
    "radian", "radians" -> "rad"
    "degree", "degrees", "°" -> "deg"
    "rotation", "rotations", "turn", "turns" -> "rot"
    "meter", "meters", "metre", "metres" -> "m"
    "meter/second", "meters/second", "metre/second", "metres/second" -> "m/s"
    "radian/second", "radians/second" -> "rad/s"
    "rotation/second", "rotations/second", "turn/second", "turns/second" -> "rot/s"
    "meter/second²", "meters/second²", "meter/second^2", "meters/second^2", "m/s²" -> "m/s^2"
    "radian/second²", "radians/second²", "radian/second^2", "radians/second^2", "rad/s²" -> "rad/s^2"
    "rotation/second²", "rotations/second²", "turn/second²", "turns/second²", "rot/s²" -> "rot/s^2"
    "volt", "volts" -> "v"
    "amp", "amps", "ampere", "amperes" -> "a"
    else -> unit.trim().lowercase().replace(" ", "")
}

fun validateSubsystemDocument(document: SubsystemDocument): List<SubsystemValidationIssue> = buildList {
    fun issue(path: String, message: String) {
        add(SubsystemValidationIssue(path, message))
    }

    if (document.schemaVersion != ARES_SUBSYSTEM_SCHEMA_VERSION) {
        issue("schemaVersion", "Unsupported subsystem schema ${document.schemaVersion}")
    }
    if (!document.documentId.matches(STABLE_ID)) {
        issue("documentId", "Document ID must be a stable lowercase key")
    } else if (!document.documentId.replace('-', '_').isUsableKotlinIdentifier()) {
        issue("documentId", "Document ID would create a Kotlin keyword package")
    }
    if (document.displayName.isBlank()) issue("displayName", "Subsystem display name is required")
    if (!document.kotlinTypeName.matches(PASCAL_CASE)) {
        issue("kotlinTypeName", "Kotlin type name must use PascalCase")
    }
    if (document.uid.isBlank()) issue("uid", "Subsystem UID is required")
    if (document.revision < 1) issue("revision", "Revision must be positive")
    if (document.parentContentHash != null && !document.parentContentHash.matches(SHA_256)) {
        issue("parentContentHash", "Parent content hash must be SHA-256")
    }
    if (document.hardware.isEmpty()) issue("hardware", "Add at least one hardware device")
    if (document.stateFields.isEmpty()) issue("stateFields", "Add at least one state field")
    if (document.generateTest && !document.generateMockIo) {
        issue("generateTest", "Generated starter tests require mock IO")
    }
    validateImplementation(document, ::issue)
    validateTuningParameterDeclarations(document.tuningParameters).forEach {
        issue("tuningParameters.${it.path}", it.message)
    }
    val tuningOwners = document.hardware.map { it.uid }.toSet() +
        document.controlLoops.map { it.uid }.toSet() + document.uid
    document.tuningParameters.filterNot { it.componentUid in tuningOwners }.forEach {
        issue("tuningParameters.componentUid", "Unknown subsystem component '${it.componentUid}'")
    }

    duplicateIds(document.hardware.map { it.hardwareId }).forEach {
        issue("hardware", "Hardware ID '$it' is duplicated")
    }
    duplicateIds(document.stateFields.map { it.fieldId }).forEach {
        issue("stateFields", "State field ID '$it' is duplicated")
    }
    duplicateIds(document.controlLoops.map { it.loopId }).forEach {
        issue("controlLoops", "Control loop ID '$it' is duplicated")
    }
    duplicateIds(document.hardware.map { it.uid }).forEach { issue("hardware", "Hardware UID '$it' is duplicated") }
    duplicateIds(document.stateFields.map { it.uid }).forEach { issue("stateFields", "State UID '$it' is duplicated") }
    duplicateIds(document.controlLoops.map { it.uid }).forEach { issue("controlLoops", "Control UID '$it' is duplicated") }
    document.controlLoops
        .groupBy { it.actuatorId }
        .filterValues { loops -> loops.size > 1 }
        .forEach { (actuatorId, loops) ->
            issue(
                "controlLoops",
                "Actuator '$actuatorId' has ${loops.size} controllers. Each independent actuator must have exactly one controller.",
            )
        }

    val hardwareById = document.hardware.associateBy { it.hardwareId }
    val fieldsById = document.stateFields.associateBy { it.fieldId }

    document.hardware.forEachIndexed { index, device ->
        val path = "hardware[$index]"
        if (!device.hardwareId.isUsableKotlinIdentifier()) issue("$path.hardwareId", "Hardware ID must be a Kotlin identifier, not a keyword")
        if (device.uid.isBlank()) issue("$path.uid", "Hardware UID is required")
        if (device.displayName.isBlank()) issue("$path.displayName", "Hardware display name is required")
        when (document.platform) {
            SubsystemPlatform.FTC -> {
                if (device.connection.hardwareMapName.isNullOrBlank()) {
                    issue("$path.connection.hardwareMapName", "FTC hardware requires a hardware-map name")
                }
                if (device.connection.canId != null || device.connection.channel != null) {
                    issue("$path.connection", "FTC hardware must not use FRC CAN/channel addressing")
                }
                if (device.currentLimitAmps != null) {
                    issue("$path.currentLimitAmps", "FTC DcMotorEx cannot enforce a controller current limit; use a current safety rule instead")
                }
                if (device.kind == SubsystemHardwareKind.SOLENOID) {
                    issue("$path.kind", "Generated pneumatic solenoids are available only for FRC projects")
                }
            }
            SubsystemPlatform.FRC -> when (device.kind) {
                SubsystemHardwareKind.MOTOR -> if (device.connection.canId == null || device.connection.canId !in 0..62) {
                    issue("$path.connection.canId", "FRC motors require a CAN ID from 0 to 62")
                }
                SubsystemHardwareKind.SOLENOID -> {
                    if (device.connection.canId == null || device.connection.canId !in 0..62) {
                        issue("$path.connection.canId", "FRC solenoids require a pneumatic module CAN ID from 0 to 62")
                    }
                    if (device.connection.channel == null || device.connection.channel !in 0..15) {
                        issue("$path.connection.channel", "FRC solenoid channels must be from 0 to 15")
                    }
                    if (device.connection.pneumaticsModuleType == null) {
                        issue("$path.connection.pneumaticsModuleType", "Select REV PH or CTRE PCM for an FRC solenoid")
                    }
                }
                SubsystemHardwareKind.QUADRATURE_ENCODER -> {
                    if (device.connection.channel == null || device.connection.channel !in 0..31) {
                        issue("$path.connection.channel", "FRC quadrature encoders require an A channel from 0 to 31")
                    }
                    if (device.connection.secondaryChannel == null || device.connection.secondaryChannel !in 0..31) {
                        issue("$path.connection.secondaryChannel", "FRC quadrature encoders require a B channel from 0 to 31")
                    }
                    if (device.connection.channel == device.connection.secondaryChannel) {
                        issue("$path.connection.secondaryChannel", "Quadrature encoder A and B channels must be different")
                    }
                }
                SubsystemHardwareKind.IMU -> if (
                    device.connection.canId != null || device.connection.channel != null ||
                    device.connection.secondaryChannel != null
                ) {
                    issue("$path.connection", "Generated FRC IMU uses the roboRIO onboard SPI port and has no CAN/DIO channel")
                }
                SubsystemHardwareKind.POSITIONAL_SERVO,
                SubsystemHardwareKind.CONTINUOUS_SERVO,
                SubsystemHardwareKind.ABSOLUTE_ENCODER,
                SubsystemHardwareKind.DIGITAL_INPUT,
                SubsystemHardwareKind.ANALOG_INPUT,
                SubsystemHardwareKind.DISTANCE_SENSOR,
                SubsystemHardwareKind.INDICATOR_LIGHT,
                SubsystemHardwareKind.PRISM_DRIVER -> if (device.connection.channel == null || device.connection.channel !in 0..31) {
                    issue("$path.connection.channel", "FRC channel must be from 0 to 31")
                }
                SubsystemHardwareKind.COLOR_SENSOR ->
                    issue("$path.kind", "Generated FRC color-sensor wiring is not supported yet")
            }
        }
        if (device.kind != SubsystemHardwareKind.QUADRATURE_ENCODER && device.connection.secondaryChannel != null) {
            issue("$path.connection.secondaryChannel", "Only quadrature encoders use a secondary channel")
        }
        if (device.kind != SubsystemHardwareKind.SOLENOID && device.connection.pneumaticsModuleType != null) {
            issue("$path.connection.pneumaticsModuleType", "Only solenoids use a pneumatic module type")
        }
        if (device.kind == SubsystemHardwareKind.QUADRATURE_ENCODER) {
            val counts = device.encoderCountsPerRevolution
            if (counts == null || !counts.isFinite() || counts <= 0.0) {
                issue("$path.encoderCountsPerRevolution", "Quadrature encoders require finite positive counts per revolution")
            }
        } else if (device.encoderCountsPerRevolution != null) {
            issue("$path.encoderCountsPerRevolution", "Only quadrature encoders use counts per revolution")
        }
        if (device.kind == SubsystemHardwareKind.DISTANCE_SENSOR && document.platform == SubsystemPlatform.FRC) {
            val conversion = device.distanceMetersPerVolt
            if (conversion == null || !conversion.isFinite() || conversion <= 0.0) {
                issue("$path.distanceMetersPerVolt", "Generated FRC analog distance sensors require positive meters per volt")
            }
        } else if (device.distanceMetersPerVolt != null && device.kind != SubsystemHardwareKind.DISTANCE_SENSOR) {
            issue("$path.distanceMetersPerVolt", "Only distance sensors use meters-per-volt conversion")
        }
        if (device.kind == SubsystemHardwareKind.IMU && document.platform == SubsystemPlatform.FTC) {
            val logo = device.imuLogoFacingDirection
            val usb = device.imuUsbFacingDirection
            if (logo == null) issue("$path.imuLogoFacingDirection", "FTC IMU requires the Control Hub logo direction")
            if (usb == null) issue("$path.imuUsbFacingDirection", "FTC IMU requires the Control Hub USB direction")
            if (logo != null && usb != null && !logo.isPerpendicularTo(usb)) {
                issue("$path.imuUsbFacingDirection", "Control Hub logo and USB directions must be perpendicular")
            }
        } else if (device.imuLogoFacingDirection != null || device.imuUsbFacingDirection != null) {
            issue(path, "Control Hub orientation is valid only for an FTC IMU")
        }
        device.visualPlacement?.let { placement ->
            if (device.kind != SubsystemHardwareKind.INDICATOR_LIGHT && device.kind != SubsystemHardwareKind.PRISM_DRIVER) {
                issue("$path.visualPlacement", "Visible robot placement is currently supported only for indicator and Prism lights")
            }
            if (!placement.forwardFraction.isFinite() || placement.forwardFraction !in -0.5..0.5) {
                issue("$path.visualPlacement.forwardFraction", "Forward placement must be finite and between -0.5 and 0.5")
            }
            if (!placement.leftFraction.isFinite() || placement.leftFraction !in -0.5..0.5) {
                issue("$path.visualPlacement.leftFraction", "Left placement must be finite and between -0.5 and 0.5")
            }
            if (device.kind == SubsystemHardwareKind.PRISM_DRIVER && placement.anchor != SubsystemVisualAnchor.UNDERBODY) {
                issue("$path.visualPlacement.anchor", "Prism lighting should use the underbody visual placement")
            }
        }
        device.currentLimitAmps?.let { limit ->
            if (!limit.isFinite() || limit <= 0.0) issue("$path.currentLimitAmps", "Current limit must be finite and positive")
            if (device.kind != SubsystemHardwareKind.MOTOR) issue("$path.currentLimitAmps", "Only motors use a current limit")
        }
        if (device.kind in ACTUATOR_KINDS) {
            val neutral = device.safeOutput
            if (neutral == null || !neutral.isFinite()) {
                issue("$path.safeOutput", "Actuators require a finite safe neutral output")
            } else when (device.kind) {
                SubsystemHardwareKind.MOTOR -> if (neutral !in -12.0..12.0) {
                    issue("$path.safeOutput", "Motor neutral must be within -12 to 12 volts")
                }
                SubsystemHardwareKind.CONTINUOUS_SERVO -> if (neutral !in -1.0..1.0) {
                    issue("$path.safeOutput", "Continuous-servo neutral must be within -1 to 1")
                }
                SubsystemHardwareKind.POSITIONAL_SERVO,
                SubsystemHardwareKind.INDICATOR_LIGHT -> if (neutral !in 0.0..1.0) {
                    issue("$path.safeOutput", "Positional-servo/indicator neutral must be within 0 to 1")
                }
                SubsystemHardwareKind.PRISM_DRIVER -> if (neutral < 0.0) {
                    issue("$path.safeOutput", "Prism driver neutral must be non-negative (0.0 for off)")
                }
                SubsystemHardwareKind.SOLENOID -> if (neutral != 0.0 && neutral != 1.0) {
                    issue("$path.safeOutput", "Solenoid neutral must be exactly 0 (off) or 1 (on)")
                }
                else -> Unit
            }
        } else if (device.safeOutput != null) {
            issue("$path.safeOutput", "Sensors do not accept an output neutral")
        }
        if (device.inverted && device.kind !in ACTUATOR_KINDS) {
            issue("$path.inverted", "Only motors and servos have a reversible hardware direction")
        }
        device.following?.let { follower ->
            val relationPath = "$path.following"
            val leader = hardwareById[follower.leaderId]
            when {
                device.kind !in ACTUATOR_KINDS -> issue(relationPath, "Only actuators can follow another actuator")
                follower.leaderId == device.hardwareId -> issue("$relationPath.leaderId", "An actuator cannot follow itself")
                leader == null -> issue("$relationPath.leaderId", "Unknown leader '${follower.leaderId}'")
                leader.kind != device.kind -> issue("$relationPath.leaderId", "Leader and follower must use the same actuator kind")
                leader.following != null -> issue("$relationPath.leaderId", "Follower chains are not supported; select an independent leader")
            }
            if (follower.transform == SubsystemFollowerTransform.MIRRORED_POSITION &&
                device.kind != SubsystemHardwareKind.POSITIONAL_SERVO
            ) {
                issue("$relationPath.transform", "Mirrored position is only valid for positional servos")
            }
            if (follower.transform == SubsystemFollowerTransform.INVERTED &&
                device.kind == SubsystemHardwareKind.POSITIONAL_SERVO
            ) {
                issue("$relationPath.transform", "Positional-servo followers use mirrored position rather than signed inversion")
            }
        }
        duplicateIds(device.measurements.map { it.fieldId }).forEach {
            issue("$path.measurements", "Cached field '$it' is sampled more than once from this device")
        }
        device.measurements.forEachIndexed { measurementIndex, measurement ->
            val measurementPath = "$path.measurements[$measurementIndex]"
            if (!measurement.scale.isFinite() || !measurement.offset.isFinite()) {
                issue("$measurementPath.scale", "Measurement conversion must be finite")
            }
            measurement.maxAgeMs?.let {
                if (it !in 20L..10_000L) issue("$measurementPath.maxAgeMs", "Measurement freshness must be from 20 to 10000 ms")
            }
            measurement.validMinimum?.let {
                if (!it.isFinite()) issue("$measurementPath.validMinimum", "Measurement minimum must be finite")
            }
            measurement.validMaximum?.let {
                if (!it.isFinite()) issue("$measurementPath.validMaximum", "Measurement maximum must be finite")
            }
            if (measurement.validMinimum != null && measurement.validMaximum != null &&
                measurement.validMinimum > measurement.validMaximum
            ) {
                issue(measurementPath, "Measurement validity minimum cannot exceed its maximum")
            }
            val fieldId = measurement.fieldId
            val field = fieldsById[fieldId]
            if (field == null) {
                issue("$measurementPath.fieldId", "Unknown measurement field '$fieldId'")
            } else if (field.role != SubsystemFieldRole.MEASUREMENT && field.role != SubsystemFieldRole.STATUS) {
                issue("$measurementPath.fieldId", "Hardware measurements must write a measurement or status field")
            } else {
                val source = measurement.source
                if (source !in device.kind.compatibleMeasurementSources()) {
                    issue("$measurementPath.source", "$source cannot be read from ${device.kind}")
                }
                val requiredType = source.valueType()
                if (field.type != requiredType) {
                    issue("$measurementPath.fieldId", "$source measurements require a ${requiredType.name} field")
                }
                source.canonicalUnit()?.let { canonicalUnit ->
                    if (field.unit != canonicalUnit) {
                        issue("$measurementPath.fieldId", "$source measurements require canonical unit '$canonicalUnit'")
                    }
                }
                if (requiredType != SubsystemValueType.DOUBLE && (measurement.scale != 1.0 || measurement.offset != 0.0)) {
                    issue("$measurementPath.scale", "Only numeric double measurements use scale and offset")
                }
            }
        }
    }

    document.stateFields.forEachIndexed { index, field ->
        val path = "stateFields[$index]"
        if (!field.fieldId.isUsableKotlinIdentifier()) issue("$path.fieldId", "State field ID must be a Kotlin identifier, not a keyword")
        if (field.uid.isBlank()) issue("$path.uid", "State field UID is required")
        if (field.displayName.isBlank()) issue("$path.displayName", "State field display name is required")
        if (field.unit?.isBlank() == true) issue("$path.unit", "Unit must be omitted or non-blank")
        field.minimum?.let { if (!it.isFinite()) issue("$path.minimum", "Minimum must be finite") }
        field.maximum?.let { if (!it.isFinite()) issue("$path.maximum", "Maximum must be finite") }
        if (field.minimum != null && field.maximum != null && field.minimum > field.maximum) {
            issue(path, "Minimum cannot exceed maximum")
        }
        when (field.type) {
            SubsystemValueType.DOUBLE -> {
                val value = field.defaultNumber
                if (value == null || !value.isFinite()) issue("$path.defaultNumber", "Double fields require a finite default")
                if (field.defaultBoolean != null || field.defaultInt != null || field.defaultText != null) {
                    issue(path, "Double field contains a default for another type")
                }
                if (value != null && field.minimum != null && value < field.minimum) issue(path, "Default is below the minimum")
                if (value != null && field.maximum != null && value > field.maximum) issue(path, "Default is above the maximum")
            }
            SubsystemValueType.BOOLEAN -> {
                if (field.defaultBoolean == null) issue("$path.defaultBoolean", "Boolean fields require a default")
                if (field.defaultNumber != null || field.defaultInt != null || field.defaultText != null) issue(path, "Boolean field contains a default for another type")
                if (field.minimum != null || field.maximum != null) issue(path, "Boolean fields cannot have numeric limits")
            }
            SubsystemValueType.INT -> {
                if (field.defaultInt == null) issue("$path.defaultInt", "Int fields require a default")
                if (field.defaultNumber != null || field.defaultBoolean != null || field.defaultText != null) issue(path, "Int field contains a default for another type")
            }
            SubsystemValueType.STRING -> {
                if (field.defaultText == null) issue("$path.defaultText", "String fields require a default")
                if (field.defaultNumber != null || field.defaultBoolean != null || field.defaultInt != null) issue(path, "String field contains a default for another type")
                if (field.minimum != null || field.maximum != null) issue(path, "String fields cannot have numeric limits")
            }
        }
    }

    document.controlLoops.forEachIndexed { index, loop ->
        val path = "controlLoops[$index]"
        if (!loop.loopId.isUsableKotlinIdentifier()) issue("$path.loopId", "Control loop ID must be a Kotlin identifier, not a keyword")
        if (loop.uid.isBlank()) issue("$path.uid", "Control loop UID is required")
        if (loop.displayName.isBlank()) issue("$path.displayName", "Control loop display name is required")
        val actuator = hardwareById[loop.actuatorId]
        if (actuator == null) {
            issue("$path.actuatorId", "Unknown actuator '${loop.actuatorId}'")
        } else if (actuator.kind !in ACTUATOR_KINDS) {
            issue("$path.actuatorId", "Selected hardware is a sensor, not an actuator")
        } else if (actuator.following != null) {
            issue("$path.actuatorId", "A follower cannot own a controller; control its leader instead")
        }
        val target = fieldsById[loop.targetFieldId]
        if (target == null) {
            issue("$path.targetFieldId", "Unknown target field '${loop.targetFieldId}'")
        } else {
            if (target.role != SubsystemFieldRole.TARGET && target.role != SubsystemFieldRole.CONFIGURATION) {
                issue("$path.targetFieldId", "Control targets must use a target or configuration field")
            }
            if (target.type !in NUMERIC_TYPES) issue("$path.targetFieldId", "Control targets must be numeric")
        }
        val needsMeasurement = loop.strategy in CLOSED_LOOP_STRATEGIES
        val measurement = loop.measurementFieldId?.let(fieldsById::get)
        if (needsMeasurement && measurement == null) issue("$path.measurementFieldId", "This strategy requires a measurement field")
        if (measurement != null && measurement.type !in NUMERIC_TYPES) issue("$path.measurementFieldId", "Control measurements must be numeric")
        if (needsMeasurement && target != null && measurement != null &&
            !subsystemControlUnitsCompatible(target.unit, measurement.unit)
        ) {
            issue(
                "$path.measurementFieldId",
                "Target '${target.fieldId}' uses ${target.unit} but feedback '${measurement.fieldId}' uses ${measurement.unit}. Convert both to the same unit before control.",
            )
        }
        if (loop.strategy == SubsystemControlStrategy.SERVO_POSITION && actuator?.kind != SubsystemHardwareKind.POSITIONAL_SERVO) {
            issue("$path.strategy", "Servo-position control requires a positional servo")
        }
        if (loop.strategy != SubsystemControlStrategy.SERVO_POSITION && actuator?.kind == SubsystemHardwareKind.POSITIONAL_SERVO) {
            issue("$path.strategy", "Positional servos require servo-position control")
        }
        listOf(
            loop.kP,
            loop.kI,
            loop.kD,
            loop.feedforward.kS,
            loop.feedforward.kV,
            loop.feedforward.kA,
            loop.feedforward.kG,
            loop.derivativeFilterTimeConstantSeconds,
            loop.continuousInput.minimumInput,
            loop.continuousInput.maximumInput,
            loop.tolerance,
            loop.hysteresis,
            loop.minimumOutput,
            loop.maximumOutput,
        )
            .forEach { value -> if (!value.isFinite()) issue(path, "Controller values must be finite") }
        if (loop.derivativeFilterTimeConstantSeconds < 0.0) {
            issue("$path.derivativeFilterTimeConstantSeconds", "Derivative filter time cannot be negative")
        }
        if (loop.tolerance < 0.0) issue("$path.tolerance", "Tolerance cannot be negative")
        if (loop.hysteresis < 0.0) issue("$path.hysteresis", "Hysteresis cannot be negative")
        if (loop.strategy != SubsystemControlStrategy.BANG_BANG && loop.hysteresis != 0.0) {
            issue("$path.hysteresis", "Restart hysteresis is available only for bang-bang control")
        }
        if (loop.continuousInput.enabled) {
            if (loop.strategy !in CONTINUOUS_POSITION_STRATEGIES) {
                issue("$path.continuousInput.enabled", "Continuous input is available only for position PID control")
            }
            if (target != null && !subsystemUnitIsCanonicalAngle(target.unit)) {
                issue("$path.targetFieldId", "Continuous position targets must use canonical radians (rad)")
            }
            if (measurement != null && !subsystemUnitIsCanonicalAngle(measurement.unit)) {
                issue("$path.measurementFieldId", "Continuous position feedback must use canonical radians (rad)")
            }
            val period = loop.continuousInput.maximumInput - loop.continuousInput.minimumInput
            if (loop.continuousInput.minimumInput >= loop.continuousInput.maximumInput) {
                issue("$path.continuousInput", "Continuous input minimum must be below maximum")
            } else if (kotlin.math.abs(period - 2.0 * Math.PI) > 1e-4) {
                issue("$path.continuousInput", "Continuous angle range must span one full turn (2π radians)")
            }
        }
        if (loop.minimumOutput >= loop.maximumOutput) issue(path, "Minimum output must be below maximum output")
        val profile = loop.motionProfile
        if (!profile.maximumVelocity.isFinite() || profile.maximumVelocity <= 0.0) {
            issue("$path.motionProfile.maximumVelocity", "Profile maximum velocity must be finite and positive")
        }
        if (!profile.maximumAcceleration.isFinite() || profile.maximumAcceleration <= 0.0) {
            issue("$path.motionProfile.maximumAcceleration", "Profile maximum acceleration must be finite and positive")
        }
        if (loop.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID && actuator?.kind != SubsystemHardwareKind.MOTOR) {
            issue("$path.strategy", "Profiled position control currently requires a motor actuator")
        }
        validateFeedforward(document, loop, fieldsById, path, ::issue)
    }

    document.hardware.filter { it.kind in ACTUATOR_KINDS && it.following == null }.forEach { actuator ->
        if (document.controlLoops.none { it.actuatorId == actuator.hardwareId }) {
            issue("hardware.${actuator.hardwareId}", "Actuator '${actuator.displayName}' is not controlled by any loop")
        }
    }
    val hasActuators = document.hardware.any { it.kind in ACTUATOR_KINDS }
    document.safety.feedbackTimeoutMs?.let {
        if (it !in 20L..10_000L) issue("safety.feedbackTimeoutMs", "Feedback timeout must be from 20 to 10000 ms")
    }
    if (hasActuators && document.controlLoops.any { it.strategy in CLOSED_LOOP_STRATEGIES } &&
        document.safety.feedbackTimeoutMs == null
    ) {
        issue("safety.feedbackTimeoutMs", "Closed-loop mechanisms require a feedback timeout")
    }
    validateHoming(document, hardwareById, fieldsById, ::issue)
    validateFaultRecovery(document, ::issue)
    validateInterlocks(document, ::issue)
    validateLinkage(document, ::issue)
    validateSimInteraction(document, ::issue)
    if (document.safety.requiresExplicitNeutralRecovery && !document.safety.latchOutputFaults) {
        issue("safety.requiresExplicitNeutralRecovery", "Explicit neutral recovery requires fault latching")
    }
    if (document.safety.requiresCurrentMonitoring && document.hardware.none { device ->
            device.measurements.any { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }
        }
    ) {
        issue("safety.requiresCurrentMonitoring", "Current monitoring requires a cached motor-current measurement")
    }
    if (!hasActuators && (document.safety.requiresCurrentMonitoring || document.safety.latchOutputFaults)) {
        issue("safety", "Sensor-only subsystems cannot require actuator current monitoring or output fault latching")
    }
    document.autonomousResourceKey?.let {
        if (!it.matches(STABLE_ID)) issue("autonomousResourceKey", "Autonomous resource key must be a stable lowercase key")
    }
}

/**
 * Validates relationships that cannot be proven from one subsystem document in isolation.
 * Missing or ambiguous interlock targets are build errors, never runtime permits.
 */
fun validateSubsystemDocuments(documents: List<SubsystemDocument>): List<SubsystemValidationIssue> = buildList {
    val byUid = documents.groupBy { it.uid }
    byUid.filterValues { it.size > 1 }.keys.sorted().forEach { uid ->
        add(SubsystemValidationIssue("subsystems", "Subsystem UID '$uid' is duplicated"))
    }

    documents.forEach { owner ->
        owner.interlocks.forEachIndexed { index, interlock ->
            val path = "subsystems[${owner.documentId}].interlocks[$index]"
            val target = byUid[interlock.targetSubsystemUid]?.singleOrNull()
            if (target == null) {
                add(
                    SubsystemValidationIssue(
                        "$path.targetSubsystemUid",
                        "Interlock target '${interlock.targetSubsystemUid}' does not resolve to exactly one subsystem",
                    ),
                )
                return@forEachIndexed
            }
            if (!target.implementation.kind.isAresGenerated()) {
                add(
                    SubsystemValidationIssue(
                        "$path.targetSubsystemUid",
                        "Generated interlocks require a generated target state; '${target.uid}' is hand-authored",
                    ),
                )
                return@forEachIndexed
            }
            val field = target.stateFields.singleOrNull { it.fieldId == interlock.targetFieldId }
            if (field == null) {
                add(
                    SubsystemValidationIssue(
                        "$path.targetFieldId",
                        "Target subsystem '${target.uid}' has no state field '${interlock.targetFieldId}'",
                    ),
                )
                return@forEachIndexed
            }
            when (interlock.comparison) {
                InterlockComparison.LESS_THAN,
                InterlockComparison.GREATER_THAN -> if (field.type !in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) {
                    add(SubsystemValidationIssue("$path.comparison", "Ordered interlocks require a numeric target field"))
                }
                InterlockComparison.EQUALS_STATE,
                InterlockComparison.NOT_EQUALS_STATE -> when (field.type) {
                    SubsystemValueType.BOOLEAN -> if (interlock.targetStateName?.lowercase() !in setOf("true", "false")) {
                        add(SubsystemValidationIssue("$path.targetStateName", "Boolean equality requires true or false"))
                    }
                    SubsystemValueType.STRING -> if (interlock.targetStateName.isNullOrBlank()) {
                        add(SubsystemValidationIssue("$path.targetStateName", "String equality requires an expected state value"))
                    }
                    SubsystemValueType.DOUBLE,
                    SubsystemValueType.INT -> Unit
                }
            }
        }
    }
}

private fun validateFeedforward(
    document: SubsystemDocument,
    loop: SubsystemControlLoopDocument,
    fieldsById: Map<String, SubsystemStateFieldDocument>,
    path: String,
    issue: (path: String, message: String) -> Unit,
) {
    val feedforward = loop.feedforward
    if (feedforward.kind == SubsystemFeedforwardKind.NONE) {
        if (feedforward.kS != 0.0 || feedforward.kV != 0.0 || feedforward.kA != 0.0 || feedforward.kG != 0.0 ||
            feedforward.velocityFieldId != null || feedforward.accelerationFieldId != null ||
            feedforward.gravityAngleFieldId != null || feedforward.linkageJoint != null
        ) {
            issue("$path.feedforward", "Select a feedforward model before configuring its gains or fields")
        }
        return
    }
    if (loop.strategy !in setOf(
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
            SubsystemControlStrategy.VELOCITY_PID,
        )
    ) {
        issue("$path.feedforward", "Feedforward requires a PID-based motor controller")
    }
    if (loop.strategy == SubsystemControlStrategy.SERVO_POSITION) {
        issue("$path.feedforward", "Generated positional-servo control does not use voltage feedforward")
    }
    listOf(
        "velocityFieldId" to feedforward.velocityFieldId,
        "accelerationFieldId" to feedforward.accelerationFieldId,
        "gravityAngleFieldId" to feedforward.gravityAngleFieldId,
    ).forEach { (name, id) ->
        if (id != null && fieldsById[id]?.type !in NUMERIC_TYPES) {
            issue("$path.feedforward.$name", "Feedforward fields must reference numeric state values")
        }
    }
    feedforward.velocityFieldId?.let { id ->
        val field = fieldsById[id]
        if (field != null && !subsystemUnitCanRepresentVelocity(field.unit)) {
            issue("$path.feedforward.velocityFieldId", "Desired velocity must use m/s, rad/s, rot/s, or an explicitly unitless advanced field")
        }
    }
    feedforward.accelerationFieldId?.let { id ->
        val field = fieldsById[id]
        if (field != null && !subsystemUnitCanRepresentAcceleration(field.unit)) {
            issue("$path.feedforward.accelerationFieldId", "Desired acceleration must use m/s², rad/s², rot/s², or an explicitly unitless advanced field")
        }
    }
    if (feedforward.kind == SubsystemFeedforwardKind.ARM && feedforward.gravityAngleFieldId == null) {
        issue("$path.feedforward.gravityAngleFieldId", "Arm feedforward requires an angle measurement in radians")
    }
    if (feedforward.kind == SubsystemFeedforwardKind.ARM) {
        feedforward.gravityAngleFieldId?.let { id ->
            val field = fieldsById[id]
            if (field != null && !subsystemUnitIsCanonicalAngle(field.unit)) {
                issue("$path.feedforward.gravityAngleFieldId", "Arm gravity angle must declare canonical radians (rad)")
            }
        }
    }
    if (feedforward.kind != SubsystemFeedforwardKind.ARM && feedforward.gravityAngleFieldId != null) {
        issue("$path.feedforward.gravityAngleFieldId", "Only arm feedforward uses a gravity angle field")
    }
    if (feedforward.kind == SubsystemFeedforwardKind.FOUR_BAR_LINKAGE) {
        issue(
            "$path.feedforward.kind",
            "Generated four-bar feedforward is not available yet; use a hand-authored closed-chain controller",
        )
    }
    if (feedforward.kind == SubsystemFeedforwardKind.TWO_DOF_ARM) {
        if (!document.linkage.enabled) {
            issue("$path.feedforward.kind", "2-DOF feedforward requires an enabled linkage model")
        }
        if (feedforward.linkageJoint == null || feedforward.linkageJoint !in 1..2) {
            issue("$path.feedforward.linkageJoint", "2-DOF feedforward must select linkage joint 1 or 2")
        } else {
            val expectedActuator = if (feedforward.linkageJoint == 1) {
                document.linkage.joint1ActuatorId
            } else {
                document.linkage.joint2ActuatorId
            }
            if (expectedActuator != null && loop.actuatorId != expectedActuator) {
                issue(
                    "$path.actuatorId",
                    "The selected 2-DOF joint is mapped to actuator '$expectedActuator', not '${loop.actuatorId}'",
                )
            }
        }
    } else if (feedforward.linkageJoint != null) {
        issue("$path.feedforward.linkageJoint", "Only 2-DOF feedforward selects a linkage joint")
    }
}

private fun validateHoming(
    document: SubsystemDocument,
    hardwareById: Map<String, SubsystemHardwareDocument>,
    fieldsById: Map<String, SubsystemStateFieldDocument>,
    issue: (path: String, message: String) -> Unit,
) {
    val homing = document.safety.homing
    if (homing.method == SubsystemHomingMethod.NONE) {
        if (homing.actuatorId != null || homing.searchOutput != null || homing.evidence.isNotEmpty()) {
            issue("safety.homing", "A mechanism without homing cannot declare a homing actuator, output, or evidence")
        }
        return
    }

    val actuator = homing.actuatorId?.let(hardwareById::get)
    if (actuator == null) {
        issue("safety.homing.actuatorId", "Homing requires a known actuator")
    } else if (actuator.kind != SubsystemHardwareKind.MOTOR) {
        issue("safety.homing.actuatorId", "Generated homing currently requires a motor actuator")
    } else if (actuator.following != null) {
        issue("safety.homing.actuatorId", "A follower cannot own a homing sequence; home its leader")
    }
    val output = homing.searchOutput
    if (output == null || !output.isFinite() || output == 0.0) {
        issue("safety.homing.searchOutput", "Homing requires a finite, non-zero search output")
    } else if (output !in -4.0..4.0) {
        issue("safety.homing.searchOutput", "Generated motor homing is limited to -4 to 4 volts")
    }
    if (homing.dwellMs !in 40L..2_000L) {
        issue("safety.homing.dwellMs", "Homing evidence dwell must be from 40 to 2000 ms")
    }
    if (homing.timeoutMs !in 250L..15_000L || homing.timeoutMs <= homing.dwellMs) {
        issue("safety.homing.timeoutMs", "Homing timeout must exceed dwell and be from 250 to 15000 ms")
    }
    if (!homing.zeroPosition.isFinite()) issue("safety.homing.zeroPosition", "Home position must be finite")
    if (homing.evidence.isEmpty()) issue("safety.homing.evidence", "Homing requires at least one cached measurement")
    duplicateIds(homing.evidence.map { it.fieldId }).forEach {
        issue("safety.homing.evidence", "Homing evidence '$it' is duplicated")
    }

    val measurementSources = document.hardware.flatMap { device ->
        device.measurements.map { it.fieldId to it.source }
    }.toMap()
    homing.evidence.forEachIndexed { index, evidence ->
        val path = "safety.homing.evidence[$index]"
        val field = fieldsById[evidence.fieldId]
        val source = measurementSources[evidence.fieldId]
        if (field == null || source == null) {
            issue("$path.fieldId", "Homing evidence must reference a cached hardware measurement")
            return@forEachIndexed
        }
        val booleanComparison = evidence.comparison == SubsystemHomingComparison.TRUE ||
            evidence.comparison == SubsystemHomingComparison.FALSE
        if (booleanComparison && field.type != SubsystemValueType.BOOLEAN) {
            issue("$path.comparison", "TRUE/FALSE homing evidence requires a Boolean measurement")
        }
        if (!booleanComparison && field.type !in NUMERIC_TYPES) {
            issue("$path.comparison", "Threshold homing evidence requires a numeric measurement")
        }
        if (booleanComparison && evidence.threshold != null) {
            issue("$path.threshold", "Boolean homing evidence does not use a threshold")
        }
        if (!booleanComparison && (evidence.threshold == null || !evidence.threshold.isFinite())) {
            issue("$path.threshold", "Numeric homing evidence requires a finite threshold")
        }
        when (homing.method) {
            SubsystemHomingMethod.DIGITAL_SENSOR -> if (source != SubsystemMeasurementSource.DIGITAL_STATE) {
                issue("$path.fieldId", "Digital-sensor homing requires a digital-state measurement")
            }
            SubsystemHomingMethod.CURRENT_STALL -> if (source != SubsystemMeasurementSource.MOTOR_CURRENT_AMPS) {
                issue("$path.fieldId", "Current-stall homing requires a motor-current measurement")
            }
            SubsystemHomingMethod.VELOCITY_STALL -> if (source != SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND) {
                issue("$path.fieldId", "Velocity-stall homing requires a motor-velocity measurement")
            }
            SubsystemHomingMethod.CURRENT_AND_VELOCITY_STALL -> Unit
            SubsystemHomingMethod.CUSTOM_MEASUREMENT -> Unit
            SubsystemHomingMethod.NONE -> Unit
        }
    }
    if (homing.method == SubsystemHomingMethod.CURRENT_AND_VELOCITY_STALL) {
        val sources = homing.evidence.mapNotNull { measurementSources[it.fieldId] }.toSet()
        if (SubsystemMeasurementSource.MOTOR_CURRENT_AMPS !in sources ||
            SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND !in sources
        ) {
            issue("safety.homing.evidence", "Combined stall homing requires both current and velocity evidence")
        }
    }
    if (homing.method == SubsystemHomingMethod.CURRENT_STALL ||
        homing.method == SubsystemHomingMethod.CURRENT_AND_VELOCITY_STALL
    ) {
        if (!document.safety.requiresCurrentMonitoring) {
            issue("safety.requiresCurrentMonitoring", "Current-based homing requires current monitoring")
        }
    }
    if (document.safety.feedbackTimeoutMs == null) {
        issue("safety.feedbackTimeoutMs", "Homing requires a feedback timeout")
    }
}

private fun validateFaultRecovery(
    document: SubsystemDocument,
    issue: (path: String, message: String) -> Unit,
) {
    val recovery = document.safety.faultRecovery
    if (!recovery.enabled) return
    if (!document.safety.requiresCurrentMonitoring) {
        issue("safety.requiresCurrentMonitoring", "Automatic fault recovery requires cached current monitoring")
    }
    val actuator = recovery.actuatorId?.let { id -> document.hardware.singleOrNull { it.hardwareId == id } }
    if (actuator == null || actuator.following != null ||
        actuator.kind !in setOf(SubsystemHardwareKind.MOTOR, SubsystemHardwareKind.CONTINUOUS_SERVO)
    ) {
        issue("safety.faultRecovery.actuatorId", "Automatic recovery requires an independently controlled motor or continuous servo")
    }
    val currentSource = actuator?.measurements?.singleOrNull { it.fieldId == recovery.currentFieldId }
    if (currentSource?.source != SubsystemMeasurementSource.MOTOR_CURRENT_AMPS) {
        issue("safety.faultRecovery.currentFieldId", "Automatic recovery requires a cached motor-current field")
    }
    if (!recovery.currentThresholdAmps.isFinite() || recovery.currentThresholdAmps <= 0.0) {
        issue("safety.faultRecovery.currentThresholdAmps", "Current threshold must be finite and positive")
    }
    if (recovery.currentDurationMs !in 50L..5_000L) {
        issue("safety.faultRecovery.currentDurationMs", "Current stall duration must be from 50 to 5000 ms")
    }
    if (recovery.reverseDurationMs !in 50L..5_000L) {
        issue("safety.faultRecovery.reverseDurationMs", "Reverse duration must be from 50 to 5000 ms")
    }
    if (!recovery.reverseDutyCycle.isFinite() || recovery.reverseDutyCycle !in -1.0..1.0) {
        issue("safety.faultRecovery.reverseDutyCycle", "Reverse duty cycle must be between -1.0 and 1.0")
    }
    if (recovery.maxRetries !in 1..10) {
        issue("safety.faultRecovery.maxRetries", "Max retries must be between 1 and 10")
    }
    if (recovery.recoveryAction in setOf(FaultRecoveryActionKind.NONE, FaultRecoveryActionKind.HOLD_POSITION)) {
        issue(
            "safety.faultRecovery.recoveryAction",
            "Generated recovery supports bounded reverse or latched neutral stop; hold-position requires a hand-authored controller",
        )
    }
}

private fun validateInterlocks(
    document: SubsystemDocument,
    issue: (path: String, message: String) -> Unit,
) {
    val duplicateInterlocks = duplicateIds(document.interlocks.map { it.interlockId })
    duplicateInterlocks.forEach { issue("interlocks", "Interlock ID '$it' is duplicated") }
    document.interlocks.forEachIndexed { index, interlock ->
        val path = "interlocks[$index]"
        if (interlock.targetSubsystemUid.isBlank()) {
            issue("$path.targetSubsystemUid", "Target subsystem UID is required")
        }
        if (interlock.targetFieldId.isBlank()) {
            issue("$path.targetFieldId", "Target field ID is required")
        }
        if (!interlock.thresholdValue.isFinite()) {
            issue("$path.thresholdValue", "Threshold value must be finite")
        }
    }
}

private fun validateImplementation(
    document: SubsystemDocument,
    issue: (path: String, message: String) -> Unit,
) {
    val implementation = document.implementation
    val duplicateSourceFiles = duplicateIds(implementation.sourceFiles)
    duplicateSourceFiles.forEach { issue("implementation.sourceFiles", "Source file '$it' is duplicated") }
    implementation.sourceFiles.forEachIndexed { index, path ->
        if (!path.isSafeProjectRelativeKotlinPath()) {
            issue(
                "implementation.sourceFiles[$index]",
                "Source files must be normalized project-relative Kotlin paths",
            )
        }
    }
    implementation.modulePath?.let { modulePath ->
        if (!modulePath.matches(GRADLE_MODULE_PATH)) {
            issue("implementation.modulePath", "Module path must be a Gradle project path such as ':TeamCode'")
        }
    }
    listOf(
        "subsystemClassName" to implementation.subsystemClassName,
        "ioContractClassName" to implementation.ioContractClassName,
        "hardwareAdapterClassName" to implementation.hardwareAdapterClassName,
        "simulation.adapterClassName" to implementation.simulation.adapterClassName,
    ).forEach { (field, className) ->
        if (className != null && !className.matches(QUALIFIED_KOTLIN_NAME)) {
            issue("implementation.$field", "Class name must be a fully qualified Kotlin name")
        }
    }
    val teaching = implementation.teaching
    if (teaching.documentationPath != null && !teaching.documentationPath.isSafeProjectRelativePath()) {
        issue("implementation.teaching.documentationPath", "Documentation must use a normalized project-relative path")
    }
    if (teaching.summary.isBlank() && teaching.documentationPath != null) {
        issue("implementation.teaching.summary", "A documented teaching example requires a short summary")
    }
    teaching.concepts.forEachIndexed { index, concept ->
        if (concept.isBlank()) issue("implementation.teaching.concepts[$index]", "Teaching concepts cannot be blank")
    }
    duplicateIds(teaching.concepts).forEach {
        issue("implementation.teaching.concepts", "Teaching concept '$it' is duplicated")
    }
    duplicateIds(document.capabilityActionKeys).forEach {
        issue("capabilityActionKeys", "Capability action '$it' is duplicated")
    }
    document.capabilityActionKeys.forEachIndexed { index, key ->
        if (!key.matches(CAPABILITY_KEY)) {
            issue("capabilityActionKeys[$index]", "Capability action key '$key' is invalid")
        }
    }

    when (implementation.kind) {
        SubsystemImplementationKind.DECLARATIVE_GENERATED -> {
            if (implementation.ownership != SubsystemSourceOwnership.GENERATED_DO_NOT_EDIT) {
                issue("implementation.ownership", "Declarative generated runtimes must use GENERATED_DO_NOT_EDIT ownership")
            }
            if (implementation.modulePath != null || implementation.sourceFiles.isNotEmpty() ||
                implementation.subsystemClassName != null || implementation.ioContractClassName != null ||
                implementation.hardwareAdapterClassName != null
            ) {
                issue("implementation", "Declarative generated source locations come from the Gradle generated-source target")
            }
            if (!document.generateMockIo) {
                issue("generateMockIo", "Declarative generated subsystems require a simulator/mock adapter")
            }
            if (!document.generateTest) {
                issue("generateTest", "Declarative generated subsystems require baseline generated safety verification")
            }
            if (implementation.simulation.support != SubsystemSimulationSupport.GENERATED_MOCK ||
                implementation.simulation.adapterClassName != null
            ) {
                issue(
                    "implementation.simulation",
                    "Declarative generated subsystems require the generated simulator/mock contract",
                )
            }
            if (document.capabilityActionKeys.isNotEmpty()) {
                issue("capabilityActionKeys", "Declarative generated actions are derived from target state fields")
            }
        }

        SubsystemImplementationKind.GENERATED_STARTER -> {
            if (implementation.ownership != SubsystemSourceOwnership.GENERATED_STARTER) {
                issue("implementation.ownership", "Generated starters must use GENERATED_STARTER ownership")
            }
            if (implementation.modulePath != null || implementation.sourceFiles.isNotEmpty() ||
                implementation.subsystemClassName != null || implementation.ioContractClassName != null ||
                implementation.hardwareAdapterClassName != null
            ) {
                issue("implementation", "Generated starter source locations come from the code-generation target")
            }
            val expectedSimulation = if (document.generateMockIo) {
                SubsystemSimulationSupport.GENERATED_MOCK
            } else {
                SubsystemSimulationSupport.UNAVAILABLE
            }
            if (implementation.simulation.support != expectedSimulation ||
                implementation.simulation.adapterClassName != null
            ) {
                issue(
                    "implementation.simulation",
                    "Generated starter simulation metadata must match generateMockIo",
                )
            }
            if (document.capabilityActionKeys.isNotEmpty()) {
                issue("capabilityActionKeys", "Generated starter actions are derived from target state fields")
            }
        }

        SubsystemImplementationKind.HAND_AUTHORED -> {
            if (implementation.ownership != SubsystemSourceOwnership.USER_OWNED) {
                issue("implementation.ownership", "Hand-authored Kotlin must use USER_OWNED ownership")
            }
            if (implementation.modulePath == null) {
                issue("implementation.modulePath", "Hand-authored subsystems require an owning Gradle module")
            }
            if (implementation.sourceFiles.isEmpty()) {
                issue("implementation.sourceFiles", "Hand-authored subsystems require at least one user-owned source file")
            }
            listOf(
                "subsystemClassName" to implementation.subsystemClassName,
                "ioContractClassName" to implementation.ioContractClassName,
                "hardwareAdapterClassName" to implementation.hardwareAdapterClassName,
            ).forEach { (field, className) ->
                if (className == null) issue("implementation.$field", "Hand-authored subsystems must name this runtime type")
            }
            if (document.generateMockIo || document.generateTest) {
                issue(
                    "implementation",
                    "Hand-authored descriptors cannot request generated starter or test files",
                )
            }
            when (implementation.simulation.support) {
                SubsystemSimulationSupport.GENERATED_MOCK -> issue(
                    "implementation.simulation.support",
                    "Hand-authored subsystems cannot claim a generated mock",
                )
                SubsystemSimulationSupport.HAND_AUTHORED_MOCK,
                SubsystemSimulationSupport.HAND_AUTHORED_SIMULATOR -> if (implementation.simulation.adapterClassName == null) {
                    issue("implementation.simulation.adapterClassName", "Available simulation support requires its adapter class")
                }
                SubsystemSimulationSupport.UNAVAILABLE -> if (implementation.simulation.adapterClassName != null) {
                    issue("implementation.simulation.adapterClassName", "Unavailable simulation support cannot name an adapter")
                }
            }
        }
    }
}

private fun validateLinkage(document: SubsystemDocument, issue: (String, String) -> Unit) {
    if (!document.linkage.enabled) return
    val path = "linkage"
    val linkage = document.linkage
    val finiteValues = listOf(
        linkage.link1LengthMeters,
        linkage.link2LengthMeters,
        linkage.link1MassKg,
        linkage.link2MassKg,
        linkage.link1CenterOfMassMeters,
        linkage.link2CenterOfMassMeters,
        linkage.joint1MinRad,
        linkage.joint1MaxRad,
        linkage.joint2MinRad,
        linkage.joint2MaxRad,
        linkage.joint1TorquePerVoltNm,
        linkage.joint2TorquePerVoltNm,
        linkage.joint1DampingNmPerRadPerSec,
        linkage.joint2DampingNmPerRadPerSec,
    )
    if (finiteValues.any { !it.isFinite() }) issue(path, "Every linkage geometry, mass, and limit value must be finite")
    if (linkage.link1LengthMeters <= 0.0) issue("$path.link1LengthMeters", "Link 1 length must be positive")
    if (linkage.link2LengthMeters <= 0.0) issue("$path.link2LengthMeters", "Link 2 length must be positive")
    if (linkage.link1MassKg <= 0.0) issue("$path.link1MassKg", "Link 1 mass must be positive for dynamics simulation")
    if (linkage.link2MassKg <= 0.0) issue("$path.link2MassKg", "Link 2 mass must be positive for dynamics simulation")
    if (linkage.link1CenterOfMassMeters !in 0.0..linkage.link1LengthMeters) {
        issue("$path.link1CenterOfMassMeters", "Link 1 center of mass must lie on link 1")
    }
    if (linkage.link2CenterOfMassMeters !in 0.0..linkage.link2LengthMeters) {
        issue("$path.link2CenterOfMassMeters", "Link 2 center of mass must lie on link 2")
    }
    if (document.linkage.joint1MinRad >= document.linkage.joint1MaxRad) {
        issue("$path.joint1MinRad", "Joint 1 minimum angle must be less than maximum angle")
    }
    if (document.linkage.joint2MinRad >= document.linkage.joint2MaxRad) {
        issue("$path.joint2MinRad", "Joint 2 minimum angle must be less than maximum angle")
    }
    val fieldsById = document.stateFields.associateBy { it.fieldId }
    listOf(
        "joint1AngleFieldId" to linkage.joint1AngleFieldId,
        "joint2AngleFieldId" to linkage.joint2AngleFieldId,
    ).forEach { (name, id) ->
        val field = id?.let(fieldsById::get)
        if (field == null || field.type != SubsystemValueType.DOUBLE || field.role != SubsystemFieldRole.MEASUREMENT) {
            issue("$path.$name", "Each linkage joint requires a double measurement state field in radians")
        }
    }
    val hardwareById = document.hardware.associateBy { it.hardwareId }
    listOf(
        "joint1ActuatorId" to linkage.joint1ActuatorId,
        "joint2ActuatorId" to linkage.joint2ActuatorId,
    ).forEach { (name, id) ->
        val actuator = id?.let(hardwareById::get)
        if (actuator == null || actuator.kind != SubsystemHardwareKind.MOTOR || actuator.following != null) {
            issue("$path.$name", "Each linkage joint requires an independently controlled motor actuator")
        }
    }
    if (linkage.joint1ActuatorId != null && linkage.joint1ActuatorId == linkage.joint2ActuatorId) {
        issue(path, "Linkage joints must use distinct actuators")
    }
    if (linkage.joint1TorquePerVoltNm <= 0.0 || linkage.joint2TorquePerVoltNm <= 0.0) {
        issue(path, "Each linkage joint requires a positive torque-per-volt simulation constant")
    }
    if (linkage.joint1DampingNmPerRadPerSec < 0.0 || linkage.joint2DampingNmPerRadPerSec < 0.0) {
        issue(path, "Linkage damping values cannot be negative")
    }
}

private fun validateSimInteraction(document: SubsystemDocument, issue: (String, String) -> Unit) {
    val interaction = document.implementation.simulation.interaction
    if (interaction.role == SimInteractionRole.NONE) return
    val path = "implementation.simulation.interaction"
    val trigger = interaction.triggerActuatorId?.let { id -> document.hardware.singleOrNull { it.hardwareId == id } }
    if (trigger == null || trigger.kind !in ACTUATOR_KINDS || trigger.following != null) {
        issue("$path.triggerActuatorId", "Field interaction requires an independently controlled actuator output")
    }
    if (interaction.storageCapacity < 1) issue("$path.storageCapacity", "Storage capacity must be at least 1")
    if (interaction.intakeDistanceMeters <= 0.0) issue("$path.intakeDistanceMeters", "Intake distance must be positive")
    if (interaction.captureRadiusMeters <= 0.0) issue("$path.captureRadiusMeters", "Capture radius must be positive")
    if (interaction.launchSpeedMps <= 0.0) issue("$path.launchSpeedMps", "Launch speed must be positive")
    if (interaction.launchElevationDeg !in 0.0..90.0) issue("$path.launchElevationDeg", "Launch elevation must be between 0 and 90 degrees")
}

object SubsystemDocumentCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(document: SubsystemDocument): String {
        requireValid(document)
        return gson.toJson(document)
    }

    @Suppress("USELESS_ELVIS", "UNNECESSARY_SAFE_CALL", "UNNECESSARY_NOT_NULL_ASSERTION") // Gson/Unsafe can deliver null for non-null fields; see the note below
    fun decode(json: String): SubsystemDocument {
        val document = try {
            val root = parseJsonElement(json).asJsonObject
            val schemaVersion = root.get("schemaVersion")?.asInt
            require(schemaVersion == ARES_SUBSYSTEM_SCHEMA_VERSION) {
                "Unsupported subsystem schema $schemaVersion"
            }
            require(root.get("implementation")?.isJsonObject == true) {
                "Subsystem implementation metadata is required"
            }
            require(root.get("displayName")?.isJsonPrimitive == true &&
                root.get("kotlinTypeName")?.isJsonPrimitive == true
            ) {
                "Subsystem displayName and kotlinTypeName are required"
            }
            require(root.getAsJsonObject("safety")?.get("homing")?.isJsonObject == true) {
                "Subsystem homing metadata is required"
            }
            require(root.get("tuningParameters")?.isJsonArray == true) {
                "Subsystem tuningParameters are required (use an empty array when none are declared)"
            }
            val implementation = root.getAsJsonObject("implementation")
            require(implementation.has("kind") && implementation.has("ownership")) {
                "Subsystem implementation kind and ownership are required"
            }
            // The normalization below is load-bearing: Gson allocates via Unsafe
            // without calling constructors, leaving omitted or defaulted fields null at runtime.
            // We fully normalize and re-instantiate each model with non-null defaults.
            val parsed = gson.fromJson(json, SubsystemDocument::class.java)
                ?: throw IllegalArgumentException("Subsystem document is empty")
            normalizeSubsystemDocument(
                parsed,
                feedbackTimeoutWasDeclared = root.getAsJsonObject("safety")?.has("feedbackTimeoutMs") == true,
                generateMockIoWasDeclared = root.has("generateMockIo"),
                generateTestWasDeclared = root.has("generateTest"),
            )
        } catch (error: Exception) {
            throw IllegalArgumentException("Subsystem document is not valid JSON: ${error.message}", error)
        }
        requireValid(document)
        return document
    }

    @Suppress("USELESS_ELVIS", "UNNECESSARY_SAFE_CALL", "UNNECESSARY_NOT_NULL_ASSERTION")
    private fun normalizeSubsystemDocument(
        doc: SubsystemDocument,
        feedbackTimeoutWasDeclared: Boolean,
        generateMockIoWasDeclared: Boolean,
        generateTestWasDeclared: Boolean,
    ): SubsystemDocument {
        val hardware = (doc.hardware ?: emptyList()).map { h ->
            val conn = h.connection
            SubsystemHardwareDocument(
                hardwareId = h.hardwareId ?: "",
                displayName = h.displayName ?: h.hardwareId ?: "",
                kind = h.kind ?: SubsystemHardwareKind.MOTOR,
                connection = SubsystemHardwareConnection(
                    hardwareMapName = conn?.hardwareMapName,
                    canId = conn?.canId,
                    canBus = conn?.canBus ?: "rio",
                    channel = conn?.channel,
                    secondaryChannel = conn?.secondaryChannel,
                    pneumaticsModuleType = conn?.pneumaticsModuleType,
                ),
                required = h.required ?: true,
                inverted = h.inverted ?: false,
                measurements = (h.measurements ?: emptyList()).map { m ->
                    SubsystemMeasurementDocument(
                        fieldId = m.fieldId ?: "",
                        source = m.source ?: SubsystemMeasurementSource.MOTOR_POSITION_NATIVE,
                        scale = m.scale ?: 1.0,
                        offset = m.offset ?: 0.0,
                        maxAgeMs = m.maxAgeMs,
                        validMinimum = m.validMinimum,
                        validMaximum = m.validMaximum,
                    )
                },
                currentLimitAmps = h.currentLimitAmps,
                safeOutput = h.safeOutput,
                description = h.description ?: "",
                uid = h.uid ?: h.hardwareId ?: "",
                following = h.following?.let { f ->
                    SubsystemFollowerDocument(
                        leaderId = f.leaderId ?: "",
                        transform = f.transform ?: SubsystemFollowerTransform.SAME_DIRECTION,
                    )
                },
                encoderCountsPerRevolution = h.encoderCountsPerRevolution,
                distanceMetersPerVolt = h.distanceMetersPerVolt,
                imuLogoFacingDirection = h.imuLogoFacingDirection,
                imuUsbFacingDirection = h.imuUsbFacingDirection,
                visualPlacement = h.visualPlacement?.let { placement ->
                    SubsystemVisualPlacementDocument(
                        anchor = placement.anchor ?: SubsystemVisualAnchor.UNSPECIFIED,
                        forwardFraction = placement.forwardFraction ?: 0.0,
                        leftFraction = placement.leftFraction ?: 0.0,
                    )
                },
            )
        }

        val stateFields = (doc.stateFields ?: emptyList()).map { f ->
            SubsystemStateFieldDocument(
                fieldId = f.fieldId ?: "",
                displayName = f.displayName ?: f.fieldId ?: "",
                type = f.type ?: SubsystemValueType.DOUBLE,
                role = f.role ?: SubsystemFieldRole.TARGET,
                unit = f.unit,
                defaultNumber = f.defaultNumber,
                defaultBoolean = f.defaultBoolean,
                defaultInt = f.defaultInt,
                defaultText = f.defaultText,
                minimum = f.minimum,
                maximum = f.maximum,
                description = f.description ?: "",
                uid = f.uid ?: f.fieldId ?: "",
            )
        }

        val controlLoops = (doc.controlLoops ?: emptyList()).map { l ->
            val ff = l.feedforward
            SubsystemControlLoopDocument(
                loopId = l.loopId ?: "",
                displayName = l.displayName ?: l.loopId ?: "",
                strategy = l.strategy ?: SubsystemControlStrategy.DIRECT,
                actuatorId = l.actuatorId ?: "",
                targetFieldId = l.targetFieldId ?: "",
                measurementFieldId = l.measurementFieldId,
                kP = l.kP ?: 0.0,
                kI = l.kI ?: 0.0,
                kD = l.kD ?: 0.0,
                motionProfile = SubsystemMotionProfileDocument(
                    maximumVelocity = l.motionProfile?.maximumVelocity ?: 1.0,
                    maximumAcceleration = l.motionProfile?.maximumAcceleration ?: 2.0,
                ),
                feedforward = SubsystemFeedforwardDocument(
                    kind = ff?.kind ?: SubsystemFeedforwardKind.NONE,
                    kS = ff?.kS ?: 0.0,
                    kV = ff?.kV ?: 0.0,
                    kA = ff?.kA ?: 0.0,
                    kG = ff?.kG ?: 0.0,
                    velocityFieldId = ff?.velocityFieldId,
                    accelerationFieldId = ff?.accelerationFieldId,
                    gravityAngleFieldId = ff?.gravityAngleFieldId,
                    linkageJoint = ff?.linkageJoint,
                ),
                derivativeFilterTimeConstantSeconds = l.derivativeFilterTimeConstantSeconds ?: 0.02,
                continuousInput = SubsystemContinuousInputDocument(
                    enabled = l.continuousInput?.enabled ?: false,
                    minimumInput = l.continuousInput?.minimumInput ?: -Math.PI,
                    maximumInput = l.continuousInput?.maximumInput ?: Math.PI,
                ),
                tolerance = l.tolerance ?: 0.0,
                hysteresis = l.hysteresis ?: 0.0,
                minimumOutput = l.minimumOutput ?: -12.0,
                maximumOutput = l.maximumOutput ?: 12.0,
                description = l.description ?: "",
                uid = l.uid ?: l.loopId ?: "",
            )
        }

        val tuningParameters = (doc.tuningParameters ?: emptyList()).map { p ->
            TuningParameterDeclaration(
                uid = p.uid ?: "",
                key = p.key ?: "",
                componentUid = p.componentUid ?: "",
                displayName = p.displayName ?: p.key ?: "",
                description = p.description ?: "",
                type = p.type ?: com.areslib.tuning.TuningParameterType.DOUBLE,
                unit = p.unit,
                minimum = p.minimum,
                maximum = p.maximum,
                defaultValue = p.defaultValue ?: com.areslib.tuning.TuningValue(0.0),
                enumOptions = p.enumOptions ?: emptyList(),
                applyPolicy = p.applyPolicy ?: com.areslib.tuning.TuningApplyPolicy.LIVE_SAFE,
            )
        }

        val interlocks = (doc.interlocks ?: emptyList()).map { i ->
            SubsystemInterlockDocument(
                interlockId = i.interlockId ?: "",
                targetSubsystemUid = i.targetSubsystemUid ?: "",
                targetFieldId = i.targetFieldId ?: "",
                comparison = i.comparison ?: InterlockComparison.LESS_THAN,
                thresholdValue = i.thresholdValue ?: 0.0,
                targetStateName = i.targetStateName,
                forbiddenZoneDescription = i.forbiddenZoneDescription ?: "",
                safeFallbackValue = i.safeFallbackValue,
            )
        }

        val link = doc.linkage
        val linkage = SubsystemLinkageDocument(
            enabled = link?.enabled ?: false,
            link1LengthMeters = link?.link1LengthMeters ?: 0.35,
            link2LengthMeters = link?.link2LengthMeters ?: 0.25,
            link1MassKg = link?.link1MassKg ?: 0.5,
            link2MassKg = link?.link2MassKg ?: 0.3,
            link1CenterOfMassMeters = link?.link1CenterOfMassMeters ?: ((link?.link1LengthMeters ?: 0.35) / 2.0),
            link2CenterOfMassMeters = link?.link2CenterOfMassMeters ?: ((link?.link2LengthMeters ?: 0.25) / 2.0),
            joint1MinRad = link?.joint1MinRad ?: -Math.PI,
            joint1MaxRad = link?.joint1MaxRad ?: Math.PI,
            joint2MinRad = link?.joint2MinRad ?: -Math.PI,
            joint2MaxRad = link?.joint2MaxRad ?: Math.PI,
            joint1ActuatorId = link?.joint1ActuatorId,
            joint2ActuatorId = link?.joint2ActuatorId,
            joint1AngleFieldId = link?.joint1AngleFieldId,
            joint2AngleFieldId = link?.joint2AngleFieldId,
            joint1TorquePerVoltNm = link?.joint1TorquePerVoltNm ?: 0.5,
            joint2TorquePerVoltNm = link?.joint2TorquePerVoltNm ?: 0.35,
            joint1DampingNmPerRadPerSec = link?.joint1DampingNmPerRadPerSec ?: 0.08,
            joint2DampingNmPerRadPerSec = link?.joint2DampingNmPerRadPerSec ?: 0.05,
        )

        val impl = doc.implementation
        val sim = impl?.simulation
        val inter = sim?.interaction
        val teach = impl?.teaching
        val genMock = when {
            generateMockIoWasDeclared -> doc.generateMockIo
            impl?.kind == SubsystemImplementationKind.HAND_AUTHORED -> false
            sim?.support == SubsystemSimulationSupport.UNAVAILABLE -> false
            else -> true
        }
        val genTest = when {
            generateTestWasDeclared -> doc.generateTest
            impl?.kind == SubsystemImplementationKind.HAND_AUTHORED -> false
            else -> true
        }
        val simSupport = when (impl?.kind) {
            SubsystemImplementationKind.DECLARATIVE_GENERATED,
            SubsystemImplementationKind.GENERATED_STARTER -> if (genMock) SubsystemSimulationSupport.GENERATED_MOCK else SubsystemSimulationSupport.UNAVAILABLE
            SubsystemImplementationKind.HAND_AUTHORED -> sim?.support ?: SubsystemSimulationSupport.UNAVAILABLE
            null -> if (genMock) SubsystemSimulationSupport.GENERATED_MOCK else SubsystemSimulationSupport.UNAVAILABLE
        }

        val implementation = SubsystemImplementationDocument(
            kind = impl?.kind ?: SubsystemImplementationKind.GENERATED_STARTER,
            ownership = impl?.ownership ?: SubsystemSourceOwnership.GENERATED_STARTER,
            modulePath = impl?.modulePath,
            sourceFiles = impl?.sourceFiles ?: emptyList(),
            subsystemClassName = impl?.subsystemClassName,
            ioContractClassName = impl?.ioContractClassName,
            hardwareAdapterClassName = impl?.hardwareAdapterClassName,
            simulation = SubsystemSimulationDocument(
                support = simSupport,
                adapterClassName = if (impl?.kind?.isAresGenerated() != false) null else sim?.adapterClassName,
                interaction = SubsystemSimInteractionDocument(
                    role = inter?.role ?: SimInteractionRole.NONE,
                    triggerActuatorId = inter?.triggerActuatorId,
                    triggerThreshold = inter?.triggerThreshold ?: 1.0,
                    storageCapacity = inter?.storageCapacity ?: 1,
                    intakeDistanceMeters = inter?.intakeDistanceMeters ?: 0.35,
                    captureRadiusMeters = inter?.captureRadiusMeters ?: 0.15,
                    launchSpeedMps = inter?.launchSpeedMps ?: 8.0,
                    launchElevationDeg = inter?.launchElevationDeg ?: 45.0,
                    beamBreakFieldId = inter?.beamBreakFieldId,
                ),
            ),
            teaching = SubsystemTeachingDocument(
                level = teach?.level ?: SubsystemTeachingLevel.INTERMEDIATE,
                summary = teach?.summary ?: "",
                documentationPath = teach?.documentationPath,
                concepts = teach?.concepts ?: emptyList(),
            ),
        )

        val s = doc.safety
        val homing = s?.homing
        val fault = s?.faultRecovery
        val safety = SubsystemSafetyDocument(
            // Gson supplies the Kotlin field initializer when this nullable property is omitted.
            // Preserve an intentionally absent lease without changing an explicitly declared one.
            feedbackTimeoutMs = if (feedbackTimeoutWasDeclared) s?.feedbackTimeoutMs else null,
            homing = SubsystemHomingDocument(
                method = homing?.method ?: SubsystemHomingMethod.NONE,
                actuatorId = homing?.actuatorId,
                searchOutput = homing?.searchOutput,
                evidence = (homing?.evidence ?: emptyList()).map { e ->
                    SubsystemHomingEvidenceDocument(
                        fieldId = e.fieldId ?: "",
                        comparison = e.comparison ?: SubsystemHomingComparison.TRUE,
                        threshold = e.threshold,
                    )
                },
                dwellMs = homing?.dwellMs ?: 250L,
                timeoutMs = homing?.timeoutMs ?: 3_000L,
                zeroPosition = homing?.zeroPosition ?: 0.0,
            ),
            faultRecovery = SubsystemFaultRecoveryDocument(
                enabled = fault?.enabled ?: false,
                actuatorId = fault?.actuatorId,
                currentFieldId = fault?.currentFieldId,
                currentThresholdAmps = fault?.currentThresholdAmps ?: 18.0,
                currentDurationMs = fault?.currentDurationMs ?: 250L,
                recoveryAction = fault?.recoveryAction ?: FaultRecoveryActionKind.REVERSE_BRIEFLY,
                reverseDurationMs = fault?.reverseDurationMs ?: 400L,
                reverseDutyCycle = fault?.reverseDutyCycle ?: -0.40,
                maxRetries = fault?.maxRetries ?: 3,
            ),
            requiresCalibration = s?.requiresCalibration ?: false,
            requiresConfigurationHealth = s?.requiresConfigurationHealth ?: true,
            requiresCurrentMonitoring = s?.requiresCurrentMonitoring ?: false,
            latchOutputFaults = s?.latchOutputFaults ?: true,
            requiresExplicitNeutralRecovery = s?.requiresExplicitNeutralRecovery ?: true,
            telemetryEnabled = s?.telemetryEnabled ?: true,
            zeroAllocationPeriodic = s?.zeroAllocationPeriodic ?: true,
        )

        return SubsystemDocument(
            schemaVersion = doc.schemaVersion ?: ARES_SUBSYSTEM_SCHEMA_VERSION,
            documentId = doc.documentId ?: "",
            displayName = doc.displayName ?: doc.documentId ?: "",
            kotlinTypeName = doc.kotlinTypeName ?: "Subsystem",
            description = doc.description ?: "",
            platform = doc.platform ?: SubsystemPlatform.FTC,
            revision = doc.revision ?: 1,
            parentContentHash = doc.parentContentHash,
            hardware = hardware,
            stateFields = stateFields,
            controlLoops = controlLoops,
            tuningParameters = tuningParameters,
            template = doc.template ?: SubsystemTemplate.ADVANCED_CUSTOM,
            implementation = implementation,
            capabilityActionKeys = doc.capabilityActionKeys ?: emptyList(),
            safety = safety,
            interlocks = interlocks,
            linkage = linkage,
            autonomousResourceKey = doc.autonomousResourceKey,
            requiredAtStartup = doc.requiredAtStartup ?: true,
            generateMockIo = genMock,
            generateTest = genTest,
            uid = doc.uid ?: doc.documentId ?: "",
        )
    }

    fun contentHash(document: SubsystemDocument): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(document).toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun requireValid(document: SubsystemDocument) {
        val issues = validateSubsystemDocument(document)
        require(issues.isEmpty()) { issues.joinToString("; ") { "${it.path}: ${it.message}" } }
    }
}

private val STABLE_ID = Regex("[a-z][a-z0-9-]{0,63}")
private val PASCAL_CASE = Regex("[A-Z][A-Za-z0-9]{0,63}")
private val KOTLIN_IDENTIFIER = Regex("[a-z][A-Za-z0-9_]{0,63}")
private val KOTLIN_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
    "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch", "constructor",
    "delegate", "dynamic", "field", "file", "finally", "get", "import", "init", "param", "property",
    "receiver", "set", "setparam", "where", "actual", "abstract", "annotation", "companion", "const",
    "crossinline", "data", "enum", "expect", "external", "final", "infix", "inline", "inner", "internal",
    "lateinit", "noinline", "open", "operator", "out", "override", "private", "protected", "public",
    "reified", "sealed", "suspend", "tailrec", "vararg",
)
private val SHA_256 = Regex("[a-f0-9]{64}")
private val GRADLE_MODULE_PATH = Regex(":[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)*")
private val QUALIFIED_KOTLIN_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
private val CAPABILITY_KEY = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
private val ACTUATOR_KINDS = setOf(
    SubsystemHardwareKind.MOTOR,
    SubsystemHardwareKind.POSITIONAL_SERVO,
    SubsystemHardwareKind.CONTINUOUS_SERVO,
    SubsystemHardwareKind.INDICATOR_LIGHT,
    SubsystemHardwareKind.PRISM_DRIVER,
    SubsystemHardwareKind.SOLENOID,
)
private val NUMERIC_TYPES = setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)
private val CLOSED_LOOP_STRATEGIES = setOf(
    SubsystemControlStrategy.POSITION_PID,
    SubsystemControlStrategy.PROFILED_POSITION_PID,
    SubsystemControlStrategy.VELOCITY_PID,
    SubsystemControlStrategy.BANG_BANG,
)
private val CONTINUOUS_POSITION_STRATEGIES = setOf(
    SubsystemControlStrategy.POSITION_PID,
    SubsystemControlStrategy.PROFILED_POSITION_PID,
)

private fun SubsystemHubFacingDirection.isPerpendicularTo(other: SubsystemHubFacingDirection): Boolean =
    axisGroup() != other.axisGroup()

private fun SubsystemHubFacingDirection.axisGroup(): Int = when (this) {
    SubsystemHubFacingDirection.UP, SubsystemHubFacingDirection.DOWN -> 0
    SubsystemHubFacingDirection.FORWARD, SubsystemHubFacingDirection.BACKWARD -> 1
    SubsystemHubFacingDirection.LEFT, SubsystemHubFacingDirection.RIGHT -> 2
}

private fun String.isSafeProjectRelativePath(): Boolean =
    isNotBlank() && '/' in this && !startsWith('/') && '\\' !in this &&
        split('/').none { it.isBlank() || it == "." || it == ".." }

private fun String.isSafeProjectRelativeKotlinPath(): Boolean =
    isSafeProjectRelativePath() && endsWith(".kt")

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
    SubsystemHardwareKind.ANALOG_INPUT -> listOf(SubsystemMeasurementSource.ANALOG_VOLTAGE)
    SubsystemHardwareKind.DISTANCE_SENSOR -> listOf(SubsystemMeasurementSource.DISTANCE_METERS)
    SubsystemHardwareKind.IMU -> listOf(
        SubsystemMeasurementSource.IMU_YAW_RADIANS,
        SubsystemMeasurementSource.IMU_YAW_RATE_RADIANS_PER_SECOND,
    )
    SubsystemHardwareKind.COLOR_SENSOR -> listOf(SubsystemMeasurementSource.COLOR_ARGB)
    SubsystemHardwareKind.POSITIONAL_SERVO,
    SubsystemHardwareKind.CONTINUOUS_SERVO,
    SubsystemHardwareKind.INDICATOR_LIGHT,
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
    SubsystemMeasurementSource.DISTANCE_METERS,
    SubsystemMeasurementSource.IMU_YAW_RADIANS,
    SubsystemMeasurementSource.IMU_YAW_RATE_RADIANS_PER_SECOND -> SubsystemValueType.DOUBLE
    SubsystemMeasurementSource.DIGITAL_STATE -> SubsystemValueType.BOOLEAN
    SubsystemMeasurementSource.COLOR_ARGB -> SubsystemValueType.INT
}

/** Canonical state-field unit required after measurement scale/offset conversion. */
fun SubsystemMeasurementSource.canonicalUnit(): String? = when (this) {
    SubsystemMeasurementSource.ENCODER_POSITION_TURNS,
    SubsystemMeasurementSource.IMU_YAW_RADIANS -> "rad"
    SubsystemMeasurementSource.ENCODER_VELOCITY_TURNS_PER_SECOND,
    SubsystemMeasurementSource.IMU_YAW_RATE_RADIANS_PER_SECOND -> "rad/s"
    SubsystemMeasurementSource.DISTANCE_METERS -> "m"
    SubsystemMeasurementSource.MOTOR_CURRENT_AMPS -> "A"
    SubsystemMeasurementSource.ANALOG_VOLTAGE -> "V"
    else -> null
}

private fun duplicateIds(ids: List<String>): Set<String> {
    val seen = hashSetOf<String>()
    return ids.filterNot(seen::add).toSet()
}

private fun String.isUsableKotlinIdentifier(): Boolean = matches(KOTLIN_IDENTIFIER) && this !in KOTLIN_KEYWORDS
