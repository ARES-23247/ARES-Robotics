package com.areslib.codegen

import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControllerProfileCodec
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.routine.RoutineDocument
import java.security.MessageDigest

/** Hashes every canonical input that can change generated project behavior. */
internal fun kotlinProjectContentHash(
    request: KotlinProjectCodegenRequest,
    routines: List<RoutineDocument>,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun record(label: String, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(label.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0.toByte())
        digest.update(bytes)
    }
    record("generator", ARES_KOTLIN_CODEGEN_VERSION.toString())
    record("subsystem-registry", request.subsystemRegistryFqn.orEmpty())
    request.generatedActionRegistryBindings.toSortedMap().forEach { (key, registry) ->
        record("generated-action:$key", registry)
    }
    request.projectMetadata?.let { record("project-metadata", AresProjectMetadataCodec.encode(it)) }
    record("catalog", CapabilityCatalogCodec.encode(request.catalog))
    routines.forEach { record("routine:${it.documentId}", AresRoutineCodec.encode(it)) }
    request.autonomousCatalog?.let { record("autonomous-catalog", AutonomousCatalogCodec.encode(it)) }
    request.controllerProfiles.sortedBy { it.documentId }.forEach {
        record("controller-profile:${it.documentId}", ControllerProfileCodec.encode(it))
    }
    record("controller-input-platform", request.targetInputPlatform?.name ?: "none")
    request.controlSchemes.sortedBy { it.documentId }.forEach {
        record("control-scheme:${it.documentId}", ControlSchemeCodec.encode(it))
    }
    return digest.digest().toHex()
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}
