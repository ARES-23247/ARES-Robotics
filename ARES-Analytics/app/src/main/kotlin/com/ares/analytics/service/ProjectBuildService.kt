package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import com.ares.analytics.service.verification.RobotVerificationReport
import com.ares.analytics.service.verification.RobotVerificationReportLoader
import com.ares.analytics.service.verification.VerificationRunStore
import com.areslib.codegen.SubsystemStarterPlan
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class AresGenerationPhase { IDLE, RUNNING, SUCCEEDED, FAILED }

data class AresGenerationState(
    val phase: AresGenerationPhase = AresGenerationPhase.IDLE,
    val message: String = "",
    val contentHash: String? = null
)

enum class BuildExecutionPhase { IDLE, RUNNING, SUCCEEDED, FAILED, CANCELED }

/**
 * Observable evidence from the most recent project verification build.
 *
 * This reports only the selected project, Gradle result, and process outcome. A successful build
 * never implies deployment, a running simulator, or physical-robot validation.
 */
data class BuildExecutionState(
    val phase: BuildExecutionPhase = BuildExecutionPhase.IDLE,
    val projectPath: String = "",
    val league: League? = null,
    val message: String = "No project verification build has run in this app session.",
    val exitCode: Int? = null,
    val requestId: Long = 0L,
    val verificationReport: RobotVerificationReport? = null,
)

/** Canonical observable state for project generation and verification builds. */
data class ProjectProcessState(
    val buildRunning: Boolean = false,
    val buildExecution: BuildExecutionState = BuildExecutionState(),
)

/** Small testable boundary used by offline authoring screens. */
interface AresProjectGenerator {
    val aresGenerationState: StateFlow<AresGenerationState>
    fun generateAresProject(projectPath: String, league: League)
    fun previewSubsystemStarters(projectPath: String, league: League): SubsystemStarterPlan
    fun applySubsystemStarters(projectPath: String, league: League, confirmationToken: String? = null)
}

private enum class BuildOperationKind { BUILD, GENERATION, TEST }

private data class BuildOwnership(
    val generation: Long,
    val kind: BuildOperationKind?,
    val job: Job?,
    val process: Process?
)

/**
 * Owns external OS process lifecycle for project generation and verification builds.
 *
 * Robot deployment and device targeting are owned by [RobotDeploymentService].
 *
 * ### Process Management Tasks:
 * - **Gradle Compilation**: Invokes local Gradle wrapper (`gradlew.bat` or `./gradlew`) with real-time output line buffering.
 *
 * ### Thread Safety & Performance Guarantees:
 * Process standard output/error reading runs asynchronously on `Dispatchers.IO`. Utilizes `SharedFlow(replay = 200)` to buffer process logs without thread blocking.
 *
 * @see AutoImportService
 * @see TargetScannerService
 */
class ProjectBuildService internal constructor(
    aresRepositoryUri: String?,
    aresVersion: String? = null,
    gradleJavaInstallations: List<File> = ManagedToolchainPaths.gradleJavaInstallations(),
    private val operationGate: ProjectProcessGate = ProjectProcessGate(),
) : AresProjectGenerator {

    constructor() : this(
        aresRepositoryUri = System.getProperty(ARES_REPOSITORY_URI_PROPERTY),
        aresVersion = System.getProperty(ARES_VERSION_PROPERTY),
    )

    internal constructor(operationGate: ProjectProcessGate) : this(
        aresRepositoryUri = System.getProperty(ARES_REPOSITORY_URI_PROPERTY),
        aresVersion = System.getProperty(ARES_VERSION_PROPERTY),
        operationGate = operationGate,
    )

    private val commandFactory = ProjectProcessCommandFactory(
        aresRepositoryUri = aresRepositoryUri,
        aresVersion = aresVersion,
        gradleJavaInstallations = gradleJavaInstallations,
    )
    private val subsystemStarterPlanner = SubsystemStarterPlanner()

    // Build tools can emit thousands of lines in a burst. Terminal rendering must never apply
    // back-pressure to the child process or its stdout pipe can fill and deadlock Gradle.
    private val _buildOutput = MutableSharedFlow<String>(
        replay = 200,
        extraBufferCapacity = 800,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val buildOutput: SharedFlow<String> = _buildOutput.asSharedFlow()

    private val _processState = MutableStateFlow(ProjectProcessState())
    val processState: StateFlow<ProjectProcessState> = _processState.asStateFlow()

    private val _aresGenerationState = MutableStateFlow(AresGenerationState())
    override val aresGenerationState: StateFlow<AresGenerationState> = _aresGenerationState.asStateFlow()

    internal fun rejectAresGeneration(message: String) {
        _aresGenerationState.value = AresGenerationState(AresGenerationPhase.FAILED, message)
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val buildLifecycleMutex = Mutex()
    private val buildStateLock = Any()
    private val buildRequestId = AtomicLong(0L)
    private val shuttingDown = AtomicBoolean(false)

    @Volatile
    private var activeBuildJob: Job? = null

    @Volatile
    private var buildProcess: Process? = null
    private var activeBuildGeneration = 0L
    private var activeBuildKind: BuildOperationKind? = null

    fun runBuild(projectPath: String, league: League) {
        enqueueBuildOperation(BuildOperationKind.BUILD) { generation ->
            executeBuild(generation, projectPath, league)
        }
    }

    private suspend fun executeBuild(generation: Long, projectPath: String, league: League) {
        val projectIdentity = normalizedProjectIdentity(projectPath)
        updateBuildExecutionStateIfOwner(
            generation,
            BuildExecutionState(
                phase = BuildExecutionPhase.RUNNING,
                projectPath = projectIdentity,
                league = league,
                message = "Verifying generated files, tests, and the ${league.name} project package. No deployment is performed.",
                requestId = generation,
            ),
        )
        try {
            val projectRoot = requireSafeProjectRoot(projectPath)
            val isWindows = System.getProperty("os.name").contains("win", ignoreCase = true)
            commandFactory.requireProjectDependenciesCompatible(projectRoot)
            commandFactory.requireProjectWrapper(projectRoot, league, isWindows)
            val command = commandFactory.verificationBuild(league, isWindows)
            val pendingRun = VerificationRunStore.begin(
                projectRoot = projectRoot,
                command = command,
                aresVersion = commandFactory.explicitAresVersion ?: commandFactory.projectPinnedAresVersion(projectRoot) ?: "unknown",
            )

            _buildOutput.emit("[SYSTEM] Starting compile-only project verification: ${command.joinToString(" ")}")
            var transientGradleCacheMoveFailure = false
            suspend fun runVerificationAttempt(): Int = runOwnedBuildProcess(
                generation,
                commandFactory.configureEnvironment(ProcessBuilder(command)
                    .directory(projectRoot)
                    .redirectErrorStream(true))
            ) { line ->
                if (isTransientGradleCacheMoveFailure(line)) transientGradleCacheMoveFailure = true
                _buildOutput.emit(line)
            }
            var exitCode = runVerificationAttempt()
            if (exitCode != 0 && transientGradleCacheMoveFailure) {
                currentCoroutineContext().ensureActive()
                _buildOutput.emit(
                    "[SYSTEM] Gradle's local transform cache was temporarily locked. " +
                        "Retrying verification once; no project files were changed.",
                )
                transientGradleCacheMoveFailure = false
                exitCode = runVerificationAttempt()
            }
            currentCoroutineContext().ensureActive()
            val provenance = VerificationRunStore.complete(pendingRun, exitCode)
            val verificationReport = VerificationRunStore.saveAndReload(
                projectRoot,
                RobotVerificationReportLoader.load(projectRoot, league, exitCode, provenance),
            )
            val result = if (exitCode == 0) {
                BuildExecutionState(
                    phase = BuildExecutionPhase.SUCCEEDED,
                    projectPath = projectIdentity,
                    league = league,
                    message = "Verification, tests, and package build passed. Nothing was deployed; rebuild after changing project files.",
                    exitCode = exitCode,
                    requestId = generation,
                    verificationReport = verificationReport,
                )
            } else {
                BuildExecutionState(
                    phase = BuildExecutionPhase.FAILED,
                    projectPath = projectIdentity,
                    league = league,
                    message = "Project verification failed with exit code $exitCode. Review the Build terminal, fix the first reported error, then run verification again.",
                    exitCode = exitCode,
                    requestId = generation,
                    verificationReport = verificationReport,
                )
            }
            updateBuildExecutionStateIfOwner(generation, result)
            _buildOutput.emit("[SYSTEM] ${result.message}")
        } catch (cancelled: CancellationException) {
            updateBuildExecutionStateIfOwner(
                generation,
                BuildExecutionState(
                    phase = BuildExecutionPhase.CANCELED,
                    projectPath = projectIdentity,
                    league = league,
                    message = "Project verification was canceled before a result was available. No deployment was performed.",
                    requestId = generation,
                ),
            )
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val result = BuildExecutionState(
                phase = BuildExecutionPhase.FAILED,
                projectPath = projectIdentity,
                league = league,
                message = "Project verification could not run: ${error.message ?: "unknown process error"}. Check the selected project and Build terminal, then try again.",
                requestId = generation,
            )
            updateBuildExecutionStateIfOwner(generation, result)
            _buildOutput.emit("[SYSTEM] ${result.message}")
        }
    }

    /**
     * Regenerates the checked-in Kotlin bridge from canonical `.ares` documents.
     *
     * The platform wrapper is launched with a fixed argument list and the selected project is used
     * only as the process working directory. No student-selected path is interpolated into shell
     * text. Using the wrapper entrypoint (rather than invoking GradleWrapperMain directly) is also
     * important on Gradle 8.11, whose direct client can remain alive after a successful single-use
     * daemon build. A robot connection is never involved.
    */
    override fun generateAresProject(projectPath: String, league: League) {
        enqueueBuildOperation(BuildOperationKind.GENERATION) { generation ->
            executeAresGeneration(generation, projectPath, league)
        }
    }

    override fun previewSubsystemStarters(projectPath: String, league: League): SubsystemStarterPlan {
        return subsystemStarterPlanner.plan(requireSafeProjectRoot(projectPath), league)
    }

    override fun applySubsystemStarters(projectPath: String, league: League, confirmationToken: String?) {
        val root = requireSafeProjectRoot(projectPath)
        // Re-plan immediately before scheduling so stale UI tokens fail closed in the Gradle task.
        val plan = previewSubsystemStarters(root.path, league)
        if (plan.hasReplacements) {
            require(confirmationToken != null && confirmationToken == plan.confirmationToken) {
                "The generated starter proposal changed. Review the new diff before replacing files."
            }
        }
        enqueueBuildOperation(BuildOperationKind.GENERATION) { generation ->
            executeSubsystemStarterGeneration(generation, root, league, confirmationToken)
        }
    }

    private suspend fun executeSubsystemStarterGeneration(
        generation: Long,
        root: File,
        league: League,
        confirmationToken: String?,
    ) {
        updateGenerationStateIfOwner(
            generation,
            AresGenerationState(AresGenerationPhase.RUNNING, "Applying reviewed subsystem starters and generated plumbing...")
        )
        val taskName = if (confirmationToken == null) "generateSubsystemStarters" else "replaceSubsystemStarters"
        val task = if (league == League.FTC) ":TeamCode:$taskName" else taskName
        val isWindows = System.getProperty("os.name").contains("win", ignoreCase = true)
        commandFactory.requireProjectDependenciesCompatible(root)
        commandFactory.requireProjectWrapper(root, league, isWindows)
        val command = commandFactory.authoring(league, task, isWindows, confirmationToken)
        val diagnosticLines = ArrayDeque<String>(GENERATION_DIAGNOSTIC_LINE_LIMIT)
        try {
            val exitCode = runOwnedBuildProcess(
                generation,
                commandFactory.configureEnvironment(ProcessBuilder(command).directory(root).redirectErrorStream(true)),
            ) { line ->
                if (diagnosticLines.size == GENERATION_DIAGNOSTIC_LINE_LIMIT) diagnosticLines.removeFirst()
                diagnosticLines.addLast(line)
                _buildOutput.emit(line)
            }
            check(exitCode == 0) {
                diagnosticLines.joinToString("\n").takeLast(GENERATION_DIAGNOSTIC_CHARACTER_LIMIT)
                    .ifBlank { "Subsystem starter generation failed with exit code $exitCode" }
            }
            updateGenerationStateIfOwner(
                generation,
                AresGenerationState(AresGenerationPhase.SUCCEEDED, "Subsystem starters and generated plumbing are current.")
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val message = error.message?.takeLast(GENERATION_DIAGNOSTIC_CHARACTER_LIMIT)
                ?: "Subsystem starter generation failed"
            updateGenerationStateIfOwner(generation, AresGenerationState(AresGenerationPhase.FAILED, message))
            _buildOutput.emit("[ARES] Subsystem starter generation failed: $message")
        }
    }

    private suspend fun executeAresGeneration(generation: Long, projectPath: String, league: League) {
        updateGenerationStateIfOwner(
            generation,
            AresGenerationState(
                AresGenerationPhase.RUNNING,
                "Saving complete. Generating Kotlin from the local project..."
            )
        )
        val diagnosticLines = ArrayDeque<String>(GENERATION_DIAGNOSTIC_LINE_LIMIT)
        try {
            val root = requireSafeProjectRoot(projectPath)
            require(File(root, ".ares").canonicalFile.isDirectory) {
                "This directory does not contain canonical .ares project documents"
            }
            val isWindows = System.getProperty("os.name").contains("win", ignoreCase = true)
            commandFactory.requireProjectDependenciesCompatible(root)
            commandFactory.requireProjectWrapper(root, league, isWindows)

            val command = commandFactory.authoring(league, "generateAresProject", isWindows)
            _buildOutput.emit("[ARES] Generating ${if (league == League.XRP) "MicroPython" else "Kotlin"} from canonical project files")
            val exitCode = runOwnedBuildProcess(
                generation,
                commandFactory.configureEnvironment(ProcessBuilder(command)
                    .directory(root)
                    .redirectErrorStream(true))
            ) { line ->
                if (diagnosticLines.size == GENERATION_DIAGNOSTIC_LINE_LIMIT) diagnosticLines.removeFirst()
                diagnosticLines.addLast(line)
                _buildOutput.emit(line)
            }
            currentCoroutineContext().ensureActive()
            if (exitCode != 0) {
                error(
                    diagnosticLines.joinToString("\n").ifBlank {
                        "Gradle generation failed with exit code $exitCode"
                    }
                )
            }
            val hash = readGeneratedContentHash(root, league)
            val suffix = hash?.let { " Content ${it.take(12)}..." }.orEmpty()
            updateGenerationStateIfOwner(
                generation,
                AresGenerationState(
                    AresGenerationPhase.SUCCEEDED,
                    "Generated ${if (league == League.XRP) "MicroPython" else "Kotlin"} is current.$suffix Robot builds will still verify it is not stale.",
                    hash
                )
            )
            _buildOutput.emit("[ARES] Generation finished successfully.$suffix")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val message = error.message?.takeLast(GENERATION_DIAGNOSTIC_CHARACTER_LIMIT)
                ?: "ARES project generation failed"
            updateGenerationStateIfOwner(
                generation,
                AresGenerationState(AresGenerationPhase.FAILED, message)
            )
            _buildOutput.emit("[ARES] Generation failed: $message")
        }
    }

    private fun enqueueBuildOperation(
        kind: BuildOperationKind,
        operation: suspend (generation: Long) -> Unit
    ) {
        if (shuttingDown.get()) return
        val requestId = buildRequestId.incrementAndGet()
        serviceScope.launch {
            buildLifecycleMutex.withLock {
                if (shuttingDown.get() || requestId != buildRequestId.get()) return@withLock
                stopActiveBuildLocked()
                if (shuttingDown.get() || requestId != buildRequestId.get()) return@withLock

                val replacement = serviceScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        operationGate.runExclusive { operation(requestId) }
                    } finally {
                        releaseBuildOwnership(requestId)
                    }
                }
                synchronized(buildStateLock) {
                    activeBuildGeneration = requestId
                    activeBuildKind = kind
                    activeBuildJob = replacement
                    buildProcess = null
                }
                _processState.update { it.copy(buildRunning = true) }
                replacement.start()
            }
        }
    }

    /** Test seam that exercises the same ownership/replacement path as builds and generation. */
    internal fun runManagedProcessForTest(command: List<String>, generationOperation: Boolean = false) {
        require(command.isNotEmpty()) { "Process command must not be empty" }
        val kind = if (generationOperation) BuildOperationKind.GENERATION else BuildOperationKind.TEST
        enqueueBuildOperation(kind) { generation ->
            if (generationOperation) {
                updateGenerationStateIfOwner(
                    generation,
                    AresGenerationState(AresGenerationPhase.RUNNING, "Test generation running")
                )
            }
            runOwnedBuildProcess(
                generation,
                ProcessBuilder(command).redirectErrorStream(true)
            ) { line -> _buildOutput.emit(line) }
        }
    }

    internal suspend fun awaitBuildIdleForTest() {
        while (true) {
            val active = synchronized(buildStateLock) { activeBuildJob } ?: return
            active.join()
            if (synchronized(buildStateLock) { activeBuildJob == null }) return
        }
    }

    private suspend fun runOwnedBuildProcess(
        generation: Long,
        processBuilder: ProcessBuilder,
        onLine: suspend (String) -> Unit
    ): Int {
        var process: Process? = null
        try {
            // Capture the handle even if cancellation arrives while the OS is creating it; the
            // ownership check immediately below then kills it instead of leaking an untracked child.
            val started = withContext(NonCancellable + Dispatchers.IO) { processBuilder.start() }
            process = started
            if (!claimBuildProcess(generation, started)) {
                throw CancellationException("Build ownership changed before process registration")
            }
            currentCoroutineContext().ensureActive()
            started.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    currentCoroutineContext().ensureActive()
                    onLine(line)
                }
            }
            currentCoroutineContext().ensureActive()
            return runInterruptible(Dispatchers.IO) { started.waitFor() }
        } catch (cancelled: CancellationException) {
            process?.let { terminateProcessTree(it) }
            throw cancelled
        } finally {
            process?.let {
                if (it.isAlive) terminateProcessTree(it)
                releaseBuildProcess(generation, it)
            }
        }
    }

    private fun claimBuildProcess(generation: Long, process: Process): Boolean = synchronized(buildStateLock) {
        if (activeBuildGeneration != generation || activeBuildJob?.isActive != true) {
            false
        } else {
            buildProcess = process
            true
        }
    }

    private fun releaseBuildProcess(generation: Long, process: Process) {
        synchronized(buildStateLock) {
            if (activeBuildGeneration == generation && buildProcess === process) {
                buildProcess = null
            }
        }
    }

    private fun releaseBuildOwnership(generation: Long) {
        synchronized(buildStateLock) {
            if (activeBuildGeneration != generation) {
                return@synchronized
            } else {
                activeBuildGeneration = 0L
                activeBuildKind = null
                activeBuildJob = null
                buildProcess = null
                _processState.update { it.copy(buildRunning = false) }
            }
        }
    }

    private fun updateGenerationStateIfOwner(generation: Long, state: AresGenerationState) {
        synchronized(buildStateLock) {
            if (activeBuildGeneration == generation && activeBuildKind == BuildOperationKind.GENERATION) {
                _aresGenerationState.value = state
            }
        }
    }

    private fun updateBuildExecutionStateIfOwner(generation: Long, state: BuildExecutionState) {
        synchronized(buildStateLock) {
            if (activeBuildGeneration == generation && activeBuildKind == BuildOperationKind.BUILD) {
                _processState.update { it.copy(buildExecution = state) }
            }
        }
    }

    fun killActiveBuild() {
        runBlocking { killActiveBuildAndJoin() }
    }

    internal suspend fun killActiveBuildAndJoin() {
        val requestId = buildRequestId.incrementAndGet()
        buildLifecycleMutex.withLock {
            if (requestId == buildRequestId.get()) stopActiveBuildLocked()
        }
    }

    private suspend fun stopActiveBuildLocked() = withContext(NonCancellable) {
        val ownership = synchronized(buildStateLock) {
            BuildOwnership(activeBuildGeneration, activeBuildKind, activeBuildJob, buildProcess)
        }
        ownership.job?.cancel()
        ownership.process?.let { terminateProcessTree(it) }
        ownership.job?.cancelAndJoin()

        val released = synchronized(buildStateLock) {
            if (activeBuildGeneration != ownership.generation || ownership.generation == 0L) {
                false
            } else {
                activeBuildGeneration = 0L
                activeBuildKind = null
                activeBuildJob = null
                buildProcess = null
                true
            }
        }
        if (released || ownership.job != null) {
            _processState.update { it.copy(buildRunning = false) }
        }
        if (
            ownership.kind == BuildOperationKind.BUILD &&
            _processState.value.buildExecution.phase == BuildExecutionPhase.RUNNING
        ) {
            _processState.update {
                it.copy(
                    buildExecution = it.buildExecution.copy(
                        phase = BuildExecutionPhase.CANCELED,
                        message = "Project verification was canceled before a result was available. No deployment was performed.",
                        exitCode = null,
                    ),
                )
            }
        }
        if (ownership.kind == BuildOperationKind.GENERATION &&
            _aresGenerationState.value.phase == AresGenerationPhase.RUNNING
        ) {
            _aresGenerationState.value = AresGenerationState(AresGenerationPhase.FAILED, "Generation canceled.")
        }
    }

    fun shutdown() {
        runBlocking { shutdownAndJoin() }
    }

    internal suspend fun shutdownAndJoin() = withContext(NonCancellable) {
        shuttingDown.set(true)
        buildRequestId.incrementAndGet()
        buildLifecycleMutex.withLock { stopActiveBuildLocked() }
        serviceScope.coroutineContext[Job]?.cancelAndJoin()
    }

    private fun requireSafeProjectRoot(projectPath: String): File {
        require(projectPath.isNotBlank()) { "Choose a robot project directory first" }
        val root = File(projectPath).canonicalFile
        require(root.isDirectory) { "The selected project directory does not exist" }
        return root
    }

    /** Test seam for success, failure, cancellation, and replacement result ownership. */
    internal fun runVerificationProcessForTest(command: List<String>, projectPath: String, league: League) {
        require(command.isNotEmpty()) { "Process command must not be empty" }
        enqueueBuildOperation(BuildOperationKind.BUILD) { generation ->
            val projectIdentity = normalizedProjectIdentity(projectPath)
            updateBuildExecutionStateIfOwner(
                generation,
                BuildExecutionState(
                    phase = BuildExecutionPhase.RUNNING,
                    projectPath = projectIdentity,
                    league = league,
                    message = "Test verification running. No deployment is performed.",
                    requestId = generation,
                ),
            )
            try {
                val exitCode = runOwnedBuildProcess(
                    generation,
                    ProcessBuilder(command).directory(requireSafeProjectRoot(projectPath)).redirectErrorStream(true),
                ) { }
                updateBuildExecutionStateIfOwner(
                    generation,
                    BuildExecutionState(
                        phase = if (exitCode == 0) BuildExecutionPhase.SUCCEEDED else BuildExecutionPhase.FAILED,
                        projectPath = projectIdentity,
                        league = league,
                        message = if (exitCode == 0) "Test verification passed." else "Test verification failed.",
                        exitCode = exitCode,
                        requestId = generation,
                    ),
                )
            } catch (cancelled: CancellationException) {
                updateBuildExecutionStateIfOwner(
                    generation,
                    BuildExecutionState(
                        phase = BuildExecutionPhase.CANCELED,
                        projectPath = projectIdentity,
                        league = league,
                        message = "Test verification canceled. No deployment was performed.",
                        requestId = generation,
                    ),
                )
                throw cancelled
            }
        }
    }

    private fun normalizedProjectIdentity(projectPath: String): String =
        runCatching { File(projectPath).absoluteFile.normalize().path }
            .getOrDefault(projectPath.trim())

    private fun readGeneratedContentHash(root: File, league: League): String? {
        val relative = when (league) {
            League.FTC -> "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/generated/GeneratedAresProject.kt"
            League.FRC -> "src/main/kotlin/com/areslib/frc/generated/GeneratedAresProject.kt"
            League.XRP -> "build/generated/ares/python/generated_ares_project.py"
        }
        val generated = File(root, relative).canonicalFile
        if (!generated.isFile || !generated.toPath().startsWith(root.toPath())) return null
        return GENERATED_CONTENT_HASH.find(generated.readText())?.groupValues?.get(1)
    }

    private companion object {
        const val GENERATION_DIAGNOSTIC_LINE_LIMIT = 24
        const val GENERATION_DIAGNOSTIC_CHARACTER_LIMIT = 4_000
        val GENERATED_CONTENT_HASH = Regex(
            "(?:CONTENT_SHA256:\\s*String\\s*=|CONTENT_SHA256\\s*=)\\s*\"([0-9a-fA-F]{64})\"",
        )
    }
}

internal fun isTransientGradleCacheMoveFailure(line: String): Boolean {
    val normalized = line.lowercase()
    return normalized.contains("could not move temporary workspace") ||
        (normalized.contains("temporary workspace") && normalized.contains("immutable location"))
}
internal fun normalizeUnixGradleWrapper(wrapper: File) {
    val bytes = wrapper.readBytes()
    if (bytes.contains('\r'.code.toByte())) {
        wrapper.writeBytes(bytes.filterNot { it == '\r'.code.toByte() }.toByteArray())
    }
}
