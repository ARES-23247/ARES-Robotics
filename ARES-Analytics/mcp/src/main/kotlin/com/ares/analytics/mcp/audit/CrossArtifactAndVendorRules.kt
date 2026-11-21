package com.ares.analytics.mcp.audit

import com.ares.analytics.mcp.bringup.FrcVendordepCatalog
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.subsystem.SubsystemDocumentCodec
import java.io.File

/**
 * Checks rules `ARES-HW-009` through `ARES-COORD-012`: cross-artifact hardware/CAN/vendordep
 * consistency, batched Phoenix 6 signals, hot-loop blocking calls, and league coordinate transforms.
 */
internal object CrossArtifactAndVendorRules {
    private val hotMethodStart = Regex(
        """\bfun\s+(periodic|simulationPeriodic|loop|readInputs|writeOutputs|update|execute|stepControl|teleopPeriodic|disabledPeriodic|autonomousPeriodic)\s*\(""",
    )
    private val waitForUpdateCall = Regex("""\.\s*waitForUpdate\s*\(""")
    private val unbatchedRefreshCall = Regex("""\.\s*refresh\s*\(""")
    private val phoenixImport = Regex("""^\s*import\s+com\.ctre\.phoenix6\.""")
    private val blockingHotPatterns = listOf(
        Regex("""\brunBlocking\b""") to "runBlocking { ... }",
        Regex("""\bCompletableFuture\b""") to "CompletableFuture",
        Regex("""\b\w*(?:Future|Deferred|Job|Process)\w*\.(?:get|join|waitFor)\s*\(""") to "blocking future/job wait",
        Regex("""\.get\s*\(\s*\d+\s*,\s*TimeUnit\b""") to "timed blocking .get()",
        Regex("""\.await\s*\(""") to "blocking .await()",
    )
    private val frcMirrorInFtcRegex = Regex("""(?:\bfieldLength\w*|16\.54\d*)\s*-\s*(?:\w+|\([^)]+\))""")
    private val centerMirrorInFrcRegex = Regex("""\b(?:x|targetX|startX)\s*=\s*-\s*(?:pose|robotPose|startPose|targetPose)\w*\.x\b""")

    private val revImport = Regex("""^\s*import\s+com\.revrobotics\.""")
    private val photonImport = Regex("""^\s*import\s+org\.photonvision\.""")
    private val hardwareMapGetCode = Regex("""\bhardwareMap\s*\.\s*get\s*\(""")
    private val hardwareMapTwoArgRaw = Regex("""\bhardwareMap\s*\.\s*get\s*\([^,]+,\s*"([^"]+)"\s*\)""")
    private val hardwareMapOneArgRaw = Regex("""\bhardwareMap\s*\.\s*get\s*\(\s*"([^"]+)"\s*\)""")
    private val canDeviceCtorRegex = Regex("""\b(?:TalonFX|SparkMax|SparkFlex|CANcoder|Pigeon2)\s*\(\s*(\d+)\b""")

    fun checkSingleFile(
        file: ScannedSourceFile,
        coordinateConvention: String? = null,
    ): List<AuditFinding> = buildList {
        addAll(checkPhoenixAndBlockingLoops(file))
        addAll(checkLeagueCoordinateMath(file, coordinateConvention))
    }

    fun checkCrossArtifact(projectRoot: File, file: ScannedSourceFile): List<AuditFinding> {
        if (file.isTestFile) return emptyList()
        val metadataFile = File(projectRoot, ".ares/project.json")
        if (!metadataFile.isFile) return emptyList()
        val metadata = runCatching { AresProjectMetadataCodec.decode(metadataFile.readText()) }.getOrNull()
            ?: return emptyList()

        val decls = loadDeclaredHardware(projectRoot)
        val findings = mutableListOf<AuditFinding>()

        if (metadata.league == AresLeague.FRC) {
            findings += checkFrcVendordeps(projectRoot, file)
        }

        val isDrivetrainFile = file.relativePath.lowercase().contains("drivetrain") ||
            file.relativePath.lowercase().contains("swerve")

        file.codeLines.forEachIndexed { index, codeLine ->
            val rawLine = file.rawLines.getOrElse(index) { "" }.trim()
            if (hardwareMapGetCode.containsMatchIn(codeLine)) {
                val deviceName = hardwareMapTwoArgRaw.find(rawLine)?.groupValues?.getOrNull(1)
                    ?: hardwareMapOneArgRaw.find(rawLine)?.groupValues?.getOrNull(1)
                if (deviceName != null && deviceName !in decls.hardwareNames) {
                    findings += AuditFinding(
                        ruleId = "ARES-HW-009",
                        severity = AuditSeverity.WARNING,
                        file = file.relativePath,
                        line = index + 1,
                        snippet = rawLine,
                        message = "hardwareMap device '$deviceName' is not declared in any .ares subsystem or drivetrain descriptor.",
                        suggestedFix = "Declare '$deviceName' in .ares/subsystems/*.aressubsystem (or generate/validate the Robot Controller Hub XML).",
                    )
                }
            }
            for (match in canDeviceCtorRegex.findAll(codeLine)) {
                val canId = match.groupValues[1].toIntOrNull() ?: continue
                if (!isDrivetrainFile && canId in decls.drivetrainCanIds) {
                    findings += AuditFinding(
                        ruleId = "ARES-HW-009",
                        severity = AuditSeverity.ERROR,
                        file = file.relativePath,
                        line = index + 1,
                        snippet = rawLine,
                        message = "CAN ID $canId collides with a drivetrain CAN device declared in .ares/drivetrains.",
                        suggestedFix = "Assign a unique non-drivetrain CAN ID or reference the existing drivetrain component.",
                    )
                } else if (canId !in decls.allCanIds && decls.allCanIds.isNotEmpty()) {
                    findings += AuditFinding(
                        ruleId = "ARES-HW-009",
                        severity = AuditSeverity.WARNING,
                        file = file.relativePath,
                        line = index + 1,
                        snippet = rawLine,
                        message = "CAN ID $canId is not declared in any .ares subsystem or drivetrain descriptor.",
                        suggestedFix = "Record CAN ID $canId in your .ares hardware declaration so bring-up audits can track bus collisions.",
                    )
                }
            }
        }
        return findings
    }

    private fun checkPhoenixAndBlockingLoops(file: ScannedSourceFile): List<AuditFinding> {
        if (file.isTestFile) return emptyList()
        val findings = mutableListOf<AuditFinding>()
        val hasPhoenixImport = file.codeLines.any { phoenixImport.containsMatchIn(it) }
        var insideHotMethod = false
        var awaitingOpeningBrace = false
        var braceDepth = 0

        file.codeLines.forEachIndexed { index, codeLine ->
            val rawLine = file.rawLines.getOrElse(index) { "" }.trim()
            val lineNum = index + 1
            if (waitForUpdateCall.containsMatchIn(codeLine)) {
                findings += AuditFinding(
                    ruleId = "ARES-PHX-010",
                    severity = AuditSeverity.ERROR,
                    file = file.relativePath,
                    line = lineNum,
                    snippet = rawLine,
                    message = "Blocking Phoenix 6 waitForUpdate() stalls deterministic robot loop timing.",
                    suggestedFix = "Configure signal update frequency during init and call BaseStatusSignal.refreshAll(...) once in readInputs().",
                )
            }

            var enteredOnThisLine = false
            if (!insideHotMethod && hotMethodStart.containsMatchIn(codeLine)) {
                val opens = codeLine.count { it == '{' }
                val closes = codeLine.count { it == '}' }
                if (opens > 0) {
                    braceDepth = opens - closes
                    insideHotMethod = braceDepth > 0
                    awaitingOpeningBrace = false
                    enteredOnThisLine = true
                } else if (!codeLine.contains("=")) {
                    awaitingOpeningBrace = true
                }
            } else if (awaitingOpeningBrace) {
                val opens = codeLine.count { it == '{' }
                val closes = codeLine.count { it == '}' }
                if (opens > 0) {
                    braceDepth = opens - closes
                    insideHotMethod = braceDepth > 0
                    awaitingOpeningBrace = false
                    enteredOnThisLine = true
                } else if (codeLine.contains("=")) {
                    awaitingOpeningBrace = false
                }
            }

            if (insideHotMethod) {
                if ((hasPhoenixImport || codeLine.contains("Signal", ignoreCase = true)) &&
                    unbatchedRefreshCall.containsMatchIn(codeLine) &&
                    !codeLine.contains("refreshAll")
                ) {
                    findings += AuditFinding(
                        ruleId = "ARES-PHX-010",
                        severity = AuditSeverity.ERROR,
                        file = file.relativePath,
                        line = lineNum,
                        snippet = rawLine,
                        message = "Unbatched StatusSignal.refresh() inside hot loop increases CAN bus round-trips.",
                        suggestedFix = "Batch status signals with BaseStatusSignal.refreshAll(sig1, sig2, ...) inside readInputs().",
                    )
                }
                for ((regex, label) in blockingHotPatterns) {
                    if (regex.containsMatchIn(codeLine)) {
                        findings += AuditFinding(
                            ruleId = "ARES-LOOP-011",
                            severity = AuditSeverity.ERROR,
                            file = file.relativePath,
                            line = lineNum,
                            snippet = rawLine,
                            message = "Blocking call '$label' inside periodic/control loop threatens overrun and missed watchdogs.",
                            suggestedFix = "Move blocking I/O off the periodic loop or poll cached asynchronous state without blocking.",
                        )
                    }
                }
                if (!enteredOnThisLine) {
                    braceDepth += codeLine.count { it == '{' } - codeLine.count { it == '}' }
                    if (braceDepth <= 0) {
                        insideHotMethod = false
                        braceDepth = 0
                    }
                }
            }
        }
        return findings
    }

    private fun checkLeagueCoordinateMath(
        file: ScannedSourceFile,
        coordinateConvention: String?,
    ): List<AuditFinding> {
        if (file.isTestFile) return emptyList()
        val lowerPath = file.relativePath.lowercase()
        val effectiveConvention = coordinateConvention ?: when {
            lowerPath.contains("teamcode") || lowerPath.contains("firstinspires/ftc") -> "CENTER_ORIGIN_CCW"
            lowerPath.contains("frc") -> "BLUE_CORNER_ORIGIN_CCW"
            else -> return emptyList()
        }
        val findings = mutableListOf<AuditFinding>()
        file.codeLines.forEachIndexed { index, codeLine ->
            val rawLine = file.rawLines.getOrElse(index) { "" }.trim()
            if (effectiveConvention == "CENTER_ORIGIN_CCW" && frcMirrorInFtcRegex.containsMatchIn(codeLine)) {
                findings += AuditFinding(
                    ruleId = "ARES-COORD-012",
                    severity = AuditSeverity.ERROR,
                    file = file.relativePath,
                    line = index + 1,
                    snippet = rawLine,
                    message = "FRC blue-corner X mirror (`fieldLength - x` or `16.54 - x`) applied inside FTC CENTER_ORIGIN_CCW project.",
                    suggestedFix = "Mirror FTC center-origin coordinates around 0.0 (-x / -y / heading + PI) rather than subtracting fieldLength.",
                )
            } else if (effectiveConvention == "BLUE_CORNER_ORIGIN_CCW" &&
                centerMirrorInFrcRegex.containsMatchIn(codeLine) &&
                !codeLine.contains("fieldLength", ignoreCase = true)
            ) {
                findings += AuditFinding(
                    ruleId = "ARES-COORD-012",
                    severity = AuditSeverity.ERROR,
                    file = file.relativePath,
                    line = index + 1,
                    snippet = rawLine,
                    message = "Negating pose X (`-pose.x`) without fieldLength assumes center origin inside FRC BLUE_CORNER_ORIGIN_CCW.",
                    suggestedFix = "Mirror FRC red alliance poses using `fieldLengthMeters - pose.x` and `PI - theta`.",
                )
            }
        }
        return findings
    }

    private fun checkFrcVendordeps(projectRoot: File, file: ScannedSourceFile): List<AuditFinding> {
        val installed = FrcVendordepCatalog.listInstalled(projectRoot)
        val hasPhoenix6 = installed.any {
            it.uuid == FrcVendordepCatalog.PHOENIX6_UUID ||
                it.name.contains("Phoenix", ignoreCase = true) ||
                it.fileName.contains("Phoenix6", ignoreCase = true)
        }
        val hasRevLib = installed.any {
            it.uuid == FrcVendordepCatalog.REVLIB_UUID ||
                it.name.contains("REVLib", ignoreCase = true) ||
                it.fileName.contains("REVLib", ignoreCase = true)
        }
        val hasPhoton = installed.any {
            it.uuid == FrcVendordepCatalog.PHOTONLIB_UUID ||
                it.name.contains("photon", ignoreCase = true) ||
                it.fileName.contains("photon", ignoreCase = true)
        }
        val findings = mutableListOf<AuditFinding>()
        file.codeLines.forEachIndexed { index, codeLine ->
            val rawLine = file.rawLines.getOrElse(index) { "" }.trim()
            val missingPreset = when {
                phoenixImport.containsMatchIn(codeLine) && !hasPhoenix6 -> "PHOENIX6"
                revImport.containsMatchIn(codeLine) && !hasRevLib -> "REVLIB"
                photonImport.containsMatchIn(codeLine) && !hasPhoton -> "PHOTONLIB"
                else -> null
            }
            if (missingPreset != null) {
                findings += AuditFinding(
                    ruleId = "ARES-HW-009",
                    severity = AuditSeverity.ERROR,
                    file = file.relativePath,
                    line = index + 1,
                    snippet = rawLine,
                    message = "FRC source imports vendor SDK for '$missingPreset' but no matching vendordeps/*.json is installed.",
                    suggestedFix = "Call ares_hardware_bringup(operation='INSTALL_VENDORDEP', vendordepPreset='$missingPreset').",
                )
            }
        }
        return findings
    }

    private data class DeclaredHardwareIndex(
        val hardwareNames: Set<String>,
        val drivetrainCanIds: Set<Int>,
        val allCanIds: Set<Int>,
    )

    private fun loadDeclaredHardware(projectRoot: File): DeclaredHardwareIndex {
        val names = linkedSetOf<String>()
        val drivetrainIds = linkedSetOf<Int>()
        val allIds = linkedSetOf<Int>()

        val canHardwareIdRegex = Regex("""^(?:\w+_can_)?(\d+)$""")
        File(projectRoot, ".ares/drivetrains").takeIf(File::isDirectory)
            ?.listFiles { f -> f.isFile && f.extension == "aresdrivetrain" }
            ?.forEach { file ->
                val doc = runCatching { DrivetrainDocumentCodec.decode(file.readText()) }.getOrNull() ?: return@forEach
                for (comp in doc.components) {
                    names += comp.hardwareId
                    canHardwareIdRegex.matchEntire(comp.hardwareId.trim())?.groupValues?.get(1)?.toIntOrNull()?.let { id ->
                        drivetrainIds += id
                        allIds += id
                    }
                }
            }

        File(projectRoot, ".ares/subsystems").takeIf(File::isDirectory)
            ?.listFiles { f -> f.isFile && f.extension == "aressubsystem" }
            ?.forEach { file ->
                val doc = runCatching { SubsystemDocumentCodec.decode(file.readText()) }.getOrNull() ?: return@forEach
                for (hw in doc.hardware) {
                    names += hw.hardwareId
                    hw.connection.hardwareMapName?.takeIf { it.isNotBlank() }?.let { names += it }
                    hw.connection.canId?.let { allIds += it }
                }
            }

        return DeclaredHardwareIndex(
            hardwareNames = names,
            drivetrainCanIds = drivetrainIds,
            allCanIds = allIds,
        )
    }
}
