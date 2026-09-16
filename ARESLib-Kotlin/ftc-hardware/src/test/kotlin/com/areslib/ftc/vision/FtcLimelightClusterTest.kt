package com.areslib.ftc.vision

import com.areslib.Store
import com.areslib.hardware.vision.*
import com.areslib.reducer.rootReducer
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import com.qualcomm.hardware.limelightvision.*
import org.firstinspires.ftc.robotcore.external.navigation.*
import kotlin.test.*

class FtcLimelightClusterTest {
    private val config = AprilTagCluster("cell", listOf(
        AprilTagClusterMember(30, .2, -.1, .15), AprilTagClusterMember(31, -.2, -.1, .15)))
    private class Camera : Limelight3A() {
        var connected = true
        var failure = false
        var stops = 0
        override fun isConnected() = connected
        override fun getLatestResult(): LLResult? {
            if (failure) error("camera failure")
            return simulatedResult
        }
        override fun stop() { stops++ }
    }
    private fun tag(id: Int, x: Double, unit: DistanceUnit = DistanceUnit.METER): LLResultTypes.FiducialResult =
        object : LLResultTypes.FiducialResult(id, 0.0, 0.0, Pose3D()) {
            override fun getTargetPoseCameraSpace() = Pose3D(
                Position(unit, unit.fromUnit(DistanceUnit.METER, x), unit.fromUnit(DistanceUnit.METER, .1),
                    unit.fromUnit(DistanceUnit.METER, 2.85), 0),
                YawPitchRollAngles(AngleUnit.RADIANS, 0.0, 0.0, 0.0, 0))
        }
    private fun frame(tags: List<LLResultTypes.FiducialResult>, stamp: Long = 1000,
        latency: Double = 20.0, fieldPose: Boolean = false): LLResult = object : LLResult(
        controlHubTimestampMs = stamp, captureLatency = latency) {
        override fun isValid() = true
        override fun getFiducialResults() = tags
        override fun getBotpose() = if (fieldPose) Pose3D() else null
        override fun getBotpose_MT2() = getBotpose()
    }
    @BeforeTest fun clock() { RobotClock.useMockTime(1000) }
    @AfterTest fun restore() { RobotClock.useSystemTime() }

    @Test fun `target-only result reaches Redux without a field pose or valid drive feedback`() {
        val camera = Camera()
        val io = FtcLimelightIO(camera, sourceId = "front")
        io.configureTargetClusters(listOf(config))
        camera.simulatedResult = frame(listOf(tag(30, -.2), tag(31, .2, DistanceUnit.INCH)))
        val store = Store(RobotState(), ::rootReducer)
        val tracker = FtcVisionTracker(store, io, null)
        val initial = store.state.drive.poseEstimator
        tracker.update(1000)
        val target = store.state.vision.clusterTargets.single()
        assertEquals(0.0, target.xMeters, 1e-10)
        assertEquals(0.0, target.yMeters, 1e-10)
        assertEquals(3.0, target.zMeters, 1e-10)
        assertEquals(980, target.timestampMs)
        assertEquals("front", target.sourceId)
        assertEquals(2, target.contributingTags)
        assertTrue(tracker.visionInputs.measurements.isEmpty())
        assertSame(initial, store.state.drive.poseEstimator)
        // Cached result neither invents a new timestamp nor replaces the immutable Redux snapshot.
        tracker.update(1010)
        assertSame(target, store.state.vision.clusterTargets.single())
        RobotClock.useMockTime(1231); tracker.update(1231)
        assertTrue(store.state.vision.clusterTargets.isEmpty())
        assertSame(initial, store.state.drive.poseEstimator)
        io.close()
    }

    @Test fun `mixed static and moving frame never leaks a combined MegaTag pose`() {
        val camera = Camera(); val io = FtcLimelightIO(camera)
        io.configureTargetClusters(listOf(config))
        camera.simulatedResult = frame(listOf(tag(30, -.2), tag(99, 1.0)), fieldPose = true)
        val input = VisionIOInputs(); io.updateInputs(input)
        assertEquals(1, input.clusterTargets.single().contributingTags)
        assertTrue(input.measurements.isEmpty())
        camera.simulatedResult = frame(listOf(tag(99, 1.0)), fieldPose = true)
        io.updateInputs(input)
        assertTrue(input.clusterTargets.isEmpty()); assertTrue(input.measurements.isEmpty())
        io.close()
    }

    @Test fun `lost tags disconnect exceptions invalid latency and close clear targets`() {
        val camera = Camera(); val io = FtcLimelightIO(camera)
        io.configureTargetClusters(listOf(config))
        val input = VisionIOInputs()
        fun acquire() {
            camera.connected = true; camera.failure = false
            camera.simulatedResult = frame(listOf(tag(30, -.2)))
            io.updateInputs(input); assertEquals(1, input.clusterTargets.size)
        }
        acquire(); camera.connected = false; io.updateInputs(input); assertTrue(input.clusterTargets.isEmpty())
        acquire(); camera.failure = true; io.updateInputs(input); assertTrue(input.clusterTargets.isEmpty())
        acquire(); camera.simulatedResult = frame(emptyList()); io.updateInputs(input); assertTrue(input.clusterTargets.isEmpty())
        for (latency in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 251.0)) {
            acquire(); camera.simulatedResult = frame(listOf(tag(30, -.2)), latency = latency)
            io.updateInputs(input); assertTrue(input.clusterTargets.isEmpty())
        }
        acquire(); camera.simulatedResult = frame(listOf(tag(30, -.2)), stamp = 2000)
        io.updateInputs(input); assertTrue(input.clusterTargets.isEmpty())
        acquire(); io.close(); io.close()
        assertTrue(input.clusterTargets.isEmpty()); assertFalse(input.isConnected); assertEquals(1, camera.stops)
        io.updateInputs(input); assertTrue(input.clusterTargets.isEmpty())
    }

    @Test fun `multiple cameras retain separate target sources and clear independently`() {
        val a = Camera(); val b = Camera()
        val front = FtcLimelightIO(a, sourceId = "front"); val rear = FtcLimelightIO(b, sourceId = "rear")
        val composite = CompositeVisionIO(listOf(front, rear))
        composite.configureTargetClusters(listOf(config))
        a.simulatedResult = frame(listOf(tag(30, -.2))); b.simulatedResult = frame(listOf(tag(31, .2)))
        val input = VisionIOInputs(); composite.updateInputs(input)
        assertEquals(listOf("front", "rear"), input.clusterTargets.map { it.sourceId })
        a.connected = false; composite.updateInputs(input)
        assertEquals("rear", input.clusterTargets.single().sourceId)
        composite.close(); assertTrue(input.clusterTargets.isEmpty())
    }

    @Test fun `duplicate cluster identities and membership fail at configuration`() {
        val io = FtcLimelightIO(Camera())
        assertFailsWith<IllegalArgumentException> { io.configureTargetClusters(listOf(config, config)) }
        assertFailsWith<IllegalArgumentException> {
            io.configureTargetClusters(listOf(config, AprilTagCluster("other", config.members)))
        }
        io.close()
    }
}
