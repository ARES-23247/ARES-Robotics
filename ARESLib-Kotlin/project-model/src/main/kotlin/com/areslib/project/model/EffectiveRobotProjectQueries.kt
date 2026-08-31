package com.areslib.project.model

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControllerProfileDocument
import com.areslib.drivetrain.DrivetrainDocument
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.schema.AresProjectTarget
import com.areslib.project.schema.ProjectActionKey
import com.areslib.project.schema.ProjectDocumentId
import com.areslib.project.schema.ProjectId
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.RoutineDocument
import com.areslib.simulation.SimulationProjectPlan
import com.areslib.state.RobotFieldConfig
import com.areslib.subsystem.SubsystemDocument
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.tuning.TuningComponentDocument
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningProfileDocument

/**
 * Stable read API for Studio and other project consumers.
 *
 * Features query the validated effective indexes instead of rebuilding catalogs or depending on
 * authoring repository order. Raw documents that do not yet have a derived index remain explicitly
 * named canonical inputs here, keeping filesystem and merge semantics out of feature code.
 */
public class EffectiveRobotProjectQueries(private val project: EffectiveRobotProject) {
    public val projectRoot: String get() = project.raw.projectRoot
    public val isValid: Boolean get() = project.isValid
    public val projectId: ProjectId? get() = project.projectId
    public val target: AresProjectTarget? get() = project.target
    public val issues: List<ProjectModelIssue> get() = project.issues
    public val metadata: AresProjectMetadataDocument? get() = project.raw.metadata
    public val capabilityCatalog: CapabilityCatalogDocument? get() = project.capabilityCatalog
    public val autonomousCatalog: AutonomousCatalogDocument? get() = project.raw.autonomousCatalog
    public val field: RobotFieldConfig? get() = project.raw.field
    public val simulationPlan: SimulationProjectPlan? get() = project.simulationPlan
    public val tuningComponents: List<TuningComponentDocument> get() = project.raw.tuningComponents.sortedBy { it.uid }
    public val tuningProfiles: List<TuningProfileDocument> get() = project.raw.tuningProfiles.sortedBy { it.uid }
    public val tuningParameters: List<TuningParameterDeclaration> get() =
        (drivetrains.flatMap { it.parameters } +
            subsystems.flatMap { it.tuningParameters } +
            tuningComponents.flatMap { it.parameters })
            .sortedBy { it.uid }

    public val actions: List<ActionDescriptor> get() = project.actions.entries.sortedBy { it.key.value }.map { it.value }
    public val actionKeys: Set<ProjectActionKey> get() = project.actions.keys
    public val routines: List<RoutineDocument> get() = project.routines.entries.sortedBy { it.key.value }.map { it.value }
    public val controlSchemes: List<ControlSchemeDocument> get() =
        project.controlSchemes.entries.sortedBy { it.key.value }.map { it.value }
    public val controllerProfiles: List<ControllerProfileDocument> get() =
        project.controllerProfiles.entries.sortedBy { it.key.value }.map { it.value }
    public val subsystems: List<SubsystemDocument> get() = project.subsystems.entries.sortedBy { it.key.value }.map { it.value }
    public val superstructures: List<SuperstructureDocument> get() =
        project.superstructures.entries.sortedBy { it.key.value }.map { it.value }
    public val drivetrains: List<DrivetrainDocument> get() = project.drivetrains.entries.sortedBy { it.key.value }.map { it.value }

    public fun action(key: String): ActionDescriptor? = runCatching { ProjectActionKey(key) }.getOrNull()?.let(project.actions::get)
    public fun routine(id: String): RoutineDocument? = document(id, project.routines)
    public fun controlScheme(id: String): ControlSchemeDocument? = document(id, project.controlSchemes)
    public fun controllerProfile(id: String): ControllerProfileDocument? = document(id, project.controllerProfiles)
    public fun subsystem(id: String): SubsystemDocument? = document(id, project.subsystems)
    public fun superstructure(id: String): SuperstructureDocument? = document(id, project.superstructures)
    public fun drivetrain(id: String): DrivetrainDocument? = document(id, project.drivetrains)

    private fun <T> document(id: String, index: Map<ProjectDocumentId, T>): T? =
        runCatching { ProjectDocumentId(id) }.getOrNull()?.let(index::get)
}

public fun EffectiveRobotProject.queries(): EffectiveRobotProjectQueries = EffectiveRobotProjectQueries(this)
