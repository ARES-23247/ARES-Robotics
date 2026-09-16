package com.ares.analytics.service

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal data class AutoImportProcessExecution(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
) {
    val succeeded: Boolean get() = !timedOut && exitCode == 0
}

internal class AutoImportProcessRunner(
    private val maxProcessOutputBytes: Int = 64 * 1024,
    private val processKillGraceMs: Long = 1_000L,
    val listProcessTimeoutMs: Long = 10_000L,
    val transferProcessTimeoutMs: Long = 60_000L,
    private val pingProcessTimeoutMs: Long = 2_000L,
    private val adbProbeTimeoutMs: Long = 2_000L,
) {
    suspend fun executeProcess(
        processBuilder: ProcessBuilder,
        timeoutMs: Long,
    ): AutoImportProcessExecution = coroutineScope {
        require(timeoutMs > 0L) { "Process timeout must be positive" }
        val process = withContext(NonCancellable + Dispatchers.IO) { processBuilder.start() }
        runCatching { process.outputStream.close() }
        val stdoutReader = async(Dispatchers.IO) { readBounded(process.inputStream, maxProcessOutputBytes) }
        val stderrReader = async(Dispatchers.IO) { readBounded(process.errorStream, maxProcessOutputBytes) }
        var timedOut = false

        try {
            val completed = withTimeoutOrNull(timeoutMs) {
                runInterruptible(Dispatchers.IO) { process.waitFor() }
                true
            } == true
            if (!completed) {
                timedOut = true
                terminateProcess(process)
            }

            val stdout = stdoutReader.await()
            val stderr = stderrReader.await()
            val exitCode = runCatching { process.exitValue() }.getOrNull()
            AutoImportProcessExecution(exitCode, stdout, stderr, timedOut)
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { terminateProcess(process) }
            throw cancellation
        } finally {
            if (process.isAlive) {
                withContext(NonCancellable) { terminateProcess(process) }
            }
        }
    }

    suspend fun runProcessOrNull(
        processBuilder: ProcessBuilder,
        timeoutMs: Long,
    ): AutoImportProcessExecution? = try {
        executeProcess(processBuilder, timeoutMs)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    suspend fun readSnapshotFromProcess(processBuilder: ProcessBuilder): AutoImportService.SourceSnapshot? {
        val result = runProcessOrNull(processBuilder, listProcessTimeoutMs) ?: return null
        if (!result.succeeded) return null
        val parts = result.stdout.lineSequence().firstOrNull()?.trim()?.split(':') ?: return null
        return if (parts.size != 2) {
            null
        } else {
            runCatching { AutoImportService.SourceSnapshot(parts[0].toLong(), parts[1].toLong()) }.getOrNull()
        }
    }

    private suspend fun terminateProcess(process: Process) {
        withContext(NonCancellable + Dispatchers.IO) {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.waitFor(processKillGraceMs, TimeUnit.MILLISECONDS) }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    private fun readBounded(input: InputStream, maximumBytes: Int): String {
        val retained = ByteArrayOutputStream(minOf(maximumBytes, 8_192))
        val buffer = ByteArray(8_192)
        try {
            input.use { stream ->
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    val remaining = maximumBytes - retained.size()
                    if (remaining > 0) retained.write(buffer, 0, minOf(count, remaining))
                }
            }
        } catch (_: java.io.IOException) {
            // Process termination closes streams to unblock readers; retain the prefix read so far.
        }
        return retained.toString(Charsets.UTF_8.name())
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun sshOptions(connectTimeoutSeconds: Int): List<String> = listOf(
        "-o", "StrictHostKeyChecking=yes",
        "-o", "ConnectTimeout=$connectTimeoutSeconds",
        "-o", "BatchMode=yes"
    )

    suspend fun isHostReachable(host: String): Boolean {
        val processBuilder = ProcessBuilder(pingCommand(host, System.getProperty("os.name")))
        return runProcessOrNull(processBuilder, pingProcessTimeoutMs)?.succeeded == true
    }

    suspend fun findAdbPath(): String {
        try {
            val result = executeProcess(ProcessBuilder("adb", "--version"), adbProbeTimeoutMs)
            if (result.succeeded) {
                return "adb"
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Ignore and fall through to fixed SDK paths.
        }
        val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (!androidHome.isNullOrEmpty()) {
            val exe = if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
                File(androidHome, "platform-tools/adb.exe")
            } else {
                File(androidHome, "platform-tools/adb")
            }
            if (exe.exists() && exe.canExecute()) {
                return exe.absolutePath
            }
        }
        val userHome = System.getProperty("user.home")
        val defaultPaths = listOf(
            File(userHome, "AppData/Local/Android/Sdk/platform-tools/adb.exe"),
            File(userHome, "Library/Android/sdk/platform-tools/adb"),
            File("/usr/bin/adb"),
            File("/usr/local/bin/adb")
        )
        for (file in defaultPaths) {
            if (file.exists() && file.canExecute()) {
                return file.absolutePath
            }
        }

        return "adb"
    }

    fun getDefaultFrcHost(teamId: String): String {
        val teamNumber = teamId.filter(Char::isDigit).toIntOrNull()
        return if (teamNumber != null && teamNumber in 1..25_599) {
            val te = teamNumber / 100
            val am = teamNumber % 100
            "10.$te.$am.2"
        } else {
            "10.0.0.2"
        }
    }
}

internal fun pingCommand(host: String, osName: String): List<String> = when {
    osName.contains("win", ignoreCase = true) -> listOf("ping", "-n", "1", "-w", "1000", host)
    osName.contains("mac", ignoreCase = true) -> listOf("ping", "-c", "1", "-W", "1000", host)
    else -> listOf("ping", "-c", "1", "-W", "1", host)
}
