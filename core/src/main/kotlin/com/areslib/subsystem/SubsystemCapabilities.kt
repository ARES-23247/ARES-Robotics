package com.areslib.subsystem

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.catalog.CapabilityContext
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.CapabilityParameterType
import com.areslib.catalog.ResourceClaim
import com.areslib.hardware.actuator.IndicatorLightColor
import com.areslib.hardware.actuator.PrismPwmPreset

/**
 * One automatically exposed generated-subsystem action.
 *
 * [fieldId] names either a writable domain target or the immutable request-sequence field used by
 * a one-shot safety operation. Consumers must branch on [operation] before resolving a DSL field.
 */
data class SubsystemTargetCapability(
    val subsystemId: String,
    val fieldId: String,
    val valueType: SubsystemValueType,
    val operation: SubsystemCapabilityOperation = SubsystemCapabilityOperation.SET_FIELD,
    val descriptor: ActionDescriptor,
)

enum class SubsystemCapabilityOperation {
    SET_FIELD,
    CYCLE_INDICATOR_COLOR_FORWARD,
    CYCLE_INDICATOR_COLOR_BACKWARD,
    SET_HOMING_REQUEST,
    REQUEST_NEUTRAL_RECOVERY,
    CONFIRM_CALIBRATION,
}

/** Stable action key shared by the subsystem builder, controls editor, routines, and codegen. */
fun subsystemTargetActionKey(subsystemId: String, fieldId: String): String =
    "subsystem.$subsystemId.set.$fieldId"

/** Stable action key for advancing one generated indicator-light target to its next named color. */
fun subsystemIndicatorCycleForwardActionKey(subsystemId: String, fieldId: String): String =
    "subsystem.$subsystemId.cycleForward.$fieldId"

/** Stable action key for moving one generated indicator-light target to its previous named color. */
fun subsystemIndicatorCycleBackwardActionKey(subsystemId: String, fieldId: String): String =
    "subsystem.$subsystemId.cycleBackward.$fieldId"

/** Stable action key for the explicit, one-shot neutral recovery handshake. */
fun subsystemNeutralRecoveryActionKey(subsystemId: String): String =
    "subsystem.$subsystemId.recover.neutral"

/** Stable action key for the explicit, one-shot calibration confirmation handshake. */
fun subsystemCalibrationConfirmationActionKey(subsystemId: String): String =
    "subsystem.$subsystemId.confirm.calibration"

/** Derives typed, novice-facing actions without duplicating them in `action-catalog.json`. */
fun subsystemTargetCapabilities(documents: Collection<SubsystemDocument>): List<SubsystemTargetCapability> =
    documents.sortedBy { it.documentId }.flatMap { document ->
        require(validateSubsystemDocument(document).isEmpty()) {
            "Subsystem '${document.documentId}' must be valid before deriving actions"
        }
        if (document.implementation.kind == SubsystemImplementationKind.HAND_AUTHORED) {
            return@flatMap emptyList()
        }
        val targetFields = document.stateFields
            .filter { it.role == SubsystemFieldRole.TARGET }
            .sortedBy { it.fieldId }
        val targets = targetFields.map { field ->
                val key = subsystemTargetActionKey(document.documentId, field.fieldId)
                SubsystemTargetCapability(
                    subsystemId = document.documentId,
                    fieldId = field.fieldId,
                    valueType = field.type,
                    descriptor = ActionDescriptor(
                        key = key,
                        displayName = "Set ${field.displayName}",
                        description = "Sets ${field.displayName.lowercase()} on the ${document.displayName} subsystem.",
                        category = document.displayName,
                        parameters = listOf(field.asCapabilityParameter(document)),
                        resources = listOf(ResourceClaim("subsystem.${document.documentId}")),
                        allowedContexts = CapabilityContext.entries,
                    ),
                )
            }
        val indicatorCycles = targetFields.flatMap { field ->
            if (field.type != SubsystemValueType.DOUBLE ||
                document.actuatorKindForTarget(field.fieldId) != SubsystemHardwareKind.INDICATOR_LIGHT ||
                field.indicatorCycleColors().isEmpty()
            ) return@flatMap emptyList()
            listOf(
                SubsystemTargetCapability(
                    subsystemId = document.documentId,
                    fieldId = field.fieldId,
                    valueType = field.type,
                    operation = SubsystemCapabilityOperation.CYCLE_INDICATOR_COLOR_FORWARD,
                    descriptor = ActionDescriptor(
                        key = subsystemIndicatorCycleForwardActionKey(document.documentId, field.fieldId),
                        displayName = "Cycle ${field.displayName} forward",
                        description = "Advances ${field.displayName.lowercase()} to the next visible named color and wraps from white to red.",
                        category = document.displayName,
                        parameters = emptyList(),
                        resources = listOf(ResourceClaim("subsystem.${document.documentId}")),
                        allowedContexts = CapabilityContext.entries,
                    ),
                ),
                SubsystemTargetCapability(
                    subsystemId = document.documentId,
                    fieldId = field.fieldId,
                    valueType = field.type,
                    operation = SubsystemCapabilityOperation.CYCLE_INDICATOR_COLOR_BACKWARD,
                    descriptor = ActionDescriptor(
                        key = subsystemIndicatorCycleBackwardActionKey(document.documentId, field.fieldId),
                        displayName = "Cycle ${field.displayName} backward",
                        description = "Moves ${field.displayName.lowercase()} to the previous visible named color and wraps from red to white.",
                        category = document.displayName,
                        parameters = emptyList(),
                        resources = listOf(ResourceClaim("subsystem.${document.documentId}")),
                        allowedContexts = CapabilityContext.entries,
                    ),
                ),
            )
        }
        val homing = if (document.safety.homing.method != SubsystemHomingMethod.NONE) {
            val key = subsystemTargetActionKey(document.documentId, "homingRequested")
            listOf(
                SubsystemTargetCapability(
                    subsystemId = document.documentId,
                    fieldId = "homingRequested",
                    valueType = SubsystemValueType.BOOLEAN,
                    operation = SubsystemCapabilityOperation.SET_HOMING_REQUEST,
                    descriptor = ActionDescriptor(
                        key = key,
                        displayName = "Run ${document.displayName} homing",
                        description = "Starts or cancels the bounded ${document.displayName.lowercase()} homing sequence.",
                        category = document.displayName,
                        parameters = listOf(
                            CapabilityParameterDescriptor(
                                key = "value",
                                displayName = "Run homing",
                                description = "True starts homing; false cancels and commands neutral.",
                                type = CapabilityParameterType.BOOLEAN,
                                defaultBoolean = false,
                            )
                        ),
                        resources = listOf(ResourceClaim("subsystem.${document.documentId}")),
                        allowedContexts = CapabilityContext.entries,
                    ),
                )
            )
        } else emptyList()
        val neutralRecovery = if (document.safety.requiresExplicitNeutralRecovery) {
            listOf(
                SubsystemTargetCapability(
                    subsystemId = document.documentId,
                    fieldId = "neutralRecoveryRequestSequence",
                    valueType = SubsystemValueType.BOOLEAN,
                    operation = SubsystemCapabilityOperation.REQUEST_NEUTRAL_RECOVERY,
                    descriptor = ActionDescriptor(
                        key = subsystemNeutralRecoveryActionKey(document.documentId),
                        displayName = "Recover ${document.displayName} with neutral",
                        description = "Applies the declared safe neutral once, clears the output fault only if every write succeeds, and holds neutral until a later target command.",
                        category = document.displayName,
                        parameters = listOf(explicitConfirmationParameter("Recover with neutral")),
                        resources = listOf(ResourceClaim("subsystem.${document.documentId}")),
                        allowedContexts = listOf(CapabilityContext.TELEOP, CapabilityContext.TEST),
                    ),
                )
            )
        } else emptyList()
        val calibrationConfirmation = if (document.safety.requiresCalibration) {
            listOf(
                SubsystemTargetCapability(
                    subsystemId = document.documentId,
                    fieldId = "calibrationConfirmationRequestSequence",
                    valueType = SubsystemValueType.BOOLEAN,
                    operation = SubsystemCapabilityOperation.CONFIRM_CALIBRATION,
                    descriptor = ActionDescriptor(
                        key = subsystemCalibrationConfirmationActionKey(document.documentId),
                        displayName = "Confirm ${document.displayName} calibration",
                        description = "With fresh healthy feedback, applies safe neutral once before accepting calibration and holds neutral until a later target command.",
                        category = document.displayName,
                        parameters = listOf(explicitConfirmationParameter("Calibration is complete")),
                        resources = listOf(ResourceClaim("subsystem.${document.documentId}")),
                        allowedContexts = listOf(CapabilityContext.TELEOP, CapabilityContext.TEST),
                    ),
                )
            )
        } else emptyList()
        targets + indicatorCycles + homing + neutralRecovery + calibrationConfirmation
    }

/**
 * Adds generated subsystem actions to an offline catalog while preserving every hand-authored
 * action. A manual action may share a generated key only when its complete descriptor is equal.
 */
fun mergeSubsystemCapabilities(
    catalog: CapabilityCatalogDocument,
    documents: Collection<SubsystemDocument>,
): CapabilityCatalogDocument {
    val derived = subsystemTargetCapabilities(documents)
    val existing = catalog.actions.associateBy { it.key }
    documents.filter { it.implementation.kind == SubsystemImplementationKind.HAND_AUTHORED }
        .sortedBy { it.documentId }
        .forEach { document ->
            document.capabilityActionKeys.sorted().forEach { actionKey ->
                require(actionKey in existing) {
                    "Hand-authored subsystem '${document.documentId}' references missing catalog action '$actionKey'"
                }
            }
        }
    derived.forEach { capability ->
        val collision = existing[capability.descriptor.key]
        require(collision == null || collision == capability.descriptor) {
            "Action '${capability.descriptor.key}' conflicts with its generated subsystem action"
        }
    }
    return catalog.copy(
        actions = (catalog.actions + derived.map { it.descriptor })
            .distinctBy { it.key }
            .sortedBy { it.key },
    )
}

private fun SubsystemStateFieldDocument.asCapabilityParameter(
    document: SubsystemDocument,
): CapabilityParameterDescriptor {
    val actuatorKind = document.actuatorKindForTarget(fieldId)
    val namedOptions = when (actuatorKind) {
        SubsystemHardwareKind.INDICATOR_LIGHT -> IndicatorLightColor.entries
            .asSequence()
            .filter { it != IndicatorLightColor.RAINBOW }
            .filter { option -> minimum == null || option.position >= minimum }
            .filter { option -> maximum == null || option.position <= maximum }
            .map { it.name }
            .toList()
        SubsystemHardwareKind.PRISM_DRIVER -> PrismPwmPreset.entries
            .asSequence()
            .filter { option -> minimum == null || option.pulseWidthUs >= minimum }
            .filter { option -> maximum == null || option.pulseWidthUs <= maximum }
            .map { it.name }
            .toList()
        else -> emptyList()
    }
    val defaultOption = when (actuatorKind) {
        SubsystemHardwareKind.INDICATOR_LIGHT -> defaultNumber?.let { default ->
            IndicatorLightColor.entries.firstOrNull {
                it != IndicatorLightColor.RAINBOW && kotlin.math.abs(it.position - default) <= 1e-9
            }?.name
        }
        SubsystemHardwareKind.PRISM_DRIVER -> when (type) {
            SubsystemValueType.DOUBLE -> defaultNumber?.let { default ->
                PrismPwmPreset.entries.firstOrNull { kotlin.math.abs(it.pulseWidthUs - default) <= 1e-9 }?.name
            }
            SubsystemValueType.INT -> defaultInt?.let { default ->
                PrismPwmPreset.entries.firstOrNull { it.pulseWidthUs == default }?.name
            }
            else -> null
        }
        else -> null
    }
    if (namedOptions.isNotEmpty()) {
        return CapabilityParameterDescriptor(
            key = "value",
            displayName = displayName,
            description = when (actuatorKind) {
                SubsystemHardwareKind.INDICATOR_LIGHT -> "Choose the named indicator-light color."
                SubsystemHardwareKind.PRISM_DRIVER -> "Choose the named goBILDA Prism pattern or color."
                else -> error("Named options require a supported actuator")
            },
            type = CapabilityParameterType.ENUM,
            defaultText = defaultOption ?: namedOptions.first(),
            options = namedOptions,
        )
    }
    return CapabilityParameterDescriptor(
        key = "value",
        displayName = displayName,
        description = "New $displayName value for the subsystem target.",
        type = when (type) {
            SubsystemValueType.DOUBLE, SubsystemValueType.INT -> CapabilityParameterType.NUMBER
            SubsystemValueType.BOOLEAN -> CapabilityParameterType.BOOLEAN
            SubsystemValueType.STRING -> CapabilityParameterType.TEXT
        },
        unit = unit,
        minimum = minimum,
        maximum = maximum,
        defaultNumber = when (type) {
            SubsystemValueType.DOUBLE -> defaultNumber
            SubsystemValueType.INT -> defaultInt?.toDouble()
            else -> null
        },
        defaultBoolean = defaultBoolean,
        defaultText = defaultText,
    )
}

private fun explicitConfirmationParameter(displayName: String): CapabilityParameterDescriptor =
    CapabilityParameterDescriptor(
        key = "value",
        displayName = displayName,
        description = "Must be explicitly checked for this one-shot safety request.",
        type = CapabilityParameterType.BOOLEAN,
        required = true,
    )

private fun SubsystemDocument.actuatorKindForTarget(fieldId: String): SubsystemHardwareKind? = controlLoops
    .firstOrNull { it.targetFieldId == fieldId }
    ?.let { loop -> hardware.firstOrNull { it.hardwareId == loop.actuatorId } }
    ?.kind

/** OFF remains a separate explicit choice; cycling walks only colors visible to students. */
private fun SubsystemStateFieldDocument.indicatorCycleColors(): List<IndicatorLightColor> =
    IndicatorLightColor.entries
        .asSequence()
        .filter { it != IndicatorLightColor.OFF && it != IndicatorLightColor.RAINBOW }
        .filter { option -> minimum == null || option.position >= minimum }
        .filter { option -> maximum == null || option.position <= maximum }
        .distinctBy { it.position }
        .toList()
