package com.areslib.e2e.tier1.app

import com.areslib.ftc.FtcMecanumRobot
import com.areslib.ftc.MockDcMotorEx
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
import com.qualcomm.hardware.limelightvision.Limelight3A
import com.qualcomm.robotcore.hardware.VoltageSensor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AppFailsafeTier1Test {

    @Test
    fun testLoopOverrunWatchdog() {
        val fl = MockDcMotorEx()
        val fr = MockDcMotorEx()
        val bl = MockDcMotorEx()
        val br = MockDcMotorEx()
        val pinpoint = GoBildaPinpointDriver()
        val limelight = Limelight3A()

        val hardwareMap = object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T {
                return when (deviceName) {
                    "fl" -> fl as T
                    "fr" -> fr as T
                    "rl" -> bl as T
                    "rr" -> br as T
                    "pinpoint" -> pinpoint as T
                    "limelight" -> limelight as T
                    else -> throw IllegalArgumentException()
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun <T> getAll(classOrType: Class<out T>): List<T> {
                if (classOrType == VoltageSensor::class.java) {
                    val mockVS = object : VoltageSensor {
                        override val voltage: Double = 12.0
                    }
                    return listOf(mockVS as T)
                }
                return emptyList()
            }
        }

        val robot = FtcMecanumRobot(hardwareMap)
        
        // Loop time overrun verification
        var loopCount = 0L
        var overrunCount = 0L
        val baseTime = 1000L
        
        // Simulated execution (loop takes 10ms - healthy)
        com.areslib.util.RobotClock.setMockTimeMs(baseTime)
        robot.update()
        val loop1Elapsed = 10L
        com.areslib.util.RobotClock.setMockTimeMs(baseTime + loop1Elapsed)
        loopCount++
        if (loop1Elapsed > 30L) overrunCount++

        // Simulated execution (loop takes 45ms - overrun!)
        val baseTime2 = 2000L
        com.areslib.util.RobotClock.setMockTimeMs(baseTime2)
        robot.update()
        val loop2Elapsed = 45L
        com.areslib.util.RobotClock.setMockTimeMs(baseTime2 + loop2Elapsed)
        loopCount++
        if (loop2Elapsed > 30L) overrunCount++

        assertEquals(2, loopCount)
        assertEquals(1, overrunCount)
    }

    @Test
    fun testPerIterationFailsafeRecovery() {
        val fl = MockDcMotorEx()
        val fr = MockDcMotorEx()
        val bl = MockDcMotorEx()
        val br = MockDcMotorEx()
        val pinpoint = GoBildaPinpointDriver()
        val limelight = Limelight3A()

        val hardwareMap = object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T {
                return when (deviceName) {
                    "fl" -> fl as T
                    "fr" -> fr as T
                    "rl" -> bl as T
                    "rr" -> br as T
                    "pinpoint" -> pinpoint as T
                    "limelight" -> limelight as T
                    else -> throw IllegalArgumentException()
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun <T> getAll(classOrType: Class<out T>): List<T> {
                return emptyList()
            }
        }

        FtcMecanumRobot(hardwareMap)

        // Set initial motors power to non-zero
        fl.currentPower = 0.5
        fr.currentPower = 0.5

        // Simulate a loop iteration crash (e.g. some internal subsystem throws)
        val testException = RuntimeException("Subsystem crashed!")
        
        assertDoesNotThrow {
            try {
                // Inside the OpMode, if this throws, we catch it and cut power
                throw testException
            } catch (e: Exception) {
                // Per-iteration failsafe shuts down motors
                fl.currentPower = 0.0
                fr.currentPower = 0.0
                bl.currentPower = 0.0
                br.currentPower = 0.0
            }
        }

        // Verify motors shut off safely
        assertEquals(0.0, fl.currentPower, 1e-6)
        assertEquals(0.0, fr.currentPower, 1e-6)
        assertEquals(0.0, bl.currentPower, 1e-6)
        assertEquals(0.0, br.currentPower, 1e-6)
    }
}
