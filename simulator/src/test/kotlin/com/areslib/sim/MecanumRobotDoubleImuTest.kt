package com.areslib.sim

import com.areslib.sim.model.MecanumRobotDouble
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class MecanumRobotDoubleImuTest {
    @Test
    fun `wheel encoder plus IMU simulation reports physical heading and angular velocity`() {
        val robot = MecanumRobotDouble()

        robot.updateSensors(
            dt = 0.02,
            actualVx = 0.0,
            actualVy = 0.0,
            actualOmega = -0.75,
            trueX = 1.0,
            trueY = 2.0,
            trueHeadingRad = 1.25,
        )

        assertEquals(
            1.25,
            robot.mockImu.getRobotYawPitchRollAngles().getYaw(AngleUnit.RADIANS),
            1e-9,
        )
        assertEquals(
            -0.75,
            robot.mockImu.getRobotAngularVelocity(AngleUnit.RADIANS)
                .getZRotationRate(AngleUnit.RADIANS).toDouble(),
            1e-6,
        )
        assertEquals(
            Math.toDegrees(-0.75),
            robot.mockImu.getRobotAngularVelocity(AngleUnit.DEGREES)
                .getZRotationRate(AngleUnit.DEGREES).toDouble(),
            1e-5,
        )
    }
}
