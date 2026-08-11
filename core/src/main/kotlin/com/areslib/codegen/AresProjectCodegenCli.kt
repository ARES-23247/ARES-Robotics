package com.areslib.codegen

import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileCodec
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.mergeSubsystemCapabilities
import com.areslib.subsystem.subsystemTargetCapabilities
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
        val subsystemActions = subsystemTargetCapabilities(subsystems)
        val catalog = mergeSubsystemCapabilities(baseCatalog, subsystems)

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
            )
        )

        if (options.checkOnly) {
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
        return generated
    }

    private fun syncSubsystemSources(
        projectRoot: Path,
        subsystems: List<com.areslib.subsystem.SubsystemDocument>,
        options: CliOptions,
    ) {
        if (subsystems.isEmpty() && options.subsystemsOutput == null && options.subsystemsTestOutput == null) return
        val mainRoot = requireNotNull(options.subsystemsOutput) {
            "--subsystems-output is required when .ares/subsystems contains documents"
        }.toAbsolutePath().normalize()
        val testRoot = requireNotNull(options.subsystemsTestOutput) {
            "--subsystems-test-output is required when generated subsystem tests are enabled"
        }.toAbsolutePath().normalize()
        require(mainRoot.startsWith(projectRoot) && testRoot.startsWith(projectRoot)) {
            "Generated subsystem output must stay inside the selected project"
        }
        val platform = when (options.platform) {
            ControllerInputPlatform.FTC -> SubsystemPlatform.FTC
            ControllerInputPlatform.FRC -> SubsystemPlatform.FRC
            ControllerInputPlatform.DESKTOP_GLFW, null -> error("Subsystem generation requires --platform FTC or FRC")
        }
        val basePackage = requireNotNull(options.subsystemsPackage) {
            "--subsystems-package is required when generating subsystem sources"
        }
        val target = SubsystemKotlinCodegenTarget(platform, basePackage)
        val files = subsystems.flatMap { document ->
            SubsystemKotlinGenerator.generate(document, target)
        } + SubsystemKotlinGenerator.generateRegistry(subsystems, target)
        val duplicate = files.groupBy { it.sourceSet to it.relativePath }.filterValues { it.size > 1 }.keys
        require(duplicate.isEmpty()) { "Generated subsystem paths collide: ${duplicate.joinToString()}" }
        syncSourceSet(
            mainRoot,
            files.filter { it.sourceSet == GeneratedSubsystemSourceSet.MAIN },
            options.checkOnly,
        )
        syncSourceSet(
            testRoot,
            files.filter { it.sourceSet == GeneratedSubsystemSourceSet.TEST },
            options.checkOnly,
        )
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
        val subsystemsOutput: Path?,
        val subsystemsTestOutput: Path?,
        val subsystemsPackage: String?,
        val checkOnly: Boolean
    ) {
        companion object {
            fun parse(args: Array<String>): CliOptions {
                val values = linkedMapOf<String, String>()
                var checkOnly = false
                var index = 0
                while (index < args.size) {
                    val key = args[index]
                    if (key == "--check") {
                        checkOnly = true
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
                    values["--subsystems-output"]?.let(Path::of),
                    values["--subsystems-test-output"]?.let(Path::of),
                    values["--subsystems-package"],
                    checkOnly,
                )
            }

            private val VALUE_OPTIONS = setOf(
                "--project", "--output", "--package", "--object", "--registry", "--platform",
                "--subsystems-output", "--subsystems-test-output", "--subsystems-package"
            )
        }
    }
}
