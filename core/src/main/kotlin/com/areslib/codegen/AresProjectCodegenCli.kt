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
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.superstructure.SuperstructureDocumentCodec
import com.areslib.superstructure.SuperstructureIssueSeverity
import com.areslib.superstructure.TransitionTriggerKind
import com.areslib.superstructure.validateSuperstructureProject
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.mergeSubsystemCapabilities
import com.areslib.subsystem.subsystemTargetCapabilities
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
        val tuningProfiles = if (declarations.isNotEmpty()) {
            readDocuments(aresRoot.resolve("tuning"), "arestuning") {
                TuningProfileDocumentCodec.decode(it, declarations)
            }.also { loadedProfiles ->
                require(loadedProfiles.all { it.authority == TuningProfileAuthority.CANONICAL_CHECKED_IN }) {
                    "Build generation accepts only CANONICAL_CHECKED_IN profiles from .ares/tuning; local overlays belong in .ares/local/tuning"
                }
            }
        } else emptyList()
        val subsystemActions = subsystemTargetCapabilities(subsystems)
        val catalog = mergeSubsystemCapabilities(baseCatalog, subsystems)
        val catalogActionKeys = catalog.actions.mapTo(linkedSetOf()) { it.key }
        val parameterlessActionKeys = catalog.actions.asSequence()
            .filter { it.parameters.isEmpty() }
            .mapTo(linkedSetOf()) { it.key }
        superstructures.forEach { document ->
            val errors = validateSuperstructureProject(document, subsystems, catalogActionKeys, parameterlessActionKeys)
                .filter { it.severity == SuperstructureIssueSeverity.ERROR }
            require(errors.isEmpty()) {
                "Superstructure '${document.superstructureId}' is invalid: " +
                    errors.joinToString("; ") { "${it.path}: ${it.message}" }
            }
        }
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

        val generated = AresKotlinProjectGenerator.generate(
            KotlinProjectCodegenRequest(
                packageName = options.packageName,
                objectName = options.objectName,
                registryInterfaceName = options.registryInterfaceName,
                catalog = catalog,
                routines = routines,
                autonomousCatalog = autonomousCatalog,
                controlSchemes = controls,
                controllerProfiles = profiles,
                targetInputPlatform = options.platform,
                projectMetadata = metadata,
                subsystemActions = subsystemActions,
                subsystemRegistryFqn = options.subsystemsPackage?.let { "$it.GeneratedSubsystemRegistry" },
                generatedActionRegistryBindings = superstructureActionBindings,
            )
        )

        if (options.subsystemsOnly) {
            // The caller requested only subsystem reconciliation/materialization. The canonical
            // project source remains untouched and retains its independent checked-in verification.
        } else if (options.checkOnly) {
            require(Files.isRegularFile(output)) {
                "Generated source is missing at $output. Run the ARES generation task."
            }
            val current = Files.readString(output)
            require(current == generated.source && AresKotlinProjectGenerator.hasValidEmbeddedSourceHash(current)) {
                "Generated source is stale at $output. Regenerate and commit it before building."
            }
        } else if (!Files.isRegularFile(output) || Files.readString(output) != generated.source) {
            writeAtomically(output, generated.source)
        }
        syncSubsystemSources(projectRoot, subsystems, options)
        syncDrivebaseSources(projectRoot, drivetrains, tuningProfiles, declarations, options)
        syncSuperstructureSources(
            projectRoot,
            superstructures,
            subsystems,
            catalogActionKeys,
            parameterlessActionKeys,
            options,
        )
        if (!options.subsystemsOnly) {
            FtcStarterContractMigration.reconcile(projectRoot, options.platform, options.checkOnly)
        }
        return generated
    }

    private fun syncDrivebaseSources(
        projectRoot: Path,
        drivetrains: List<com.areslib.drivetrain.DrivetrainDocument>,
        profiles: List<com.areslib.tuning.TuningProfileDocument>,
        declarations: List<com.areslib.tuning.TuningParameterDeclaration>,
        options: CliOptions,
    ) {
        if (drivetrains.isEmpty() && declarations.isEmpty() && options.drivebaseOutput == null) return
        val root = requireNotNull(options.drivebaseOutput) {
            "--drivebase-output is required when drivetrain or typed tuning documents exist"
        }.toAbsolutePath().normalize()
        require(root.startsWith(projectRoot)) { "Generated drivebase output must stay inside the selected project" }
        val files = drivetrains.singleOrNull()?.let { drivetrain ->
            val packageName = requireNotNull(options.drivebasePackage) {
                "--drivebase-package is required when generating drivebase plumbing"
            }
            val projectUid = requireNotNull(profiles.firstOrNull()?.projectUid) {
                "Drivebase '${drivetrain.uid}' requires at least one checked-in canonical profile in .ares/tuning"
            }
            val additional = declarations.filterNot { candidate -> drivetrain.parameters.any { it.uid == candidate.uid } }
            buildList {
                add(
                    DrivetrainKotlinGenerator.generate(drivetrain, profiles, packageName, additional),
                )
                add(
                    DrivetrainKotlinGenerator.generateProjectTuning(
                        projectUid = projectUid,
                        canonicalProfileUid = drivetrain.canonicalProfileUid,
                        drivebaseUid = drivetrain.uid,
                        declarations = declarations,
                        profiles = profiles,
                        packageName = packageName,
                    ),
                )
                if (options.ftcZeroCodeRuntime) {
                    require(options.platform == ControllerInputPlatform.FTC) {
                        "--ftc-zero-code-runtime requires --platform FTC"
                    }
                    add(
                        DrivetrainKotlinGenerator.generateFtcMecanumRuntime(
                            drivetrain,
                            profiles,
                            packageName,
                            additional,
                        ),
                    )
                }
            }
        } ?: if (declarations.isNotEmpty()) {
            val packageName = requireNotNull(options.drivebasePackage) {
                "--drivebase-package is required when generating typed tuning plumbing"
            }
            val canonicalProfile = profiles.singleOrNull { it.baseProfileUid == null }
                ?: error("A project without a drivebase requires exactly one root canonical tuning profile")
            listOf(
                DrivetrainKotlinGenerator.generateProjectTuning(
                    projectUid = canonicalProfile.projectUid,
                    canonicalProfileUid = canonicalProfile.uid,
                    drivebaseUid = null,
                    declarations = declarations,
                    profiles = profiles,
                    packageName = packageName,
                ),
            )
        } else emptyList()
        val manifest = root.resolve(".ares-drivebase-manifest")
        val expected = files.associate { it.relativePath to it.content }
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
            return
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
    }

    private fun syncSuperstructureSources(
        projectRoot: Path,
        superstructures: List<com.areslib.superstructure.SuperstructureDocument>,
        subsystems: List<com.areslib.subsystem.SubsystemDocument>,
        actionKeys: Set<String>,
        parameterlessActionKeys: Set<String>,
        options: CliOptions,
    ) {
        if (superstructures.isEmpty() && options.superstructureOutput == null &&
            options.subsystemsGeneratedOutput == null
        ) return
        val root = (options.superstructureOutput ?: options.subsystemsGeneratedOutput?.resolve("superstructure"))
            ?.toAbsolutePath()?.normalize() ?: return
        require(root.startsWith(projectRoot)) { "Generated superstructure output must stay inside the selected project" }
        val packageName = options.superstructurePackage
            ?: options.subsystemsPackage?.let { "$it.superstructure" }
            ?: options.packageName
        val subsystemRegistryFqn = requireNotNull(options.subsystemsPackage) {
            "--subsystems-package is required when superstructure documents exist"
        } + ".GeneratedSubsystemRegistry"
        val files = superstructures.map { superstructure ->
            SuperstructureKotlinGenerator.generate(
                document = superstructure,
                packageName = packageName,
                subsystemRegistryFqn = subsystemRegistryFqn,
                subsystems = subsystems,
                actionKeys = actionKeys,
                parameterlessActionKeys = parameterlessActionKeys,
            )
        } + SuperstructureKotlinGenerator.generateRegistry(superstructures, packageName)
        val manifest = root.resolve(".ares-superstructure-manifest")
        val expected = files.associate { it.relativePath to it.content }
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
            return
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
    }

    private fun syncSubsystemSources(
        projectRoot: Path,
        subsystems: List<com.areslib.subsystem.SubsystemDocument>,
        options: CliOptions,
    ) {
        if (subsystems.isEmpty() &&
            options.subsystemsGeneratedOutput == null && options.subsystemsGeneratedTestOutput == null &&
            options.subsystemsStarterOutput == null
        ) return
        val platform = when (options.platform) {
            ControllerInputPlatform.FTC -> SubsystemPlatform.FTC
            ControllerInputPlatform.FRC -> SubsystemPlatform.FRC
            ControllerInputPlatform.DESKTOP_GLFW, null -> error("Subsystem generation requires --platform FTC or FRC")
        }
        val basePackage = requireNotNull(options.subsystemsPackage) {
            "--subsystems-package is required when generating subsystem sources"
        }
        val target = SubsystemKotlinCodegenTarget(platform, basePackage)
        val files = subsystems.filter {
            it.implementation.kind == com.areslib.subsystem.SubsystemImplementationKind.GENERATED_STARTER
        }.flatMap { document ->
            SubsystemKotlinGenerator.generate(document, target)
        } + SubsystemKotlinGenerator.generateRegistry(subsystems, target)
        val duplicate = files.groupBy { it.sourceSet to it.relativePath }.filterValues { it.size > 1 }.keys
        require(duplicate.isEmpty()) { "Generated subsystem paths collide: ${duplicate.joinToString()}" }
        if (options.subsystemsStarterOutput != null) {
            val starterRoot = options.subsystemsStarterOutput.toAbsolutePath().normalize()
            require(starterRoot.startsWith(projectRoot)) { "Subsystem starter output must stay inside the selected project" }
            val plan = SubsystemStarterReconciler.plan(starterRoot, files)
            if (options.previewSubsystemStarters) {
                println(plan.render())
                return
            }
            if (options.applySubsystemStarters) {
                println(SubsystemStarterReconciler.apply(starterRoot, files, options.subsystemConfirmationToken).render())
            } else {
                SubsystemStarterReconciler.requirePresent(starterRoot, files)
            }
            val generatedRoot = requireNotNull(options.subsystemsGeneratedOutput) {
                "--subsystems-generated-output is required with --subsystems-starter-output"
            }.toAbsolutePath().normalize()
            val generatedTestRoot = requireNotNull(options.subsystemsGeneratedTestOutput) {
                "--subsystems-generated-test-output is required with --subsystems-starter-output"
            }.toAbsolutePath().normalize()
            require(generatedRoot.startsWith(projectRoot) && generatedTestRoot.startsWith(projectRoot)) {
                "Generated subsystem output must stay inside the selected project"
            }
            syncSourceSet(
                generatedRoot,
                files.filter {
                    it.sourceSet == GeneratedSubsystemSourceSet.MAIN &&
                        it.ownership == SubsystemArtifactOwnership.GENERATED_DO_NOT_EDIT
                },
                options.checkOnly,
            )
            syncSourceSet(
                generatedTestRoot,
                files.filter {
                    it.sourceSet == GeneratedSubsystemSourceSet.TEST &&
                        it.ownership == SubsystemArtifactOwnership.GENERATED_DO_NOT_EDIT
                },
                options.checkOnly,
            )
            return
        }

        error("Subsystem generation requires --subsystems-starter-output and split generated-source outputs")
    }

    private fun syncSourceSet(root: Path, files: List<GeneratedSubsystemFile>, checkOnly: Boolean) {
        val manifest = root.resolve(".ares-subsystems-manifest")
        val expected = files.associate { file -> file.relativePath.replace('\\', '/') to file.content }
        val expectedManifest = expected.keys.sorted().joinToString(separator = "\n", postfix = if (expected.isEmpty()) "" else "\n")
        if (checkOnly) {
            require(Files.isRegularFile(manifest) || expected.isEmpty()) {
                "Generated subsystem manifest is missing at $manifest"
            }
            val actualManifest = if (Files.isRegularFile(manifest)) Files.readString(manifest) else ""
            require(actualManifest == expectedManifest) {
                "Generated subsystem file list is stale at $root. Run the ARES generation task."
            }
            expected.forEach { (relative, content) ->
                val path = safeGeneratedPath(root, relative)
                require(Files.isRegularFile(path) && Files.readString(path) == content) {
                    "Generated subsystem source is stale at $path. Run the ARES generation task."
                }
            }
            return
        }

        val previous = if (Files.isRegularFile(manifest)) Files.readAllLines(manifest).filter(String::isNotBlank) else emptyList()
        previous.filterNot(expected::containsKey).forEach { relative ->
            Files.deleteIfExists(safeGeneratedPath(root, relative))
        }
        expected.forEach { (relative, content) ->
            val path = safeGeneratedPath(root, relative)
            if (!Files.isRegularFile(path) || Files.readString(path) != content) writeAtomically(path, content)
        }
        if (expected.isEmpty()) {
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
                )
            }

            private val VALUE_OPTIONS = setOf(
                "--project", "--output", "--package", "--object", "--registry", "--platform",
                "--subsystems-package", "--subsystems-starter-output", "--subsystems-generated-output",
                "--subsystems-generated-test-output", "--subsystems-confirmation-token",
                "--drivebase-output", "--drivebase-package",
                "--superstructure-output", "--superstructure-package",
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
