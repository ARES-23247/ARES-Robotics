package org.ares.biobuzz

import com.areslib.ftc.FtcBaseRobot
import com.areslib.networktables.NT4Instance
import com.areslib.sim.model.MecanumRobotDouble
import com.areslib.sim.opmode.SimOpModeRunner
import com.areslib.util.RobotClock
import com.qualcomm.hardware.limelightvision.LLResult
import com.qualcomm.hardware.limelightvision.LLResultTypes
import com.qualcomm.robotcore.hardware.DcMotorEx
import org.firstinspires.ftc.robotcore.external.navigation.*
import org.firstinspires.ftc.teamcode.opmodes.ARESStarterTeleOp
import org.firstinspires.ftc.teamcode.vision.BiobuzzTagClusters
import org.junit.After
import org.junit.Test
import kotlin.test.*

class BiobuzzClusterRuntimeTest {
    @After fun cleanup() { NT4Instance.defaultInstance.closeServer(); RobotClock.useSystemTime() }

    @Test fun `official clusters preserve cell identity when individual tags disappear`() {
        assertEquals(82.55, BiobuzzTagClusters.TAG_SIZE_MILLIMETERS)
        assertEquals((30..45).toList(), BiobuzzTagClusters.clusters.flatMap { it.members.map { m -> m.tagId } })
        assertEquals(listOf("red-scoring", "red-audience", "blue-audience", "blue-scoring"),
            BiobuzzTagClusters.clusters.map { it.id })
        // Reference SDK member positions are in inches in the raw tag plane. These check the
        // sign, origin offset, and physical size independently of the generic geometry fixtures.
        val first = BiobuzzTagClusters.clusters.first().members.first()
        assertEquals(.1651, first.offsetX, 1e-12)
        assertEquals(-.18255996, first.offsetY, 1e-12)
        assertEquals(.1427988, first.offsetZ, 1e-12)
    }

    @Test fun `generated BioBuzz robot accepts moving target feedback while mechanisms run and stops safely`() {
        RobotClock.useMockTime(1000)
        val hardware = MecanumRobotDouble()
        val opMode = ARESStarterTeleOp()
        val lifecycle = requireNotNull(SimOpModeRunner.createOpModeInstance(opMode, null))
        try {
            lifecycle.initialize(hardware.hardwareMap)
            val robot = requireNotNull(FtcBaseRobot.activeInstance)
            assertNotNull(robot.limelightIO, "Generated descriptor must construct the targeting camera")
            fun sample(visible: List<Int>, pointX: Double) {
                RobotClock.useMockTime(RobotClock.currentTimeMillis() + 20)
                hardware.updateSensors(.02, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
                val tags = visible.map { index ->
                    val x = listOf(-6.5, -2.75, 2.75, 6.5)[index] * .0254
                    object : LLResultTypes.FiducialResult(30 + index, 0.0, 0.0, Pose3D()) {
                        override fun getTargetPoseCameraSpace() = Pose3D(
                            Position(DistanceUnit.METER, pointX + x, .18255996, 3.0 - .1427988, 0),
                            YawPitchRollAngles(AngleUnit.RADIANS, 0.0, 0.0, 0.0, 0))
                    }
                }
                hardware.limelight.setLatestResult(object : LLResult() {
                    override fun isValid() = true
                    override fun getFiducialResults() = tags
                    // A bad field map must not be able to pull the estimator to this moving target.
                    override fun getBotpose() = Pose3D(Position(DistanceUnit.METER, 1.5, 1.5, 0.0, 0),
                        YawPitchRollAngles(AngleUnit.RADIANS, 1.0, 0.0, 0.0, 0))
                })
                lifecycle.tick()
            }
            sample(listOf(0, 1, 2, 3), 0.0)
            assertEquals("red-scoring", robot.store.state.vision.clusterTargets.single().clusterId)
            val estimatorX = robot.store.state.drive.poseEstimator.estimatedPose.x
            lifecycle.start()
            opMode.gamepad1.a = true
            opMode.gamepad1.b = true
            repeat(8) { sample(listOf(it % 4), .1 * it) }
            val target = robot.store.state.vision.clusterTargets.single()
            assertEquals(.7, target.xMeters, 1e-10)
            assertEquals(1, target.contributingTags)
            assertEquals(estimatorX, robot.store.state.drive.poseEstimator.estimatedPose.x, 1e-9)
            val intake = hardware.hardwareMap.get(DcMotorEx::class.java, "intake")
            val flywheel = hardware.hardwareMap.get(DcMotorEx::class.java, "flywheel")
            assertTrue(intake.power > 0.0)
            assertTrue(flywheel.power > 0.0)
            sample(emptyList(), 0.0)
            assertTrue(robot.store.state.vision.clusterTargets.isEmpty())
            lifecycle.stop()
            assertEquals(0.0, intake.power)
            assertEquals(0.0, flywheel.power)
            assertEquals(0.0, hardware.fl.power)
        } finally {
            lifecycle.stop()
        }
    }
}
