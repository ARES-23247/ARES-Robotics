package com.ares.analytics.mcp.scaffold

import com.ares.analytics.mcp.engine.ConfirmationCheckResult
import com.ares.analytics.mcp.engine.ConfirmationTokenManager
import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.JGitSnapshotSafety
import com.ares.analytics.mcp.engine.KotlinScaffoldResult
import com.ares.analytics.mcp.engine.McpPathAndIdentifierValidators
import com.ares.analytics.mcp.engine.TemplateAndPatchSupport
import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectAuthoringModel
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemHardwareConnection
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemImplementationDocument
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemSimulationDocument
import com.areslib.subsystem.SubsystemSimulationSupport
import com.areslib.subsystem.SubsystemSourceOwnership
import com.areslib.subsystem.SubsystemTeachingDocument
import com.areslib.subsystem.SubsystemTeachingLevel
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import java.io.File

internal data class HybridRegistrationRequest(
    val documentId: String,
    val displayName: String,
    val kotlinTypeName: String,
    val sourceFiles: List<String>,
    val subsystemClassName: String,
    val ioContractClassName: String,
    val hardwareAdapterClassName: String,
    val mockAdapterClassName: String? = null,
    val actionKeys: List<String> = emptyList(),
    val primaryCanId: Int? = null,
    val primaryHardwareMapName: String? = null,
    val primaryHardwareKind: SubsystemHardwareKind? = null,
)

/**
 * Atomically registers a team-owned Kotlin mechanism bundle as a `HAND_AUTHORED` / `USER_OWNED`
 * subsystem descriptor, promotes `GUI_OWNED` projects to `HYBRID`, registers declared action
 * keys into `.ares/action-catalog.json`, and updates marker-bounded `TeamRobotExtensions.kt`.
 */
internal class HybridSubsystemRegistrar(
    private val engine: HeadlessProjectEngine,
) {
    suspend fun scaffoldFromTool(
        projectRoot: File,
        leagueRaw: String,
        kindRaw: String,
        name: String,
        modeRaw: String = "PREVIEW",
        packageName: String? = null,
        vendorProfile: String? = null,
        deviceNameOrCanId: String? = null,
        registerHybrid: Boolean = true,
        documentId: String? = null,
        sourceFiles: List<String> = emptyList(),
        subsystemClassName: String? = null,
        ioContractClassName: String? = null,
        hardwareAdapterClassName: String? = null,
        mockAdapterClassName: String? = null,
        actionKeys: List<String> = emptyList(),
        confirmationToken: String? = null,
    ): KotlinScaffoldResult = when (kindRaw.trim().uppercase()) {
        "VENDOR_SUBSYSTEM_BUNDLE" -> scaffoldVendorBundle(
            projectRoot = projectRoot,
            leagueRaw = leagueRaw,
            vendorProfileRaw = requireNotNull(vendorProfile?.takeIf { it.isNotBlank() }) {
                "vendorProfile is required for kind='VENDOR_SUBSYSTEM_BUNDLE'."
            },
            name = name,
            modeRaw = modeRaw,
            packageName = packageName,
            deviceNameOrCanId = deviceNameOrCanId,
            registerHybrid = registerHybrid,
            actionKeys = actionKeys,
            confirmationToken = confirmationToken,
        )
        "REGISTER_EXISTING_HYBRID" -> {
            val baseName = name.trim().replaceFirstChar(Char::uppercaseChar)
            val defaultPkg = if (leagueRaw.trim().uppercase() == "FTC") {
                "org.firstinspires.ftc.teamcode.extensions"
            } else {
                "org.aresfirst.starter.frc.extensions"
            }
            val pkg = packageName?.trim()?.takeIf { it.isNotBlank() }
                ?.let { McpPathAndIdentifierValidators.validateKotlinPackageName(it) }
                ?: defaultPkg
            val adapterPrefix = if (leagueRaw.trim().uppercase() == "FTC") "Ftc" else "Frc"
            registerExistingHybrid(
                projectRoot = projectRoot,
                modeRaw = modeRaw,
                request = HybridRegistrationRequest(
                    documentId = documentId?.takeIf { it.isNotBlank() } ?: name.lowercase().replace('_', '-'),
                    displayName = TemplateAndPatchSupport.toDisplayName(name),
                    kotlinTypeName = if (baseName.endsWith("Subsystem")) baseName else "${baseName}Subsystem",
                    sourceFiles = sourceFiles,
                    subsystemClassName = subsystemClassName ?: "$pkg.${baseName}Subsystem",
                    ioContractClassName = ioContractClassName ?: "$pkg.${baseName}IO",
                    hardwareAdapterClassName = hardwareAdapterClassName ?: "$pkg.$adapterPrefix${baseName}IO",
                    mockAdapterClassName = mockAdapterClassName,
                    actionKeys = actionKeys,
                ),
                confirmationToken = confirmationToken,
            )
        }
        else -> throw IllegalArgumentException("Unsupported hybrid scaffold kind '$kindRaw'")
    }

    suspend fun scaffoldVendorBundle(
        projectRoot: File,
        leagueRaw: String,
        vendorProfileRaw: String,
        name: String,
        modeRaw: String = "PREVIEW",
        packageName: String? = null,
        deviceNameOrCanId: String? = null,
        registerHybrid: Boolean = true,
        actionKeys: List<String> = emptyList(),
        confirmationToken: String? = null,
    ): KotlinScaffoldResult {
        val canonical = projectRoot.canonicalFile
        val league = AresLeague.valueOf(leagueRaw.trim().uppercase())
        val profile = VendorSdkProfile.resolve(vendorProfileRaw)
        require(profile.requiredLeague == league) {
            "Vendor profile '${profile.name}' targets ${profile.requiredLeague.name}, not ${league.name}."
        }
        val baseClassName = name.trim()
            .removeSuffix("Subsystem")
            .removeSuffix("IO")
            .replaceFirstChar(Char::uppercaseChar)
        require(baseClassName.matches(Regex("[A-Z][A-Za-z0-9]{0,63}"))) {
            "Vendor bundle base name must be PascalCase Kotlin identifier: '$name'"
        }
        val pkg = packageName?.trim()?.takeIf { it.isNotBlank() } ?: when (league) {
            AresLeague.FTC -> "org.firstinspires.ftc.teamcode.extensions.${baseClassName.lowercase()}"
            AresLeague.FRC -> "org.aresfirst.starter.frc.extensions.${baseClassName.lowercase()}"
            AresLeague.XRP -> throw IllegalArgumentException("Vendor SDK bundles only support FTC and FRC.")
        }
        val rendered = VendorSdkScaffoldRenderer.renderBundle(
            profile = profile,
            packageName = pkg,
            baseClassName = baseClassName,
            deviceNameOrCanId = deviceNameOrCanId,
        )
        val pendingWrites = rendered.files.associate { it.relativePath to it.content }
        if (registerHybrid) {
            val docId = TemplateAndPatchSupport.sanitizeSlugId(baseClassName.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2"))
            val defaultActionKeys = if (actionKeys.isNotEmpty()) {
                actionKeys
            } else {
                listOf("$docId.configure", "$docId.enable", "$docId.disable")
            }
            val primaryCanId = when (profile) {
                VendorSdkProfile.FRC_TALONFX_POSITION,
                VendorSdkProfile.FRC_TALONFX_VELOCITY,
                VendorSdkProfile.FRC_SPARKMAX_BRUSHLESS,
                VendorSdkProfile.FRC_SPARKFLEX_VORTEX -> rendered.defaultCanId
                else -> null
            }
            val primaryHardwareMapName = when (profile) {
                VendorSdkProfile.FTC_DCMOTOREX_PIDF,
                VendorSdkProfile.FTC_SERVO_ACTUATOR,
                VendorSdkProfile.FTC_LIMELIGHT3A_PINPOINT -> rendered.defaultHardwareMapName
                else -> null
            }
            val primaryHardwareKind = when (profile) {
                VendorSdkProfile.FTC_SERVO_ACTUATOR -> SubsystemHardwareKind.POSITIONAL_SERVO
                else -> null
            }
            return registerExistingHybrid(
                projectRoot = canonical,
                modeRaw = modeRaw,
                request = HybridRegistrationRequest(
                    documentId = docId,
                    displayName = TemplateAndPatchSupport.toDisplayName(docId),
                    kotlinTypeName = "${baseClassName}Subsystem",
                    sourceFiles = rendered.files.map { it.relativePath },
                    subsystemClassName = rendered.subsystemClassName,
                    ioContractClassName = rendered.ioContractClassName,
                    hardwareAdapterClassName = rendered.hardwareAdapterClassName,
                    mockAdapterClassName = rendered.mockAdapterClassName,
                    actionKeys = defaultActionKeys,
                    primaryCanId = primaryCanId,
                    primaryHardwareMapName = primaryHardwareMapName,
                    primaryHardwareKind = primaryHardwareKind,
                ),
                extraPendingWrites = pendingWrites,
                confirmationToken = confirmationToken,
            )
        }

        val mode = modeRaw.trim().uppercase()
        require(mode == "PREVIEW" || mode == "APPLY") { "Unsupported mode '$modeRaw'; expected PREVIEW or APPLY." }
        val opKey = "VENDOR_BUNDLE::$pkg::$baseClassName"
        val digest = pendingWrites.entries.sortedBy { it.key }.joinToString("\n") { (rel, text) ->
            "${ConfirmationTokenManager.digestFileOrMissing(File(canonical, rel))}->${ConfirmationTokenManager.sha256Hex(text)}"
        }
        val previewPayload = rendered.files.joinToString("\n\n") { "// === ${it.relativePath} ===\n${it.content}" }
        val anyExists = rendered.files.any { File(canonical, it.relativePath).isFile }
        val primaryRel = rendered.files.first { it.relativePath.endsWith("Subsystem.kt") }.relativePath

        if (mode == "PREVIEW") {
            val token = engine.tokens.issueTargetToken(canonical, opKey, digest)
            return KotlinScaffoldResult(
                projectRoot = canonical.path,
                league = league.name,
                kind = "VENDOR_SUBSYSTEM_BUNDLE",
                mode = "PREVIEW",
                relativePath = primaryRel,
                targetExists = anyExists,
                confirmationToken = token,
                generatedCode = previewPayload,
                writtenPaths = rendered.files.map { it.relativePath },
                message = "Preview prepared for 4-file ${profile.name} bundle ($pkg.$baseClassName).",
            )
        }
        if (anyExists || !confirmationToken.isNullOrBlank()) {
            when (val check = engine.tokens.verifyAndConsumeTargetToken(canonical, opKey, digest, confirmationToken)) {
                is ConfirmationCheckResult.Rejected -> throw IllegalStateException(check.message)
                ConfirmationCheckResult.Approved -> Unit
            }
        }
        val snapshot = JGitSnapshotSafety.createSnapshot(canonical, slug = "before-vendor-bundle-$baseClassName")
        for ((rel, text) in pendingWrites) {
            val f = JGitSnapshotSafety.safeResolveChild(canonical, rel)
            JGitSnapshotSafety.writeBytesAtomically(f, text.toByteArray(Charsets.UTF_8))
        }
        return KotlinScaffoldResult(
            projectRoot = canonical.path,
            league = league.name,
            kind = "VENDOR_SUBSYSTEM_BUNDLE",
            mode = "APPLY",
            relativePath = primaryRel,
            targetExists = true,
            snapshotRef = snapshot.refName,
            generatedCode = previewPayload,
            writtenPaths = rendered.files.map { it.relativePath },
            message = "Wrote 4-file ${profile.name} bundle (${rendered.files.size} USER-OWNED files).",
        )
    }

    suspend fun registerExistingHybrid(
        projectRoot: File,
        modeRaw: String,
        request: HybridRegistrationRequest,
        extraPendingWrites: Map<String, String> = emptyMap(),
        confirmationToken: String? = null,
    ): KotlinScaffoldResult {
        val canonical = projectRoot.canonicalFile
        val bundle = engine.loadBundle(canonical)
        val metadata = requireNotNull(bundle.metadata) { "Missing .ares/project.json in ${canonical.path}" }
        val league = metadata.league
        require(league == AresLeague.FTC || league == AresLeague.FRC) {
            "REGISTER_EXISTING_HYBRID applies only to FTC and FRC projects (found ${league.name})."
        }

        val docId = TemplateAndPatchSupport.sanitizeSlugId(request.documentId)
        val validatedKotlinType = McpPathAndIdentifierValidators.validateSimpleTypeName(request.kotlinTypeName)
        val validatedSubsystemClass = McpPathAndIdentifierValidators.validateKotlinFqn(
            request.subsystemClassName,
            "subsystemClassName",
        )
        val validatedIoClass = McpPathAndIdentifierValidators.validateKotlinFqn(
            request.ioContractClassName,
            "ioContractClassName",
        )
        val validatedHardwareClass = McpPathAndIdentifierValidators.validateKotlinFqn(
            request.hardwareAdapterClassName,
            "hardwareAdapterClassName",
        )
        val validatedMockClass = request.mockAdapterClassName?.trim()?.takeIf { it.isNotBlank() }?.let {
            McpPathAndIdentifierValidators.validateKotlinFqn(it, "mockAdapterClassName")
        }
        val normalizedSources = request.sourceFiles.map { it.trim().replace('\\', '/') }.filter { it.isNotBlank() }.distinct()
        require(normalizedSources.isNotEmpty()) { "At least one repo-relative Kotlin sourceFile is required." }
        for (rel in normalizedSources) {
            if (rel !in extraPendingWrites) {
                val diskFile = JGitSnapshotSafety.safeResolveChild(canonical, rel)
                require(diskFile.isFile) { "Declared hand-authored source file does not exist on disk: $rel" }
            }
        }

        val platform = if (league == AresLeague.FTC) SubsystemPlatform.FTC else SubsystemPlatform.FRC
        val modulePath = resolveGradleModulePath(canonical, league)
        val simDoc = if (validatedMockClass != null) {
            SubsystemSimulationDocument(
                support = SubsystemSimulationSupport.HAND_AUTHORED_MOCK,
                adapterClassName = validatedMockClass,
            )
        } else {
            SubsystemSimulationDocument(support = SubsystemSimulationSupport.UNAVAILABLE)
        }

        val implementation = SubsystemImplementationDocument(
            kind = SubsystemImplementationKind.HAND_AUTHORED,
            ownership = SubsystemSourceOwnership.USER_OWNED,
            modulePath = modulePath,
            sourceFiles = normalizedSources,
            subsystemClassName = validatedSubsystemClass,
            ioContractClassName = validatedIoClass,
            hardwareAdapterClassName = validatedHardwareClass,
            simulation = simDoc,
            teaching = SubsystemTeachingDocument(
                level = SubsystemTeachingLevel.INTERMEDIATE,
                summary = "Team-owned Kotlin subsystem registered via ares_kotlin_scaffold.",
            ),
        )

        val descriptorRelPath = ".ares/subsystems/$docId.aressubsystem"
        val existingDescriptor = File(canonical, descriptorRelPath)
            .takeIf(File::isFile)
            ?.let { runCatching { SubsystemDocumentCodec.decode(it.readText()) }.getOrNull() }

        val cleanActionKeys = request.actionKeys.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val baseDoc = SubsystemTemplates.create(
            template = SubsystemTemplate.ADVANCED_CUSTOM,
            documentId = docId,
            kotlinTypeName = validatedKotlinType,
            platform = platform,
            displayName = request.displayName,
        )
        val updatedHardware = baseDoc.hardware.mapIndexed { idx, hw ->
            if (idx != 0) {
                hw
            } else {
                val nextConn = when {
                    request.primaryCanId != null -> SubsystemHardwareConnection(canId = request.primaryCanId, canBus = "rio")
                    request.primaryHardwareMapName != null -> SubsystemHardwareConnection(hardwareMapName = request.primaryHardwareMapName)
                    else -> hw.connection
                }
                hw.copy(
                    kind = request.primaryHardwareKind ?: hw.kind,
                    connection = nextConn,
                )
            }
        }
        val subsystemDoc = baseDoc.copy(
            uid = existingDescriptor?.uid ?: baseDoc.uid,
            revision = (existingDescriptor?.revision ?: 0) + 1,
            hardware = updatedHardware,
            capabilityActionKeys = cleanActionKeys,
            generateMockIo = false,
            generateTest = false,
            implementation = implementation,
        )
        val descriptorJson = SubsystemDocumentCodec.encode(subsystemDoc)

        val writes = linkedMapOf<String, String>()
        writes.putAll(extraPendingWrites)
        writes[descriptorRelPath] = descriptorJson

        if (metadata.authoringModel == AresProjectAuthoringModel.GUI_OWNED) {
            val promoted = metadata.copy(authoringModel = AresProjectAuthoringModel.HYBRID)
            writes[".ares/project.json"] = AresProjectMetadataCodec.encode(promoted)
        }

        if (cleanActionKeys.isNotEmpty()) {
            val catalogFile = File(canonical, ".ares/action-catalog.json")
            val currentCatalog = if (catalogFile.isFile) {
                CapabilityCatalogCodec.decode(catalogFile.readText())
            } else {
                CapabilityCatalogDocument(projectId = metadata.projectId)
            }
            val existingByKey = currentCatalog.actions.associateBy { it.key }
            val mergedActions = currentCatalog.actions.toMutableList()
            for (actionKey in cleanActionKeys) {
                if (actionKey !in existingByKey) {
                    val label = TemplateAndPatchSupport.toDisplayName(actionKey.replace('.', '-'))
                    mergedActions += ActionDescriptor(
                        key = actionKey,
                        displayName = label,
                        description = "Hybrid capability exposed by ${request.kotlinTypeName}.",
                    )
                }
            }
            val updatedCatalog = currentCatalog.copy(
                revision = currentCatalog.revision + 1,
                actions = mergedActions,
            )
            writes[".ares/action-catalog.json"] = CapabilityCatalogCodec.encode(updatedCatalog)
        }

        val extensionsRelPath = locateTeamRobotExtensionsRelPath(canonical, league)
        val extensionsFile = JGitSnapshotSafety.safeResolveChild(canonical, extensionsRelPath)
        val updatedExtensions = upsertHybridMarkerBlock(
            existingContent = if (extensionsFile.isFile) extensionsFile.readText() else defaultTeamExtensionsFile(league),
            docId = docId,
            subsystemClassName = validatedSubsystemClass,
            ioContractClassName = validatedIoClass,
        )
        writes[extensionsRelPath] = updatedExtensions

        val mode = modeRaw.trim().uppercase()
        require(mode == "PREVIEW" || mode == "APPLY") { "Unsupported mode '$modeRaw'; expected PREVIEW or APPLY." }

        val opKey = "HYBRID_REGISTER::$docId"
        val targetDigest = writes.entries
            .sortedBy { it.key }
            .joinToString("\n") { (rel, content) ->
                val diskDigest = ConfirmationTokenManager.digestFileOrMissing(File(canonical, rel))
                val nextDigest = ConfirmationTokenManager.sha256Hex(content)
                "$rel:$diskDigest->$nextDigest"
            }

        val previewPayload = writes.entries.joinToString("\n\n") { (rel, text) ->
            "// === $rel ===\n$text"
        }

        val existingUserOwnedConflict = writes.keys.any { rel ->
            val file = File(canonical, rel)
            file.isFile && !rel.startsWith(".ares/") && rel != extensionsRelPath
        }

        if (mode == "PREVIEW") {
            val token = engine.tokens.issueTargetToken(canonical, opKey, targetDigest)
            return KotlinScaffoldResult(
                projectRoot = canonical.path,
                league = league.name,
                kind = if (extraPendingWrites.isEmpty()) "REGISTER_EXISTING_HYBRID" else "VENDOR_SUBSYSTEM_BUNDLE",
                mode = "PREVIEW",
                relativePath = descriptorRelPath,
                targetExists = existingDescriptor != null || existingUserOwnedConflict,
                confirmationToken = token,
                generatedCode = previewPayload,
                writtenPaths = writes.keys.toList(),
                registeredSubsystemId = docId,
                message = "Preview prepared for hybrid subsystem '$docId' (${writes.size} file(s)). Pass confirmationToken to APPLY if overwriting existing files.",
            )
        }

        if (existingUserOwnedConflict || !confirmationToken.isNullOrBlank()) {
            when (val check = engine.tokens.verifyAndConsumeTargetToken(canonical, opKey, targetDigest, confirmationToken)) {
                is ConfirmationCheckResult.Rejected -> throw IllegalStateException(check.message)
                ConfirmationCheckResult.Approved -> Unit
            }
        }

        val batchResult = TemplateAndPatchSupport.applyTransactionalFileBatch(
            engine = engine,
            projectRoot = canonical,
            slug = "hybrid-$docId",
            documentKind = "SUBSYSTEM",
            operation = "REGISTER_HYBRID",
            documentId = docId,
            primaryRelativePath = descriptorRelPath,
            writes = writes,
            message = "Registered HAND_AUTHORED hybrid subsystem '$docId' ($modulePath) across ${writes.size} file(s).",
        )

        return KotlinScaffoldResult(
            projectRoot = canonical.path,
            league = league.name,
            kind = if (extraPendingWrites.isEmpty()) "REGISTER_EXISTING_HYBRID" else "VENDOR_SUBSYSTEM_BUNDLE",
            mode = "APPLY",
            relativePath = descriptorRelPath,
            targetExists = true,
            snapshotRef = batchResult.snapshotRef,
            generatedCode = previewPayload,
            writtenPaths = writes.keys.toList(),
            registeredSubsystemId = docId,
            validation = batchResult.validation,
            message = batchResult.message,
        )
    }

    internal fun resolveGradleModulePath(projectRoot: File, league: AresLeague): String {
        if (league == AresLeague.FTC) return ":TeamCode"
        val settingsFiles = listOf(File(projectRoot, "settings.gradle.kts"), File(projectRoot, "settings.gradle"))
        val nameRegex = Regex("""rootProject\.name\s*=\s*['"]([A-Za-z0-9_.-]+)['"]""")
        for (file in settingsFiles) {
            if (!file.isFile) continue
            val match = nameRegex.find(file.readText())
            val found = match?.groupValues?.get(1)?.trim()
            if (!found.isNullOrBlank()) return ":$found"
        }
        return ":ARES-FRC-Starter"
    }

    private fun locateTeamRobotExtensionsRelPath(projectRoot: File, league: AresLeague): String {
        val searchRoots = if (league == AresLeague.FTC) {
            listOf(
                "TeamCode/src/main/kotlin/org/firstinspires/ftc/teamcode/extensions/TeamRobotExtensions.kt",
                "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/extensions/TeamRobotExtensions.kt",
            )
        } else {
            listOf("src/main/kotlin/org/aresfirst/starter/frc/extensions/TeamRobotExtensions.kt")
        }
        searchRoots.firstOrNull { File(projectRoot, it).isFile }?.let { return it }

        val excludedWalkDirs = setOf(".git", ".gradle", "build", "out", ".idea")
        val walked = projectRoot.walkTopDown()
            .onEnter { dir -> dir == projectRoot || dir.name !in excludedWalkDirs }
            .firstOrNull { it.isFile && it.name == "TeamRobotExtensions.kt" }
        if (walked != null) {
            return walked.relativeTo(projectRoot).invariantSeparatorsPath
        }
        return searchRoots.first()
    }

    private fun upsertHybridMarkerBlock(
        existingContent: String,
        docId: String,
        subsystemClassName: String,
        ioContractClassName: String,
    ): String {
        val existingEntries = linkedMapOf<String, Pair<String, String>>()
        val startIdx = existingContent.indexOf(BEGIN_MARKER)
        val endIdx = existingContent.indexOf(END_MARKER)
        if (startIdx >= 0 && endIdx > startIdx) {
            val block = existingContent.substring(startIdx + BEGIN_MARKER.length, endIdx)
            val rowRegex = Regex("""const\s+val\s+[A-Z0-9_]+_SUBSYSTEM_CLASS\s*=\s*"([^"]+)".*//\s*id=([a-z0-9-]+)\s+io=([^\s]+)""")
            rowRegex.findAll(block).forEach { m ->
                existingEntries[m.groupValues[2]] = m.groupValues[1] to m.groupValues[3]
            }
        }
        existingEntries[docId] = subsystemClassName to ioContractClassName

        val renderedEntries = existingEntries.entries
            .sortedBy { it.key }
            .joinToString("\n") { (id, classes) ->
                val constPrefix = id.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_').ifBlank { "SUBSYSTEM" }
                "    const val ${constPrefix}_SUBSYSTEM_CLASS = \"${classes.first}\" // id=$id io=${classes.second}"
            }

        val markerBlock = buildString {
            appendLine(BEGIN_MARKER)
            appendLine("/** Managed by ares-project-mcp between markers; user code outside markers is preserved. */")
            appendLine("internal object AresHybridSubsystemIndex {")
            appendLine(renderedEntries)
            appendLine("}")
            append(END_MARKER)
        }

        val normalized = existingContent.replace("\r\n", "\n")
        return if (startIdx >= 0 && endIdx > startIdx) {
            val before = normalized.substring(0, normalized.indexOf(BEGIN_MARKER)).trimEnd()
            val after = normalized.substring(normalized.indexOf(END_MARKER) + END_MARKER.length).trimStart()
            buildString {
                append(before)
                append("\n\n")
                append(markerBlock)
                if (after.isNotBlank()) {
                    append("\n\n")
                    append(after)
                } else {
                    append('\n')
                }
            }
        } else {
            normalized.trimEnd() + "\n\n" + markerBlock + "\n"
        }
    }

    private fun defaultTeamExtensionsFile(league: AresLeague): String {
        val pkg = if (league == AresLeague.FTC) {
            "org.firstinspires.ftc.teamcode.extensions"
        } else {
            "org.aresfirst.starter.frc.extensions"
        }
        return """
            |// ARES OWNERSHIP: USER-OWNED
            |package $pkg
            |
            |/** Team-owned extension entry point preserved by ARES Studio and MCP. */
            |object TeamRobotExtensions
            |
        """.trimMargin()
    }

    private companion object {
        const val BEGIN_MARKER = "// BEGIN ARES-MCP HYBRID SUBSYSTEMS"
        const val END_MARKER = "// END ARES-MCP HYBRID SUBSYSTEMS"
    }
}
