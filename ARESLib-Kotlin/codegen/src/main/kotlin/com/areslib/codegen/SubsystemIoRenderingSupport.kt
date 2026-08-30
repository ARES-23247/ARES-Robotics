package com.areslib.codegen

import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemHomingComparison
import com.areslib.subsystem.SubsystemValueType

/** Shared source fragments used by physical and mock IO renderers. */
internal object SubsystemIoRenderingSupport {
    fun automaticRecoveryMethods(document: SubsystemDocument): String {
        val recovery = document.safety.faultRecovery
        val body = if (recovery.enabled) {
            val actuator = document.hardware.single { it.hardwareId == recovery.actuatorId }
            """override fun commandAutomaticRecovery(value: Double): Boolean {
    if (outputFaultLatched || !value.isFinite()) return false
    ${actuator.commandName()}(value)
    return !outputFaultLatched
}

override fun latchOutputFault() {
    outputFaultLatched = true
    safe()
}"""
        } else {
            """override fun commandAutomaticRecovery(value: Double): Boolean = false

override fun latchOutputFault() {
    outputFaultLatched = true
    safe()
}"""
        }
        return body.prependIndent("                ")
    }

    fun homingConditionExpression(document: SubsystemDocument, prefix: String): String {
        if (!document.requiresHoming()) return "false"
        return document.safety.homing.evidence.joinToString(" && ") { evidence ->
            val value = if (prefix.isEmpty()) evidence.fieldId else "$prefix${evidence.fieldId.pascalCase()}"
            when (evidence.comparison) {
                SubsystemHomingComparison.TRUE -> "$value == true"
                SubsystemHomingComparison.FALSE -> "$value == false"
                SubsystemHomingComparison.AT_OR_ABOVE -> "$value >= ${requireNotNull(evidence.threshold).kotlinDouble()}"
                SubsystemHomingComparison.AT_OR_BELOW -> "$value <= ${requireNotNull(evidence.threshold).kotlinDouble()}"
                SubsystemHomingComparison.ABS_AT_OR_ABOVE -> "kotlin.math.abs($value) >= ${requireNotNull(evidence.threshold).kotlinDouble()}"
                SubsystemHomingComparison.ABS_AT_OR_BELOW -> "kotlin.math.abs($value) <= ${requireNotNull(evidence.threshold).kotlinDouble()}"
            }
        }.ifBlank { "false" }
    }

    fun homingMethods(document: SubsystemDocument, isFtc: Boolean): String {
        if (!document.requiresHoming()) return """
                override fun commandHoming(): Boolean = false
                override fun establishHome(): Boolean = false
                override fun failHoming() { homingFaultLatched = false }
                override fun cancelHoming(): Boolean = recoverWithNeutral()
        """.trimIndent().prependIndent("                ")
        val homing = document.safety.homing
        val actuator = document.hardware.first { it.hardwareId == homing.actuatorId }
        val searchOutput = requireNotNull(homing.searchOutput).kotlinDouble()
        val write = if (isFtc) {
            "requireNotNull(${actuator.hardwareId}) { ${("Missing ${actuator.displayName}").quoted()} }.power = ($searchOutput / 12.0).coerceIn(-1.0, 1.0)"
        } else {
            "${actuator.hardwareId}.setVoltage($searchOutput)"
        }
        val zero = if (actuator.kind == SubsystemHardwareKind.MOTOR) {
            if (isFtc) {
                "requireNotNull(${actuator.hardwareId}) { ${("Missing ${actuator.displayName}").quoted()} }.mode = com.qualcomm.robotcore.hardware.DcMotor.RunMode.STOP_AND_RESET_ENCODER"
            } else {
                "check(${actuator.hardwareId}.setPosition(${homing.zeroPosition.kotlinDouble()}).isOK) { \"Failed to establish home position\" }"
            }
        } else "// This homing strategy does not reset an encoder."
        return """
                override fun commandHoming(): Boolean {
                    if (!configurationHealthy || !feedbackValid || !currentReadingValid ||
                        outputFaultLatched || homingFaultLatched || closed) return false
                    return try {
                        $write
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

                override fun establishHome(): Boolean {
                    if (!homingConditionMet || !applyNeutral()) return false
                    return try {
                        $zero
                        homed = true
                        homingFaultLatched = false
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

                override fun failHoming() {
                    if (!applyNeutral()) outputFaultLatched = ${document.safety.latchOutputFaults}
                    homingFaultLatched = true
                    homed = false
                }

                override fun cancelHoming(): Boolean {
                    val neutral = recoverWithNeutral()
                    if (neutral) homingFaultLatched = false
                    return neutral
                }
        """.trimIndent().prependIndent("                ")
    }

    fun mockHomingMethods(document: SubsystemDocument): String {
        if (!document.requiresHoming()) return """
                override fun commandHoming(): Boolean = false
                override fun establishHome(): Boolean = false
                override fun failHoming() { homingFaultLatched = false }
                override fun cancelHoming(): Boolean = recoverWithNeutral()
        """.trimIndent().prependIndent("                ")
        val homing = document.safety.homing
        val actuator = document.hardware.first { it.hardwareId == homing.actuatorId }
        val output = requireNotNull(homing.searchOutput).kotlinDouble()
        return """
                override fun commandHoming(): Boolean {
                    if (!configurationHealthy || !feedbackValid || !currentReadingValid ||
                        outputFaultLatched || homingFaultLatched || closed) return false
                    if (failNextWrite) { failNextWrite = false; return false }
                    ${actuator.hardwareId}Command = $output
                    return true
                }

                override fun establishHome(): Boolean {
                    if (!homingConditionMet || !recoverWithNeutral()) return false
                    homed = true
                    homingFaultLatched = false
                    return true
                }

                override fun failHoming() {
                    safe()
                    homingFaultLatched = true
                    homed = false
                }

                override fun cancelHoming(): Boolean {
                    val neutral = recoverWithNeutral()
                    if (neutral) homingFaultLatched = false
                    return neutral
                }
        """.trimIndent().prependIndent("                ")
    }


    fun telemetryBody(document: SubsystemDocument): String {
        if (!document.safety.telemetryEnabled) return "        // Telemetry is disabled by the subsystem safety document."
        val measurements = document.hardware.flatMap { it.measurements }
            .mapNotNull { document.field(it.fieldId) }
            .distinctBy { it.fieldId }
            .joinToString("\n") { field ->
                when (field.type) {
                    SubsystemValueType.DOUBLE -> "        telemetry.putNumber(\"${'$'}prefix/${field.fieldId}\", ${field.fieldId})"
                    SubsystemValueType.BOOLEAN -> "        telemetry.putBoolean(\"${'$'}prefix/${field.fieldId}\", ${field.fieldId})"
                    SubsystemValueType.INT -> "        telemetry.putNumber(\"${'$'}prefix/${field.fieldId}\", ${field.fieldId}.toDouble())"
                    SubsystemValueType.STRING -> "        telemetry.putString(\"${'$'}prefix/${field.fieldId}\", ${field.fieldId})"
                }
            }
        val safety = """
        telemetry.putBoolean("${'$'}prefix/FeedbackValid", feedbackValid)
        telemetry.putBoolean("${'$'}prefix/ConfigurationHealthy", configurationHealthy)
        telemetry.putBoolean("${'$'}prefix/Homed", homed)
        telemetry.putBoolean("${'$'}prefix/HomingConditionMet", homingConditionMet)
        telemetry.putBoolean("${'$'}prefix/HomingFaultLatched", homingFaultLatched)
        telemetry.putBoolean("${'$'}prefix/Calibrated", calibrated)
        telemetry.putBoolean("${'$'}prefix/CurrentReadingValid", currentReadingValid)
        telemetry.putBoolean("${'$'}prefix/OutputFaultLatched", outputFaultLatched)
        """.trimIndent()
        val appliedOutputs = document.hardware.filter { it.kind.isActuator() }.joinToString("\n") { device ->
            "        telemetry.putNumber(\"${'$'}prefix/AppliedOutputs/${device.hardwareId}/${device.kind.name}\", ${device.hardwareId}Command)"
        }
        return listOf(measurements, appliedOutputs, safety).filter(String::isNotBlank).joinToString("\n")
    }
}

