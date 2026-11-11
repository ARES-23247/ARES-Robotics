package com.areslib.subsystem

import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterType

/**
 * Renders this canonical [SubsystemDocument] into idiomatic, copy-pasteable Kotlin Text DSL code
 * ([subsystem]).
 *
 * Emits typed `val` references for state fields and hardware devices so control loops, follower
 * relationships, and safety rules read like clean hand-authored Kotlin code.
 */
fun SubsystemDocument.toKotlinDsl(): String = buildString {
    val defaultTypeName = defaultSubsystemKotlinTypeName(documentId)
    val defaultDisplayName = kotlinTypeName.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
    if (kotlinTypeName == defaultTypeName) {
        appendLine("subsystem(${documentId.quote()}, SubsystemPlatform.${platform.name}) {")
    } else {
        appendLine("subsystem(${documentId.quote()}, ${kotlinTypeName.quote()}, SubsystemPlatform.${platform.name}) {")
    }

    if (displayName != defaultDisplayName && displayName.isNotBlank()) {
        appendLine("    displayName = ${displayName.quote()}")
    }
    if (description.isNotBlank()) {
        appendLine("    description = ${description.quote()}")
    }
    if (template != SubsystemTemplate.ADVANCED_CUSTOM) {
        appendLine("    template = SubsystemTemplate.${template.name}")
    }
    if (!requiredAtStartup) appendLine("    requiredAtStartup = false")
    if (!generateMockIo) appendLine("    generateMockIo = false")
    if (!generateTest) appendLine("    generateTest = false")
    autonomousResourceKey?.let {
        appendLine("    autonomousResourceKey = ${it.quote()}")
    }

    val fieldVarNames = linkedMapOf<String, String>()
    val usedIdentifiers = linkedSetOf<String>()
    stateFields.forEach { fld ->
        val varName = uniqueIdentifier(fld.fieldId.toCamelIdentifier("field"), usedIdentifiers)
        fieldVarNames[fld.fieldId] = varName
        appendLine("    val $varName = ${renderStateFieldCall(fld)}")
    }

    val hwVarNames = linkedMapOf<String, String>()
    hardware.forEach { dev ->
        val varName = uniqueIdentifier(dev.hardwareId.toCamelIdentifier("device"), usedIdentifiers)
        hwVarNames[dev.hardwareId] = varName
        renderHardwareDevice(this, dev, varName, platform, fieldVarNames, hwVarNames)
    }

    controlLoops.forEach { loop ->
        renderControlLoop(this, loop, hwVarNames, fieldVarNames)
    }

    renderSafetySection(this, safety, hwVarNames, fieldVarNames)

    if (linkage.enabled) {
        renderLinkageSection(this, linkage, hwVarNames, fieldVarNames)
    }

    interlocks.forEach { rule ->
        val args = buildList {
            add(rule.interlockId.quote())
            add("targetSubsystemUid = ${rule.targetSubsystemUid.quote()}")
            add("targetFieldId = ${rule.targetFieldId.quote()}")
            if (rule.comparison != InterlockComparison.LESS_THAN) {
                add("comparison = InterlockComparison.${rule.comparison.name}")
            }
            if (rule.thresholdValue != 0.0) add("thresholdValue = ${rule.thresholdValue}")
        }
        val stateName = rule.targetStateName
        val hasBody = stateName != null ||
            rule.forbiddenZoneDescription.isNotBlank() ||
            rule.safeFallbackValue != null
        if (!hasBody) {
            appendLine("    interlock(${args.joinToString(", ")})")
        } else {
            appendLine("    interlock(${args.joinToString(", ")}) {")
            if (stateName != null) {
                appendLine("        targetStateName = ${stateName.quote()}")
            }
            if (rule.forbiddenZoneDescription.isNotBlank()) {
                appendLine("        forbiddenZoneDescription = ${rule.forbiddenZoneDescription.quote()}")
            }
            if (rule.safeFallbackValue != null) {
                appendLine("        safeFallbackValue = ${rule.safeFallbackValue}")
            }
            appendLine("    }")
        }
    }

    tuningParameters.forEach { param ->
        if (param.type == TuningParameterType.DOUBLE) {
            val defaultVal = param.defaultValue.doubleValue ?: 0.0
            val args = buildList {
                add("key = ${param.key.quote()}")
                add("defaultValue = $defaultVal")
                if (param.componentUid != documentId) add("componentUid = ${param.componentUid.quote()}")
                param.unit?.let { add("unit = ${it.quote()}") }
                param.minimum?.let { add("minimum = $it") }
                param.maximum?.let { add("maximum = $it") }
                if (param.applyPolicy != TuningApplyPolicy.LIVE_SAFE) {
                    add("applyPolicy = TuningApplyPolicy.${param.applyPolicy.name}")
                }
            }
            appendLine("    tuningDouble(${args.joinToString(", ")})")
        } else {
            appendLine("    tuningParam(${renderNonDoubleTuningParam(param)})")
        }
    }

    capabilityActionKeys.forEach { key ->
        appendLine("    capabilityAction(${key.quote()})")
    }

    append("}")
}

private fun renderStateFieldCall(fld: SubsystemStateFieldDocument): String {
    val defaultDisplay = defaultSubsystemDisplayName(fld.fieldId)
    val methodPrefix = when (fld.role) {
        SubsystemFieldRole.TARGET -> "target"
        SubsystemFieldRole.MEASUREMENT -> "measurement"
        SubsystemFieldRole.STATUS -> "status"
        SubsystemFieldRole.CONFIGURATION -> "config"
    }
    return when (fld.type) {
        SubsystemValueType.DOUBLE -> {
            val args = buildList {
                add(fld.fieldId.quote())
                val def = fld.defaultNumber ?: 0.0
                if (def != 0.0) add("default = $def")
                fld.unit?.let { add("unit = ${it.quote()}") }
                fld.minimum?.let { add("minimum = $it") }
                fld.maximum?.let { add("maximum = $it") }
                if (fld.displayName != defaultDisplay) add("displayName = ${fld.displayName.quote()}")
            }
            "state.${methodPrefix}Double(${args.joinToString(", ")})"
        }
        SubsystemValueType.BOOLEAN -> {
            val args = buildList {
                add(fld.fieldId.quote())
                val def = fld.defaultBoolean ?: false
                if (def) add("default = true")
                if (fld.displayName != defaultDisplay) add("displayName = ${fld.displayName.quote()}")
            }
            "state.${methodPrefix}Boolean(${args.joinToString(", ")})"
        }
        SubsystemValueType.INT -> {
            val args = buildList {
                add(fld.fieldId.quote())
                val def = fld.defaultInt ?: 0
                if (def != 0) add("default = $def")
                fld.unit?.let { add("unit = ${it.quote()}") }
                fld.minimum?.let { add("minimum = $it") }
                fld.maximum?.let { add("maximum = $it") }
                if (fld.displayName != defaultDisplay) add("displayName = ${fld.displayName.quote()}")
            }
            "state.${methodPrefix}Int(${args.joinToString(", ")})"
        }
        SubsystemValueType.STRING -> {
            val args = buildList {
                add(fld.fieldId.quote())
                val def = fld.defaultText ?: ""
                if (def.isNotEmpty()) add("default = ${def.quote()}")
                if (fld.displayName != defaultDisplay) add("displayName = ${fld.displayName.quote()}")
            }
            "state.${methodPrefix}Text(${args.joinToString(", ")})"
        }
    }
}

private fun renderHardwareDevice(
    out: StringBuilder,
    dev: SubsystemHardwareDocument,
    varName: String,
    platform: SubsystemPlatform,
    fieldVarNames: Map<String, String>,
    hwVarNames: Map<String, String>,
) {
    val fnName = when (dev.kind) {
        SubsystemHardwareKind.MOTOR -> "motor"
        SubsystemHardwareKind.POSITIONAL_SERVO -> "positionalServo"
        SubsystemHardwareKind.CONTINUOUS_SERVO -> "continuousServo"
        SubsystemHardwareKind.ABSOLUTE_ENCODER -> "absoluteEncoder"
        SubsystemHardwareKind.QUADRATURE_ENCODER -> "quadratureEncoder"
        SubsystemHardwareKind.DIGITAL_INPUT -> "digitalInput"
        SubsystemHardwareKind.DIGITAL_OUTPUT -> "digitalOutput"
        SubsystemHardwareKind.ANALOG_INPUT -> "analogInput"
        SubsystemHardwareKind.PWM_OUTPUT -> "pwmOutput"
        SubsystemHardwareKind.DISTANCE_SENSOR -> "distanceSensor"
        SubsystemHardwareKind.IMU -> "imu"
        SubsystemHardwareKind.COLOR_SENSOR -> "colorSensor"
        SubsystemHardwareKind.SOLENOID -> "solenoid"
        SubsystemHardwareKind.INDICATOR_LIGHT -> "indicatorLight"
        SubsystemHardwareKind.BUZZER -> "buzzer"
        SubsystemHardwareKind.PRISM_DRIVER -> "prismDriver"
    }
    val defaultDisplay = defaultSubsystemDisplayName(dev.hardwareId)
    val callArgs = if (dev.displayName == defaultDisplay) {
        dev.hardwareId.quote()
    } else {
        "${dev.hardwareId.quote()}, ${dev.displayName.quote()}"
    }

    val bodyLines = buildList {
        val defaultMapName = if (platform == SubsystemPlatform.FTC) dev.hardwareId else null
        val hwMapName = dev.connection.hardwareMapName
        if (hwMapName != defaultMapName && hwMapName != null) {
            add("hardwareMapName = ${hwMapName.quote()}")
        }
        dev.connection.canId?.let { add("canId = $it") }
        if (dev.connection.canBus != "rio") {
            add("canBus = ${dev.connection.canBus.quote()}")
        }
        dev.connection.channel?.let { add("channel = $it") }
        dev.connection.secondaryChannel?.let { add("secondaryChannel = $it") }
        dev.connection.pneumaticsModuleType?.let {
            add("pneumaticsModuleType = SubsystemPneumaticsModuleType.${it.name}")
        }
        if (!dev.required) add("required = false")
        if (dev.inverted) add("inverted = true")
        dev.currentLimitAmps?.let { add("currentLimitAmps = $it") }
        val defaultSafe = when (dev.kind) {
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
        }
        val safeOut = dev.safeOutput
        if (safeOut != defaultSafe && safeOut != null) {
            add("safeOutput = $safeOut")
        }
        dev.encoderCountsPerRevolution?.let {
            add("encoderCountsPerRevolution = $it")
        }
        dev.distanceMetersPerVolt?.let {
            add("distanceMetersPerVolt = $it")
        }
        dev.imuLogoFacingDirection?.let {
            add("imuLogoFacingDirection = SubsystemHubFacingDirection.${it.name}")
        }
        dev.imuUsbFacingDirection?.let {
            add("imuUsbFacingDirection = SubsystemHubFacingDirection.${it.name}")
        }
        dev.following?.let { follow ->
            val leaderRef = hwVarNames[follow.leaderId]
                ?: "SubsystemHardwareRef(${follow.leaderId.quote()})"
            val invertedFlag = follow.transform == SubsystemFollowerTransform.INVERTED
            if (invertedFlag) {
                add("follows($leaderRef, inverted = true)")
            } else {
                add("follows($leaderRef)")
            }
        }
        dev.measurements.forEach { m ->
            val fldRef = fieldVarNames[m.fieldId] ?: "SubsystemFieldRef(${m.fieldId.quote()})"
            val mArgs = buildList {
                add(fldRef)
                add("source = SubsystemMeasurementSource.${m.source.name}")
                if (m.scale != 1.0) add("scale = ${m.scale}")
                if (m.offset != 0.0) add("offset = ${m.offset}")
                m.maxAgeMs?.let { add("maxAgeMs = ${it}L") }
                m.validMinimum?.let { add("validMinimum = $it") }
                m.validMaximum?.let { add("validMaximum = $it") }
            }
            add("measurement(${mArgs.joinToString(", ")})")
        }
    }

    if (bodyLines.isEmpty()) {
        out.appendLine("    val $varName = hardware.$fnName($callArgs)")
    } else {
        out.appendLine("    val $varName = hardware.$fnName($callArgs) {")
        bodyLines.forEach { out.appendLine("        $it") }
        out.appendLine("    }")
    }
}

private fun renderControlLoop(
    out: StringBuilder,
    loop: SubsystemControlLoopDocument,
    hwVarNames: Map<String, String>,
    fieldVarNames: Map<String, String>,
) {
    val actuatorRef = hwVarNames[loop.actuatorId] ?: "SubsystemHardwareRef(${loop.actuatorId.quote()})"
    val targetRef = fieldVarNames[loop.targetFieldId] ?: "SubsystemFieldRef(${loop.targetFieldId.quote()})"
    val measRef = loop.measurementFieldId?.let { fieldVarNames[it] ?: "SubsystemFieldRef(${it.quote()})" }
    val fnName = when (loop.strategy) {
        SubsystemControlStrategy.DIRECT -> "openLoop"
        SubsystemControlStrategy.POSITION_PID -> "positionPid"
        SubsystemControlStrategy.PROFILED_POSITION_PID -> "profiledPositionPid"
        SubsystemControlStrategy.VELOCITY_PID -> "velocityPid"
        SubsystemControlStrategy.BANG_BANG -> "bangBang"
        SubsystemControlStrategy.SERVO_POSITION -> "servoPosition"
    }
    val callArgs = if (measRef == null) {
        "${loop.loopId.quote()}, $actuatorRef, $targetRef"
    } else {
        "${loop.loopId.quote()}, $actuatorRef, $targetRef, $measRef"
    }

    val bodyLines = buildList {
        if (loop.kP != 0.0) add("kP = ${loop.kP}")
        if (loop.kI != 0.0) add("kI = ${loop.kI}")
        if (loop.kD != 0.0) add("kD = ${loop.kD}")
        if (loop.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID ||
            loop.motionProfile.maximumVelocity != 1.0 || loop.motionProfile.maximumAcceleration != 2.0
        ) {
            add("maximumVelocity = ${loop.motionProfile.maximumVelocity}")
            add("maximumAcceleration = ${loop.motionProfile.maximumAcceleration}")
        }
        val ff = loop.feedforward
        if (ff.kind != SubsystemFeedforwardKind.NONE) {
            val ffArgs = buildList {
                if (ff.kS != 0.0) add("kS = ${ff.kS}")
                if (ff.kV != 0.0) add("kV = ${ff.kV}")
                if (ff.kG != 0.0) add("kG = ${ff.kG}")
                if (ff.kA != 0.0) add("kA = ${ff.kA}")
                ff.gravityAngleFieldId?.let {
                    add("gravityAngleField = ${fieldVarNames[it] ?: "SubsystemFieldRef(${it.quote()})"}")
                }
                ff.velocityFieldId?.let {
                    add("velocityField = ${fieldVarNames[it] ?: "SubsystemFieldRef(${it.quote()})"}")
                }
                ff.accelerationFieldId?.let {
                    add("accelerationField = ${fieldVarNames[it] ?: "SubsystemFieldRef(${it.quote()})"}")
                }
                if (ff.kind in setOf(SubsystemFeedforwardKind.ARM, SubsystemFeedforwardKind.TWO_DOF_ARM, SubsystemFeedforwardKind.FOUR_BAR_LINKAGE)) {
                    ff.linkageJoint?.let { add("linkageJoint = $it") }
                }
            }
            val helperName = when (ff.kind) {
                SubsystemFeedforwardKind.SIMPLE_MOTOR -> "simpleMotor"
                SubsystemFeedforwardKind.ELEVATOR -> "elevator"
                SubsystemFeedforwardKind.ARM,
                SubsystemFeedforwardKind.TWO_DOF_ARM,
                SubsystemFeedforwardKind.FOUR_BAR_LINKAGE -> "arm"
                SubsystemFeedforwardKind.NONE -> "simpleMotor"
            }
            add("feedforward.$helperName(${ffArgs.joinToString(", ")})")
        }
        if (loop.derivativeFilterTimeConstantSeconds != 0.02) {
            add("derivativeFilterTimeConstantSeconds = ${loop.derivativeFilterTimeConstantSeconds}")
        }
        if (loop.tolerance != 0.0) add("tolerance = ${loop.tolerance}")
        if (loop.hysteresis != 0.0) add("hysteresis = ${loop.hysteresis}")
        if (loop.minimumOutput != -12.0) add("minimumOutput = ${loop.minimumOutput}")
        if (loop.maximumOutput != 12.0) add("maximumOutput = ${loop.maximumOutput}")
        if (loop.continuousInput.enabled) {
            add("continuousInputEnabled = true")
            add("continuousInputMinimum = ${loop.continuousInput.minimumInput}")
            add("continuousInputMaximum = ${loop.continuousInput.maximumInput}")
        }
    }

    if (bodyLines.isEmpty()) {
        out.appendLine("    control.$fnName($callArgs)")
    } else {
        out.appendLine("    control.$fnName($callArgs) {")
        bodyLines.forEach { out.appendLine("        $it") }
        out.appendLine("    }")
    }
}
