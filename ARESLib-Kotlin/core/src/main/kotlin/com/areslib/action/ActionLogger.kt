package com.areslib.action

import com.areslib.util.RobotClock
import com.google.gson.GsonBuilder
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe asynchronous JSONL recorder for [RobotAction] streams.
 *
 * Every action is converted to a detached JSON tree before it crosses the writer-thread boundary.
 * This snapshots pooled [com.areslib.state.VisionMeasurement] objects, mutable pose/joystick
 * actions, mutable path points, arrays, lists, and season payloads at the instant [logAction]
 * accepts them. Producer reuse after dispatch therefore cannot rewrite recorded history.
 *
 * Active files end in `.jsonl.active`. Their queued records drain before a mode transition or
 * [stop] exposes the completed file. Names contain both the run id and mode. Creation uses `CREATE_NEW` plus a numeric suffix
 * so equal clock values and repeated run ids never truncate an earlier run; finalization never
 * deletes or replaces an existing completed log.
 * A write, flush, close, or finalization failure stops this recorder and leaves the affected
 * file active. Create a new recorder to resume logging; incomplete files are never promoted.
 */
class ActionLogger(
    val runId: String = "",
    val robotId: String = "",
    val matchNumber: Int = 0,
    val alliance: String = "BLUE",
    mode: String = "Init",
    private val logDirectory: File? = null
) {
    // Preserve explicit null members in the detached tree through the final writer boundary.
    private val gson = GsonBuilder().serializeNulls().create()
    @Volatile private var requestedMode = mode
    /** Latest producer mode; each accepted action keeps its own enqueue-time mode. */
    val mode: String get() = requestedMode
    private var writerMode = mode
    private class PendingAction(var action: ActionReplay.EncodedAction? = null, var mode: String = "")
    private val queue = LinkedBlockingQueue<PendingAction>(QUEUE_CAPACITY)
    private val actionPool = LinkedBlockingQueue<PendingAction>().apply { repeat(16) { offer(PendingAction()) } }
    private var writer: BufferedWriter? = null
    private var activeLogFile: File? = null
    private var completedLogFile: File? = null
    private var fileActionCount = 0L
    @Volatile private var isRunning = false
    private val queueStateLock = Any()
    private val workerDone = CountDownLatch(1)
    private val droppedActions = AtomicLong(0L)

    /** Test-only scheduling seam used to prove enqueue-time ownership under a blocked writer. */
    @Volatile
    internal var beforeWriteForTest: (() -> Unit)? = null

    /**
     * Actions rejected or not committed to a completed file. On a file failure, this conservatively
     * includes all its records, even if some bytes remain recoverable in the active file.
     */
    val droppedActionCount: Long
        get() = droppedActions.get()

    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        { thread -> Thread(thread, "ARES-ActionLogger-Thread").apply { isDaemon = true } }
    )

    init {
        try {
            openWriter(writerMode)
            isRunning = true
            startLoggingLoop()
        } catch (e: Exception) {
            System.err.println("ActionLogger: Failed to initialize! ${e.message}")
            isRunning = false
            closeWriter()
            workerDone.countDown()
            executor.shutdown()
        }
    }

    private fun openWriter(fileMode: String) {
        val javaVendor = System.getProperty("java.vendor") ?: ""
        val isAndroid = javaVendor.contains("Android", ignoreCase = true) || File("/sdcard").exists()
        val logDir = logDirectory ?: if (isAndroid) File("/sdcard/FIRST/telemetry_logs/") else File("./logs/")
        Files.createDirectories(logDir.toPath())
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS",Locale.getDefault())
            .format(Date(RobotClock.currentTimeMillis()))
        val safeRunId = sanitize(runId,"no-run-id")
        val safeMode = sanitize(fileMode,"Unknown")
        val reservation = reserveUniqueFile(logDir,"action_log_${timestamp}_${safeRunId}_${safeMode}")
        activeLogFile = reservation.active
        completedLogFile = reservation.completed
        writer = reservation.writer
        writerMode = fileMode
    }

    /** Selects the mode for subsequent actions without waiting for file IO. */
    fun beginMode(mode: String) {
        synchronized(queueStateLock) { if (isRunning) requestedMode = mode }
    }

    /**
     * Snapshots and enqueues [action]. Disk I/O remains on the background worker; queue insertion
     * never blocks. Encoding failures and full/shutdown queues increment [droppedActionCount].
     */
    fun logAction(action: RobotAction) = logAction(action, mode)

    /** Atomically captures explicit mode and action data before queueing either to the writer. */
    fun logAction(action: RobotAction, mode: String) {
        synchronized(queueStateLock) {
            if (!isRunning) {
                droppedActions.incrementAndGet()
                return
            }
            requestedMode = mode
            val snapshot = try {
                ActionReplay.encodeForLog(action)
            } catch (e: Exception) {
                droppedActions.incrementAndGet()
                System.err.println("ActionLogger: Failed to snapshot ${action.javaClass.name}: ${e.message}")
                return
            }
            val pending = (actionPool.poll() ?: PendingAction()).apply { this.action = snapshot; this.mode = mode }
            if (!queue.offer(pending)) {
                recyclePending(pending)
                droppedActions.incrementAndGet()
            }
        }
    }

    private fun startLoggingLoop() {
        executor.submit {
            var wasInterrupted = false
            var fileFailed = false
            try {
                while (isRunning || queue.isNotEmpty()) {
                    val pending = try {
                        queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    } catch (_: InterruptedException) {
                        wasInterrupted = true
                        continue
                    }
                    var written = false
                    try {
                        beforeWriteForTest?.invoke()
                        if (writerMode != pending.mode || writer == null) {
                            if (!finishFile()) throw IOException("Previous action log could not be finalized")
                            openWriter(pending.mode)
                        }
                        writeAction(checkNotNull(pending.action),pending.mode)
                        fileActionCount++
                        written = true
                    } catch (failure: Throwable) {
                        fileFailed = true
                        if (failure is InterruptedException) wasInterrupted = true
                        synchronized(queueStateLock) { isRunning = false }
                        System.err.println("ActionLogger: Recording stopped: ${failure.message}")
                        break
                    } finally {
                        if (!written) droppedActions.incrementAndGet()
                        recyclePending(pending)
                    }
                }
            } finally {
                synchronized(queueStateLock) { isRunning = false }
                try {
                    finishFile(fileFailed)
                } finally {
                    try {
                        while (true) {
                            val pending = queue.poll() ?: break
                            droppedActions.incrementAndGet()
                            recyclePending(pending)
                        }
                    } finally {
                        executor.shutdown()
                        workerDone.countDown()
                        if (wasInterrupted) Thread.currentThread().interrupt()
                    }
                }
            }
        }
    }

    private fun recyclePending(pending: PendingAction) {
        pending.action = null
        pending.mode = ""
        actionPool.offer(pending)
    }

    private fun writeAction(action: ActionReplay.EncodedAction, fileMode: String) {
        val output = writer ?: throw IOException("Action log writer is closed")
        output.write("{\"schema_version\":")
        output.write(ActionReplay.SCHEMA_VERSION.toString())
        output.write(",\"run_id\":")
        output.write(gson.toJson(runId))
        output.write(",\"robot_id\":")
        output.write(gson.toJson(robotId))
        output.write(",\"match_number\":")
        output.write(matchNumber.toString())
        output.write(",\"alliance\":")
        output.write(gson.toJson(alliance))
        output.write(",\"op_mode\":")
        output.write(gson.toJson(fileMode))
        output.write(",\"type\":")
        output.write(gson.toJson(action.type))
        output.write(",\"payload\":")
        gson.toJson(action.payload, output)
        output.write("}")
        output.newLine()
    }

    private fun closeWriter(): Boolean {
        val output = writer ?: return true
        writer = null
        var success = true
        try {
            output.flush()
        } catch (failure: Throwable) {
            success = false
            System.err.println("ActionLogger: Failed to flush: ${failure.message}")
        } finally {
            try {
                output.close()
            } catch (failure: Throwable) {
                success = false
                System.err.println("ActionLogger: Failed to close: ${failure.message}")
            }
        }
        return success
    }

    private fun finishFile(writeFailed: Boolean = false): Boolean {
        val closed = closeWriter()
        val complete = !writeFailed && closed && finalizeLogFile()
        if (!complete) droppedActions.addAndGet(fileActionCount)
        fileActionCount = 0L
        return complete
    }

    private fun finalizeLogFile(): Boolean {
        val active = activeLogFile ?: return true
        val completed = completedLogFile ?: return false
        try {
            // No REPLACE_EXISTING: an unexpected collision remains visible as an active file and
            // can never destroy the completed run already present at this name.
            Files.move(active.toPath(), completed.toPath())
            activeLogFile = null
            return true
        } catch (e: Exception) {
            System.err.println(
                "ActionLogger: Could not finalize ${active.absolutePath} without replacing " +
                    "${completed.absolutePath}: ${e.message}"
            )
            return false
        }
    }

    /** Drains accepted actions; only a successfully written and closed file becomes completed. */
    fun stop() {
        synchronized(queueStateLock) {
            isRunning = false
        }
        executor.shutdown()
        var wasInterrupted = false
        while (workerDone.count > 0L) {
            try {
                workerDone.await()
            } catch (_: InterruptedException) {
                wasInterrupted = true
            }
        }
        if (wasInterrupted) Thread.currentThread().interrupt()
    }

    private data class FileReservation(
        val active: File,
        val completed: File,
        val writer: BufferedWriter
    )

    private fun reserveUniqueFile(logDir: File, baseName: String): FileReservation {
        for (collisionIndex in 0 until MAX_COLLISION_ATTEMPTS) {
            val suffix = if (collisionIndex == 0) "" else "_$collisionIndex"
            val completed = File(logDir, "$baseName$suffix.jsonl")
            if (completed.exists()) continue
            val active = File(logDir, "${completed.name}.active")
            try {
                val output = Files.newBufferedWriter(
                    active.toPath(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                )
                // Close the narrow race where an older active file was finalized between the
                // completed-file check and this CREATE_NEW reservation.
                if (completed.exists()) {
                    output.close()
                    Files.deleteIfExists(active.toPath())
                    continue
                }
                return FileReservation(active, completed, output)
            } catch (_: FileAlreadyExistsException) {
                continue
            }
        }
        throw IOException("Could not reserve a unique action log name for '$baseName'")
    }

    private fun sanitize(value: String, fallback: String): String {
        val sanitized = buildString(minOf(value.length, MAX_FILENAME_SEGMENT_LENGTH)) {
            for (character in value) {
                if (length == MAX_FILENAME_SEGMENT_LENGTH) break
                append(
                    if (character.isLetterOrDigit() || character == '-' || character == '_') {
                        character
                    } else {
                        '_'
                    }
                )
            }
        }
        return sanitized.ifBlank { fallback }
    }

    private companion object {
        const val QUEUE_CAPACITY = 1000
        const val MAX_COLLISION_ATTEMPTS = 10_000
        const val MAX_FILENAME_SEGMENT_LENGTH = 64
    }
}
