package com.areslib.codegen

import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemHomingComparison
import com.areslib.subsystem.SubsystemMeasurementSource
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
        val targets = listOf(actuator to searchOutput) + document.followersOf(actuator.hardwareId).map { follower ->
            follower to follower.following!!.transformedExpression(searchOutput)
        }
        val write = targets.joinToString("\n                        ") { (target, expression) ->
            val bounded = "($expression).coerceIn(-12.0, 12.0)"
            val hwWrite = if (isFtc) {
                "requireNotNull(${target.hardwareId}) { ${("Missing ${target.displayName}").quoted()} }.power = (${target.hardwareId}Command / 12.0).coerceIn(-1.0, 1.0)"
            } else {
                "${target.hardwareId}.setVoltage(${target.hardwareId}Command)"
            }
            "${target.hardwareId}Command = $bounded\n                        $hwWrite"
        }
        val zero = if (actuator.kind == SubsystemHardwareKind.MOTOR) {
            targets.joinToString("\n                        ") { (target, _) ->
                if (isFtc) {
                    val motorVar = "${target.hardwareId}HomingMotor"
                    "val $motorVar = requireNotNull(${target.hardwareId}) { ${("Missing ${target.displayName}").quoted()} }\n" +
                        "                        $motorVar.mode = com.qualcomm.robotcore.hardware.DcMotor.RunMode.STOP_AND_RESET_ENCODER\n" +
                        "                        $motorVar.mode = com.qualcomm.robotcore.hardware.DcMotor.RunMode.RUN_WITHOUT_ENCODER\n" +
                        "                        ${target.hardwareId}HomeOffsetNative = ${homing.zeroPosition.kotlinDouble()}"
                } else {
                    "check(${target.hardwareId}.setPosition(${homing.zeroPosition.kotlinDouble()}).isOK) { \"Failed to establish home position\" }"
                }
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
                        safe()
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
        val targets = listOf(actuator to output) + document.followersOf(actuator.hardwareId).map { follower ->
            follower to follower.following!!.transformedExpression(output)
        }
        val writes = targets.joinToString("\n                    ") { (target, expression) ->
            val applied = "(${target.invertedExpression(expression)}).coerceIn(-12.0, 12.0)"
            "${target.hardwareId}Command = $applied\n                    ${target.hardwareId}SimSignal.publish(${target.hardwareId}Command)"
        }
        val zeroPositionReset = targets.flatMap { (target, _) ->
            target.measurements.filter { it.source == SubsystemMeasurementSource.MOTOR_POSITION_NATIVE }
        }.distinctBy { it.fieldId }.mapNotNull { measurement ->
            val field = document.field(measurement.fieldId) ?: return@mapNotNull null
            if (field.type == SubsystemValueType.DOUBLE) {
                "${field.fieldId} = ${homing.zeroPosition.kotlinDouble()} * ${measurement.scale.kotlinDouble()} + ${measurement.offset.kotlinDouble()}"
            } else null
        }.joinToString("\n                    ")
        return """
                override fun commandHoming(): Boolean {
                    if (!configurationHealthy || !feedbackValid || !currentReadingValid ||
                        outputFaultLatched || homingFaultLatched || closed) return false
                    if (failNextWrite) { failNextWrite = false; return false }
                    $writes
                    return true
                }

                override fun establishHome(): Boolean {
                    if (!homingConditionMet || !recoverWithNeutral()) return false
                    ${if (zeroPositionReset.isBlank()) "// No native motor position fields declared." else zeroPositionReset}
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


    private val SAFETY_TELEMETRY_SUFFIXES = listOf(
        "FeedbackValid" to "feedbackValid",
        "ConfigurationHealthy" to "configurationHealthy",
        "Homed" to "homed",
        "HomingConditionMet" to "homingConditionMet",
        "HomingFaultLatched" to "homingFaultLatched",
        "Calibrated" to "calibrated",
        "CurrentReadingValid" to "currentReadingValid",
        "OutputFaultLatched" to "outputFaultLatched",
    )

    private fun telemetryTopicSuffixes(document: SubsystemDocument): List<String> {
        val measurementSuffixes = document.hardware.flatMap { it.measurements }
            .mapNotNull { document.field(it.fieldId) }
            .distinctBy { it.fieldId }
            .map { it.fieldId }
        val actuatorSuffixes = document.hardware.filter { it.kind.isActuator() }
            .map { "AppliedOutputs/${it.hardwareId}/${it.kind.name}" }
        return measurementSuffixes + actuatorSuffixes + SAFETY_TELEMETRY_SUFFIXES.map { it.first }
    }

    fun telemetryCacheDeclaration(document: SubsystemDocument): String {
        if (!document.safety.telemetryEnabled) return ""
        val entries = telemetryTopicSuffixes(document).joinToString(",\n            ") { suffix ->
            "\"${'$'}prefix/$suffix\""
        }
        return """
    private var cachedTelemetryPrefix: String? = null
    private var cachedTelemetryTopics: Array<String>? = null

    private fun telemetryTopics(prefix: String): Array<String> {
        val existing = cachedTelemetryTopics
        if (existing != null && cachedTelemetryPrefix == prefix) return existing
        val created = arrayOf(
            $entries,
        )
        cachedTelemetryPrefix = prefix
        cachedTelemetryTopics = created
        return created
    }
        """.trimIndent().prependIndent("                ")
    }

    fun telemetryBody(document: SubsystemDocument): String {
        if (!document.safety.telemetryEnabled) return "        // Telemetry is disabled by the subsystem safety document."
        var index = 0
        val lines = mutableListOf("        val topics = telemetryTopics(prefix)")
        document.hardware.flatMap { it.measurements }
            .mapNotNull { document.field(it.fieldId) }
            .distinctBy { it.fieldId }
            .forEach { field ->
                val topicExpr = "topics[${index++}]"
                lines += when (field.type) {
                    SubsystemValueType.DOUBLE -> "        telemetry.putNumber($topicExpr, ${field.fieldId})"
                    SubsystemValueType.BOOLEAN -> "        telemetry.putBoolean($topicExpr, ${field.fieldId})"
                    SubsystemValueType.INT -> "        telemetry.putNumber($topicExpr, ${field.fieldId}.toDouble())"
                    SubsystemValueType.STRING -> "        telemetry.putString($topicExpr, ${field.fieldId})"
                }
            }
        document.hardware.filter { it.kind.isActuator() }.forEach { device ->
            lines += "        telemetry.putNumber(topics[${index++}], ${device.hardwareId}Command)"
        }
        SAFETY_TELEMETRY_SUFFIXES.forEach { (_, expr) ->
            lines += "        telemetry.putBoolean(topics[${index++}], $expr)"
        }
        return lines.joinToString("\n")
    }
}

