package org.aresfirst.marvin.config

import com.areslib.state.RobotFieldDocument
import edu.wpi.first.apriltag.AprilTagFields
import edu.wpi.first.hal.HAL
import edu.wpi.first.units.Units.Rotations
import frc.robot.generated.TunerConstants
import org.aresfirst.marvin.generated.drivebase.GeneratedAresDrivebaseConfig as Profile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.*

class DeploymentDataAuditTest {
    private fun field() = RobotFieldDocument.decode(File("src/main/deploy/paths/field.json").readText())

    @Test fun `deployed tag positions and rotations match the bundled WPILib Crescendo layout`() {
        val actual = field()
        val expected = AprilTagFields.k2024Crescendo.loadAprilTagLayoutField()
        assertEquals(expected.fieldLength, actual.widthMeters, 0.001)
        assertEquals(expected.fieldWidth, actual.heightMeters, 0.001)
        assertEquals(expected.tags.map { it.ID }.toSet(), actual.apriltags.map { it.id }.toSet())
        assertEquals(expected.tags.size, actual.apriltags.size)
        for (tag in actual.apriltags) {
            val pose = expected.getTagPose(tag.id).orElseThrow()
            assertEquals(pose.x, tag.x, 1e-9, "tag ${tag.id} X")
            assertEquals(pose.y, tag.y, 1e-9, "tag ${tag.id} Y")
            assertEquals(pose.z, tag.z, 1e-9, "tag ${tag.id} Z")
            for ((angle, radians) in listOf(tag.roll to pose.rotation.x,
                tag.pitch to pose.rotation.y, tag.yaw to pose.rotation.z)) {
                assertEquals(sin(radians), sin(Math.toRadians(angle)), 1e-9)
                assertEquals(cos(radians), cos(Math.toRadians(angle)), 1e-9)
            }
        }
    }

    @Test fun `field pieces resolve unique types and fit inside the declared boundary`() {
        val document = field()
        val types = document.elementTypes.associateBy { it.id }
        assertEquals(document.elementTypes.size, types.size)
        assertEquals(document.elements.size, document.elements.map { it.id }.toSet().size)
        assertEquals(11, document.elements.size)
        for (piece in document.elements) {
            val type = requireNotNull(types[piece.elementTypeId])
            assertTrue(type.massKg.isFinite() && type.massKg > 0.0)
            assertTrue(type.width > 0.0 && type.height > 0.0 && type.depth > 0.0)
            assertTrue(type.friction >= 0.0 && type.restitution in 0.0..1.0)
            assertTrue(piece.x - type.width / 2 >= 0.0 && piece.x + type.width / 2 <= document.widthMeters)
            assertTrue(piece.y - type.depth / 2 >= 0.0 && piece.y + type.depth / 2 <= document.heightMeters)
        }
        assertEquals(6, document.obstacles.size)
        assertEquals(6, document.obstacles.map { it.id }.toSet().size)
        for (obstacle in document.obstacles) {
            assertTrue(obstacle.x - obstacle.width / 2 >= 0.0)
            assertTrue(obstacle.x + obstacle.width / 2 <= document.widthMeters)
            assertTrue(obstacle.y - obstacle.height / 2 >= 0.0)
            assertTrue(obstacle.y + obstacle.height / 2 <= document.heightMeters)
            assertTrue(obstacle.friction >= 0.0 && obstacle.restitution in 0.0..1.0)
        }
    }

    @Test fun `vendor module geometry ratios and speed agree with the canonical ARES profile`() {
        assertTrue(HAL.initialize(500, 0))
        val modules = listOf(TunerConstants.FrontLeft, TunerConstants.FrontRight,
            TunerConstants.BackLeft, TunerConstants.BackRight)
        val xSigns = listOf(1, 1, -1, -1)
        val ySigns = listOf(1, -1, 1, -1)
        for ((i, module) in modules.withIndex()) {
            assertEquals(xSigns[i] * Profile.WHEEL_BASE_METERS / 2, module.LocationX, 1e-12)
            assertEquals(ySigns[i] * Profile.TRACK_WIDTH_METERS / 2, module.LocationY, 1e-12)
            assertEquals(Profile.WHEEL_DIAMETER_METERS / 2, module.WheelRadius, 1e-12)
            assertEquals(Profile.DRIVE_GEAR_RATIO, module.DriveMotorGearRatio, 1e-12)
            assertEquals(Profile.STEER_GEAR_RATIO, module.SteerMotorGearRatio, 1e-12)
            assertEquals(Profile.MAX_LINEAR_SPEED_METERS_PER_SECOND, module.SpeedAt12Volts, 1e-12)
            assertEquals(Profile.MAX_ANGULAR_SPEED_RADIANS_PER_SECOND,
                module.SpeedAt12Volts / hypot(module.LocationX, module.LocationY), 1e-10)
        }
        assertEquals(listOf(Profile.Components.FRC_MODULE_FL_DRIVE.HARDWARE_ID,
            Profile.Components.FRC_MODULE_FR_DRIVE.HARDWARE_ID, Profile.Components.FRC_MODULE_BL_DRIVE.HARDWARE_ID,
            Profile.Components.FRC_MODULE_BR_DRIVE.HARDWARE_ID), modules.map { "drive:${it.DriveMotorId}" })
        assertEquals(listOf(Profile.Components.FRC_MODULE_FL_STEER.HARDWARE_ID,
            Profile.Components.FRC_MODULE_FR_STEER.HARDWARE_ID, Profile.Components.FRC_MODULE_BL_STEER.HARDWARE_ID,
            Profile.Components.FRC_MODULE_BR_STEER.HARDWARE_ID), modules.map { "steer:${it.SteerMotorId}" })
        assertEquals(listOf(Profile.Components.FRC_MODULE_FL_ENCODER.HARDWARE_ID,
            Profile.Components.FRC_MODULE_FR_ENCODER.HARDWARE_ID, Profile.Components.FRC_MODULE_BL_ENCODER.HARDWARE_ID,
            Profile.Components.FRC_MODULE_BR_ENCODER.HARDWARE_ID), modules.map { "encoder:${it.EncoderId}" })
        assertEquals(listOf(Profile.Components.FRC_MODULE_FL_DRIVE.INVERTED,
            Profile.Components.FRC_MODULE_FR_DRIVE.INVERTED, Profile.Components.FRC_MODULE_BL_DRIVE.INVERTED,
            Profile.Components.FRC_MODULE_BR_DRIVE.INVERTED), modules.map { it.DriveMotorInverted })
        assertEquals(Profile.CTRE_CAN_BUS, TunerConstants.DrivetrainConstants.CANBusName)
        assertEquals(Profile.Components.FRC_GYRO_PIGEON.HARDWARE_ID,
            "pigeon:${TunerConstants.DrivetrainConstants.Pigeon2Id}")
    }

    @Test fun `calibration factories preserve geometry and replace only the supplied encoder offset`() {
        assertTrue(HAL.initialize(500, 0))
        val original = listOf(TunerConstants.FrontLeft, TunerConstants.FrontRight,
            TunerConstants.BackLeft, TunerConstants.BackRight)
        val replacements = listOf(TunerConstants.createFrontLeft(Rotations.of(0.125)),
            TunerConstants.createFrontRight(Rotations.of(-0.25)),
            TunerConstants.createBackLeft(Rotations.of(0.375)),
            TunerConstants.createBackRight(Rotations.of(-0.5)))
        val offsets = listOf(0.125, -0.25, 0.375, -0.5)
        for (i in original.indices) {
            val old = original[i]
            val updated = replacements[i]
            assertNotSame(old, updated)
            assertEquals(offsets[i], updated.EncoderOffset, 0.0)
            assertEquals(old.DriveMotorId, updated.DriveMotorId)
            assertEquals(old.SteerMotorId, updated.SteerMotorId)
            assertEquals(old.EncoderId, updated.EncoderId)
            assertEquals(old.LocationX, updated.LocationX, 0.0)
            assertEquals(old.LocationY, updated.LocationY, 0.0)
            assertEquals(old.WheelRadius, updated.WheelRadius, 0.0)
            assertEquals(old.DriveMotorInverted, updated.DriveMotorInverted)
        }
        val defaults = TunerConstants.getDefaultOffsets()
        val profile = CanonicalDrivebaseConfig.profiledOffsets()
        for ((expected, actual) in listOf(defaults.frontLeft to profile.frontLeft,
            defaults.frontRight to profile.frontRight, defaults.backLeft to profile.backLeft,
            defaults.backRight to profile.backRight)) assertEquals(expected, actual, 1e-7)
    }
}
