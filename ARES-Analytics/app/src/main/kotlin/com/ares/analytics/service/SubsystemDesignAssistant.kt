package com.ares.analytics.service

import com.ares.analytics.shared.AppJson
import com.areslib.subsystem.SubsystemControlLoopDocument
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemHardwareDocument
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A reviewed form proposal. It is never written to source or a project by the assistant. */
data class SubsystemDesignProposal(
    val summary: String,
    val explanations: List<String>,
    val candidate: SubsystemDocument,
)

/** Injectable boundary used by the Subsystem Builder and deterministic UI tests. */
fun interface SubsystemDesignAssistant {
    suspend fun propose(current: SubsystemDocument, studentRequest: String): SubsystemDesignProposal
}

/** Strictly parses Gemini's review envelope before the candidate reaches the editor. */
internal fun parseSubsystemDesignProposalResponse(
    current: SubsystemDocument,
    responseText: String,
    gson: Gson = GsonBuilder().create(),
): SubsystemDesignProposal {
    val sanitizedResponse = responseText
        .replace(Regex("```(?:json)?\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL), "$1")
        .trim()
    val envelope = AppJson.parseToJsonElement(sanitizedResponse).jsonObject
    val summary = envelope["summary"]?.jsonPrimitive?.content?.trim().orEmpty()
        .ifBlank { "Gemini proposed subsystem form changes." }
    val explanations = envelope["explanations"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
        .orEmpty()
        .take(12)
    val proposedElement = requireNotNull(envelope["proposedDocument"]) {
        "Gemini response did not contain proposedDocument."
    }
    val proposedJson = if (proposedElement is JsonPrimitive && proposedElement.isString) {
        proposedElement.content
    } else {
        proposedElement.toString()
    }
    val proposed = requireNotNull(gson.fromJson(proposedJson, SubsystemDocument::class.java)) {
        "Gemini returned an empty subsystem descriptor."
    }
    return SubsystemDesignProposal(
        summary = summary,
        explanations = explanations,
        candidate = sanitizeSubsystemDesignCandidate(current, proposed),
    )
}

/**
 * Preserves repository ownership and stable editor identity around an untrusted AI candidate.
 *
 * Gemini may fill the form, add devices, or suggest safer policies. It may not change the selected
 * platform, revision lineage, source ownership, hand-authored class names, or catalog action keys.
 */
internal fun sanitizeSubsystemDesignCandidate(
    current: SubsystemDocument,
    proposed: SubsystemDocument,
): SubsystemDocument {
    fun hardwareUid(device: SubsystemHardwareDocument, index: Int): String =
        current.hardware.firstOrNull { it.uid == device.uid || it.hardwareId == device.hardwareId }?.uid
            ?: "ai-hardware-${index + 1}-${device.hardwareId}".take(64)

    fun fieldUid(field: SubsystemStateFieldDocument, index: Int): String =
        current.stateFields.firstOrNull { it.uid == field.uid || it.fieldId == field.fieldId }?.uid
            ?: "ai-state-${index + 1}-${field.fieldId}".take(64)

    fun loopUid(loop: SubsystemControlLoopDocument, index: Int): String =
        current.controlLoops.firstOrNull { it.uid == loop.uid || it.loopId == loop.loopId }?.uid
            ?: "ai-control-${index + 1}-${loop.loopId}".take(64)

    val sanitizedHardware = proposed.hardware.mapIndexed { index, device -> device.copy(uid = hardwareUid(device, index)) }
    val sanitizedFields = proposed.stateFields.mapIndexed { index, field -> field.copy(uid = fieldUid(field, index)) }
    val sanitizedLoops = proposed.controlLoops.mapIndexed { index, loop -> loop.copy(uid = loopUid(loop, index)) }
    val permittedOwners = sanitizedHardware.mapTo(hashSetOf()) { it.uid }
        .apply { addAll(sanitizedLoops.map { it.uid }); add(current.uid) }
    val sanitizedParameters = proposed.tuningParameters.mapIndexed { index, parameter ->
        val existing = current.tuningParameters.firstOrNull { it.uid == parameter.uid || it.key == parameter.key }
            ?: current.tuningParameters.getOrNull(index).takeIf {
                proposed.tuningParameters.size == current.tuningParameters.size
            }
        when {
            existing != null -> parameter.copy(
                uid = existing.uid,
                key = existing.key,
                componentUid = existing.componentUid,
            )
            parameter.componentUid in permittedOwners -> parameter
            else -> parameter.copy(componentUid = current.uid)
        }
    }

    return proposed.copy(
        schemaVersion = current.schemaVersion,
        documentId = current.documentId,
        uid = current.uid,
        platform = current.platform,
        revision = current.revision,
        parentContentHash = current.parentContentHash,
        implementation = current.implementation,
        capabilityActionKeys = current.capabilityActionKeys,
        hardware = sanitizedHardware,
        stateFields = sanitizedFields,
        controlLoops = sanitizedLoops,
        tuningParameters = sanitizedParameters,
    )
}
