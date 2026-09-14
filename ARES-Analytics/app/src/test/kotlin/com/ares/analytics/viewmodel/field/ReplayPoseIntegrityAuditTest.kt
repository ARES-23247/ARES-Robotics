package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayPoseIntegrityAuditTest {
    private val trueKeys = listOf("ARES/TruePose/0", "ARES/TruePose/1", "ARES/TruePose/2")
    private fun packed() = (0..9).associate { "ARES/SimulatorPoseFrame/$it" to it.toDouble() }
    private fun snapshot(values: Map<String, Double>, strings: Map<String, String> = emptyMap()) =
        ReplayFrame(1_000, values, strings).toReplayPoseState()

    @Test
    fun `packed pose rejects nonfinite components atomically`() {
        for (index in 0..8) for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val state = snapshot(packed() + ("ARES/SimulatorPoseFrame/$index" to bad))
            assertFalse(state.hasTruePoseData, "slot $index = $bad")
            assertNull(state.simHeading)
            assertNull(state.ekfX)
            assertNull(state.odomX)
        }
    }

    @Test
    fun `packed pose requires an exact nonnegative sequence`() {
        for (bad in listOf(-1.0, 0.5, Double.NaN, Double.POSITIVE_INFINITY, 9_007_199_254_740_992.0)) {
            assertFalse(snapshot(packed() + ("ARES/SimulatorPoseFrame/9" to bad)).hasTruePoseData, "$bad")
        }
    }

    @Test
    fun `packed text placeholders cannot become numeric pose coordinates`() {
        for (index in 0..9) {
            assertFalse(snapshot(packed(), mapOf("ARES/SimulatorPoseFrame/$index" to "unavailable")).hasTruePoseData)
        }
    }

    @Test
    fun `an incomplete packed frame cannot supply simulator heading`() {
        assertNull(snapshot(mapOf("ARES/SimulatorPoseFrame/2" to 0.7)).simHeading)
    }

    @Test
    fun `scalar truth requires complete numeric finite coordinates`() {
        val values = trueKeys.zip(listOf(1.0, 2.0, 0.3)).toMap()
        for (key in trueKeys) {
            assertFalse(snapshot(values, mapOf(key to "offline")).hasTruePoseData)
            assertFalse(snapshot(values + (key to Double.NaN)).hasTruePoseData)
            assertFalse(snapshot(values - key).hasTruePoseData)
        }
    }

    @Test
    fun `estimator fallback uses a complete source without mixing axes`() {
        val state = snapshot(mapOf("ARES/EstimatedPose/0" to 90.0,
            "Drive/Pose_X" to 1.0, "Drive/Pose_Y" to 2.0, "Drive/Drive_Heading" to 0.3))
        assertEquals(1.0, state.ekfX)
        assertEquals(2.0, state.ekfY)
        assertEquals(0.3, state.ekfHeading)
        assertNull(snapshot(mapOf("Drive/Odom_X" to 8.0)).odomX)
    }

    @Test
    fun `text lighting placeholders cannot appear as applied output`() {
        val key = "Subsystems/status/AppliedOutputs/light/INDICATOR_LIGHT"
        assertTrue(snapshot(mapOf(key to 0.0), mapOf(key to "unavailable")).indicatorLights.isEmpty())
    }

    @Test
    fun `vision requires a numeric boolean target flag`() {
        for (bad in listOf(0.6, 2.0, Double.POSITIVE_INFINITY, Double.NaN)) {
            assertFalse(snapshot(mapOf("Vision/HasTarget" to bad)).visionHasTarget, "$bad")
        }
        assertFalse(snapshot(mapOf("Vision/HasTarget" to 1.0), mapOf("Vision/HasTarget" to "true")).visionHasTarget)
    }

    @Test
    fun `vision arrays reject sparse oversized incomplete and cross-source coordinates`() {
        val state = snapshot(mapOf("Vision/HasTarget" to 1.0,
            "Vision/PoseArray/0" to 1.0, "AdvantageScope/VisionPose/1" to 2.0,
            "AdvantageScope/VisionPose/2" to 0.3, "Vision/PoseArray/-1" to 1.0,
            "Vision/PoseArray/2147483647" to 1.0))
        assertTrue(state.visionPoses.isEmpty())
        assertNull(snapshot(mapOf("Vision/HasTarget" to 1.0, "Vision/Pose_X" to Double.NaN,
            "Vision/Pose_Y" to 2.0, "Vision/Pose_Heading" to 0.3)).visionX)
    }


    @Test
    fun `valid packed and scalar poses preserve finite radians and source priority`() {
        val state = snapshot(packed() + trueKeys.zip(listOf(91.0, 92.0, 93.0)).toMap())
        assertEquals(listOf(0.0, 1.0, 2.0), listOf(state.trueX, state.trueY, state.trueHeading))
        assertEquals(listOf(3.0, 4.0, 5.0), listOf(state.ekfX, state.ekfY, state.ekfHeading))
        assertEquals(listOf(6.0, 7.0, 8.0), listOf(state.odomX, state.odomY, state.odomHeading))
        assertEquals(2.0, state.simHeading)
        for (sequence in listOf(0.0, 9_007_199_254_740_991.0)) {
            assertTrue(snapshot(packed() + ("ARES/SimulatorPoseFrame/9" to sequence)).hasTruePoseData)
        }
        val fallback = snapshot(packed() - "ARES/SimulatorPoseFrame/9" +
            trueKeys.zip(listOf(1.0, 2.0, -10.0)).toMap())
        assertTrue(fallback.hasTruePoseData)
        assertEquals(-10.0, fallback.trueHeading)
        assertNull(fallback.simHeading)
    }

    @Test
    fun `invalid scalar groups stay absent and finite complete alternatives remain usable`() {
        val keys = listOf("Drive/Odom_X", "Drive/Odom_Y", "Drive/Odom_Heading")
        val values = keys.zip(listOf(1.0, 2.0, 0.3)).toMap()
        assertEquals(1.0, snapshot(values).odomX)
        for (key in keys) {
            assertNull(snapshot(values, mapOf(key to "unknown")).odomX)
            assertNull(snapshot(values + (key to Double.NEGATIVE_INFINITY)).odomX)
        }
        val estimate = (0..2).map { "ARES/EstimatedPose/$it" }.zip(listOf(3.0, 4.0, 0.5)).toMap()
        assertEquals(3.0, snapshot(estimate).ekfX)
        assertNull(snapshot(estimate, mapOf("ARES/EstimatedPose/2" to "unknown")).ekfX)
    }

    @Test
    fun `vision accepts complete bounded triples and deterministic array source priority`() {
        val canonical = (0..2).associate { "Vision/PoseArray/$it" to (it + 1.0) }
        val alias = (0..2).associate { "AdvantageScope/VisionPose/$it" to (it + 11.0) }
        val on = mapOf("Vision/HasTarget" to 1.0)
        assertEquals(mapOf(0 to 1.0, 1 to 2.0, 2 to 3.0), snapshot(on + canonical + alias).visionPoses)
        assertEquals(mapOf(0 to 11.0, 1 to 12.0, 2 to 13.0), snapshot(on + alias).visionPoses)
        assertTrue(snapshot(on + canonical, mapOf("Vision/PoseArray/1" to "unknown")).visionPoses.isEmpty())
        assertTrue(snapshot(canonical).visionPoses.isEmpty())
        val boundary = (4092..4094).associate { "Vision/PoseArray/$it" to it.toDouble() }
        assertEquals(setOf(4092, 4093, 4094), snapshot(on + boundary).visionPoses.keys)
        val overflow = (4095..4097).associate { "Vision/PoseArray/$it" to it.toDouble() }
        assertTrue(snapshot(on + overflow).visionPoses.isEmpty())
        val scalar = snapshot(on + mapOf("Vision/Pose_X" to 1.0, "Vision/Pose_Y" to 2.0, "Vision/Pose_Heading" to -0.5))
        assertEquals(-0.5, scalar.visionHeading)
    }

    @Test
    fun `numeric lighting survives while text and nonfinite outputs are omitted`() {
        val prefix = "Subsystems/status/AppliedOutputs/"
        val state = snapshot(mapOf(prefix + "lamp/INDICATOR_LIGHT" to 1.0,
            prefix + "prism/PRISM_DRIVER" to 0.25, prefix + "broken/PRISM_DRIVER" to Double.NaN))
        assertEquals(mapOf("status/lamp" to 1.0), state.indicatorLights)
        assertEquals(mapOf("status/prism" to 0.25), state.prismLights)
    }

    @Test
    fun `trace preserves all unsampled poses including multiple exact frames per microsecond`() = databaseTest { database ->
        database.insertTelemetryFrames(frames(1_000_001, listOf(1.0, 2.0, 0.1), orders = listOf(1, 1, 1)) +
            frames(1_000_001, listOf(3.0, 4.0, 0.2), orders = listOf(2, 2, 2)) +
            frames(1_000_999, listOf(5.0, 6.0, 0.3)))
        assertEquals(listOf(1.0, 3.0, 5.0), loadReplayFieldTrace(database, "trace", 1000, 1000, 20).map { it.x })
    }

    @Test
    fun `trace never joins different microseconds or different pose sources`() = databaseTest { database ->
        val mixed = frames(1_000_001, listOf(1.0, 2.0, 0.1)).mapIndexed { index, frame ->
            if (index == 1) frame.copy(timestampUs = 1_000_002) else frame
        }
        database.insertTelemetryFrames(mixed + frames(1_001_000, listOf(3.0, 4.0, 0.2)).mapIndexed { index, frame ->
            if (index == 1) frame.copy(key = "ARES/EstimatedPose/1") else frame
        })
        assertTrue(loadReplayFieldTrace(database, "trace", 1000, 1001, 20).isEmpty())
    }

    @Test
    fun `trace validates every packed component and sequence before source selection`() = databaseTest { database ->
        val keys = (0..9).map { "ARES/SimulatorPoseFrame/$it" }
        val valid = (0..9).map { it.toDouble() }
        database.insertTelemetryFrames(frames(1_000_000, valid, keys) +
            frames(1_001_000, valid.mapIndexed { i, v -> if (i == 5) Double.NaN else v }, keys) +
            frames(1_002_000, valid.mapIndexed { i, v -> if (i == 9) 0.5 else v }, keys) +
            frames(1_003_000, valid, keys).mapIndexed { i, f -> if (i == 8) f.copy(stringValue = "") else f })
        val trace = loadReplayFieldTrace(database, "trace", 1000, 1003, 20)
        assertEquals(1, trace.size)
        assertEquals(0.0, trace.single().x)
        assertEquals(2.0, trace.single().headingRad)
    }

    @Test
    fun `trace sampling obeys each point budget and preserves both endpoints`() = databaseTest { database ->
        database.insertTelemetryFrames((0..20).flatMap { i ->
            frames(1_000_000L + i * 1_000L, listOf(i.toDouble(), (i * i).toDouble(), 0.0))
        })
        for (limit in listOf(2, 3, 10, 20, 21, 22, 10_000)) {
            val trace = loadReplayFieldTrace(database, "trace", Long.MIN_VALUE, Long.MAX_VALUE, limit)
            assertEquals(minOf(limit, 21), trace.size)
            assertEquals(0.0, trace.first().x)
            assertEquals(20.0, trace.last().x)
            assertTrue(trace.all { it.y == it.x * it.x && it.headingRad == 0.0 })
        }
    }

    @Test
    fun `trace bounds and session filters use one query and no raw series reads`() = databaseTest { database ->
        val session = "trace' OR 1=1 --"
        database.insertTelemetryFrames(frames(1_000_000, listOf(91.0, 92.0, 0.1)) +
            (0..2).flatMap { i -> frames(1_000_000L + i * 1_000L, listOf(i.toDouble(), 2.0, 0.1), session = session) })
        val before = database.metrics.snapshot().queryCount
        val trace = loadReplayFieldTrace(database, session, 1001, 1002, 20)
        assertEquals(listOf(1.0, 2.0), trace.map { it.x })
        assertEquals(before + 1, database.metrics.snapshot().queryCount)
        assertTrue(loadReplayFieldTrace(database, "missing", 1000, 1002, 20).isEmpty())
        val after = database.metrics.snapshot().queryCount
        assertTrue(loadReplayFieldTrace(database, "", 1000, 1002, 20).isEmpty())
        assertTrue(loadReplayFieldTrace(database, session, 1002, 1001, 20).isEmpty())
        assertEquals(after, database.metrics.snapshot().queryCount)
    }

    @Test
    fun `trace also reads the isolated live database without mixing persistent sessions`() = databaseTest { database ->
        database.insertTelemetryFrames(frames(1_000_000, listOf(1.0, 2.0, 0.3), session = "live-telemetry") +
            frames(1_000_000, listOf(91.0, 92.0, 0.9)))
        assertEquals(1.0, loadReplayFieldTrace(database, "live-telemetry", 1000, 1000, 20).single().x)
        assertEquals(91.0, loadReplayFieldTrace(database, "trace", 1000, 1000, 20).single().x)
    }

    @Test
    fun `trace rejects invalid budgets before querying and cancellation preserves database usability`() = databaseTest { database ->
        val before = database.metrics.snapshot().queryCount
        for (limit in listOf(Int.MIN_VALUE, 0, 1, 10_001, Int.MAX_VALUE)) {
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                loadReplayFieldTrace(database, "trace", 1000, 1000, limit)
            }
        }
        assertEquals(before, database.metrics.snapshot().queryCount)
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Job().apply { cancel() }) {
                loadReplayFieldTrace(database, "trace", 1000, 1000, 20)
            }
        }
        database.insertTelemetryFrames(frames(1_000_000, listOf(1.0, 2.0, 0.3)))
        assertEquals(1.0, loadReplayFieldTrace(database, "trace", 1000, 1000, 20).single().x)
    }

    private fun frames(timeUs: Long, values: List<Double>, keys: List<String> = trueKeys,
        orders: List<Long> = keys.indices.map { it + 1L }, session: String = "trace") =
        keys.mapIndexed { index, key ->
            TelemetryFrame(timeUs / 1000, session, key, values[index], timestampUs = timeUs, sampleOrder = orders[index])
        }

    private fun databaseTest(block: suspend (DatabaseService) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("ares-replay-pose-audit").toFile()
        var database: DatabaseService? = null
        try {
            val owned = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
            database = owned
            block(owned)
        } finally {
            try { database?.closeAndJoin() } finally { directory.deleteRecursively() }
        }
    }

    @Test
    fun `trace joins complete poses before downsampling constant heading`() = databaseTest { database ->
        database.insertTelemetryFrames((0..100).flatMap { i ->
            frames(1_000_000L + i * 1_000L, listOf(i.toDouble(), 100.0 - i, 0.0))
        })
        val trace = loadReplayFieldTrace(database, "trace", 1000, 1100, 10)
        assertEquals(10, trace.size)
        assertEquals(0.0, trace.first().x)
        assertEquals(100.0, trace.last().x)
        assertTrue(trace.zipWithNext().all { (a, b) -> a.x < b.x })
        assertTrue(trace.all { it.x + it.y == 100.0 && it.headingRad == 0.0 })
    }

    @Test
    fun `exact sample identities do not discard other unambiguous timestamps`() = databaseTest { database ->
        database.insertTelemetryFrames(frames(1_000_000, listOf(1.0, 2.0, 0.1), orders = listOf(1, 1, 1)) +
            frames(1_001_000, listOf(3.0, 4.0, 0.2)))
        assertEquals(listOf(1.0, 3.0), loadReplayFieldTrace(database, "trace", 1000, 1001, 20).map { it.x })
    }

    @Test
    fun `ambiguous duplicate scalar instants cannot be stitched into invented poses`() = databaseTest { database ->
        database.insertTelemetryFrames(frames(1_000_000, listOf(1.0, 2.0, 0.1)) +
            frames(1_000_000, listOf(10.0, 20.0, 0.9), orders = listOf(4, 5, 6)))
        assertTrue(loadReplayFieldTrace(database, "trace", 1000, 1000, 20).isEmpty())
    }

    @Test
    fun `trace rejects text placeholders and nonfinite coordinates`() = databaseTest { database ->
        val text = frames(1_000_000, listOf(0.0, 2.0, 0.1)).mapIndexed { index, frame ->
            if (index == 0) frame.copy(stringValue = "missing") else frame
        }
        database.insertTelemetryFrames(text + frames(1_001_000, listOf(Double.POSITIVE_INFINITY, 4.0, 0.2)))
        assertTrue(loadReplayFieldTrace(database, "trace", 1000, 1001, 20).isEmpty())
    }

    @Test
    fun `incomplete packed trace does not override complete estimator samples`() = databaseTest { database ->
        database.insertTelemetryFrames(frames(1_000_000, listOf(90.0, 80.0, 0.5),
            keys = (0..2).map { "ARES/SimulatorPoseFrame/$it" }) +
            frames(1_000_000, listOf(1.0, 2.0, 0.3), keys = (0..2).map { "ARES/EstimatedPose/$it" }))
        assertEquals(1.0, loadReplayFieldTrace(database, "trace", 1000, 1000, 20).single().x)
    }

    @Test
    fun `trace supports the same drive heading alias as the replay pose`() = databaseTest { database ->
        database.insertTelemetryFrames(frames(1_000_000, listOf(1.0, 2.0, 0.3),
            keys = listOf("Drive/Pose_X", "Drive/Pose_Y", "Drive/Drive_Heading")))
        assertEquals(0.3, loadReplayFieldTrace(database, "trace", 1000, 1000, 20).single().headingRad)
    }
}
