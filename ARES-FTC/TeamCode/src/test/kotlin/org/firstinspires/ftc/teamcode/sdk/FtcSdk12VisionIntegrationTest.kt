package org.firstinspires.ftc.teamcode.sdk

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.ftc.vision.FtcAprilTagLibraryFactory
import com.areslib.ftc.vision.FtcVisionPortalIO
import com.areslib.ftc.vision.FtcVisionTracker
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Translation3d
import com.areslib.reducer.rootReducer
import com.areslib.state.*
import com.areslib.util.RobotClock
import org.firstinspires.ftc.robotcore.external.matrices.VectorF
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion
import org.firstinspires.ftc.vision.apriltag.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/** Real SDK 12 POJOs and processor signature, without camera hardware or native image processing. */
class FtcSdk12VisionIntegrationTest {
    private lateinit var original: RobotFieldConfig
    private lateinit var field: RobotFieldConfig

    @Before fun prepare() {
        original = RobotFieldManager.activeConfig
        RobotClock.useMockTime(1000)
        field = RobotFieldConfig(apriltags=listOf(RobotFieldAprilTag(
            id=1,family="36h11",sizeMeters=0.1651,x=1.0,z=0.7493,roll=-90.0,yaw=-90.0,
        )))
        RobotFieldManager.setActiveConfig(field)
    }
    @After fun restore() {
        RobotFieldManager.setActiveConfig(original)
        RobotClock.useSystemTime()
    }
    private fun singleton(): AprilTagSingleDetection = AprilTagSingleDetection(
        1,0,100f,null,null,FtcAprilTagLibraryFactory.create(field).lookupTag(1),
        AprilTagPoseFtc(0.0,1.0,0.3493,0.0,0.0,0.0,1.0,0.0,0.0),null,null,
        900_000_000L,DistanceUnit.METER,
    )
    private fun tracker(detections: ArrayList<AprilTagDetection>): Pair<Store,FtcVisionTracker> {
        val processor = Mockito.mock(AprilTagProcessor::class.java)
        Mockito.`when`(processor.freshDetections).thenReturn(detections)
        val store = Store(RobotState(drive=DriveState(measuredMotionValid=true,imuMeasurementsValid=true)),::rootReducer)
        store.dispatch(RobotAction.PoseUpdate(0.0,0.0,0.0,800L))
        store.dispatch(RobotAction.PoseUpdate(0.0,0.0,0.0,1000L))
        val io = FtcVisionPortalIO(processor,listOf(Pose3d(Translation3d(0.0,0.0,0.4))),setOf(1))
        return store to FtcVisionTracker(store,io,null).apply { hasInitializedPoseWithVision = true }
    }

    @Test fun realSdkSingletonReachesNormalReduxLocalization() {
        val (store,tracker) = tracker(arrayListOf(singleton()))
        tracker.update(1000L)
        assertEquals("ACCEPTED",tracker.lastVisionStatus)
        assertEquals(1,store.state.vision.measurements.size)
        assertTrue(store.state.drive.poseEstimator.lastMeasurementAccepted)
        assertEquals(0.0,store.state.drive.poseEstimator.estimatedPose.x,1e-6)
        assertEquals(0.0,store.state.drive.poseEstimator.estimatedPose.y,1e-6)
    }

    @Test fun realSdkMixedClusterFrameCannotLocalizeOrReseed() {
        val members = arrayListOf(
            AprilTagClusterMemberMetadata(101,VectorF(-3f,0f,0f),6.5),
            AprilTagClusterMemberMetadata(102,VectorF(3f,0f,0f),6.5),
        )
        val metadata = AprilTagClusterMetadata(members,"Red goal","red-goal",VectorF(0f,0f,0f),
            DistanceUnit.INCH,Quaternion(1f,0f,0f,0f,0L))
        val cluster = AprilTagClusterDetection(50,metadata,DistanceUnit.METER,
            AprilTagPoseFtc(0.1,2.0,0.3,0.0,0.0,0.0,2.0,0.0,0.0),null,null,900_000_000L)
        val (store,tracker) = tracker(arrayListOf(singleton(),cluster))
        tracker.hasInitializedPoseWithVision = false
        tracker.update(1000L)
        assertTrue(store.state.vision.measurements.isEmpty())
        assertFalse(tracker.hasInitializedPoseWithVision)
        assertEquals("red-goal",store.state.vision.clusterTargets.single().clusterId)
        assertEquals(0.0,store.state.drive.poseEstimator.estimatedPose.x,1e-9)
    }
}
