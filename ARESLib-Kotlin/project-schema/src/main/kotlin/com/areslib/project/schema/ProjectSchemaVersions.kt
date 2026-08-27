package com.areslib.project.schema

import com.areslib.catalog.ARES_CAPABILITY_CATALOG_SCHEMA_VERSION
import com.areslib.controls.ARES_CONTROLLER_PROFILE_SCHEMA_VERSION
import com.areslib.controls.ARES_CONTROL_SCHEME_SCHEMA_VERSION
import com.areslib.drivetrain.ARES_DRIVETRAIN_SCHEMA_VERSION
import com.areslib.project.ARES_PROJECT_METADATA_SCHEMA_VERSION
import com.areslib.routine.ARES_AUTONOMOUS_CATALOG_SCHEMA_VERSION
import com.areslib.routine.ARES_ROUTINE_SCHEMA_VERSION
import com.areslib.subsystem.ARES_SUBSYSTEM_SCHEMA_VERSION
import com.areslib.superstructure.ARES_SUPERSTRUCTURE_SCHEMA_VERSION
import com.areslib.tuning.ARES_TUNING_COMPONENT_SCHEMA_VERSION
import com.areslib.tuning.ARES_TUNING_PROFILE_SCHEMA_VERSION

/** Result of checking authored bytes before a document codec is allowed to decode them. */
enum class ProjectSchemaDisposition {
    CURRENT,
    UNSUPPORTED,
}

/** Explicit version contract. Every non-current version fails closed. */
data class ProjectSchemaVersionPolicy(
    val currentVersion: Int,
) {
    init { require(currentVersion > 0) { "Current schema version must be positive" } }

    fun disposition(version: Int): ProjectSchemaDisposition = when (version) {
        currentVersion -> ProjectSchemaDisposition.CURRENT
        else -> ProjectSchemaDisposition.UNSUPPORTED
    }
}

/**
 * One registry for the version policy of every JSON document physically owned by project-schema.
 *
 * The field document currently remains runtime-owned by core and therefore has no entry here.
 */
object ProjectSchemaVersions {
    private val policies = mapOf(
        ProjectDocumentKind.PROJECT_METADATA to ProjectSchemaVersionPolicy(ARES_PROJECT_METADATA_SCHEMA_VERSION),
        ProjectDocumentKind.CAPABILITY_CATALOG to ProjectSchemaVersionPolicy(ARES_CAPABILITY_CATALOG_SCHEMA_VERSION),
        ProjectDocumentKind.AUTONOMOUS_CATALOG to ProjectSchemaVersionPolicy(ARES_AUTONOMOUS_CATALOG_SCHEMA_VERSION),
        ProjectDocumentKind.ROUTINE to ProjectSchemaVersionPolicy(ARES_ROUTINE_SCHEMA_VERSION),
        ProjectDocumentKind.CONTROL_SCHEME to ProjectSchemaVersionPolicy(ARES_CONTROL_SCHEME_SCHEMA_VERSION),
        ProjectDocumentKind.CONTROLLER_PROFILE to ProjectSchemaVersionPolicy(ARES_CONTROLLER_PROFILE_SCHEMA_VERSION),
        ProjectDocumentKind.SUBSYSTEM to ProjectSchemaVersionPolicy(ARES_SUBSYSTEM_SCHEMA_VERSION),
        ProjectDocumentKind.SUPERSTRUCTURE to ProjectSchemaVersionPolicy(ARES_SUPERSTRUCTURE_SCHEMA_VERSION),
        ProjectDocumentKind.DRIVETRAIN to ProjectSchemaVersionPolicy(ARES_DRIVETRAIN_SCHEMA_VERSION),
        ProjectDocumentKind.TUNING_COMPONENT to ProjectSchemaVersionPolicy(ARES_TUNING_COMPONENT_SCHEMA_VERSION),
        ProjectDocumentKind.TUNING_PROFILE to ProjectSchemaVersionPolicy(ARES_TUNING_PROFILE_SCHEMA_VERSION),
    )

    @JvmStatic
    fun policy(kind: ProjectDocumentKind): ProjectSchemaVersionPolicy? = policies[kind]

    @JvmStatic
    fun disposition(kind: ProjectDocumentKind, version: Int): ProjectSchemaDisposition =
        requireNotNull(policy(kind)) { "$kind is not owned by project-schema" }.disposition(version)
}
