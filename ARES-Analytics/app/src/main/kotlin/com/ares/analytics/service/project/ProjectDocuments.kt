package com.ares.analytics.service.project

import com.ares.analytics.shared.League
import com.ares.analytics.util.ProjectLayout
import com.ares.analytics.service.project.persistence.AutonomousCatalogProjectRepository
import com.ares.analytics.service.project.persistence.CapabilityCatalogProjectRepository
import com.ares.analytics.service.project.persistence.ControlSchemeProjectRepository
import com.ares.analytics.service.project.persistence.ControllerProfileProjectRepository
import com.ares.analytics.service.project.persistence.ProjectDocumentDiagnostic
import com.ares.analytics.service.project.persistence.ProjectDocumentKind
import com.ares.analytics.service.project.persistence.ProjectDocumentListing
import com.ares.analytics.service.project.persistence.ProjectMetadataRepository
import com.ares.analytics.service.project.persistence.RoutineProjectRepository
import com.ares.analytics.service.project.persistence.SubsystemProjectRepository
import com.ares.analytics.service.project.persistence.SuperstructureProjectRepository
import com.ares.analytics.service.project.persistence.requireProjectRoot
import com.ares.analytics.service.project.persistence.resolveProjectPath
import com.areslib.controls.ControllerInputPlatform
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.model.EffectiveRobotProject
import com.areslib.project.model.EffectiveRobotProjectQueries
import com.areslib.project.model.ProjectModelIssue
import com.areslib.project.model.ProjectModelSeverity
import com.areslib.project.model.RobotProjectAssembler
import com.areslib.project.model.RobotProjectSnapshot
import com.areslib.project.model.queries
import com.areslib.state.RobotFieldDocument
import com.areslib.tuning.TuningComponentDocumentCodec
import com.areslib.tuning.TuningProfileDocumentCodec
import java.io.File

/** Studio read boundary backed by the shared effective project model. */
data class AresProjectDocumentSnapshot(
    val effectiveProject: EffectiveRobotProject,
    val diagnostics: List<ProjectDocumentDiagnostic>,
) {
    val projectRoot: String get() = effectiveProject.raw.projectRoot
    val query: EffectiveRobotProjectQueries get() = effectiveProject.queries()
}

/**
 * Loads canonical project bytes and delegates all derivation and cross-document semantics to the
 * shared ARES project assembler. Repositories retain write/history ownership; this class is the
 * single read boundary used by Studio features.
 */
interface ProjectDocumentGateway {
    val routines: RoutineProjectRepository
    val controls: ControlSchemeProjectRepository
    val controllers: ControllerProfileProjectRepository
    val subsystems: SubsystemProjectRepository
    val superstructures: SuperstructureProjectRepository
    val capabilities: CapabilityCatalogProjectRepository
    val metadata: ProjectMetadataRepository
    val autonomous: AutonomousCatalogProjectRepository

    fun load(projectPath: String, targetPlatform: ControllerInputPlatform? = null): AresProjectDocumentSnapshot
}

class AresProjectDocuments(
    override val routines: RoutineProjectRepository = RoutineProjectRepository(),
    override val controls: ControlSchemeProjectRepository = ControlSchemeProjectRepository(),
    override val controllers: ControllerProfileProjectRepository = ControllerProfileProjectRepository(),
    override val subsystems: SubsystemProjectRepository = SubsystemProjectRepository(),
    override val superstructures: SuperstructureProjectRepository = SuperstructureProjectRepository(),
    override val capabilities: CapabilityCatalogProjectRepository = CapabilityCatalogProjectRepository(),
    override val metadata: ProjectMetadataRepository = ProjectMetadataRepository(),
    override val autonomous: AutonomousCatalogProjectRepository = AutonomousCatalogProjectRepository(routines),
) : ProjectDocumentGateway {
    override fun load(
        projectPath: String,
        targetPlatform: ControllerInputPlatform?,
    ): AresProjectDocumentSnapshot {
        val root = requireProjectRoot(projectPath)
        val routineListing = routines.list(root.path)
        val controlsListing = controls.list(root.path)
        val profileListing = controllers.list(root.path)
        val subsystemListing = subsystems.list(root.path)
        val superstructureListing = superstructures.list(root.path)
        val diagnostics = buildList {
            addAll(routineListing.diagnostics)
            addAll(controlsListing.diagnostics)
            addAll(profileListing.diagnostics)
            addAll(subsystemListing.diagnostics)
            addAll(superstructureListing.diagnostics)
        }.toMutableList()

        val baseCatalog = capabilities.load(root.path).fold(
            onSuccess = { it },
            onFailure = { error ->
                capabilities.file(root.path).takeIf(File::isFile)?.let { file ->
                    diagnostics += ProjectDocumentDiagnostic(
                        ProjectDocumentKind.CAPABILITY_CATALOG,
                        file,
                        error.message ?: "Capability catalog could not be decoded",
                    )
                }
                null
            },
        )
        val autonomousCatalog = autonomous.load(root.path).fold(
            onSuccess = { it },
            onFailure = { error ->
                val file = resolveProjectPath(root.path, ".ares/autonomous-catalog.json")
                if (file.isFile) {
                    diagnostics += ProjectDocumentDiagnostic(
                        ProjectDocumentKind.AUTONOMOUS_CATALOG,
                        file,
                        error.message ?: "Autonomous catalog could not be decoded",
                    )
                }
                null
            },
        )
        val projectMetadata = metadata.load(root.path).fold(
            onSuccess = { it },
            onFailure = { error ->
                diagnostics += ProjectDocumentDiagnostic(
                    ProjectDocumentKind.PROJECT_METADATA,
                    metadata.file(root.path),
                    error.message ?: "Canonical project metadata could not be decoded",
                )
                null
            },
        )

        val drivetrainListing = loadFiles(
            File(root, ".ares/drivetrains"),
            "aresdrivetrain",
            ProjectDocumentKind.DRIVETRAIN,
            DrivetrainDocumentCodec::decode,
        )
        diagnostics += drivetrainListing.diagnostics
        val tuningComponentListing = loadFiles(
            File(root, ".ares/tuning-components"),
            "arestuningcomponent",
            ProjectDocumentKind.TUNING_COMPONENT,
            TuningComponentDocumentCodec::decode,
        )
        diagnostics += tuningComponentListing.diagnostics
        val declarations = drivetrainListing.documents.flatMap { it.parameters } +
            subsystemListing.documents.flatMap { it.tuningParameters } +
            tuningComponentListing.documents.flatMap { it.parameters }
        val tuningProfileListing = loadFiles(
            File(root, ".ares/tuning"),
            "arestuning",
            ProjectDocumentKind.TUNING_PROFILE,
        ) { TuningProfileDocumentCodec.decode(it, declarations) }
        diagnostics += tuningProfileListing.diagnostics

        val fieldFile = projectMetadata?.let { ProjectLayout.fieldDefinitionFile(root.path, it.toStudioLeague()) }
        val field = fieldFile?.takeIf(File::isFile)?.let { file ->
            runCatching { RobotFieldDocument.decode(file.readText()) }.getOrElse { error ->
                diagnostics += ProjectDocumentDiagnostic(
                    ProjectDocumentKind.FIELD,
                    file,
                    error.message ?: "Canonical field document could not be decoded",
                )
                null
            }
        }

        val raw = RobotProjectSnapshot(
            projectRoot = root.path,
            metadata = projectMetadata,
            baseCapabilityCatalog = baseCatalog,
            autonomousCatalog = autonomousCatalog,
            routines = routineListing.documents,
            controlSchemes = controlsListing.documents,
            controllerProfiles = profileListing.documents,
            subsystems = subsystemListing.documents,
            superstructures = superstructureListing.documents,
            drivetrains = drivetrainListing.documents,
            field = field,
            tuningComponents = tuningComponentListing.documents,
            tuningProfiles = tuningProfileListing.documents,
            loadIssues = diagnostics.map { diagnostic ->
                ProjectModelIssue(
                    severity = ProjectModelSeverity.ERROR,
                    kind = diagnostic.kind,
                    documentId = diagnostic.file.nameWithoutExtension,
                    path = "decode",
                    code = "decode_error",
                    message = diagnostic.message,
                )
            },
        )
        val effective = RobotProjectAssembler.assemble(raw, targetPlatform)
        effective.issues
            .asSequence()
            .filter { issue -> issue.severity == ProjectModelSeverity.ERROR && issue.code != "decode_error" }
            .forEach { issue ->
                diagnostics += ProjectDocumentDiagnostic(
                    issue.kind,
                    issueFile(root, projectMetadata, issue),
                    "${issue.path}: ${issue.message}",
                )
            }

        return AresProjectDocumentSnapshot(
            effectiveProject = effective,
            diagnostics = diagnostics
                .distinctBy { Triple(it.kind, it.file.canonicalPath, it.message) }
                .sortedWith(compareBy<ProjectDocumentDiagnostic> { it.kind.ordinal }.thenBy { it.file.name.lowercase() }),
        )
    }

    private fun issueFile(
        root: File,
        projectMetadata: AresProjectMetadataDocument?,
        issue: ProjectModelIssue,
    ): File = when (issue.kind) {
        ProjectDocumentKind.PROJECT_METADATA -> metadata.file(root.path)
        ProjectDocumentKind.CAPABILITY_CATALOG -> capabilities.file(root.path)
        ProjectDocumentKind.AUTONOMOUS_CATALOG -> File(root, ".ares/autonomous-catalog.json")
        ProjectDocumentKind.ROUTINE -> issue.documentId?.let { resolveProjectPath(root.path, ".ares/routines/$it.aresroutine") }
        ProjectDocumentKind.CONTROL_SCHEME -> issue.documentId?.let { resolveProjectPath(root.path, ".ares/controls/$it.arescontrols") }
        ProjectDocumentKind.CONTROLLER_PROFILE -> issue.documentId?.let { resolveProjectPath(root.path, ".ares/controllers/$it.arescontroller") }
        ProjectDocumentKind.SUBSYSTEM -> issue.documentId?.let { subsystems.file(root.path, it) }
        ProjectDocumentKind.SUPERSTRUCTURE -> issue.documentId?.let { superstructures.file(root.path, it) }
        ProjectDocumentKind.DRIVETRAIN -> issue.documentId?.let { File(root, ".ares/drivetrains/$it.aresdrivetrain") }
        ProjectDocumentKind.FIELD -> projectMetadata?.let { ProjectLayout.fieldDefinitionFile(root.path, it.toStudioLeague()) }
        ProjectDocumentKind.TUNING_COMPONENT -> issue.documentId?.let { File(root, ".ares/tuning-components/$it.arestuningcomponent") }
        ProjectDocumentKind.TUNING_PROFILE -> issue.documentId?.let { File(root, ".ares/tuning/$it.arestuning") }
    } ?: File(root, ".ares")

    private fun AresProjectMetadataDocument.toStudioLeague(): League = when (league) {
        AresLeague.FTC -> League.FTC
        AresLeague.FRC -> League.FRC
    }

    private fun <T> loadFiles(
        directory: File,
        extension: String,
        kind: ProjectDocumentKind,
        decode: (String) -> T,
    ): ProjectDocumentListing<T> {
        if (!directory.isDirectory) return ProjectDocumentListing(emptyList(), emptyList())
        val documents = mutableListOf<T>()
        val diagnostics = mutableListOf<ProjectDocumentDiagnostic>()
        directory.listFiles { file -> file.isFile && file.extension == extension }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .forEach { file ->
                runCatching { decode(file.readText()) }
                    .onSuccess(documents::add)
                    .onFailure { error ->
                        diagnostics += ProjectDocumentDiagnostic(
                            kind,
                            file,
                            error.message ?: "${kind.name.lowercase()} could not be decoded",
                        )
                    }
            }
        return ProjectDocumentListing(documents, diagnostics)
    }
}
