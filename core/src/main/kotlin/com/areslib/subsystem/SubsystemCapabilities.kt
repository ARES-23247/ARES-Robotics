package com.areslib.subsystem

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.catalog.CapabilityContext
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.CapabilityParameterType
import com.areslib.catalog.ResourceClaim

/** One automatically exposed action for a writable generated subsystem target. */
data class SubsystemTargetCapability(
    val subsystemId: String,
    val fieldId: String,
    val valueType: SubsystemValueType,
    val operation: SubsystemCapabilityOperation = SubsystemCapabilityOperation.SET_FIELD,
    val descriptor: ActionDescriptor,
)

enum class SubsystemCapabilityOperation { SET_FIELD, SET_HOMING_REQUEST }

/** Stable action key shared by the subsystem builder, controls editor, routines, and codegen. */
fun subsystemTargetActionKey(subsystemId: String, fieldId: String): String =
    "subsystem.$subsystemId.set.$fieldId"

/** Derives typed, novice-facing actions without duplicating them in `action-catalog.json`. */
fun subsystemTargetCapabilities(documents: Collection<SubsystemDocument>): List<SubsystemTargetCapability> =
    documents.sortedBy { it.documentId }.flatMap { document ->
        require(validateSubsystemDocument(document).isEmpty()) {
            "Subsystem '${document.documentId}' must be valid before deriving actions"
        }
        if (document.implementation.kind == SubsystemImplementationKind.HAND_AUTHORED) {
            return@flatMap emptyList()
        }
        val targets = document.stateFields
            .filter { it.role == SubsystemFieldRole.TARGET }
            .sortedBy { it.fieldId }
            .map { field ->
                val key = subsystemTargetActionKey(document.documentId, field.fieldId)
                SubsystemTargetCapability(
                    subsystemId = document.documentId,
                    fieldId = field.fieldId,
                    valueType = field.type,
                    descriptor = ActionDescriptor(
                        key = key,
                        displayName = "Set ${document.displayName} ${field.displayName}",
                        description = "Sets ${field.displayName.lowercase()} on the ${document.displayName} subsystem.",
                        category = document.displayName,
                        parameters = listOf(field.asCapabilityParameter()),
                        resources = listOf(ResourceClaim("subsystem.${document.documentId}")),
                        allowedContexts = CapabilityContext.entries,
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
        targets + homing
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

private fun SubsystemStateFieldDocument.asCapabilityParameter(): CapabilityParameterDescriptor =
    CapabilityParameterDescriptor(
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
