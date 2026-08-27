package com.ares.analytics.viewmodel

import com.areslib.subsystem.SubsystemControlLoopDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFeedforwardKind
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningValue

enum class SubsystemTuningPreset(
    val displayName: String,
    val explanation: String,
) {
    PID_GAINS(
        "PID gains",
        "Declares kP, kI, and kD for a position or velocity feedback controller. Existing controller values become the defaults.",
    ),
    FEEDFORWARD_GAINS(
        "Feedforward gains",
        "Declares only the kS, kV, kA, and applicable kG terms already used by this controller's feedforward model.",
    ),
    MOTION_PROFILE_LIMITS(
        "Motion-profile limits",
        "Declares the maximum velocity and acceleration used by this profiled position controller.",
    ),
}

/** Pure authoring helpers shared by the form and focused tests. */
object SubsystemTuningAuthoring {
    fun newParameter(document: SubsystemDocument): TuningParameterDeclaration {
        val index = generateSequence(1) { it + 1 }.first { candidate ->
            document.tuningParameters.none { it.uid == "${document.uid}.parameter.$candidate" }
        }
        return TuningParameterDeclaration(
            uid = "${document.uid}.parameter.$index",
            key = "subsystem.${document.documentId.toKeySegment()}.parameter$index",
            componentUid = document.controlLoops.firstOrNull()?.uid
                ?: document.hardware.firstOrNull()?.uid
                ?: document.uid,
            displayName = "New tuning parameter",
            description = "Explain what robot behavior this value changes and how to verify it safely.",
            type = TuningParameterType.DOUBLE,
            unit = null,
            minimum = null,
            maximum = null,
            defaultValue = TuningValue(doubleValue = 0.0),
            applyPolicy = TuningApplyPolicy.DISABLED_ONLY,
        )
    }

    fun changeType(
        declaration: TuningParameterDeclaration,
        type: TuningParameterType,
    ): TuningParameterDeclaration = declaration.copy(
        type = type,
        unit = declaration.unit.takeIf { type == TuningParameterType.DOUBLE || type == TuningParameterType.INT },
        minimum = declaration.minimum.takeIf { type == TuningParameterType.DOUBLE || type == TuningParameterType.INT },
        maximum = declaration.maximum.takeIf { type == TuningParameterType.DOUBLE || type == TuningParameterType.INT },
        enumOptions = if (type == TuningParameterType.ENUM) listOf("optionA", "optionB") else emptyList(),
        defaultValue = when (type) {
            TuningParameterType.DOUBLE -> TuningValue(doubleValue = 0.0)
            TuningParameterType.INT -> TuningValue(intValue = 0)
            TuningParameterType.BOOLEAN -> TuningValue(booleanValue = false)
            TuningParameterType.TEXT -> TuningValue(textValue = "")
            TuningParameterType.ENUM -> TuningValue(textValue = "optionA")
        },
    )

    fun parseEnumOptions(raw: String): List<String> = raw.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)

    fun availablePresets(loop: SubsystemControlLoopDocument): List<SubsystemTuningPreset> = buildList {
        if (loop.strategy in setOf(
                SubsystemControlStrategy.POSITION_PID,
                SubsystemControlStrategy.PROFILED_POSITION_PID,
                SubsystemControlStrategy.VELOCITY_PID,
            )
        ) {
            add(SubsystemTuningPreset.PID_GAINS)
        }
        if (loop.feedforward.kind != SubsystemFeedforwardKind.NONE) add(SubsystemTuningPreset.FEEDFORWARD_GAINS)
        if (loop.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID) {
            add(SubsystemTuningPreset.MOTION_PROFILE_LIMITS)
        }
    }

    fun applyPreset(
        document: SubsystemDocument,
        loopUid: String,
        preset: SubsystemTuningPreset,
    ): SubsystemDocument {
        val loop = document.controlLoops.firstOrNull { it.uid == loopUid } ?: return document
        if (preset !in availablePresets(loop)) return document
        val proposed = when (preset) {
            SubsystemTuningPreset.PID_GAINS -> listOf(
                gain(document, loop, "kp", "kP", "Proportional gain", loop.kP, minimum = 0.0, maximum = 100.0),
                gain(document, loop, "ki", "kI", "Integral gain", loop.kI, minimum = 0.0, maximum = 100.0),
                gain(document, loop, "kd", "kD", "Derivative gain", loop.kD, minimum = 0.0, maximum = 100.0),
            )
            SubsystemTuningPreset.FEEDFORWARD_GAINS -> buildList {
                add(gain(document, loop, "ks", "kS", "Static-friction feedforward", loop.feedforward.kS, "V", 0.0, 24.0))
                add(gain(document, loop, "kv", "kV", "Velocity feedforward", loop.feedforward.kV, minimum = 0.0, maximum = 1_000.0))
                add(gain(document, loop, "ka", "kA", "Acceleration feedforward", loop.feedforward.kA, minimum = 0.0, maximum = 1_000.0))
                if (loop.feedforward.kind == SubsystemFeedforwardKind.ELEVATOR || loop.feedforward.kind == SubsystemFeedforwardKind.ARM) {
                    add(gain(document, loop, "kg", "kG", "Gravity feedforward", loop.feedforward.kG, "V", -24.0, 24.0))
                }
            }
            SubsystemTuningPreset.MOTION_PROFILE_LIMITS -> {
                val unit = document.stateFields.firstOrNull { it.fieldId == loop.targetFieldId }?.unit
                val velocityUnit = unit?.let { "$it/s" }
                val accelerationUnit = unit?.let { "$it/s²" }
                listOf(
                    gain(
                        document,
                        loop,
                        "maxvelocity",
                        "max velocity",
                        "Maximum profile velocity",
                        loop.motionProfile.maximumVelocity,
                        velocityUnit,
                        0.000001,
                        maxOf(100.0, loop.motionProfile.maximumVelocity * 10.0),
                    ),
                    gain(
                        document,
                        loop,
                        "maxacceleration",
                        "max acceleration",
                        "Maximum profile acceleration",
                        loop.motionProfile.maximumAcceleration,
                        accelerationUnit,
                        0.000001,
                        maxOf(100.0, loop.motionProfile.maximumAcceleration * 10.0),
                    ),
                )
            }
        }
        val existingUids = document.tuningParameters.mapTo(hashSetOf()) { it.uid }
        val existingKeys = document.tuningParameters.mapTo(hashSetOf()) { it.key }
        return document.copy(
            tuningParameters = document.tuningParameters + proposed.filterNot {
                it.uid in existingUids || it.key in existingKeys
            },
        )
    }

    fun moveByUid(
        parameters: List<TuningParameterDeclaration>,
        uid: String,
        offset: Int,
    ): List<TuningParameterDeclaration> {
        val from = parameters.indexOfFirst { it.uid == uid }
        if (from < 0) return parameters
        val to = (from + offset).coerceIn(parameters.indices)
        if (to == from) return parameters
        return parameters.toMutableList().also {
            val value = it.removeAt(from)
            it.add(to, value)
        }
    }

    private fun gain(
        document: SubsystemDocument,
        loop: SubsystemControlLoopDocument,
        suffix: String,
        symbol: String,
        name: String,
        value: Double,
        unit: String? = null,
        minimum: Double? = null,
        maximum: Double? = null,
    ) = TuningParameterDeclaration(
        uid = "${document.uid}.tuning.${loop.uid.uidSegment()}.$suffix",
        key = "subsystem.${document.documentId.toKeySegment()}.${loop.loopId.toKeySegment()}.$suffix",
        componentUid = loop.uid,
        displayName = "$name ($symbol)",
        description = "$symbol used by the ${loop.displayName} controller. Change while disabled, then verify the response before enabling normal operation.",
        type = TuningParameterType.DOUBLE,
        unit = unit,
        minimum = minimum,
        maximum = maximum,
        defaultValue = TuningValue(doubleValue = value),
        applyPolicy = TuningApplyPolicy.DISABLED_ONLY,
    )
}

private fun String.toKeySegment(): String = split(Regex("[^A-Za-z0-9]+"))
    .filter(String::isNotBlank)
    .mapIndexed { index, part ->
        val lower = part.lowercase()
        if (index == 0) lower else lower.replaceFirstChar(Char::uppercase)
    }
    .joinToString("")
    .ifBlank { "component" }
    .let { if (it.first().isLetter()) it else "value$it" }

private fun String.uidSegment(): String = lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')
    .ifBlank { "control" }
