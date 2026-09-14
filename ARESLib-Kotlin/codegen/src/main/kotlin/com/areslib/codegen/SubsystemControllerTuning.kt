package com.areslib.codegen

import com.areslib.subsystem.*

/** Resolves descriptor tuning declarations to deterministic controller variables and literals. */
internal val PID_STRATEGIES = setOf(
    SubsystemControlStrategy.POSITION_PID,
    SubsystemControlStrategy.PROFILED_POSITION_PID,
    SubsystemControlStrategy.VELOCITY_PID,
)

internal data class GeneratedControllerTuningBinding(
    val parameterUid: String,
    val variableName: String,
    val initialValue: Double,
)

internal fun SubsystemDocument.controllerTuningBindings(): List<GeneratedControllerTuningBinding> {
    val bindings = controlLoops.flatMap { loop ->
        val supportedDefaults = buildMap {
            if (loop.strategy in PID_STRATEGIES) {
                put("kp", loop.kP)
                put("ki", loop.kI)
                put("kd", loop.kD)
            }
            if (loop.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID) {
                put("maxvelocity", loop.motionProfile.maximumVelocity)
                put("maxacceleration", loop.motionProfile.maximumAcceleration)
            }
            if (loop.feedforward.kind != SubsystemFeedforwardKind.NONE) {
                put("ks", loop.feedforward.kS)
                put("kv", loop.feedforward.kV)
                put("ka", loop.feedforward.kA)
                if (loop.feedforward.kind != SubsystemFeedforwardKind.SIMPLE_MOTOR) {
                    put("kg", loop.feedforward.kG)
                }
            }
        }
        tuningParameters.mapNotNull { declaration ->
            if (declaration.componentUid != loop.uid ||
                declaration.type != com.areslib.tuning.TuningParameterType.DOUBLE
            ) return@mapNotNull null
            val suffix = declaration.key.substringAfterLast('.').lowercase()
            val fallback = supportedDefaults[suffix] ?: return@mapNotNull null
            GeneratedControllerTuningBinding(
                parameterUid = declaration.uid,
                variableName = "${loop.loopId}${suffix.replaceFirstChar(Char::uppercaseChar)}",
                initialValue = declaration.defaultValue.doubleValue ?: fallback,
            )
        }
    }.distinctBy { it.parameterUid }.sortedBy { it.parameterUid }

    val duplicateRuntimeBinding = bindings.groupBy(GeneratedControllerTuningBinding::variableName)
        .entries
        .firstOrNull { (_, declarations) -> declarations.size > 1 }
    require(duplicateRuntimeBinding == null) {
        val (variableName, declarations) = requireNotNull(duplicateRuntimeBinding)
        "Subsystem '$documentId' declares multiple tuning parameters for generated controller binding " +
            "'$variableName': ${declarations.joinToString { it.parameterUid }}"
    }
    return bindings
}

internal fun SubsystemDocument.controllerTuningExpression(
    loop: SubsystemControlLoopDocument,
    suffix: String,
    fallback: Double,
): String = controllerTuningBindings()
    .firstOrNull {
        it.variableName == "${loop.loopId}${suffix.replaceFirstChar(Char::uppercaseChar)}"
    }
    ?.variableName
    ?: fallback.kotlinDouble()
