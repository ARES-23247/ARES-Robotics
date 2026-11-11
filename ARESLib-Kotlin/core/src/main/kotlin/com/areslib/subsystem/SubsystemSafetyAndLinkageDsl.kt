package com.areslib.subsystem

/** Declarative fail-closed requirements used by codegen, preview warnings, and verification. */
@AresSubsystemDsl
class SubsystemSafetyBuilder internal constructor() {
    var feedbackTimeoutMs: Long? = 250L
    val homing = SubsystemHomingBuilder()
    val faultRecovery = SubsystemFaultRecoveryBuilder()
    var requiresCalibration: Boolean = false
    var requiresConfigurationHealth: Boolean = true
    var requiresCurrentMonitoring: Boolean = false
    var latchOutputFaults: Boolean = true
    var requiresExplicitNeutralRecovery: Boolean = true
    var telemetryEnabled: Boolean = true
    var zeroAllocationPeriodic: Boolean = true

    /** Configures homing behavior for mechanisms that establish a physical zero reference. */
    fun homing(block: SubsystemHomingBuilder.() -> Unit) {
        homing.apply(block)
    }

    /** Configures bounded automatic jam/fault recovery using cached motor current. */
    fun faultRecovery(block: SubsystemFaultRecoveryBuilder.() -> Unit) {
        faultRecovery.enabled = true
        faultRecovery.apply(block)
    }

    internal fun build() = SubsystemSafetyDocument(
        feedbackTimeoutMs = feedbackTimeoutMs,
        homing = homing.build(),
        faultRecovery = faultRecovery.build(),
        requiresCalibration = requiresCalibration,
        requiresConfigurationHealth = requiresConfigurationHealth,
        requiresCurrentMonitoring = requiresCurrentMonitoring,
        latchOutputFaults = latchOutputFaults,
        requiresExplicitNeutralRecovery = requiresExplicitNeutralRecovery,
        telemetryEnabled = telemetryEnabled,
        zeroAllocationPeriodic = zeroAllocationPeriodic,
    )
}

/** Configures automatic jam detection and bounded recovery actions. */
@AresSubsystemDsl
class SubsystemFaultRecoveryBuilder internal constructor() {
    var enabled: Boolean = false
    var actuator: SubsystemHardwareRef? = null
    var currentField: SubsystemFieldRef? = null
    var currentThresholdAmps: Double = 18.0
    var currentDurationMs: Long = 250L
    var recoveryAction: FaultRecoveryActionKind = FaultRecoveryActionKind.REVERSE_BRIEFLY
    var reverseDurationMs: Long = 400L
    var reverseDutyCycle: Double = -0.40
    var maxRetries: Int = 3

    fun none() {
        enabled = false
        actuator = null
        currentField = null
    }

    /** Reverses [actuator] briefly when [current] stays at or above [currentThresholdAmps] for [currentDurationMs]. */
    fun reverseBriefly(
        actuator: SubsystemHardwareRef,
        current: SubsystemFieldRef,
        currentThresholdAmps: Double = 18.0,
        currentDurationMs: Long = 250L,
        reverseDurationMs: Long = 400L,
        reverseDutyCycle: Double = -0.40,
        maxRetries: Int = 3,
    ) {
        this.enabled = true
        this.actuator = actuator
        this.currentField = current
        this.currentThresholdAmps = currentThresholdAmps
        this.currentDurationMs = currentDurationMs
        this.recoveryAction = FaultRecoveryActionKind.REVERSE_BRIEFLY
        this.reverseDurationMs = reverseDurationMs
        this.reverseDutyCycle = reverseDutyCycle
        this.maxRetries = maxRetries
    }

    /** Latches a neutral stop when [current] stays at or above [currentThresholdAmps] for [currentDurationMs]. */
    fun neutralStop(
        actuator: SubsystemHardwareRef,
        current: SubsystemFieldRef,
        currentThresholdAmps: Double = 18.0,
        currentDurationMs: Long = 250L,
        maxRetries: Int = 1,
    ) {
        this.enabled = true
        this.actuator = actuator
        this.currentField = current
        this.currentThresholdAmps = currentThresholdAmps
        this.currentDurationMs = currentDurationMs
        this.recoveryAction = FaultRecoveryActionKind.NEUTRAL_STOP
        this.maxRetries = maxRetries
    }

    /** Holds the current actuator position when [current] stays at or above [currentThresholdAmps] for [currentDurationMs]. */
    fun holdPosition(
        actuator: SubsystemHardwareRef,
        current: SubsystemFieldRef,
        currentThresholdAmps: Double = 18.0,
        currentDurationMs: Long = 250L,
        maxRetries: Int = 1,
    ) {
        this.enabled = true
        this.actuator = actuator
        this.currentField = current
        this.currentThresholdAmps = currentThresholdAmps
        this.currentDurationMs = currentDurationMs
        this.recoveryAction = FaultRecoveryActionKind.HOLD_POSITION
        this.maxRetries = maxRetries
    }

    internal fun build(): SubsystemFaultRecoveryDocument = if (!enabled) {
        SubsystemFaultRecoveryDocument()
    } else {
        SubsystemFaultRecoveryDocument(
            enabled = true,
            actuatorId = actuator?.id,
            currentFieldId = currentField?.id,
            currentThresholdAmps = currentThresholdAmps,
            currentDurationMs = currentDurationMs,
            recoveryAction = recoveryAction,
            reverseDurationMs = reverseDurationMs,
            reverseDutyCycle = reverseDutyCycle,
            maxRetries = maxRetries,
        )
    }
}

/** Builder for cross-subsystem safety interlocks ([SubsystemInterlockDocument]). */
@AresSubsystemDsl
class SubsystemInterlockBuilder internal constructor(
    var interlockId: String,
    var targetSubsystemUid: String,
    var targetFieldId: String,
    var comparison: InterlockComparison = InterlockComparison.LESS_THAN,
    var thresholdValue: Double = 0.0,
) {
    var targetStateName: String? = null
    var forbiddenZoneDescription: String = ""
    var safeFallbackValue: Double? = null

    internal fun build() = SubsystemInterlockDocument(
        interlockId = interlockId,
        targetSubsystemUid = targetSubsystemUid,
        targetFieldId = targetFieldId,
        comparison = comparison,
        thresholdValue = thresholdValue,
        targetStateName = targetStateName,
        forbiddenZoneDescription = forbiddenZoneDescription,
        safeFallbackValue = safeFallbackValue,
    )
}

/** Builder for 2-DOF serial linkage geometry and simulation dynamics ([SubsystemLinkageDocument]). */
@AresSubsystemDsl
class SubsystemLinkageBuilder internal constructor() {
    var enabled: Boolean = false
    var link1LengthMeters: Double = 0.35
    var link2LengthMeters: Double = 0.25
    var link1MassKg: Double = 0.5
    var link2MassKg: Double = 0.3
    var link1CenterOfMassMeters: Double? = null
    var link2CenterOfMassMeters: Double? = null
    var joint1MinRad: Double = -Math.PI
    var joint1MaxRad: Double = Math.PI
    var joint2MinRad: Double = -Math.PI
    var joint2MaxRad: Double = Math.PI
    var joint1Actuator: SubsystemHardwareRef? = null
    var joint2Actuator: SubsystemHardwareRef? = null
    var joint1AngleField: SubsystemFieldRef? = null
    var joint2AngleField: SubsystemFieldRef? = null
    var joint1TorquePerVoltNm: Double = 0.5
    var joint2TorquePerVoltNm: Double = 0.35
    var joint1DampingNmPerRadPerSec: Double = 0.08
    var joint2DampingNmPerRadPerSec: Double = 0.05

    internal fun build(): SubsystemLinkageDocument = if (!enabled) {
        SubsystemLinkageDocument()
    } else {
        SubsystemLinkageDocument(
            enabled = true,
            link1LengthMeters = link1LengthMeters,
            link2LengthMeters = link2LengthMeters,
            link1MassKg = link1MassKg,
            link2MassKg = link2MassKg,
            link1CenterOfMassMeters = link1CenterOfMassMeters ?: (link1LengthMeters / 2.0),
            link2CenterOfMassMeters = link2CenterOfMassMeters ?: (link2LengthMeters / 2.0),
            joint1MinRad = joint1MinRad,
            joint1MaxRad = joint1MaxRad,
            joint2MinRad = joint2MinRad,
            joint2MaxRad = joint2MaxRad,
            joint1ActuatorId = joint1Actuator?.id,
            joint2ActuatorId = joint2Actuator?.id,
            joint1AngleFieldId = joint1AngleField?.id,
            joint2AngleFieldId = joint2AngleField?.id,
            joint1TorquePerVoltNm = joint1TorquePerVoltNm,
            joint2TorquePerVoltNm = joint2TorquePerVoltNm,
            joint1DampingNmPerRadPerSec = joint1DampingNmPerRadPerSec,
            joint2DampingNmPerRadPerSec = joint2DampingNmPerRadPerSec,
        )
    }
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
