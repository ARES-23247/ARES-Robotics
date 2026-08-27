package com.areslib.project.model

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControllerProfileDocument
import com.areslib.drivetrain.DrivetrainDocument
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.schema.ProjectActionKey
import com.areslib.project.schema.ProjectDocumentId
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.RoutineDocument
import com.areslib.simulation.SimulationProjectPlan
import com.areslib.state.RobotFieldConfig
import com.areslib.subsystem.SubsystemDocument
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.tuning.TuningComponentDocument
import com.areslib.tuning.TuningProfileDocument

/**
 * Stable read API for Studio and other project consumers.
 *
 * Features query the validated effective indexes instead of rebuilding catalogs or depending on
 * authoring repository order. Raw documents that do not yet have a derived index remain explicitly
 * named canonical inputs here, keeping filesystem and merge semantics out of feature code.
 */
class EffectiveRobotProjectQueries(private val project: EffectiveRobotProject) {
    val isValid: Boolean get() = project.isValid
    val metadata: AresProjectMetadataDocument? get() = project.raw.metadata
    val capabilityCatalog: CapabilityCatalogDocument? get() = project.capabilityCatalog
    val autonomousCatalog: AutonomousCatalogDocument? get() = project.raw.autonomousCatalog
    val field: RobotFieldConfig? get() = project.raw.field
    val simulationPlan: SimulationProjectPlan? get() = project.simulationPlan
    val tuningComponents: List<TuningComponentDocument> get() = project.raw.tuningComponents.sortedBy { it.uid }
    val tuningProfiles: List<TuningProfileDocument> get() = project.raw.tuningProfiles.sortedBy { it.uid }

    val actions: List<ActionDescriptor> get() = project.actions.entries.sortedBy { it.key.value }.map { it.value }
    val routines: List<RoutineDocument> get() = project.routines.entries.sortedBy { it.key.value }.map { it.value }
    val controlSchemes: List<ControlSchemeDocument> get() =
        project.controlSchemes.entries.sortedBy { it.key.value }.map { it.value }
    val controllerProfiles: List<ControllerProfileDocument> get() =
        project.controllerProfiles.entries.sortedBy { it.key.value }.map { it.value }
    val subsystems: List<SubsystemDocument> get() = project.subsystems.entries.sortedBy { it.key.value }.map { it.value }
    val superstructures: List<SuperstructureDocument> get() =
        project.superstructures.entries.sortedBy { it.key.value }.map { it.value }
    val drivetrains: List<DrivetrainDocument> get() = project.drivetrains.entries.sortedBy { it.key.value }.map { it.value }

    fun action(key: String): ActionDescriptor? = runCatching { ProjectActionKey(key) }.getOrNull()?.let(project.actions::get)
    fun routine(id: String): RoutineDocument? = document(id, project.routines)
    fun controlScheme(id: String): ControlSchemeDocument? = document(id, project.controlSchemes)
    fun controllerProfile(id: String): ControllerProfileDocument? = document(id, project.controllerProfiles)
    fun subsystem(id: String): SubsystemDocument? = document(id, project.subsystems)
    fun superstructure(id: String): SuperstructureDocument? = document(id, project.superstructures)
    fun drivetrain(id: String): DrivetrainDocument? = document(id, project.drivetrains)

    private fun <T> document(id: String, index: Map<ProjectDocumentId, T>): T? =
        runCatching { ProjectDocumentId(id) }.getOrNull()?.let(index::get)
}

fun EffectiveRobotProject.queries(): EffectiveRobotProjectQueries = EffectiveRobotProjectQueries(this)
