package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import com.areslib.simulation.SimulationProductId
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class SimulatorProcessState(
    val running: Boolean = false,
    val projectPath: String? = null,
    val league: League? = null,
)

/** Owns exactly one FTC or FRC desktop simulator child process. */
class SimulatorProcessService internal constructor(
    aresRepositoryUri: String?,
    aresVersion: String? = null,
    gradleJavaInstallations: List<File> = ManagedToolchainPaths.gradleJavaInstallations(),
) {
    constructor() : this(
        aresRepositoryUri = System.getProperty(ARES_REPOSITORY_URI_PROPERTY),
        aresVersion = System.getProperty(ARES_VERSION_PROPERTY),
    )

    private val commandFactory = ProjectProcessCommandFactory(
        aresRepositoryUri = aresRepositoryUri,
        aresVersion = aresVersion,
        gradleJavaInstallations = gradleJavaInstallations,
    )
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val shuttingDown = AtomicBoolean(false)
    private val _state = MutableStateFlow(SimulatorProcessState())
    val state: StateFlow<SimulatorProcessState> = _state.asStateFlow()
    private val _output = MutableSharedFlow<String>(
        replay = 200,
        extraBufferCapacity = 800,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val output: SharedFlow<String> = _output.asSharedFlow()

    private var process: Process? = null
    private var job: Job? = null

    fun start(projectPath: String, product: SimulationProductId, simulatorCommand: String? = null) {
        if (shuttingDown.get()) return
        val league = product.league
        val projectRoot = runCatching { requireProjectRoot(projectPath) }.getOrElse { error ->
            scope.launch {
                _output.emit("[SYSTEM] Simulation could not start: ${error.message ?: "choose a valid robot project"}")
            }
            return
        }
        runCatching { commandFactory.requireProjectDependenciesCompatible(projectRoot) }.onFailure { error ->
            scope.launch {
                _output.emit("[SYSTEM] Simulation could not start: ${error.message ?: "incompatible ARES project"}")
            }
            return
        }
        stop()

        val replacement = scope.launch(start = CoroutineStart.LAZY) {
            var ownedProcess: Process? = null
            try {
                _state.value = SimulatorProcessState(true, projectRoot.path, league)
                val isWindows = System.getProperty("os.name").contains("win", ignoreCase = true)
                val userCommand = simulatorCommand?.takeIf(String::isNotBlank)
                val fatJar = File(projectRoot, "simulator/build/libs/simulator-all.jar")
                val simulationJavaHome = if (league == League.FRC) {
                    ManagedToolchainPaths.resolveFrcSimulationJavaHome()
                } else {
                    ManagedToolchainPaths.resolveJavaHome()
                }
                val javaExecutable = simulationJavaHome
                    ?.let { File(it, "bin/${if (isWindows) "java.exe" else "java"}") }
                    ?.takeIf(File::isFile)
                    ?.path
                    ?: File(
                        System.getProperty("java.home"),
                        "bin/${if (isWindows) "java.exe" else "java"}",
                    ).path
                val command = when {
                    userCommand != null && isWindows -> listOf("cmd.exe", "/d", "/s", "/c", userCommand)
                    userCommand != null -> listOf("sh", "-c", userCommand)
                    product == SimulationProductId.FTC_DESKTOP_OPMODE && fatJar.isFile ->
                        listOf(javaExecutable, "-jar", fatJar.absolutePath)
                    else -> commandFactory.simulation(isWindows, product)
                }

                _output.emit("[SYSTEM] Starting Simulation: ${command.joinToString(" ")}")
                val builder = commandFactory.configureEnvironment(
                    ProcessBuilder(command).directory(projectRoot).redirectErrorStream(true),
                )
                if (league == League.FRC && simulationJavaHome != null) {
                    ManagedToolchainPaths.configureJavaEnvironment(builder, simulationJavaHome)
                    _output.emit("[SYSTEM] FRC simulator Java: ${simulationJavaHome.path}")
                }
                val child = builder.start()
                ownedProcess = child
                process = child
                currentCoroutineContext().ensureActive()
                child.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        currentCoroutineContext().ensureActive()
                        _output.emit(line)
                    }
                }
                currentCoroutineContext().ensureActive()
                val exitCode = runInterruptible(Dispatchers.IO) { child.waitFor() }
                _output.emit("[SYSTEM] Simulation finished with exit code $exitCode")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _output.emit("[SYSTEM] Error running simulation: ${error.message}")
            } finally {
                ownedProcess?.let { if (it.isAlive) terminateProcessTree(it) }
                if (process === ownedProcess) process = null
                _state.value = SimulatorProcessState()
            }
        }
        job = replacement
        replacement.start()
    }

    fun stop() {
        runBlocking { stopAndJoin() }
    }

    fun shutdown() {
        runBlocking { shutdownAndJoin() }
    }

    internal suspend fun shutdownAndJoin() = withContext(NonCancellable) {
        if (!shuttingDown.compareAndSet(false, true)) return@withContext
        stopAndJoin()
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    internal suspend fun stopAndJoin() = withContext(NonCancellable) {
        val ownedProcess = process
        val ownedJob = job
        ownedJob?.cancel()
        ownedProcess?.let { terminateProcessTree(it) }
        ownedJob?.cancelAndJoin()
        if (process === ownedProcess) process = null
        if (job === ownedJob) job = null
        _state.value = SimulatorProcessState()
    }

    private fun requireProjectRoot(path: String): File {
        require(path.isNotBlank()) { "Choose a robot project directory first" }
        return File(path).canonicalFile.also { require(it.isDirectory) { "The selected project directory does not exist" } }
    }
}

internal val SimulationProductId.league: League
    get() = when (this) {
        SimulationProductId.FTC_DESKTOP_OPMODE -> League.FTC
        SimulationProductId.FRC_WPILIB_DESKTOP -> League.FRC
    }
