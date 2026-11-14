package com.ares.analytics.mcp.audit

/**
 * Checks rules `ARES-RDX-003` through `ARES-SIM-008`: Redux purity, disable/fault neutralization,
 * single-read caching, CCW/Limelight coordinates, offline-first robot code, and simulator truth isolation.
 */
internal object SafetyAndCoordinateRules {
    private val stateClassHeader = Regex("""\bdata\s+class\s+\w*State\w*\b""")
    private val mutableVarDecl = Regex("""\bvar\s+[A-Za-z_]\w*""")
    private val hardwareSdkImport = Regex(
        """^\s*import\s+(?:com\.qualcomm\.|com\.ctre\.|com\.revrobotics\.|edu\.wpi\.first\.wpilibj\.motorcontrol\.)""",
    )
    private val actuatorWriteCall = Regex(
        """\b(?:setPower|setVoltage|setControl|applyMotorPower|applyVoltage|applyPower)\s*\(""",
    )
    private val neutralMethodDecl = Regex(
        """\bfun\s+(?:disable|stop|close|neutralize|disabledInit)\s*\(""",
    )
    private val neutralResetToken = Regex(
        """(?:0\.0|0f|NeutralOut|stopMotor|disable\s*\(\)|stop\s*\(\)|applyNeutralOutputs\s*\(\))""",
    )
    private val methodDecl = Regex("""\bfun\s+([A-Za-z_]\w*)\s*\(""")
    private val rawHardwareGetter = Regex(
        """\b(?:\w+\.(?:currentPosition|getCurrentPosition\(\)|velocity|getVelocity\(\)|getPosition\(\)))""",
    )
    private val badLimelightAxis = Regex("""\brobotPoseTargetSpace\.rotation\.z\b""")
    private val invertedPinpoint = Regex("""-\s*(?:pinpoint\w*|odometry\.heading|pinpointHeading)\b""")
    private val degreeToPoseAssign = Regex(
        """\b(?:heading|theta|yaw|omega|poseAngle|angleRadians)\s*=\s*.*Math\.toDegrees\s*\(""",
    )
    private val forbiddenHttpUsage = Regex(
        """(?:\bimport\s+(?:io\.ktor\.client\.|java\.net\.http\.HttpClient|com\.google\.cloud\.)|\b(?:HttpURLConnection|HttpClient\s*\())""",
    )
    private val truePoseTopic = Regex(""""ARES/TruePose"""")
    private val simTruthSymbol = Regex("""\b(?:simTruth|groundTruthPose|simulatorTruth|perfectPose)\b""")

    fun check(file: ScannedSourceFile): List<AuditFinding> = buildList {
        addAll(checkReduxPurity(file))
        addAll(checkActuatorNeutralization(file))
        addAll(checkSingleReadCaching(file))
        addAll(checkCoordinates(file))
        addAll(checkOfflineRobotCode(file))
        addAll(checkTruthIsolation(file))
    }

    private fun checkReduxPurity(file: ScannedSourceFile): List<AuditFinding> {
        val findings = mutableListOf<AuditFinding>()
        val lowerName = file.relativePath.lowercase()
        val isReduxFile = lowerName.contains("reducer") || lowerName.contains("state") ||
            file.codeLines.any { it.contains("fun reduce(") || stateClassHeader.containsMatchIn(it) }

        var inStateBlock = false
        var parenDepth = 0

        file.codeLines.forEachIndexed { index, codeLine ->
            val rawLine = file.rawLines.getOrElse(index) { "" }.trim()
            if (isReduxFile && hardwareSdkImport.containsMatchIn(codeLine)) {
                findings += AuditFinding(
                    ruleId = "ARES-RDX-003",
                    severity = AuditSeverity.ERROR,
                    file = file.relativePath,
                    line = index + 1,
                    snippet = rawLine,
                    message = "Hardware/motor SDK import inside Redux state/reducer violates pure IO decoupling.",
                    suggestedFix = "Move hardware SDK types behind a dedicated IO interface outside reducers and states.",
                )
            }
            if (!inStateBlock && stateClassHeader.containsMatchIn(codeLine)) {
                if (mutableVarDecl.containsMatchIn(codeLine.substringAfter('(', ""))) {
                    findings += AuditFinding(
                        ruleId = "ARES-RDX-003",
                        severity = AuditSeverity.ERROR,
                        file = file.relativePath,
                        line = index + 1,
                        snippet = rawLine,
                        message = "Mutable 'var' field in Redux State data class breaks state immutability.",
                        suggestedFix = "Declare State properties with 'val' and return updated copies via state.copy(...).",
                    )
                }
                parenDepth = codeLine.count { it == '(' } - codeLine.count { it == ')' }
                inStateBlock = parenDepth > 0 || !codeLine.contains(')')
            } else if (inStateBlock) {
                if (mutableVarDecl.containsMatchIn(codeLine)) {
                    findings += AuditFinding(
                        ruleId = "ARES-RDX-003",
                        severity = AuditSeverity.ERROR,
                        file = file.relativePath,
                        line = index + 1,
                        snippet = rawLine,
                        message = "Mutable 'var' field in Redux State data class breaks state immutability.",
                        suggestedFix = "Declare State properties with 'val' and return updated copies via state.copy(...).",
                    )
                }
                parenDepth += codeLine.count { it == '(' } - codeLine.count { it == ')' }
                if (parenDepth <= 0) inStateBlock = false
            }
        }
        return findings
    }

    private fun checkActuatorNeutralization(file: ScannedSourceFile): List<AuditFinding> {
        if (file.isTestFile) return emptyList()
        val firstActuatorLine = file.codeLines.indexOfFirst { actuatorWriteCall.containsMatchIn(it) }
        if (firstActuatorLine < 0) return emptyList()

        val hasNeutralMethod = file.codeLines.any { neutralMethodDecl.containsMatchIn(it) }
        val hasNeutralReset = file.codeLines.any { neutralResetToken.containsMatchIn(it) }
        if (hasNeutralMethod && hasNeutralReset) return emptyList()

        return listOf(
            AuditFinding(
                ruleId = "ARES-SAF-004",
                severity = AuditSeverity.ERROR,
                file = file.relativePath,
                line = firstActuatorLine + 1,
                snippet = file.rawLines.getOrElse(firstActuatorLine) { "" }.trim(),
                message = "Actuator output write found without explicit disable()/stop()/close() zero-output neutralization.",
                suggestedFix = "Implement disable()/stop()/close() commanding neutral output (0.0 / NeutralOut() / stopMotor()).",
            ),
        )
    }

    private fun checkSingleReadCaching(file: ScannedSourceFile): List<AuditFinding> {
        if (file.isTestFile) return emptyList()
        val findings = mutableListOf<AuditFinding>()
        var currentMethod: String? = null
        var braceDepth = 0
        var readsInMethod = 0
        var firstReadLine = -1

        file.codeLines.forEachIndexed { index, codeLine ->
            val match = methodDecl.find(codeLine)
            if (currentMethod == null && match != null) {
                currentMethod = match.groupValues[1]
                readsInMethod = 0
                firstReadLine = -1
                val opens = codeLine.count { it == '{' }
                val closes = codeLine.count { it == '}' }
                braceDepth = opens - closes
                if (opens > 0 && braceDepth <= 0) {
                    currentMethod = null
                }
            } else if (currentMethod != null) {
                braceDepth += codeLine.count { it == '{' } - codeLine.count { it == '}' }
            }

            if (currentMethod != null && currentMethod != "readInputs") {
                val readMatches = rawHardwareGetter.findAll(codeLine).count()
                if (readMatches > 0) {
                    if (firstReadLine < 0) firstReadLine = index + 1
                    readsInMethod += readMatches
                    if (readsInMethod >= 2) {
                        findings += AuditFinding(
                            ruleId = "ARES-IO-005",
                            severity = AuditSeverity.WARNING,
                            file = file.relativePath,
                            line = index + 1,
                            snippet = file.rawLines.getOrElse(index) { "" }.trim(),
                            message = "Multiple direct hardware sensor reads outside readInputs() in '$currentMethod()'.",
                            suggestedFix = "Cache hardware reads once per cycle inside readInputs() and consume cached state fields.",
                        )
                        readsInMethod = Int.MIN_VALUE / 2 // report once per offending method
                    }
                }
            }

            if (currentMethod != null && braceDepth <= 0 && !codeLine.contains("fun ")) {
                currentMethod = null
                readsInMethod = 0
            }
        }
        return findings
    }

    private fun checkCoordinates(file: ScannedSourceFile): List<AuditFinding> {
        val findings = mutableListOf<AuditFinding>()
        file.codeLines.forEachIndexed { index, codeLine ->
            val rawLine = file.rawLines.getOrElse(index) { "" }.trim()
            if (badLimelightAxis.containsMatchIn(codeLine)) {
                findings += AuditFinding(
                    ruleId = "ARES-CRD-006",
                    severity = AuditSeverity.ERROR,
                    file = file.relativePath,
                    line = index + 1,
                    snippet = rawLine,
                    message = "Reading robotPoseTargetSpace.rotation.z uses the wrong Limelight target-space yaw axis.",
                    suggestedFix = "Use -robotPoseTargetSpace.rotation.y per ARES CCW coordinate conventions.",
                )
            }
            if (invertedPinpoint.containsMatchIn(codeLine)) {
                findings += AuditFinding(
                    ruleId = "ARES-CRD-006",
                    severity = AuditSeverity.ERROR,
                    file = file.relativePath,
                    line = index + 1,
                    snippet = rawLine,
                    message = "Downstream negation of Pinpoint/odometry heading stacks sign inversions.",
                    suggestedFix = "Preserve single CCW-positive negation only inside the IO boundary.",
                )
            }
            if (degreeToPoseAssign.containsMatchIn(codeLine)) {
                findings += AuditFinding(
                    ruleId = "ARES-CRD-006",
                    severity = AuditSeverity.ERROR,
                    file = file.relativePath,
                    line = index + 1,
                    snippet = rawLine,
                    message = "Degrees assigned into canonical radian pose/heading state.",
                    suggestedFix = "Keep all internal pose, heading, and angular velocity values in CCW-positive radians.",
                )
            }
        }
        return findings
    }

    private fun checkOfflineRobotCode(file: ScannedSourceFile): List<AuditFinding> {
        if (file.isTestFile) return emptyList()
        val findings = mutableListOf<AuditFinding>()
        file.codeLines.forEachIndexed { index, codeLine ->
            if (forbiddenHttpUsage.containsMatchIn(codeLine)) {
                findings += AuditFinding(
                    ruleId = "ARES-OFF-007",
                    severity = AuditSeverity.ERROR,
                    file = file.relativePath,
                    line = index + 1,
                    snippet = file.rawLines.getOrElse(index) { "" }.trim(),
                    message = "Robot runtime code must remain offline-first without external HTTP or cloud clients.",
                    suggestedFix = "Remove cloud/HTTP client usage; Studio owns network sync while robots use NanoHTTPD (5002) and NT4.",
                )
            }
        }
        return findings
    }

    private fun checkTruthIsolation(file: ScannedSourceFile): List<AuditFinding> {
        val lower = file.relativePath.lowercase()
        if (file.isTestFile || lower.contains("visualizer") || lower.contains("groundtruth")) return emptyList()
        val findings = mutableListOf<AuditFinding>()
        file.rawLines.forEachIndexed { index, rawLine ->
            val codeLine = file.codeLines.getOrElse(index) { "" }
            val topicMatch = truePoseTopic.find(rawLine)
            val hasTopic = topicMatch != null && codeLine.getOrNull(topicMatch.range.first) == '"'
            val hasTruthVar = simTruthSymbol.containsMatchIn(codeLine)
            if (hasTopic || hasTruthVar) {
                findings += AuditFinding(
                    ruleId = "ARES-SIM-008",
                    severity = AuditSeverity.ERROR,
                    file = file.relativePath,
                    line = index + 1,
                    snippet = rawLine.trim(),
                    message = "Simulator ground truth ('ARES/TruePose' / simTruth) must not feed robot estimators or controllers.",
                    suggestedFix = "Consume estimated odometry/vision state in estimators; reserve ARES/TruePose strictly for simulator visualizers.",
                )
            }
        }
        return findings
    }
}
