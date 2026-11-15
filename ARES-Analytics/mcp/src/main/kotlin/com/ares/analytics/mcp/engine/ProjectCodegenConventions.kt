package com.ares.analytics.mcp.engine

import com.ares.analytics.mcp.McpBuildConfig
import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.schema.AresProjectTarget
import com.areslib.project.schema.defaultProjectTarget
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.tuning.TuningComponentDocumentCodec
import com.areslib.tuning.TuningProfileDocumentCodec
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Canonical path and codegen option conventions shared across FTC, FRC, and XRP starters. */
object ProjectCodegenConventions {
    data class LeagueTemplateSpec(
        val league: AresLeague,
        val archiveName: String,
        val bundledResourcePath: String,
    )

    fun starterSpec(league: AresLeague): LeagueTemplateSpec = when (league) {
        AresLeague.FTC -> LeagueTemplateSpec(
            league = AresLeague.FTC,
            archiveName = "ARES-FTC-Starter-${McpBuildConfig.FTC_STARTER_VERSION}.zip",
            bundledResourcePath = "/project-templates/ARES-FTC-Starter-${McpBuildConfig.FTC_STARTER_VERSION}.zip",
        )
        AresLeague.FRC -> LeagueTemplateSpec(
            league = AresLeague.FRC,
            archiveName = "ARES-FRC-Starter-${McpBuildConfig.FRC_STARTER_VERSION}.zip",
            bundledResourcePath = "/project-templates/ARES-FRC-Starter-${McpBuildConfig.FRC_STARTER_VERSION}.zip",
        )
        AresLeague.XRP -> LeagueTemplateSpec(
            league = AresLeague.XRP,
            archiveName = "ARES-XRP-Starter-${McpBuildConfig.XRP_STARTER_VERSION}.zip",
            bundledResourcePath = "/project-templates/ARES-XRP-Starter-${McpBuildConfig.XRP_STARTER_VERSION}.zip",
        )
    }

    fun codegenCliArgs(
        projectRoot: File,
        league: AresLeague,
        previewStarters: Boolean = false,
        applyStarters: Boolean = false,
        confirmationToken: String? = null,
    ): List<String> {
        val rootPath = projectRoot.absolutePath
        val args = mutableListOf("--project", rootPath)
        when (league) {
            AresLeague.FTC -> {
                val genRoot = File(projectRoot, "TeamCode/build/generated/ares/main/kotlin")
                args += listOf(
                    "--output", File(genRoot, "org/firstinspires/ftc/teamcode/generated/GeneratedAresProject.kt").absolutePath,
                    "--package", "org.firstinspires.ftc.teamcode.generated",
                    "--platform", "FTC",
                    "--subsystems-starter-output", File(projectRoot, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems").absolutePath,
                    "--subsystems-generated-output", genRoot.absolutePath,
                    "--subsystems-generated-test-output", File(projectRoot, "TeamCode/build/generated/ares/test/kotlin").absolutePath,
                    "--subsystems-package", "org.firstinspires.ftc.teamcode.subsystems",
                    "--drivebase-output", File(projectRoot, "TeamCode/build/generated/ares/drivebase/kotlin").absolutePath,
                    "--drivebase-package", "org.firstinspires.ftc.teamcode.generated.drivebase",
                    "--ftc-zero-code-runtime",
                )
            }
            AresLeague.FRC -> {
                val genRoot = File(projectRoot, "build/generated/ares/main/kotlin")
                args += listOf(
                    "--output", File(genRoot, "org/aresfirst/starter/frc/generated/GeneratedAresProject.kt").absolutePath,
                    "--package", "org.aresfirst.starter.frc.generated",
                    "--platform", "FRC",
                    "--subsystems-starter-output", File(projectRoot, "src/main/kotlin/org/aresfirst/starter/frc/generated/subsystems").absolutePath,
                    "--subsystems-generated-output", genRoot.absolutePath,
                    "--subsystems-generated-test-output", File(projectRoot, "build/generated/ares/test/kotlin").absolutePath,
                    "--subsystems-package", "org.aresfirst.starter.frc.generated.subsystems",
                    "--drivebase-output", File(projectRoot, "build/generated/ares/drivebase/kotlin").absolutePath,
                    "--drivebase-package", "org.aresfirst.starter.frc.generated.drivebase",
                )
            }
            AresLeague.XRP -> {
                error("XRP projects use the MicroPython generator rather than Kotlin codegen.")
            }
        }
        if (previewStarters) args += "--preview-subsystem-starters"
        if (applyStarters) {
            args += "--apply-subsystem-starters"
            if (!confirmationToken.isNullOrBlank()) {
                args += listOf("--subsystems-confirmation-token", confirmationToken)
            }
        }
        return args
    }

    fun starterOutputDir(projectRoot: File, league: AresLeague): File = when (league) {
        AresLeague.FTC -> File(projectRoot, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems")
        AresLeague.FRC -> File(projectRoot, "src/main/kotlin/org/aresfirst/starter/frc/generated/subsystems")
        AresLeague.XRP -> File(projectRoot, "src")
    }

    fun fieldDefinitionFile(projectRoot: File, league: AresLeague): File = when (league) {
        AresLeague.FTC -> File(projectRoot, "TeamCode/src/main/assets/paths/field.json")
        AresLeague.FRC -> File(projectRoot, "src/main/deploy/paths/field.json")
        AresLeague.XRP -> File(projectRoot, ".ares/field.json")
    }

    fun openStarterArchiveStream(
        league: AresLeague,
        explicitTemplateDir: File? = null,
    ): Pair<String, InputStream> {
        val spec = starterSpec(league)
        val candidates = buildList {
            if (explicitTemplateDir != null) add(File(explicitTemplateDir, spec.archiveName))
            System.getProperty("ares.mcp.templateDir")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add(File(it, spec.archiveName))
            }
            System.getenv("ARES_MCP_TEMPLATE_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add(File(it, spec.archiveName))
            }
            add(File("ARES-Analytics/app/src/main/resources/project-templates/${spec.archiveName}"))
            add(File("app/src/main/resources/project-templates/${spec.archiveName}"))
        }
        for (candidate in candidates) {
            if (candidate.isFile) return candidate.absolutePath to candidate.inputStream().buffered()
        }
        val resourceStream = ProjectCodegenConventions::class.java.getResourceAsStream(spec.bundledResourcePath)
        if (resourceStream != null) {
            return "classpath:${spec.bundledResourcePath}" to resourceStream.buffered()
        }
        throw IllegalStateException(
            "Starter archive '${spec.archiveName}' was not found on classpath or in ${candidates.joinToString { it.absolutePath }}. " +
                "Pass --template-dir=<dir> containing official starter archives.",
        )
    }

    fun extractZipSafely(input: InputStream, targetDir: File) {
        targetDir.mkdirs()
        val canonicalTarget = targetDir.canonicalFile.toPath()
        val rawEntries = mutableListOf<Pair<String, ByteArray>>()
        var totalBytes = 0L
        ZipInputStream(input).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val entryName = entry.name.trimStart('/', '\\').replace('\\', '/')
                if (entryName.isNotEmpty() && !entry.isDirectory) {
                    val bytes = zis.readBytes()
                    totalBytes += bytes.size
                    require(totalBytes <= 128L * 1024L * 1024L) {
                        "Extracted archive exceeded 128 MiB safety limit."
                    }
                    rawEntries += entryName to bytes
                }
                zis.closeEntry()
            }
        }
        require(rawEntries.isNotEmpty()) { "Starter archive contained zero files." }

        val hasRootProjectJson = rawEntries.any { (name, _) -> name == ".ares/project.json" }
        val singleRootPrefix = if (!hasRootProjectJson && rawEntries.all { (name, _) -> name.contains('/') }) {
            rawEntries.map { (name, _) -> name.substringBefore('/') }.distinct().singleOrNull()
        } else {
            null
        }
        val stripPrefix = singleRootPrefix?.let { "$it/" }

        for ((rawName, bytes) in rawEntries) {
            val relativeName = if (stripPrefix != null && rawName.startsWith(stripPrefix)) {
                rawName.removePrefix(stripPrefix)
            } else {
                rawName
            }
            if (relativeName.isEmpty()) continue
            val outPath = canonicalTarget.resolve(relativeName).normalize()
            require(outPath.startsWith(canonicalTarget)) {
                "Archive entry escapes target directory: $rawName"
            }
            outPath.parent?.toFile()?.mkdirs()
            outPath.toFile().writeBytes(bytes)
        }
    }

    fun buildProjectId(teamId: String, robotId: String): String {
        val raw = "${teamId.lowercase()}-${robotId.lowercase()}"
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
        val candidate = when {
            raw.isEmpty() -> "ares-robot"
            raw.first().isLetter() -> raw
            else -> "ares-$raw"
        }
        return candidate.take(64).trimEnd('-')
    }

    fun personalizeExtractedProject(
        root: File,
        teamId: String,
        seasonId: String,
        robotId: String,
        displayName: String,
        projectIdOverride: String? = null,
    ): String {
        val metadataFile = File(root, ".ares/project.json")
        require(metadataFile.isFile) { "Extracted starter is missing .ares/project.json" }
        val original = AresProjectMetadataCodec.decode(metadataFile.readText())
        val newProjectId = projectIdOverride
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: buildProjectId(teamId, robotId)
        val convention = when (original.league) {
            AresLeague.FTC -> AresCoordinateConvention.CENTER_ORIGIN_CCW
            AresLeague.FRC -> AresCoordinateConvention.BLUE_CORNER_ORIGIN_CCW
            AresLeague.XRP -> AresCoordinateConvention.CENTER_ORIGIN_CCW
        }
        val updatedMetadata = original.copy(
            projectId = newProjectId,
            coordinateConvention = convention,
            identity = original.identity.copy(
                teamId = teamId,
                seasonId = seasonId,
                robotId = robotId,
                displayName = displayName,
            ),
        )
        JGitSnapshotSafety.writeBytesAtomically(metadataFile, AresProjectMetadataCodec.encode(updatedMetadata).toByteArray())

        val actionFile = File(root, ".ares/action-catalog.json")
        if (actionFile.isFile) {
            val catalog = CapabilityCatalogCodec.decode(actionFile.readText())
            JGitSnapshotSafety.writeBytesAtomically(
                actionFile,
                CapabilityCatalogCodec.encode(catalog.copy(projectId = newProjectId)).toByteArray(),
            )
        }
        val autoFile = File(root, ".ares/autonomous-catalog.json")
        if (autoFile.isFile) {
            val autoCatalog = AutonomousCatalogCodec.decode(autoFile.readText())
            JGitSnapshotSafety.writeBytesAtomically(
                autoFile,
                AutonomousCatalogCodec.encode(autoCatalog.copy(projectId = newProjectId)).toByteArray(),
            )
        }
        val tuningComponentsDir = File(root, ".ares/tuning-components")
        val components = if (tuningComponentsDir.isDirectory) {
            tuningComponentsDir.listFiles { f -> f.isFile && f.extension == "arestuningcomponent" }.orEmpty()
                .map { file ->
                    val doc = TuningComponentDocumentCodec.decode(file.readText()).copy(projectId = newProjectId)
                    JGitSnapshotSafety.writeBytesAtomically(file, TuningComponentDocumentCodec.encode(doc).toByteArray())
                    doc
                }
        } else emptyList()
        val drivetrainsDir = File(root, ".ares/drivetrains")
        val drivetrains = if (drivetrainsDir.isDirectory) {
            drivetrainsDir.listFiles { f -> f.isFile && f.extension == "aresdrivetrain" }.orEmpty()
                .map { file -> com.areslib.drivetrain.DrivetrainDocumentCodec.decode(file.readText()) }
        } else emptyList()
        val declarations = drivetrains.flatMap { it.parameters } + components.flatMap { it.parameters }
        val tuningDir = File(root, ".ares/tuning")
        if (tuningDir.isDirectory) {
            tuningDir.listFiles { f -> f.isFile && f.extension == "arestuning" }.orEmpty().forEach { file ->
                val profile = TuningProfileDocumentCodec.decode(file.readText(), declarations).copy(projectId = newProjectId)
                JGitSnapshotSafety.writeBytesAtomically(
                    file,
                    TuningProfileDocumentCodec.encode(profile, declarations).toByteArray(),
                )
            }
        }
        return newProjectId
    }

    fun targetPlatformFor(league: AresLeague): AresProjectTarget = league.defaultProjectTarget()
}
