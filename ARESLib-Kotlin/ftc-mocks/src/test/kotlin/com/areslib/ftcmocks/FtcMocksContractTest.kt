package com.areslib.ftcmocks

import com.areslib.util.RobotClock
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
import com.qualcomm.hardware.lynx.LynxModule
import com.qualcomm.hardware.lynx.LynxUsbDeviceImpl
import com.qualcomm.hardware.sparkfun.SparkFunOTOS
import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.HardwareDevice
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.PIDFCoefficients
import com.qualcomm.robotcore.hardware.Servo
import com.qualcomm.robotcore.hardware.VoltageSensor
import com.qualcomm.robotcore.util.ElapsedTime
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.robotcore.external.navigation.UnnormalizedAngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

class FtcMocksContractTest {

    @Test
    fun `GoBildaPinpointDriver applies pod offset convention and resetPosAndIMU rotation`() {
        val pinpoint = GoBildaPinpointDriver()
        // SDK 1st arg = lateral (robot Y), 2nd arg = forward (robot X)
        pinpoint.setOffsets(120.0, -80.0, DistanceUnit.MM)
        assertEquals(0.12, pinpoint.yOffsetMeters, 1e-9)
        assertEquals(-0.08, pinpoint.xOffsetMeters, 1e-9)

        pinpoint.setOffsets(0.0, 0.0, DistanceUnit.METER)
        pinpoint.posX = 1.0
        pinpoint.posY = 2.0
        pinpoint.heading = PI / 2.0
        pinpoint.trueHeading = PI / 2.0
        pinpoint.resetPosAndIMU()

        assertEquals(0.0, pinpoint.getPosX(DistanceUnit.METER), 1e-9)
        assertEquals(0.0, pinpoint.getPosY(DistanceUnit.METER), 1e-9)
        assertEquals(0.0, pinpoint.getHeading(AngleUnit.RADIANS), 1e-9)
        assertEquals(0.0, pinpoint.getHeading(UnnormalizedAngleUnit.RADIANS), 1e-9)

        // Move +1 m along field +Y (which is +X in the reset frame oriented at +pi/2)
        pinpoint.posY = 3.0
        pinpoint.velX = 0.0
        pinpoint.velY = 2.0
        pinpoint.heading = PI
        pinpoint.headingVelocity = 0.75

        assertEquals(1.0, pinpoint.getPosX(DistanceUnit.METER), 1e-9)
        assertEquals(0.0, pinpoint.getPosY(DistanceUnit.METER), 1e-9)
        assertEquals(PI / 2.0, pinpoint.getHeading(AngleUnit.RADIANS), 1e-9)
        assertEquals(2.0, pinpoint.getVelX(DistanceUnit.METER), 1e-9)
        assertEquals(0.0, pinpoint.getVelY(DistanceUnit.METER), 1e-9)
        assertEquals(0.75, pinpoint.getHeadingVelocity(UnnormalizedAngleUnit.RADIANS), 1e-9)

        val pose = pinpoint.getPosition()
        assertEquals(1.0, pose.getX(DistanceUnit.METER), 1e-9)
        assertEquals(0.0, pose.getY(DistanceUnit.METER), 1e-9)
        assertEquals(PI / 2.0, pose.getHeading(AngleUnit.RADIANS), 1e-9)
    }

    @Test
    fun `GoBildaPinpointDriver encoder resolution presets and custom values are mutually exclusive`() {
        val pinpoint = GoBildaPinpointDriver()
        pinpoint.setEncoderResolution(19.89436789, DistanceUnit.MM)
        assertEquals(19.89436789, pinpoint.configuredEncoderResolution)
        assertNull(pinpoint.configuredPod)

        pinpoint.setEncoderResolution(GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD)
        assertEquals(GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD, pinpoint.configuredPod)
        assertNull(pinpoint.configuredEncoderResolution)
    }

    @Test
    fun `SparkFunOTOS defensively copies poses and resets tracking`() {
        val otos = SparkFunOTOS()
        val inputPos = SparkFunOTOS.Pose2D(1.5, -0.5, 0.25)
        val inputVel = SparkFunOTOS.Pose2D(0.8, 0.2, -0.4)
        val inputAcc = SparkFunOTOS.Pose2D(0.1, -0.2, 0.3)

        otos.setPosition(inputPos)
        otos.setVelocity(inputVel)
        otos.setAcceleration(inputAcc)

        // Mutating caller objects must not mutate stored sensor snapshots
        inputPos.x = 99.0
        inputVel.y = 99.0
        inputAcc.h = 99.0

        val readPos = otos.getPosition()
        assertNotSame(inputPos, readPos)
        assertEquals(SparkFunOTOS.Pose2D(1.5, -0.5, 0.25), readPos)
        assertEquals(SparkFunOTOS.Pose2D(0.8, 0.2, -0.4), otos.getVelocity())
        assertEquals(SparkFunOTOS.Pose2D(0.1, -0.2, 0.3), otos.getAcceleration())

        assertTrue(otos.setLinearScalar(1.02))
        assertTrue(otos.setAngularScalar(0.99))
        assertTrue(otos.calibrateImu())
        assertEquals(DistanceUnit.METER, otos.getLinearUnit())
        assertEquals(AngleUnit.RADIANS, otos.getAngularUnit())

        otos.resetTracking()
        assertEquals(SparkFunOTOS.Pose2D(0.0, 0.0, 0.0), otos.getPosition())
    }

    @Test
    fun `LynxModule defaults and bulk cache mode contract`() {
        val usb = LynxUsbDeviceImpl()
        val hub = LynxModule(usb, moduleAddress = 173, isParent = true, isUserModule = false)

        assertEquals(173, hub.moduleAddress)
        assertTrue(hub.isParent)
        assertFalse(hub.isUserModule)
        assertEquals(LynxModule.BulkCachingMode.OFF, hub.bulkCachingMode)
        assertEquals(HardwareDevice.Manufacturer.Unknown, hub.getManufacturer())
        assertEquals(1, hub.getVersion())
        assertTrue(hub.unfinishedCommands.isEmpty())

        hub.bulkCachingMode = LynxModule.BulkCachingMode.MANUAL
        assertEquals(LynxModule.BulkCachingMode.MANUAL, hub.bulkCachingMode)
        hub.clearBulkCache()
        hub.close()
    }

    @Test
    fun `MotorMocks fail loudly on unconfigured optional SDK surfaces and preserve defaults`() {
        val motor = object : DcMotorEx {
            override var direction: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD
            override var power: Double = 0.0
            override var mode: DcMotor.RunMode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
            override var zeroPowerBehavior: DcMotor.ZeroPowerBehavior = DcMotor.ZeroPowerBehavior.BRAKE
            override val currentPosition: Int = 42
            override var velocity: Double = 100.0
            override fun getCurrent(unit: CurrentUnit): Double = 1.25
        }
        assertFalse(motor.isMotorEnabled)
        assertEquals(42, motor.currentPosition)
        assertEquals(1.25, motor.getCurrent(CurrentUnit.AMPS), 1e-9)
        assertEquals(0.0, motor.getPIDFCoefficients(DcMotor.RunMode.RUN_USING_ENCODER).p, 1e-9)
        motor.setPIDFCoefficients(DcMotor.RunMode.RUN_USING_ENCODER, PIDFCoefficients(1.0, 0.0, 0.1, 0.2))

        assertThrows(UnsupportedOperationException::class.java) { motor.motorType }
        assertThrows(IllegalStateException::class.java) { motor.controller }
        assertThrows(IllegalStateException::class.java) { motor.setMotorEnable() }
        assertThrows(IllegalStateException::class.java) { motor.getVelocity(AngleUnit.RADIANS) }
        assertThrows(IllegalStateException::class.java) { motor.isOverCurrent }

        val servo = object : Servo {
            override var position: Double = 0.5
        }
        assertEquals(Servo.Direction.FORWARD, servo.getDirection())
        assertThrows(IllegalStateException::class.java) { servo.scaleRange(0.1, 0.9) }
        assertThrows(IllegalStateException::class.java) { servo.setDirection(Servo.Direction.REVERSE) }
    }

    @Test
    fun `HardwareMap provides synthetic 12V default when empty and uses registered sensors when populated`() {
        val hw = HardwareMap()
        assertEquals(0, hw.voltageSensor.size)
        val defaultVoltages = hw.voltageSensor.map { it.voltage }
        assertEquals(listOf(12.0), defaultVoltages)

        val customSensor = object : VoltageSensor {
            override val voltage: Double = 12.8
        }
        hw.voltageSensor.put("Control Hub", customSensor)
        assertEquals(1, hw.voltageSensor.size)
        assertEquals(12.8, hw.voltageSensor.get("Control Hub")?.voltage)
        assertEquals(listOf(12.8), hw.voltageSensor.map { it.voltage })

        assertThrows(NotImplementedError::class.java) { hw.get(VoltageSensor::class.java, "Control Hub") }
        assertThrows(NotImplementedError::class.java) { hw.getAll(VoltageSensor::class.java) }
    }

    @Test
    fun `ElapsedTime tracks deterministic RobotClock time and reset`() {
        try {
            RobotClock.useMockTime(1_000L)
            val timer = ElapsedTime()
            assertEquals(0.0, timer.milliseconds(), 1e-9)
            assertEquals(0.0, timer.seconds(), 1e-9)

            RobotClock.useMockTime(2_250L)
            assertEquals(1_250.0, timer.milliseconds(), 1e-9)
            assertEquals(1.25, timer.seconds(), 1e-9)

            timer.reset()
            assertEquals(0.0, timer.milliseconds(), 1e-9)

            RobotClock.useMockTime(2_000L)
            assertEquals(-250.0, timer.milliseconds(), 1e-9)
        } finally {
            RobotClock.useSystemTime()
        }
    }

    @Test
    fun `FtcNavigationMocks convert distances, yaw-pitch-roll angles, and angular velocities in both directions`() {
        assertEquals(1000.0, DistanceUnit.MM.fromUnit(DistanceUnit.METER, 1.0), 1e-9)
        assertEquals(0.0254, DistanceUnit.METER.fromUnit(DistanceUnit.INCH, 1.0), 1e-9)
        assertEquals(100.0, DistanceUnit.CM.fromUnit(DistanceUnit.METER, 1.0), 1e-9)
        assertEquals(1.0, DistanceUnit.INCH.fromUnit(DistanceUnit.MM, 25.4), 1e-9)

        val degAngles = YawPitchRollAngles(AngleUnit.DEGREES, yaw = 90.0, pitch = -45.0, roll = 180.0)
        assertEquals(90.0, degAngles.getYaw(AngleUnit.DEGREES), 1e-9)
        assertEquals(PI / 2.0, degAngles.getYaw(AngleUnit.RADIANS), 1e-9)
        assertEquals(-PI / 4.0, degAngles.getPitch(AngleUnit.RADIANS), 1e-9)
        assertEquals(PI, degAngles.getRoll(AngleUnit.RADIANS), 1e-9)

        val radAngles = YawPitchRollAngles(AngleUnit.RADIANS, yaw = PI / 2.0, pitch = -PI / 4.0, roll = PI)
        assertEquals(90.0, radAngles.getYaw(AngleUnit.DEGREES), 1e-9)
        assertEquals(-45.0, radAngles.getPitch(AngleUnit.DEGREES), 1e-9)
        assertEquals(180.0, radAngles.getRoll(AngleUnit.DEGREES), 1e-9)

        val velDeg = AngularVelocity(AngleUnit.DEGREES, 180f, 90f, -90f, 123L)
        val velRad = velDeg.toAngleUnit(AngleUnit.RADIANS)
        assertEquals(PI.toFloat(), velRad.xRotationRate, 1e-4f)
        assertEquals((PI / 2.0).toFloat(), velRad.yRotationRate, 1e-4f)
        assertEquals(123L, velRad.acquisitionTime)
        val velBack = velRad.toAngleUnit(AngleUnit.DEGREES)
        assertEquals(180f, velBack.xRotationRate, 1e-3f)
    }
}
