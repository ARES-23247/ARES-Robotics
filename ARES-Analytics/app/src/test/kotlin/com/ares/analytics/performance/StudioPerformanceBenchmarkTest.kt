package com.ares.analytics.performance

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.FrameBatcher
import com.ares.analytics.service.ReplayEngineService
import com.ares.analytics.service.UiTelemetryFanout
import com.ares.analytics.service.loadTelemetryWindowPages
import com.ares.analytics.service.log.JsonlLogDecoder
import com.ares.analytics.service.log.WpiLogDecoder
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.ui.components.dashboard.TelemetryPoint
import com.ares.analytics.ui.components.dashboard.buildTelemetryChartSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioPerformanceBenchmarkTest {
    private val threadMxBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

    private fun allocatedBytes(): Long =
        threadMxBean?.getThreadAllocatedBytes(Thread.currentThread().id) ?: 0L

    @Test
    fun `benchmark studio live telemetry chart log decoders and replay`() = runBlocking {
        val tempDir = Files.createTempDirectory("studio-perf-bench").toFile()
        val database = DatabaseService(tempDir.resolve("perf.duckdb").absolutePath)
        try {
            // 1. Track 2a: UiTelemetryFanout 100k burst across 64 topics
            val fanoutScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val fanout = UiTelemetryFanout(fanoutScope, frameIntervalMs = 50L)
            val prebuiltFrames = Array(64) { idx ->
                TelemetryFrame(1000L + idx, "live-telemetry", "Subsystem/Topic_$idx", idx * 0.5)
            }
            repeat(10_000) { i -> fanout.offer(prebuiltFrames[i and 63]) }
            val fanoutStartAlloc = allocatedBytes()
            val fanoutStartNs = System.nanoTime()
            repeat(100_000) { i -> fanout.offer(prebuiltFrames[i and 63]) }
            val fanoutElapsedMs = (System.nanoTime() - fanoutStartNs) / 1_000_000.0
            val fanoutAllocBytes = allocatedBytes() - fanoutStartAlloc
            fanoutScope.coroutineContext.job.cancelAndJoin()

            // 2. Track 2b: TelemetryChartModel 200 viewport snapshots across 8 signals x 10,000 points
            val keys = (0 until 8).map { "Drive/Wheel_${it}_Velocity_mps" }
            val pointsByKey = keys.associateWith { key ->
                val offset = key.hashCode() and 15
                List(10_000) { idx -> TelemetryPoint(idx * 20L, (idx + offset) * 0.01) }
            }
            repeat(20) { step ->
                buildTelemetryChartSnapshot(keys, pointsByKey, emptyMap(), 150_000L + step * 20L, 10_000L)
            }
            val chartStartAlloc = allocatedBytes()
            val chartStartNs = System.nanoTime()
            var totalVisible = 0
            repeat(200) { step ->
                val snap = buildTelemetryChartSnapshot(keys, pointsByKey, emptyMap(), 150_000L + step * 20L, 10_000L)
                totalVisible += snap.groups.sumOf { g -> g.series.sumOf { it.points.size } }
            }
            val chartElapsedMs = (System.nanoTime() - chartStartNs) / 1_000_000.0
            val chartAllocBytes = allocatedBytes() - chartStartAlloc
            assertTrue(totalVisible > 0)

            // 3. Track 3a: WpiLogDecoder 5,000 cycles x (6 doubles + 10-element pose array = 80,000 frames)
            val wpilogFile = tempDir.resolve("bench.wpilog")
            writeSyntheticWpiLog(wpilogFile, cycles = 5_000)
            val wpiDecoder = WpiLogDecoder()
            // Warmup pass
            val warmupWpiBatcher = FrameBatcher(database, batchSize = 100_000)
            wpiDecoder.parseWpiLog(wpilogFile, "wpi-warmup", warmupWpiBatcher)
            database.deleteTelemetryFrames("wpi-warmup")

            val wpiBatcher = FrameBatcher(database, batchSize = 100_000)
            val wpiStartAlloc = allocatedBytes()
            val wpiStartNs = System.nanoTime()
            wpiDecoder.parseWpiLog(wpilogFile, "wpi-bench", wpiBatcher)
            val wpiDecodeMs = (System.nanoTime() - wpiStartNs) / 1_000_000.0
            val wpiAllocBytes = allocatedBytes() - wpiStartAlloc
            assertEquals(80_000, wpiBatcher.frameCount)
            wpiBatcher.flush()

            // 4. Track 3b: JsonlLogDecoder (10,000 telemetry lines = 60,000 frames + 5,000 action lines)
            val jsonlTelemetryFile = tempDir.resolve("telemetry.jsonl")
            jsonlTelemetryFile.bufferedWriter().use { writer ->
                repeat(10_000) { i ->
                    val ts = 1_000L + i * 20L
                    writer.append("""{"timestampMs":$ts,"/Drive/Pose_X":${i * 0.01},"/Drive/Pose_Y":${i * 0.02},"/Drive/Heading":0.5,"/Hardware/Voltage":12.4,"/Robot/Enabled":true,"/Robot/Mode":"TELEOP"}""")
                    writer.append('\n')
                }
            }
            val jsonlActionsFile = tempDir.resolve("actions.jsonl")
            jsonlActionsFile.bufferedWriter().use { writer ->
                repeat(5_000) { i ->
                    val ts = 1_000L + i * 20L
                    writer.append("""{"schema_version":1,"run_id":"run-1","robot_id":"bot-1","match_number":1,"alliance":"RED","type":"DriveCommand","payload":{"timestampMs":$ts,"vx":1.2,"vy":0.0,"omega":0.3}}""")
                    writer.append('\n')
                }
            }
            val jsonlDecoder = JsonlLogDecoder(database)
            // Warmup
            jsonlDecoder.parseJsonlLog(jsonlTelemetryFile, "jsonl-warmup") { }
            val jsonlStartAlloc = allocatedBytes()
            val jsonlStartNs = System.nanoTime()
            var jsonlEmitted = 0
            jsonlDecoder.parseJsonlLog(jsonlTelemetryFile, "jsonl-bench") { jsonlEmitted++ }
            val actionMeta = jsonlDecoder.parseActionLogJsonl(jsonlActionsFile, "jsonl-actions-bench")
            val jsonlElapsedMs = (System.nanoTime() - jsonlStartNs) / 1_000_000.0
            val jsonlAllocBytes = allocatedBytes() - jsonlStartAlloc
            assertEquals(60_000, jsonlEmitted)
            assertTrue(actionMeta != null)

            // 5. Track 3c: ReplayWindowSource & ReplayEngineService over 120,000 DuckDB rows
            val replayRows = ArrayList<TelemetryFrame>(120_000)
            for (cycle in 0 until 6_000) {
                val tsMs = cycle * 20L
                val tsUs = tsMs * 1_000L
                for (topicIdx in 0 until 20) {
                    replayRows += TelemetryFrame(
                        timestampMs = tsMs,
                        sessionId = "replay-bench",
                        key = "Drive/Signal_$topicIdx",
                        value = cycle * 0.1 + topicIdx,
                        timestampUs = tsUs,
                    )
                }
            }
            database.insertTelemetryFrames(replayRows)

            val pageStartAlloc = allocatedBytes()
            val pageStartNs = System.nanoTime()
            val pagedWindow = loadTelemetryWindowPages(database, "replay-bench", 0L, 120_000L, pageSize = 10_000)
            val pageElapsedMs = (System.nanoTime() - pageStartNs) / 1_000_000.0
            val pageAllocBytes = allocatedBytes() - pageStartAlloc
            assertEquals(120_000, pagedWindow.size)

            val replayEngine = ReplayEngineService(database)
            try {
                val loadStartAlloc = allocatedBytes()
                val loadStartNs = System.nanoTime()
                replayEngine.loadSession("replay-bench")
                val loadElapsedMs = (System.nanoTime() - loadStartNs) / 1_000_000.0
                val loadAllocBytes = allocatedBytes() - loadStartAlloc

                val scrubStartAlloc = allocatedBytes()
                val scrubStartNs = System.nanoTime()
                repeat(500) { idx ->
                    replayEngine.seekToTimestamp((idx * 14L) % 5_000L)
                }
                val scrubElapsedMs = (System.nanoTime() - scrubStartNs) / 1_000_000.0
                val scrubAllocBytes = allocatedBytes() - scrubStartAlloc

                println(
                    "[STUDIO-BENCH] " +
                        "fanout100kMs=${"%.3f".format(fanoutElapsedMs)} fanout100kAllocBytes=$fanoutAllocBytes | " +
                        "chart200Ms=${"%.3f".format(chartElapsedMs)} chart200AllocBytes=$chartAllocBytes | " +
                        "wpilog80kMs=${"%.3f".format(wpiDecodeMs)} wpilog80kAllocBytes=$wpiAllocBytes | " +
                        "jsonlMs=${"%.3f".format(jsonlElapsedMs)} jsonlAllocBytes=$jsonlAllocBytes | " +
                        "windowPages120kMs=${"%.3f".format(pageElapsedMs)} windowPages120kAllocBytes=$pageAllocBytes | " +
                        "replayLoadMs=${"%.3f".format(loadElapsedMs)} replayLoadAllocBytes=$loadAllocBytes | " +
                        "replayScrub500Ms=${"%.3f".format(scrubElapsedMs)} replayScrub500AllocBytes=$scrubAllocBytes"
                )
            } finally {
                replayEngine.disposeAndJoin()
            }
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    private fun writeSyntheticWpiLog(target: java.io.File, cycles: Int) {
        val out = ByteArrayOutputStream(cycles * 256)
        // Header: "WPILOG" + version 0x0100 + extraHeaderSize 0
        out.write("WPILOG".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00))

        for (entryId in 1..6) {
            out.writeWpiRecord(0, 0L, wpiControlStart(entryId, "/Robot/Scalar_$entryId", "double"))
        }
        out.writeWpiRecord(0, 0L, wpiControlStart(7, "/ARES/SimulatorPoseFrame", "double[]"))

        val doubleBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        val poseBuf = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
        for (cycle in 0 until cycles) {
            val tsUs = cycle * 20_000L
            for (entryId in 1..6) {
                doubleBuf.clear()
                doubleBuf.putDouble(cycle * 0.05 + entryId)
                out.writeWpiRecord(entryId, tsUs, doubleBuf.array())
            }
            poseBuf.clear()
            for (k in 0 until 10) {
                poseBuf.putDouble(cycle * 0.01 + k)
            }
            out.writeWpiRecord(7, tsUs, poseBuf.array())
        }
        target.writeBytes(out.toByteArray())
    }

    private fun wpiControlStart(entryId: Int, name: String, type: String): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val typeBytes = type.toByteArray(Charsets.UTF_8)
        val metaBytes = "{}".toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + 4 + 4 + nameBytes.size + 4 + typeBytes.size + 4 + metaBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0.toByte())
        buf.putInt(entryId)
        buf.putInt(nameBytes.size)
        buf.put(nameBytes)
        buf.putInt(typeBytes.size)
        buf.put(typeBytes)
        buf.putInt(metaBytes.size)
        buf.put(metaBytes)
        return buf.array()
    }

    private fun ByteArrayOutputStream.writeWpiRecord(entryId: Int, timestampUs: Long, payload: ByteArray) {
        // entryIdSize = 1 (0), payloadSizeSize = 2 (1 shl 2 = 4), timestampSize = 8 (7 shl 4 = 0x70) -> descriptor = 0x74
        write(0x74)
        write(entryId and 0xFF)
        write(payload.size and 0xFF)
        write((payload.size ushr 8) and 0xFF)
        for (shift in 0 until 8) {
            write(((timestampUs ushr (shift * 8)) and 0xFFL).toInt())
        }
        write(payload)
    }
}
