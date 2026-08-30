package com.areslib.subsystem

import com.areslib.tuning.validateTuningParameterDeclarations

/** Public validation façade for canonical subsystem documents and project-wide relationships. */
object SubsystemSchema {
    fun validate(document: SubsystemDocument): List<SubsystemValidationIssue> = buildList {
        fun issue(path: String, message: String) {
            add(SubsystemValidationIssue(path, message))
        }
    
        if (document.schemaVersion != ARES_SUBSYSTEM_SCHEMA_VERSION) {
            issue("schemaVersion", "Unsupported subsystem schema ${document.schemaVersion}")
        }
        if (!document.documentId.matches(SUBSYSTEM_STABLE_ID)) {
            issue("documentId", "Document ID must be a stable lowercase key")
        } else if (!document.documentId.replace('-', '_').isUsableSubsystemKotlinIdentifier()) {
            issue("documentId", "Document ID would create a Kotlin keyword package")
        }
        if (document.displayName.isBlank()) issue("displayName", "Subsystem display name is required")
        if (!document.kotlinTypeName.matches(SUBSYSTEM_PASCAL_CASE)) {
            issue("kotlinTypeName", "Kotlin type name must use PascalCase")
        }
        if (document.uid.isBlank()) issue("uid", "Subsystem UID is required")
        if (document.revision < 1) issue("revision", "Revision must be positive")
        if (document.parentContentHash != null && !document.parentContentHash.matches(SUBSYSTEM_SHA_256)) {
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
    
        duplicateSubsystemIds(document.hardware.map { it.hardwareId }).forEach {
            issue("hardware", "Hardware ID '$it' is duplicated")
        }
        duplicateSubsystemIds(document.stateFields.map { it.fieldId }).forEach {
            issue("stateFields", "State field ID '$it' is duplicated")
        }
        duplicateSubsystemIds(document.controlLoops.map { it.loopId }).forEach {
            issue("controlLoops", "Control loop ID '$it' is duplicated")
        }
        duplicateSubsystemIds(document.hardware.map { it.uid }).forEach { issue("hardware", "Hardware UID '$it' is duplicated") }
        duplicateSubsystemIds(document.stateFields.map { it.uid }).forEach { issue("stateFields", "State UID '$it' is duplicated") }
        duplicateSubsystemIds(document.controlLoops.map { it.uid }).forEach { issue("controlLoops", "Control UID '$it' is duplicated") }
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
            if (!device.hardwareId.isUsableSubsystemKotlinIdentifier()) issue("$path.hardwareId", "Hardware ID must be a Kotlin identifier, not a keyword")
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
            if (device.kind in SUBSYSTEM_ACTUATOR_KINDS) {
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
            if (device.inverted && device.kind !in SUBSYSTEM_ACTUATOR_KINDS) {
                issue("$path.inverted", "Only motors and servos have a reversible hardware direction")
            }
            device.following?.let { follower ->
                val relationPath = "$path.following"
                val leader = hardwareById[follower.leaderId]
                when {
                    device.kind !in SUBSYSTEM_ACTUATOR_KINDS -> issue(relationPath, "Only actuators can follow another actuator")
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
            duplicateSubsystemIds(device.measurements.map { it.fieldId }).forEach {
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
            if (!field.fieldId.isUsableSubsystemKotlinIdentifier()) issue("$path.fieldId", "State field ID must be a Kotlin identifier, not a keyword")
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
            if (!loop.loopId.isUsableSubsystemKotlinIdentifier()) issue("$path.loopId", "Control loop ID must be a Kotlin identifier, not a keyword")
            if (loop.uid.isBlank()) issue("$path.uid", "Control loop UID is required")
            if (loop.displayName.isBlank()) issue("$path.displayName", "Control loop display name is required")
            val actuator = hardwareById[loop.actuatorId]
            if (actuator == null) {
                issue("$path.actuatorId", "Unknown actuator '${loop.actuatorId}'")
            } else if (actuator.kind !in SUBSYSTEM_ACTUATOR_KINDS) {
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
                if (target.type !in SUBSYSTEM_NUMERIC_TYPES) issue("$path.targetFieldId", "Control targets must be numeric")
            }
            val needsMeasurement = loop.strategy in SUBSYSTEM_CLOSED_LOOP_STRATEGIES
            val measurement = loop.measurementFieldId?.let(fieldsById::get)
            if (needsMeasurement && measurement == null) issue("$path.measurementFieldId", "This strategy requires a measurement field")
            if (measurement != null && measurement.type !in SUBSYSTEM_NUMERIC_TYPES) issue("$path.measurementFieldId", "Control measurements must be numeric")
            if (needsMeasurement && target != null && measurement != null &&
                !SubsystemUnits.controlUnitsCompatible(target.unit, measurement.unit)
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
                if (loop.strategy !in SUBSYSTEM_CONTINUOUS_POSITION_STRATEGIES) {
                    issue("$path.continuousInput.enabled", "Continuous input is available only for position PID control")
                }
                if (target != null && !SubsystemUnits.isCanonicalAngle(target.unit)) {
                    issue("$path.targetFieldId", "Continuous position targets must use canonical radians (rad)")
                }
                if (measurement != null && !SubsystemUnits.isCanonicalAngle(measurement.unit)) {
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
    
        document.hardware.filter { it.kind in SUBSYSTEM_ACTUATOR_KINDS && it.following == null }.forEach { actuator ->
            if (document.controlLoops.none { it.actuatorId == actuator.hardwareId }) {
                issue("hardware.${actuator.hardwareId}", "Actuator '${actuator.displayName}' is not controlled by any loop")
            }
        }
        val hasActuators = document.hardware.any { it.kind in SUBSYSTEM_ACTUATOR_KINDS }
        document.safety.feedbackTimeoutMs?.let {
            if (it !in 20L..10_000L) issue("safety.feedbackTimeoutMs", "Feedback timeout must be from 20 to 10000 ms")
        }
        if (hasActuators && document.controlLoops.any { it.strategy in SUBSYSTEM_CLOSED_LOOP_STRATEGIES } &&
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
            if (!it.matches(SUBSYSTEM_STABLE_ID)) issue("autonomousResourceKey", "Autonomous resource key must be a stable lowercase key")
        }
    }
    
    /**
     * Validates relationships that cannot be proven from one subsystem document in isolation.
     * Missing or ambiguous interlock targets are build errors, never runtime permits.
     */
    fun validateAll(documents: List<SubsystemDocument>): List<SubsystemValidationIssue> = buildList {
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
    
    private fun validateImplementation(
        document: SubsystemDocument,
        issue: (path: String, message: String) -> Unit,
    ) {
        val implementation = document.implementation
        val duplicateSourceFiles = duplicateSubsystemIds(implementation.sourceFiles)
        duplicateSourceFiles.forEach { issue("implementation.sourceFiles", "Source file '$it' is duplicated") }
        implementation.sourceFiles.forEachIndexed { index, path ->
            if (!path.isSafeSubsystemProjectRelativeKotlinPath()) {
                issue(
                    "implementation.sourceFiles[$index]",
                    "Source files must be normalized project-relative Kotlin paths",
                )
            }
        }
        implementation.modulePath?.let { modulePath ->
            if (!modulePath.matches(SUBSYSTEM_GRADLE_MODULE_PATH)) {
                issue("implementation.modulePath", "Module path must be a Gradle project path such as ':TeamCode'")
            }
        }
        listOf(
            "subsystemClassName" to implementation.subsystemClassName,
            "ioContractClassName" to implementation.ioContractClassName,
            "hardwareAdapterClassName" to implementation.hardwareAdapterClassName,
            "simulation.adapterClassName" to implementation.simulation.adapterClassName,
        ).forEach { (field, className) ->
            if (className != null && !className.matches(SUBSYSTEM_QUALIFIED_KOTLIN_NAME)) {
                issue("implementation.$field", "Class name must be a fully qualified Kotlin name")
            }
        }
        val teaching = implementation.teaching
        if (teaching.documentationPath != null && !teaching.documentationPath.isSafeSubsystemProjectRelativePath()) {
            issue("implementation.teaching.documentationPath", "Documentation must use a normalized project-relative path")
        }
        if (teaching.summary.isBlank() && teaching.documentationPath != null) {
            issue("implementation.teaching.summary", "A documented teaching example requires a short summary")
        }
        teaching.concepts.forEachIndexed { index, concept ->
            if (concept.isBlank()) issue("implementation.teaching.concepts[$index]", "Teaching concepts cannot be blank")
        }
        duplicateSubsystemIds(teaching.concepts).forEach {
            issue("implementation.teaching.concepts", "Teaching concept '$it' is duplicated")
        }
        duplicateSubsystemIds(document.capabilityActionKeys).forEach {
            issue("capabilityActionKeys", "Capability action '$it' is duplicated")
        }
        document.capabilityActionKeys.forEachIndexed { index, key ->
            if (!key.matches(SUBSYSTEM_CAPABILITY_KEY)) {
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
        if (trigger == null || trigger.kind !in SUBSYSTEM_ACTUATOR_KINDS || trigger.following != null) {
            issue("$path.triggerActuatorId", "Field interaction requires an independently controlled actuator output")
        }
        if (interaction.storageCapacity < 1) issue("$path.storageCapacity", "Storage capacity must be at least 1")
        if (interaction.intakeDistanceMeters <= 0.0) issue("$path.intakeDistanceMeters", "Intake distance must be positive")
        if (interaction.captureRadiusMeters <= 0.0) issue("$path.captureRadiusMeters", "Capture radius must be positive")
        if (interaction.launchSpeedMps <= 0.0) issue("$path.launchSpeedMps", "Launch speed must be positive")
        if (interaction.launchElevationDeg !in 0.0..90.0) issue("$path.launchElevationDeg", "Launch elevation must be between 0 and 90 degrees")
    }

    private val SUBSYSTEM_ACTUATOR_KINDS = setOf(
        SubsystemHardwareKind.MOTOR,
        SubsystemHardwareKind.POSITIONAL_SERVO,
        SubsystemHardwareKind.CONTINUOUS_SERVO,
        SubsystemHardwareKind.INDICATOR_LIGHT,
        SubsystemHardwareKind.PRISM_DRIVER,
        SubsystemHardwareKind.SOLENOID,
    )
    private val SUBSYSTEM_NUMERIC_TYPES = setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)
    private val SUBSYSTEM_CLOSED_LOOP_STRATEGIES = setOf(
        SubsystemControlStrategy.POSITION_PID,
        SubsystemControlStrategy.PROFILED_POSITION_PID,
        SubsystemControlStrategy.VELOCITY_PID,
        SubsystemControlStrategy.BANG_BANG,
    )
    private val SUBSYSTEM_CONTINUOUS_POSITION_STRATEGIES = setOf(
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

    private fun duplicateSubsystemIds(ids: List<String>): Set<String> {
        val seen = hashSetOf<String>()
        return ids.filterNot(seen::add).toSet()
    }
}
