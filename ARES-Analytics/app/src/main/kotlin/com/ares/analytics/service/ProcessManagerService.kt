package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import com.ares.analytics.service.project.templateDeploymentBlockReason
import com.ares.analytics.service.verification.RobotVerificationReport
import com.ares.analytics.service.verification.RobotVerificationReportLoader
import com.ares.analytics.service.verification.VerificationRunStore
import com.areslib.codegen.GeneratedSubsystemFile
import com.areslib.codegen.SubsystemKotlinCodegenTarget
import com.areslib.codegen.SubsystemKotlinGenerator
import com.areslib.codegen.SubsystemStarterPlan
import com.areslib.codegen.SubsystemStarterReconciler
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.isAresGenerated
import com.areslib.simulation.SimulationProductId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit
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

enum class DeployExecutionPhase {
    IDLE,
    CONNECTING,
    BUILDING,
    INSTALLING,
    SUCCEEDED,
    FAILED,
    CANCELED,
}

data class DeployExecutionState(
    val phase: DeployExecutionPhase = DeployExecutionPhase.IDLE,
    val projectPath: String = "",
    val league: League = League.FTC,
    val message: String = "Ready to deploy to robot.",
    val progressPercent: Float = 0f,
    val requestId: Long = 0L,
)

/** Canonical observable state for project builds, deployment, ADB, and simulation processes. */
data class ProjectProcessState(
    val simulatorRunning: Boolean = false,
    val activeSimulationProjectPath: String? = null,
    val activeSimulationLeague: League? = null,
    val buildRunning: Boolean = false,
    val buildExecution: BuildExecutionState = BuildExecutionState(),
    val adbConnected: Boolean = false,
    val deployExecution: DeployExecutionState = DeployExecutionState(),
)

/** Small testable boundary used by offline authoring screens. */
interface AresProjectGenerator {
    val aresGenerationState: StateFlow<AresGenerationState>
    fun generateAresProject(projectPath: String, league: League)
    fun previewSubsystemStarters(projectPath: String, league: League): SubsystemStarterPlan
    fun applySubsystemStarters(projectPath: String, league: League, confirmationToken: String? = null)
}

private val SimulationProductId.league: League
    get() = when (this) {
        SimulationProductId.FTC_DESKTOP_OPMODE -> League.FTC
        SimulationProductId.FRC_WPILIB_DESKTOP -> League.FRC
    }

private enum class BuildOperationKind { BUILD, GENERATION, TEST }

private data class BuildOwnership(
    val generation: Long,
    val kind: BuildOperationKind?,
    val job: Job?,
    val process: Process?
)

private data class SubsystemStarterInputs(
    val root: File,
    val files: List<GeneratedSubsystemFile>,
)

/**
 * Service managing external OS process lifecycle execution for Gradle builds, ADB logcat streams, and physics simulators.
 *
 * Spawns and monitors underlying system processes for compiling FTC/FRC codebases (`./gradlew assembleDebug`), streaming Android
 * Control Hub logs (`adb logcat`), and launching desktop robot physics simulators (`DesktopSimLauncher`).
 *
 * ### Process Management Tasks:
 * - **Gradle Compilation**: Invokes local Gradle wrapper (`gradlew.bat` or `./gradlew`) with real-time output line buffering.
 * - **ADB Daemon Monitoring**: Monitors ADB connection state to physical Control Hubs on port 5555.
 * - **Simulator Launcher**: Executes JVM desktop physics simulator processes with cancellation supervisor jobs.
 *
 * ### Thread Safety & Performance Guarantees:
 * Process standard output/error reading runs asynchronously on `Dispatchers.IO`. Utilizes `SharedFlow(replay = 200)` to buffer process logs without thread blocking.
 *
 * @see AutoImportService
 * @see TargetScannerService
 */
class ProcessManagerService internal constructor(
    private val monitorAdbConnection: Boolean,
    aresRepositoryUri: String?,
    aresVersion: String? = null,
    gradleJavaInstallations: List<File> = ManagedToolchainPaths.gradleJavaInstallations(),
) : AresProjectGenerator {

    constructor() : this(
        monitorAdbConnection = true,
        aresRepositoryUri = System.getProperty(ARES_REPOSITORY_URI_PROPERTY),
        aresVersion = System.getProperty(ARES_VERSION_PROPERTY),
    )

    internal constructor(monitorAdbConnection: Boolean) : this(
        monitorAdbConnection = monitorAdbConnection,
        aresRepositoryUri = null,
        aresVersion = null,
    )

    private val commandFactory = ProjectProcessCommandFactory(
        aresRepositoryUri = aresRepositoryUri,
        aresVersion = aresVersion,
        gradleJavaInstallations = gradleJavaInstallations,
    )

    // Build tools can emit thousands of lines in a burst. Terminal rendering must never apply
    // back-pressure to the child process or its stdout pipe can fill and deadlock Gradle.
    private val _buildOutput = MutableSharedFlow<String>(
        replay = 200,
        extraBufferCapacity = 800,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val buildOutput: SharedFlow<String> = _buildOutput.asSharedFlow()

    private val _logcatOutput = MutableSharedFlow<String>(
        replay = 200,
        extraBufferCapacity = 800,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val logcatOutput: SharedFlow<String> = _logcatOutput.asSharedFlow()

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
    private var activeLogcatJob: Job? = null
    private var activeSimJob: Job? = null
    private var adbMonitorJob: Job? = null

    @Volatile
    private var buildProcess: Process? = null
    private var activeBuildGeneration = 0L
    private var activeBuildKind: BuildOperationKind? = null
    private var logcatProcess: Process? = null
    private var simProcess: Process? = null

    init {
        // Start periodic ADB connection check
        if (monitorAdbConnection) startAdbMonitoring()
    }

    private fun startAdbMonitoring() {
        adbMonitorJob?.cancel()
        adbMonitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    val pb = ProcessBuilder("adb", "devices").redirectErrorStream(true)
                    val proc = pb.start()
                    val output = StringBuilder()
                    val exitCode = waitForProcess(proc, 3) { line ->
                        if (output.length < MAX_MONITOR_OUTPUT_CHARS) output.appendLine(line)
                    }
                    val text = output.toString()
                    val isConnected = exitCode == 0 &&
                        (text.contains("192.168.43.1:5555") || text.contains("device\n") || text.contains("device\r"))
                    _processState.update { it.copy(adbConnected = isConnected) }
                } catch (e: Exception) {
                    _processState.update { it.copy(adbConnected = false) }
                }
                delay(5000)
            }
        }
    }

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
            commandFactory.requireGradleWrapper(projectRoot, isWindows)
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
        val inputs = subsystemStarterInputs(requireSafeProjectRoot(projectPath), league)
        return SubsystemStarterReconciler.plan(inputs.root.toPath(), inputs.files)
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
        commandFactory.requireGradleWrapper(root, isWindows)
        val command = commandFactory.authoring(task, isWindows, confirmationToken)
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

    private fun subsystemStarterInputs(root: File, league: League): SubsystemStarterInputs {
        val platform = if (league == League.FTC) SubsystemPlatform.FTC else SubsystemPlatform.FRC
        val basePackage = if (league == League.FTC) {
            "org.firstinspires.ftc.teamcode.subsystems"
        } else {
            "com.areslib.frc.subsystems"
        }
        val starterRoot = if (league == League.FTC) {
            File(root, "TeamCode/src/main/java/${basePackage.replace('.', '/')}")
        } else {
            File(root, "src/main/kotlin/${basePackage.replace('.', '/')}")
        }.canonicalFile
        require(starterRoot.toPath().startsWith(root.toPath())) { "Subsystem starter root escaped the project" }
        val documentsRoot = File(root, ".ares/subsystems").canonicalFile
        val documents = documentsRoot.listFiles { file -> file.isFile && file.extension.equals("aressubsystem", true) }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { SubsystemDocumentCodec.decode(it.readText()) }
            .filter { it.platform == platform }
            .filter { it.implementation.kind.isAresGenerated() }
        val target = SubsystemKotlinCodegenTarget(platform, basePackage)
        return SubsystemStarterInputs(starterRoot, documents.flatMap { SubsystemKotlinGenerator.generate(it, target) })
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
            commandFactory.requireGradleWrapper(root, isWindows)

            val command = commandFactory.authoring("generateAresProject", isWindows)
            _buildOutput.emit("[ARES] Generating checked-in Kotlin from canonical project files")
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
                    "Generated Kotlin is current.$suffix Robot builds will still verify it is not stale.",
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
                        operation(requestId)
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

    private fun updateDeployStateIfOwner(generation: Long, state: DeployExecutionState) {
        synchronized(buildStateLock) {
            if (activeBuildGeneration == generation) {
                _processState.update { it.copy(deployExecution = state) }
            }
        }
    }

    fun deployToRobot(projectPath: String, league: League) {
        val isWindows = System.getProperty("os.name").contains("win", true)
        enqueueBuildOperation(BuildOperationKind.BUILD) { generation ->
            var canonicalProjectPath = projectPath
            updateDeployStateIfOwner(
                generation,
                DeployExecutionState(
                    phase = DeployExecutionPhase.CONNECTING,
                    projectPath = projectPath,
                    league = league,
                    message = "Validating the selected ${league.name} project before deployment...",
                    progressPercent = 0.1f,
                    requestId = generation,
                ),
            )
            try {
                val root = requireSafeProjectRoot(projectPath)
                canonicalProjectPath = root.path
                templateDeploymentBlockReason(root)?.let { reason -> error(reason) }
                if (league == League.FTC) {
                    val adb = resolveAdbPath()
                    _buildOutput.emit("[DEPLOY] Connecting wireless ADB ($FTC_ADB_TARGET)...")
                    val connectExit = runOwnedBuildProcess(
                        generation,
                        ProcessBuilder(commandFactory.adbConnect(adb)).directory(root).redirectErrorStream(true),
                    ) { line -> _buildOutput.emit("[ADB] $line") }
                    check(connectExit == 0) { "ADB could not connect to $FTC_ADB_TARGET (exit $connectExit)" }

                    val identityOutput = StringBuilder()
                    val identityExit = runOwnedBuildProcess(
                        generation,
                        ProcessBuilder(commandFactory.adbIdentity(adb)).directory(root).redirectErrorStream(true),
                    ) { line ->
                        identityOutput.appendLine(line)
                        _buildOutput.emit("[ADB] $line")
                    }
                    check(identityExit == 0 && identityOutput.isNotBlank()) {
                        "The selected Control Hub did not answer the identity check"
                    }

                    updateDeployStateIfOwner(
                        generation,
                        DeployExecutionState(
                            phase = DeployExecutionPhase.BUILDING,
                            projectPath = root.path,
                            league = league,
                            message = "Generating, verifying, testing, and packaging the FTC robot app...",
                            progressPercent = 0.4f,
                            requestId = generation,
                        ),
                    )

                    val buildCommand = commandFactory.ftcDeployBuild(isWindows)

                    val buildExit = runOwnedBuildProcess(
                        generation,
                        commandFactory.configureEnvironment(ProcessBuilder(buildCommand).directory(root).redirectErrorStream(true)),
                    ) { line -> _buildOutput.emit(line) }

                    check(buildExit == 0) { "FTC compilation failed with exit code $buildExit" }

                    updateDeployStateIfOwner(
                        generation,
                        DeployExecutionState(
                            phase = DeployExecutionPhase.INSTALLING,
                            projectPath = root.path,
                            league = league,
                            message = "Installing APK on robot via ADB...",
                            progressPercent = 0.8f,
                            requestId = generation,
                        ),
                    )

                    val apkFile = File(root, "TeamCode/build/outputs/apk/debug/TeamCode-debug.apk")
                    val apkTarget = if (apkFile.exists()) apkFile.absolutePath else "TeamCode/build/outputs/apk/debug/TeamCode-debug.apk"

                    val installExit = runOwnedBuildProcess(
                        generation,
                        ProcessBuilder(commandFactory.adbInstall(adb, apkTarget)).directory(root).redirectErrorStream(true),
                    ) { line -> _buildOutput.emit("[INSTALL] $line") }

                    check(installExit == 0) { "APK installation failed with exit code $installExit" }

                    val packageOutput = StringBuilder()
                    val packageCheckExit = runOwnedBuildProcess(
                        generation,
                        ProcessBuilder(commandFactory.adbPackageCheck(adb)).directory(root).redirectErrorStream(true),
                    ) { line ->
                        packageOutput.appendLine(line)
                        _buildOutput.emit("[VERIFY] $line")
                    }
                    check(packageCheckExit == 0 && packageOutput.contains("package:")) {
                        "ADB did not confirm the installed FTC Robot Controller package"
                    }

                    updateDeployStateIfOwner(
                        generation,
                        DeployExecutionState(
                            phase = DeployExecutionPhase.SUCCEEDED,
                            projectPath = root.path,
                            league = league,
                            message = "FTC Robot Controller package installed and verified on $FTC_ADB_TARGET. Select the intended OpMode in Driver Station before enabling.",
                            progressPercent = 1.0f,
                            requestId = generation,
                        ),
                    )
                    _buildOutput.emit("[DEPLOY] SUCCESS: Package installed and verified. No OpMode was started or robot motion commanded.")
                } else {
                    updateDeployStateIfOwner(
                        generation,
                        DeployExecutionState(
                            phase = DeployExecutionPhase.BUILDING,
                            projectPath = root.path,
                            league = league,
                            message = "Generating, verifying, testing, building, then deploying to the configured RoboRIO...",
                            progressPercent = 0.5f,
                            requestId = generation,
                        ),
                    )

                    val deployCommand = commandFactory.frcDeployBuild(isWindows)

                    val deployExit = runOwnedBuildProcess(
                        generation,
                        commandFactory.configureEnvironment(ProcessBuilder(deployCommand).directory(root).redirectErrorStream(true)),
                    ) { line -> _buildOutput.emit(line) }

                    check(deployExit == 0) { "FRC deploy failed with exit code $deployExit" }

                    updateDeployStateIfOwner(
                        generation,
                        DeployExecutionState(
                            phase = DeployExecutionPhase.SUCCEEDED,
                            projectPath = root.path,
                            league = league,
                            message = "FRC deploy completed for the RoboRIO configured by this project. No robot motion was commanded.",
                            progressPercent = 1.0f,
                            requestId = generation,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                updateDeployStateIfOwner(
                    generation,
                    DeployExecutionState(
                        phase = DeployExecutionPhase.CANCELED,
                        projectPath = canonicalProjectPath,
                        league = league,
                        message = "Deploy operation was canceled.",
                        progressPercent = 0f,
                        requestId = generation,
                    ),
                )
                throw cancelled
            } catch (error: Exception) {
                updateDeployStateIfOwner(
                    generation,
                    DeployExecutionState(
                        phase = DeployExecutionPhase.FAILED,
                        projectPath = canonicalProjectPath,
                        league = league,
                        message = "Deployment failed: ${error.message ?: "unknown error"}",
                        progressPercent = 0f,
                        requestId = generation,
                    ),
                )
                _buildOutput.emit("[DEPLOY] FAILED: ${error.message}")
            }
        }
    }

    fun runSimulation(projectPath: String, product: SimulationProductId, simulatorCommand: String? = null) {
        if (shuttingDown.get()) return
        val league = product.league
        val projectRoot = runCatching { requireSafeProjectRoot(projectPath) }.getOrElse { error ->
            serviceScope.launch {
                _buildOutput.emit("[SYSTEM] Simulation could not start: ${error.message ?: "choose a valid robot project"}")
            }
            return
        }
        killActiveSim()

        val replacement = serviceScope.launch(start = CoroutineStart.LAZY) {
            var ownedProcess: Process? = null
            try {
                _processState.update {
                    it.copy(
                        simulatorRunning = true,
                        activeSimulationProjectPath = projectRoot.path,
                        activeSimulationLeague = league,
                    )
                }
                val isWindows = System.getProperty("os.name").contains("win", ignoreCase = true)
                val userCmd = simulatorCommand?.takeIf { it.isNotBlank() }
                val fatJarFile = File(projectRoot, "simulator/build/libs/simulator-all.jar")
                val simulationJavaHome = if (league == League.FRC) {
                    ManagedToolchainPaths.resolveFrcSimulationJavaHome()
                } else {
                    ManagedToolchainPaths.resolveJavaHome()
                }
                val javaExe = simulationJavaHome
                    ?.let { File(it, "bin/${if (isWindows) "java.exe" else "java"}") }
                    ?.takeIf(File::isFile)
                    ?.path
                    ?: File(System.getProperty("java.home"), "bin/${if (isWindows) "java.exe" else "java"}").path
                val cmd = when {
                    userCmd != null && isWindows -> listOf("cmd.exe", "/d", "/s", "/c", userCmd)
                    userCmd != null -> listOf("sh", "-c", userCmd)
                    product == SimulationProductId.FTC_DESKTOP_OPMODE && fatJarFile.exists() ->
                        listOf(javaExe, "-jar", fatJarFile.absolutePath)
                    else -> commandFactory.simulation(isWindows, product)
                }

                _buildOutput.emit("[SYSTEM] Starting Simulation: ${cmd.joinToString(" ")}")
                val pb = commandFactory.configureEnvironment(ProcessBuilder(cmd)
                    .directory(projectRoot)
                    .redirectErrorStream(true))
                if (league == League.FRC && simulationJavaHome != null) {
                    ManagedToolchainPaths.configureJavaEnvironment(pb, simulationJavaHome)
                    _buildOutput.emit("[SYSTEM] FRC simulator Java: ${simulationJavaHome.path}")
                }
                val proc = pb.start()
                ownedProcess = proc
                simProcess = proc
                currentCoroutineContext().ensureActive()

                proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        currentCoroutineContext().ensureActive()
                        _buildOutput.emit(line)
                    }
                }
                currentCoroutineContext().ensureActive()
                val exitCode = runInterruptible(Dispatchers.IO) { proc.waitFor() }
                _buildOutput.emit("[SYSTEM] Simulation finished with exit code $exitCode")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                _buildOutput.emit("[SYSTEM] Error running simulation: ${e.message}")
            } finally {
                ownedProcess?.let { if (it.isAlive) terminateProcessTree(it) }
                if (simProcess === ownedProcess) simProcess = null
                _processState.update {
                    it.copy(
                        simulatorRunning = false,
                        activeSimulationProjectPath = null,
                        activeSimulationLeague = null,
                    )
                }
            }
        }
        activeSimJob = replacement
        replacement.start()
    }

    fun startLogcat() {
        if (shuttingDown.get()) return
        killActiveLogcat()

        val replacement = serviceScope.launch(start = CoroutineStart.LAZY) {
            var ownedProcess: Process? = null
            try {
                _logcatOutput.emit("[SYSTEM] Starting ADB logcat stream...")
                val adb = resolveAdbPath()
                val pb = ProcessBuilder(adb, "logcat", "-v", "time")
                    .redirectErrorStream(true)
                val proc = pb.start()
                ownedProcess = proc
                logcatProcess = proc
                currentCoroutineContext().ensureActive()

                proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        currentCoroutineContext().ensureActive()
                        _logcatOutput.emit(line)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                _logcatOutput.emit("[SYSTEM] Error streaming logcat: ${e.message}")
            } finally {
                ownedProcess?.let { if (it.isAlive) terminateProcessTree(it) }
                if (logcatProcess === ownedProcess) logcatProcess = null
            }
        }
        activeLogcatJob = replacement
        replacement.start()
    }

    private fun resolveAdbPath(): String {
        ManagedToolchainPaths.resolveAndroidSdk()?.let { sdk ->
            val executable = File(sdk, "platform-tools/${if (System.getProperty("os.name").contains("win", true)) "adb.exe" else "adb"}")
            if (executable.isFile) return executable.absolutePath
        }
        val platformTools = File(System.getenv("LOCALAPPDATA") ?: "", "Android/Sdk/platform-tools/adb.exe")
        if (platformTools.exists()) return platformTools.absolutePath
        val adbMac = File(System.getProperty("user.home"), "Library/Android/sdk/platform-tools/adb")
        if (adbMac.exists()) return adbMac.absolutePath
        return "adb"
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

    fun killActiveLogcat() {
        runBlocking { stopLogcatAndJoin() }
    }

    fun killActiveSim() {
        runBlocking { stopSimulationAndJoin() }
    }

    fun shutdown() {
        runBlocking { shutdownAndJoin() }
    }

    internal suspend fun shutdownAndJoin() = withContext(NonCancellable) {
        shuttingDown.set(true)
        buildRequestId.incrementAndGet()
        buildLifecycleMutex.withLock { stopActiveBuildLocked() }
        stopLogcatAndJoin()
        stopSimulationAndJoin()
        adbMonitorJob?.cancelAndJoin()
        adbMonitorJob = null
        serviceScope.coroutineContext[Job]?.cancelAndJoin()
    }

    private suspend fun stopLogcatAndJoin() = withContext(NonCancellable) {
        val process = logcatProcess
        val job = activeLogcatJob
        job?.cancel()
        process?.let { terminateProcessTree(it) }
        job?.cancelAndJoin()
        if (logcatProcess === process) logcatProcess = null
        if (activeLogcatJob === job) activeLogcatJob = null
    }

    private suspend fun stopSimulationAndJoin() = withContext(NonCancellable) {
        val process = simProcess
        val job = activeSimJob
        job?.cancel()
        process?.let { terminateProcessTree(it) }
        job?.cancelAndJoin()
        if (simProcess === process) simProcess = null
        if (activeSimJob === job) activeSimJob = null
        _processState.update {
            it.copy(
                simulatorRunning = false,
                activeSimulationProjectPath = null,
                activeSimulationLeague = null,
            )
        }
    }

    /** Drains output concurrently so a verbose child cannot fill its pipe before the timeout. */
    private suspend fun waitForProcess(
        process: Process,
        timeoutSeconds: Long,
        onLine: suspend (String) -> Unit
    ): Int? = coroutineScope {
        runCatching { process.outputStream.close() }
        val drain = async(Dispatchers.IO) {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) onLine(reader.readLine() ?: break)
                }
            }
        }
        val finished = try {
            withTimeoutOrNull(timeoutSeconds * 1_000L) {
                while (process.isAlive) delay(25)
                true
            } ?: false
        } catch (cancelled: CancellationException) {
            terminateProcessTree(process)
            throw cancelled
        }
        if (!finished) {
            terminateProcessTree(process)
            withTimeoutOrNull(2_000) {
                while (process.isAlive) delay(25)
            }
        }
        withTimeoutOrNull(2_000) { drain.await() } ?: drain.cancel()
        if (finished) process.exitValue() else null
    }

    private suspend fun terminateProcessTree(process: Process) {
        withContext(NonCancellable + Dispatchers.IO) {
            val handles = mutableListOf<ProcessHandle>()
            runCatching {
                process.descendants().use { descendants ->
                    descendants.forEach { handles.add(it) }
                }
            }
            handles.asReversed().forEach { child -> runCatching { child.destroyForcibly() } }
            runCatching { process.destroyForcibly() }

            val allHandles = handles + process.toHandle()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_TREE_KILL_GRACE_MS)
            while (allHandles.any { it.isAlive } && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(PROCESS_TREE_POLL_MS)
                } catch (_: InterruptedException) {
                    // Coroutine cancellation cannot abandon process cleanup once it has begun.
                    Thread.interrupted()
                }
            }
            allHandles.filter { it.isAlive }.forEach { handle ->
                runCatching { handle.destroyForcibly() }
            }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
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
        }
        val generated = File(root, relative).canonicalFile
        if (!generated.isFile || !generated.toPath().startsWith(root.toPath())) return null
        return GENERATED_CONTENT_HASH.find(generated.readText())?.groupValues?.get(1)
    }

    private companion object {
        const val GENERATION_DIAGNOSTIC_LINE_LIMIT = 24
        const val GENERATION_DIAGNOSTIC_CHARACTER_LIMIT = 4_000
        const val MAX_MONITOR_OUTPUT_CHARS = 64 * 1024
        const val PROCESS_TREE_KILL_GRACE_MS = 2_000L
        const val PROCESS_TREE_POLL_MS = 10L
        val GENERATED_CONTENT_HASH = Regex("CONTENT_SHA256:\\s*String\\s*=\\s*\"([0-9a-fA-F]{64})\"")
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
