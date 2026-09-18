package com.areslib.codegen

import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileCodec
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.drivetrain.DrivetrainPlatform
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.model.ProjectModelSeverity
import com.areslib.project.model.RobotProjectAssembler
import com.areslib.project.model.RobotProjectSnapshot
import com.areslib.project.compiler.ProjectVerificationManifestBuilder
import com.areslib.project.compiler.ProjectVerificationManifestCodec
import com.areslib.project.compiler.ProjectArtifactOwnership
import com.areslib.project.compiler.RobotProjectCompiler
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.superstructure.SuperstructureDocumentCodec
import com.areslib.superstructure.TransitionTriggerKind
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.isAresGenerated
import com.areslib.tuning.TuningProfileAuthority
import com.areslib.tuning.TuningComponentDocumentCodec
import com.areslib.tuning.TuningProfileDocumentCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Build-time entry point used by FTC/FRC Gradle tasks and the Analytics Generate button. */
object AresProjectCodegenCli {
    @JvmStatic
    fun main(args: Array<String>) {
        run(args)
    }

    fun run(args: Array<String>): GeneratedKotlinSource {
        val options = AresProjectCodegenOptions.parse(args)
        val projectRoot = options.project.toRealPath()
        val aresRoot = projectRoot.resolve(".ares")
        require(Files.isDirectory(aresRoot)) { "Missing project directory: $aresRoot" }
        val output = options.output.toAbsolutePath().normalize()
        GeneratedOutputPaths.requireWithin(projectRoot, output)

        val metadata = AresProjectMetadataCodec.decode(readRequired(aresRoot.resolve("project.json")))
        val baseCatalog = CapabilityCatalogCodec.decode(readRequired(aresRoot.resolve("action-catalog.json")))
        val routines = readDocuments(aresRoot.resolve("routines"), "aresroutine") { AresRoutineCodec.decode(it) }
        val controls = readDocuments(aresRoot.resolve("controls"), "arescontrols") { ControlSchemeCodec.decode(it) }
        val profiles = readDocuments(aresRoot.resolve("controllers"), "arescontroller") { ControllerProfileCodec.decode(it) }
        val autonomousCatalog = aresRoot.resolve("autonomous-catalog.json").takeIf(Files::isRegularFile)
            ?.let { AutonomousCatalogCodec.decode(Files.readString(it)) }
        val subsystems = readDocuments(aresRoot.resolve("subsystems"), "aressubsystem") {
            SubsystemDocumentCodec.decode(it)
        }
        val superstructures = readDocuments(aresRoot.resolve("superstructures"), "aressuperstructure") {
            SuperstructureDocumentCodec.decode(it)
        }
        val drivetrains = readDocuments(aresRoot.resolve("drivetrains"), "aresdrivetrain") {
            DrivetrainDocumentCodec.decode(it)
        }
        require(drivetrains.size <= 1) { "A robot project may declare at most one drivebase contract" }
        validateDrivebaseCodegenPlatform(drivetrains.singleOrNull()?.platform, options.platform)
        val tuningComponents = readDocuments(aresRoot.resolve("tuning-components"), "arestuningcomponent") {
            TuningComponentDocumentCodec.decode(it)
        }
        val declarations = drivetrains.flatMap { it.parameters } +
            subsystems.flatMap { it.tuningParameters } + tuningComponents.flatMap { it.parameters }
        require(declarations.map { it.uid }.distinct().size == declarations.size) {
            "Typed tuning parameter UIDs must be unique across drivebase, subsystem, and project components"
        }
        require(declarations.map { it.key }.distinct().size == declarations.size) {
            "Typed tuning parameter keys must be unique across drivebase, subsystem, and project components"
        }
        // A canonical profile also establishes project/drivebase ownership. It remains required
        // for a zero-parameter drivebase, even though there are no assignments to decode.
        val tuningProfiles = if (declarations.isNotEmpty() || drivetrains.isNotEmpty()) {
            readDocuments(aresRoot.resolve("tuning"), "arestuning") {
                TuningProfileDocumentCodec.decode(it, declarations)
            }.also { loadedProfiles ->
                require(loadedProfiles.all { it.authority == TuningProfileAuthority.CANONICAL_CHECKED_IN }) {
                    "Build generation accepts only CANONICAL_CHECKED_IN profiles from .ares/tuning; local overlays belong in .ares/local/tuning"
                }
            }
        } else emptyList()
        val effectiveProject = RobotProjectAssembler.assemble(
            RobotProjectSnapshot(
                projectRoot = projectRoot.toString(),
                metadata = metadata,
                baseCapabilityCatalog = baseCatalog,
                autonomousCatalog = autonomousCatalog,
                routines = routines,
                controlSchemes = controls,
                controllerProfiles = profiles,
                subsystems = subsystems,
                superstructures = superstructures,
                drivetrains = drivetrains,
                tuningComponents = tuningComponents,
                tuningProfiles = tuningProfiles,
            ),
            inputPlatform = options.platform,
        )
        val modelErrors = effectiveProject.issues.filter { it.severity == ProjectModelSeverity.ERROR }
        require(modelErrors.isEmpty()) {
            "ARES project model is invalid: " + modelErrors.joinToString("; ") {
                "${it.kind.name.lowercase()}:${it.documentId.orEmpty()}:${it.path}: ${it.message}"
            }
        }
        val compilerIr = RobotProjectCompiler.lower(effectiveProject, options.platform)
        val superstructureActionOwners = superstructures.flatMap { document ->
            document.transitions.filter { it.triggerKind == TransitionTriggerKind.ACTION_REQUEST }
                .mapNotNull { transition -> transition.actionKey?.let { it to document.superstructureId } }
        }
        val multiplyOwnedAction = superstructureActionOwners.groupBy(Pair<String, String>::first)
            .entries.firstOrNull { (_, owners) -> owners.map { it.second }.distinct().size > 1 }
        require(multiplyOwnedAction == null) {
            "Superstructure action '${multiplyOwnedAction?.key}' is owned by multiple state machines: " +
                multiplyOwnedAction?.value.orEmpty().map { it.second }.distinct().sorted().joinToString()
        }
        val superstructureActionKeys = superstructureActionOwners.map(Pair<String, String>::first).distinct()
        val superstructurePackage = options.superstructurePackage
            ?: options.subsystemsPackage?.let { "$it.superstructure" }
            ?: options.packageName
        val superstructureActionBindings = superstructureActionKeys.associateWith {
            "$superstructurePackage.GeneratedSuperstructureRegistry"
        }

        val (projectRuntimeArtifact, generated) = ProjectRuntimeKotlinRenderer.render(
            project = compilerIr,
            relativePath = projectRelativePath(projectRoot, output),
            packageName = options.packageName,
            objectName = options.objectName,
            registryInterfaceName = options.registryInterfaceName,
            subsystemRegistryFqn = options.subsystemsPackage?.let { "$it.GeneratedSubsystemRegistry" },
            generatedActionRegistryBindings = superstructureActionBindings,
        )
        val projectVerificationArtifact = options.subsystemsGeneratedTestOutput?.let { testRoot ->
            ProjectVerificationKotlinRenderer.render(
                compilerIr,
                projectRelativePath(projectRoot, testRoot.toAbsolutePath().normalize()),
                options.packageName,
            )
        }

        if (options.previewSubsystemStarters) {
            syncSubsystemSources(projectRoot, compilerIr, options, projectVerificationArtifact)
            return generated
        }

        if (options.subsystemsOnly) {
            // The caller requested only subsystem reconciliation/materialization. Full project
            // plumbing is recreated by the ordinary generation/verification task.
        } else if (options.checkOnly) {
            require(Files.isRegularFile(output)) {
                "Generated source is missing at $output. Run the ARES generation task."
            }
            val current = Files.readString(output)
            require(current == generated.source && AresKotlinProjectGenerator.hasValidEmbeddedSourceHash(current)) {
                "Generated source is stale at $output. Regenerate it before building."
            }
        } else {
            val exists = Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            require(!exists || Files.isRegularFile(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                "Generated runtime output collides with a non-file at $output"
            }
            val current = if (exists) Files.readString(output) else null
            require(current == null || current == generated.source || ownsProjectRuntime(current)) {
                "Refusing to overwrite protected or unowned project runtime at $output"
            }
            if (current != generated.source) GeneratedFileWriter.writeAtomically(output, generated.source)
        }
        val renderedArtifacts = buildList {
            add(projectRuntimeArtifact)
            addAll(syncSubsystemSources(projectRoot, compilerIr, options, projectVerificationArtifact))
            if (!options.subsystemsOnly) {
                addAll(syncDrivebaseSources(projectRoot, compilerIr, options))
                addAll(syncSuperstructureSources(projectRoot, compilerIr, options))
            }
        }
        if (!options.subsystemsOnly) {
            syncVerificationManifest(projectRoot, compilerIr, renderedArtifacts, options)
        }
        return generated
    }

    private fun syncDrivebaseSources(
        projectRoot: Path,
        project: com.areslib.project.compiler.RobotProjectIr,
        options: AresProjectCodegenOptions,
    ): List<RenderedKotlinArtifact> {
        if (project.drivetrain == null && project.tuningDeclarations.isEmpty() && options.drivebaseOutput == null) {
            return emptyList()
        }
        val root = requireNotNull(options.drivebaseOutput) {
            "--drivebase-output is required when drivetrain or typed tuning documents exist"
        }.toAbsolutePath().normalize()
        GeneratedOutputPaths.requireWithin(projectRoot, root)
        val packageName = requireNotNull(options.drivebasePackage) {
            "--drivebase-package is required when generating drivebase or typed tuning plumbing"
        }
        val artifacts = DrivebaseKotlinArtifactRenderer.render(
            project,
            projectRelativePath(projectRoot, root),
            packageName,
            options.ftcZeroCodeRuntime,
        )
        val prefix = projectRelativePath(projectRoot, root)
        val expected = artifacts.associate { relativeToPrefix(prefix, it.plan.relativePath) to it.content }
        GeneratedSourceSetSynchronizer.sync(root, expected, options.checkOnly, ".ares-drivebase-manifest")
        return artifacts
    }

    private fun syncSuperstructureSources(
        projectRoot: Path,
        project: com.areslib.project.compiler.RobotProjectIr,
        options: AresProjectCodegenOptions,
    ): List<RenderedKotlinArtifact> {
        if (project.superstructures.isEmpty() && options.superstructureOutput == null &&
            options.subsystemsGeneratedOutput == null
        ) return emptyList()
        val root = (options.superstructureOutput ?: options.subsystemsGeneratedOutput?.resolve("superstructure"))
            ?.toAbsolutePath()?.normalize() ?: return emptyList()
        GeneratedOutputPaths.requireWithin(projectRoot, root)
        val packageName = options.superstructurePackage
            ?: options.subsystemsPackage?.let { "$it.superstructure" }
            ?: options.packageName
        val subsystemRegistryFqn = requireNotNull(options.subsystemsPackage) {
            "--subsystems-package is required when superstructure documents exist"
        } + ".GeneratedSubsystemRegistry"
        val prefix = projectRelativePath(projectRoot, root)
        val artifacts = SuperstructureKotlinArtifactRenderer.render(
            project,
            prefix,
            packageName,
            subsystemRegistryFqn,
        )
        val expected = artifacts.associate { relativeToPrefix(prefix, it.plan.relativePath) to it.content }
        GeneratedSourceSetSynchronizer.sync(root, expected, options.checkOnly, ".ares-superstructure-manifest")
        return artifacts
    }

    private fun syncSubsystemSources(
        projectRoot: Path,
        project: com.areslib.project.compiler.RobotProjectIr,
        options: AresProjectCodegenOptions,
        projectVerification: RenderedKotlinArtifact?,
    ): List<RenderedKotlinArtifact> {
        if (project.subsystems.isEmpty() &&
            options.subsystemsGeneratedOutput == null && options.subsystemsGeneratedTestOutput == null &&
            options.subsystemsStarterOutput == null
        ) return emptyList()
        val basePackage = requireNotNull(options.subsystemsPackage) {
            "--subsystems-package is required when generating subsystem sources"
        }
        val starterRoot = requireNotNull(options.subsystemsStarterOutput) {
            "--subsystems-starter-output is required when generating subsystem sources"
        }.toAbsolutePath().normalize()
        val generatedRoot = requireNotNull(options.subsystemsGeneratedOutput) {
            "--subsystems-generated-output is required when generating subsystem sources"
        }.toAbsolutePath().normalize()
        val generatedTestRoot = requireNotNull(options.subsystemsGeneratedTestOutput) {
            "--subsystems-generated-test-output is required when generating subsystem sources"
        }.toAbsolutePath().normalize()
        for (root in listOf(starterRoot, generatedRoot, generatedTestRoot)) {
            GeneratedOutputPaths.requireWithin(projectRoot, root)
        }
        val rendered = SubsystemKotlinArtifactRenderer.render(
            project,
            basePackage,
            projectRelativePath(projectRoot, starterRoot),
            projectRelativePath(projectRoot, generatedRoot),
            projectRelativePath(projectRoot, generatedTestRoot),
        )
        val artifacts = rendered.artifacts
        val files = rendered.generatedFiles
        val plan = SubsystemStarterReconciler.plan(starterRoot, files)
        if (options.previewSubsystemStarters) {
            println(plan.render())
            return artifacts
        }
        if (options.applySubsystemStarters) {
            println(SubsystemStarterReconciler.apply(starterRoot, files, options.subsystemConfirmationToken).render())
        } else {
            SubsystemStarterReconciler.requirePresent(starterRoot, files)
        }
        syncSourceSet(
            generatedRoot,
            files.filter {
                it.sourceSet == GeneratedSubsystemSourceSet.MAIN &&
                    it.ownership == SubsystemArtifactOwnership.GENERATED_DO_NOT_EDIT
            }.associate { it.relativePath to it.content },
            options.checkOnly,
        )
        val generatedTests = files.filter {
            it.sourceSet == GeneratedSubsystemSourceSet.TEST &&
                it.ownership == SubsystemArtifactOwnership.GENERATED_DO_NOT_EDIT
        }.associate { it.relativePath to it.content }.toMutableMap()
        projectVerification?.let {
            generatedTests[relativeToPrefix(
                projectRelativePath(projectRoot, generatedTestRoot),
                it.plan.relativePath,
            )] = it.content
        }
        syncSourceSet(generatedTestRoot, generatedTests, options.checkOnly)
        return artifacts + listOfNotNull(projectVerification)
    }

    private fun syncSourceSet(root: Path, expected: Map<String, String>, checkOnly: Boolean) {
        GeneratedSourceSetSynchronizer.sync(root, expected, checkOnly, ".ares-subsystems-manifest")
    }

    private fun projectRelativePath(projectRoot: Path, path: Path): String {
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val normalizedPath = path.toAbsolutePath().normalize()
        GeneratedOutputPaths.requireWithin(normalizedRoot, normalizedPath)
        return normalizedRoot.relativize(normalizedPath).toString().replace('\\', '/')
    }

    private fun relativeToPrefix(prefix: String, fullPath: String): String {
        val normalizedPrefix = prefix.replace('\\', '/').trim('/')
        val normalizedPath = fullPath.replace('\\', '/').trim('/')
        require(normalizedPath.startsWith("$normalizedPrefix/") || normalizedPath == normalizedPrefix) {
            "Generated artifact '$fullPath' is outside '$prefix'"
        }
        return normalizedPath.removePrefix(normalizedPrefix).trimStart('/')
    }

    private fun syncVerificationManifest(
        projectRoot: Path,
        project: com.areslib.project.compiler.RobotProjectIr,
        artifacts: List<RenderedKotlinArtifact>,
        options: AresProjectCodegenOptions,
    ) {
        val manifestPath = (options.verificationManifestOutput
            ?: projectRoot.resolve("build/generated/ares/verification/ares-project-verification.json"))
            .toAbsolutePath().normalize()
        GeneratedOutputPaths.requireWithin(projectRoot, manifestPath)
        val ownedArtifacts = artifacts.filter { it.plan.ownership == ProjectArtifactOwnership.GENERATED_DO_NOT_EDIT }
        require(artifacts.none { projectRoot.resolve(it.plan.relativePath).normalize() == manifestPath }) {
            "Verification manifest collides with a generated artifact at $manifestPath"
        }
        val uniqueArtifacts = ownedArtifacts.distinctBy { it.plan.relativePath.replace('\\', '/') }
        require(uniqueArtifacts.size == ownedArtifacts.size) { "Generated artifact paths must be unique" }
        val content = ProjectVerificationManifestCodec.encode(
            ProjectVerificationManifestBuilder.build(project, uniqueArtifacts.map(RenderedKotlinArtifact::manifestEntry))
        )
        if (options.checkOnly) {
            require(Files.isRegularFile(manifestPath) && Files.readString(manifestPath) == content) {
                "Generated verification manifest is stale at $manifestPath. Run the ARES generation task."
            }
        } else {
            val exists = Files.exists(manifestPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            require(!exists || Files.isRegularFile(manifestPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                "Verification manifest collides with a non-file at $manifestPath"
            }
            val current = if (exists) Files.readString(manifestPath) else null
            require(current == null || current == content || ownsVerificationManifest(current)) {
                "Refusing to overwrite protected or unowned verification manifest at $manifestPath"
            }
            if (current != content) GeneratedFileWriter.writeAtomically(manifestPath, content)
        }
    }

    /** Recognizes canonical generated manifest bytes and their embedded digest without changing its schema. */
    private fun ownsVerificationManifest(content: String): Boolean {
        val normalized = content.replace("\r\n", "\n").trimEnd() + "\n"
        if (!normalized.startsWith("{\n  \"schemaVersion\": 2,\n  \"compilerIrVersion\": ") ||
            "\n  \"canonicalProjectSha256\": " !in normalized || "\n  \"artifacts\": [" !in normalized
        ) return false
        val hashLine = VERIFICATION_MANIFEST_HASH.find(normalized) ?: return false
        val unsigned = normalized.replaceRange(hashLine.range, "\n}\n")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(unsigned.toByteArray(Charsets.UTF_8)).toHex()
        return digest == hashLine.groupValues[1]
    }

    private val VERIFICATION_MANIFEST_HASH = Regex(",\\n  \"manifestSha256\": \"([a-f0-9]{64})\"\\n}\\n$")

    private fun ownsProjectRuntime(content: String): Boolean =
        content.startsWith("@file:Suppress(\"MagicNumber\", \"LongMethod\")") &&
            content.contains("/** Generated from the project's canonical ARES documents. Do not edit by hand. */") &&
            content.contains("    const val SOURCE_SHA256: String = ")

    private fun readRequired(path: Path): String {
        require(path.isRegularFile()) { "Required ARES project file is missing: $path" }
        return Files.readString(path)
    }

    private fun <T> readDocuments(directory: Path, extension: String, decode: (String) -> T): List<T> {
        if (!Files.isDirectory(directory)) return emptyList()
        val paths = Files.list(directory).use { stream ->
            stream.filter { it.isRegularFile() && it.extension.equals(extension, ignoreCase = true) }
                .sorted(compareBy<Path> { it.name.lowercase() }.thenBy { it.name })
                .toList()
        }
        return paths.map { path ->
            runCatching { decode(Files.readString(path)) }.getOrElse { error ->
                throw IllegalArgumentException("Could not read ${path.fileName}: ${error.message}", error)
            }
        }
    }


}

/** Fails before any generated source is written when a drivebase targets another robot platform. */
internal fun validateDrivebaseCodegenPlatform(
    declaredPlatform: DrivetrainPlatform?,
    requestedPlatform: ControllerInputPlatform?,
) {
    if (declaredPlatform == null) return
    val targetPlatform = when (requestedPlatform) {
        ControllerInputPlatform.FTC -> DrivetrainPlatform.FTC
        ControllerInputPlatform.FRC -> DrivetrainPlatform.FRC
        ControllerInputPlatform.XRP -> DrivetrainPlatform.XRP
        ControllerInputPlatform.DESKTOP_GLFW, null ->
            error("Drivebase generation requires --platform FTC, FRC, or XRP")
    }
    require(declaredPlatform == targetPlatform) {
        "Drivebase targets $declaredPlatform, not requested codegen platform $targetPlatform"
    }
}
