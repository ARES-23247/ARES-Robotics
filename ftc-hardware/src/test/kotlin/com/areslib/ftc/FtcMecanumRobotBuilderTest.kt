package com.areslib.ftc

import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.VoltageSensor
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
import com.qualcomm.hardware.limelightvision.Limelight3A
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FtcMecanumRobotBuilderTest {

    @Test
    fun `test ftcMecanumRobot builder with custom names`() {
        val fl = MockDcMotorEx()
        val fr = MockDcMotorEx()
        val rl = MockDcMotorEx()
        val rr = MockDcMotorEx()
        val pinpoint = GoBildaPinpointDriver()
        val limelight = Limelight3A()

        val hardwareMap = object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T {
                return when (deviceName) {
                    "my_fl" -> fl as T
                    "my_fr" -> fr as T
                    "my_rl" -> rl as T
                    "my_rr" -> rr as T
                    "my_pinpoint" -> pinpoint as T
                    "my_limelight" -> limelight as T
                    else -> throw IllegalArgumentException("Unknown device name: $deviceName")
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun <T> getAll(classOrType: Class<out T>): List<T> {
                if (classOrType == VoltageSensor::class.java) {
                    val mockVoltageSensor = object : VoltageSensor {
                        override val voltage: Double = 12.5
                    }
                    return listOf(mockVoltageSensor as T)
                }
                return emptyList()
            }
        }

        val robot = ftcMecanumRobot(hardwareMap) {
            frontLeftMotorName = "my_fl"
            frontRightMotorName = "my_fr"
            rearLeftMotorName = "my_rl"
            rearRightMotorName = "my_rr"
            pinpointName = "my_pinpoint"
            limelightName = "my_limelight"
        }

        assertNotNull(robot)
        // Verify we can call update without issues
        robot.update()
    }
}
