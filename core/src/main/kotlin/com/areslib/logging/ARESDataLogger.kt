package com.areslib.logging

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
 * ends in `.csv.active`; [stop] blocks until every accepted frame is drained and atomically exposes
 * the completed `.csv` name. Importers can therefore ignore active files instead of guessing from
 * temporary size stability.
 *
 * This class is thread-safe for producers and shutdown. It reduces control-loop IO latency; it does
 * not promise allocation-free logging when the pool is exhausted or when rows are serialized.
 */
class ARESDataLogger(
    val mode: String = "Init",
    private val logDirectory: File = RobotLogEnvironment.logDirectory
) {

    private val logQueue = LinkedBlockingQueue<Map<String, Any>>(1000)
    private val activeKeys = mutableListOf<String>()
    private val activeKeySet = HashSet<String>()
    private var writer: BufferedWriter? = null
    private var activeLogFile: File? = null
    private var completedLogFile: File? = null
    private var isHeaderWritten = false
    @Volatile private var isRunning = false
    private val queueStateLock = Any()
    private val workerDone = CountDownLatch(1)
    private val droppedFrames = AtomicLong(0L)

    /** Number of frames rejected because the logger was stopped or its bounded queue was full. */
    val droppedFrameCount: Long
        get() = droppedFrames.get()

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

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.getDefault()).format(Date())
            val finalFile = File(logDirectory, "ares_log_${timestamp}_$mode.csv")
            val logFile = File(logDirectory, "${finalFile.name}.active")

            activeLogFile = logFile
            completedLogFile = finalFile
            writer = BufferedWriter(FileWriter(logFile))
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
                closeWriter()
                finalizeLogFile()
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
        val w = writer ?: return

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
            } catch (e: IOException) {
                System.err.println("ARESDataLogger: Failed to write CSV row: ${e.message}")
            }
        } finally {
            // HashMap inputs follow the ownership contract and return to this logger's pool.
            if (frame is HashMap<String, Any>) {
                recycleMap(frame)
            }
        }
    }

    private fun closeWriter() {
        try {
            writer?.flush()
            writer?.close()
        } catch (e: IOException) {
            System.err.println("ARESDataLogger: Failed to close writer: ${e.message}")
        } finally {
            writer = null
        }
    }

    private fun finalizeLogFile() {
        val active = activeLogFile ?: return
        val completed = completedLogFile ?: return
        if (!active.exists()) return
        if (completed.exists() && !completed.delete()) {
            System.err.println("ARESDataLogger: Could not replace existing completed log ${completed.absolutePath}")
            return
        }
        if (!active.renameTo(completed)) {
            System.err.println("ARESDataLogger: Could not finalize active log ${active.absolutePath}")
            return
        }
        activeLogFile = null
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
    }
}
