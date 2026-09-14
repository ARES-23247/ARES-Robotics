package com.areslib.subsystem

import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningValue

/** Typed tuning declarations shared by capability template factories. */
internal object SubsystemTemplateTuning {
    /**
     * Gives every applicable capability template a complete, typed starting tuning surface.
     *
     * These declarations are consumed by generated controllers; they are not decorative metadata.
     * Values remain disabled-only until a reviewed tuning profile applies them, so a novice can see
     * what may be tuned without accidentally changing an enabled mechanism.
     */
    fun SubsystemDocument.withRecommendedTuningParameters(): SubsystemDocument {
        if (tuningParameters.isNotEmpty()) return this
        val documentKey = documentId.toTuningKeySegment()
        val parameters = buildList {
            controlLoops.forEach { loop ->
                val loopKey = loop.loopId.toTuningKeySegment()
                val targetUnit = stateFields.firstOrNull { it.fieldId == loop.targetFieldId }?.unit

                fun addDouble(
                    suffix: String,
                    displayName: String,
                    description: String,
                    value: Double,
                    unit: String? = null,
                    minimum: Double? = null,
                    maximum: Double? = null,
                ) {
                    add(
                        TuningParameterDeclaration(
                            uid = "${uid}.tuning.${loop.uid.toTuningUidSegment()}.$suffix",
                            key = "subsystem.$documentKey.$loopKey.$suffix",
                            componentUid = loop.uid,
                            displayName = displayName,
                            description = "$description Changes apply only while disabled; verify the response in simulation before physical use.",
                            type = TuningParameterType.DOUBLE,
                            unit = unit,
                            minimum = minimum,
                            maximum = maximum,
                            defaultValue = TuningValue(doubleValue = value),
                            applyPolicy = TuningApplyPolicy.DISABLED_ONLY,
                        )
                    )
                }

                if (loop.strategy in setOf(
                        SubsystemControlStrategy.POSITION_PID,
                        SubsystemControlStrategy.PROFILED_POSITION_PID,
                        SubsystemControlStrategy.VELOCITY_PID,
                    )
                ) {
                    addDouble("kp", "Proportional gain (kP)", "Corrects present controller error.", loop.kP, minimum = 0.0, maximum = 100.0)
                    addDouble("ki", "Integral gain (kI)", "Corrects error that persists over time.", loop.kI, minimum = 0.0, maximum = 100.0)
                    addDouble("kd", "Derivative gain (kD)", "Damps rapid changes in controller error.", loop.kD, minimum = 0.0, maximum = 100.0)
                }

                if (loop.feedforward.kind != SubsystemFeedforwardKind.NONE) {
                    addDouble("ks", "Static feedforward (kS)", "Offsets static friction before motion begins.", loop.feedforward.kS, "V", 0.0, 24.0)
                    addDouble("kv", "Velocity feedforward (kV)", "Predicts output needed for the requested velocity.", loop.feedforward.kV, minimum = 0.0, maximum = 1_000.0)
                    addDouble("ka", "Acceleration feedforward (kA)", "Predicts output needed for the requested acceleration.", loop.feedforward.kA, minimum = 0.0, maximum = 1_000.0)
                    if (loop.feedforward.kind != SubsystemFeedforwardKind.SIMPLE_MOTOR) {
                        addDouble("kg", "Gravity feedforward (kG)", "Offsets gravity for the declared mechanism model.", loop.feedforward.kG, "V", -24.0, 24.0)
                    }
                }

                if (loop.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID) {
                    addDouble(
                        "maxvelocity",
                        "Maximum profile velocity",
                        "Limits how quickly the motion profile may move toward its goal.",
                        loop.motionProfile.maximumVelocity,
                        targetUnit?.let { "$it/s" },
                        0.000001,
                        maxOf(100.0, loop.motionProfile.maximumVelocity * 10.0),
                    )
                    addDouble(
                        "maxacceleration",
                        "Maximum profile acceleration",
                        "Limits how quickly the motion profile may change velocity.",
                        loop.motionProfile.maximumAcceleration,
                        targetUnit?.let { "$it/s²" },
                        0.000001,
                        maxOf(100.0, loop.motionProfile.maximumAcceleration * 10.0),
                    )
                }
            }
        }
        return copy(tuningParameters = parameters)
    }

    private fun String.toTuningKeySegment(): String = split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotBlank)
        .mapIndexed { index, part ->
            val lower = part.lowercase()
            if (index == 0) lower else lower.replaceFirstChar(Char::uppercase)
        }
        .joinToString("")
        .ifBlank { "component" }
        .let { if (it.first().isLetter()) it else "value$it" }

    private fun String.toTuningUidSegment(): String = lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "control" }
}
