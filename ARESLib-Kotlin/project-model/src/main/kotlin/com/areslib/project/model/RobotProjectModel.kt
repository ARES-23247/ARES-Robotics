package com.areslib.project.model

import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.catalog.CatalogValidationSeverity
import com.areslib.catalog.validateCapabilityCatalog
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControlValidationContext
import com.areslib.controls.ControlValidationSeverity
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileDocument
import com.areslib.controls.DriveAxisKeys
import com.areslib.controls.learnedControlIds
import com.areslib.controls.validateControlScheme
import com.areslib.controls.validateControllerProfile
import com.areslib.drivetrain.DrivetrainDocument
import com.areslib.drivetrain.DrivetrainPlatform
import com.areslib.drivetrain.validateDrivetrainDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.validateAresProjectMetadata
import com.areslib.project.schema.AresProjectTarget
import com.areslib.project.schema.ProjectActionKey
import com.areslib.project.schema.ProjectDocumentId
import com.areslib.project.schema.ProjectDocumentKind
import com.areslib.project.schema.ProjectId
import com.areslib.project.schema.defaultProjectTarget
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineValidationContext
import com.areslib.routine.RoutineValidationSeverity
import com.areslib.routine.validateAutonomousCatalog
import com.areslib.routine.validateRoutineSet
import com.areslib.simulation.SimulationProjectPlan
import com.areslib.simulation.SimulationProjectPlanner
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldValidator
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.mergeSubsystemCapabilities
import com.areslib.subsystem.SubsystemSchema
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureIssueSeverity
import com.areslib.superstructure.TransitionTriggerKind
import com.areslib.superstructure.validateSuperstructureProject
import com.areslib.tuning.TuningComponentDocument
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.validateTuningParameterDeclarations
import com.areslib.tuning.validateTuningProfileDocument

public enum class ProjectModelSeverity { WARNING, ERROR }

/** Pure diagnostic: filesystem repositories may attach a concrete path at their boundary. */
public data class ProjectModelIssue(
    val severity: ProjectModelSeverity,
    val kind: ProjectDocumentKind,
    val documentId: String? = null,
    val path: String,
    val code: String,
    val message: String,
)

/**
 * Every decoded canonical authoring input for one robot.
 *
 * This type performs no I/O and intentionally distinguishes the authored base catalog from the
 * subsystem-derived effective catalog. Decode failures are supplied as [loadIssues] so Studio and
 * codegen feed the same assembler without giving the model filesystem responsibilities.
 */
public data class RobotProjectSnapshot(
    val projectRoot: String,
    val metadata: AresProjectMetadataDocument?,
    val baseCapabilityCatalog: CapabilityCatalogDocument?,
    val autonomousCatalog: AutonomousCatalogDocument? = null,
    val routines: List<RoutineDocument> = emptyList(),
    val controlSchemes: List<ControlSchemeDocument> = emptyList(),
    val controllerProfiles: List<ControllerProfileDocument> = emptyList(),
    val subsystems: List<SubsystemDocument> = emptyList(),
    val superstructures: List<SuperstructureDocument> = emptyList(),
    val drivetrains: List<DrivetrainDocument> = emptyList(),
    val field: RobotFieldConfig? = null,
    val tuningComponents: List<TuningComponentDocument> = emptyList(),
    val tuningProfiles: List<TuningProfileDocument> = emptyList(),
    val loadIssues: List<ProjectModelIssue> = emptyList(),
)

/** One validated, derived project view consumed by Studio, codegen, verification, and simulation. */
public data class EffectiveRobotProject(
    val raw: RobotProjectSnapshot,
    val projectId: ProjectId?,
    val target: AresProjectTarget?,
    /** Simulator product and capability evidence derived from the same canonical project. */
    val simulationPlan: SimulationProjectPlan?,
    val capabilityCatalog: CapabilityCatalogDocument?,
    val actions: Map<ProjectActionKey, com.areslib.catalog.ActionDescriptor>,
    val routines: Map<ProjectDocumentId, RoutineDocument>,
    val controlSchemes: Map<ProjectDocumentId, ControlSchemeDocument>,
    val controllerProfiles: Map<ProjectDocumentId, ControllerProfileDocument>,
    val subsystems: Map<ProjectDocumentId, SubsystemDocument>,
    val superstructures: Map<ProjectDocumentId, SuperstructureDocument>,
    val drivetrains: Map<ProjectDocumentId, DrivetrainDocument>,
    val issues: List<ProjectModelIssue>,
) {
    public val isValid: Boolean get() = issues.none { it.severity == ProjectModelSeverity.ERROR }
}

/** Shared pure assembler. Callers own bytes and filesystem paths; this owns project semantics. */
public object RobotProjectAssembler {
    @JvmStatic
    public fun assemble(
        snapshot: RobotProjectSnapshot,
        inputPlatform: ControllerInputPlatform? = null,
    ): EffectiveRobotProject {
        val issues = snapshot.loadIssues.toMutableList()
        val metadata = snapshot.metadata
        val projectId = metadata?.projectId?.let { raw ->
            runCatching { ProjectId(raw) }.getOrElse { error ->
                issues.error(ProjectDocumentKind.PROJECT_METADATA, raw, "projectId", "invalid_project_id", error.message.orEmpty())
                null
            }
        }
        if (metadata == null) {
            issues.error(ProjectDocumentKind.PROJECT_METADATA, null, "project", "missing_metadata", "Canonical .ares/project.json is required")
        } else {
            validateAresProjectMetadata(metadata).forEach { message ->
                issues.error(ProjectDocumentKind.PROJECT_METADATA, metadata.projectId, "project", "metadata_validation", message)
            }
        }

        val target = metadata?.league?.defaultProjectTarget()
        if (metadata != null && inputPlatform != null && inputPlatform != ControllerInputPlatform.DESKTOP_GLFW) {
            val expected = when (metadata.league) {
                AresLeague.FTC -> ControllerInputPlatform.FTC
                AresLeague.FRC -> ControllerInputPlatform.FRC
                AresLeague.XRP -> ControllerInputPlatform.XRP
            }
            if (inputPlatform != expected) {
                issues.error(
                    ProjectDocumentKind.PROJECT_METADATA,
                    metadata.projectId,
                    "league",
                    "platform_mismatch",
                    "Project league ${metadata.league} cannot be assembled for $inputPlatform",
                )
            }
        }

        val baseCatalog = snapshot.baseCapabilityCatalog
        if (baseCatalog == null) {
            issues.error(ProjectDocumentKind.CAPABILITY_CATALOG, null, "catalog", "missing_catalog", "Canonical .ares/action-catalog.json is required")
        }
        val effectiveCatalog = baseCatalog?.let { catalog ->
            runCatching { mergeSubsystemCapabilities(catalog, snapshot.subsystems) }
                .onFailure { error ->
                    issues.error(
                        ProjectDocumentKind.CAPABILITY_CATALOG,
                        catalog.projectId,
                        "actions",
                        "capability_derivation_failed",
                        error.message ?: "Subsystem actions could not be derived",
                    )
                }
                .getOrNull()
        }

        validateProjectIds(metadata, baseCatalog, snapshot.autonomousCatalog, snapshot, issues)
        validatePlatforms(metadata, snapshot, issues)
        validateDocuments(snapshot, effectiveCatalog, inputPlatform, issues)

        val actions = effectiveCatalog?.actions.orEmpty().mapNotNull { action ->
            runCatching { ProjectActionKey(action.key) }.getOrElse { error ->
                issues.error(ProjectDocumentKind.CAPABILITY_CATALOG, effectiveCatalog?.projectId, "actions.${action.key}", "invalid_action_key", error.message.orEmpty())
                null
            }?.let { it to action }
        }.toMap()

        val indexedSubsystems = typedIndex(snapshot.subsystems, SubsystemDocument::documentId, ProjectDocumentKind.SUBSYSTEM, issues)
        val indexedDrivetrains = typedIndex(snapshot.drivetrains, DrivetrainDocument::uid, ProjectDocumentKind.DRIVETRAIN, issues)
        val simulationPlan = target?.let {
            SimulationProjectPlanner.plan(it, indexedDrivetrains.values.singleOrNull(), indexedSubsystems.values)
        }

        return EffectiveRobotProject(
            raw = snapshot,
            projectId = projectId,
            target = target,
            simulationPlan = simulationPlan,
            capabilityCatalog = effectiveCatalog,
            actions = actions,
            routines = typedIndex(snapshot.routines, RoutineDocument::documentId, ProjectDocumentKind.ROUTINE, issues),
            controlSchemes = typedIndex(snapshot.controlSchemes, ControlSchemeDocument::documentId, ProjectDocumentKind.CONTROL_SCHEME, issues),
            controllerProfiles = typedIndex(snapshot.controllerProfiles, ControllerProfileDocument::documentId, ProjectDocumentKind.CONTROLLER_PROFILE, issues),
            subsystems = indexedSubsystems,
            superstructures = typedIndex(snapshot.superstructures, SuperstructureDocument::superstructureId, ProjectDocumentKind.SUPERSTRUCTURE, issues),
            drivetrains = indexedDrivetrains,
            issues = issues.distinctBy { listOf(it.severity, it.kind, it.documentId, it.path, it.code, it.message) }
                .sortedWith(compareBy<ProjectModelIssue> { it.kind.ordinal }.thenBy { it.documentId.orEmpty() }.thenBy { it.path }),
        )
    }

    private fun validateProjectIds(
        metadata: AresProjectMetadataDocument?,
        catalog: CapabilityCatalogDocument?,
        autonomous: AutonomousCatalogDocument?,
        snapshot: RobotProjectSnapshot,
        issues: MutableList<ProjectModelIssue>,
    ) {
        val expected = metadata?.projectId
        fun check(kind: ProjectDocumentKind, documentId: String?, actual: String, path: String) {
            if (expected != null && actual != expected) {
                issues.error(kind, documentId, path, "project_identity_mismatch", "Expected project '$expected' but found '$actual'")
            }
        }
        catalog?.let { check(ProjectDocumentKind.CAPABILITY_CATALOG, it.projectId, it.projectId, "projectId") }
        autonomous?.let { check(ProjectDocumentKind.AUTONOMOUS_CATALOG, it.projectId, it.projectId, "projectId") }
        snapshot.tuningComponents.forEach {
            check(ProjectDocumentKind.TUNING_COMPONENT, it.uid, it.projectId, "projectId")
        }
        snapshot.tuningProfiles.forEach {
            check(ProjectDocumentKind.TUNING_PROFILE, it.uid, it.projectId, "projectId")
        }
    }

    private fun validatePlatforms(
        metadata: AresProjectMetadataDocument?,
        snapshot: RobotProjectSnapshot,
        issues: MutableList<ProjectModelIssue>,
    ) {
        metadata ?: return
        val subsystemPlatform = when (metadata.league) {
            AresLeague.FTC -> SubsystemPlatform.FTC
            AresLeague.FRC -> SubsystemPlatform.FRC
            AresLeague.XRP -> SubsystemPlatform.XRP
        }
        snapshot.subsystems.filter { it.platform != subsystemPlatform }.forEach {
            issues.error(ProjectDocumentKind.SUBSYSTEM, it.documentId, "platform", "league_mismatch", "${it.platform} subsystem cannot belong to ${metadata.league} project")
        }
        val drivetrainPlatform = when (metadata.league) {
            AresLeague.FTC -> DrivetrainPlatform.FTC
            AresLeague.FRC -> DrivetrainPlatform.FRC
            AresLeague.XRP -> DrivetrainPlatform.XRP
        }
        snapshot.drivetrains.filter { it.platform != drivetrainPlatform }.forEach {
            issues.error(ProjectDocumentKind.DRIVETRAIN, it.uid, "platform", "league_mismatch", "${it.platform} drivetrain cannot belong to ${metadata.league} project")
        }
        if (snapshot.drivetrains.size > 1) {
            issues.error(ProjectDocumentKind.DRIVETRAIN, null, "drivetrains", "multiple_drivetrains", "A robot project may declare exactly one active drivetrain")
        }
    }

    private fun validateDocuments(
        snapshot: RobotProjectSnapshot,
        catalog: CapabilityCatalogDocument?,
        inputPlatform: ControllerInputPlatform?,
        issues: MutableList<ProjectModelIssue>,
    ) {
        catalog?.let { document ->
            validateCapabilityCatalog(document).forEach { issue ->
                issues.add(
                    ProjectModelIssue(
                        if (issue.severity == CatalogValidationSeverity.ERROR) ProjectModelSeverity.ERROR else ProjectModelSeverity.WARNING,
                        ProjectDocumentKind.CAPABILITY_CATALOG,
                        document.projectId,
                        issue.path,
                        "capability_catalog",
                        issue.message,
                    )
                )
            }
        }

        SubsystemSchema.validateAll(snapshot.subsystems).forEach {
            issues.error(ProjectDocumentKind.SUBSYSTEM, null, it.path, "subsystem_validation", it.message)
        }
        snapshot.drivetrains.forEach { document ->
            validateDrivetrainDocument(document).forEach {
                issues.error(ProjectDocumentKind.DRIVETRAIN, document.uid, it.path, "drivetrain_validation", it.message)
            }
        }
        snapshot.controllerProfiles.forEach { document ->
            validateControllerProfile(document).forEach { issue ->
                issues.add(
                    ProjectModelIssue(
                        if (issue.severity == ControlValidationSeverity.ERROR) ProjectModelSeverity.ERROR else ProjectModelSeverity.WARNING,
                        ProjectDocumentKind.CONTROLLER_PROFILE,
                        document.documentId,
                        issue.path,
                        issue.code,
                        issue.message,
                    )
                )
            }
        }

        val referenceCatalog = catalog ?: CapabilityCatalogDocument(
            projectId = snapshot.metadata?.projectId ?: "missing-project",
        )
        validateSuperstructures(snapshot, referenceCatalog, issues)
        validateControls(snapshot, referenceCatalog, inputPlatform, issues)
        validateRoutines(snapshot, referenceCatalog, issues)

        snapshot.autonomousCatalog?.let { document ->
            validateAutonomousCatalog(document, snapshot.routines.mapTo(linkedSetOf()) { it.documentId }).forEach { issue ->
                issues.add(issue.toProjectIssue(ProjectDocumentKind.AUTONOMOUS_CATALOG))
            }
        }
        snapshot.field?.let { field ->
            val requiredType = snapshot.metadata?.league?.let {
                when (it) {
                    AresLeague.FTC -> FieldType.FTC
                    AresLeague.FRC -> FieldType.FRC
                    AresLeague.XRP -> FieldType.XRP
                }
            }
            RobotFieldValidator.validate(field, requiredType).forEach { issue ->
                issues.error(ProjectDocumentKind.FIELD, field.id, "field", issue.code.name.lowercase(), issue.message)
            }
        }

        val tuningDeclarations = buildList<TuningParameterDeclaration> {
            snapshot.tuningComponents.forEach { addAll(it.parameters) }
            snapshot.drivetrains.forEach { addAll(it.parameters) }
            snapshot.subsystems.forEach { addAll(it.tuningParameters) }
        }
        validateTuningParameterDeclarations(tuningDeclarations).forEach {
            issues.error(ProjectDocumentKind.TUNING_COMPONENT, null, it.path, "tuning_declaration", it.message)
        }
        snapshot.tuningProfiles.forEach { profile ->
            validateTuningProfileDocument(profile, tuningDeclarations).forEach {
                issues.error(ProjectDocumentKind.TUNING_PROFILE, profile.uid, it.path, "tuning_profile", it.message)
            }
        }
    }

    private fun validateSuperstructures(
        snapshot: RobotProjectSnapshot,
        catalog: CapabilityCatalogDocument,
        issues: MutableList<ProjectModelIssue>,
    ) {
        val actionKeys = catalog.actions.mapTo(linkedSetOf()) { it.key }
        val parameterless = catalog.actions.filter { it.parameters.isEmpty() }.mapTo(linkedSetOf()) { it.key }
        snapshot.superstructures.forEach { document ->
            validateSuperstructureProject(document, snapshot.subsystems, actionKeys, parameterless).forEach { issue ->
                issues.add(
                    ProjectModelIssue(
                        if (issue.severity == SuperstructureIssueSeverity.ERROR) ProjectModelSeverity.ERROR else ProjectModelSeverity.WARNING,
                        ProjectDocumentKind.SUPERSTRUCTURE,
                        document.superstructureId,
                        issue.path,
                        "superstructure_validation",
                        issue.message,
                    )
                )
            }
        }
        snapshot.superstructures
            .flatMap { document ->
                document.transitions.filter { it.triggerKind == TransitionTriggerKind.ACTION_REQUEST }
                    .mapNotNull { edge -> edge.actionKey?.let { it to document.superstructureId } }
            }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.distinct().size > 1 }
            .forEach { (key, owners) ->
                owners.distinct().forEach { owner ->
                    issues.error(ProjectDocumentKind.SUPERSTRUCTURE, owner, "transitions.actionKey", "duplicate_action_owner", "Action '$key' is owned by more than one superstructure: ${owners.distinct().sorted().joinToString()}")
                }
            }
    }

    private fun validateControls(
        snapshot: RobotProjectSnapshot,
        catalog: CapabilityCatalogDocument,
        inputPlatform: ControllerInputPlatform?,
        issues: MutableList<ProjectModelIssue>,
    ) {
        val profiles = snapshot.controllerProfiles.associateBy { it.documentId }
        val controlsByProfile = profiles.mapValues { (_, profile) ->
            if (inputPlatform == null) {
                profile.controls.filter { it.mappings.isNotEmpty() }.mapTo(linkedSetOf()) { it.controlId }
            } else {
                profile.learnedControlIds(inputPlatform)
            }
        }
        val routineIds = snapshot.routines.mapTo(linkedSetOf()) { it.documentId }
        val actionKeys = catalog.actions.mapTo(linkedSetOf()) { it.key }
        val context = ControlValidationContext.fromCatalog(catalog, routineIds, controlsByProfile)
        snapshot.controlSchemes.forEach { scheme ->
            scheme.controllers.filter { it.profileId !in profiles }.forEach {
                issues.error(ProjectDocumentKind.CONTROL_SCHEME, scheme.documentId, "controllers.${it.slot}", "unknown_controller_profile", "Unknown controller profile '${it.profileId}'")
            }
            val profileBySlot = scheme.controllers.associate { it.slot to it.profileId }
            scheme.bindings.filter { it.enabled }.forEach { binding ->
                val profileId = profileBySlot[binding.source.controllerSlot]
                if (profileId != null && binding.source.controlIds.any { it !in controlsByProfile[profileId].orEmpty() }) {
                    issues.error(ProjectDocumentKind.CONTROL_SCHEME, scheme.documentId, "bindings.${binding.bindingId}", "unlearned_control", "Binding '${binding.bindingId}' uses an unlearned control for profile '$profileId'")
                }
                when (binding.target.kind) {
                    ControlTargetKind.ACTION -> if (binding.target.key !in actionKeys) {
                        issues.error(ProjectDocumentKind.CONTROL_SCHEME, scheme.documentId, "bindings.${binding.bindingId}.target", "unknown_action", "Unknown action '${binding.target.key}'")
                    }
                    ControlTargetKind.ROUTINE, ControlTargetKind.CANCEL_ROUTINE -> if (binding.target.key !in routineIds) {
                        issues.error(ProjectDocumentKind.CONTROL_SCHEME, scheme.documentId, "bindings.${binding.bindingId}.target", "unknown_routine", "Unknown routine '${binding.target.key}'")
                    }
                    ControlTargetKind.DRIVE -> if (binding.target.key !in DriveAxisKeys.ALL) {
                        issues.error(ProjectDocumentKind.CONTROL_SCHEME, scheme.documentId, "bindings.${binding.bindingId}.target", "unknown_drive_axis", "Unknown drivetrain axis '${binding.target.key}'")
                    }
                }
            }
            validateControlScheme(scheme, context).forEach { issue ->
                issues.add(
                    ProjectModelIssue(
                        if (issue.severity == ControlValidationSeverity.ERROR) ProjectModelSeverity.ERROR else ProjectModelSeverity.WARNING,
                        ProjectDocumentKind.CONTROL_SCHEME,
                        scheme.documentId,
                        issue.path,
                        issue.code,
                        issue.message,
                    )
                )
            }
        }
    }

    private fun validateRoutines(
        snapshot: RobotProjectSnapshot,
        catalog: CapabilityCatalogDocument,
        issues: MutableList<ProjectModelIssue>,
    ) {
        val actionByKey = catalog.actions.associateBy { it.key }
        val conditionKeys = catalog.conditions.mapTo(linkedSetOf()) { it.key }
        val context = RoutineValidationContext(
            hasAction = actionByKey::containsKey,
            hasCondition = conditionKeys::contains,
            resourcesForAction = { key -> actionByKey[key]?.resources?.mapTo(linkedSetOf()) { it.resourceKey }.orEmpty() },
        )
        validateRoutineSet(snapshot.routines, context).forEach { issue ->
            issues.add(issue.toProjectIssue(ProjectDocumentKind.ROUTINE))
        }
    }

    private fun com.areslib.routine.RoutineValidationIssue.toProjectIssue(kind: ProjectDocumentKind) =
        ProjectModelIssue(
            if (severity == RoutineValidationSeverity.ERROR) ProjectModelSeverity.ERROR else ProjectModelSeverity.WARNING,
            kind,
            documentId,
            path,
            code,
            message,
        )

    private fun <T> typedIndex(
        documents: List<T>,
        id: (T) -> String,
        kind: ProjectDocumentKind,
        issues: MutableList<ProjectModelIssue>,
    ): Map<ProjectDocumentId, T> {
        val result = linkedMapOf<ProjectDocumentId, T>()
        documents.forEach { document ->
            val rawId = id(document)
            val typed = runCatching { ProjectDocumentId(rawId) }.getOrElse { error ->
                issues.error(kind, rawId, "documentId", "invalid_document_id", error.message.orEmpty())
                return@forEach
            }
            if (result.putIfAbsent(typed, document) != null) {
                issues.error(kind, rawId, "documentId", "duplicate_document_id", "More than one ${kind.name.lowercase()} uses ID '$rawId'")
            }
        }
        return result
    }

    private fun MutableList<ProjectModelIssue>.error(
        kind: ProjectDocumentKind,
        documentId: String?,
        path: String,
        code: String,
        message: String,
    ) {
        add(ProjectModelIssue(ProjectModelSeverity.ERROR, kind, documentId, path, code, message))
    }
}
