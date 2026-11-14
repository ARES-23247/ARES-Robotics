package com.ares.analytics.mcp.audit

/** Checks `ARES-GC-001` (zero-GC hot loops) and `ARES-CLK-002` (clock purity). */
internal object GcAndClockRules {
    private val hotMethodStart = Regex(
        """\bfun\s+(periodic|simulationPeriodic|loop|reduce|readInputs|writeOutputs|update|execute|stepControl|teleopPeriodic|disabledPeriodic|autonomousPeriodic)\s*\(""",
    )
    private val allocationPattern = Regex(
        """(?:\b(?:mutableListOf|mutableMapOf|mutableSetOf|arrayListOf|listOf|mapOf|setOf|ArrayList|HashMap|HashSet|Regex|StringBuilder)(?:\s*<[^{};()]*>)?\s*\(|\.(?:map|filter|associate|toList|toMutableList)\s*\{)""",
    )
    private val rawClockPatterns = listOf(
        Regex("""\bSystem\.currentTimeMillis\s*\(""") to "System.currentTimeMillis()",
        Regex("""\bSystem\.nanoTime\s*\(""") to "System.nanoTime()",
        Regex("""\bInstant\.now\s*\(""") to "Instant.now()",
        Regex("""\bThread\.sleep\s*\(""") to "Thread.sleep()",
        Regex("""\bElapsedTime\s*\(""") to "ElapsedTime()",
    )

    fun check(file: ScannedSourceFile): List<AuditFinding> = buildList {
        addAll(checkHotLoopAllocations(file))
        addAll(checkClockPurity(file))
    }

    private fun checkHotLoopAllocations(file: ScannedSourceFile): List<AuditFinding> {
        val findings = mutableListOf<AuditFinding>()
        var insideHotMethod = false
        var braceDepth = 0
        var awaitingOpeningBrace = false

        file.codeLines.forEachIndexed { index, codeLine ->
            val lineNumber = index + 1
            val rawLine = file.rawLines.getOrElse(index) { "" }.trim()

            if (!insideHotMethod && !awaitingOpeningBrace && hotMethodStart.containsMatchIn(codeLine)) {
                val opens = codeLine.count { it == '{' }
                val closes = codeLine.count { it == '}' }
                if (opens > 0) {
                    braceDepth = opens - closes
                    insideHotMethod = braceDepth > 0
                } else if (codeLine.contains("=")) {
                    // Single-expression hot function (e.g., fun reduce(...) = listOf(...))
                    if (allocationPattern.containsMatchIn(codeLine)) {
                        findings += gcFinding(file.relativePath, lineNumber, rawLine)
                    }
                    awaitingOpeningBrace = !codeLine.contains("when") && !codeLine.endsWith(")")
                } else {
                    awaitingOpeningBrace = true
                }
            } else if (awaitingOpeningBrace) {
                val opens = codeLine.count { it == '{' }
                val closes = codeLine.count { it == '}' }
                if (opens > 0) {
                    awaitingOpeningBrace = false
                    braceDepth = opens - closes
                    insideHotMethod = braceDepth > 0
                }
            }

            if (insideHotMethod || (awaitingOpeningBrace && codeLine.isNotBlank())) {
                if (allocationPattern.containsMatchIn(codeLine)) {
                    findings += gcFinding(file.relativePath, lineNumber, rawLine)
                }
            }

            if (insideHotMethod && !hotMethodStart.containsMatchIn(codeLine)) {
                braceDepth += codeLine.count { it == '{' } - codeLine.count { it == '}' }
                if (braceDepth <= 0) {
                    insideHotMethod = false
                    braceDepth = 0
                }
            }
        }
        return findings
    }

    private fun checkClockPurity(file: ScannedSourceFile): List<AuditFinding> {
        val findings = mutableListOf<AuditFinding>()
        file.codeLines.forEachIndexed { index, codeLine ->
            val rawLine = file.rawLines.getOrElse(index) { "" }.trim()
            for ((regex, apiName) in rawClockPatterns) {
                if (regex.containsMatchIn(codeLine)) {
                    findings += AuditFinding(
                        ruleId = "ARES-CLK-002",
                        severity = AuditSeverity.ERROR,
                        file = file.relativePath,
                        line = index + 1,
                        snippet = rawLine,
                        message = "Direct wall-clock or blocking timing API '$apiName' bypasses deterministic robot time.",
                        suggestedFix = "Use com.areslib.util.RobotClock (currentTimeMillis() / nanoTime()) or non-blocking sequencer states.",
                    )
                }
            }
        }
        return findings
    }

    private fun gcFinding(path: String, line: Int, snippet: String) = AuditFinding(
        ruleId = "ARES-GC-001",
        severity = AuditSeverity.ERROR,
        file = path,
        line = line,
        snippet = snippet,
        message = "Heap collection or builder allocation detected inside hot robot control loop.",
        suggestedFix = "Preallocate reusable arrays/fields on the class instance and mutate them in place.",
    )
}
