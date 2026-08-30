package com.areslib.ftc.drivetrain

import com.areslib.Store
import com.areslib.control.tuning.PIDFCoefficients
import com.areslib.ftc.MockDcMotorEx
import com.areslib.hardware.HardwareRegistry
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.state.DriveTuningState
import com.areslib.state.TuningState
import com.areslib.subsystem.DriveSubsystem
import com.qualcomm.robotcore.hardware.HardwareMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MecanumTrajectoryFollowerTest {
    private val hardwareRegistry = HardwareRegistry()

    @AfterTest
    fun tearDown() {
        hardwareRegistry.closeAll()
        hardwareRegistry.clear()
    }

    @Test
    fun `request edge starts one task and cancellation returns to idle`() {
        val store = Store()
        val hardware = MecanumHardwareIO(motorHardwareMap(), hardwareRegistry)
        val follower = MecanumTrajectoryFollower(DriveSubsystem(store))
        val target = Pose2d(1.0, 0.5, Rotation2d(0.25))

        follower.driveToPose(store, hardware, target, isRequested = true)
        val active = assertNotNull(follower.activePathfindTask)
        assertTrue(follower.wasPathfindRequested)

        follower.driveToPose(store, hardware, target, isRequested = true)
        assertTrue(active === follower.activePathfindTask, "a held request must not recreate its task")

        follower.driveToPose(store, hardware, target, isRequested = false)
        assertFalse(follower.wasPathfindRequested)
        assertNull(follower.activePathfindTask)
    }

    @Test
    fun `active follower consumes the current typed path gains`() {
        val store = Store()
        val hardware = MecanumHardwareIO(motorHardwareMap(), hardwareRegistry)
        val follower = MecanumTrajectoryFollower(DriveSubsystem(store))
        val translation = PIDFCoefficients(3.1, 0.2, 0.4)
        val rotation = PIDFCoefficients(4.2, 0.3, 0.5)

        follower.driveToPose(
            store,
            hardware,
            Pose2d(0.5, 0.0, Rotation2d(0.0)),
            isRequested = true,
        )
        follower.updateTuning(
            TuningState(
                drive = DriveTuningState(
                    pathTranslationGains = translation,
                    pathRotationGains = rotation,
                ),
            ),
        )

        assertEquals(translation.kP, follower.pathfindFollower.xController.p)
        assertEquals(translation.kI, follower.pathfindFollower.xController.i)
        assertEquals(translation.kD, follower.pathfindFollower.xController.d)
        assertEquals(translation.kP, follower.pathfindFollower.yController.p)
        assertEquals(rotation.kP, follower.pathfindFollower.thetaController.p)
        assertEquals(rotation.kI, follower.pathfindFollower.thetaController.i)
        assertEquals(rotation.kD, follower.pathfindFollower.thetaController.d)
    }

    private fun motorHardwareMap(): HardwareMap {
        val motors = Array(4) { MockDcMotorEx() }
        return object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T = when (deviceName) {
                "fl" -> motors[0] as T
                "fr" -> motors[1] as T
                "rl" -> motors[2] as T
                "rr" -> motors[3] as T
                else -> throw IllegalArgumentException("Unknown motor $deviceName")
            }

            override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
        }
    }
}
