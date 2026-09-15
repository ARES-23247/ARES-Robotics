package com.areslib.sim

import com.areslib.util.RobotClock
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory

/**
 * Opt-in desktop evidence, independent of RobotClock's fixed simulation time. Buffers are bounded
 * and allocated once. CSV I/O occurs only after capture completes or the runner exits. Allocations
 * cover this runner thread's work, not NT4/OpMode worker threads or target-controller hardware.
 */
internal class SimLoopTimingRecorder private constructor(private val output: File, capacity: Int) : AutoCloseable {
    private val starts = LongArray(capacity)
    private val work = LongArray(capacity)
    private val waits = LongArray(capacity)
    private val lateness = LongArray(capacity)
    private val rebased = LongArray(capacity)
    private val allocations = LongArray(capacity)
    private val active = BooleanArray(capacity)
    private val bean = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.takeIf {
        it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled
    }
    private val threadId = Thread.currentThread().id
    private var allocationStart = -1L
    private var workEnd = 0L
    private var count = 0
    private var written = false

    fun beginFrame() {
        if (count == starts.size) return
        allocationStart = bean?.getThreadAllocatedBytes(threadId) ?: -1L
        starts[count] = RobotClock.hostNanoTime()
    }

    fun endWork(isActive: Boolean) {
        if (count == starts.size) return
        workEnd = RobotClock.hostNanoTime()
        work[count] = workEnd - starts[count]
        allocations[count] = if (allocationStart >= 0) bean!!.getThreadAllocatedBytes(threadId) - allocationStart else -1L
        active[count] = isActive
    }

    fun endPacing(pacer: SimFramePacer) {
        if (count == starts.size) return
        waits[count] = RobotClock.hostNanoTime() - workEnd
        lateness[count] = pacer.lastLatenessNanos
        rebased[count] = pacer.rebasedPeriods
        count++
        if (count == starts.size) close()
    }

    override fun close() {
        if (written) return
        written = true
        runCatching {
            output.parentFile?.mkdirs()
            output.bufferedWriter().use { writer ->
                writer.appendLine("frame,startNanos,workNanos,waitNanos,wakeLatenessNanos,rebasedPeriods,allocatedBytes,active")
                repeat(count) { i ->
                    writer.appendLine("$i,${starts[i]},${work[i]},${waits[i]},${lateness[i]},${rebased[i]},${allocations[i]},${active[i]}")
                }
            }
            println("[Simulator] Saved $count host timing frames to ${output.absolutePath}")
        }.onFailure { System.err.println("[Simulator] Could not save host timing: ${it.message}") }
    }

    companion object {
        fun fromEnvironment(): SimLoopTimingRecorder? {
            val path = System.getenv("ARES_SIM_TIMING_PATH")?.takeIf(String::isNotBlank) ?: return null
            val frames = System.getenv("ARES_SIM_TIMING_FRAMES")?.toIntOrNull()?.coerceIn(100, 100_000) ?: 6_000
            return SimLoopTimingRecorder(File(path), frames)
        }
    }
}
