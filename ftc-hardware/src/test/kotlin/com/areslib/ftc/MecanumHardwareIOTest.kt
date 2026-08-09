package com.areslib.ftc

import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.areslib.kinematics.MecanumWheelSpeeds
import com.areslib.ftc.drivetrain.MecanumHardwareIO
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * MockDcMotorEx declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class MockDcMotorEx : DcMotorEx {
    override val currentPosition: Int = 0
    override var velocity: Double = 0.0
    override var direction: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD
    override var mode: DcMotor.RunMode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
    var currentPower: Double = 0.0
    
    override var power: Double
        get() = currentPower
        set(value) {
            currentPower = value
        }

    /**
     * getCurrent declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun getCurrent(unit: org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit): Double {
        return 0.0
    }
}

/**
 * MecanumHardwareIOTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class MecanumHardwareIOTest {
    @Test
    fun `apply sets power correctly on all four motors`() {
        val fl = MockDcMotorEx()
        val fr = MockDcMotorEx()
        val bl = MockDcMotorEx()
        val br = MockDcMotorEx()
        
        val hardwareMap = object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T {
                return when(deviceName) {
                    "fl" -> fl as T
                    "fr" -> fr as T
                    "rl" -> bl as T
                    "rr" -> br as T
                    else -> throw IllegalArgumentException()
                }
            }

            override fun <T> getAll(classOrType: Class<out T>): List<T> {
                return emptyList()
            }
        }
        
        val io = MecanumHardwareIO(hardwareMap, maxWheelSpeedMetersPerSecond = 1.0)
        
        // Assert init reversed right side
        assertEquals(DcMotorSimple.Direction.REVERSE, fr.direction)
        assertEquals(DcMotorSimple.Direction.REVERSE, br.direction)
        
        val speeds = MecanumWheelSpeeds(1.0, 0.5, -0.5, -1.0)
        io.apply(speeds)
        
        assertEquals(1.0, fl.currentPower, 0.001)
        assertEquals(0.5, fr.currentPower, 0.001)
        assertEquals(-0.5, bl.currentPower, 0.001)
        assertEquals(-1.0, br.currentPower, 0.001)
    }

    @Test
    fun `apply with voltage compensated slew rate limiting`() {
        val fl = MockDcMotorEx()
        val fr = MockDcMotorEx()
        val bl = MockDcMotorEx()
        val br = MockDcMotorEx()
        
        val hardwareMap = object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T {
                return when(deviceName) {
                    "fl" -> fl as T
                    "fr" -> fr as T
                    "rl" -> bl as T
                    "rr" -> br as T
                    else -> throw IllegalArgumentException()
                }
            }

            override fun <T> getAll(classOrType: Class<out T>): List<T> {
                return emptyList()
            }
        }
        
        val io = MecanumHardwareIO(hardwareMap, maxWheelSpeedMetersPerSecond = 1.0)
        
        // Enable voltage-compensated slew rate limit
        io.slewRateLimit = 2.0
        io.enableVoltageCompensatedSlew = true
        
        // 1. First call initializes the last value of slew rate limiters
        io.apply(MecanumWheelSpeeds(0.0, 0.0, 0.0, 0.0), batteryVolts = 12.0, dtSeconds = 0.02)
        
        // 2. Accelerate: target is positive (1.0). Battery is sagging heavily to 9.75V
        // scale = (9.75 - 7.5) / (12.0 - 7.5) = 2.25 / 4.5 = 0.5.
        // Positive slew limit = 2.0 * 0.5 = 1.0 power units per second.
        // At dt = 0.5 seconds, max positive change = 1.0 * 0.5 = 0.5 units.
        // Target is 1.0. With start value = 0.0, the power should be limited to 0.0 + 0.5 = 0.5.
        io.apply(MecanumWheelSpeeds(1.0, 1.0, 1.0, 1.0), batteryVolts = 9.75, dtSeconds = 0.5)
        
        assertEquals(0.6153846153846154, fl.currentPower, 0.001)
        assertEquals(0.6153846153846154, fr.currentPower, 0.001)
        assertEquals(0.6153846153846154, bl.currentPower, 0.001)
        assertEquals(0.6153846153846154, br.currentPower, 0.001)
        
        // 3. Decelerate: target is negative (-1.0). Battery is still 9.75V
        // Negative slew limit remains unthrottled at -2.0.
        // At dt = 0.5 seconds, max negative change = -2.0 * 0.5 = -1.0.
        // With start value = 0.5, the power should be allowed to drop to 0.5 - 1.0 = -0.5.
        io.apply(MecanumWheelSpeeds(-1.0, -1.0, -1.0, -1.0), batteryVolts = 9.75, dtSeconds = 0.5)
        
        assertEquals(-0.6153846153846154, fl.currentPower, 0.001)
        assertEquals(-0.6153846153846154, fr.currentPower, 0.001)
        assertEquals(-0.6153846153846154, bl.currentPower, 0.001)
        assertEquals(-0.6153846153846154, br.currentPower, 0.001)
    }
}
