package com.areslib.frc.vision

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.frc.TestSwerveHardwareIO
import com.areslib.hardware.vision.VisionFilterConfig
import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.math.geometry.*
import com.areslib.reducer.rootReducer
import com.areslib.state.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FrcVisionTimeHistoryAuditTest {
    private class Camera : VisionIO {
        var frames: List<VisionMeasurement> = emptyList()
        override fun updateInputs(inputs: VisionIOInputs) {
            inputs.isConnected = true
            inputs.measurements = frames
        }
    }

    private class Swerve : TestSwerveHardwareIO() {
        val fusedTimes = ArrayList<Double>()
        val historyTimes = ArrayList<Double>()
        var history: (Double, DoubleArray) -> Boolean = { _, _ -> false }
        override fun read() = DriveState()
        override fun write(driveState: DriveState, powerScale: Double) = Unit
        override fun seedPose(pose: Pose2d) = Unit
        override fun addVisionMeasurement(pose: Pose2d, timestampSeconds: Double) {
            fusedTimes.add(timestampSeconds)
        }
        override fun samplePoseAt(timestampSeconds: Double, out: DoubleArray): Boolean {
            historyTimes.add(timestampSeconds)
            return history(timestampSeconds, out)
        }
    }

    private class Harness(hasSwerve: Boolean = true, simulation: Boolean = false) {
        val camera = Camera()
        val swerve = Swerve()
        var clockReads = 0
        var conversions = 0
        var clock: () -> Double = { 10.0 }
        var converter: (Double) -> Double = { it + 20.0 }
        var batches = 0
        var batchSize = 0
        val store = Store(RobotState(
            drive = DriveState(measuredMotionValid = true, imuMeasurementsValid = true),
            vision = VisionState(filterConfig = VisionFilterConfig(
                minFieldX = -20.0, maxFieldX = 20.0, minFieldY = -20.0, maxFieldY = 20.0))
        ), { state, action ->
            if (action is RobotAction.VisionMeasurementsReceived) {
                assertFalse(action.fuseIntoPoseEstimator)
                batches++
                batchSize = action.measurements.size
            }
            rootReducer(state, action)
        })
        val tracker = FrcVisionTracker(store, camera, if (hasSwerve) swerve else null, simulation,
            estimatorTimeSecondsProvider = { clockReads++; clock() },
            fpgaToEstimatorTimeSeconds = { conversions++; converter(it) },
            isDisabledProvider = { true })

        fun frame(time: Long = 900L, source: String = "camera", captureMicros: Long = 0L,
            x: Double = 0.2, y: Double = 0.0) = VisionMeasurement(
            timestampMs = time, frameId = time, sourceId = source,
            captureTimestampMicros = captureMicros, tagId = 1, tagCount = 2,
            targetPose = Pose3d(Translation3d(x, y, 0.0), Rotation3d()),
            averageTagDistanceMeters = 2.0, ambiguity = 0.01,
            solverType = VisionSolverType.MEGATAG2)

        fun poll(now: Long = 1000L, vararg frames: VisionMeasurement) {
            camera.frames = frames.toList()
            tracker.update(now)
        }
    }

    @Test fun `nonfinite fallback time never reaches history or fusion`() {
        for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val h = Harness()
            h.clock = { bad }
            h.poll(1000L, h.frame())
            assertTrue(h.swerve.fusedTimes.isEmpty(), "clock=$bad")
            assertTrue(h.swerve.historyTimes.isEmpty())
            assertEquals("REJECTED_TIMESTAMP", h.tracker.lastVisionStatus)
            assertTrue(h.tracker.isConnected)
            assertEquals(1, h.batches)
        }
    }

    @Test fun `failed fallback does not abort a later usable native capture in the batch`() {
        val h = Harness()
        h.clock = { throw IllegalStateException("clock unavailable") }
        assertDoesNotThrow {
            h.poll(1000L, h.frame(source = "fallback"),
                h.frame(source = "native", captureMicros = 2_000_000L))
        }
        assertEquals(listOf(22.0), h.swerve.fusedTimes)
        assertEquals(listOf(22.0), h.swerve.historyTimes)
        assertEquals(1, h.clockReads)
        assertEquals(1, h.conversions)
        assertTrue(h.tracker.isConnected)
        assertEquals("ACCEPTED", h.tracker.lastVisionStatus)
        assertEquals(2, h.batchSize)
    }

    @Test fun `fallback observations share one estimator clock sample per update`() {
        val h = Harness()
        h.clock = { 9.0 + h.clockReads }
        h.poll(1000L, h.frame(900L, "a"), h.frame(850L, "b"))
        assertEquals(1, h.clockReads)
        assertEquals(9.9, h.swerve.fusedTimes[0], 1e-12)
        assertEquals(9.85, h.swerve.fusedTimes[1], 1e-12)
        h.poll(1200L, h.frame(1100L, "a"))
        assertEquals(2, h.clockReads)
        assertEquals(10.9, h.swerve.fusedTimes[2], 1e-12)
    }

    @Test fun `failed clock snapshot is shared until the next update`() {
        val h = Harness()
        h.clock = { if (h.clockReads == 1) Double.NaN else 10.0 }
        h.poll(1000L, h.frame(900L, "a"), h.frame(850L, "b"))
        assertEquals(1, h.clockReads)
        assertTrue(h.swerve.fusedTimes.isEmpty())
        h.poll(1200L, h.frame(1100L, "a"))
        assertEquals(2, h.clockReads)
        assertEquals(listOf(9.9), h.swerve.fusedTimes)
    }

    @Test fun `fusion disabled observation does not call estimator clocks or history`() {
        val h = Harness()
        h.tracker.fusionEnabled = false
        h.clock = { error("unneeded fallback clock") }
        h.converter = { error("unneeded native conversion") }
        h.swerve.history = { _, _ -> error("unneeded history") }
        assertDoesNotThrow { h.poll(1000L, h.frame(captureMicros = 2_000_000L)) }
        assertEquals(0, h.clockReads)
        assertEquals(0, h.conversions)
        assertTrue(h.swerve.historyTimes.isEmpty())
        assertTrue(h.swerve.fusedTimes.isEmpty())
        assertEquals("FUSION_DISABLED", h.tracker.lastVisionStatus)
        assertEquals(1, h.batchSize)
    }

    @Test fun `partial history success cannot reuse components from the prior sample`() {
        val h = Harness()
        h.swerve.history = { _, out -> out[0] = 2.0; out[1] = 2.0; out[2] = 0.0; true }
        h.poll(1000L, h.frame(x = 2.0, y = 2.0))
        assertEquals(1, h.swerve.fusedTimes.size)
        h.swerve.history = { _, out -> out[0] = 3.0; true }
        h.poll(1200L, h.frame(1100L, x = 3.0, y = 2.0))
        assertEquals(1, h.swerve.fusedTimes.size)
        assertEquals("REJECTED_RESIDUAL", h.tracker.lastVisionStatus)
    }

    @Test fun `nonfinite history is unavailable and uses the current finite estimate`() {
        for (component in 0..2) for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            val h = Harness()
            h.swerve.history = { _, out ->
                out[0] = 0.0; out[1] = 0.0; out[2] = 0.0
                out[component] = bad
                true
            }
            h.poll(1000L, h.frame())
            assertEquals(1, h.swerve.fusedTimes.size, "component=$component bad=$bad")
            assertEquals("ACCEPTED", h.tracker.lastVisionStatus)
        }
    }

    @Test fun `absent drivetrain does not read a vendor clock`() {
        val h = Harness(hasSwerve = false)
        h.clock = { error("no estimator exists") }
        assertDoesNotThrow { h.poll(1000L, h.frame()) }
        assertEquals(0, h.clockReads)
        assertEquals(0, h.conversions)
        assertEquals(1, h.batchSize)
        assertEquals("NO TARGET", h.tracker.lastVisionStatus)
    }

    @Test fun `native capture retains fractional seconds and is not latency corrected twice`() {
        val h = Harness()
        h.clock = { error("native capture does not need fallback") }
        h.poll(1000L, h.frame(700L, captureMicros = 2_345_678L))
        assertEquals(22.345678, h.swerve.fusedTimes.single(), 1e-12)
        assertEquals(h.swerve.fusedTimes, h.swerve.historyTimes)
        assertEquals(0, h.clockReads)
        assertEquals(1, h.conversions)
    }

    @Test fun `failed or nonfinite native conversions use the shared finite fallback`() {
        val converters: List<(Double) -> Double> = listOf(
            { Double.NaN }, { Double.POSITIVE_INFINITY }, { Double.NEGATIVE_INFINITY },
            { throw IllegalStateException("conversion unavailable") })
        for (converter in converters) {
            val h = Harness()
            h.converter = converter
            h.poll(1000L, h.frame(900L, "a", 2_000_000L), h.frame(800L, "b", 3_000_000L))
            assertEquals(listOf(9.9, 9.8), h.swerve.fusedTimes)
            assertEquals(h.swerve.fusedTimes, h.swerve.historyTimes)
            assertEquals(1, h.clockReads)
            assertEquals(2, h.conversions)
        }
    }

    @Test fun `history failure or false after partial writes falls back without retaining output`() {
        for (throws in listOf(false, true)) {
            val h = Harness()
            h.swerve.history = { _, out ->
                out[0] = 10.0; out[1] = 10.0; out[2] = 1.0
                if (throws) throw IllegalStateException("history unavailable")
                false
            }
            h.poll(1000L, h.frame())
            assertEquals(1, h.swerve.fusedTimes.size)
            h.swerve.history = { _, out -> out[0] = 10.0; true }
            h.poll(1200L, h.frame(1100L, x = 10.0, y = 10.0))
            assertEquals(1, h.swerve.fusedTimes.size)
        }
    }

    @Test fun `history query reuses storage and preserves wrapped historical heading`() {
        val h = Harness()
        var firstOutput: DoubleArray? = null
        h.swerve.history = { _, out ->
            if (firstOutput == null) firstOutput = out else assertSame(firstOutput, out)
            out[0] = 2.0; out[1] = 2.0; out[2] = Math.toRadians(179.0)
            true
        }
        val pose = Pose3d(Translation3d(2.0, 2.0, 0.0), Rotation3d(0.0, 0.0, Math.toRadians(-179.0)))
        h.poll(1000L, h.frame().copy(targetPose = pose, solverType = VisionSolverType.MEGATAG1))
        h.poll(1200L, h.frame(1100L).copy(targetPose = pose, solverType = VisionSolverType.MEGATAG1))
        assertEquals(2, h.swerve.fusedTimes.size)
        assertEquals(2, h.swerve.historyTimes.size)
    }

    @Test fun `rejected timestamp consumes the frame without retrying cached observations`() {
        val h = Harness()
        val frame = h.frame()
        h.clock = { Double.NaN }
        h.poll(1000L, frame)
        h.clock = { 10.0 }
        h.poll(1020L, frame)
        assertEquals(1, h.clockReads)
        assertTrue(h.swerve.fusedTimes.isEmpty())
        assertEquals("STALE_FRAME", h.tracker.lastVisionStatus)
        h.poll(1100L, h.frame(1050L))
        assertEquals(2, h.clockReads)
        assertEquals(listOf(9.95), h.swerve.fusedTimes)
    }

    @Test fun `empty stale and future out of tolerance frames do not read estimator time`() {
        val h = Harness()
        h.clock = { error("no eligible observations") }
        h.converter = { error("no eligible native capture") }
        h.poll(2000L)
        h.poll(2000L, h.frame(999L, "old", 2_000_000L), h.frame(2051L, "future", 2_000_000L))
        assertEquals(0, h.clockReads)
        assertEquals(0, h.conversions)
        assertTrue(h.swerve.historyTimes.isEmpty())
        assertEquals(0, h.batches)
    }

    @Test fun `fallback age arithmetic preserves inclusive limits near the long boundary`() {
        for (now in longArrayOf(2000L, Long.MAX_VALUE - 100L)) {
            for (age in longArrayOf(-50L, -1L, 0L, 1L, 100L, 999L, 1000L)) {
                val h = Harness()
                h.poll(now, h.frame(now - age))
                val expected = java.math.BigDecimal.TEN.subtract(
                    java.math.BigDecimal(age.coerceAtLeast(0L)).movePointLeft(3)).toDouble()
                assertEquals(expected, h.swerve.fusedTimes.single(), 1e-12, "now=$now age=$age")
                assertEquals(h.swerve.fusedTimes, h.swerve.historyTimes)
            }
        }
    }

    @Test fun `finite estimator epochs may include zero and negative seconds`() {
        for (clock in doubleArrayOf(0.0, -10.0)) {
            val h = Harness()
            h.clock = { clock }
            h.poll(1000L, h.frame())
            assertEquals(clock - 0.1, h.swerve.fusedTimes.single(), 1e-12)
        }
    }

    @Test fun `reenabling fusion obtains a new clock and preserves camera freshness`() {
        val h = Harness()
        h.poll(1000L, h.frame())
        h.tracker.fusionEnabled = false
        h.poll(1200L, h.frame(1100L))
        assertEquals(1, h.clockReads)
        assertEquals(1, h.swerve.fusedTimes.size)
        h.tracker.fusionEnabled = true
        h.poll(1220L, h.frame(1100L))
        assertEquals("STALE_FRAME", h.tracker.lastVisionStatus)
        h.clock = { 12.0 }
        h.poll(1400L, h.frame(1300L))
        assertEquals(2, h.clockReads)
        assertEquals(listOf(9.9, 11.9), h.swerve.fusedTimes)
    }
}
