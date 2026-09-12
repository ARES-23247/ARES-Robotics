package com.areslib.reducer

import com.areslib.Store
import com.areslib.action.ActionReplay
import com.areslib.action.ActionReplayException
import com.areslib.action.RobotAction
import com.areslib.hardware.vision.VisionFilterConfig
import com.areslib.math.estimation.PoseEstimatorSnapshot
import com.areslib.math.geometry.*
import com.areslib.state.*
import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path

class VisionDecisionAttributionAuditTest {
    @TempDir lateinit var directory: Path

    private fun store(history: Boolean = true, config: VisionFilterConfig = VisionFilterConfig()) = Store(RobotState(drive = DriveState(
        measuredMotionValid = true, imuMeasurementsValid = true,
        poseEstimator = PoseEstimatorSnapshot(covariance00 = 1e-4, covariance11 = 1e-4, covariance22 = 1e-4)),
        vision = VisionState(filterConfig = config))).also {
        if (history) it.dispatch(RobotAction.DriveHardwareUpdate(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 100L))
    }
    private fun frame(x: Double = 0.0) = VisionMeasurement(timestampMs = 100L, tagId = 1, tagCount = 2,
        sourceId = "camera", frameId = 1L, ambiguity = 0.01,
        targetPose = Pose3d(Translation3d(x, 0.0, 0.0), Rotation3d()),
        stdDevXMeters = 0.01, stdDevYMeters = 0.01, stdDevHeadingRadians = 0.01)
    private fun dispatch(store: Store, frames: List<VisionMeasurement>, index: Int, fuse: Boolean = true) =
        store.dispatchAndGetState(RobotAction.VisionMeasurementsReceived(frames, 110L,
            fuseIntoPoseEstimator = fuse, diagnosticMeasurementIndex = index)).vision

    @Test fun `selected rejection survives a later accepted result with identical packet identity`() {
        val result = dispatch(store(), listOf(frame(0.3), frame()), 0)
        assertTrue(result.lastMeasurementAccepted)
        assertEquals(0, result.diagnosticMeasurementIndex)
        assertFalse(result.diagnosticMeasurementAccepted)
        assertEquals("mahalanobis_rejected", result.diagnosticMeasurementRejectionReason)
        assertEquals(1, result.measurementCount)
        assertEquals(1, result.rejectionCount)
    }

    @Test fun `selected acceptance survives a later rejected result`() {
        val result = dispatch(store(), listOf(frame(), frame(0.3)), 0)
        assertFalse(result.lastMeasurementAccepted)
        assertTrue(result.diagnosticMeasurementAccepted)
        assertNull(result.diagnosticMeasurementRejectionReason)
    }

    @Test fun `prefilter removal retains the original requested input index`() {
        val result = dispatch(store(), listOf(frame().copy(tagCount = 0), frame(0.3), frame()), 2)
        assertEquals(2, result.diagnosticMeasurementIndex)
        assertTrue(result.diagnosticMeasurementAccepted)
        assertEquals(1, result.measurementCount)
        assertEquals(2, result.rejectionCount)
    }

    @Test fun `selected prefilter rejection is reported even when another packet is accepted`() {
        val result = dispatch(store(), listOf(frame().copy(ambiguity = 0.9), frame()), 0)
        assertTrue(result.lastMeasurementAccepted)
        assertFalse(result.diagnosticMeasurementAccepted)
        assertEquals("prefilter_rejected", result.diagnosticMeasurementRejectionReason)
    }

    @Test fun `early estimator rejection is associated without inventing an innovation`() {
        val result = dispatch(store(history = false), listOf(frame()), 0)
        assertEquals("empty_history", result.diagnosticMeasurementRejectionReason)
        assertFalse(result.diagnosticMeasurementAccepted)
        assertEquals(0, result.lastNisDegreesOfFreedom)
    }

    @Test fun `late selected observation cannot borrow a later observation acceptance`() {
        val result = dispatch(store(), listOf(frame().copy(timestampMs = 1L), frame()), 0)
        assertEquals("vision_too_old", result.diagnosticMeasurementRejectionReason)
        assertTrue(result.lastMeasurementAccepted)
    }

    @Test fun `external routing reports selected filter result without local fusion`() {
        val store = store()
        val before = store.state.drive.poseEstimator
        val result = dispatch(store, listOf(frame().copy(tagCount = 0), frame(0.3)), 0, fuse = false)
        assertEquals("external_filter_rejected", result.diagnosticMeasurementRejectionReason)
        assertEquals(before, store.state.drive.poseEstimator)
        assertTrue(dispatch(store, listOf(frame(0.3)), 0, fuse = false).diagnosticMeasurementAccepted)
        assertEquals(before, store.state.drive.poseEstimator)
    }

    @Test fun `unrequested invalid empty and reset results clear prior attribution`() {
        val store = store()
        val first = dispatch(store, listOf(frame()), 0)
        for (index in intArrayOf(-1, -2, 1, Int.MAX_VALUE)) {
            val result = dispatch(store, listOf(frame()), index)
            assertEquals(-1, result.diagnosticMeasurementIndex)
            assertFalse(result.diagnosticMeasurementAccepted)
            assertNull(result.diagnosticMeasurementRejectionReason)
        }
        assertEquals(-1, dispatch(store, emptyList(), 0).diagnosticMeasurementIndex)
        dispatch(store, listOf(frame()), 0)
        store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, 120L, isReset = true))
        assertEquals(-1, store.state.vision.diagnosticMeasurementIndex)
        assertFalse(store.state.vision.diagnosticMeasurementAccepted)
        assertTrue(first.diagnosticMeasurementAccepted)
    }

    @Test fun `requesting an outcome preserves batch order covariance and aggregate diagnostics`() {
        val frames = listOf(frame(0.001), frame(0.3), frame(-0.001), frame().copy(tagCount = 0))
        val reference = store()
        val expected = dispatch(reference, frames, -1)
        for (index in frames.indices) {
            val actual = store()
            val result = dispatch(actual, frames, index)
            assertEquals(reference.state.drive.poseEstimator, actual.state.drive.poseEstimator)
            assertEquals(expected, result.copy(diagnosticMeasurementIndex = -1,
                diagnosticMeasurementAccepted = false, diagnosticMeasurementRejectionReason = null))
        }
    }

    @Test fun `dispatch returns its committed snapshot even when a subscriber dispatches again`() {
        val store = store()
        var nested = false
        store.subscribe {
            if (!nested) {
                nested = true
                store.dispatch(RobotAction.VisionMeasurementsReceived(emptyList(), 120L))
            }
        }
        val result = dispatch(store, listOf(frame()), 0)
        assertEquals(0, result.diagnosticMeasurementIndex)
        assertTrue(result.diagnosticMeasurementAccepted)
        assertEquals(-1, store.state.vision.diagnosticMeasurementIndex)
        assertNotSame(result, store.state.vision)
    }

    private fun envelope() = ActionReplay.encodeForLog(RobotAction.VisionMeasurementsReceived(
        listOf(frame()), 110L, diagnosticMeasurementIndex = 0)).let { encoded ->
        JsonObject().apply {
            addProperty("schema_version", ActionReplay.SCHEMA_VERSION)
            addProperty("type", encoded.type)
            add("payload", encoded.payload)
        }
    }
    private fun decode(json: JsonObject): RobotAction.VisionMeasurementsReceived {
        val file = directory.resolve("vision.jsonl").toFile()
        file.writeText(json.toString() + "\n")
        return ActionReplay.parseActions(file).single() as RobotAction.VisionMeasurementsReceived
    }

    @Test fun `legacy schema one vision logs default to no selected diagnostic`() {
        val json = envelope()
        json.getAsJsonObject("payload").remove("diagnosticMeasurementIndex")
        assertEquals(-1, decode(json).diagnosticMeasurementIndex)
    }

    @Test fun `selected diagnostics round trip and replay with the same estimator result`() {
        val action = decode(envelope())
        assertEquals(0, action.diagnosticMeasurementIndex)
        val live = store().dispatchAndGetState(RobotAction.VisionMeasurementsReceived(listOf(frame()), 110L,
            diagnosticMeasurementIndex = 0))
        assertEquals(live, store().dispatchAndGetState(action))
    }

    @Test fun `diagnostic selector rejects null fractional and overflowing log values`() {
        for (value in listOf("null", "0.5", "2147483648", "\"0\"")) {
            val json = envelope()
            json.getAsJsonObject("payload").add("diagnosticMeasurementIndex", com.google.gson.JsonParser.parseString(value))
            assertThrows(ActionReplayException::class.java) { decode(json) }
        }
    }

    @Test fun `accepted unavailable ambiguity observations remain in the published vision buffer`() {
        for (unknown in doubleArrayOf(Double.NaN, -1.0, 5.0)) {
            val frame = frame().copy(ambiguityAvailable = false, ambiguity = unknown)
            val result = dispatch(store(), listOf(frame), 0)
            assertTrue(result.diagnosticMeasurementAccepted)
            assertEquals(1, result.measurements.size)
            assertFalse(result.measurements.single().ambiguityAvailable)
        }
    }

    @Test fun `vision buffer respects the configured inclusive ambiguity limit`() {
        for ((limit, ambiguity) in listOf(0.2 to 0.2, 0.5 to 0.3)) {
            val result = dispatch(store(config = VisionFilterConfig(maxAmbiguity = limit)),
                listOf(frame().copy(ambiguity = ambiguity)), 0)
            assertTrue(result.diagnosticMeasurementAccepted)
            assertEquals(1, result.measurements.size)
            assertEquals(ambiguity, result.measurements.single().ambiguity, 0.0)
        }
    }

    @Test fun `direct reducer calls still reject malformed available ambiguity and invalid configuration`() {
        for (ambiguity in doubleArrayOf(-0.1, 0.3, Double.NaN, Double.POSITIVE_INFINITY)) {
            val result = VisionReducer.reduce(VisionState(), RobotAction.VisionMeasurementsReceived(
                listOf(frame().copy(ambiguity = ambiguity)), 110L))
            assertTrue(result.measurements.isEmpty())
        }
        val result = VisionReducer.reduce(VisionState(filterConfig = VisionFilterConfig(maxAmbiguity = Double.NaN)),
            RobotAction.VisionMeasurementsReceived(listOf(frame().copy(ambiguityAvailable = false)), 110L))
        assertTrue(result.measurements.isEmpty())
    }
}
