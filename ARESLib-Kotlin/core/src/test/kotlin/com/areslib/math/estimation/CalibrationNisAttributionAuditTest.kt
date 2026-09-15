package com.areslib.math.estimation

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.state.VisionMeasurement
import com.areslib.state.VisionSolverType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalibrationNisAttributionAuditTest {
    @Test
    fun `no local innovation cannot be recorded as a perfect zero NIS`() {
        val store = initializedStore()
        assertTrue(capture(store, emptyList()).nis.isNaN())
        val external = measurement()
        store.dispatch(RobotAction.VisionMeasurementsReceived(listOf(external), 110L, fuseIntoPoseEstimator = false))
        assertTrue(capture(store, listOf(external)).nis.isNaN())
    }

    @Test
    fun `a rejected prefilter frame cannot reuse a previous camera innovation`() {
        val store = initializedStore()
        val valid = measurement()
        store.dispatch(RobotAction.VisionMeasurementsReceived(listOf(valid), 110L))
        assertTrue(store.state.vision.lastMeasurementAccepted)
        assertTrue(capture(store, listOf(valid)).nis > 0.0)
        val bad = valid.copy(ambiguity = 1.0, frameId = 2L)
        store.dispatch(RobotAction.VisionMeasurementsReceived(listOf(bad), 120L))
        assertTrue(capture(store, listOf(bad)).nis.isNaN())
    }

    @Test
    fun `NIS degrees of freedom and distance belong to the fused packet rather than the raw list tail`() {
        val store = initializedStore()
        val valid = measurement()
        val badTail = valid.copy(sourceId = "other", frameId = 2L, ambiguity = 1.0,
            solverType = VisionSolverType.MEGATAG2,
            robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 5.0), Rotation3d()))
        val packets = listOf(valid, badTail)
        store.dispatch(RobotAction.VisionMeasurementsReceived(packets, 110L))
        assertTrue(store.state.vision.lastMeasurementAccepted)
        val sample = capture(store, packets)
        assertTrue(sample.nis > 0.0)
        assertEquals(3, sample.nisDegreesOfFreedom)
        assertEquals(1.0, sample.tagDistanceMeters, 1e-12)
    }

    @Test
    fun `computed rejected innovations remain available for unbiased consistency statistics`() {
        val store = initializedStore()
        store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, 100L, isReset = true))
        val packet = measurement().copy(targetPose = Pose3d(Translation3d(0.75, 0.0, 0.0), Rotation3d()))
        store.dispatch(RobotAction.VisionMeasurementsReceived(listOf(packet), 110L))
        assertEquals("mahalanobis_rejected", store.state.vision.lastRejectionReason)
        val sample = capture(store, listOf(packet))
        assertTrue(sample.nis > 12.0)
        assertEquals(false, sample.visionAccepted)
        assertEquals(3, sample.nisDegreesOfFreedom)
    }

    @Test
    fun `translation-only innovation retains two DOF and ambiguous identity is unavailable`() {
        val store = initializedStore()
        val packet = measurement().copy(solverType = VisionSolverType.MEGATAG2)
        store.dispatch(RobotAction.VisionMeasurementsReceived(listOf(packet), 110L))
        assertTrue(store.state.vision.lastMeasurementAccepted)
        val sample = capture(store, listOf(packet))
        assertTrue(sample.nis > 0.0)
        assertEquals(2, sample.nisDegreesOfFreedom)
        val ambiguous = capture(store, listOf(packet, packet.copy()))
        assertTrue(ambiguous.nis.isNaN())
        assertEquals(0, ambiguous.nisDegreesOfFreedom)
    }

    @Test
    fun `local estimator rejection before innovation does not retain earlier NIS`() {
        val store = initializedStore()
        val valid = measurement()
        store.dispatch(RobotAction.VisionMeasurementsReceived(listOf(valid), 110L))
        assertTrue(store.state.vision.lastNis > 0.0)
        val tooOld = valid.copy(timestampMs = 1L, frameId = 2L)
        store.dispatch(RobotAction.VisionMeasurementsReceived(listOf(tooOld), 120L))
        assertEquals(false, store.state.vision.lastMeasurementAccepted)
        assertTrue(capture(store, listOf(tooOld)).nis.isNaN())
        assertEquals(0, store.state.vision.lastNisDegreesOfFreedom)
    }

    @Test
    fun `published NIS identity survives packet reuse and reset invalidates association`() {
        val store = initializedStore()
        val packet = measurement()
        store.dispatch(RobotAction.VisionMeasurementsReceived(listOf(packet), 110L))
        val retained = store.state.vision
        assertTrue(retained.lastNis > 0.0)
        packet.sourceId = "recycled"
        assertEquals("primary", retained.lastNisSourceId)
        assertTrue(capture(store, listOf(packet)).nis.isNaN())
        packet.sourceId = "primary"
        store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, 120L, isReset = true))
        assertTrue(capture(store, listOf(packet)).nis.isNaN())
        assertEquals(3, retained.lastNisDegreesOfFreedom)
        assertEquals(0, store.state.vision.lastNisDegreesOfFreedom)
    }

    private fun initializedStore() = Store().also {
        it.dispatch(RobotAction.DriveHardwareUpdate(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 100L))
    }
    private fun measurement() = VisionMeasurement(timestampMs = 100L,
        targetPose = Pose3d(Translation3d(0.1, 0.0, 0.0), Rotation3d()), tagId = -1, ambiguity = 0.01,
        sourceId = "primary", frameId = 1L, solverType = VisionSolverType.MEGATAG1,
        robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 1.0), Rotation3d()))
    private fun capture(store: Store, packets: List<VisionMeasurement>) = LocalizationCalibrationSample.capture(
        120L, LocalizationCalibrationPlatform.FTC, LocalizationCalibrationTestType.VISION_STATIONARY,
        1, store.state, packets, truthValid = true, truthX = 0.0, truthY = 0.0, truthHeading = 0.0)
}
