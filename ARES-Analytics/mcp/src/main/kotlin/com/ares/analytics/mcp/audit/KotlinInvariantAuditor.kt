package com.ares.analytics.mcp.audit

import kotlinx.serialization.Serializable
import java.io.File

/** Severity of a deterministic ARES static invariant finding. */
@Serializable
enum class AuditSeverity { ERROR, WARNING }

/** One deterministic invariant finding reported by `ares_code_audit`. */
@Serializable
data class AuditFinding(
    val ruleId: String,
    val severity: AuditSeverity,
    val file: String,
    val line: Int,
    val snippet: String,
    val message: String,
    val suggestedFix: String,
)

/** Summary envelope returned by `ares_code_audit`. */
@Serializable
data class AuditReport(
    val projectRoot: String,
    val scannedFilesCount: Int,
    val errorCount: Int,
    val warningCount: Int,
    val passed: Boolean,
    val findings: List<AuditFinding>,
)

/** Parsed line representation with comments and string literals masked cleanly for AST-safe scanning. */
internal data class ScannedSourceFile(
    val relativePath: String,
    val rawLines: List<String>,
    val codeLines: List<String>,
    val isTestFile: Boolean,
)

/**
 * Deterministic static invariant auditor implementing rules `ARES-GC-001` through `ARES-SIM-008`.
 *
 * Comments (`//`, nested `/* ... */`) and string literals (`"..."`, `"""..."""`) are masked out of
 * [ScannedSourceFile.codeLines] while keeping 1-based line numbers exact so examples and docstrings
 * never trigger false positives.
 */
object KotlinInvariantAuditor {
    fun auditProject(projectRoot: File, explicitRelativePaths: List<String> = emptyList()): AuditReport {
        val canonical = projectRoot.canonicalFile
        val files = if (explicitRelativePaths.isNotEmpty()) {
            explicitRelativePaths.map { rel ->
                val resolved = canonical.toPath().resolve(rel).normalize().toFile()
                require(resolved.canonicalPath.startsWith(canonical.canonicalPath)) {
                    "Audit path escapes project root: $rel"
                }
                resolved
            }.filter { it.isFile }
        } else {
            collectAuditableRobotSources(canonical)
        }
        val findings = files.flatMap { file ->
            val rel = file.relativeToOrSelf(canonical).invariantSeparatorsPath
            auditText(rel, file.readText())
        }.sortedWith(compareBy({ it.file }, { it.line }, { it.ruleId }))

        val errors = findings.count { it.severity == AuditSeverity.ERROR }
        val warnings = findings.count { it.severity == AuditSeverity.WARNING }
        return AuditReport(
            projectRoot = canonical.path,
            scannedFilesCount = files.size,
            errorCount = errors,
            warningCount = warnings,
            passed = errors == 0,
            findings = findings,
        )
    }

    fun auditText(relativePath: String, sourceText: String): List<AuditFinding> {
        val firstLine = sourceText.lineSequence().firstOrNull().orEmpty()
        if (firstLine.contains("// ARES OWNERSHIP: GENERATED DO NOT EDIT")) {
            return emptyList()
        }
        val scanned = scanSource(relativePath, sourceText)
        return (GcAndClockRules.check(scanned) + SafetyAndCoordinateRules.check(scanned))
            .sortedWith(compareBy({ it.line }, { it.ruleId }))
    }

    internal fun collectAuditableRobotSources(root: File): List<File> {
        val candidateRoots = listOf(File(root, "TeamCode/src"), File(root, "src")).filter { it.isDirectory }
        return candidateRoots.flatMap { base ->
            base.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .filter { !it.invariantSeparatorsPath.contains("/build/generated/") }
                .toList()
        }.distinctBy { it.canonicalPath }.sortedBy { it.relativeToOrSelf(root).invariantSeparatorsPath }
    }

    internal fun scanSource(relativePath: String, rawText: String): ScannedSourceFile {
        val normalized = rawText.replace("\r\n", "\n")
        val rawLines = normalized.split('\n')
        val masked = maskCommentsAndStrings(normalized)
        val codeLines = masked.split('\n')
        val lower = relativePath.lowercase()
        val isTest = lower.contains("/test/") || lower.endsWith("test.kt") || lower.endsWith("test.java")
        return ScannedSourceFile(
            relativePath = relativePath,
            rawLines = rawLines,
            codeLines = codeLines,
            isTestFile = isTest,
        )
    }

    private fun maskCommentsAndStrings(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var blockDepth = 0
        var inLineComment = false
        var inString = false
        var inRawString = false
        var inChar = false

        while (i < text.length) {
            val ch = text[i]
            val next = text.getOrNull(i + 1)
            val next2 = text.getOrNull(i + 2)

            if (ch == '\n') {
                out.append('\n')
                inLineComment = false
                if (!inRawString) {
                    inString = false
                    inChar = false
                }
                i++
                continue
            }

            when {
                inLineComment -> {
                    out.append(' ')
                    i++
                }
                blockDepth > 0 -> {
                    if (ch == '/' && next == '*') {
                        blockDepth++
                        out.append("  ")
                        i += 2
                    } else if (ch == '*' && next == '/') {
                        blockDepth--
                        out.append("  ")
                        i += 2
                    } else {
                        out.append(' ')
                        i++
                    }
                }
                inRawString -> {
                    if (ch == '"' && next == '"' && next2 == '"') {
                        out.append("\"\"\"")
                        inRawString = false
                        i += 3
                    } else {
                        out.append(' ')
                        i++
                    }
                }
                inString -> {
                    if (ch == '\\' && next != null) {
                        out.append("  ")
                        i += 2
                    } else if (ch == '"') {
                        out.append('"')
                        inString = false
                        i++
                    } else {
                        out.append(' ')
                        i++
                    }
                }
                inChar -> {
                    if (ch == '\\' && next != null) {
                        out.append("  ")
                        i += 2
                    } else if (ch == '\'') {
                        out.append('\'')
                        inChar = false
                        i++
                    } else {
                        out.append(' ')
                        i++
                    }
                }
                ch == '/' && next == '/' -> {
                    inLineComment = true
                    out.append("  ")
                    i += 2
                }
                ch == '/' && next == '*' -> {
                    blockDepth++
                    out.append("  ")
                    i += 2
                }
                ch == '"' && next == '"' && next2 == '"' -> {
                    inRawString = true
                    out.append("\"\"\"")
                    i += 3
                }
                ch == '"' -> {
                    inString = true
                    out.append('"')
                    i++
                }
                ch == '\'' -> {
                    inChar = true
                    out.append('\'')
                    i++
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        return out.toString()
    }
}
