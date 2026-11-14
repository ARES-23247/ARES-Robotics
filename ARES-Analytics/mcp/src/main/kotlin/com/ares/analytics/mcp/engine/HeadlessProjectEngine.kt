package com.ares.analytics.mcp.engine

import com.ares.analytics.mcp.bridge.StudioBridgeClient
import com.ares.analytics.mcp.bridge.StudioBridgeStatus
import com.ares.analytics.mcp.bridge.StudioReloadOutcome
import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.codegen.AresProjectCodegenCli
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileCodec
import com.areslib.controls.ControllerProfileDocument
import com.areslib.drivetrain.DrivetrainDocument
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.compiler.RobotProjectCompiler
import com.areslib.project.model.EffectiveRobotProject
import com.areslib.project.model.ProjectModelIssue
import com.areslib.project.model.ProjectModelSeverity
import com.areslib.project.model.RobotProjectAssembler
import com.areslib.project.model.RobotProjectSnapshot
import com.areslib.project.schema.ProjectDocumentKind
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.RoutineDocument
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureDocumentCodec
import com.areslib.tuning.TuningComponentDocument
import com.areslib.tuning.TuningComponentDocumentCodec
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningProfileAuthority
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningProfileDocumentCodec
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class ProjectIssueDto(
    val severity: String,
    val kind: String,
    val documentId: String? = null,
    val path: String,
    val code: String,
    val message: String,
)

@Serializable
data class ProjectValidationSummary(
    val valid: Boolean,
    val revision: String,
    val projectRoot: String,
    val league: String? = null,
    val projectId: String? = null,
    val errorCount: Int,
    val warningCount: Int,
    val compilerIrVerified: Boolean = false,
    val issues: List<ProjectIssueDto>,
)

@Serializable
data class SourceFileClassification(
    val relativePath: String,
    val ownership: String,
)

@Serializable
data class ProjectInitResult(
    val projectRoot: String,
    val league: String,
    val projectId: String,
    val displayName: String,
    val templateSource: String,
    val snapshotRef: String? = null,
    val validation: ProjectValidationSummary,
)

@Serializable
data class ProjectInspectionResult(
    val projectRoot: String,
    val revision: String,
    val league: String? = null,
    val projectId: String? = null,
    val displayName: String? = null,
    val coordinateConvention: String? = null,
    val actionKeys: List<String> = emptyList(),
    val tuningKeys: List<String> = emptyList(),
    val subsystemIds: List<String> = emptyList(),
    val drivetrainIds: List<String> = emptyList(),
    val superstructureIds: List<String> = emptyList(),
    val routineIds: List<String> = emptyList(),
    val controlSchemeIds: List<String> = emptyList(),
    val controllerProfileIds: List<String> = emptyList(),
    val sourceFiles: List<SourceFileClassification> = emptyList(),
    val bridgeStatus: StudioBridgeStatus? = null,
    val validation: ProjectValidationSummary,
)

@Serializable
data class CodegenReconcileResult(
    val projectRoot: String,
    val mode: String,
    val league: String,
    val hasReplacements: Boolean = false,
    val confirmationToken: String? = null,
    val protectedUserOwnedFiles: List<String> = emptyList(),
    val planText: String,
    val generatedHash: String? = null,
    val snapshotRef: String? = null,
    val bridgeReload: StudioReloadOutcome? = null,
)

internal data class LoadedProjectBundle(
    val snapshot: RobotProjectSnapshot,
    val effective: EffectiveRobotProject,
    val metadata: AresProjectMetadataDocument?,
    val tuningDeclarations: List<TuningParameterDeclaration>,
    val revision: String,
)

/**
 * Pure JVM headless project engine backing ARES MCP inspection, validation, starter initialization,
 * and codegen reconciliation using exact ARESLib schemas and assemblers.
 */
class HeadlessProjectEngine(
    internal val bridgeClient: StudioBridgeClient,
    internal val tokens: ConfirmationTokenManager = ConfirmationTokenManager(),
    private val templateDir: File? = null,
) {
    private val lastInspectedRoot = AtomicReference<String?>(null)
    private val codegenStdoutLock = Any()

    fun latestProjectRoot(): String? = lastInspectedRoot.get()

    suspend fun initProject(
        targetDir: File,
        leagueRaw: String,
        projectIdHint: String? = null,
        displayName: String,
        teamId: String = "00000",
        seasonId: String = "2026",
        robotId: String = "AresRobot",
        initGit: Boolean = true,
    ): ProjectInitResult {
        val league = AresLeague.valueOf(leagueRaw.trim().uppercase())
        val aresDir = File(targetDir, ".ares")
        require(!aresDir.exists()) {
            "Refusing to overwrite existing ARES project at '${targetDir.absolutePath}'; .ares already exists."
        }
        val (sourceLabel, stream) = ProjectCodegenConventions.openStarterArchiveStream(league, templateDir)
        stream.use { ProjectCodegenConventions.extractZipSafely(it, targetDir) }
        val resolvedProjectId = ProjectCodegenConventions.personalizeExtractedProject(
            root = targetDir,
            teamId = teamId,
            seasonId = seasonId,
            robotId = robotId,
            displayName = displayName,
            projectIdOverride = projectIdHint,
        )
        val snapshotResult = if (initGit) {
            JGitSnapshotSafety.createSnapshot(targetDir, slug = "init-$resolvedProjectId", initGitIfMissing = true)
        } else {
            null
        }
        lastInspectedRoot.set(targetDir.canonicalPathSafe())
        val validation = validateProject(targetDir)
        return ProjectInitResult(
            projectRoot = targetDir.canonicalPathSafe(),
            league = league.name,
            projectId = resolvedProjectId,
            displayName = displayName,
            templateSource = sourceLabel,
            snapshotRef = snapshotResult?.refName,
            validation = validation,
        )
    }

    suspend fun resolveTargetRoot(explicitPath: String?): File {
        if (!explicitPath.isNullOrBlank()) return File(explicitPath).canonicalFile
        val bridgeHealth = bridgeClient.health()
        bridgeHealth.activeProjectRoot?.takeIf { it.isNotBlank() }?.let { return File(it).canonicalFile }
        lastInspectedRoot.get()?.let { return File(it).canonicalFile }
        throw IllegalArgumentException("projectPath is required when no active Studio project is attached.")
    }

    suspend fun inspectProject(
        projectRoot: File,
        includeFiles: Boolean = true,
        includeCatalog: Boolean = true,
    ): ProjectInspectionResult {
        val canonical = projectRoot.canonicalFile
        lastInspectedRoot.set(canonical.path)
        val bundle = loadBundle(canonical)
        val validation = summarizeValidation(canonical, bundle)
        val bridge = bridgeClient.health()
        val actionKeys = if (includeCatalog) {
            bundle.effective.actions.keys.map { it.value }.sorted()
        } else {
            emptyList()
        }
        val sourceFiles = if (includeFiles) classifySourceFiles(canonical) else emptyList()
        val metadata = bundle.metadata
        return ProjectInspectionResult(
            projectRoot = canonical.path,
            revision = bundle.revision,
            league = metadata?.league?.name,
            projectId = metadata?.projectId,
            displayName = metadata?.identity?.displayName,
            coordinateConvention = metadata?.coordinateConvention?.name,
            actionKeys = actionKeys,
            tuningKeys = if (includeCatalog) bundle.tuningDeclarations.map { it.key }.sorted() else emptyList(),
            subsystemIds = bundle.snapshot.subsystems.map { it.documentId }.sorted(),
            drivetrainIds = bundle.snapshot.drivetrains.map { it.drivebaseId }.sorted(),
            superstructureIds = bundle.snapshot.superstructures.map { it.superstructureId }.sorted(),
            routineIds = bundle.snapshot.routines.map { it.documentId }.sorted(),
            controlSchemeIds = bundle.snapshot.controlSchemes.map { it.documentId }.sorted(),
            controllerProfileIds = bundle.snapshot.controllerProfiles.map { it.documentId }.sorted(),
            sourceFiles = sourceFiles,
            bridgeStatus = bridge,
            validation = validation,
        )
    }

    fun validateProject(projectRoot: File): ProjectValidationSummary {
        val canonical = projectRoot.canonicalFile
        lastInspectedRoot.set(canonical.path)
        return summarizeValidation(canonical, loadBundle(canonical))
    }

    suspend fun reconcileCodegen(
        projectRoot: File,
        modeRaw: String,
        confirmationToken: String? = null,
    ): CodegenReconcileResult {
        val canonical = projectRoot.canonicalFile
        lastInspectedRoot.set(canonical.path)
        val bundle = loadBundle(canonical)
        val metadata = requireNotNull(bundle.metadata) { "Missing or invalid .ares/project.json in '${canonical.path}'" }
        val validation = summarizeValidation(canonical, bundle)
        require(validation.valid) {
            "Project has validation error(s) before codegen: " +
                validation.issues.filter { it.severity == "ERROR" }.joinToString("; ") { "${it.path}: ${it.message}" }
        }
        val mode = modeRaw.trim().uppercase()
        require(mode == "PREVIEW" || mode == "APPLY") {
            "Unsupported mode '$modeRaw'; expected 'PREVIEW' or 'APPLY'."
        }
        val previewArgs = ProjectCodegenConventions.codegenCliArgs(canonical, metadata.league, previewStarters = true)
        val previewOutput = captureCodegenStdout {
            AresProjectCodegenCli.run(previewArgs.toTypedArray())
        }
        val reconcilerToken = parseConfirmationToken(previewOutput.stdout)
        val protectedFiles = parseProtectedFiles(previewOutput.stdout)
        val hasReplacements = reconcilerToken != null || previewOutput.stdout.lines().any { it.startsWith("REPLACE ") }

        if (mode == "PREVIEW") {
            reconcilerToken?.let { tokens.recordPreviewToken(canonical, it) }
            return CodegenReconcileResult(
                projectRoot = canonical.path,
                mode = "PREVIEW",
                league = metadata.league.name,
                hasReplacements = hasReplacements,
                confirmationToken = reconcilerToken,
                protectedUserOwnedFiles = protectedFiles,
                planText = previewOutput.stdout,
                generatedHash = previewOutput.sourceHash,
            )
        }

        if (reconcilerToken != null) {
            when (val check = tokens.consumeForReplacement(canonical, confirmationToken, reconcilerToken)) {
                is ConfirmationCheckResult.Rejected -> throw IllegalStateException(check.message)
                ConfirmationCheckResult.Approved -> Unit
            }
        }
        val snapshot = JGitSnapshotSafety.createSnapshot(canonical, slug = "before-codegen-apply")
        val applyArgs = ProjectCodegenConventions.codegenCliArgs(
            projectRoot = canonical,
            league = metadata.league,
            applyStarters = true,
            confirmationToken = reconcilerToken,
        )
        val applyOutput = captureCodegenStdout {
            AresProjectCodegenCli.run(applyArgs.toTypedArray())
        }
        val reload = bridgeClient.notifyReload(
            projectRoot = canonical.path,
            reason = "ares_codegen_reconcile(APPLY)",
        )
        return CodegenReconcileResult(
            projectRoot = canonical.path,
            mode = "APPLY",
            league = metadata.league.name,
            hasReplacements = hasReplacements,
            protectedUserOwnedFiles = protectedFiles,
            planText = applyOutput.stdout,
            generatedHash = applyOutput.sourceHash,
            snapshotRef = snapshot.refName,
            bridgeReload = reload,
        )
    }

    internal fun loadBundle(root: File): LoadedProjectBundle {
        val issues = mutableListOf<ProjectModelIssue>()
        val aresDir = File(root, ".ares")

        fun loadSingle(
            file: File,
            kind: ProjectDocumentKind,
            docId: String?,
            decode: (String) -> Any,
        ): Any? {
            if (!file.isFile) return null
            return runCatching { decode(file.readText()) }.onFailure { err ->
                issues += ProjectModelIssue(
                    severity = ProjectModelSeverity.ERROR,
                    kind = kind,
                    documentId = docId,
                    path = file.relativeToOrSelf(root).invariantSeparatorsPath,
                    code = "decode_error",
                    message = err.message ?: "Failed to decode $kind document",
                )
            }.getOrNull()
        }

        val metadata = loadSingle(File(aresDir, "project.json"), ProjectDocumentKind.PROJECT_METADATA, null) {
            AresProjectMetadataCodec.decode(it)
        } as? AresProjectMetadataDocument

        val baseCatalog = loadSingle(File(aresDir, "action-catalog.json"), ProjectDocumentKind.CAPABILITY_CATALOG, null) {
            CapabilityCatalogCodec.decode(it)
        } as? CapabilityCatalogDocument

        val autonomousCatalog = loadSingle(
            File(aresDir, "autonomous-catalog.json"),
            ProjectDocumentKind.AUTONOMOUS_CATALOG,
            null,
        ) { AutonomousCatalogCodec.decode(it) } as? AutonomousCatalogDocument

        fun <T> loadDir(dirName: String, ext: String, kind: ProjectDocumentKind, decode: (String) -> T): List<T> {
            val dir = File(aresDir, dirName)
            if (!dir.isDirectory) return emptyList()
            return dir.listFiles { f -> f.isFile && f.extension == ext }.orEmpty()
                .sortedBy { it.name }
                .mapNotNull { file ->
                    runCatching { decode(file.readText()) }.onFailure { err ->
                        issues += ProjectModelIssue(
                            severity = ProjectModelSeverity.ERROR,
                            kind = kind,
                            documentId = file.nameWithoutExtension,
                            path = file.relativeToOrSelf(root).invariantSeparatorsPath,
                            code = "decode_error",
                            message = err.message ?: "Decode failed",
                        )
                    }.getOrNull()
                }
        }

        val routines: List<RoutineDocument> = loadDir("routines", "aresroutine", ProjectDocumentKind.ROUTINE, AresRoutineCodec::decode)
        val controls: List<ControlSchemeDocument> = loadDir("controls", "arescontrols", ProjectDocumentKind.CONTROL_SCHEME, ControlSchemeCodec::decode)
        val profiles: List<ControllerProfileDocument> = loadDir("controllers", "arescontroller", ProjectDocumentKind.CONTROLLER_PROFILE, ControllerProfileCodec::decode)
        val subsystems: List<SubsystemDocument> = loadDir("subsystems", "aressubsystem", ProjectDocumentKind.SUBSYSTEM, SubsystemDocumentCodec::decode)
        val superstructures: List<SuperstructureDocument> = loadDir("superstructures", "aressuperstructure", ProjectDocumentKind.SUPERSTRUCTURE, SuperstructureDocumentCodec::decode)
        val drivetrains: List<DrivetrainDocument> = loadDir("drivetrains", "aresdrivetrain", ProjectDocumentKind.DRIVETRAIN, DrivetrainDocumentCodec::decode)
        val tuningComponents: List<TuningComponentDocument> = loadDir("tuning-components", "arestuningcomponent", ProjectDocumentKind.TUNING_COMPONENT, TuningComponentDocumentCodec::decode)

        val declarations = (
            drivetrains.flatMap { it.parameters } +
                subsystems.flatMap { it.tuningParameters } +
                tuningComponents.flatMap { it.parameters }
            ).sortedBy { it.uid }

        val tuningProfiles: List<TuningProfileDocument> = loadDir("tuning", "arestuning", ProjectDocumentKind.TUNING_PROFILE) {
            TuningProfileDocumentCodec.decode(it, declarations)
        }

        val field: RobotFieldConfig? = metadata?.let { meta ->
            val fieldFile = ProjectCodegenConventions.fieldDefinitionFile(root, meta.league)
            loadSingle(fieldFile, ProjectDocumentKind.FIELD, null, RobotFieldDocument::decode) as? RobotFieldConfig
        }

        val snapshot = RobotProjectSnapshot(
            projectRoot = root.path,
            metadata = metadata,
            baseCapabilityCatalog = baseCatalog,
            autonomousCatalog = autonomousCatalog,
            routines = routines,
            controlSchemes = controls,
            controllerProfiles = profiles,
            subsystems = subsystems,
            superstructures = superstructures,
            drivetrains = drivetrains,
            field = field,
            tuningComponents = tuningComponents,
            tuningProfiles = tuningProfiles,
            loadIssues = issues,
        )
        val platform = when (metadata?.league) {
            AresLeague.FTC -> ControllerInputPlatform.FTC
            AresLeague.FRC -> ControllerInputPlatform.FRC
            AresLeague.XRP -> ControllerInputPlatform.XRP
            null -> null
        }
        val effective = RobotProjectAssembler.assemble(snapshot, platform)
        val revision = computeProjectRevision(root, metadata?.league)
        return LoadedProjectBundle(
            snapshot = snapshot,
            effective = effective,
            metadata = metadata,
            tuningDeclarations = declarations,
            revision = revision,
        )
    }

    internal fun computeProjectRevision(root: File, league: AresLeague? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val aresDir = File(root, ".ares")
        val candidates = mutableListOf<File>()
        if (aresDir.isDirectory) {
            aresDir.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        !file.relativeTo(aresDir).invariantSeparatorsPath.startsWith("history/") &&
                        !file.relativeTo(aresDir).invariantSeparatorsPath.startsWith("local/")
                }
                .forEach { candidates += it }
        }
        val effectiveLeague = league ?: runCatching {
            val file = File(aresDir, "project.json")
            if (file.isFile) AresProjectMetadataCodec.decode(file.readText()).league else null
        }.getOrNull()
        if (effectiveLeague != null) {
            val field = ProjectCodegenConventions.fieldDefinitionFile(root, effectiveLeague)
            if (field.isFile) candidates += field
        }
        candidates.distinctBy { it.canonicalPathSafe() }
            .sortedBy { it.relativeToOrSelf(root).invariantSeparatorsPath }
            .forEach { file ->
                val rel = file.relativeToOrSelf(root).invariantSeparatorsPath
                val body = file.readText().replace("\r\n", "\n")
                digest.update(rel.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(body.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun summarizeValidation(root: File, bundle: LoadedProjectBundle): ProjectValidationSummary {
        val issueDtos = bundle.effective.issues.map {
            ProjectIssueDto(
                severity = it.severity.name,
                kind = it.kind.name,
                documentId = it.documentId,
                path = it.path,
                code = it.code,
                message = it.message,
            )
        }.toMutableList()
        var irOk = false
        if (bundle.effective.isValid && bundle.metadata?.league != AresLeague.XRP) {
            runCatching { RobotProjectCompiler.lower(bundle.effective) }
                .onSuccess { irOk = true }
                .onFailure { err ->
                    issueDtos += ProjectIssueDto(
                        severity = "ERROR",
                        kind = "COMPILER_IR",
                        documentId = null,
                        path = ".ares",
                        code = "compiler_lowering_failed",
                        message = err.message ?: "RobotProjectCompiler.lower failed",
                    )
                }
        } else if (bundle.effective.isValid && bundle.metadata?.league == AresLeague.XRP) {
            irOk = true
        }
        val errors = issueDtos.count { it.severity == "ERROR" }
        val warnings = issueDtos.count { it.severity == "WARNING" }
        return ProjectValidationSummary(
            valid = errors == 0,
            revision = bundle.revision,
            projectRoot = root.path,
            league = bundle.metadata?.league?.name,
            projectId = bundle.metadata?.projectId,
            errorCount = errors,
            warningCount = warnings,
            compilerIrVerified = irOk,
            issues = issueDtos,
        )
    }

    internal fun classifySourceFiles(root: File): List<SourceFileClassification> {
        val searchRoots = listOf(File(root, "TeamCode/src"), File(root, "TeamCode/build/generated/ares"), File(root, "src"), File(root, "build/generated/ares"))
            .filter { it.isDirectory }
        return searchRoots.flatMap { base ->
            base.walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "java", "py") }
                .toList()
        }
            .distinctBy { it.canonicalPathSafe() }
            .sortedBy { it.relativeToOrSelf(root).invariantSeparatorsPath }
            .map { file ->
                val rel = file.relativeToOrSelf(root).invariantSeparatorsPath
                val ownership = when {
                    ConfirmationTokenManager.isGeneratedDoNotEditFile(file) || rel.contains("/build/generated/") ->
                        "PLUMBING_GENERATED"
                    file.useFirstLineOrEmpty().contains(ConfirmationTokenManager.GENERATED_STARTER_MARKER) ->
                        "GENERATED_STARTER"
                    ConfirmationTokenManager.isProtectedUserOwnedFile(file) ->
                        "USER_OWNED"
                    else -> "HAND_AUTHORED"
                }
                SourceFileClassification(relativePath = rel, ownership = ownership)
            }
    }

    private fun File.useFirstLineOrEmpty(): String =
        runCatching { bufferedReader().use { it.readLine().orEmpty() } }.getOrDefault("")

    private data class CodegenCapturedOutput(val stdout: String, val sourceHash: String)

    private inline fun captureCodegenStdout(block: () -> com.areslib.codegen.GeneratedKotlinSource): CodegenCapturedOutput {
        synchronized(codegenStdoutLock) {
            val previousOut = System.out
            val buffer = ByteArrayOutputStream()
            val captureStream = PrintStream(buffer, true, Charsets.UTF_8)
            return try {
                System.setOut(captureStream)
                val result = block()
                captureStream.flush()
                CodegenCapturedOutput(
                    stdout = buffer.toString(Charsets.UTF_8).replace("\r\n", "\n").trim(),
                    sourceHash = result.sourceHash,
                )
            } finally {
                System.setOut(previousOut)
            }
        }
    }

    private fun parseConfirmationToken(planText: String): String? {
        val match = Regex("""REPLACEMENT CONFIRMATION TOKEN:\s*([0-9a-fA-F]+)""").find(planText)
        return match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun parseProtectedFiles(planText: String): List<String> = planText.lineSequence()
        .mapNotNull { line ->
            if (!line.startsWith("PROTECTED ")) return@mapNotNull null
            line.removePrefix("PROTECTED ").substringBefore(" —").trim().takeIf { it.isNotEmpty() }
        }
        .toList()
}
