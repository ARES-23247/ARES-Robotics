package com.areslib.codegen

import com.areslib.controls.ControllerInputPlatform
import java.nio.file.Path

/** Command-line parsing is independent of project assembly and generated-file ownership. */
internal data class AresProjectCodegenOptions(
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
        fun parse(args: Array<String>): AresProjectCodegenOptions {
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
            return AresProjectCodegenOptions(
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
