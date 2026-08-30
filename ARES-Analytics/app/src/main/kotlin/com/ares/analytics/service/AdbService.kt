package com.ares.analytics.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Owns Android Debug Bridge discovery and logcat lifecycle. */
class AdbService internal constructor(
    monitorConnection: Boolean,
    private val connectionPollMs: Long = 5_000L,
    private val adbPath: () -> String = ::resolveAdbExecutable,
    private val startProcess: (List<String>) -> Process = { command ->
        ProcessBuilder(command).redirectErrorStream(true).start()
    },
) {
    constructor() : this(monitorConnection = true)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val shuttingDown = AtomicBoolean(false)
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _logcatOutput = MutableSharedFlow<String>(
        replay = 200,
        extraBufferCapacity = 800,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val logcatOutput: SharedFlow<String> = _logcatOutput.asSharedFlow()

    private var monitorJob: Job? = null
    private var logcatJob: Job? = null
    private var logcatProcess: Process? = null

    init {
        if (monitorConnection) startConnectionMonitor()
    }

    fun startLogcat() {
        if (shuttingDown.get()) return
        stopLogcat()
        val replacement = scope.launch(start = CoroutineStart.LAZY) {
            var ownedProcess: Process? = null
            try {
                _logcatOutput.emit("[SYSTEM] Starting ADB logcat stream...")
                val process = startProcess(listOf(adbPath(), "logcat", "-v", "time"))
                ownedProcess = process
                logcatProcess = process
                currentCoroutineContext().ensureActive()
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        currentCoroutineContext().ensureActive()
                        _logcatOutput.emit(line)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _logcatOutput.emit("[SYSTEM] Error streaming logcat: ${error.message}")
            } finally {
                ownedProcess?.let { if (it.isAlive) terminateProcessTree(it) }
                if (logcatProcess === ownedProcess) logcatProcess = null
            }
        }
        logcatJob = replacement
        replacement.start()
    }

    fun stopLogcat() {
        runBlocking { stopLogcatAndJoin() }
    }

    fun shutdown() {
        runBlocking { shutdownAndJoin() }
    }

    internal suspend fun shutdownAndJoin() = withContext(NonCancellable) {
        if (!shuttingDown.compareAndSet(false, true)) return@withContext
        stopLogcatAndJoin()
        monitorJob?.cancelAndJoin()
        monitorJob = null
        scope.coroutineContext[Job]?.cancelAndJoin()
        _connected.value = false
    }

    private fun startConnectionMonitor() {
        monitorJob = scope.launch {
            while (isActive) {
                _connected.value = runCatching {
                    val output = StringBuilder()
                    val process = startProcess(listOf(adbPath(), "devices"))
                    val exitCode = awaitProcessExit(process, 3) { line ->
                        if (output.length < MAX_MONITOR_OUTPUT_CHARS) output.appendLine(line)
                    }
                    exitCode == 0 && output.lineSequence().any { line ->
                        line.endsWith("\tdevice") || line.contains("192.168.43.1:5555")
                    }
                }.getOrDefault(false)
                delay(connectionPollMs)
            }
        }
    }

    private suspend fun stopLogcatAndJoin() = withContext(NonCancellable) {
        val process = logcatProcess
        val job = logcatJob
        job?.cancel()
        process?.let { terminateProcessTree(it) }
        job?.cancelAndJoin()
        if (logcatProcess === process) logcatProcess = null
        if (logcatJob === job) logcatJob = null
    }

    private companion object {
        const val MAX_MONITOR_OUTPUT_CHARS = 64 * 1024
    }
}

internal fun resolveAdbExecutable(): String {
    ManagedToolchainPaths.resolveAndroidSdk()?.let { sdk ->
        val executable = File(
            sdk,
            "platform-tools/${if (System.getProperty("os.name").contains("win", true)) "adb.exe" else "adb"}",
        )
        if (executable.isFile) return executable.absolutePath
    }
    val windowsAdb = File(System.getenv("LOCALAPPDATA") ?: "", "Android/Sdk/platform-tools/adb.exe")
    if (windowsAdb.isFile) return windowsAdb.absolutePath
    val macAdb = File(System.getProperty("user.home"), "Library/Android/sdk/platform-tools/adb")
    if (macAdb.isFile) return macAdb.absolutePath
    return "adb"
}
