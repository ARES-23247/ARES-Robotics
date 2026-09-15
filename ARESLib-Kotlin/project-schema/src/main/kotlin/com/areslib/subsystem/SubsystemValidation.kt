package com.areslib.subsystem

import com.areslib.tuning.validateTuningParameterDeclarations
import com.areslib.subsystem.SubsystemControlValidation.SUBSYSTEM_ACTUATOR_KINDS
import com.areslib.subsystem.SubsystemControlValidation.SUBSYSTEM_CLOSED_LOOP_STRATEGIES

private val xrpOnlyMeasurementSources = setOf(
    SubsystemMeasurementSource.REFLECTANCE_NORMALIZED,
    SubsystemMeasurementSource.IMU_PITCH_RADIANS,
    SubsystemMeasurementSource.IMU_ROLL_RADIANS,
    SubsystemMeasurementSource.IMU_GYRO_X_RADIANS_PER_SECOND,
    SubsystemMeasurementSource.IMU_GYRO_Y_RADIANS_PER_SECOND,
    SubsystemMeasurementSource.IMU_ACCEL_X_METERS_PER_SECOND_SQUARED,
    SubsystemMeasurementSource.IMU_ACCEL_Y_METERS_PER_SECOND_SQUARED,
    SubsystemMeasurementSource.IMU_ACCEL_Z_METERS_PER_SECOND_SQUARED,
)

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
        SubsystemImplementationValidation.validateImplementation(document, ::issue)
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
                    if (!device.kind.supportsPlatform(SubsystemPlatform.FTC)) {
                        issue("$path.kind", "${device.kind} has no generated FTC adapter")
                    }
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
                    SubsystemHardwareKind.COLOR_SENSOR,
                    SubsystemHardwareKind.DIGITAL_OUTPUT,
                    SubsystemHardwareKind.PWM_OUTPUT,
                    SubsystemHardwareKind.BUZZER ->
                        issue("$path.kind", "${device.kind} has no generated FRC adapter")
                }
                SubsystemPlatform.XRP -> {
                    if (device.connection.canId != null) {
                        issue("$path.connection", "XRP hardware must not use CAN addressing")
                    }
                    if (device.kind == SubsystemHardwareKind.SOLENOID) {
                        issue("$path.kind", "Generated pneumatic solenoids are not available for XRP projects")
                    }
                    if (!device.kind.supportsPlatform(SubsystemPlatform.XRP)) {
                        issue("$path.kind", "${device.kind} has no generated XRPLib adapter")
                    }
                    when (device.kind) {
                        SubsystemHardwareKind.MOTOR -> if (device.connection.channel !in setOf(3, 4)) {
                            issue("$path.connection.channel", "XRP mechanism motors use channel 3 or 4")
                        }
                        SubsystemHardwareKind.POSITIONAL_SERVO -> if (device.connection.channel !in setOf(1, 2, 3, 4)) {
                            issue("$path.connection.channel", "XRP servos use channel 1 through 4")
                        }
                        SubsystemHardwareKind.DIGITAL_INPUT -> if (device.connection.channel != null && device.connection.channel !in 0..29) {
                            issue("$path.connection.channel", "XRP digital inputs use GPIO channel 0 through 29, or no channel for the built-in user button")
                        }
                        SubsystemHardwareKind.DIGITAL_OUTPUT,
                        SubsystemHardwareKind.PWM_OUTPUT -> if (device.connection.channel !in 0..29) {
                            issue("$path.connection.channel", "XRP digital and PWM outputs use GPIO channel 0 through 29")
                        }
                        SubsystemHardwareKind.ANALOG_INPUT -> if (
                            device.measurements.none { it.source == SubsystemMeasurementSource.REFLECTANCE_NORMALIZED } &&
                                device.connection.channel !in 26..29
                        ) {
                            issue("$path.connection.channel", "XRP analog inputs use ADC-capable GPIO 26 through 29")
                        } else if (
                            device.measurements.any { it.source == SubsystemMeasurementSource.REFLECTANCE_NORMALIZED } &&
                                device.connection.channel !in 0..2
                        ) {
                            issue("$path.connection.channel", "Built-in XRP reflectance sensors use channel 0 for left, 1 for middle, or 2 for right")
                        }
                        SubsystemHardwareKind.INDICATOR_LIGHT -> if (device.connection.channel != null && device.connection.channel !in 0..2) {
                            issue("$path.connection.channel", "XRP indicator lights use no channel for green, or RGB component channel 0, 1, or 2")
                        }
                        SubsystemHardwareKind.DISTANCE_SENSOR,
                        SubsystemHardwareKind.IMU,
                        SubsystemHardwareKind.BUZZER -> if (device.connection.channel != null) {
                            issue("$path.connection.channel", "The default XRP sensor has no selectable channel")
                        }
                        else -> Unit
                    }
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
                    SubsystemHardwareKind.DIGITAL_OUTPUT -> if (neutral != 0.0 && neutral != 1.0) {
                        issue("$path.safeOutput", "Digital-output neutral must be exactly 0 (off) or 1 (on)")
                    }
                    SubsystemHardwareKind.PWM_OUTPUT -> if (neutral !in 0.0..1.0) {
                        issue("$path.safeOutput", "PWM-output neutral must be within 0 to 1")
                    }
                    SubsystemHardwareKind.BUZZER -> if (neutral !in 0.0..127.0) {
                        issue("$path.safeOutput", "Buzzer neutral must be a MIDI note from 0 through 127")
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
                    if (document.platform != SubsystemPlatform.XRP && source in xrpOnlyMeasurementSources) {
                        issue("$measurementPath.source", "$source is available only from the generated XRP adapter")
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
                    val value = field.defaultInt
                    if (value != null && field.minimum != null && value < field.minimum) issue(path, "Default is below the minimum")
                    if (value != null && field.maximum != null && value > field.maximum) issue(path, "Default is above the maximum")
                }
                SubsystemValueType.STRING -> {
                    if (field.defaultText == null) issue("$path.defaultText", "String fields require a default")
                    if (field.defaultNumber != null || field.defaultBoolean != null || field.defaultInt != null) issue(path, "String field contains a default for another type")
                    if (field.minimum != null || field.maximum != null) issue(path, "String fields cannot have numeric limits")
                }
            }
        }
    
        SubsystemControlValidation.validate(document, hardwareById, fieldsById, ::issue)

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
        SubsystemSafetyValidation.validateHoming(document, hardwareById, fieldsById, ::issue)
        SubsystemSafetyValidation.validateFaultRecovery(document, ::issue)
        SubsystemSafetyValidation.validateInterlocks(document, ::issue)
        SubsystemImplementationValidation.validateLinkage(document, ::issue)
        SubsystemImplementationValidation.validateSimInteraction(document, ::issue)
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
    fun validateAll(documents: List<SubsystemDocument>): List<SubsystemValidationIssue> =
        SubsystemProjectValidation.validateAll(documents)

    private fun SubsystemHubFacingDirection.isPerpendicularTo(other: SubsystemHubFacingDirection): Boolean =
        axisGroup() != other.axisGroup()

    private fun SubsystemHubFacingDirection.axisGroup(): Int = when (this) {
        SubsystemHubFacingDirection.UP, SubsystemHubFacingDirection.DOWN -> 0
        SubsystemHubFacingDirection.FORWARD, SubsystemHubFacingDirection.BACKWARD -> 1
        SubsystemHubFacingDirection.LEFT, SubsystemHubFacingDirection.RIGHT -> 2
    }

}
