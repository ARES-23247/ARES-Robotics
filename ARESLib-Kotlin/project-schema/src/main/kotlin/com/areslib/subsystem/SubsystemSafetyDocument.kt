package com.areslib.subsystem

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
    /** Custom implementation metadata only; generated interlocks use each actuator's safe output. */
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
