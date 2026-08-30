package com.ares.analytics.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Drains output concurrently so a verbose child cannot fill its pipe before the timeout. */
internal suspend fun awaitProcessExit(
    process: Process,
    timeoutSeconds: Long,
    onLine: suspend (String) -> Unit,
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

/** Terminates a child process and every descendant before returning. */
internal suspend fun terminateProcessTree(process: Process) {
    withContext(NonCancellable + Dispatchers.IO) {
        val descendants = mutableListOf<ProcessHandle>()
        runCatching {
            process.descendants().use { handles -> handles.forEach(descendants::add) }
        }
        descendants.asReversed().forEach { child -> runCatching { child.destroyForcibly() } }
        runCatching { process.destroyForcibly() }

        val allHandles = descendants + process.toHandle()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_TREE_KILL_GRACE_MS)
        while (allHandles.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(PROCESS_TREE_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.interrupted()
            }
        }
        allHandles.filter(ProcessHandle::isAlive).forEach { handle ->
            runCatching { handle.destroyForcibly() }
        }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }
}

private const val PROCESS_TREE_KILL_GRACE_MS = 2_000L
private const val PROCESS_TREE_POLL_MS = 10L
