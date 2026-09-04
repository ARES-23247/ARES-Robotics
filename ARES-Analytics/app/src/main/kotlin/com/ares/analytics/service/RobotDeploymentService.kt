package com.ares.analytics.service

import com.ares.analytics.service.project.templateDeploymentBlockReason
import com.ares.analytics.shared.models.League
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

/** Owns robot deployment commands, device targeting, child processes, and deployment evidence. */
class RobotDeploymentService internal constructor(
    aresRepositoryUri: String?,
    aresVersion: String? = null,
    gradleJavaInstallations: List<File> = ManagedToolchainPaths.gradleJavaInstallations(),
    private val operationGate: ProjectProcessGate = ProjectProcessGate(),
    private val adbPath: () -> String = ::resolveAdbExecutable,
) {
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lifecycleMutex = Mutex()
    private val ownershipLock = Any()
    private val requestId = AtomicLong(0L)
    private val shuttingDown = AtomicBoolean(false)

    private val _state = MutableStateFlow(DeployExecutionState())
    val state: StateFlow<DeployExecutionState> = _state.asStateFlow()

    private val _output = MutableSharedFlow<String>(
        replay = 200,
        extraBufferCapacity = 800,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val output: SharedFlow<String> = _output.asSharedFlow()

    @Volatile
    private var activeRequestId = 0L

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var activeProcess: Process? = null

    fun deploy(projectPath: String, league: League) {
        if (shuttingDown.get()) return
        val nextRequestId = requestId.incrementAndGet()
        scope.launch {
            lifecycleMutex.withLock {
                if (shuttingDown.get() || nextRequestId != requestId.get()) return@withLock
                stopActiveLocked()
                if (shuttingDown.get() || nextRequestId != requestId.get()) return@withLock

                val replacement = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        operationGate.runExclusive { execute(nextRequestId, projectPath, league) }
                    } finally {
                        releaseOwnership(nextRequestId)
                    }
                }
                synchronized(ownershipLock) {
                    activeRequestId = nextRequestId
                    activeJob = replacement
                    activeProcess = null
                }
                replacement.start()
            }
        }
    }

    private suspend fun execute(generation: Long, projectPath: String, league: League) {
        val isWindows = System.getProperty("os.name").contains("win", true)
        var canonicalProjectPath = projectPath
        updateIfOwner(
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
            commandFactory.requireProjectDependenciesCompatible(root)
            templateDeploymentBlockReason(root)?.let { reason -> error(reason) }
            when (league) {
                League.FTC -> deployFtc(generation, root, isWindows)
                League.FRC -> deployFrc(generation, root, isWindows)
                League.XRP -> deployXrp(generation, root, isWindows)
            }
        } catch (cancelled: CancellationException) {
            updateIfOwner(
                generation,
                DeployExecutionState(
                    phase = DeployExecutionPhase.CANCELED,
                    projectPath = canonicalProjectPath,
                    league = league,
                    message = "Deploy operation was canceled.",
                    requestId = generation,
                ),
            )
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            updateIfOwner(
                generation,
                DeployExecutionState(
                    phase = DeployExecutionPhase.FAILED,
                    projectPath = canonicalProjectPath,
                    league = league,
                    message = "Deployment failed: ${error.message ?: "unknown error"}",
                    requestId = generation,
                ),
            )
            _output.emit("[DEPLOY] FAILED: ${error.message}")
        }
    }

    private suspend fun deployFtc(generation: Long, root: File, isWindows: Boolean) {
        val adb = adbPath()
        _output.emit("[DEPLOY] Connecting wireless ADB ($FTC_ADB_TARGET)...")
        val connectExit = runOwnedProcess(
            generation,
            ProcessBuilder(commandFactory.adbConnect(adb)).directory(root).redirectErrorStream(true),
        ) { line -> _output.emit("[ADB] $line") }
        check(connectExit == 0) { "ADB could not connect to $FTC_ADB_TARGET (exit $connectExit)" }

        val identityOutput = StringBuilder()
        val identityExit = runOwnedProcess(
            generation,
            ProcessBuilder(commandFactory.adbIdentity(adb)).directory(root).redirectErrorStream(true),
        ) { line ->
            identityOutput.appendLine(line)
            _output.emit("[ADB] $line")
        }
        check(identityExit == 0 && identityOutput.isNotBlank()) {
            "The selected Control Hub did not answer the identity check"
        }

        updateIfOwner(
            generation,
            DeployExecutionState(
                phase = DeployExecutionPhase.BUILDING,
                projectPath = root.path,
                league = League.FTC,
                message = "Generating, verifying, testing, and packaging the FTC robot app...",
                progressPercent = 0.4f,
                requestId = generation,
            ),
        )
        val buildExit = runOwnedProcess(
            generation,
            commandFactory.configureEnvironment(
                ProcessBuilder(commandFactory.ftcDeployBuild(isWindows)).directory(root).redirectErrorStream(true),
            ),
        ) { line -> _output.emit(line) }
        check(buildExit == 0) { "FTC compilation failed with exit code $buildExit" }

        updateIfOwner(
            generation,
            DeployExecutionState(
                phase = DeployExecutionPhase.INSTALLING,
                projectPath = root.path,
                league = League.FTC,
                message = "Installing APK on robot via ADB...",
                progressPercent = 0.8f,
                requestId = generation,
            ),
        )
        val apkFile = File(root, "TeamCode/build/outputs/apk/debug/TeamCode-debug.apk")
        val apkTarget = apkFile.takeIf(File::exists)?.absolutePath
            ?: "TeamCode/build/outputs/apk/debug/TeamCode-debug.apk"
        val installExit = runOwnedProcess(
            generation,
            ProcessBuilder(commandFactory.adbInstall(adb, apkTarget)).directory(root).redirectErrorStream(true),
        ) { line -> _output.emit("[INSTALL] $line") }
        check(installExit == 0) { "APK installation failed with exit code $installExit" }

        val packageOutput = StringBuilder()
        val packageCheckExit = runOwnedProcess(
            generation,
            ProcessBuilder(commandFactory.adbPackageCheck(adb)).directory(root).redirectErrorStream(true),
        ) { line ->
            packageOutput.appendLine(line)
            _output.emit("[VERIFY] $line")
        }
        check(packageCheckExit == 0 && packageOutput.contains("package:")) {
            "ADB did not confirm the installed FTC Robot Controller package"
        }

        updateIfOwner(
            generation,
            DeployExecutionState(
                phase = DeployExecutionPhase.SUCCEEDED,
                projectPath = root.path,
                league = League.FTC,
                message = "FTC Robot Controller package installed and verified on $FTC_ADB_TARGET. Select the intended OpMode in Driver Station before enabling.",
                progressPercent = 1.0f,
                requestId = generation,
            ),
        )
        _output.emit("[DEPLOY] SUCCESS: Package installed and verified. No OpMode was started or robot motion commanded.")
    }

    private suspend fun deployFrc(generation: Long, root: File, isWindows: Boolean) {
        updateIfOwner(
            generation,
            DeployExecutionState(
                phase = DeployExecutionPhase.BUILDING,
                projectPath = root.path,
                league = League.FRC,
                message = "Generating, verifying, testing, building, then deploying to the configured RoboRIO...",
                progressPercent = 0.5f,
                requestId = generation,
            ),
        )
        val deployExit = runOwnedProcess(
            generation,
            commandFactory.configureEnvironment(
                ProcessBuilder(commandFactory.frcDeployBuild(isWindows)).directory(root).redirectErrorStream(true),
            ),
        ) { line -> _output.emit(line) }
        check(deployExit == 0) { "FRC deploy failed with exit code $deployExit" }

        updateIfOwner(
            generation,
            DeployExecutionState(
                phase = DeployExecutionPhase.SUCCEEDED,
                projectPath = root.path,
                league = League.FRC,
                message = "FRC deploy completed for the RoboRIO configured by this project. No robot motion was commanded.",
                progressPercent = 1.0f,
                requestId = generation,
            ),
        )
    }

    private suspend fun deployXrp(generation: Long, root: File, isWindows: Boolean) {
        commandFactory.requireProjectWrapper(root, League.XRP, isWindows)
        updateIfOwner(
            generation,
            DeployExecutionState(
                phase = DeployExecutionPhase.BUILDING,
                projectPath = root.path,
                league = League.XRP,
                message = "Generating and verifying the XRP project before USB deployment...",
                progressPercent = 0.4f,
                requestId = generation,
            ),
        )
        val exit = runOwnedProcess(
            generation,
            ProcessBuilder(commandFactory.xrpDeployBuild(isWindows)).directory(root).redirectErrorStream(true),
        ) { line -> _output.emit(line) }
        check(exit == 0) { "XRP deploy failed with exit code $exit" }
        updateIfOwner(
            generation,
            DeployExecutionState(
                phase = DeployExecutionPhase.SUCCEEDED,
                projectPath = root.path,
                league = League.XRP,
                message = "XRP MicroPython project verified and copied to the connected controller. No robot motion was commanded.",
                progressPercent = 1.0f,
                requestId = generation,
            ),
        )
    }

    private suspend fun runOwnedProcess(
        generation: Long,
        processBuilder: ProcessBuilder,
        onLine: suspend (String) -> Unit,
    ): Int {
        var process: Process? = null
        try {
            val started = withContext(NonCancellable + Dispatchers.IO) { processBuilder.start() }
            process = started
            if (!claimProcess(generation, started)) {
                throw CancellationException("Deployment ownership changed before process registration")
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
                releaseProcess(generation, it)
            }
        }
    }

    private fun claimProcess(generation: Long, process: Process): Boolean = synchronized(ownershipLock) {
        if (activeRequestId != generation || activeJob?.isActive != true) {
            false
        } else {
            activeProcess = process
            true
        }
    }

    private fun releaseProcess(generation: Long, process: Process) {
        synchronized(ownershipLock) {
            if (activeRequestId == generation && activeProcess === process) activeProcess = null
        }
    }

    private fun updateIfOwner(generation: Long, nextState: DeployExecutionState) {
        synchronized(ownershipLock) {
            if (activeRequestId == generation) _state.value = nextState
        }
    }

    private fun releaseOwnership(generation: Long) {
        synchronized(ownershipLock) {
            if (activeRequestId == generation) {
                activeRequestId = 0L
                activeJob = null
                activeProcess = null
            }
        }
    }

    fun cancel() = runBlocking { cancelAndJoin() }

    internal suspend fun cancelAndJoin() {
        requestId.incrementAndGet()
        lifecycleMutex.withLock { stopActiveLocked() }
    }

    private suspend fun stopActiveLocked() = withContext(NonCancellable) {
        val owned = synchronized(ownershipLock) { Triple(activeRequestId, activeJob, activeProcess) }
        owned.second?.cancel()
        owned.third?.let { terminateProcessTree(it) }
        owned.second?.cancelAndJoin()
        synchronized(ownershipLock) {
            if (activeRequestId == owned.first && owned.first != 0L) {
                activeRequestId = 0L
                activeJob = null
                activeProcess = null
            }
        }
        if (_state.value.phase in setOf(
                DeployExecutionPhase.CONNECTING,
                DeployExecutionPhase.BUILDING,
                DeployExecutionPhase.INSTALLING,
            )
        ) {
            _state.value = _state.value.copy(
                phase = DeployExecutionPhase.CANCELED,
                message = "Deploy operation was canceled.",
                progressPercent = 0f,
            )
        }
    }

    fun shutdown() = runBlocking { shutdownAndJoin() }

    internal suspend fun shutdownAndJoin() = withContext(NonCancellable) {
        shuttingDown.set(true)
        requestId.incrementAndGet()
        lifecycleMutex.withLock { stopActiveLocked() }
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    private fun requireSafeProjectRoot(projectPath: String): File {
        require(projectPath.isNotBlank()) { "Choose a robot project directory first" }
        return File(projectPath).canonicalFile.also { root ->
            require(root.isDirectory) { "The selected project directory does not exist" }
        }
    }
}
