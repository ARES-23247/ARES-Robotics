package com.areslib.subsystem

/** Feedforward, homing, recovery, and cross-subsystem safety validation. */
internal object SubsystemSafetyValidation {
    fun validateFeedforward(
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
            if (id != null && fieldsById[id]?.type !in SUBSYSTEM_NUMERIC_TYPES) {
                issue("$path.feedforward.$name", "Feedforward fields must reference numeric state values")
            }
        }
        feedforward.velocityFieldId?.let { id ->
            val field = fieldsById[id]
            if (field != null && !SubsystemUnits.canRepresentVelocity(field.unit)) {
                issue("$path.feedforward.velocityFieldId", "Desired velocity must use m/s, rad/s, rot/s, or an explicitly unitless advanced field")
            }
        }
        feedforward.accelerationFieldId?.let { id ->
            val field = fieldsById[id]
            if (field != null && !SubsystemUnits.canRepresentAcceleration(field.unit)) {
                issue("$path.feedforward.accelerationFieldId", "Desired acceleration must use m/s², rad/s², rot/s², or an explicitly unitless advanced field")
            }
        }
        if (feedforward.kind == SubsystemFeedforwardKind.ARM && feedforward.gravityAngleFieldId == null) {
            issue("$path.feedforward.gravityAngleFieldId", "Arm feedforward requires an angle measurement in radians")
        }
        if (feedforward.kind == SubsystemFeedforwardKind.ARM) {
            feedforward.gravityAngleFieldId?.let { id ->
                val field = fieldsById[id]
                if (field != null && !SubsystemUnits.isCanonicalAngle(field.unit)) {
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
    
    fun validateHoming(
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
        duplicateSubsystemIds(homing.evidence.map { it.fieldId }).forEach {
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
            if (!booleanComparison && field.type !in SUBSYSTEM_NUMERIC_TYPES) {
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
    
    fun validateFaultRecovery(
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
    
    fun validateInterlocks(
        document: SubsystemDocument,
        issue: (path: String, message: String) -> Unit,
    ) {
        val duplicateInterlocks = duplicateSubsystemIds(document.interlocks.map { it.interlockId })
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
    
    private val SUBSYSTEM_NUMERIC_TYPES = setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)

    private fun duplicateSubsystemIds(ids: List<String>): Set<String> {
        val seen = hashSetOf<String>()
        return ids.filterNot(seen::add).toSet()
    }
}

