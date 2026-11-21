package com.ares.analytics.mcp.engine

import com.ares.analytics.mcp.bridge.StudioReloadOutcome
import com.ares.analytics.mcp.scaffold.FrcKotlinScaffoldRenderer
import com.ares.analytics.mcp.scaffold.FtcKotlinScaffoldRenderer
import com.ares.analytics.mcp.scaffold.HybridSubsystemRegistrar
import com.ares.analytics.mcp.scaffold.MockIoTestScaffoldRenderer
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControllerProfileCodec
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.project.AresLeague
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineAlliance
import com.areslib.routine.RoutinePose
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.superstructure.SuperstructureDocumentCodec
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningAssignment
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningProfileAuthority
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningProfileDocumentCodec
import com.areslib.tuning.TuningValue
import com.areslib.tuning.validateTuningProfileDocument
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class DescriptorMutationResult(
    val projectRoot: String,
    val documentKind: String,
    val operation: String,
    val documentId: String,
    val filePath: String,
    val pendingConfirmation: Boolean = false,
    val confirmationToken: String? = null,
    val previousRevision: String? = null,
    val newRevision: String? = null,
    val snapshotRef: String? = null,
    val validation: ProjectValidationSummary? = null,
    val bridgeReload: StudioReloadOutcome? = null,
    val message: String,
)

@Serializable
data class KotlinScaffoldResult(
    val projectRoot: String,
    val league: String,
    val kind: String,
    val mode: String,
    val relativePath: String,
    val targetExists: Boolean,
    val confirmationToken: String? = null,
    val snapshotRef: String? = null,
    val generatedCode: String,
    val message: String,
    val writtenPaths: List<String> = emptyList(),
    val registeredSubsystemId: String? = null,
    val validation: ProjectValidationSummary? = null,
)

@Serializable
data class TuningAssignmentInput(
    val keyOrUid: String,
    val doubleValue: Double? = null,
    val intValue: Int? = null,
    val booleanValue: Boolean? = null,
    val textValue: String? = null,
)

/**
 * Handles validated `.ares/` descriptor mutations and `USER-OWNED` Kotlin file scaffolding
 * with CAS revision checking, automatic pre-write JGit snapshots, and two-step confirmations.
 */
class HeadlessProjectMutators(
    private val engine: HeadlessProjectEngine,
) {
    internal val templateAndPatch by lazy { TemplateAndPatchSupport(engine, this) }
    internal val configAndField by lazy { ProjectConfigAndFieldMutators(engine, templateAndPatch) }
    internal val hybridRegistrar by lazy { HybridSubsystemRegistrar(engine) }

    suspend fun mutateSubsystem(
        projectRoot: File,
        operationRaw: String,
        documentIdHint: String? = null,
        documentJson: String? = null,
        expectedRevision: String? = null,
        confirmationToken: String? = null,
        templateName: String? = null,
        kotlinTypeName: String? = null,
        displayName: String? = null,
        implementationKind: String? = null,
        patchJson: String? = null,
    ): DescriptorMutationResult = when (operationRaw.trim().uppercase()) {
        "TEMPLATE" -> templateAndPatch.applySubsystemTemplate(
            projectRoot = projectRoot,
            templateNameRaw = requireNotNull(templateName?.takeIf { it.isNotBlank() }) {
                "templateName is required for SUBSYSTEM TEMPLATE."
            },
            documentIdHint = documentIdHint,
            kotlinTypeNameHint = kotlinTypeName,
            displayNameHint = displayName,
            implementationKindRaw = implementationKind,
            expectedRevision = expectedRevision,
        )
        "PATCH" -> templateAndPatch.patchSubsystem(
            projectRoot = projectRoot,
            documentIdHint = documentIdHint,
            patchJson = requireNotNull(patchJson) { "patchJson is required for SUBSYSTEM PATCH." },
            expectedRevision = expectedRevision,
        )
        else -> mutateVersionedDoc(
            projectRoot = projectRoot,
            documentKind = "SUBSYSTEM",
            operationRaw = operationRaw,
            relativeDir = ".ares/subsystems",
            extension = "aressubsystem",
            expectedRevision = expectedRevision,
            confirmationToken = confirmationToken,
            resolveDeleteId = { documentIdHint?.trim() },
            encodeUpsert = {
                val json = requireNotNull(documentJson) { "documentJson is required for SUBSYSTEM UPSERT" }
                val decoded = SubsystemDocumentCodec.decode(json)
                decoded.documentId to SubsystemDocumentCodec.encode(decoded)
            },
        )
    }

    suspend fun mutateDrivetrain(
        projectRoot: File,
        documentJson: String? = null,
        maxLinearSpeedMps: Double? = null,
        maxAngularSpeedRadPerSec: Double? = null,
        expectedRevision: String? = null,
        operationRaw: String = "UPSERT",
        presetName: String? = null,
        drivebaseIdHint: String? = null,
        patchJson: String? = null,
    ): DescriptorMutationResult {
        when (operationRaw.trim().uppercase()) {
            "TEMPLATE" -> return templateAndPatch.applyDrivetrainTemplate(
                projectRoot = projectRoot,
                presetRaw = requireNotNull(presetName?.takeIf { it.isNotBlank() }) {
                    "presetName is required for DRIVETRAIN TEMPLATE."
                },
                drivebaseIdHint = drivebaseIdHint,
                expectedRevision = expectedRevision,
            )
            "PATCH" -> return templateAndPatch.patchDrivetrain(
                projectRoot = projectRoot,
                patchJson = requireNotNull(patchJson) { "patchJson is required for DRIVETRAIN PATCH." },
                expectedRevision = expectedRevision,
            )
            "UPSERT" -> Unit
            else -> throw IllegalArgumentException(
                "Unsupported DRIVETRAIN operation '$operationRaw'; expected UPSERT, TEMPLATE, or PATCH.",
            )
        }
        val canonical = projectRoot.canonicalFile
        val bundle = engine.loadBundle(canonical)
        val current = bundle.snapshot.drivetrains.firstOrNull()
        val updated = when {
            documentJson != null -> DrivetrainDocumentCodec.decode(documentJson)
            current != null -> {
                val geo = current.geometry
                val resolvedLinear = maxLinearSpeedMps ?: geo.maxLinearSpeedMetersPerSecond
                val resolvedAngular = when {
                    maxAngularSpeedRadPerSec != null -> maxAngularSpeedRadPerSec
                    maxLinearSpeedMps != null && current.kind.name == "FTC_MECANUM" -> {
                        val effectiveRadius = (geo.trackWidthMeters + geo.wheelBaseMeters) / 2.0
                        if (effectiveRadius > 0.0) resolvedLinear / effectiveRadius else geo.maxAngularSpeedRadiansPerSecond
                    }
                    else -> geo.maxAngularSpeedRadiansPerSecond
                }
                current.copy(
                    geometry = geo.copy(
                        maxLinearSpeedMetersPerSecond = resolvedLinear,
                        maxAngularSpeedRadiansPerSecond = resolvedAngular,
                    ),
                )
            }
            else -> throw IllegalArgumentException("Either documentJson or an existing .aresdrivetrain must exist.")
        }
        val safeId = McpPathAndIdentifierValidators.validateDocumentSlug(updated.drivebaseId, "drivebaseId")
        val fileName = "$safeId.aresdrivetrain"
        val existingFile = File(canonical, ".ares/drivetrains")
            .listFiles { f -> f.isFile && f.extension == "aresdrivetrain" }
            ?.firstOrNull()
            ?: File(canonical, ".ares/drivetrains/$fileName")
        val relPath = existingFile.relativeTo(canonical).invariantSeparatorsPath
        return applyTransactionalUpsert(
            projectRoot = canonical,
            documentKind = "DRIVETRAIN",
            documentId = safeId,
            relativePath = relPath,
            encodedText = DrivetrainDocumentCodec.encode(updated),
            expectedRevision = expectedRevision,
            requiredSubdir = ".ares/drivetrains",
        )
    }

    suspend fun mutateSuperstructure(
        projectRoot: File,
        operationRaw: String,
        superstructureIdHint: String? = null,
        documentJson: String? = null,
        expectedRevision: String? = null,
        confirmationToken: String? = null,
        patchJson: String? = null,
    ): DescriptorMutationResult = if (operationRaw.trim().uppercase() == "PATCH") {
        templateAndPatch.patchSuperstructure(
            projectRoot = projectRoot,
            superstructureIdHint = superstructureIdHint,
            patchJson = requireNotNull(patchJson) { "patchJson is required for SUPERSTRUCTURE PATCH." },
            expectedRevision = expectedRevision,
        )
    } else {
        mutateVersionedDoc(
            projectRoot = projectRoot,
            documentKind = "SUPERSTRUCTURE",
            operationRaw = operationRaw,
            relativeDir = ".ares/superstructures",
            extension = "aressuperstructure",
            expectedRevision = expectedRevision,
            confirmationToken = confirmationToken,
            resolveDeleteId = { superstructureIdHint?.trim() },
            encodeUpsert = {
                val json = requireNotNull(documentJson) { "documentJson is required for SUPERSTRUCTURE UPSERT" }
                val decoded = SuperstructureDocumentCodec.decode(json)
                decoded.superstructureId to SuperstructureDocumentCodec.encode(decoded)
            },
        )
    }

    suspend fun mutateRoutine(
        projectRoot: File,
        operationRaw: String,
        documentIdHint: String? = null,
        documentJson: String? = null,
        registerAutonomousDisplayName: String? = null,
        expectedRevision: String? = null,
        confirmationToken: String? = null,
        patchJson: String? = null,
    ): DescriptorMutationResult {
        val canonical = projectRoot.canonicalFile
        val op = operationRaw.trim().uppercase()
        if (op == "DELETE") {
            return mutateVersionedDoc(
                projectRoot = canonical,
                documentKind = "ROUTINE",
                operationRaw = op,
                relativeDir = ".ares/routines",
                extension = "aresroutine",
                expectedRevision = expectedRevision,
                confirmationToken = confirmationToken,
                resolveDeleteId = { documentIdHint?.trim() },
                encodeUpsert = { error("Unreachable") },
            )
        }
        fun buildAutoWrites(routineId: String, routineDesc: String?): List<Triple<String, String?, String>> {
            val displayName = registerAutonomousDisplayName?.takeIf { it.isNotBlank() } ?: return emptyList()
            val autoFile = JGitSnapshotSafety.safeResolveChild(canonical, ".ares/autonomous-catalog.json", requiredSubdir = ".ares")
            val projectId = engine.loadBundle(canonical).metadata?.projectId ?: "ares-robot"
            val currentAuto = if (autoFile.isFile) AutonomousCatalogCodec.decode(autoFile.readText())
                else AutonomousCatalogDocument(projectId = projectId, entries = emptyList())
            val entry = AutonomousCatalogEntry(routineId, displayName, routineDesc, routineId, RoutinePose(0.0, 0.0, 0.0), RoutineAlliance.RED)
            val updated = currentAuto.copy(revision = currentAuto.revision + 1, entries = currentAuto.entries.filterNot { it.routineId == routineId } + entry)
            return listOf(Triple(".ares/autonomous-catalog.json", ".ares", AutonomousCatalogCodec.encode(updated)))
        }
        return if (op == "PATCH") {
            val id = McpPathAndIdentifierValidators.validateDocumentSlug(requireNotNull(documentIdHint) { "documentId is required for ROUTINE PATCH." }, "ROUTINE")
            val existing = JGitSnapshotSafety.safeResolveChild(canonical, ".ares/routines/$id.aresroutine", requiredSubdir = ".ares/routines")
            val mergedDesc = if (existing.isFile) runCatching {
                AresRoutineCodec.decode(templateAndPatch.applyJsonMergePatchPreservingUids(existing.readText(), requireNotNull(patchJson))).description
            }.getOrDefault("") else ""
            templateAndPatch.patchRoutine(
                projectRoot = canonical,
                documentIdHint = id,
                patchJson = requireNotNull(patchJson) { "patchJson is required for ROUTINE PATCH." },
                expectedRevision = expectedRevision,
                additionalWrites = buildAutoWrites(id, mergedDesc),
            )
        } else {
            val decoded = AresRoutineCodec.decode(requireNotNull(documentJson) { "documentJson is required for ROUTINE UPSERT" })
            val safeId = McpPathAndIdentifierValidators.validateDocumentSlug(decoded.documentId, "ROUTINE")
            applyTransactionalUpsert(
                projectRoot = canonical,
                documentKind = "ROUTINE",
                documentId = safeId,
                relativePath = ".ares/routines/$safeId.aresroutine",
                encodedText = AresRoutineCodec.encode(decoded),
                expectedRevision = expectedRevision,
                requiredSubdir = ".ares/routines",
                additionalWrites = buildAutoWrites(safeId, decoded.description),
            )
        }
    }

    suspend fun mutateControls(
        projectRoot: File,
        operationRaw: String,
        targetKindRaw: String = "CONTROL_SCHEME",
        documentIdHint: String? = null,
        documentJson: String? = null,
        expectedRevision: String? = null,
        confirmationToken: String? = null,
        patchJson: String? = null,
    ): DescriptorMutationResult {
        if (operationRaw.trim().uppercase() == "PATCH") {
            return templateAndPatch.patchControls(
                projectRoot = projectRoot,
                targetKindRaw = targetKindRaw,
                documentIdHint = documentIdHint,
                patchJson = requireNotNull(patchJson) { "patchJson is required for CONTROLS PATCH." },
                expectedRevision = expectedRevision,
            )
        }
        val targetKind = targetKindRaw.trim().uppercase()
        return if (targetKind == "CONTROLLER_PROFILE") {
            mutateVersionedDoc(
                projectRoot = projectRoot,
                documentKind = "CONTROLLER_PROFILE",
                operationRaw = operationRaw,
                relativeDir = ".ares/controllers",
                extension = "arescontroller",
                expectedRevision = expectedRevision,
                confirmationToken = confirmationToken,
                resolveDeleteId = { documentIdHint?.trim() },
                encodeUpsert = {
                    val json = requireNotNull(documentJson) { "documentJson is required" }
                    val doc = ControllerProfileCodec.decode(json)
                    doc.documentId to ControllerProfileCodec.encode(doc)
                },
            )
        } else {
            mutateVersionedDoc(
                projectRoot = projectRoot,
                documentKind = "CONTROL_SCHEME",
                operationRaw = operationRaw,
                relativeDir = ".ares/controls",
                extension = "arescontrols",
                expectedRevision = expectedRevision,
                confirmationToken = confirmationToken,
                resolveDeleteId = { documentIdHint?.trim() },
                encodeUpsert = {
                    val json = requireNotNull(documentJson) { "documentJson is required" }
                    val doc = ControlSchemeCodec.decode(json)
                    doc.documentId to ControlSchemeCodec.encode(doc)
                },
            )
        }
    }

    suspend fun mutateTuning(
        projectRoot: File,
        targetRaw: String,
        profileId: String,
        assignments: List<TuningAssignmentInput>,
        confirmationToken: String? = null,
        expectedRevision: String? = null,
    ): DescriptorMutationResult {
        val canonical = projectRoot.canonicalFile
        val bundle = engine.loadBundle(canonical)
        val previousRevision = bundle.revision
        if (expectedRevision != null && expectedRevision != previousRevision) {
            throw IllegalStateException("CAS revision mismatch: expected '$expectedRevision', found '$previousRevision'")
        }
        val projectId = requireNotNull(bundle.metadata?.projectId) { "Missing .ares/project.json" }
        val target = targetRaw.trim().uppercase()
        val authority = when (target) {
            "LOCAL_OVERLAY" -> TuningProfileAuthority.LOCAL_EXPERIMENTAL
            "CANONICAL" -> TuningProfileAuthority.CANONICAL_CHECKED_IN
            else -> throw IllegalArgumentException("Unsupported tuning target '$targetRaw'; expected LOCAL_OVERLAY or CANONICAL.")
        }
        val safeProfileId = McpPathAndIdentifierValidators.validateDocumentSlug(profileId, "profileId")
        val targetDir = if (target == "LOCAL_OVERLAY") ".ares/local/tuning" else ".ares/tuning"
        val targetFile = JGitSnapshotSafety.safeResolveChild(canonical, "$targetDir/$safeProfileId.arestuning", requiredSubdir = targetDir)
        val declarations = bundle.tuningDeclarations
        val declByKeyOrUid = declarations.associateBy { it.key } + declarations.associateBy { it.uid }
        val baseProfile = bundle.snapshot.tuningProfiles.firstOrNull { it.profileId == safeProfileId }
            ?: targetFile.takeIf(File::isFile)?.let { TuningProfileDocumentCodec.decode(it.readText(), declarations) }
        val existingMap = baseProfile?.values?.associateBy { it.parameterUid }.orEmpty().toMutableMap()
        for (input in assignments) {
            val decl = declByKeyOrUid[input.keyOrUid]
                ?: throw IllegalArgumentException("Unknown tuning parameter '${input.keyOrUid}'.")
            if (decl.applyPolicy == TuningApplyPolicy.READ_ONLY_VENDOR) {
                throw IllegalArgumentException("Tuning parameter '${decl.key}' is READ_ONLY_VENDOR and cannot be mutated.")
            }
            val value = when (decl.type) {
                TuningParameterType.DOUBLE -> TuningValue(doubleValue = input.doubleValue ?: input.intValue?.toDouble())
                TuningParameterType.INT -> TuningValue(intValue = input.intValue)
                TuningParameterType.BOOLEAN -> TuningValue(booleanValue = input.booleanValue)
                TuningParameterType.TEXT, TuningParameterType.ENUM -> TuningValue(textValue = input.textValue)
            }
            existingMap[decl.uid] = TuningAssignment(parameterUid = decl.uid, value = value)
        }
        val draftProfile = TuningProfileDocument(
            uid = baseProfile?.uid ?: "$projectId.profile.$safeProfileId",
            profileId = safeProfileId,
            displayName = baseProfile?.displayName ?: "Profile $safeProfileId",
            description = baseProfile?.description ?: "MCP tuning profile update ($target)",
            projectId = projectId,
            drivebaseUid = baseProfile?.drivebaseUid ?: bundle.snapshot.drivetrains.firstOrNull()?.uid,
            authority = authority,
            values = existingMap.values.sortedBy { it.parameterUid },
            promotion = baseProfile?.promotion?.takeIf { authority == TuningProfileAuthority.CANONICAL_CHECKED_IN },
        )
        val profileIssues = validateTuningProfileDocument(draftProfile, declarations)
        require(profileIssues.isEmpty()) {
            "Tuning validation failed: " + profileIssues.joinToString("; ") { "${it.path}: ${it.message}" }
        }
        val encoded = TuningProfileDocumentCodec.encode(draftProfile, declarations)
        val relPath = targetFile.relativeTo(canonical).invariantSeparatorsPath

        if (target == "CANONICAL") {
            val opKey = "TUNING_CANONICAL::$safeProfileId"
            val digest = ConfirmationTokenManager.digestFileOrMissing(targetFile) + "::" +
                ConfirmationTokenManager.sha256Hex(encoded)
            if (confirmationToken.isNullOrBlank()) {
                val issued = engine.tokens.issueTargetToken(canonical, opKey, digest)
                return DescriptorMutationResult(
                    projectRoot = canonical.path,
                    documentKind = "TUNING",
                    operation = "PREVIEW_CANONICAL",
                    documentId = safeProfileId,
                    filePath = relPath,
                    pendingConfirmation = true,
                    confirmationToken = issued,
                    previousRevision = previousRevision,
                    message = "Canonical tuning write staged. Re-run with confirmationToken to write $relPath.",
                )
            }
            when (val check = engine.tokens.verifyAndConsumeTargetToken(canonical, opKey, digest, confirmationToken)) {
                is ConfirmationCheckResult.Rejected -> throw IllegalStateException(check.message)
                ConfirmationCheckResult.Approved -> Unit
            }
        }

        return applyTransactionalUpsert(
            projectRoot = canonical,
            documentKind = "TUNING",
            documentId = safeProfileId,
            relativePath = relPath,
            encodedText = encoded,
            expectedRevision = expectedRevision,
            requiredSubdir = targetDir,
        )
    }

    fun scaffoldKotlin(
        projectRoot: File,
        leagueRaw: String,
        kindRaw: String,
        name: String,
        modeRaw: String = "PREVIEW",
        packageName: String? = null,
        relativePathOverride: String? = null,
        confirmationToken: String? = null,
    ): KotlinScaffoldResult {
        val canonical = projectRoot.canonicalFile
        val league = AresLeague.valueOf(leagueRaw.trim().uppercase())
        require(league == AresLeague.FTC || league == AresLeague.FRC) {
            "Kotlin scaffolding supports FTC and FRC projects; found $league"
        }
        val requestedName = name.trim().replaceFirstChar { it.uppercaseChar() }
        val kind = kindRaw.trim().uppercase()
        val className = if (kind == "UNIT_TEST" && !requestedName.endsWith("Test")) requestedName + "Test" else requestedName
        require(requestedName.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}"))) {
            "Scaffold name must be a valid Kotlin identifier: '$name'"
        }
        val defaultPkg = when (league) {
            AresLeague.FTC -> "org.firstinspires.ftc.teamcode.extensions"
            AresLeague.FRC -> "org.aresfirst.starter.frc.extensions"
            AresLeague.XRP -> error("Unsupported")
        }
        val pkg = McpPathAndIdentifierValidators.validateKotlinPackageName(
            packageName?.takeIf { it.isNotBlank() } ?: defaultPkg,
        )
        require(kind != "UNIT_TEST" || className != "Test") {
            "Name the test after its hardware adapter, for example LiftHardwareIoTest for LiftHardwareIo."
        }
        val rendered = when (kind) {
            "HARDWARE_IO" -> if (league == AresLeague.FTC) {
                FtcKotlinScaffoldRenderer.renderHardwareIo(FtcKotlinScaffoldRenderer.FtcScaffoldInput(pkg, className))
            } else {
                FrcKotlinScaffoldRenderer.renderHardwareIo(FrcKotlinScaffoldRenderer.FrcScaffoldInput(pkg, className))
            }
            "SEASON_REDUX_SLICE" -> FtcKotlinScaffoldRenderer.renderSeasonReduxSlice(
                FtcKotlinScaffoldRenderer.FtcScaffoldInput(pkg, className, subsystemName = className),
            )
            "TELEOP_OPMODE" -> {
                require(league == AresLeague.FTC) { "TELEOP_OPMODE applies to FTC projects" }
                FtcKotlinScaffoldRenderer.renderOpMode(
                    FtcKotlinScaffoldRenderer.FtcScaffoldInput(pkg, className, opModeName = className),
                    autonomous = false,
                )
            }
            "AUTO_OPMODE" -> {
                require(league == AresLeague.FTC) { "AUTO_OPMODE applies to FTC projects" }
                FtcKotlinScaffoldRenderer.renderOpMode(
                    FtcKotlinScaffoldRenderer.FtcScaffoldInput(pkg, className, opModeName = className),
                    autonomous = true,
                )
            }
            "TIMED_ROBOT_HOOK" -> {
                require(league == AresLeague.FRC) { "TIMED_ROBOT_HOOK applies to FRC projects" }
                FrcKotlinScaffoldRenderer.renderTimedRobotHook(
                    FrcKotlinScaffoldRenderer.FrcScaffoldInput(pkg, className),
                )
            }
            "UNIT_TEST" -> MockIoTestScaffoldRenderer.renderUnitTest(
                packageName = pkg,
                className = className,
                ftc = league == AresLeague.FTC,
                subjectClassName = className.removeSuffix("Test"),
            )
            else -> throw IllegalArgumentException(
                "Unsupported scaffold kind '$kindRaw'; expected HARDWARE_IO, SEASON_REDUX_SLICE, TELEOP_OPMODE, AUTO_OPMODE, TIMED_ROBOT_HOOK, or UNIT_TEST.",
            )
        }

        val relPath = relativePathOverride?.trim()?.takeIf { it.isNotBlank() }
            ?.let { McpPathAndIdentifierValidators.validateScaffoldRelativePath(it, league) }
            ?: defaultScaffoldPath(league, kind, pkg, className)
        val allowedRoot = when (league) {
            AresLeague.FTC -> if (relPath.startsWith("simulator/src/")) "simulator/src" else "TeamCode/src"
            AresLeague.FRC -> "src"
            AresLeague.XRP -> error("Unsupported")
        }
        val targetFile = JGitSnapshotSafety.safeResolveChild(canonical, relPath, requiredSubdir = allowedRoot)
        require(!ConfirmationTokenManager.isGeneratedDoNotEditFile(targetFile)) {
            "Refusing to overwrite GENERATED DO NOT EDIT plumbing file: $relPath"
        }
        val opKey = "SCAFFOLD::$relPath"
        val previewDigest = ConfirmationTokenManager.digestFileOrMissing(targetFile) + "::" +
            ConfirmationTokenManager.sha256Hex(rendered)

        val mode = modeRaw.trim().uppercase()
        require(mode == "PREVIEW" || mode == "APPLY") { "Unsupported mode '$modeRaw'; expected PREVIEW or APPLY." }
        if (mode == "PREVIEW") {
            val token = engine.tokens.issueTargetToken(canonical, opKey, previewDigest)
            return KotlinScaffoldResult(
                projectRoot = canonical.path,
                league = league.name,
                kind = kind,
                mode = "PREVIEW",
                relativePath = relPath,
                targetExists = targetFile.isFile,
                confirmationToken = token,
                generatedCode = rendered,
                message = "Preview generated for $relPath. Pass confirmationToken to apply.",
            )
        }

        if (targetFile.isFile || !confirmationToken.isNullOrBlank()) {
            when (val check = engine.tokens.verifyAndConsumeTargetToken(canonical, opKey, previewDigest, confirmationToken)) {
                is ConfirmationCheckResult.Rejected -> throw IllegalStateException(check.message)
                ConfirmationCheckResult.Approved -> Unit
            }
        }
        val snapshot = if (targetFile.isFile) {
            JGitSnapshotSafety.createSnapshot(canonical, slug = "before-scaffold-$className").refName
        } else null
        JGitSnapshotSafety.writeBytesAtomically(targetFile, rendered.toByteArray(Charsets.UTF_8))
        return KotlinScaffoldResult(
            projectRoot = canonical.path,
            league = league.name,
            kind = kind,
            mode = "APPLY",
            relativePath = relPath,
            targetExists = true,
            snapshotRef = snapshot,
            generatedCode = rendered,
            message = "Wrote Kotlin scaffold to $relPath.",
        )
    }

    private fun defaultScaffoldPath(league: AresLeague, kind: String, pkg: String, className: String): String {
        val pkgDir = pkg.replace('.', '/')
        val isTest = kind == "UNIT_TEST"
        return when (league) {
            AresLeague.FTC -> if (isTest) "TeamCode/src/test/kotlin/$pkgDir/$className.kt" else "TeamCode/src/main/kotlin/$pkgDir/$className.kt"
            AresLeague.FRC -> if (isTest) "src/test/kotlin/$pkgDir/$className.kt" else "src/main/kotlin/$pkgDir/$className.kt"
            AresLeague.XRP -> error("Unsupported")
        }
    }

    private suspend fun mutateVersionedDoc(
        projectRoot: File,
        documentKind: String,
        operationRaw: String,
        relativeDir: String,
        extension: String,
        expectedRevision: String?,
        confirmationToken: String?,
        resolveDeleteId: () -> String?,
        encodeUpsert: () -> Pair<String, String>,
    ): DescriptorMutationResult {
        val canonical = projectRoot.canonicalFile
        val op = operationRaw.trim().uppercase()
        return when (op) {
            "UPSERT" -> {
                val (rawId, encoded) = encodeUpsert()
                val id = McpPathAndIdentifierValidators.validateDocumentSlug(rawId, documentKind)
                applyTransactionalUpsert(
                    projectRoot = canonical,
                    documentKind = documentKind,
                    documentId = id,
                    relativePath = "$relativeDir/$id.$extension",
                    encodedText = encoded,
                    expectedRevision = expectedRevision,
                    requiredSubdir = relativeDir,
                )
            }
            "DELETE" -> {
                val rawId = resolveDeleteId()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("documentId is required for $documentKind DELETE")
                val id = McpPathAndIdentifierValidators.validateDocumentSlug(rawId, documentKind)
                val targetFile = JGitSnapshotSafety.safeResolveChild(canonical, "$relativeDir/$id.$extension", requiredSubdir = relativeDir)
                require(targetFile.isFile) { "Cannot delete $documentKind '$id': file not found." }
                val opKey = "DELETE::$documentKind::$id"
                val digest = ConfirmationTokenManager.digestFileOrMissing(targetFile)
                if (confirmationToken.isNullOrBlank()) {
                    val issued = engine.tokens.issueTargetToken(canonical, opKey, digest)
                    return DescriptorMutationResult(
                        projectRoot = canonical.path,
                        documentKind = documentKind,
                        operation = "PREVIEW_DELETE",
                        documentId = id,
                        filePath = targetFile.relativeTo(canonical).invariantSeparatorsPath,
                        pendingConfirmation = true,
                        confirmationToken = issued,
                        previousRevision = engine.computeProjectRevision(canonical),
                        message = "Deletion requires two-step confirmation. Re-run with confirmationToken.",
                    )
                }
                when (val check = engine.tokens.verifyAndConsumeTargetToken(canonical, opKey, digest, confirmationToken)) {
                    is ConfirmationCheckResult.Rejected -> throw IllegalStateException(check.message)
                    ConfirmationCheckResult.Approved -> Unit
                }
                val previousRevision = engine.computeProjectRevision(canonical)
                if (expectedRevision != null && expectedRevision != previousRevision) {
                    throw IllegalStateException("CAS revision mismatch: expected '$expectedRevision', found '$previousRevision'")
                }
                val snapshot = JGitSnapshotSafety.createSnapshot(canonical, slug = "before-delete-$documentKind-$id")
                val backupBytes = targetFile.readBytes()
                check(targetFile.delete()) { "Failed to delete ${targetFile.absolutePath}" }
                val validation = engine.validateProject(canonical)
                if (!validation.valid) {
                    JGitSnapshotSafety.writeBytesAtomically(targetFile, backupBytes)
                    throw IllegalStateException(
                        "Delete rolled back due to project validation error(s): " +
                            validation.issues.filter { it.severity == "ERROR" }.joinToString("; ") { "${it.path}: ${it.message}" },
                    )
                }
                val reload = engine.bridgeClient.notifyReload(canonical.path, reason = "delete $documentKind:$id")
                DescriptorMutationResult(
                    projectRoot = canonical.path,
                    documentKind = documentKind,
                    operation = "DELETE",
                    documentId = id,
                    filePath = targetFile.relativeTo(canonical).invariantSeparatorsPath,
                    previousRevision = previousRevision,
                    newRevision = validation.revision,
                    snapshotRef = snapshot.refName,
                    validation = validation,
                    bridgeReload = reload,
                    message = "Deleted $documentKind '$id' cleanly.",
                )
            }
            else -> throw IllegalArgumentException("Unsupported operation '$operationRaw'; expected UPSERT or DELETE.")
        }
    }

    internal suspend fun applyTransactionalUpsert(
        projectRoot: File,
        documentKind: String,
        documentId: String,
        relativePath: String,
        encodedText: String,
        expectedRevision: String?,
        requiredSubdir: String? = null,
        additionalWrites: List<Triple<String, String?, String>> = emptyList(),
    ): DescriptorMutationResult {
        val primaryFile = JGitSnapshotSafety.safeResolveChild(projectRoot, relativePath, requiredSubdir = requiredSubdir)
        val extraTargets = additionalWrites.map { (r, sub, txt) -> JGitSnapshotSafety.safeResolveChild(projectRoot, r, requiredSubdir = sub) to txt }
        val previousRevision = engine.computeProjectRevision(projectRoot)
        if (expectedRevision != null && expectedRevision != previousRevision) {
            throw IllegalStateException("Revision conflict (CAS): expected '$expectedRevision' but project revision is '$previousRevision'.")
        }
        val beforeValidation = engine.validateProject(projectRoot)
        val snapshot = JGitSnapshotSafety.createSnapshot(projectRoot, slug = "before-upsert-$documentKind-$documentId")
        val allWrites = listOf(primaryFile to encodedText) + extraTargets
        val backups = allWrites.map { (file, _) -> file to (if (file.isFile) file.readBytes() else null) }
        try {
            allWrites.forEach { (file, text) -> JGitSnapshotSafety.writeBytesAtomically(file, text.toByteArray(Charsets.UTF_8)) }
            val afterValidation = engine.validateProject(projectRoot)
            if (!afterValidation.valid && afterValidation.errorCount > beforeValidation.errorCount) {
                backups.forEach { (f, b) -> if (b != null) JGitSnapshotSafety.writeBytesAtomically(f, b) else f.delete() }
                throw IllegalStateException(
                    "Upsert rejected & rolled back because it introduced validation error(s): " +
                        afterValidation.issues.filter { it.severity == "ERROR" }.joinToString("; ") { "${it.path}: ${it.message}" },
                )
            }
            val reload = engine.bridgeClient.notifyReload(projectRoot = projectRoot.path, reason = "upsert $documentKind:$documentId")
            return DescriptorMutationResult(
                projectRoot = projectRoot.path, documentKind = documentKind, operation = "UPSERT",
                documentId = documentId, filePath = relativePath, previousRevision = previousRevision,
                newRevision = afterValidation.revision, snapshotRef = snapshot.refName, validation = afterValidation,
                bridgeReload = reload, message = "Upserted $documentKind '$documentId' at $relativePath.",
            )
        } catch (failure: Exception) {
            if (failure !is IllegalStateException || !failure.message.orEmpty().startsWith("Upsert rejected & rolled back")) {
                backups.forEach { (f, b) -> runCatching { if (b != null) JGitSnapshotSafety.writeBytesAtomically(f, b) else f.delete() } }
            }
            throw failure
        }
    }
}
