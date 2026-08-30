package com.areslib.codegen

import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControllerProfileCodec
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.compiler.ProjectArtifactId
import com.areslib.project.compiler.ProjectArtifactKind
import com.areslib.project.compiler.ProjectArtifactManifestEntry
import com.areslib.project.compiler.ProjectArtifactOwnership
import com.areslib.project.compiler.ProjectArtifactPlan
import com.areslib.project.compiler.ProjectArtifactSourceSet
import com.areslib.project.compiler.RobotProjectIr
import com.areslib.project.compiler.sha256
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.isAresGenerated

/** Rendered content paired with the plan that authorized its destination and ownership. */
data class RenderedKotlinArtifact(
    val plan: ProjectArtifactPlan,
    val content: String,
) {
    fun manifestEntry(): ProjectArtifactManifestEntry = ProjectArtifactManifestEntry(
        id = plan.id,
        relativePath = plan.relativePath,
        sourceSet = plan.sourceSet,
        ownership = plan.ownership,
        kind = plan.kind,
        contentSha256 = sha256(content),
    )
}

/** Project registry/runtime renderer; it cannot load or assemble project files. */
object ProjectRuntimeKotlinRenderer {
    fun render(
        project: RobotProjectIr,
        relativePath: String,
        packageName: String,
        objectName: String,
        registryInterfaceName: String,
        subsystemRegistryFqn: String?,
        generatedActionRegistryBindings: Map<String, String>,
    ): Pair<RenderedKotlinArtifact, GeneratedKotlinSource> {
        val generated = AresKotlinProjectGenerator.generate(
            KotlinProjectCompilationRequest(
                project = project,
                packageName = packageName,
                objectName = objectName,
                registryInterfaceName = registryInterfaceName,
                subsystemRegistryFqn = subsystemRegistryFqn,
                generatedActionRegistryBindings = generatedActionRegistryBindings,
            )
        )
        return RenderedKotlinArtifact(
            ProjectArtifactPlan(
                ProjectArtifactId("project.runtime"),
                relativePath,
                ProjectArtifactSourceSet.MAIN,
                ProjectArtifactOwnership.GENERATED_DO_NOT_EDIT,
                ProjectArtifactKind.PROJECT_RUNTIME,
                "Generated project registry, routines, controls, and capability dispatch.",
            ),
            generated.source,
        ) to generated
    }
}

/** Generated whole-project behavioral contract tests. */
object ProjectVerificationKotlinRenderer {
    fun render(
        project: RobotProjectIr,
        relativePathPrefix: String,
        packageName: String,
    ): RenderedKotlinArtifact {
        val generated = ProjectVerificationKotlinGenerator.generate(
            ProjectVerificationCodegenRequest(
                packageName = packageName,
                platform = project.inputPlatform,
                projectJson = AresProjectMetadataCodec.encode(project.metadata),
                catalogJson = CapabilityCatalogCodec.encode(project.capabilityCatalog),
                drivetrainJson = project.drivetrain?.let { listOf(com.areslib.drivetrain.DrivetrainDocumentCodec.encode(it.document)) }.orEmpty(),
                subsystemJson = project.subsystems.map { com.areslib.subsystem.SubsystemDocumentCodec.encode(it.document) },
                superstructureJson = project.superstructures.map { com.areslib.superstructure.SuperstructureDocumentCodec.encode(it.document) },
                controllerProfileJson = project.controllerProfiles.map { ControllerProfileCodec.encode(it.document) },
                controlSchemeJson = project.controlSchemes.map { ControlSchemeCodec.encode(it.document) },
                routineJson = project.routines.map { AresRoutineCodec.encode(it.document) },
                autonomousCatalogJson = project.autonomousCatalog?.let(AutonomousCatalogCodec::encode),
            )
        )
        return RenderedKotlinArtifact(
            ProjectArtifactPlan(
                ProjectArtifactId("project.verification"),
                joinPath(relativePathPrefix, generated.relativePath),
                ProjectArtifactSourceSet.TEST,
                ProjectArtifactOwnership.GENERATED_DO_NOT_EDIT,
                ProjectArtifactKind.PROJECT_VERIFICATION,
                "Generated project identity, autonomous, controls, drivetrain, and superstructure contracts.",
            ),
            generated.content,
        )
    }
}

/** Subsystem responsibility renderer and ownership planner. */
object SubsystemKotlinArtifactRenderer {
    data class Result(
        val artifacts: List<RenderedKotlinArtifact>,
        internal val generatedFiles: List<GeneratedSubsystemFile>,
    )

    fun render(
        project: RobotProjectIr,
        basePackage: String,
        starterPrefix: String,
        generatedMainPrefix: String,
        generatedTestPrefix: String,
    ): Result {
        val platform = when (project.inputPlatform) {
            com.areslib.controls.ControllerInputPlatform.FTC -> SubsystemPlatform.FTC
            com.areslib.controls.ControllerInputPlatform.FRC -> SubsystemPlatform.FRC
            com.areslib.controls.ControllerInputPlatform.DESKTOP_GLFW -> error("Robot compiler IR cannot target desktop input")
        }
        val target = SubsystemKotlinCodegenTarget(platform, basePackage)
        val documents = project.subsystems.map { it.document }
        val files = documents.filter { it.implementation.kind.isAresGenerated() }
            .flatMap { SubsystemKotlinGenerator.generate(it, target) } +
            SubsystemKotlinGenerator.generateRegistry(documents, target)
        val duplicates = files.groupBy { it.sourceSet to it.relativePath }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Generated subsystem paths collide: ${duplicates.joinToString()}" }
        val artifacts = files.map { file ->
            val prefix = when {
                file.ownership == SubsystemArtifactOwnership.GENERATED_STARTER ||
                    file.ownership == SubsystemArtifactOwnership.USER_OWNED -> starterPrefix
                file.sourceSet == GeneratedSubsystemSourceSet.TEST -> generatedTestPrefix
                else -> generatedMainPrefix
            }
            RenderedKotlinArtifact(
                ProjectArtifactPlan(
                    id = ProjectArtifactId("subsystem.${file.artifact.name.lowercase()}.${artifactSuffix(file.relativePath)}"),
                    relativePath = joinPath(prefix, file.relativePath),
                    sourceSet = if (file.sourceSet == GeneratedSubsystemSourceSet.TEST) {
                        ProjectArtifactSourceSet.TEST
                    } else {
                        ProjectArtifactSourceSet.MAIN
                    },
                    ownership = when (file.ownership) {
                        SubsystemArtifactOwnership.USER_OWNED -> ProjectArtifactOwnership.USER_OWNED
                        SubsystemArtifactOwnership.GENERATED_STARTER -> ProjectArtifactOwnership.GENERATED_STARTER
                        SubsystemArtifactOwnership.GENERATED_DO_NOT_EDIT -> ProjectArtifactOwnership.GENERATED_DO_NOT_EDIT
                    },
                    kind = when (file.group) {
                        SubsystemArtifactGroup.DOMAIN -> ProjectArtifactKind.SUBSYSTEM_DOMAIN
                        SubsystemArtifactGroup.CONTROL -> ProjectArtifactKind.SUBSYSTEM_CONTROL
                        SubsystemArtifactGroup.HARDWARE -> ProjectArtifactKind.SUBSYSTEM_HARDWARE
                        SubsystemArtifactGroup.SIMULATION -> ProjectArtifactKind.SUBSYSTEM_SIMULATION
                        SubsystemArtifactGroup.GENERATED_PLUMBING -> ProjectArtifactKind.SUBSYSTEM_PLUMBING
                        SubsystemArtifactGroup.VERIFICATION -> ProjectArtifactKind.SUBSYSTEM_VERIFICATION
                    },
                    description = file.description,
                ),
                content = file.content,
            )
        }
        return Result(artifacts, files)
    }
}

/** Drivetrain, target runtime, and project tuning renderer. */
object DrivebaseKotlinArtifactRenderer {
    fun render(
        project: RobotProjectIr,
        relativePathPrefix: String,
        packageName: String,
        includeFtcZeroCodeRuntime: Boolean,
    ): List<RenderedKotlinArtifact> {
        val drivetrain = project.drivetrain?.document
        if (drivetrain == null && project.tuningDeclarations.isEmpty()) return emptyList()
        val profiles = project.tuningProfiles
        val files = if (drivetrain != null) {
            val projectUid = requireNotNull(profiles.firstOrNull()?.projectUid) {
                "Drivebase '${drivetrain.uid}' requires a checked-in canonical tuning profile"
            }
            val additional = project.tuningDeclarations.filterNot { candidate ->
                drivetrain.parameters.any { it.uid == candidate.uid }
            }
            buildList {
                add(DrivetrainKotlinGenerator.generate(drivetrain, profiles, packageName, additional))
                add(
                    DrivetrainKotlinGenerator.generateProjectTuning(
                        projectUid,
                        drivetrain.canonicalProfileUid,
                        drivetrain.uid,
                        project.tuningDeclarations,
                        profiles,
                        packageName,
                    )
                )
                if (includeFtcZeroCodeRuntime) {
                    require(project.inputPlatform == com.areslib.controls.ControllerInputPlatform.FTC) {
                        "FTC zero-code runtime can only be rendered for an FTC compiler target"
                    }
                    add(DrivetrainKotlinGenerator.generateFtcMecanumRuntime(drivetrain, profiles, packageName, additional))
                }
            }
        } else {
            val canonical = profiles.singleOrNull { it.baseProfileUid == null }
                ?: error("A project without a drivebase requires exactly one root canonical tuning profile")
            listOf(
                DrivetrainKotlinGenerator.generateProjectTuning(
                    canonical.projectUid,
                    canonical.uid,
                    null,
                    project.tuningDeclarations,
                    profiles,
                    packageName,
                )
            )
        }
        return files.map { file ->
            val kind = when (file.relativePath) {
                "GeneratedAresTuningConfig.kt" -> ProjectArtifactKind.TUNING_CONFIG
                "GeneratedAresFtcMecanumRuntimeConfig.kt" -> ProjectArtifactKind.DRIVEBASE_RUNTIME
                else -> ProjectArtifactKind.DRIVEBASE_CONFIG
            }
            RenderedKotlinArtifact(
                ProjectArtifactPlan(
                    ProjectArtifactId("drivebase.${file.relativePath.removeSuffix(".kt").camelToKebab()}"),
                    joinPath(relativePathPrefix, file.relativePath),
                    ProjectArtifactSourceSet.MAIN,
                    ProjectArtifactOwnership.GENERATED_DO_NOT_EDIT,
                    kind,
                    when (kind) {
                        ProjectArtifactKind.TUNING_CONFIG -> "Generated typed tuning declarations and canonical profile values."
                        ProjectArtifactKind.DRIVEBASE_RUNTIME -> "Generated FTC zero-code drivetrain runtime configuration."
                        else -> "Generated drivetrain geometry, localization, and control configuration."
                    },
                ),
                file.content,
            )
        }
    }
}

/** Superstructure runtime and registry renderer. */
object SuperstructureKotlinArtifactRenderer {
    fun render(
        project: RobotProjectIr,
        relativePathPrefix: String,
        packageName: String,
        subsystemRegistryFqn: String,
    ): List<RenderedKotlinArtifact> {
        val documents = project.superstructures.map { it.document }
        val subsystemDocuments = project.subsystems.map { it.document }
        val actionKeys = project.actions.mapTo(linkedSetOf()) { it.key.value }
        val parameterless = project.actions.filter { it.descriptor.parameters.isEmpty() }
            .mapTo(linkedSetOf()) { it.key.value }
        val files = documents.map { document ->
            SuperstructureKotlinGenerator.generate(
                document,
                packageName,
                subsystemRegistryFqn,
                subsystemDocuments,
                actionKeys,
                parameterless,
            )
        } + SuperstructureKotlinGenerator.generateRegistry(documents, packageName)
        return files.map { file ->
            val isRegistry = file.relativePath == "GeneratedSuperstructureRegistry.kt"
            RenderedKotlinArtifact(
                ProjectArtifactPlan(
                    ProjectArtifactId(
                        if (isRegistry) "superstructure.registry" else "superstructure.runtime.${artifactSuffix(file.relativePath)}"
                    ),
                    joinPath(relativePathPrefix, file.relativePath),
                    ProjectArtifactSourceSet.MAIN,
                    ProjectArtifactOwnership.GENERATED_DO_NOT_EDIT,
                    if (isRegistry) ProjectArtifactKind.SUPERSTRUCTURE_REGISTRY else ProjectArtifactKind.SUPERSTRUCTURE_RUNTIME,
                    file.description,
                ),
                file.content,
            )
        }
    }
}

private fun joinPath(prefix: String, path: String): String =
    listOf(prefix.trim('/').trim('\\'), path.trim('/').trim('\\'))
        .filter(String::isNotEmpty)
        .joinToString("/")

private fun artifactSuffix(path: String): String = path.removeSuffix(".kt")
    .replace(Regex("[^A-Za-z0-9]+"), "-")
    .trim('-')
    .lowercase()

private fun String.camelToKebab(): String = replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()
