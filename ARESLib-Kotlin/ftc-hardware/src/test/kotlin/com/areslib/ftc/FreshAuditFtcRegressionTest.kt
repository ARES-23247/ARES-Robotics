package com.areslib.ftc

import com.areslib.Store
import com.areslib.ftc.core.FtcOpModeLifecycleController
import com.areslib.ftc.dsl.allianceForTeleOpRestore
import com.areslib.ftc.hardware.CachedDcMotorEx
import com.areslib.ftc.hardware.FtcIndicatorLightIO
import com.areslib.ftc.hardware.FtcPrismDriverI2cIO
import com.areslib.ftc.hardware.SrsHubDriver
import com.areslib.ftc.hardware.SrsHubVL53L5CX
import com.areslib.ftc.vision.FtcLimelightIO
import com.areslib.hardware.actuator.IndicatorLightColor
import com.areslib.networktables.NT4Instance
import com.areslib.state.Alliance
import com.areslib.state.RobotState
import com.areslib.subsystem.Subsystem
import com.areslib.util.RobotClock
import com.qualcomm.hardware.limelightvision.Limelight3A
import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.I2cAddr
import com.qualcomm.robotcore.hardware.I2cDeviceSynch
import com.qualcomm.robotcore.hardware.Servo
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FreshAuditFtcRegressionTest {
    @AfterEach
    fun tearDown() {
        RobotClock.useSystemTime()
        NT4Instance.defaultInstance.closeServer()
    }

    @Test
    fun `CachedDcMotorEx stop command after setVelocity or setPowerFloat reaches delegate`() {
        val writtenPowers = mutableListOf<Double>()
        val delegate = object : DcMotorEx {
            private var rawPower = 0.0
            override var power: Double
                get() = rawPower
                set(value) {
                    writtenPowers += value
                    rawPower = value
                }
            override var direction: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD
            override var mode: DcMotor.RunMode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
            override var zeroPowerBehavior: DcMotor.ZeroPowerBehavior = DcMotor.ZeroPowerBehavior.FLOAT
            override val currentPosition: Int = 0
            override var velocity: Double = 0.0
            override fun setVelocity(angularRate: Double, unit: AngleUnit) { velocity = angularRate }
            override fun setPowerFloat() { rawPower = 0.0 }
            override fun setMotorDisable() { rawPower = 0.0 }
            override fun getCurrent(unit: CurrentUnit): Double = 0.0
        }
        val cached = CachedDcMotorEx(delegate)

        cached.power = 0.0
        assertEquals(listOf(0.0), writtenPowers)

        cached.velocity = 500.0
        cached.power = 0.0
        assertEquals(listOf(0.0, 0.0), writtenPowers, "setPower(0.0) after setVelocity must not be dropped")

        cached.setVelocity(250.0, AngleUnit.DEGREES)
        cached.power = 0.0
        assertEquals(listOf(0.0, 0.0, 0.0), writtenPowers, "setPower(0.0) after setVelocity(unit) must not be dropped")

        cached.setPowerFloat()
        cached.power = 0.0
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), writtenPowers, "setPower(0.0) after setPowerFloat must not be dropped")

        cached.setMotorDisable()
        cached.power = 0.0
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 0.0), writtenPowers, "setPower(0.0) after setMotorDisable must not be dropped")
    }

    @Test
    fun `FtcBaseRobot update runs registered subsystems before telemetry publish`() {
        RobotClock.useMockTime(1_000L)
        val pacing = FtcOpModeLifecycleController.beginExternallyPacedFrame()
        val events = mutableListOf<String>()
        val robot = object : FtcBaseRobot(
            hardwareMap = object : HardwareMap() {
                override fun <T> get(classOrType: Class<out T>, deviceName: String): T = error("No device")
                override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
            },
            pinpointName = null,
            limelightName = null,
            imuName = null,
        ) {
            override fun updateHardwareInputs() {
                events += "hardware-inputs"
            }

            override fun updateSubsystems(dtSeconds: Double, batteryVoltage: Double, powerScale: Double) {
                events += "drivetrain-subsystems"
            }

            override fun publishRobotTelemetry(timestamp: Long) {
                events += "publish-telemetry"
            }

            override fun safeHardware() = Unit
        }
        robot.registerSubsystem(object : Subsystem {
            override fun readSensors(store: Store, timestampMs: Long) {
                events += "registered-read"
            }

            override fun writeOutputs(state: RobotState, scale: Double) {
                events += "registered-write"
            }
        })
        try {
            robot.update()
            val readIdx = events.indexOf("registered-read")
            val driveIdx = events.indexOf("drivetrain-subsystems")
            val writeIdx = events.indexOf("registered-write")
            val pubIdx = events.indexOf("publish-telemetry")
            assertTrue(readIdx in 0 until driveIdx, "registered readSensors must precede drivetrain update: $events")
            assertTrue(writeIdx in (driveIdx + 1) until pubIdx, "registered writeOutputs must precede telemetry publish: $events")
        } finally {
            robot.close()
            FtcOpModeLifecycleController.endExternallyPacedFrame(pacing)
        }
    }

    @Test
    fun `allianceForTeleOpRestore preserves fallback alliance when PoseStorage is empty`() {
        assertEquals(Alliance.RED, allianceForTeleOpRestore(null))
        assertEquals(Alliance.BLUE, allianceForTeleOpRestore(null, Alliance.BLUE))
    }

    @Test
    fun `FtcLimelightIO setOrientation survives SDK exception and ignores calls after close`() {
        var orientationCalls = 0
        val faultyLimelight = object : Limelight3A() {
            override fun updateRobotOrientation(yawDegrees: Double): Boolean {
                orientationCalls++
                throw RuntimeException("USB glitch")
            }
        }
        val io = FtcLimelightIO(faultyLimelight)
        assertDoesNotThrow { io.setOrientation(45.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
        assertEquals(1, orientationCalls)
        io.close()
        assertDoesNotThrow { io.setOrientation(90.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
        assertEquals(1, orientationCalls, "setOrientation must be a no-op after close()")
    }

    @Test
    fun `FtcIndicatorLightIO sanitizes NaN to OFF and blocks writes after close`() {
        val writes = mutableListOf<Double>()
        val servo = object : Servo {
            private var pos = -1.0
            override var position: Double
                get() = pos
                set(value) {
                    pos = value
                    writes += value
                }
        }
        val map = object : HardwareMap() {
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T = classOrType.cast(servo)
            override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
        }
        val light = FtcIndicatorLightIO(map, "led")
        light.setPosition(Double.NaN)
        assertEquals(listOf(IndicatorLightColor.OFF.position), writes)
        light.setPosition(0.5)
        assertEquals(listOf(IndicatorLightColor.OFF.position, 0.5), writes)
        light.close()
        val countAfterClose = writes.size
        light.setPosition(0.8)
        assertEquals(countAfterClose, writes.size, "Closed FtcIndicatorLightIO must ignore further writes")
    }

    @Test
    fun `FtcPrismDriverI2cIO caches redundant solid color writes and enforces close`() {
        var writeCount = 0
        val i2c = object : I2cDeviceSynch {
            override var i2cAddress: I2cAddr = I2cAddr.create7bit(0x38)
            override var readWindow: I2cDeviceSynch.ReadWindow =
                I2cDeviceSynch.ReadWindow(0, 1, I2cDeviceSynch.ReadMode.ONLY_ONCE)
            override fun read(ireg: Int, length: Int): ByteArray = ByteArray(length)
            override fun read8(ireg: Int): Byte = 0x03
            override fun write(ireg: Int, data: ByteArray) { writeCount++ }
            override fun write8(ireg: Int, data: Int) { writeCount++ }
            override fun close() = Unit
        }
        val map = object : HardwareMap() {
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T = classOrType.cast(i2c)
            override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
        }
        val prism = FtcPrismDriverI2cIO(map)
        prism.setSolidColorRgb(255, 0, 0)
        val firstBatchWrites = writeCount
        assertEquals(5, firstBatchWrites)
        prism.setSolidColorRgb(255, 0, 0)
        assertEquals(firstBatchWrites, writeCount, "Identical RGB command should not rewrite over I2C")
        prism.close()
        val writesAtClose = writeCount
        prism.setSolidColorRgb(0, 255, 0)
        assertEquals(writesAtClose, writeCount, "Closed prism must not issue I2C writes")
        assertThrows(IllegalStateException::class.java) { prism.readDeviceId() }
    }

    @Test
    fun `SrsHubVL53L5CX rejects unsupported ports beyond port 0`() {
        val i2c = object : I2cDeviceSynch {
            override var i2cAddress: I2cAddr = I2cAddr.create7bit(0x20)
            override var readWindow: I2cDeviceSynch.ReadWindow =
                I2cDeviceSynch.ReadWindow(0, 1, I2cDeviceSynch.ReadMode.ONLY_ONCE)
            override fun read(ireg: Int, length: Int): ByteArray = ByteArray(length)
            override fun read8(ireg: Int): Byte = 0
            override fun write(ireg: Int, data: ByteArray) = Unit
            override fun write8(ireg: Int, data: Int) = Unit
            override fun close() = Unit
        }
        val hub = SrsHubDriver(i2c)
        try {
            assertDoesNotThrow { SrsHubVL53L5CX(hub, port = 0) }
            assertThrows(IllegalArgumentException::class.java) { SrsHubVL53L5CX(hub, port = 1) }
        } finally {
            hub.close()
        }
    }
}
