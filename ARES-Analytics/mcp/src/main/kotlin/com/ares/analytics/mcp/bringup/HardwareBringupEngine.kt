package com.ares.analytics.mcp.bringup

import com.ares.analytics.mcp.engine.ConfirmationCheckResult
import com.ares.analytics.mcp.engine.ConfirmationTokenManager
import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.JGitSnapshotSafety
import com.ares.analytics.mcp.engine.TemplateAndPatchSupport
import com.areslib.drivetrain.CtreSwerveImportDocument
import com.areslib.drivetrain.DrivetrainComponentRole
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.drivetrain.DrivetrainKind
import com.areslib.project.AresLeague
import com.areslib.project.model.RobotProjectSnapshot
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.tuning.TuningAssignment
import com.areslib.tuning.TuningProfileAuthority
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningProfileDocumentCodec
import com.google.gson.JsonParser
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.abs

@Serializable
data class HardwareAuditIssue(
    val severity: String,
    val code: String,
    val path: String,
    val message: String,
)

@Serializable
data class HardwareBringupResult(
    val projectRoot: String,
    val league: String,
    val operation: String,
    val passed: Boolean,
    val physicalValidationStatus: String = "NOT_RUN",
    val issueCount: Int = 0,
    val issues: List<HardwareAuditIssue> = emptyList(),
    val installedVendordeps: List<InstalledVendordepSummary> = emptyList(),
    val generatedPath: String? = null,
    val generatedContent: String? = null,
    val pendingConfirmation: Boolean = false,
    val confirmationToken: String? = null,
    val snapshotRef: String? = null,
    val message: String,
)

/**
 * Implements `ares_hardware_bringup` operations without ever acquiring hardware leases or modifying
 * vendor-owned `TunerConstants.java`.
 */
internal class HardwareBringupEngine(
    private val engine: HeadlessProjectEngine,
    private val templateAndPatch: TemplateAndPatchSupport,
) {
    suspend fun execute(
        projectRoot: File,
        operationRaw: String,
        tunerConstantsPath: String? = null,
        vendordepPreset: String? = null,
        vendordepJson: String? = null,
        xmlOutputPath: String? = null,
        xmlContent: String? = null,
        confirmationToken: String? = null,
    ): HardwareBringupResult {
        val canonical = projectRoot.canonicalFile
        val bundle = engine.loadBundle(canonical)
        val metadata = requireNotNull(bundle.metadata) { "Missing .ares/project.json in ${canonical.path}" }
        val league = metadata.league
        val op = operationRaw.trim().uppercase()

        return when (op) {
            "AUDIT_INVENTORY" -> auditInventory(canonical, league, bundle.snapshot)
            "LIST_VENDORDEPS" -> listVendordeps(canonical, league)
            "INSTALL_VENDORDEP" -> installVendordep(canonical, league, vendordepPreset, vendordepJson)
            "IMPORT_CTRE_TUNER_CONSTANTS" -> importCtreTunerConstants(canonical, league, tunerConstantsPath)
            "GENERATE_FTC_HUB_XML" -> generateFtcHubXml(canonical, league, bundle.snapshot, xmlOutputPath, confirmationToken)
            "VALIDATE_FTC_HUB_XML" -> validateFtcHubXml(canonical, league, xmlContent, xmlOutputPath)
            else -> throw IllegalArgumentException(
                "Unsupported bring-up operation '$operationRaw'; expected AUDIT_INVENTORY, LIST_VENDORDEPS, " +
                    "INSTALL_VENDORDEP, IMPORT_CTRE_TUNER_CONSTANTS, GENERATE_FTC_HUB_XML, or VALIDATE_FTC_HUB_XML.",
            )
        }
    }

    private fun auditInventory(
        canonical: File,
        league: AresLeague,
        snapshot: RobotProjectSnapshot,
    ): HardwareBringupResult {
        val issues = mutableListOf<HardwareAuditIssue>()

        if (league == AresLeague.FTC) {
            val motorsCount = snapshot.subsystems.sumOf { s -> s.hardware.count { it.kind == SubsystemHardwareKind.MOTOR } } +
                (snapshot.drivetrains.firstOrNull()?.components?.count { it.role == DrivetrainComponentRole.DRIVE_MOTOR } ?: 0)
            val servosCount = snapshot.subsystems.sumOf { s ->
                s.hardware.count { it.kind == SubsystemHardwareKind.POSITIONAL_SERVO || it.kind == SubsystemHardwareKind.CONTINUOUS_SERVO }
            }
            if (motorsCount > FtcRevHubXmlGenerator.MAX_MOTORS_PER_HUB * 2) {
                issues += HardwareAuditIssue("ERROR", "FTC_TOTAL_MOTOR_BUDGET", ".ares", "Total declared FTC motors ($motorsCount) exceed 2 REV Hubs (8 ports).")
            }
            if (servosCount > FtcRevHubXmlGenerator.MAX_SERVOS_PER_HUB * 2) {
                issues += HardwareAuditIssue("ERROR", "FTC_TOTAL_SERVO_BUDGET", ".ares", "Total declared FTC servos ($servosCount) exceed 2 REV Hubs (12 ports).")
            }
        }

        // Duplicate hardwareId across subsystems & drivetrain
        val allHardwareIds = mutableListOf<Pair<String, String>>()
        snapshot.drivetrains.firstOrNull()?.components?.forEach { c ->
            allHardwareIds += ".ares/drivetrains" to c.hardwareId
        }
        snapshot.subsystems.forEach { s ->
            s.hardware.forEach { h -> allHardwareIds += ".ares/subsystems/${s.documentId}.aressubsystem" to h.hardwareId }
        }
        val dupIds = allHardwareIds.groupBy { it.second }.filterValues { it.size > 1 }
        for ((hwId, refs) in dupIds) {
            issues += HardwareAuditIssue(
                severity = "ERROR",
                code = "HARDWARE_ID_COLLISION",
                path = refs.joinToString(", ") { it.first },
                message = "Hardware ID '$hwId' is declared ${refs.size} times across drivetrain/subsystems.",
            )
        }

        // Duplicate FRC CTRE CAN IDs or missing vendordeps
        val installedDeps = FrcVendordepCatalog.listInstalled(canonical)
        if (league == AresLeague.FRC) {
            val uuids = installedDeps.map { it.uuid }.toSet()
            installedDeps.forEach { dep ->
                dep.conflictingUuids.filter { it in uuids }.forEach { conflict ->
                    issues += HardwareAuditIssue("ERROR", "VENDORDEP_CONFLICT", "vendordeps/${dep.fileName}", "Installed vendordep '${dep.name}' conflicts with UUID '$conflict'.")
                }
            }
            checkMissingVendordepImports(canonical, installedDeps, issues)
            val canPattern = Regex("""\b(\w+)_can_(\d+)\b""")
            val seenCan = hashMapOf<Pair<String, Int>, String>()
            for ((src, hwId) in allHardwareIds) {
                val m = canPattern.find(hwId) ?: continue
                val key = m.groupValues[1] to m.groupValues[2].toInt()
                val prev = seenCan.putIfAbsent(key, src)
                if (prev != null) {
                    issues += HardwareAuditIssue("ERROR", "CAN_ID_COLLISION", src, "CAN ID ${key.second} on bus '${key.first}' collides with $prev.")
                }
            }
        }

        // Validate existing FTC XML config if present under TeamCode or .ares
        val excludedDirs = setOf(".git", ".gradle", "build", "out", ".idea")
        val xmlFiles = canonical.walkTopDown()
            .onEnter { dir -> dir == canonical || dir.name !in excludedDirs }
            .filter { it.isFile && it.extension.equals("xml", true) }
            .filter { f -> runCatching { f.readText().contains("<LynxModule") }.getOrDefault(false) }
            .toList()
        for (xmlFile in xmlFiles) {
            issues += FtcRevHubXmlGenerator.validateXml(xmlFile.readText())
        }

        val hasError = issues.any { it.severity == "ERROR" }
        return HardwareBringupResult(
            projectRoot = canonical.path,
            league = league.name,
            operation = "AUDIT_INVENTORY",
            passed = !hasError,
            physicalValidationStatus = "NOT_RUN",
            issueCount = issues.size,
            issues = issues,
            installedVendordeps = installedDeps,
            message = if (hasError) "Hardware inventory audit found ${issues.count { it.severity == "ERROR" }} error(s)." else "Hardware inventory audit passed (${issues.size} warning(s)). Physical robot validation remains NOT_RUN.",
        )
    }

    private fun checkMissingVendordepImports(
        canonical: File,
        installed: List<InstalledVendordepSummary>,
        issues: MutableList<HardwareAuditIssue>,
    ) {
        val installedUuids = installed.map { it.uuid }.toSet()
        val srcDir = File(canonical, "src/main")
        if (!srcDir.isDirectory) return
        val codeLines = srcDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .flatMap { file ->
                com.ares.analytics.mcp.audit.KotlinInvariantAuditor.scanSource(file.name, file.readText()).codeLines.asSequence()
            }
            .toList()
        for (preset in FrcVendordepCatalog.presets) {
            val importPrefix = "import ${preset.importedPackagePrefix}."
            if (codeLines.any { it.contains(importPrefix) } && preset.uuid !in installedUuids) {
                issues += HardwareAuditIssue(
                    severity = "ERROR",
                    code = "MISSING_VENDORDEP",
                    path = "vendordeps/${preset.fileName}",
                    message = "Robot source imports '${preset.importedPackagePrefix}.*' without installed vendordep '${preset.presetId}' (${preset.fileName}).",
                )
            }
        }
    }

    private fun listVendordeps(canonical: File, league: AresLeague): HardwareBringupResult {
        require(league == AresLeague.FRC) { "vendordeps/*.json applies only to FRC projects (found ${league.name})." }
        val installed = FrcVendordepCatalog.listInstalled(canonical)
        val availableIds = FrcVendordepCatalog.presets.joinToString(", ") { it.presetId }
        return HardwareBringupResult(
            projectRoot = canonical.path,
            league = league.name,
            operation = "LIST_VENDORDEPS",
            passed = true,
            installedVendordeps = installed,
            message = "Found ${installed.size} installed vendordep(s). Bundled presets: $availableIds.",
        )
    }

    private fun installVendordep(
        canonical: File,
        league: AresLeague,
        presetRaw: String?,
        customJson: String?,
    ): HardwareBringupResult {
        require(league == AresLeague.FRC) { "INSTALL_VENDORDEP applies only to FRC projects (found ${league.name})." }
        val (fileName, content, summary) = if (!customJson.isNullOrBlank()) {
            val parsed = FrcVendordepCatalog.parseSummaryText("custom-vendordep.json", customJson)
                ?: throw IllegalArgumentException("Invalid vendordepJson format.")
            Triple(parsed.fileName, customJson, parsed)
        } else {
            val preset = FrcVendordepCatalog.resolvePreset(
                requireNotNull(presetRaw?.takeIf { it.isNotBlank() }) { "vendordepPreset or vendordepJson is required." },
            )
            val parsed = requireNotNull(FrcVendordepCatalog.parseSummaryText(preset.fileName, preset.jsonContent))
            Triple(preset.fileName, preset.jsonContent, parsed)
        }
        require(SAFE_VENDORDEP_FILENAME_REGEX.matches(fileName)) {
            "Invalid vendordep fileName '$fileName': must be a basename ending in .json without path separators."
        }
        val relPath = "vendordeps/$fileName"
        val targetFile = JGitSnapshotSafety.safeResolveChild(canonical, relPath, requiredSubdir = "vendordeps")
        val snapshot = JGitSnapshotSafety.createSnapshot(canonical, slug = "before-vendordep-${summary.name.lowercase()}")
        JGitSnapshotSafety.writeBytesAtomically(targetFile, content.toByteArray(Charsets.UTF_8))
        return HardwareBringupResult(
            projectRoot = canonical.path,
            league = league.name,
            operation = "INSTALL_VENDORDEP",
            passed = true,
            installedVendordeps = FrcVendordepCatalog.listInstalled(canonical),
            generatedPath = relPath,
            snapshotRef = snapshot.refName,
            message = "Installed vendordep '${summary.name}' (${summary.version}) to $relPath.",
        )
    }

    private suspend fun importCtreTunerConstants(
        canonical: File,
        league: AresLeague,
        tunerConstantsPathRaw: String?,
    ): HardwareBringupResult {
        require(league == AresLeague.FRC) { "IMPORT_CTRE_TUNER_CONSTANTS applies only to FRC projects." }
        val relOrPath = tunerConstantsPathRaw?.trim()?.takeIf { it.isNotBlank() }
            ?: "src/main/java/frc/robot/generated/TunerConstants.java"
        val candidate = File(relOrPath).let { f -> if (f.isAbsolute) f.canonicalFile else JGitSnapshotSafety.safeResolveChild(canonical, relOrPath) }
        require(candidate.toPath().startsWith(canonical.toPath())) {
            "TunerConstants.java must reside inside the project root so provenance remains project-relative: $relOrPath"
        }
        require(candidate.isFile) { "TunerConstants.java not found at $relOrPath" }

        val relSource = candidate.relativeTo(canonical).invariantSeparatorsPath
        val sourceText = candidate.readText()
        val parsed = parseCtreTunerConstants(sourceText)
        val sourceSha = ConfirmationTokenManager.sha256Hex(sourceText)

        // Ensure or update canonical swerve drivetrain
        val bundle = engine.loadBundle(canonical)
        val metadata = requireNotNull(bundle.metadata)
        val existingProfile = bundle.snapshot.tuningProfiles.firstOrNull { it.authority == TuningProfileAuthority.CANONICAL_CHECKED_IN }
        val profileUid = existingProfile?.uid ?: "${metadata.projectId.lowercase()}.profile.competition"
        val existingSwerve = bundle.snapshot.drivetrains.firstOrNull { it.kind == DrivetrainKind.FRC_CTRE_SWERVE }
            ?: templateAndPatch.buildDrivetrainPresetDocument(
                presetRaw = "FRC_CTRE_SWERVE",
                league = league,
                drivebaseId = "main-drive",
                driveUid = "${metadata.projectId.lowercase()}.drive.main-drive",
                canonicalProfileUid = profileUid,
            )

        val pkgLine = Regex("""^\s*package\s+([A-Za-z0-9_.]+)\s*;""", RegexOption.MULTILINE).find(sourceText)?.groupValues?.get(1)
        val fqClass = if (pkgLine != null) "$pkgLine.TunerConstants" else "frc.robot.generated.TunerConstants"
        val safeBus = parsed.canBusName.lowercase().replace(Regex("[^a-z0-9]+"), "")

        val updatedComponents = existingSwerve.components.map { comp ->
            val cornerKey = when {
                comp.uid.contains("front-left") -> "FrontLeft"
                comp.uid.contains("front-right") -> "FrontRight"
                comp.uid.contains("back-left") || comp.uid.contains("rear-left") -> "BackLeft"
                comp.uid.contains("back-right") || comp.uid.contains("rear-right") -> "BackRight"
                else -> null
            }
            when {
                comp.role == DrivetrainComponentRole.GYRO && parsed.pigeonId != null ->
                    comp.copy(hardwareId = "${safeBus}_can_${parsed.pigeonId}")
                cornerKey != null && comp.role == DrivetrainComponentRole.DRIVE_MOTOR -> {
                    val id = parsed.ids["${cornerKey}DriveMotorId"]
                    val inv = parsed.invertLeftRight(cornerKey) ?: comp.inverted
                    comp.copy(hardwareId = id?.let { "${safeBus}_can_$it" } ?: comp.hardwareId, inverted = inv)
                }
                cornerKey != null && comp.role == DrivetrainComponentRole.STEER_MOTOR -> {
                    val id = parsed.ids["${cornerKey}SteerMotorId"]
                    val inv = parsed.booleans["${cornerKey}SteerMotorInverted"] ?: comp.inverted
                    comp.copy(hardwareId = id?.let { "${safeBus}_can_$it" } ?: comp.hardwareId, inverted = inv)
                }
                cornerKey != null && comp.role == DrivetrainComponentRole.ABSOLUTE_ENCODER -> {
                    val id = parsed.ids["${cornerKey}EncoderId"]
                    val inv = parsed.booleans["${cornerKey}EncoderInverted"] ?: comp.inverted
                    comp.copy(hardwareId = id?.let { "${safeBus}_can_$it" } ?: comp.hardwareId, inverted = inv)
                }
                else -> comp
            }
        }

        val updatedDoc = existingSwerve.copy(
            geometry = existingSwerve.geometry.copy(
                wheelDiameterMeters = parsed.wheelRadiusMeters * 2.0,
                trackWidthMeters = parsed.trackWidthMeters,
                wheelBaseMeters = parsed.wheelBaseMeters,
                driveGearRatio = parsed.driveGearRatio,
                steerGearRatio = parsed.steerGearRatio,
                maxLinearSpeedMetersPerSecond = parsed.speedAt12VoltsMps ?: existingSwerve.geometry.maxLinearSpeedMetersPerSecond,
            ),
            components = updatedComponents,
            ctreImport = CtreSwerveImportDocument(
                vendorSourcePath = relSource,
                sourceSha256 = sourceSha,
                generatorName = "CTRE Tuner",
                generatorVersion = "2026",
                drivetrainConstantsClassName = fqClass,
                canBusName = parsed.canBusName,
            ),
        )

        val existingDriveFile = File(canonical, ".ares/drivetrains")
            .listFiles { f -> f.isFile && f.extension == "aresdrivetrain" }
            ?.sortedBy { it.name }
            ?.firstOrNull()
        val targetRelPath = existingDriveFile?.relativeTo(canonical)?.invariantSeparatorsPath
            ?: ".ares/drivetrains/${updatedDoc.drivebaseId}.aresdrivetrain"
        val writes = linkedMapOf(targetRelPath to DrivetrainDocumentCodec.encode(updatedDoc))
        val nonDriveDecls = bundle.snapshot.subsystems.flatMap { it.tuningParameters } +
            bundle.snapshot.tuningComponents.flatMap { it.parameters }
        val allDecls = (updatedDoc.parameters + nonDriveDecls).associateBy { it.uid }
        val existingValues = existingProfile?.values?.filter { it.parameterUid in allDecls }?.associateBy { it.parameterUid }.orEmpty()
        val assignments = updatedDoc.parameters.map { existingValues[it.uid] ?: TuningAssignment(it.uid, it.defaultValue) } +
            existingValues.values.filter { it.parameterUid !in updatedDoc.parameters.map { p -> p.uid }.toSet() }
        val profileDoc = (existingProfile ?: TuningProfileDocument(
            uid = profileUid,
            profileId = "competition",
            displayName = "Competition Baseline",
            description = "Synced CTRE swerve profile",
            projectId = metadata.projectId,
            drivebaseUid = updatedDoc.uid,
            authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
            values = assignments,
        )).copy(drivebaseUid = updatedDoc.uid, values = assignments.distinctBy { it.parameterUid }.sortedBy { it.parameterUid })
        val profileFile = File(canonical, ".ares/tuning").listFiles { f -> f.isFile && f.extension == "arestuning" }
            ?.firstOrNull { f -> runCatching { JsonParser.parseString(f.readText()).asJsonObject.get("uid")?.asString }.getOrNull() == profileUid }
            ?: File(canonical, ".ares/tuning/competition.arestuning")
        writes[profileFile.relativeTo(canonical).invariantSeparatorsPath] = TuningProfileDocumentCodec.encode(profileDoc, allDecls.values)

        val batch = TemplateAndPatchSupport.applyTransactionalFileBatch(
            engine = engine,
            projectRoot = canonical,
            slug = "import-ctre-tuner",
            documentKind = "DRIVETRAIN",
            operation = "IMPORT_CTRE_TUNER_CONSTANTS",
            documentId = updatedDoc.drivebaseId,
            primaryRelativePath = targetRelPath,
            writes = writes,
            message = "Imported read-only CTRE TunerConstants snapshot ($relSource, bus=${parsed.canBusName}) without modifying vendor file.",
        )

        return HardwareBringupResult(
            projectRoot = canonical.path,
            league = league.name,
            operation = "IMPORT_CTRE_TUNER_CONSTANTS",
            passed = true,
            generatedPath = targetRelPath,
            snapshotRef = batch.snapshotRef,
            message = batch.message,
        )
    }

    private fun generateFtcHubXml(
        canonical: File,
        league: AresLeague,
        snapshot: RobotProjectSnapshot,
        xmlOutputPath: String?,
        confirmationToken: String?,
    ): HardwareBringupResult {
        require(league == AresLeague.FTC) { "GENERATE_FTC_HUB_XML applies only to FTC projects." }
        val relPath = xmlOutputPath?.trim()?.takeIf { it.isNotBlank() } ?: ".ares/hardware/ftc-robot-config.xml"
        val normalizedXmlRel = relPath.replace('\\', '/')
        require(normalizedXmlRel.endsWith(".xml", ignoreCase = true)) {
            "xmlOutputPath must target an .xml file (got '$relPath')."
        }
        require(!normalizedXmlRel.startsWith(".git/") && !normalizedXmlRel.contains("/.git/")) {
            "xmlOutputPath cannot target .git metadata."
        }
        val targetFile = JGitSnapshotSafety.safeResolveChild(canonical, normalizedXmlRel)
        val xml = FtcRevHubXmlGenerator.generateFromSnapshot(snapshot)
        val issues = FtcRevHubXmlGenerator.validateXml(xml)
        val opKey = "FTC_HUB_XML::$relPath"
        val digest = ConfirmationTokenManager.digestFileOrMissing(targetFile) + "::" + ConfirmationTokenManager.sha256Hex(xml)

        if (targetFile.isFile && confirmationToken.isNullOrBlank()) {
            val token = engine.tokens.issueTargetToken(canonical, opKey, digest)
            return HardwareBringupResult(
                projectRoot = canonical.path,
                league = league.name,
                operation = "PREVIEW_FTC_HUB_XML",
                passed = issues.none { it.severity == "ERROR" },
                issueCount = issues.size,
                issues = issues,
                generatedPath = relPath,
                generatedContent = xml,
                pendingConfirmation = true,
                confirmationToken = token,
                message = "Preview generated for $relPath. Pass confirmationToken to overwrite.",
            )
        }
        if (targetFile.isFile || !confirmationToken.isNullOrBlank()) {
            when (val check = engine.tokens.verifyAndConsumeTargetToken(canonical, opKey, digest, confirmationToken)) {
                is ConfirmationCheckResult.Rejected -> throw IllegalStateException(check.message)
                ConfirmationCheckResult.Approved -> Unit
            }
        }
        val snap = JGitSnapshotSafety.createSnapshot(canonical, slug = "before-ftc-hub-xml")
        JGitSnapshotSafety.writeBytesAtomically(targetFile, xml.toByteArray(Charsets.UTF_8))
        return HardwareBringupResult(
            projectRoot = canonical.path,
            league = league.name,
            operation = "GENERATE_FTC_HUB_XML",
            passed = issues.none { it.severity == "ERROR" },
            issueCount = issues.size,
            issues = issues,
            generatedPath = relPath,
            generatedContent = xml,
            snapshotRef = snap.refName,
            message = "Generated FTC REV Hub XML configuration at $relPath.",
        )
    }

    private fun validateFtcHubXml(
        canonical: File,
        league: AresLeague,
        xmlContent: String?,
        xmlPath: String?,
    ): HardwareBringupResult {
        require(league == AresLeague.FTC) { "VALIDATE_FTC_HUB_XML applies only to FTC projects." }
        val payload = when {
            !xmlContent.isNullOrBlank() -> xmlContent
            !xmlPath.isNullOrBlank() -> JGitSnapshotSafety.safeResolveChild(canonical, xmlPath).also {
                require(it.isFile) { "FTC XML config not found at $xmlPath" }
            }.readText()
            else -> throw IllegalArgumentException("Either xmlContent or xmlOutputPath is required for VALIDATE_FTC_HUB_XML.")
        }
        val issues = FtcRevHubXmlGenerator.validateXml(payload)
        val hasError = issues.any { it.severity == "ERROR" }
        return HardwareBringupResult(
            projectRoot = canonical.path,
            league = league.name,
            operation = "VALIDATE_FTC_HUB_XML",
            passed = !hasError,
            issueCount = issues.size,
            issues = issues,
            message = if (hasError) "FTC REV Hub XML validation failed with ${issues.count { it.severity == "ERROR" }} error(s)." else "FTC REV Hub XML passed REV Hub port budget & device name checks.",
        )
    }

    private data class ParsedCtreConstants(
        val canBusName: String,
        val ids: Map<String, Int>,
        val booleans: Map<String, Boolean>,
        val pigeonId: Int?,
        val wheelRadiusMeters: Double,
        val trackWidthMeters: Double,
        val wheelBaseMeters: Double,
        val driveGearRatio: Double,
        val steerGearRatio: Double,
        val speedAt12VoltsMps: Double?,
    ) {
        fun invertLeftRight(cornerKey: String): Boolean? = if (cornerKey.endsWith("Left")) {
            booleans["InvertLeftSide"]
        } else {
            booleans["InvertRightSide"]
        }
    }

    private fun parseCtreTunerConstants(text: String): ParsedCtreConstants {
        val assignRegex = Regex("""(?:public|private|protected)\s+static\s+(?:final\s+)?[\w<>,.? ]+\s+(k\w+)\s*=\s*([^;]+);""")
        val raw = assignRegex.findAll(text).associate { it.groupValues[1] to it.groupValues[2].trim().removeSurrounding("\"") }
        val canBus = Regex("""new\s+CANBus\(\s*"([^"]+)"""").find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: raw["kCANBus"]?.takeIf { it.isNotBlank() }
            ?: raw["CANBus"]?.takeIf { it.isNotBlank() }
            ?: "rio"

        val idRegex = Regex("""(?i)k?(FrontLeft|FrontRight|BackLeft|BackRight|RearLeft|RearRight)(DriveMotorId|SteerMotorId|EncoderId)""")
        val ids = hashMapOf<String, Int>()
        raw.forEach { (k, v) ->
            val m = idRegex.matchEntire(k) ?: return@forEach
            val corner = m.groupValues[1].replace("Rear", "Back")
            val kind = m.groupValues[2]
            v.toIntOrNull()?.let { ids["$corner$kind"] = it }
        }
        val booleans = hashMapOf<String, Boolean>()
        raw["kInvertLeftSide"]?.toBooleanStrictOrNull()?.let { booleans["InvertLeftSide"] = it }
        raw["kInvertRightSide"]?.toBooleanStrictOrNull()?.let { booleans["InvertRightSide"] = it }
        listOf("FrontLeft", "FrontRight", "BackLeft", "BackRight").forEach { corner ->
            raw["k${corner}SteerMotorInverted"]?.toBooleanStrictOrNull()?.let { booleans["${corner}SteerMotorInverted"] = it }
            raw["k${corner}EncoderInverted"]?.toBooleanStrictOrNull()?.let { booleans["${corner}EncoderInverted"] = it }
        }
        fun scalar(name: String): Double? {
            val expr = raw[name] ?: return null
            expr.toDoubleOrNull()?.let { return it }
            val m = Regex("""(Inches|Meters|MetersPerSecond|FeetPerSecond|Rotations|Degrees)\.of\(\s*(-?\d+(?:\.\d+)?)\s*\)""").find(expr) ?: return null
            val num = m.groupValues[2].toDouble()
            return when (m.groupValues[1]) {
                "Inches" -> num * 0.0254
                "Meters", "MetersPerSecond", "Rotations" -> num
                "FeetPerSecond" -> num * 0.3048
                "Degrees" -> num / 360.0
                else -> null
            }
        }
        val wheelRadius = scalar("kWheelRadius") ?: scalar("WheelRadius") ?: 0.0508
        val trackWidth = scalar("kTrackWidth") ?: scalar("TrackWidth") ?: scalar("kFrontLeftYPos")?.let { abs(it) * 2.0 } ?: 0.56
        val wheelBase = scalar("kWheelBase") ?: scalar("WheelBase") ?: scalar("kFrontLeftXPos")?.let { abs(it) * 2.0 } ?: 0.56
        val driveRatio = scalar("kDriveGearRatio") ?: 6.12
        val steerRatio = scalar("kSteerGearRatio") ?: 21.42857
        val speedAt12 = scalar("kSpeedAt12Volts")
            ?: Regex("""SPEED_AT_12_VOLTS\s*=\s*(-?\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val pigeonId = raw["kPigeonId"]?.toIntOrNull()
        require(ids.isNotEmpty()) { "No swerve CAN IDs found in TunerConstants.java." }
        return ParsedCtreConstants(canBus, ids, booleans, pigeonId, wheelRadius, trackWidth, wheelBase, driveRatio, steerRatio, speedAt12)
    }

    companion object {
        private val SAFE_VENDORDEP_FILENAME_REGEX = Regex("""^[A-Za-z0-9._-]{1,120}\.json$""")
    }
}
