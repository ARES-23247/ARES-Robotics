package com.areslib.project.compiler

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileCodec
import com.areslib.controls.ControllerProfileDocument
import com.areslib.drivetrain.DrivetrainDocument
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.model.EffectiveRobotProject
import com.areslib.project.schema.AresControllerTarget
import com.areslib.project.schema.AresProjectTarget
import com.areslib.project.schema.ProjectActionKey
import com.areslib.project.schema.ProjectDocumentId
import com.areslib.project.schema.ProjectId
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.RoutineDocument
import com.areslib.simulation.SimulationProductId
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureDocumentCodec
import com.areslib.tuning.TuningComponentDocument
import com.areslib.tuning.TuningComponentDocumentCodec
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningProfileDocumentCodec
import java.security.MessageDigest

/** Version of the platform-neutral compiler IR, independent of individual document schemas. */
const val ARES_PROJECT_COMPILER_IR_VERSION: Int = 2

/** An effective action with a stable, validated project key. */
data class ProjectActionIr(
    val key: ProjectActionKey,
    val descriptor: ActionDescriptor,
)

/** A canonical routine after whole-project reference validation. */
data class ProjectRoutineIr(
    val id: ProjectDocumentId,
    val document: RoutineDocument,
)

/** A canonical controller scheme after whole-project reference validation. */
data class ProjectControlSchemeIr(
    val id: ProjectDocumentId,
    val document: ControlSchemeDocument,
)

/** A controller profile selected by a validated control scheme. */
data class ProjectControllerProfileIr(
    val id: ProjectDocumentId,
    val document: ControllerProfileDocument,
)

/** A subsystem compiler input with typed identity; renderers may not reopen project files. */
data class ProjectSubsystemIr(
    val id: ProjectDocumentId,
    val document: SubsystemDocument,
)

/** A superstructure compiler input with typed identity. */
data class ProjectSuperstructureIr(
    val id: ProjectDocumentId,
    val document: SuperstructureDocument,
)

/** The single active drivetrain compiler input, when the project declares one. */
data class ProjectDrivetrainIr(
    val id: ProjectDocumentId,
    val document: DrivetrainDocument,
)

/**
 * Deterministic, validated input to artifact planning and rendering.
 *
 * Canonical documents remain attached to their typed identities while the generator is migrated in
 * stages. The important boundary is that renderers receive this sorted IR, never raw repositories,
 * partially merged catalogs, or an invalid [EffectiveRobotProject].
 */
data class RobotProjectIr(
    val irVersion: Int = ARES_PROJECT_COMPILER_IR_VERSION,
    val projectId: ProjectId,
    val target: AresProjectTarget,
    val simulationProduct: SimulationProductId,
    val inputPlatform: ControllerInputPlatform,
    val metadata: AresProjectMetadataDocument,
    val capabilityCatalog: CapabilityCatalogDocument,
    val autonomousCatalog: AutonomousCatalogDocument?,
    val actions: List<ProjectActionIr>,
    val routines: List<ProjectRoutineIr>,
    val controlSchemes: List<ProjectControlSchemeIr>,
    val controllerProfiles: List<ProjectControllerProfileIr>,
    val subsystems: List<ProjectSubsystemIr>,
    val superstructures: List<ProjectSuperstructureIr>,
    val drivetrain: ProjectDrivetrainIr?,
    val field: RobotFieldConfig?,
    val tuningComponents: List<TuningComponentDocument>,
    val tuningProfiles: List<TuningProfileDocument>,
    val tuningDeclarations: List<TuningParameterDeclaration>,
    val tuningScopeUid: String?,
    val canonicalProjectSha256: String,
)

/** Pure lowering boundary between the effective model and every code generator. */
object RobotProjectCompiler {
    @JvmStatic
    fun lower(
        project: EffectiveRobotProject,
        requestedInputPlatform: ControllerInputPlatform? = null,
    ): RobotProjectIr {
        require(project.isValid) {
            "Cannot compile an invalid ARES project: " + project.issues.joinToString("; ") {
                "${it.kind.name.lowercase()}:${it.documentId.orEmpty()}:${it.path}: ${it.message}"
            }
        }
        require(requestedInputPlatform != ControllerInputPlatform.DESKTOP_GLFW) {
            "Generated robot code requires FTC or FRC input semantics, not DESKTOP_GLFW"
        }
        val projectId = requireNotNull(project.projectId) { "Effective project is missing its typed project ID" }
        val target = requireNotNull(project.target) { "Effective project is missing its controller/simulator target" }
        val simulationPlan = requireNotNull(project.simulationPlan) {
            "Effective project is missing its simulator product plan"
        }
        val simulationProduct = simulationPlan.product.id
        val metadata = requireNotNull(project.raw.metadata) { "Effective project is missing canonical metadata" }
        val catalog = requireNotNull(project.capabilityCatalog) { "Effective project is missing its effective capability catalog" }
        val inputPlatform = when (target.controller) {
            AresControllerTarget.FTC_CONTROL_HUB -> ControllerInputPlatform.FTC
            AresControllerTarget.FRC_ROBORIO -> ControllerInputPlatform.FRC
        }
        require(requestedInputPlatform == null || requestedInputPlatform == inputPlatform) {
            "Project target ${target.controller} cannot compile for $requestedInputPlatform"
        }
        require(project.drivetrains.size <= 1) { "Compiler IR supports exactly one active drivetrain" }

        val declarations = (
            project.drivetrains.values.flatMap { it.parameters } +
                project.subsystems.values.flatMap { it.tuningParameters } +
                project.raw.tuningComponents.flatMap { it.parameters }
            ).sortedBy { it.uid }

        return RobotProjectIr(
            projectId = projectId,
            target = target,
            simulationProduct = simulationProduct,
            inputPlatform = inputPlatform,
            metadata = metadata,
            capabilityCatalog = catalog,
            autonomousCatalog = project.raw.autonomousCatalog,
            actions = project.actions.entries.sortedBy { it.key.value }
                .map { (key, descriptor) -> ProjectActionIr(key, descriptor) },
            routines = project.routines.entries.sortedBy { it.key.value }
                .map { (id, document) -> ProjectRoutineIr(id, document) },
            controlSchemes = project.controlSchemes.entries.sortedBy { it.key.value }
                .map { (id, document) -> ProjectControlSchemeIr(id, document) },
            controllerProfiles = project.controllerProfiles.entries.sortedBy { it.key.value }
                .map { (id, document) -> ProjectControllerProfileIr(id, document) },
            subsystems = project.subsystems.entries.sortedBy { it.key.value }
                .map { (id, document) -> ProjectSubsystemIr(id, document) },
            superstructures = project.superstructures.entries.sortedBy { it.key.value }
                .map { (id, document) -> ProjectSuperstructureIr(id, document) },
            drivetrain = project.drivetrains.entries.singleOrNull()
                ?.let { (id, document) -> ProjectDrivetrainIr(id, document) },
            field = project.raw.field,
            tuningComponents = project.raw.tuningComponents.sortedBy { it.uid },
            tuningProfiles = project.raw.tuningProfiles.sortedBy { it.uid },
            tuningDeclarations = declarations,
            tuningScopeUid = project.tuningScopeUid,
            canonicalProjectSha256 = canonicalProjectHash(project, declarations),
        )
    }

    private fun canonicalProjectHash(
        project: EffectiveRobotProject,
        declarations: List<TuningParameterDeclaration>,
    ): String {
        val raw = project.raw
        val entries = buildList {
            raw.metadata?.let { add("project/project.json" to AresProjectMetadataCodec.encode(it)) }
            raw.baseCapabilityCatalog?.let { add("catalog/action-catalog.json" to CapabilityCatalogCodec.encode(it)) }
            raw.autonomousCatalog?.let { add("catalog/autonomous-catalog.json" to AutonomousCatalogCodec.encode(it)) }
            raw.routines.forEach { add("routine/${it.documentId}" to AresRoutineCodec.encode(it)) }
            raw.controlSchemes.forEach { add("controls/${it.documentId}" to ControlSchemeCodec.encode(it)) }
            raw.controllerProfiles.forEach { add("controller/${it.documentId}" to ControllerProfileCodec.encode(it)) }
            raw.subsystems.forEach { add("subsystem/${it.documentId}" to SubsystemDocumentCodec.encode(it)) }
            raw.superstructures.forEach { add("superstructure/${it.superstructureId}" to SuperstructureDocumentCodec.encode(it)) }
            raw.drivetrains.forEach { add("drivetrain/${it.uid}" to DrivetrainDocumentCodec.encode(it)) }
            raw.field?.let { add("field/field.json" to RobotFieldDocument.encode(it)) }
            raw.tuningComponents.forEach { add("tuning-component/${it.uid}" to TuningComponentDocumentCodec.encode(it)) }
            raw.tuningProfiles.forEach {
                add("tuning/${it.uid}" to TuningProfileDocumentCodec.encode(it, declarations))
            }
        }.sortedBy { it.first }
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { (key, value) ->
            digest.update(key.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(value.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().toHex()
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
