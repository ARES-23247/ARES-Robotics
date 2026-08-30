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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
        val options = CliOptions.parse(args)
        val projectRoot = options.project.toRealPath()
        val aresRoot = projectRoot.resolve(".ares")
        require(Files.isDirectory(aresRoot)) { "Missing project directory: $aresRoot" }
        val output = options.output.toAbsolutePath().normalize()
        require(output.startsWith(projectRoot)) { "Generated output must stay inside the selected project" }

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
        } else if (!Files.isRegularFile(output) || Files.readString(output) != generated.source) {
            writeAtomically(output, generated.source)
        }
        val renderedArtifacts = buildList {
            add(projectRuntimeArtifact)
            addAll(syncSubsystemSources(projectRoot, compilerIr, options, projectVerificationArtifact))
            addAll(syncDrivebaseSources(projectRoot, compilerIr, options))
            addAll(syncSuperstructureSources(projectRoot, compilerIr, options))
        }
        if (!options.subsystemsOnly) {
            syncVerificationManifest(projectRoot, compilerIr, renderedArtifacts, options)
        }
        return generated
    }

    private fun syncDrivebaseSources(
        projectRoot: Path,
        project: com.areslib.project.compiler.RobotProjectIr,
        options: CliOptions,
    ): List<RenderedKotlinArtifact> {
        if (project.drivetrain == null && project.tuningDeclarations.isEmpty() && options.drivebaseOutput == null) {
            return emptyList()
        }
        val root = requireNotNull(options.drivebaseOutput) {
            "--drivebase-output is required when drivetrain or typed tuning documents exist"
        }.toAbsolutePath().normalize()
        require(root.startsWith(projectRoot)) { "Generated drivebase output must stay inside the selected project" }
        val packageName = requireNotNull(options.drivebasePackage) {
            "--drivebase-package is required when generating drivebase or typed tuning plumbing"
        }
        val artifacts = DrivebaseKotlinArtifactRenderer.render(
            project,
            projectRelativePath(projectRoot, root),
            packageName,
            options.ftcZeroCodeRuntime,
        )
        val manifest = root.resolve(".ares-drivebase-manifest")
        val prefix = projectRelativePath(projectRoot, root)
        val expected = artifacts.associate { relativeToPrefix(prefix, it.plan.relativePath) to it.content }
        val expectedManifest = expected.keys.sorted().joinToString("\n", postfix = if (expected.isEmpty()) "" else "\n")
        if (options.checkOnly) {
            val actual = if (Files.isRegularFile(manifest)) Files.readString(manifest) else ""
            require(actual == expectedManifest) { "Generated drivebase file list is stale at $root" }
            expected.forEach { (relative, content) ->
                val path = safeGeneratedPath(root, relative)
                require(Files.isRegularFile(path) && Files.readString(path) == content) {
                    "Generated drivebase source is stale at $path"
                }
            }
            return artifacts
        }
        val previous = if (Files.isRegularFile(manifest)) Files.readAllLines(manifest).filter(String::isNotBlank) else emptyList()
        previous.filterNot(expected::containsKey).forEach { Files.deleteIfExists(safeGeneratedPath(root, it)) }
        expected.forEach { (relative, content) ->
            val path = safeGeneratedPath(root, relative)
            val exists = Files.exists(path)
            require(!exists || Files.isRegularFile(path)) { "Generated drivebase output collides with a non-file at $path" }
            require(!exists || relative in previous || Files.readString(path) == content) {
                "Refusing to overwrite unowned drivebase output at $path; remove or relocate it explicitly"
            }
            if (!exists || Files.readString(path) != content) writeAtomically(path, content)
        }
        if (expected.isEmpty()) Files.deleteIfExists(manifest) else writeAtomically(manifest, expectedManifest)
        return artifacts
    }

    private fun syncSuperstructureSources(
        projectRoot: Path,
        project: com.areslib.project.compiler.RobotProjectIr,
        options: CliOptions,
    ): List<RenderedKotlinArtifact> {
        if (project.superstructures.isEmpty() && options.superstructureOutput == null &&
            options.subsystemsGeneratedOutput == null
        ) return emptyList()
        val root = (options.superstructureOutput ?: options.subsystemsGeneratedOutput?.resolve("superstructure"))
            ?.toAbsolutePath()?.normalize() ?: return emptyList()
        require(root.startsWith(projectRoot)) { "Generated superstructure output must stay inside the selected project" }
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
        val manifest = root.resolve(".ares-superstructure-manifest")
        val expected = artifacts.associate { relativeToPrefix(prefix, it.plan.relativePath) to it.content }
        val expectedManifest = expected.keys.sorted().joinToString("\n", postfix = if (expected.isEmpty()) "" else "\n")
        if (options.checkOnly) {
            val actual = if (Files.isRegularFile(manifest)) Files.readString(manifest) else ""
            require(actual == expectedManifest) { "Generated superstructure file list is stale at $root" }
            expected.forEach { (relative, content) ->
                val path = safeGeneratedPath(root, relative)
                require(Files.isRegularFile(path) && Files.readString(path) == content) {
                    "Generated superstructure source is stale at $path"
                }
            }
            return artifacts
        }
        val previous = if (Files.isRegularFile(manifest)) Files.readAllLines(manifest).filter(String::isNotBlank) else emptyList()
        previous.filterNot(expected::containsKey).forEach { Files.deleteIfExists(safeGeneratedPath(root, it)) }
        expected.forEach { (relative, content) ->
            val path = safeGeneratedPath(root, relative)
            val exists = Files.exists(path)
            require(!exists || Files.isRegularFile(path)) { "Generated superstructure output collides with a non-file at $path" }
            require(!exists || relative in previous || Files.readString(path) == content) {
                "Refusing to overwrite unowned superstructure output at $path; remove or relocate it explicitly"
            }
            if (!exists || Files.readString(path) != content) writeAtomically(path, content)
        }
        if (expected.isEmpty()) Files.deleteIfExists(manifest) else writeAtomically(manifest, expectedManifest)
        return artifacts
    }

    private fun syncSubsystemSources(
        projectRoot: Path,
        project: com.areslib.project.compiler.RobotProjectIr,
        options: CliOptions,
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
        require(starterRoot.startsWith(projectRoot) && generatedRoot.startsWith(projectRoot) &&
            generatedTestRoot.startsWith(projectRoot)
        ) { "Subsystem starter and generated outputs must stay inside the selected project" }
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
        val manifest = root.resolve(".ares-subsystems-manifest")
        val normalizedExpected = expected.entries.associate { (relativePath, content) ->
            relativePath.replace('\\', '/') to content
        }
        val expectedManifest = normalizedExpected.keys.sorted()
            .joinToString(separator = "\n", postfix = if (normalizedExpected.isEmpty()) "" else "\n")
        if (checkOnly) {
            require(Files.isRegularFile(manifest) || normalizedExpected.isEmpty()) {
                "Generated subsystem manifest is missing at $manifest"
            }
            val actualManifest = if (Files.isRegularFile(manifest)) Files.readString(manifest) else ""
            require(actualManifest == expectedManifest) {
                "Generated subsystem file list is stale at $root. Run the ARES generation task."
            }
            normalizedExpected.forEach { (relative, content) ->
                val path = safeGeneratedPath(root, relative)
                require(Files.isRegularFile(path) && Files.readString(path) == content) {
                    "Generated subsystem source is stale at $path. Run the ARES generation task."
                }
            }
            return
        }

        val previous = if (Files.isRegularFile(manifest)) Files.readAllLines(manifest).filter(String::isNotBlank) else emptyList()
        previous.filterNot(normalizedExpected::containsKey).forEach { relative ->
            Files.deleteIfExists(safeGeneratedPath(root, relative))
        }
        normalizedExpected.forEach { (relative, content) ->
            val path = safeGeneratedPath(root, relative)
            if (!Files.isRegularFile(path) || Files.readString(path) != content) writeAtomically(path, content)
        }
        if (normalizedExpected.isEmpty()) {
            Files.deleteIfExists(manifest)
        } else if (!Files.isRegularFile(manifest) || Files.readString(manifest) != expectedManifest) {
            writeAtomically(manifest, expectedManifest)
        }
    }

    private fun safeGeneratedPath(root: Path, relative: String): Path {
        val path = root.resolve(relative).normalize()
        require(path.startsWith(root) && relative.isNotBlank()) { "Invalid generated subsystem path '$relative'" }
        return path
    }

    private fun projectRelativePath(projectRoot: Path, path: Path): String {
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val normalizedPath = path.toAbsolutePath().normalize()
        require(normalizedPath.startsWith(normalizedRoot)) {
            "Generated artifact must stay inside the selected project: $normalizedPath"
        }
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
        options: CliOptions,
    ) {
        val manifestPath = (options.verificationManifestOutput
            ?: projectRoot.resolve("build/generated/ares/verification/ares-project-verification.json"))
            .toAbsolutePath().normalize()
        require(manifestPath.startsWith(projectRoot)) {
            "Generated verification manifest must stay inside the selected project"
        }
        val ownedArtifacts = artifacts.filter { it.plan.ownership == ProjectArtifactOwnership.GENERATED_DO_NOT_EDIT }
        val uniqueArtifacts = ownedArtifacts.distinctBy { it.plan.relativePath.replace('\\', '/') }
        require(uniqueArtifacts.size == ownedArtifacts.size) { "Generated artifact paths must be unique" }
        val content = ProjectVerificationManifestCodec.encode(
            ProjectVerificationManifestBuilder.build(project, uniqueArtifacts.map(RenderedKotlinArtifact::manifestEntry))
        )
        if (options.checkOnly) {
            require(Files.isRegularFile(manifestPath) && Files.readString(manifestPath) == content) {
                "Generated verification manifest is stale at $manifestPath. Run the ARES generation task."
            }
        } else if (!Files.isRegularFile(manifestPath) || Files.readString(manifestPath) != content) {
            writeAtomically(manifestPath, content)
        }
    }

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

    private fun writeAtomically(output: Path, content: String) {
        Files.createDirectories(output.parent)
        val temporary = Files.createTempFile(output.parent, ".${output.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, content)
            try {
                Files.move(
                    temporary,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private data class CliOptions(
        val project: Path,
        val output: Path,
        val packageName: String,
        val objectName: String,
        val registryInterfaceName: String,
        val platform: ControllerInputPlatform?,
        val subsystemsPackage: String?,
        val checkOnly: Boolean,
        val subsystemsOnly: Boolean,
        val previewSubsystemStarters: Boolean,
        val applySubsystemStarters: Boolean,
        val subsystemsStarterOutput: Path?,
        val subsystemsGeneratedOutput: Path?,
        val subsystemsGeneratedTestOutput: Path?,
        val subsystemConfirmationToken: String?,
        val drivebaseOutput: Path?,
        val drivebasePackage: String?,
        val ftcZeroCodeRuntime: Boolean,
        val superstructureOutput: Path?,
        val superstructurePackage: String?,
        val verificationManifestOutput: Path?,
    ) {
        companion object {
            fun parse(args: Array<String>): CliOptions {
                val values = linkedMapOf<String, String>()
                var checkOnly = false
                var subsystemsOnly = false
                var previewSubsystemStarters = false
                var applySubsystemStarters = false
                var ftcZeroCodeRuntime = false
                var index = 0
                while (index < args.size) {
                    val key = args[index]
                    if (key in FLAG_OPTIONS) {
                        when (key) {
                            "--check" -> checkOnly = true
                            "--subsystems-only" -> subsystemsOnly = true
                            "--preview-subsystem-starters" -> previewSubsystemStarters = true
                            "--apply-subsystem-starters" -> applySubsystemStarters = true
                            "--ftc-zero-code-runtime" -> ftcZeroCodeRuntime = true
                        }
                        index++
                        continue
                    }
                    require(key in VALUE_OPTIONS) { "Unknown ARES codegen option '$key'" }
                    require(index + 1 < args.size) { "Missing value after '$key'" }
                    require(values.put(key, args[index + 1]) == null) { "Option '$key' was supplied twice" }
                    index += 2
                }
                val project = Path.of(requireNotNull(values["--project"]) { "--project is required" })
                val output = Path.of(requireNotNull(values["--output"]) { "--output is required" })
                val packageName = requireNotNull(values["--package"]) { "--package is required" }
                val objectName = values["--object"] ?: "GeneratedAresProject"
                val registryName = values["--registry"] ?: "GeneratedAresProjectCapabilities"
                val platform = values["--platform"]?.let { raw ->
                    runCatching { ControllerInputPlatform.valueOf(raw.uppercase()) }
                        .getOrElse { throw IllegalArgumentException("Unknown input platform '$raw'") }
                }
                return CliOptions(
                    project,
                    output,
                    packageName,
                    objectName,
                    registryName,
                    platform,
                    values["--subsystems-package"],
                    checkOnly,
                    subsystemsOnly,
                    previewSubsystemStarters,
                    applySubsystemStarters,
                    values["--subsystems-starter-output"]?.let(Path::of),
                    values["--subsystems-generated-output"]?.let(Path::of),
                    values["--subsystems-generated-test-output"]?.let(Path::of),
                    values["--subsystems-confirmation-token"],
                    values["--drivebase-output"]?.let(Path::of),
                    values["--drivebase-package"],
                    ftcZeroCodeRuntime,
                    values["--superstructure-output"]?.let(Path::of),
                    values["--superstructure-package"],
                    values["--verification-manifest-output"]?.let(Path::of),
                )
            }

            private val VALUE_OPTIONS = setOf(
                "--project", "--output", "--package", "--object", "--registry", "--platform",
                "--subsystems-package", "--subsystems-starter-output", "--subsystems-generated-output",
                "--subsystems-generated-test-output", "--subsystems-confirmation-token",
                "--drivebase-output", "--drivebase-package",
                "--superstructure-output", "--superstructure-package",
                "--verification-manifest-output",
            )
            private val FLAG_OPTIONS = setOf(
                "--check", "--subsystems-only", "--preview-subsystem-starters", "--apply-subsystem-starters",
                "--ftc-zero-code-runtime",
            )
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
        ControllerInputPlatform.DESKTOP_GLFW, null ->
            error("Drivebase generation requires --platform FTC or FRC")
    }
    require(declaredPlatform == targetPlatform) {
        "Drivebase targets $declaredPlatform, not requested codegen platform $targetPlatform"
    }
}
