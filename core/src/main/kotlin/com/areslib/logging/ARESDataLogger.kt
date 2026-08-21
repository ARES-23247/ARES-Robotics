package com.areslib.logging

import java.io.BufferedWriter
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/** Point-in-time operational metrics for one asynchronous telemetry logger. */
internal data class ARESDataLoggerMetrics(
    val profile: LoggingProfile,
    val acceptedFrames: Long,
    val writtenFrames: Long,
    val droppedFrames: Long,
    val queueDepth: Int,
    val currentFileBytes: Long,
    val completedBytes: Long,
    val rotations: Long,
    val prunedFiles: Long,
    val prunedBytes: Long,
    val quarantinedFiles: Long,
    val activeFileName: String?
)

/**
 * Asynchronous CSV logger with a bounded producer queue and a single writer thread.
 *
 * [logFrame] never performs file IO, but it briefly takes the queue-state lock and may reject a
 * frame when the 1,000-entry queue is full or shutdown has begun. Rejections are observable through
 * [droppedFrameCount]. The first accepted frame fixes the stable CSV columns; keys first seen later
 * are preserved as JSON in the `_ExtraFieldsJson` column ([EXTRA_FIELDS_COLUMN]).
 *
 * A submitted map is owned by the logger until it is written or rejected and must not be mutated by
 * the caller. Any [HashMap] passed to [logFrame] is cleared and returned to the logger's pool, so use
 * [obtainMap] for pooled producer frames and do not retain that reference. While writing, the file
 * ends in `.csv.active` or `.csv.gz.active`; [stop] blocks until every accepted frame is drained
 * and atomically exposes the completed name. The selected [policy] controls compression, rotation,
 * and completed-log retention. Importers can therefore ignore active files instead of guessing
 * from temporary size stability.
 *
 * This class is thread-safe for producers and shutdown. It reduces control-loop IO latency; it does
 * not promise allocation-free logging when the pool is exhausted or when rows are serialized.
 */
class ARESDataLogger private constructor(
    val mode: String,
    private val logDirectory: File,
    internal val policy: LoggingPolicy,
    private val runId: String?,
    @Suppress("UNUSED_PARAMETER") internalMarker: Unit
) {

    constructor() : this(
        "Init",
        RobotLogEnvironment.logDirectory,
        RobotLogEnvironment.loggingPolicy(),
        null,
        Unit
    )

    /** Binary-compatible constructor retained for existing ARESLib consumers. */
    constructor(
        mode: String = "Init",
        logDirectory: File = RobotLogEnvironment.logDirectory
    ) : this(mode, logDirectory, RobotLogEnvironment.loggingPolicy(), null, Unit)

    /** Internal policy injection used by calibration and deterministic logging tests. */
    internal constructor(
        mode: String = "Init",
        logDirectory: File = RobotLogEnvironment.logDirectory,
        policy: LoggingPolicy,
        runId: String? = null
    ) : this(mode, logDirectory, policy, runId, Unit)

    private val retentionEnabled = RobotLogEnvironment.isRetentionEnabled()
    private val logQueue = LinkedBlockingQueue<Map<String, Any>>(1000)
    private val activeKeys = mutableListOf<String>()
    private val activeKeySet = HashSet<String>()
    private var sink: ReservedLog? = null
    private var isHeaderWritten = false
    @Volatile private var isRunning = false
    private val queueStateLock = Any()
    private val workerDone = CountDownLatch(1)
    private val droppedFrames = AtomicLong(0L)
    private val acceptedFrames = AtomicLong(0L)
    private val writtenFrames = AtomicLong(0L)
    private val completedBytes = AtomicLong(0L)
    private val rotations = AtomicLong(0L)
    private val prunedFiles = AtomicLong(0L)
    private val prunedBytes = AtomicLong(0L)
    private val quarantinedFiles = AtomicLong(0L)

    /** Number of frames rejected because the logger was stopped or its bounded queue was full. */
    val droppedFrameCount: Long
        get() = droppedFrames.get()

    /** Lock-free snapshot suitable for telemetry and health displays. */
    internal fun metricsSnapshot(): ARESDataLoggerMetrics {
        val current = sink
        return ARESDataLoggerMetrics(
            profile = policy.profile,
            acceptedFrames = acceptedFrames.get(),
            writtenFrames = writtenFrames.get(),
            droppedFrames = droppedFrames.get(),
            queueDepth = logQueue.size,
            currentFileBytes = current?.byteCounter?.count ?: 0L,
            completedBytes = completedBytes.get(),
            rotations = rotations.get(),
            prunedFiles = prunedFiles.get(),
            prunedBytes = prunedBytes.get(),
            quarantinedFiles = quarantinedFiles.get(),
            activeFileName = current?.active?.name
        )
    }

    // Reuse producer maps when available; obtainMap falls back to allocation under burst load.
    private val mapPool = LinkedBlockingQueue<HashMap<String, Any>>()

    // Single daemon worker preserves accepted-frame order without keeping the JVM alive by itself.
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        { thread -> Thread(thread, "ARES-DataLogger-Thread").apply { isDaemon = true } }
    )

    init {
        // Pre-populate the map pool with 16 instances
        for (i in 0 until 16) {
            mapPool.offer(HashMap())
        }

        try {
            if (!logDirectory.exists() && !logDirectory.mkdirs()) {
                throw java.io.IOException("Could not create log directory: ${logDirectory.absolutePath}")
            }

            val now = com.areslib.util.RobotClock.currentTimeMillis()
            val recovery = LogStorageGovernance.quarantineStaleActiveFiles(
                logDirectory,
                now,
                policy.staleActiveAfterMs
            )
            quarantinedFiles.addAndGet(recovery.quarantinedFiles.toLong())
            enforceRetentionIfEnabled()
            sink = reserveUniqueLog(now)
            isRunning = true
            startLoggingLoop()
        } catch (e: Exception) {
            System.err.println("ARESDataLogger: Failed to initialize log file! ${e.message}")
            isRunning = false
            workerDone.countDown()
        }
    }

    /**
     * Returns a cleared producer map. Ownership transfers back to the logger when passed to
     * [logFrame]; callers must not reuse or inspect it afterward.
     */
    fun obtainMap(): HashMap<String, Any> {
        return mapPool.poll() ?: HashMap()
    }

    /**
     * Clears and returns [map] to the pool. Do not recycle a map that is queued for writing.
     */
    fun recycleMap(map: HashMap<String, Any>) {
        map.clear()
        mapPool.offer(map)
    }

    /**
     * Attempts to enqueue [data] without waiting for queue capacity.
     *
     * Ownership transfers immediately, including on rejection. The frame is counted as dropped when
     * shutdown has started or the queue is full.
     */
    fun logFrame(data: Map<String, Any>) {
        synchronized(queueStateLock) {
            if (!isRunning) {
                droppedFrames.incrementAndGet()
                if (data is HashMap<String, Any>) {
                    recycleMap(data)
                }
                return
            }
            val accepted = logQueue.offer(data)
            if (!accepted) {
                droppedFrames.incrementAndGet()
                if (data is HashMap<String, Any>) {
                    recycleMap(data)
                }
            } else {
                acceptedFrames.incrementAndGet()
            }
        }
    }

    private fun startLoggingLoop() {
        executor.submit {
            var wasInterrupted = false
            try {
                while (isRunning || logQueue.isNotEmpty()) {
                    try {
                        val frame = logQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        writeFrame(frame)
                    } catch (_: InterruptedException) {
                        // Preserve all accepted frames even if shutdown races an interruption.
                        // Restore the flag after the queue has been drained and the writer closed.
                        wasInterrupted = true
                    } catch (e: Exception) {
                        System.err.println("ARESDataLogger: Error writing log frame: ${e.message}")
                    }
                }
            } finally {
                closeAndFinalizeCurrentLog()
                workerDone.countDown()
                if (wasInterrupted) Thread.currentThread().interrupt()
            }
        }
    }

    // Reused builders reduce steady-state row formatting churn.
    private val csvBuilder = StringBuilder(512)
    private val extraFieldsBuilder = StringBuilder(256)

    private fun StringBuilder.appendCsvField(value: CharSequence) {
        var needsQuotes = false
        for (i in 0 until value.length) {
            when (value[i]) {
                ',', '"', '\r', '\n' -> {
                    needsQuotes = true
                    break
                }
            }
        }
        if (!needsQuotes) {
            append(value)
            return
        }

        append('"')
        for (i in 0 until value.length) {
            val c = value[i]
            if (c == '"') append('"')
            append(c)
        }
        append('"')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        append("\\u")
                        append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        append(c)
                    }
                }
            }
        }
        append('"')
    }

    /**
     * Serializes fields that were not present when the stable CSV header was established.
     * A reserved JSON column preserves late-appearing data without changing column counts or
     * emitting a second header in the middle of the file.
     */
    private fun buildExtraFields(frame: Map<String, Any>): CharSequence {
        extraFieldsBuilder.setLength(0)
        extraFieldsBuilder.append('{')
        var first = true
        val lateKeys = frame.keys.filter { it !in activeKeySet }.sorted()
        for (key in lateKeys) {
            if (!first) extraFieldsBuilder.append(',')
            first = false
            extraFieldsBuilder.appendJsonString(key)
            extraFieldsBuilder.append(':')
            val value = frame[key]
            when (value) {
                is Double -> if (value.isFinite()) {
                    extraFieldsBuilder.append(value.toString())
                } else {
                    extraFieldsBuilder.appendJsonString(value.toString())
                }
                is Float -> if (value.isFinite()) {
                    extraFieldsBuilder.append(value.toString())
                } else {
                    extraFieldsBuilder.appendJsonString(value.toString())
                }
                is Number, is Boolean -> extraFieldsBuilder.append(value.toString())
                null -> extraFieldsBuilder.append("null")
                else -> extraFieldsBuilder.appendJsonString(value.toString())
            }
        }
        extraFieldsBuilder.append('}')
        return extraFieldsBuilder
    }

    private fun StringBuilder.appendDouble(d: Double, places: Int = 4) {
        if (d.isNaN()) { append("NaN"); return }
        if (d.isInfinite()) { append(if (d < 0) "-Infinity" else "Infinity"); return }
        var value = d
        if (value < 0) {
            append('-')
            value = -value
        }
        val intPart = value.toLong()
        append(intPart)
        val fracPart = value - intPart
        if (fracPart > 0.0) {
            append('.')
            var multiplier = 1L
            for (i in 0 until places) multiplier *= 10L
            var fracInt = (fracPart * multiplier + 0.5).toLong()
            if (fracInt >= multiplier) {
                // Rare rounding case
                fracInt = multiplier - 1
            }
            val digits = CharArray(places)
            for (i in places - 1 downTo 0) {
                digits[i] = ((fracInt % 10L) + 48L).toInt().toChar()
                fracInt /= 10L
            }
            append(digits)
        } else {
            append(".0")
        }
    }

    private fun writeFrame(frame: Map<String, Any>) {
        val currentSink = sink
        if (currentSink == null) {
            droppedFrames.incrementAndGet()
            if (frame is HashMap<String, Any>) recycleMap(frame)
            return
        }
        val w = currentSink.writer
        var rowWritten = false

        try {
            // 1. Write the CSV header on the first frame
            if (!isHeaderWritten) {
                activeKeys.clear()
                activeKeySet.clear()
                // Always place timestamp first for easier plotting
                activeKeys.add("TimestampMs")
                
                // Add all other keys alphabetically
                val sortedKeys = frame.keys.sorted()
                for (i in 0 until sortedKeys.size) {
                    val key = sortedKeys[i]
                    if (key != "TimestampMs" && key != EXTRA_FIELDS_COLUMN) {
                        activeKeys.add(key)
                    }
                }
                activeKeySet.addAll(activeKeys)

                try {
                    csvBuilder.setLength(0)
                    for (i in 0 until activeKeys.size) {
                        if (i > 0) csvBuilder.append(',')
                        csvBuilder.appendCsvField(activeKeys[i])
                    }
                    if (activeKeys.isNotEmpty()) csvBuilder.append(',')
                    csvBuilder.appendCsvField(EXTRA_FIELDS_COLUMN)
                    w.write(csvBuilder.toString())
                    w.newLine()
                    isHeaderWritten = true
                } catch (e: IOException) {
                    System.err.println("ARESDataLogger: Failed to write CSV header: ${e.message}")
                }
            }

            // 2. Write values corresponding to the configured headers
            try {
                csvBuilder.setLength(0)
                for (i in 0 until activeKeys.size) {
                    if (i > 0) csvBuilder.append(',')
                    val value = frame[activeKeys[i]]
                    if (value != null) {
                        if (value is Double) {
                            csvBuilder.appendDouble(value, 4)
                        } else {
                            csvBuilder.appendCsvField(value.toString())
                        }
                    }
                }
                if (activeKeys.isNotEmpty()) csvBuilder.append(',')
                csvBuilder.appendCsvField(buildExtraFields(frame))
                w.write(csvBuilder.toString())
                w.newLine()
                rowWritten = true
            } catch (e: IOException) {
                System.err.println("ARESDataLogger: Failed to write CSV row: ${e.message}")
            }
        } finally {
            if (rowWritten) writtenFrames.incrementAndGet()
            // HashMap inputs follow the ownership contract and return to this logger's pool.
            if (frame is HashMap<String, Any>) {
                recycleMap(frame)
            }
        }

        if (rowWritten && shouldRotate(currentSink)) {
            closeAndFinalizeCurrentLog()
            rotations.incrementAndGet()
            try {
                sink = reserveUniqueLog(com.areslib.util.RobotClock.currentTimeMillis())
                isHeaderWritten = false
                activeKeys.clear()
                activeKeySet.clear()
            } catch (failure: Exception) {
                System.err.println("ARESDataLogger: Failed to rotate log file: ${failure.message}")
                synchronized(queueStateLock) { isRunning = false }
            }
        }
    }

    private fun shouldRotate(current: ReservedLog): Boolean {
        val ageMs = com.areslib.util.RobotClock.currentTimeMillis() - current.startedAtMs
        return current.byteCounter.count >= policy.maxFileBytes || ageMs >= policy.maxFileDurationMs
    }

    private fun closeAndFinalizeCurrentLog() {
        val current = sink ?: return
        sink = null
        try {
            current.writer.flush()
            current.writer.close()
        } catch (e: IOException) {
            System.err.println("ARESDataLogger: Failed to close writer: ${e.message}")
        } finally {
            runCatching {
                if (current.lock.isValid) current.lock.release()
            }
            runCatching {
                if (current.channel.isOpen) current.channel.close()
            }
        }
        finalizeLogFile(current.active, current.completed)
        enforceRetentionIfEnabled()
    }

    private fun enforceRetentionIfEnabled() {
        if (retentionEnabled) {
            applyRetention(LogStorageGovernance.enforceRetention(logDirectory, policy))
        }
    }

    private fun finalizeLogFile(active: File, initialCompleted: File) {
        var completed = initialCompleted
        if (!active.exists()) return

        repeat(MAX_FILE_RESERVATION_ATTEMPTS) { attempt ->
            try {
                moveWithoutReplacement(active, completed)
                completedBytes.addAndGet(completed.length())
                return
            } catch (_: FileAlreadyExistsException) {
                completed = collisionTarget(completed, attempt)
            } catch (failure: IOException) {
                if (completed.exists()) {
                    completed = collisionTarget(completed, attempt)
                    return@repeat
                }
                System.err.println("ARESDataLogger: Could not finalize active log ${active.absolutePath}: ${failure.message}")
                return
            }
        }
        System.err.println("ARESDataLogger: Could not reserve a collision-free completed log for ${active.absolutePath}")
    }

    private fun reserveUniqueLog(nowMs: Long): ReservedLog {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.getDefault())
            .format(Date(nowMs))
        val safeMode = mode.map { character ->
            if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
        }.joinToString("").ifBlank { "Unknown" }
        val safeRunId = runId?.map { character ->
            if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
        }?.joinToString("")?.take(MAX_RUN_ID_FILENAME_LENGTH)?.ifBlank { null }
        val runSuffix = safeRunId?.let { "_run_$it" }.orEmpty()
        val extension = if (policy.compress) ".csv.gz" else ".csv"
        repeat(MAX_FILE_RESERVATION_ATTEMPTS) { attempt ->
            val suffix = if (attempt == 0) "" else "_${UUID.randomUUID()}"
            val completed = File(logDirectory, "ares_log_${timestamp}_${safeMode}${runSuffix}${suffix}$extension")
            if (completed.exists()) return@repeat
            val active = File(logDirectory, "${completed.name}.active")
            var channel: FileChannel? = null
            var lock: FileLock? = null
            try {
                channel = FileChannel.open(
                    active.toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                )
                lock = channel.lock()
                val byteCounter = CountingOutputStream(Channels.newOutputStream(channel))
                val output: OutputStream = if (policy.compress) {
                    FastGzipOutputStream(byteCounter)
                } else {
                    byteCounter
                }
                val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8), WRITER_BUFFER_BYTES)
                // A completed file could have appeared between the existence check and active-file
                // reservation. Do not let this logger later replace it; retry with a unique suffix.
                if (completed.exists()) {
                    writer.close()
                    Files.deleteIfExists(active.toPath())
                    return@repeat
                }
                return ReservedLog(
                    active = active,
                    completed = completed,
                    writer = writer,
                    byteCounter = byteCounter,
                    channel = channel,
                    lock = lock,
                    startedAtMs = nowMs
                )
            } catch (_: FileAlreadyExistsException) {
                // Another logger reserved the same millisecond/mode name. Try a UUID suffix.
                runCatching { lock?.release() }
                runCatching { channel?.close() }
            } catch (failure: Exception) {
                runCatching { lock?.release() }
                runCatching { channel?.close() }
                Files.deleteIfExists(active.toPath())
                throw failure
            }
        }
        throw IOException("Could not reserve a unique ARES telemetry log file")
    }

    private fun applyRetention(result: LogRetentionResult) {
        prunedFiles.addAndGet(result.deletedFiles.toLong())
        prunedBytes.addAndGet(result.deletedBytes)
    }

    private fun collisionTarget(original: File, attempt: Int): File {
        val extension = if (original.name.endsWith(".csv.gz", ignoreCase = true)) {
            ".csv.gz"
        } else {
            ".csv"
        }
        val baseName = original.name.removeSuffix(extension)
        return File(original.parentFile, "${baseName}_${attempt}_${UUID.randomUUID()}$extension")
    }

    private fun moveWithoutReplacement(active: File, completed: File) {
        // Do not request ATOMIC_MOVE here: the JDK permits providers to replace an existing target
        // when that option is used, even without REPLACE_EXISTING. A same-directory move without
        // replacement preserves every completed log and fails cleanly if another writer wins the
        // final-name race.
        Files.move(active.toPath(), completed.toPath())
    }

    /**
     * Stops accepting frames, waits for the queue to drain, flushes, and closes the writer.
     * Safe to call repeatedly. If interrupted while waiting, shutdown still completes and the
     * interrupt status is restored before return.
     */
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

    companion object {
        const val EXTRA_FIELDS_COLUMN = "_ExtraFieldsJson"
        private const val MAX_FILE_RESERVATION_ATTEMPTS = 32
        private const val MAX_RUN_ID_FILENAME_LENGTH = 64
        private const val WRITER_BUFFER_BYTES = 64 * 1024
    }

    private data class ReservedLog(
        val active: File,
        val completed: File,
        val writer: BufferedWriter,
        val byteCounter: CountingOutputStream,
        val channel: FileChannel,
        val lock: FileLock,
        val startedAtMs: Long
    )

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        @Volatile
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            out.write(value)
            count++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            out.write(bytes, offset, length)
            count += length.toLong()
        }
    }

    private class FastGzipOutputStream(output: OutputStream) :
        GZIPOutputStream(output, WRITER_BUFFER_BYTES, true) {
        init {
            def.setLevel(Deflater.BEST_SPEED)
        }
    }
}
