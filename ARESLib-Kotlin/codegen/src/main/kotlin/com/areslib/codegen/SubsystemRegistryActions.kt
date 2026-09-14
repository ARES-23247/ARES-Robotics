package com.areslib.codegen

import com.areslib.subsystem.*

/** Typed registry actions preserve accepted values and one-shot safety request sequences. */
internal fun registryActionCase(
    document: SubsystemDocument,
    field: SubsystemStateFieldDocument,
    resourceExpression: String,
): String {
    val key = subsystemTargetActionKey(document.documentId, field.fieldId)
    val actuatorKind = document.controlLoops
        .firstOrNull { it.targetFieldId == field.fieldId }
        ?.let { loop -> document.hardware.firstOrNull { it.hardwareId == loop.actuatorId } }
        ?.kind
    val fieldMinimum = field.minimum
    val fieldMaximum = field.maximum
    val numericBounds = buildList {
        field.minimum?.let { add("candidate >= ${it.kotlinDouble()}") }
        field.maximum?.let { add("candidate <= ${it.kotlinDouble()}") }
    }
    val namedConversion = when (actuatorKind) {
        SubsystemHardwareKind.INDICATOR_LIGHT -> com.areslib.hardware.actuator.IndicatorLightColor.entries
            .filter { it != com.areslib.hardware.actuator.IndicatorLightColor.RAINBOW }
            .filter { option -> fieldMinimum == null || option.position >= fieldMinimum }
            .filter { option -> fieldMaximum == null || option.position <= fieldMaximum }
            .joinToString(
                prefix = "when (value as? String) {\n",
                postfix = "\n        else -> null\n    }",
                separator = "\n",
            ) { option -> "        ${option.name.quoted()} -> ${option.position.kotlinDouble()}" }
        SubsystemHardwareKind.PRISM_DRIVER -> com.areslib.hardware.actuator.PrismPwmPreset.entries
            .filter { option -> fieldMinimum == null || option.pulseWidthUs >= fieldMinimum }
            .filter { option -> fieldMaximum == null || option.pulseWidthUs <= fieldMaximum }
            .joinToString(
                prefix = "when (value as? String) {\n",
                postfix = "\n        else -> null\n    }",
                separator = "\n",
            ) { option ->
                val value = if (field.type == SubsystemValueType.INT) option.pulseWidthUs.toString()
                else option.pulseWidthUs.toDouble().kotlinDouble()
                "        ${option.name.quoted()} -> $value"
            }
        else -> null
    }
    val converted = namedConversion ?: when (field.type) {
        SubsystemValueType.DOUBLE -> {
            val checks = (listOf("candidate.isFinite()") + numericBounds).joinToString(" && ")
            "(value as? Number)?.toDouble()?.takeIf { candidate -> $checks }"
        }
        SubsystemValueType.INT -> {
            val checks = (listOf(
                "candidate.isFinite()",
                "candidate >= Int.MIN_VALUE.toDouble()",
                "candidate <= Int.MAX_VALUE.toDouble()",
                "candidate % 1.0 == 0.0",
            ) + numericBounds).joinToString(" && ")
            "(value as? Number)?.toDouble()?.takeIf { candidate -> $checks }?.toInt()"
        }
        SubsystemValueType.BOOLEAN -> "value as? Boolean"
        SubsystemValueType.STRING -> "value as? String"
    }
    val commandSequence = if (document.hasSafetyRequestHandshake()) {
        """
            val nextCommandSequence = if (current.commandSequence == Long.MAX_VALUE) 1L else current.commandSequence + 1L
            RobotAction.UpdateNamedSubsystemState(
                ${document.kotlinTypeName}Subsystem.ID,
                current.copy(${field.fieldId} = typedValue, commandSequence = nextCommandSequence),
            )
        """.trimIndent()
    } else {
        """
            RobotAction.UpdateNamedSubsystemState(
                ${document.kotlinTypeName}Subsystem.ID,
                current.copy(${field.fieldId} = typedValue),
            )
        """.trimIndent()
    }
    return """    ${key.quoted()} -> $converted?.let { typedValue ->
        StateActionTask(${("Set ${field.displayName}").quoted()}, $resourceExpression) { robotState ->
            val current = ${document.kotlinTypeName}Subsystem.state(robotState)
            $commandSequence
        }
    }"""
}

internal fun registryIndicatorCycleActionCase(
    document: SubsystemDocument,
    field: SubsystemStateFieldDocument,
    resourceExpression: String,
    forward: Boolean,
): String {
    require(field.type == SubsystemValueType.DOUBLE) {
        "Indicator-light cycle target '${document.documentId}.${field.fieldId}' must be a Double"
    }
    val fieldMinimum = field.minimum
    val fieldMaximum = field.maximum
    val positions = com.areslib.hardware.actuator.IndicatorLightColor.entries
        .asSequence()
        .filter {
            it != com.areslib.hardware.actuator.IndicatorLightColor.OFF &&
                it != com.areslib.hardware.actuator.IndicatorLightColor.RAINBOW
        }
        .filter { option -> fieldMinimum == null || option.position >= fieldMinimum }
        .filter { option -> fieldMaximum == null || option.position <= fieldMaximum }
        .distinctBy { it.position }
        .map { it.position }
        .toList()
    require(positions.isNotEmpty()) {
        "Indicator-light cycle target '${document.documentId}.${field.fieldId}' has no visible colors inside its bounds"
    }
    val traversal = if (forward) positions else positions.asReversed()
    val wrap = if (forward) positions.first() else positions.last()
    val comparison = if (forward) "<" else ">"
    val nextValue = traversal.joinToString(
        prefix = "when {\n",
        postfix = "\n                else -> ${wrap.kotlinDouble()}\n            }",
        separator = "\n",
    ) { position ->
        "                current.${field.fieldId} $comparison ${position.kotlinDouble()} -> ${position.kotlinDouble()}"
    }
    val stateUpdate = if (document.hasSafetyRequestHandshake()) {
        """val nextCommandSequence = if (current.commandSequence == Long.MAX_VALUE) 1L else current.commandSequence + 1L
            RobotAction.UpdateNamedSubsystemState(
                ${document.kotlinTypeName}Subsystem.ID,
                current.copy(${field.fieldId} = nextValue, commandSequence = nextCommandSequence),
            )"""
    } else {
        """RobotAction.UpdateNamedSubsystemState(
                ${document.kotlinTypeName}Subsystem.ID,
                current.copy(${field.fieldId} = nextValue),
            )"""
    }
    val key = if (forward) {
        com.areslib.subsystem.subsystemIndicatorCycleForwardActionKey(document.documentId, field.fieldId)
    } else {
        com.areslib.subsystem.subsystemIndicatorCycleBackwardActionKey(document.documentId, field.fieldId)
    }
    val direction = if (forward) "forward" else "backward"
    return """    ${key.quoted()} -> StateActionTask(${("Cycle ${field.displayName} $direction").quoted()}, $resourceExpression) { robotState ->
            val current = ${document.kotlinTypeName}Subsystem.state(robotState)
            val nextValue = $nextValue
            $stateUpdate
        }"""
}

internal fun registryHomingActionCase(document: SubsystemDocument, resourceExpression: String): String {
    val key = subsystemTargetActionKey(document.documentId, "homingRequested")
    return """    ${key.quoted()} -> (value as? Boolean)?.let { requested ->
        StateActionTask(${("Run ${document.displayName} homing").quoted()}, $resourceExpression) { robotState ->
            val current = ${document.kotlinTypeName}Subsystem.state(robotState)
            RobotAction.UpdateNamedSubsystemState(
                ${document.kotlinTypeName}Subsystem.ID,
                current.copy(homingRequested = requested),
            )
        }
    }"""
}

internal fun registryNeutralRecoveryActionCase(document: SubsystemDocument, resourceExpression: String): String =
    registryOneShotSafetyActionCase(
        key = subsystemNeutralRecoveryActionKey(document.documentId),
        taskName = "Recover ${document.displayName} with neutral",
        document = document,
        sequenceField = "neutralRecoveryRequestSequence",
        resourceExpression = resourceExpression,
    )

internal fun registryCalibrationConfirmationActionCase(document: SubsystemDocument, resourceExpression: String): String =
    registryOneShotSafetyActionCase(
        key = subsystemCalibrationConfirmationActionKey(document.documentId),
        taskName = "Confirm ${document.displayName} calibration",
        document = document,
        sequenceField = "calibrationConfirmationRequestSequence",
        resourceExpression = resourceExpression,
    )

private fun registryOneShotSafetyActionCase(
    key: String,
    taskName: String,
    document: SubsystemDocument,
    sequenceField: String,
    resourceExpression: String,
): String = """    ${key.quoted()} -> (value as? Boolean)?.takeIf { it }?.let {
        StateActionTask(${taskName.quoted()}, $resourceExpression) { robotState ->
            val current = ${document.kotlinTypeName}Subsystem.state(robotState)
            val nextSequence = if (current.$sequenceField == Long.MAX_VALUE) 1L else current.$sequenceField + 1L
            RobotAction.UpdateNamedSubsystemState(
                ${document.kotlinTypeName}Subsystem.ID,
                current.copy($sequenceField = nextSequence),
            )
        }
    }"""
