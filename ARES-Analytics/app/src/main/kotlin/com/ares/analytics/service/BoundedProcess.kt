package com.ares.analytics.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal data class BoundedProcessResult(val exitCode: Int, val output: String)

/** No pipe read can block the deadline. Output is drained by the OS into an owned temporary file. */
internal suspend fun runBoundedProcess(builder: ProcessBuilder, timeoutMs: Long): BoundedProcessResult? =
    withContext(Dispatchers.IO) {
        require(timeoutMs > 0)
        val output = Files.createTempFile("ares-process-", ".log").toFile()
        var process: Process? = null
        try {
            currentCoroutineContext().ensureActive()
            val child = builder.redirectErrorStream(true).redirectOutput(output).start()
            process = child
            val started = System.nanoTime()
            while (child.isAlive) {
                currentCoroutineContext().ensureActive()
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= timeoutMs) {
                    return@withContext null
                }
                delay(25)
            }
            val tail = RandomAccessFile(output, "r").use { file ->
                val count = minOf(file.length(), 4096L).toInt()
                file.seek(file.length() - count)
                ByteArray(count).also { file.readFully(it) }.toString(Charsets.UTF_8)
            }
            BoundedProcessResult(child.exitValue(), tail)
        } finally {
            process?.let { child ->
                if (child.isAlive) child.destroyForcibly()
                child.waitFor(1, TimeUnit.SECONDS)
                child.inputStream.close()
                child.errorStream.close()
                child.outputStream.close()
            }
            output.delete()
        }
    }
