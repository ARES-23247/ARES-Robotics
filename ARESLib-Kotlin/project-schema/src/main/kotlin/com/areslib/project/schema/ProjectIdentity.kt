package com.areslib.project.schema

import com.areslib.project.AresLeague

private val STABLE_PROJECT_ID = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
private val STABLE_DOCUMENT_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
private val STABLE_ACTION_KEY = Regex("[A-Za-z][A-Za-z0-9._-]{0,159}")

/** Stable identity for one robot project, independent of its display name and filesystem path. */
@JvmInline
value class ProjectId(val value: String) {
    init {
        require(STABLE_PROJECT_ID.matches(value)) { "Project ID '$value' is not stable" }
    }

    override fun toString(): String = value
}

/** Stable identity for a canonical document inside one project. */
@JvmInline
value class ProjectDocumentId(val value: String) {
    init {
        require(STABLE_DOCUMENT_ID.matches(value)) { "Invalid project document ID '$value'" }
    }

    override fun toString(): String = value
}

/** Stable action key shared by controls, routines, generated registries, and Studio. */
@JvmInline
value class ProjectActionKey(val value: String) {
    init {
        require(STABLE_ACTION_KEY.matches(value)) { "Action key '$value' is not stable" }
    }

    override fun toString(): String = value
}

/** Canonical authoring document categories understood by the project assembler. */
enum class ProjectDocumentKind {
    PROJECT_METADATA,
    CAPABILITY_CATALOG,
    AUTONOMOUS_CATALOG,
    ROUTINE,
    CONTROL_SCHEME,
    CONTROLLER_PROFILE,
    SUBSYSTEM,
    SUPERSTRUCTURE,
    DRIVETRAIN,
    FIELD,
    TUNING_COMPONENT,
    TUNING_PROFILE,
}

/** Physical controller family selected by the effective project. */
enum class AresControllerTarget {
    FTC_CONTROL_HUB,
    FRC_ROBORIO,
    XRP_PICO,
}

/** League-specific simulator selected by the effective project. */
enum class AresSimulatorTarget {
    FTC,
    FRC,
    XRP,
}

/** Current target pair. Future controller families extend this without merging league runtimes. */
data class AresProjectTarget(
    val controller: AresControllerTarget,
    val simulator: AresSimulatorTarget,
)

/** Safe current default. New controller families require an explicit contract change, never inference. */
fun AresLeague.defaultProjectTarget(): AresProjectTarget = when (this) {
    AresLeague.FTC -> AresProjectTarget(AresControllerTarget.FTC_CONTROL_HUB, AresSimulatorTarget.FTC)
    AresLeague.FRC -> AresProjectTarget(AresControllerTarget.FRC_ROBORIO, AresSimulatorTarget.FRC)
    AresLeague.XRP -> AresProjectTarget(AresControllerTarget.XRP_PICO, AresSimulatorTarget.XRP)
}
