package com.areslib.ftc

import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.areslib.kinematics.MecanumWheelSpeeds
import com.areslib.ftc.drivetrain.MecanumHardwareIO
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MockDcMotorEx : DcMotorEx {
    override val currentPosition: Int = 0
    override var velocity: Double = 0.0
    override var direction: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD
    override var mode: DcMotor.RunMode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
    override var zeroPowerBehavior: DcMotor.ZeroPowerBehavior = DcMotor.ZeroPowerBehavior.FLOAT
    var currentPower: Double = 0.0
    var rejectPowerWrites: Boolean = false
    var rejectNextPowerWrite: Boolean = false
    
    override var power: Double
        get() = currentPower
        set(value) {
            if (rejectPowerWrites || rejectNextPowerWrite) {
                rejectNextPowerWrite = false
                throw IllegalStateException("simulated motor write failure")
            }
            currentPower = value
        }

    override fun getCurrent(unit: org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit): Double {
        return 0.0
    }
}

class MecanumHardwareIOTest {
    @Test
    fun `constructor applies one declared neutral mode to every motor`() {
        val motors = Array(4) { MockDcMotorEx() }

        MecanumHardwareIO(
            motorHardwareMap(motors),
            zeroPowerBehavior = DcMotor.ZeroPowerBehavior.BRAKE,
        )

        motors.forEach { motor ->
            assertEquals(DcMotor.ZeroPowerBehavior.BRAKE, motor.zeroPowerBehavior)
        }
    }

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
        io.kV = 1.0
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
        io.kV = 1.0
        
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

    @Test
    fun `power scale is applied once at the hardware boundary`() {
        val motors = Array(4) { MockDcMotorEx() }
        val hardwareMap = motorHardwareMap(motors)
        val io = MecanumHardwareIO(hardwareMap, maxWheelSpeedMetersPerSecond = 1.0)
        io.kV = 1.0

        io.apply(
            MecanumWheelSpeeds(1.0, 1.0, 1.0, 1.0),
            batteryVolts = 12.0,
            powerScale = 0.5
        )

        motors.forEach { assertEquals(0.5, it.currentPower, 1e-9) }
        assertEquals(1.0, io.flIO.power, 1e-9)
        assertEquals(0.5, io.flIO.powerScale, 1e-9)
        assertEquals(0.5, io.flIO.power * io.flIO.powerScale, 1e-9)
    }

    @Test
    fun `nonfinite drivetrain requests fail closed`() {
        val motors = Array(4) { MockDcMotorEx() }
        val io = MecanumHardwareIO(motorHardwareMap(motors), maxWheelSpeedMetersPerSecond = 1.0)
        io.kV = 1.0

        io.apply(
            doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, 1.0, -1.0),
            batteryVolts = Double.NaN,
            powerScale = Double.NaN
        )

        motors.forEach { motor ->
            assertTrue(motor.currentPower.isFinite())
            assertEquals(0.0, motor.currentPower, 1e-9)
        }
        assertEquals(0.0, io.flIO.powerScale, 1e-9)
        assertTrue(io.outputFaultLatched)
    }

    @Test
    fun `failed output latches neutral until explicit successful recovery`() {
        val motors = Array(4) { MockDcMotorEx() }
        val io = MecanumHardwareIO(motorHardwareMap(motors), maxWheelSpeedMetersPerSecond = 1.0)

        motors[1].rejectNextPowerWrite = true
        io.setMotorPowers(0.6, 0.6, 0.6, 0.6)

        assertTrue(io.outputFaultLatched)
        motors.forEach { assertEquals(0.0, it.currentPower, 1e-9) }

        io.setMotorPowers(0.4, 0.4, 0.4, 0.4)
        motors.forEach { assertEquals(0.0, it.currentPower, 1e-9) }
        io.safe()
        assertTrue(io.outputFaultLatched, "ordinary safe calls must not silently clear a fault")

        assertTrue(io.recoverWithNeutral())
        assertFalse(io.outputFaultLatched)
        io.setMotorPowers(0.4, 0.4, 0.4, 0.4)
        motors.forEach { assertEquals(0.4, it.currentPower, 1e-9) }
    }

    @Test
    fun `failed neutral recovery remains latched`() {
        val motors = Array(4) { MockDcMotorEx() }
        val io = MecanumHardwareIO(motorHardwareMap(motors), maxWheelSpeedMetersPerSecond = 1.0)

        motors[2].rejectPowerWrites = true
        io.setMotorPowers(0.5, 0.5, 0.5, 0.5)
        assertTrue(io.outputFaultLatched)
        assertFalse(io.recoverWithNeutral())
        assertTrue(io.outputFaultLatched)

        motors[2].rejectPowerWrites = false
        assertTrue(io.recoverWithNeutral())
        assertFalse(io.outputFaultLatched)
    }

    @Test
    fun `robot recovery requires a neutral Redux drive command`() {
        val motors = Array(4) { MockDcMotorEx() }
        val robot = FtcMecanumRobot(motorHardwareMap(motors))
        try {
            motors[0].rejectNextPowerWrite = true
            robot.mecanumIO.setMotorPowers(0.5, 0.5, 0.5, 0.5)
            assertTrue(robot.isDriveOutputFaultLatched)

            robot.store.dispatch(com.areslib.action.RobotAction.JoystickDriveIntent(1.0, 0.0, 0.0))
            assertFalse(robot.recoverDriveOutputWithNeutral())
            assertTrue(robot.isDriveOutputFaultLatched)

            robot.store.dispatch(com.areslib.action.RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0))
            assertTrue(robot.recoverDriveOutputWithNeutral())
            assertFalse(robot.isDriveOutputFaultLatched)
        } finally {
            robot.close()
        }
    }

    private fun motorHardwareMap(motors: Array<MockDcMotorEx>): HardwareMap = object : HardwareMap() {
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
