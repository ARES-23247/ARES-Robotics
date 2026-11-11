package com.areslib.ftc.vision

import com.areslib.Store
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.math.geometry.*
import com.areslib.reducer.rootReducer
import com.areslib.state.*
import com.areslib.util.RobotClock
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.vision.apriltag.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.test.*

class FtcVisionPortalSdk12Test {
    private class Processor : AprilTagProcessor() {
        var frame: ArrayList<AprilTagDetection>? = arrayListOf()
        var reads = 0
        var failure = false
        override val freshDetections: ArrayList<AprilTagDetection>?
            get() { reads++; check(!failure); return frame }
    }
    private val processor = Processor()
    private val inputs = VisionIOInputs()
    private lateinit var previousField: RobotFieldConfig
    private val tag = RobotFieldAprilTag(id=1, x=3.0, y=2.0, z=1.0, yaw=180.0)

    @BeforeEach fun prepare() {
        RobotClock.useMockTime(1000L)
        previousField = RobotFieldManager.activeConfig
        RobotFieldManager.setActiveConfig(RobotFieldConfig(apriltags=listOf(tag)))
    }
    @AfterEach fun restore() {
        RobotFieldManager.setActiveConfig(previousField)
        RobotClock.useSystemTime()
    }
    private fun singleton(pose: AprilTagPoseFtc? = AprilTagPoseFtc(y=2.0, z=0.6),
                          time: Long = 900_000_000L, unit: DistanceUnit = DistanceUnit.METER, id: Int = 1) =
        AprilTagSingleDetection(id, pose, time, unit)
    private fun cluster() = AprilTagClusterDetection(25, AprilTagClusterMetadata("Red cell", "red-cell"),
        DistanceUnit.CM, AprilTagPoseFtc(x=10.0, y=200.0, z=30.0), 900_000_000L)
    private fun io(mount: Pose3d = Pose3d(Translation3d(0.2,0.1,0.4)), angles: AngleUnit = AngleUnit.DEGREES) =
        FtcVisionPortalIO(processor, listOf(mount), setOf(1), angles, "front-camera", FtcTagPoseConvention.WPILIB_NWU)

    @Test fun `field localization needs explicit stationary review and measured mounting`() {
        processor.frame = arrayListOf(singleton())
        FtcVisionPortalIO(processor).updateInputs(inputs)
        assertTrue(inputs.measurements.isEmpty())
        assertFailsWith<IllegalArgumentException> { FtcVisionPortalIO(processor, localizationTagIds=setOf(1)) }
        assertFailsWith<IllegalArgumentException> { io(Pose3d(Translation3d(Double.NaN,0.0,0.0))) }
    }

    @Test fun `FTC right forward up axes and camera offset produce the known robot pose`() {
        processor.frame = arrayListOf(singleton(AprilTagPoseFtc(x=0.5,y=2.0,z=0.6)))
        io().updateInputs(inputs)
        val m = inputs.measurements.single()
        assertEquals(0.8, m.targetPose.x, 1e-9)
        assertEquals(2.4, m.targetPose.y, 1e-9)
        assertEquals(0.0, m.targetPose.z, 1e-9)
        assertEquals(0.0, wrap(m.targetPose.rotation.z), 1e-9)
        assertEquals(900L, m.timestampMs)
        assertEquals(900_000L, m.captureTimestampMicros)
        assertEquals(900_000_000L, m.frameId)
        assertEquals(100.0, m.latencyMs)
        assertFalse(m.ambiguityAvailable)
        assertEquals(1, processor.reads)
        assertEquals(-0.4, m.robotPoseTargetSpace.x, 1e-9)
        assertEquals(2.2, m.robotPoseTargetSpace.z, 1e-9)
    }

    @Test fun `positive field yaw and target alignment preserve CCW heading with radian output`() {
        processor.frame = arrayListOf(singleton(AprilTagPoseFtc(y=2.0,z=1.0,yaw=-PI/6)))
        io(Pose3d(), AngleUnit.RADIANS).updateInputs(inputs)
        val m = inputs.measurements.single()
        assertEquals(3.0-kotlin.math.sqrt(3.0), m.targetPose.x, 1e-9)
        assertEquals(1.0, m.targetPose.y, 1e-9)
        assertEquals(PI/6, m.targetPose.rotation.z, 1e-9)
        assertEquals(PI/6, -m.robotPoseTargetSpace.rotation.y, 1e-9)
    }

    @Test fun `inch and meter detections are equivalent`() {
        val camera = io()
        processor.frame = arrayListOf(singleton(AprilTagPoseFtc(y=2.0,z=0.6)))
        camera.updateInputs(inputs)
        val expected = inputs.measurements.single().ownedCopy()
        processor.frame = arrayListOf(singleton(AprilTagPoseFtc(y=2.0/0.0254,z=0.6/0.0254), unit=DistanceUnit.INCH))
        camera.updateInputs(inputs)
        assertEquals(expected.targetPose.x, inputs.measurements.single().targetPose.x, 1e-9)
        assertEquals(expected.averageTagDistanceMeters, inputs.measurements.single().averageTagDistanceMeters, 1e-9)
    }

    @Test fun `clusters and mixed frames can only publish camera relative aiming targets`() {
        val camera = io()
        processor.frame = arrayListOf(singleton(), cluster())
        camera.updateInputs(inputs)
        assertTrue(inputs.measurements.isEmpty())
        val target = inputs.clusterTargets.single().snapshot()
        assertEquals("red-cell", target.clusterId)
        assertEquals(0.1, target.xMeters, 1e-9)
        assertEquals(-0.3, target.yMeters, 1e-9)
        assertEquals(2.0, target.zMeters, 1e-9)
        assertTrue(target.isFresh(1000))
        processor.frame = arrayListOf(singleton())
        camera.updateInputs(inputs)
        assertTrue(inputs.measurements.isEmpty(), "A targeting camera must not later leak field poses")
        assertTrue(inputs.clusterTargets.isEmpty())
    }

    @Test fun `cluster observations reach targeting Redux state without reseeding localization`() {
        processor.frame = arrayListOf(singleton(), cluster())
        val store = Store(RobotState(), ::rootReducer)
        val before = store.state.drive.poseEstimator.estimatedPose
        var reseeds = 0
        val tracker = FtcVisionTracker(store, io(), null, onOdometryReseed={ reseeds++ })
        tracker.update(1000L)
        assertEquals("red-cell", store.state.vision.clusterTargets.single().clusterId)
        assertEquals(before, store.state.drive.poseEstimator.estimatedPose)
        assertEquals(0, reseeds)
        assertTrue(store.state.vision.measurements.isEmpty())
    }

    @Test fun `null missing stale future and nonfinite detections never replay observations`() {
        val camera = io()
        processor.frame = arrayListOf(singleton(), singleton(null), singleton(time=0),
            singleton(time=1_100_000_000L), singleton(time=1L), singleton(AprilTagPoseFtc(y=Double.NaN)))
        camera.updateInputs(inputs)
        assertEquals(1, inputs.measurements.size)
        processor.frame = null
        camera.updateInputs(inputs)
        assertTrue(inputs.isConnected)
        assertTrue(inputs.measurements.isEmpty())
        processor.failure = true
        camera.updateInputs(inputs)
        assertFalse(inputs.isConnected)
        assertTrue(inputs.measurements.isEmpty())
        assertTrue(inputs.clusterTargets.isEmpty())
    }

    @Test fun `pooled observations never alias within a capped frame`() {
        processor.frame = ArrayList(List(40) { singleton() })
        io().updateInputs(inputs)
        assertEquals(32, inputs.measurements.size)
        for (i in inputs.measurements.indices) for (j in 0 until i)
            assertNotSame(inputs.measurements[i], inputs.measurements[j])
    }

    @Test fun `tilted camera and tag rotations reconstruct independently composed rigid poses`() {
        val mount = Pose3d(Translation3d(0.2,-0.1,0.4), Rotation3d(0.1,-0.2,0.3))
        val camera = io(mount, AngleUnit.RADIANS)
        val fieldTag = Pose3d(Translation3d(3.0,2.0,1.0), Rotation3d(0.2,0.1,PI))
        RobotFieldManager.setActiveConfig(RobotFieldConfig(apriltags=listOf(tag.copy(roll=Math.toDegrees(0.2),pitch=Math.toDegrees(0.1)))))
        for (index in 0 until 20) {
            val expected = Pose3d(Translation3d(0.1+index*0.02,0.3,0.0), Rotation3d(0.03,-0.02,index*0.01))
            val fieldCamera = expected.transformBy(Transform3d(mount.translation,mount.rotation))
            val inverseCamera = Transform3d(fieldCamera.translation, fieldCamera.rotation).inverse()
            val cameraTag = Pose3d(inverseCamera.translation, inverseCamera.rotation)
                .transformBy(Transform3d(fieldTag.translation,fieldTag.rotation))
            val unflipped = cameraTag.rotation * Rotation3d(0.0,0.0,PI)
            processor.frame = arrayListOf(singleton(AprilTagPoseFtc(x=-cameraTag.y,y=cameraTag.x,z=cameraTag.z,
                roll=unflipped.x,pitch=-unflipped.y,yaw=unflipped.z)))
            camera.updateInputs(inputs)
            val actual = inputs.measurements.single().targetPose
            assertEquals(expected.x,actual.x,1e-8); assertEquals(expected.y,actual.y,1e-8)
            assertEquals(expected.z,actual.z,1e-8); assertEquals(expected.rotation.z,actual.rotation.z,1e-8)
        }
    }

    private fun wrap(value: Double): Double = kotlin.math.atan2(kotlin.math.sin(value),kotlin.math.cos(value))

    @Test fun `singleton and cluster polling reuse workspaces without per frame allocations`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        RobotFieldManager.setActiveConfig(RobotFieldConfig(apriltags=listOf(tag.copy(id=1001))))
        val camera = FtcVisionPortalIO(processor,listOf(Pose3d()),setOf(1001),tagPoseConvention=FtcTagPoseConvention.WPILIB_NWU)
        processor.frame = arrayListOf(singleton(id=1001))
        for (variant in 0..1) {
            if (variant == 1) processor.frame = arrayListOf(cluster())
            repeat(30_000) { camera.updateInputs(inputs) }
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(20_000) { camera.updateInputs(inputs) }
            val allocated = bean.getThreadAllocatedBytes(thread) - before
            assertTrue(allocated <= 1024L,"Polling variant $variant allocated $allocated bytes")
            assertTrue(if (variant==0) inputs.measurements.isNotEmpty() else inputs.clusterTargets.isNotEmpty())
            println("VisionPortal adapter variant $variant: $allocated bytes / 20000 polls")
        }
    }

    @Test fun `maintained DECODE optical tag rotations localize without rewriting the field`() {
        val decode = RobotFieldAprilTag(id=20,x=-1.48266658,y=-1.4133195,z=0.7493,
            roll=-90.0000032,yaw=144.0499447)
        RobotFieldManager.setActiveConfig(RobotFieldConfig(apriltags=listOf(decode)))
        processor.frame = arrayListOf(singleton(AprilTagPoseFtc(y=1.0,z=0.3493),id=20))
        FtcVisionPortalIO(processor,listOf(Pose3d(Translation3d(0.0,0.0,0.4))),setOf(20)).updateInputs(inputs)
        val pose = inputs.measurements.single().targetPose
        val outward = Math.toRadians(decode.yaw-90.0)
        assertEquals(decode.x+kotlin.math.cos(outward),pose.x,1e-7)
        assertEquals(decode.y+kotlin.math.sin(outward),pose.y,1e-7)
        assertEquals(0.0,pose.z,1e-7)
        assertEquals(wrap(outward+PI),wrap(pose.rotation.z),1e-7)
        assertEquals(decode,RobotFieldManager.activeConfig.apriltags.single())
    }

    @Test fun `negative and wrapped nanoTime intervals are accepted via subtraction`() {
        val wrappedMs = 9_223_372_036_855L // positive ms whose *1_000_000 wraps signed 64-bit nanoTime negative
        RobotClock.useMockTime(wrappedMs)
        val nowNs = RobotClock.nanoTime()
        assertTrue(nowNs < 0L)
        val capturedNs = nowNs - 100_000_000L
        processor.frame = arrayListOf(singleton(AprilTagPoseFtc(x = 0.5, y = 2.0, z = 0.6), time = capturedNs))
        io().updateInputs(inputs)
        val m = inputs.measurements.single()
        assertEquals(100.0, m.latencyMs, 1e-9)
        assertEquals(wrappedMs - 100L, m.timestampMs)
    }
}
