package com.areslib.subsystem

import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType

internal fun renderSafetySection(
    out: StringBuilder,
    safety: SubsystemSafetyDocument,
    hwVarNames: Map<String, String>,
    fieldVarNames: Map<String, String>,
) {
    val defaultSafety = SubsystemSafetyDocument()
    if (safety == defaultSafety) return
    out.appendLine("    safety {")
    if (safety.feedbackTimeoutMs != 250L) out.appendLine("        feedbackTimeoutMs = ${safety.feedbackTimeoutMs?.let { "${it}L" } ?: "null"}")
    if (safety.requiresCalibration) out.appendLine("        requiresCalibration = true")
    if (!safety.requiresConfigurationHealth) out.appendLine("        requiresConfigurationHealth = false")
    if (safety.requiresCurrentMonitoring) out.appendLine("        requiresCurrentMonitoring = true")
    if (!safety.latchOutputFaults) out.appendLine("        latchOutputFaults = false")
    if (!safety.requiresExplicitNeutralRecovery) out.appendLine("        requiresExplicitNeutralRecovery = false")
    if (!safety.telemetryEnabled) out.appendLine("        telemetryEnabled = false")
    if (!safety.zeroAllocationPeriodic) out.appendLine("        zeroAllocationPeriodic = false")
    val h = safety.homing
    val homingActuatorId = h.actuatorId
    if (h.method != SubsystemHomingMethod.NONE && homingActuatorId != null) {
        val actRef = hwVarNames[homingActuatorId] ?: "SubsystemHardwareRef(${homingActuatorId.quote()})"
        val firstEv = h.evidence.firstOrNull()
        if (firstEv != null) {
            val fldRef = fieldVarNames[firstEv.fieldId] ?: "SubsystemFieldRef(${firstEv.fieldId.quote()})"
            when (h.method) {
                SubsystemHomingMethod.DIGITAL_SENSOR -> out.appendLine(
                    "        homing.digitalSensor($actRef, $fldRef, searchOutput = ${h.searchOutput}, " +
                        "activeWhen = ${firstEv.comparison == SubsystemHomingComparison.TRUE}, " +
                        "dwellMs = ${h.dwellMs}L, timeoutMs = ${h.timeoutMs}L, zeroPosition = ${h.zeroPosition})"
                )
                SubsystemHomingMethod.CURRENT_STALL -> out.appendLine(
                    "        homing.currentStall($actRef, $fldRef, searchOutput = ${h.searchOutput}, " +
                        "minimumCurrentAmps = ${firstEv.threshold ?: 0.0}, dwellMs = ${h.dwellMs}L, " +
                        "timeoutMs = ${h.timeoutMs}L, zeroPosition = ${h.zeroPosition})"
                )
                SubsystemHomingMethod.VELOCITY_STALL -> out.appendLine(
                    "        homing.velocityStall($actRef, $fldRef, searchOutput = ${h.searchOutput}, " +
                        "maximumAbsoluteVelocity = ${firstEv.threshold ?: 0.0}, dwellMs = ${h.dwellMs}L, " +
                        "timeoutMs = ${h.timeoutMs}L, zeroPosition = ${h.zeroPosition})"
                )
                else -> Unit
            }
        }
    }
    val fr = safety.faultRecovery
    val frActuatorId = fr.actuatorId
    val frCurrentFieldId = fr.currentFieldId
    if (fr.enabled && frActuatorId != null && frCurrentFieldId != null) {
        val actRef = hwVarNames[frActuatorId] ?: "SubsystemHardwareRef(${frActuatorId.quote()})"
        val curRef = fieldVarNames[frCurrentFieldId] ?: "SubsystemFieldRef(${frCurrentFieldId.quote()})"
        when (fr.recoveryAction) {
            FaultRecoveryActionKind.REVERSE_BRIEFLY -> out.appendLine(
                "        faultRecovery.reverseBriefly($actRef, $curRef, " +
                    "currentThresholdAmps = ${fr.currentThresholdAmps}, " +
                    "currentDurationMs = ${fr.currentDurationMs}L, " +
                    "reverseDurationMs = ${fr.reverseDurationMs}L, " +
                    "reverseDutyCycle = ${fr.reverseDutyCycle}, maxRetries = ${fr.maxRetries})"
            )
            FaultRecoveryActionKind.NEUTRAL_STOP -> out.appendLine(
                "        faultRecovery.neutralStop($actRef, $curRef, " +
                    "currentThresholdAmps = ${fr.currentThresholdAmps}, " +
                    "currentDurationMs = ${fr.currentDurationMs}L, maxRetries = ${fr.maxRetries})"
            )
            FaultRecoveryActionKind.HOLD_POSITION -> out.appendLine(
                "        faultRecovery.holdPosition($actRef, $curRef, " +
                    "currentThresholdAmps = ${fr.currentThresholdAmps}, " +
                    "currentDurationMs = ${fr.currentDurationMs}L, maxRetries = ${fr.maxRetries})"
            )
            FaultRecoveryActionKind.NONE -> Unit
        }
    }
    out.appendLine("    }")
}

internal fun renderLinkageSection(
    out: StringBuilder,
    linkage: SubsystemLinkageDocument,
    hwVarNames: Map<String, String>,
    fieldVarNames: Map<String, String>,
) {
    out.appendLine("    linkage {")
    out.appendLine("        link1LengthMeters = ${linkage.link1LengthMeters}")
    out.appendLine("        link2LengthMeters = ${linkage.link2LengthMeters}")
    out.appendLine("        link1MassKg = ${linkage.link1MassKg}")
    out.appendLine("        link2MassKg = ${linkage.link2MassKg}")
    linkage.joint1ActuatorId?.let {
        out.appendLine("        joint1Actuator = ${hwVarNames[it] ?: "SubsystemHardwareRef(${it.quote()})"}")
    }
    linkage.joint2ActuatorId?.let {
        out.appendLine("        joint2Actuator = ${hwVarNames[it] ?: "SubsystemHardwareRef(${it.quote()})"}")
    }
    linkage.joint1AngleFieldId?.let {
        out.appendLine("        joint1AngleField = ${fieldVarNames[it] ?: "SubsystemFieldRef(${it.quote()})"}")
    }
    linkage.joint2AngleFieldId?.let {
        out.appendLine("        joint2AngleField = ${fieldVarNames[it] ?: "SubsystemFieldRef(${it.quote()})"}")
    }
    out.appendLine("    }")
}

internal fun renderNonDoubleTuningParam(param: TuningParameterDeclaration): String {
    val defaultExpr = when (param.type) {
        TuningParameterType.DOUBLE -> "com.areslib.tuning.TuningValue(doubleValue = ${param.defaultValue.doubleValue ?: 0.0})"
        TuningParameterType.INT -> "com.areslib.tuning.TuningValue(intValue = ${param.defaultValue.intValue ?: 0})"
        TuningParameterType.BOOLEAN -> "com.areslib.tuning.TuningValue(booleanValue = ${param.defaultValue.booleanValue ?: false})"
        TuningParameterType.TEXT,
        TuningParameterType.ENUM -> "com.areslib.tuning.TuningValue(textValue = ${(param.defaultValue.textValue ?: "").quote()})"
    }
    val enumOpts = param.enumOptions.joinToString(", ") { it.quote() }
    return "com.areslib.tuning.TuningParameterDeclaration(" +
        "uid = ${param.uid.quote()}, key = ${param.key.quote()}, componentUid = ${param.componentUid.quote()}, " +
        "displayName = ${param.displayName.quote()}, description = ${param.description.quote()}, " +
        "type = com.areslib.tuning.TuningParameterType.${param.type.name}, " +
        "unit = ${param.unit?.quote() ?: "null"}, minimum = ${param.minimum}, maximum = ${param.maximum}, " +
        "enumOptions = listOf($enumOpts), defaultValue = $defaultExpr, " +
        "applyPolicy = TuningApplyPolicy.${param.applyPolicy.name})"
}

internal fun String.toCamelIdentifier(fallback: String): String {
    val parts = split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return fallback
    val raw = parts.first().replaceFirstChar { it.lowercase() } +
        parts.drop(1).joinToString("") { it.replaceFirstChar { ch -> ch.uppercase() } }
    return if (raw.first().isDigit()) "_$raw" else raw
}

internal fun uniqueIdentifier(base: String, used: MutableSet<String>): String {
    var candidate = base
    var counter = 2
    while (!used.add(candidate)) {
        candidate = "$base$counter"
        counter++
    }
    return candidate
}

internal fun String.quote(): String = buildString {
    append('"')
    for (ch in this@quote) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> append(ch)
        }
    }
    append('"')
}
