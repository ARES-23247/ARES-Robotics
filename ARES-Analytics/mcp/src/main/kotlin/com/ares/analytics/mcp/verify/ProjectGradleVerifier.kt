package com.ares.analytics.mcp.verify

import com.ares.analytics.mcp.audit.AuditReport
import com.ares.analytics.mcp.audit.KotlinInvariantAuditor
import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.JGitSnapshotSafety
import com.ares.analytics.mcp.engine.ProjectValidationSummary
import com.ares.analytics.shared.AppJsonPretty
import com.areslib.project.AresLeague
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.w3c.dom.Element
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

@Serializable
data class CompilerDiagnostic(
    val severity: String,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val message: String,
)

@Serializable
data class TestExecutionSummary(
    val total: Int = 0,
    val passed: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val failureMessages: List<String> = emptyList(),
)

@Serializable
data class VerificationLayers(
    val schemaAndCompiler: String,
    val staticInvariants: String,
    val gradleCompile: String,
    val unitTests: String,
    val physicalValidation: String = PHYSICAL_VALIDATION_NOT_RUN,
) {
    companion object {
        const val PHYSICAL_VALIDATION_NOT_RUN = "NOT_RUN"
    }
}

@Serializable
data class ProjectVerifyResult(
    val projectRoot: String,
    val league: String,
    val mode: String,
    val passed: Boolean,
    val exitCode: Int,
    val durationMillis: Long,
    val commandLine: List<String>,
    val layers: VerificationLayers,
    val schemaValidation: ProjectValidationSummary,
    val staticAudit: AuditReport,
    val compilerDiagnostics: List<CompilerDiagnostic> = emptyList(),
    val testSummary: TestExecutionSummary = TestExecutionSummary(),
    val logTail: String = "",
    val message: String,
)

/**
 * Serializes headless verification (`ares_project_verify`) per canonical project root,
 * captures compiler diagnostics and JUnit XML results without touching `System.out`,
 * and persists the summary to `.ares/local/mcp-verification-latest.json`.
 */
internal class ProjectGradleVerifier(
    private val engine: HeadlessProjectEngine,
    private val extraGradleArgsProvider: () -> List<String> = ::defaultExtraGradleArgs,
) {
    suspend fun verify(
        projectRoot: File,
        modeRaw: String = "UNIT_TEST",
        testFilter: String? = null,
        timeoutSeconds: Int = 180,
    ): ProjectVerifyResult {
        val canonical = projectRoot.canonicalFile
        val mutex = rootLocks.computeIfAbsent(canonical.path) { Mutex() }
        return mutex.withLock {
            runVerifyLocked(canonical, modeRaw, testFilter, timeoutSeconds)
        }
    }

    private suspend fun runVerifyLocked(
        canonical: File,
        modeRaw: String,
        testFilter: String?,
        timeoutSeconds: Int,
    ): ProjectVerifyResult {
        val mode = modeRaw.trim().uppercase()
        require(mode in ALLOWED_MODES) {
            "Unsupported verify mode '$modeRaw'; expected COMPILE_ONLY, UNIT_TEST, or FULL_VERIFICATION."
        }
        val clampedTimeout = timeoutSeconds.coerceIn(15, 600)
        val bundle = engine.loadBundle(canonical)
        val metadata = requireNotNull(bundle.metadata) { "Missing .ares/project.json in '${canonical.path}'" }
        val league = metadata.league

        val schemaValidation = engine.validateProject(canonical)
        val staticAudit = KotlinInvariantAuditor.auditProject(canonical)

        val runStartMillis = System.currentTimeMillis()
        val startNanos = System.nanoTime()
        val commandLine = buildCommandLine(canonical, league, mode, testFilter)
        val execOutcome = if (commandLine.isEmpty()) {
            ProcessExecutionOutcome(
                exitCode = -1,
                output = "Verification wrapper (${wrapperHint(league)}) not found under ${canonical.path}.",
                timedOut = false,
            )
        } else {
            executeSubprocess(canonical, commandLine, clampedTimeout)
        }
        val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

        val diagnostics = parseCompilerDiagnostics(execOutcome.output)
        val testSummary = if (mode == "COMPILE_ONLY" || league == AresLeague.XRP) {
            TestExecutionSummary()
        } else {
            parseJunitResultsDir(canonical, minModifiedMillis = runStartMillis - 1_000L)
        }

        val compileFailed = when {
            execOutcome.exitCode == 0 -> false
            mode == "COMPILE_ONLY" -> true
            league == AresLeague.XRP -> false
            diagnostics.any { it.severity == "ERROR" } -> true
            testSummary.total == 0 -> true
            else -> false
        }
        val gradleCompileLayer = when {
            execOutcome.timedOut -> "TIMED_OUT"
            commandLine.isEmpty() -> "FAILED"
            compileFailed -> "FAILED"
            else -> "PASSED"
        }
        val unitTestsLayer = when {
            mode == "COMPILE_ONLY" -> "NOT_RUN"
            execOutcome.timedOut -> "TIMED_OUT"
            gradleCompileLayer != "PASSED" -> "NOT_RUN"
            execOutcome.exitCode == 0 && testSummary.failed == 0 -> "PASSED"
            else -> "FAILED"
        }
        val layers = VerificationLayers(
            schemaAndCompiler = if (schemaValidation.valid) "PASSED" else "FAILED",
            staticInvariants = if (staticAudit.passed) "PASSED" else "FAILED",
            gradleCompile = gradleCompileLayer,
            unitTests = unitTestsLayer,
            physicalValidation = VerificationLayers.PHYSICAL_VALIDATION_NOT_RUN,
        )

        val passed = schemaValidation.valid &&
            staticAudit.passed &&
            gradleCompileLayer == "PASSED" &&
            (unitTestsLayer == "PASSED" || unitTestsLayer == "NOT_RUN") &&
            execOutcome.exitCode == 0

        val statusSummary = buildString {
            append(if (passed) "Verification PASSED" else "Verification FAILED")
            append(" (${league.name} / $mode, exit=${execOutcome.exitCode}, ${durationMillis}ms)")
            append("; physicalValidation=${VerificationLayers.PHYSICAL_VALIDATION_NOT_RUN}.")
        }
        val result = ProjectVerifyResult(
            projectRoot = canonical.path,
            league = league.name,
            mode = mode,
            passed = passed,
            exitCode = execOutcome.exitCode,
            durationMillis = durationMillis,
            commandLine = commandLine,
            layers = layers,
            schemaValidation = schemaValidation,
            staticAudit = staticAudit,
            compilerDiagnostics = diagnostics.take(50),
            testSummary = testSummary,
            logTail = tailLines(execOutcome.output, maxLines = 120),
            message = statusSummary,
        )
        persistLatestResult(canonical, result)
        return result
    }

    private fun buildCommandLine(
        canonical: File,
        league: AresLeague,
        mode: String,
        testFilter: String?,
    ): List<String> {
        if (league == AresLeague.XRP) {
            val wrapper = resolveWrapper(canonical, windowsName = "ares.bat", unixName = "ares")
                ?: return emptyList()
            val cmd = if (mode == "COMPILE_ONLY") "validate" else "test"
            return listOf(wrapper.absolutePath, cmd)
        }
        val gradlew = resolveWrapper(canonical, windowsName = "gradlew.bat", unixName = "gradlew")
            ?: return emptyList()
        val tasks = when (league) {
            AresLeague.FTC -> when (mode) {
                "COMPILE_ONLY" -> listOf(":TeamCode:verifyAresProject", ":TeamCode:compileDebugKotlin")
                "UNIT_TEST" -> listOf(":TeamCode:verifyAresProject", ":TeamCode:testDebugUnitTest")
                else -> listOf(
                    "generateAresProject",
                    ":TeamCode:verifyAresProject",
                    ":TeamCode:testDebugUnitTest",
                    ":simulator:test",
                )
            }
            AresLeague.FRC -> when (mode) {
                "COMPILE_ONLY" -> listOf("verifyAresProject", "compileKotlin")
                "UNIT_TEST" -> listOf("verifyAresProject", "test")
                else -> listOf("generateAresProject", "verifyAresProject", "test", "build")
            }
            AresLeague.XRP -> emptyList()
        }
        val validatedFilter = testFilter?.trim()?.takeIf { it.isNotEmpty() }?.also { raw ->
            require(SAFE_TEST_FILTER_REGEX.matches(raw)) {
                "Invalid testFilter '$raw': only alphanumeric characters, dots, underscores, '$', '*', and '?' are permitted."
            }
            require(mode == "UNIT_TEST") {
                "testFilter is supported only with mode='UNIT_TEST' (got '$mode')."
            }
        }
        return buildList {
            add(gradlew.absolutePath)
            addAll(tasks)
            if (validatedFilter != null) {
                add("--tests")
                add(validatedFilter)
            }
            add("--no-daemon")
            add("--console=plain")
            addAll(extraGradleArgsProvider())
        }
    }

    private fun persistLatestResult(canonical: File, result: ProjectVerifyResult) {
        val target = File(canonical, LATEST_VERIFY_RELATIVE_PATH)
        JGitSnapshotSafety.writeBytesAtomically(
            target,
            AppJsonPretty.encodeToString(result).toByteArray(Charsets.UTF_8),
        )
    }

    private suspend fun executeSubprocess(
        workingDir: File,
        commandLine: List<String>,
        timeoutSeconds: Int,
    ): ProcessExecutionOutcome = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(commandLine)
            .directory(workingDir)
            .redirectErrorStream(true)
        val process = pb.start()
        val outputBuffer = StringBuilder()
        val readerThread = Thread {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val chunk = CharArray(4096)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    if (outputBuffer.length < MAX_CAPTURED_CHARS) {
                        val remaining = MAX_CAPTURED_CHARS - outputBuffer.length
                        outputBuffer.append(chunk, 0, minOf(read, remaining))
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            readerThread.join(2_000L)
            return@withContext ProcessExecutionOutcome(
                exitCode = 124,
                output = outputBuffer.toString() + "\n[ares_project_verify] Timed out after ${timeoutSeconds}s.",
                timedOut = true,
            )
        }
        readerThread.join(5_000L)
        ProcessExecutionOutcome(
            exitCode = process.exitValue(),
            output = outputBuffer.toString(),
            timedOut = false,
        )
    }

    private data class ProcessExecutionOutcome(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean,
    )

    companion object {
        const val LATEST_VERIFY_RELATIVE_PATH = ".ares/local/mcp-verification-latest.json"
        private const val MAX_CAPTURED_CHARS = 256_000
        private val ALLOWED_MODES = setOf("COMPILE_ONLY", "UNIT_TEST", "FULL_VERIFICATION")
        private val rootLocks = ConcurrentHashMap<String, Mutex>()

        private val kotlincUriRegex = Regex(
            """^([ew]):\s+file:///?([^\s:]+(?:/[^\s:]+)*?):(\d+):(\d+)\s+(.+)$""",
        )
        private val kotlincColonRegex = Regex(
            """^([ew]):\s+(.+?):(\d+):(\d+)\s+(.+)$""",
        )
        private val kotlincParenRegex = Regex(
            """^([ew]):\s+(.+?):\s*\((\d+),\s*(\d+)\):\s*(.+)$""",
        )
        private val javacRegex = Regex(
            """^(.+?\.java):(\d+):(?:\s*(\d+):)?\s*(error|warning):\s*(.+)$""",
        )
        private val genericKotlincRegex = Regex("""^([ew]):\s+(.+)$""")

        private fun isWindows(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("win")

        private fun resolveWrapper(canonical: File, windowsName: String, unixName: String): File? {
            val primary = File(canonical, if (isWindows()) windowsName else unixName)
            if (primary.isFile) return primary
            val fallback = File(canonical, if (isWindows()) unixName else windowsName)
            return fallback.takeIf(File::isFile)
        }

        private fun wrapperHint(league: AresLeague): String =
            if (league == AresLeague.XRP) "ares.bat/ares" else "gradlew.bat/gradlew"

        internal fun defaultExtraGradleArgs(): List<String> {
            val repo = System.getProperty("ares.repository.uri")?.takeIf { it.isNotBlank() }
                ?: System.getenv("ORG_GRADLE_PROJECT_aresRepository")?.takeIf { it.isNotBlank() }
            return if (repo != null) listOf("-ParesRepository=$repo") else emptyList()
        }

        internal fun parseCompilerDiagnostics(outputText: String): List<CompilerDiagnostic> =
            outputText.lineSequence()
                .mapNotNull { parseDiagnosticLine(it.trimEnd()) }
                .toList()

        private fun parseDiagnosticLine(line: String): CompilerDiagnostic? {
            kotlincUriRegex.matchEntire(line)?.let { m ->
                val rawPath = m.groupValues[2]
                val normalizedPath = if (rawPath.length >= 3 && rawPath[1] == ':') rawPath else "/$rawPath"
                return CompilerDiagnostic(
                    severity = if (m.groupValues[1] == "e") "ERROR" else "WARNING",
                    file = normalizedPath,
                    line = m.groupValues[3].toIntOrNull(),
                    column = m.groupValues[4].toIntOrNull(),
                    message = m.groupValues[5].trim(),
                )
            }
            kotlincParenRegex.matchEntire(line)?.let { m ->
                return CompilerDiagnostic(
                    severity = if (m.groupValues[1] == "e") "ERROR" else "WARNING",
                    file = m.groupValues[2].trim(),
                    line = m.groupValues[3].toIntOrNull(),
                    column = m.groupValues[4].toIntOrNull(),
                    message = m.groupValues[5].trim(),
                )
            }
            kotlincColonRegex.matchEntire(line)?.let { m ->
                return CompilerDiagnostic(
                    severity = if (m.groupValues[1] == "e") "ERROR" else "WARNING",
                    file = m.groupValues[2].trim(),
                    line = m.groupValues[3].toIntOrNull(),
                    column = m.groupValues[4].toIntOrNull(),
                    message = m.groupValues[5].trim(),
                )
            }
            javacRegex.matchEntire(line)?.let { m ->
                return CompilerDiagnostic(
                    severity = if (m.groupValues[4] == "error") "ERROR" else "WARNING",
                    file = m.groupValues[1].trim(),
                    line = m.groupValues[2].toIntOrNull(),
                    column = m.groupValues[3].toIntOrNull(),
                    message = m.groupValues[5].trim(),
                )
            }
            return genericKotlincRegex.matchEntire(line)?.let { m ->
                CompilerDiagnostic(
                    severity = if (m.groupValues[1] == "e") "ERROR" else "WARNING",
                    message = m.groupValues[2].trim(),
                )
            }
        }

        private val SAFE_TEST_FILTER_REGEX = Regex("""^[A-Za-z0-9_$.*?]{1,200}$""")

        internal fun parseJunitResultsDir(
            projectRoot: File,
            minModifiedMillis: Long = 0L,
        ): TestExecutionSummary {
            val xmlFiles = projectRoot.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        file.extension.equals("xml", ignoreCase = true) &&
                        file.invariantSeparatorsPath.contains("/build/test-results/") &&
                        file.lastModified() >= minModifiedMillis
                }
                .toList()
            if (xmlFiles.isEmpty()) return TestExecutionSummary()

            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val builder = factory.newDocumentBuilder()
            var total = 0
            var failed = 0
            var skipped = 0
            val failures = mutableListOf<String>()

            for (xml in xmlFiles) {
                val doc = runCatching { builder.parse(xml) }.getOrNull() ?: continue
                val suites = doc.getElementsByTagName("testsuite")
                for (i in 0 until suites.length) {
                    val el = suites.item(i) as? Element ?: continue
                    total += el.getAttribute("tests").toIntOrNull() ?: 0
                    failed += (el.getAttribute("failures").toIntOrNull() ?: 0) +
                        (el.getAttribute("errors").toIntOrNull() ?: 0)
                    skipped += el.getAttribute("skipped").toIntOrNull() ?: 0
                }
                val cases = doc.getElementsByTagName("testcase")
                for (i in 0 until cases.length) {
                    val caseEl = cases.item(i) as? Element ?: continue
                    val caseName = "${caseEl.getAttribute("classname")}.${caseEl.getAttribute("name")}"
                    val failureNodes = caseEl.getElementsByTagName("failure")
                    for (j in 0 until failureNodes.length) {
                        val fEl = failureNodes.item(j) as? Element ?: continue
                        val msg = fEl.getAttribute("message").takeIf { it.isNotBlank() }
                            ?: fEl.textContent.lineSequence().firstOrNull().orEmpty()
                        failures += "$caseName: $msg".trim()
                    }
                    val errorNodes = caseEl.getElementsByTagName("error")
                    for (j in 0 until errorNodes.length) {
                        val eEl = errorNodes.item(j) as? Element ?: continue
                        val msg = eEl.getAttribute("message").takeIf { it.isNotBlank() }
                            ?: eEl.textContent.lineSequence().firstOrNull().orEmpty()
                        failures += "$caseName: $msg".trim()
                    }
                }
            }
            val passed = (total - failed - skipped).coerceAtLeast(0)
            return TestExecutionSummary(
                total = total,
                passed = passed,
                failed = failed,
                skipped = skipped,
                failureMessages = failures.distinct().take(25),
            )
        }

        private fun tailLines(text: String, maxLines: Int): String {
            val lines = text.lines()
            return if (lines.size <= maxLines) text else lines.takeLast(maxLines).joinToString("\n")
        }
    }
}
