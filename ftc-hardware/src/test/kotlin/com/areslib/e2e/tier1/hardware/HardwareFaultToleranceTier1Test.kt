package com.areslib.e2e.tier1.hardware

import com.areslib.ftc.hardware.FtcMotor
import com.areslib.ftc.hardware.FtcImu
import com.areslib.hardware.sensor.ImuInputs
import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.IMU
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MockFtcMotorEx : DcMotorEx {
    override val currentPosition: Int = 0
    var mockVelocity: Double = 0.0
    var mockCurrentAmps: Double = 0.0
    override var direction: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD
    override var mode: DcMotor.RunMode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
    var currentPower: Double = 0.0
    
    override var power: Double
        get() = currentPower
        set(value) {
            currentPower = value
        }

    override var velocity: Double
        get() = mockVelocity
        set(value) { mockVelocity = value }

    override fun getCurrent(unit: org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit): Double {
        return mockCurrentAmps
    }
}

class MockIMU : IMU {
    var shouldThrow = false
    var mockYaw = 1.0
    var mockPitch = 0.5
    var mockRoll = 0.2
    
    override fun initialize(parameters: IMU.Parameters): Boolean {
        return true
    }

    override fun resetYaw() {}

    override fun getRobotYawPitchRollAngles(): YawPitchRollAngles {
        if (shouldThrow) throw RuntimeException("I2C Disconnect / Timeout!")
        return YawPitchRollAngles(AngleUnit.RADIANS, mockYaw, mockPitch, mockRoll, 0L)
    }

    override fun getRobotAngularVelocity(angleUnit: AngleUnit): AngularVelocity {
        if (shouldThrow) throw RuntimeException("I2C Disconnect / Timeout!")
        return AngularVelocity(AngleUnit.RADIANS, 0.0f, 0.0f, 0.0f, 0L)
    }
}

class HardwareFaultToleranceTier1Test {

    @Test
    fun testMotorStallDetection_tripsOnStall() {
        val mockMotor = MockFtcMotorEx()
        val ftcMotor = FtcMotor(mockMotor)

        // Set power to high (> 0.5) but velocity to low (< 10)
        mockMotor.mockVelocity = 5.0
        ftcMotor.updateInputs()
        
        // Loop time simulated
        val baseTime = 1000L
        com.areslib.util.RobotClock.setMockTimeMs(baseTime)

        // First power set
        ftcMotor.power = 0.8
        assertEquals(0.8, mockMotor.currentPower, 1e-6)

        // Fast-forward mock clock by 600ms (> 500ms stall threshold)
        com.areslib.util.RobotClock.setMockTimeMs(baseTime + 600)

        // Apply power again to trigger stall check
        ftcMotor.updateInputs()
        ftcMotor.power = 0.8
        
        // Power should be cut to 0.0 due to stall detection
        assertEquals(0.0, mockMotor.currentPower, 1e-6)
    }

    @Test
    fun testMotorStallAutoRecovery_resetsOnHealthyVelocity() {
        val mockMotor = MockFtcMotorEx()
        val ftcMotor = FtcMotor(mockMotor)

        // Force stall
        mockMotor.mockVelocity = 5.0
        ftcMotor.updateInputs()
        val baseTime = 1000L
        com.areslib.util.RobotClock.setMockTimeMs(baseTime)
        ftcMotor.power = 0.8
        com.areslib.util.RobotClock.setMockTimeMs(baseTime + 600)
        ftcMotor.updateInputs()
        ftcMotor.power = 0.8
        assertEquals(0.0, mockMotor.currentPower, 1e-6) // Confirmed stalled

        // Set velocity to high/healthy (> 10) or lower power command to auto-reset
        mockMotor.mockVelocity = 20.0
        ftcMotor.updateInputs()
        ftcMotor.power = 0.8 // power set should clear stall
        
        // Power should be active again
        assertEquals(0.8, mockMotor.currentPower, 1e-6)
    }

    @Test
    fun testMotorCurrentSpikeLimit_tripsVirtualBreaker() {
        val mockMotor = MockFtcMotorEx()
        val ftcMotor = FtcMotor(mockMotor)

        // Set current to dangerously high (> 9.2A)
        mockMotor.mockCurrentAmps = 10.0
        ftcMotor.updateInputs()
        
        ftcMotor.power = 0.5
        
        // Should trip virtual breaker instantly
        assertEquals(0.0, mockMotor.currentPower, 1e-6)
    }

    @Test
    fun testImuDisconnect_gracefullySwallowsTimeoutExceptions() {
        val mockIMU = MockIMU()
        val ftcImu = FtcImu(mockIMU)
        val inputs = ImuInputs()

        // Verify normal reading works
        ftcImu.updateInputs(inputs)
        assertEquals(1.0, inputs.headingRadians, 1e-6)
        assertEquals(0.5, inputs.pitchRadians, 1e-6)

        // Simulate I2C disconnect / throw
        mockIMU.shouldThrow = true
        
        // Call updateInputs, should NOT throw
        assertDoesNotThrow {
            ftcImu.updateInputs(inputs)
        }
        
        // State should remain at cached values rather than clearing or corrupting
        assertEquals(1.0, inputs.headingRadians, 1e-6)
        assertEquals(0.5, inputs.pitchRadians, 1e-6)
    }
}
