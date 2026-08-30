package com.areslib.codegen

import com.areslib.subsystem.SubsystemDocument

/** Renders the platform-neutral state and IO contracts shared by physical and simulated adapters. */
internal object SubsystemContractRenderer {
    fun stateSource(document: SubsystemDocument, pkg: String): String {
        val fields = document.stateFields.joinToString(",\n") { field ->
            val bounds = listOfNotNull(field.minimum?.let { "min=$it" }, field.maximum?.let { "max=$it" })
                .joinToString(", ").takeIf(String::isNotBlank)?.let { "; $it" }.orEmpty()
            val unit = field.unit?.let { " in $it" }.orEmpty()
            "    /** ${field.displayName}: ${field.role.name.lowercase()}$unit$bounds. */\n" +
                "    val ${field.fieldId}: ${field.kotlinType()} = ${field.defaultKotlinLiteral()}"
        }
        val separator = if (fields.isBlank()) "" else ",\n"
        val safetyRequests = buildString {
            if (document.hasSafetyRequestHandshake()) {
                append(
                    "\n    /** Advances for every explicit target command and releases a controller neutral hold. */\n" +
                        "    val commandSequence: Long = 0L,",
                )
            }
            if (document.safety.requiresExplicitNeutralRecovery) {
                append(
                    "\n    /** Explicit one-shot neutral request; success holds neutral until the next target command. */\n" +
                        "    val neutralRecoveryRequestSequence: Long = 0L,",
                )
            }
            if (document.safety.requiresCalibration) {
                append(
                    "\n    /** Explicit calibration confirmation; success holds neutral until the next target command. */\n" +
                        "    val calibrationConfirmationRequestSequence: Long = 0L,",
                )
            }
        }
        return """
            package $pkg

            import com.areslib.state.SubsystemState

            /** Immutable state owned by the ${document.displayName} subsystem. */
            data class ${document.kotlinTypeName}State(
            $fields$separator    /** True only when every required cached control sample is fresh and finite. */
                val feedbackValid: Boolean = false,
                /** Receiver timestamp of the newest complete cached input snapshot. */
                val feedbackTimestampMs: Long = 0L,
                /** True only after every required device configuration has succeeded. */
                val configurationHealthy: Boolean = ${(!document.safety.requiresConfigurationHealth)},
                /** True after the configured homing reference has been established. */
                val homed: Boolean = ${(!document.requiresHoming())},
                /** Explicit operator/autonomous request to run the bounded homing state machine. */
                val homingRequested: Boolean = false,
                /** Latched when homing times out or cannot safely write/reset; cancel before retrying. */
                val homingFaultLatched: Boolean = false,
                /** True after mechanism calibration has been explicitly established. */
                val calibrated: Boolean = ${(!document.safety.requiresCalibration)},
                /** True only when required cached current samples are finite and fresh. */
                val currentReadingValid: Boolean = ${(!document.safety.requiresCurrentMonitoring)},
                /** Latched after a failed output write until an explicit successful neutral recovery. */
                val outputFaultLatched: Boolean = false,$safetyRequests
            ) : SubsystemState
        """.trimIndent() + "\n"
    }

    fun ioSource(document: SubsystemDocument, pkg: String): String {
        val measurements = document.hardware.flatMap { device ->
            device.measurements.mapNotNull { measurement ->
                document.field(measurement.fieldId)?.let { field -> measurement to field }
            }
        }.distinctBy { it.second.fieldId }.map { (measurement, field) ->
            val unit = field.unit?.let { " Unit: $it." }.orEmpty()
            "    /** Cached ${field.displayName} from ${measurement.source.name.lowercase()}.$unit */\n" +
                "    val ${field.fieldId}: ${field.kotlinType()}"
        }
        val commands = document.actuatorLeaders().map { device ->
            val safe = requireNotNull(device.safeOutput)
            "    /** Commands ${device.displayName}; non-finite values fail neutral. Declared neutral: $safe. */\n" +
                "    fun ${device.commandName()}(value: Double)"
        }
        val members = (measurements + commands).joinToString("\n")
        return """
            package $pkg

            import com.areslib.hardware.SubsystemIO

            /**
             * Cached hardware boundary shared by physical and simulated adapters.
             * Getters never perform direct device reads; [refresh] owns one complete input snapshot.
             */
            interface ${document.kotlinTypeName}IO : SubsystemIO, AutoCloseable {
                /** Complete cached snapshot validity; false on any failed or non-finite required read. */
                val feedbackValid: Boolean
                /** Receiver timestamp for the complete cached snapshot, using RobotClock. */
                val feedbackTimestampMs: Long
                /** Required device configuration health. */
                val configurationHealthy: Boolean
                /** Homing-reference validity; always true when homing is not required. */
                val homed: Boolean
                /** True only while every configured cached homing condition is currently satisfied. */
                val homingConditionMet: Boolean
                /** Timeout/write/reset failure latch; a neutral cancel is required before retry. */
                val homingFaultLatched: Boolean
                /** Calibration validity; always true when calibration is not required. */
                val calibrated: Boolean
                /** Cached current validity; always true when current monitoring is not required. */
                val currentReadingValid: Boolean
                /** Failed-write latch. Non-neutral commands are rejected while true. */
                val outputFaultLatched: Boolean
            $members

                /** Applies every declared neutral and clears the fault latch only after complete success. */
                fun recoverWithNeutral(): Boolean
                /** Applies only the bounded descriptor-selected anti-jam output. */
                fun commandAutomaticRecovery(value: Double): Boolean
                /** Latches an exhausted/unsafe recovery and commands neutral. */
                fun latchOutputFault()
                /** Marks an explicitly completed calibration; generated code never infers calibration. */
                fun establishCalibration()
                /** Applies only the bounded generated homing output, bypassing the normal homed permit. */
                fun commandHoming(): Boolean
                /** Neutralizes, establishes the configured zero reference, and marks the mechanism homed. */
                fun establishHome(): Boolean
                /** Latches a failed homing attempt after neutralizing. */
                fun failHoming()
                /** Applies neutral and clears the homing fault so a later explicit request can retry. */
                fun cancelHoming(): Boolean
            }
        """.trimIndent() + "\n"
    }
}
