package com.areslib.drivetrain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DrivetrainDslTest {
    @Test
    fun `ftcMecanumDrivetrain DSL builds valid mecanum document and round trips JSON`() {
        val doc = ftcMecanumDrivetrain("main-drive") {
            geometry(
                wheelDiameterMeters = 0.096,
                trackWidthMeters = 0.34,
                wheelBaseMeters = 0.32,
                driveGearRatio = 19.2,
                maxLinearSpeedMetersPerSecond = 2.4,
                maxAngularSpeedRadiansPerSecond = 5.5,
            )
            tuningDouble(
                key = "drive.heading.kp",
                defaultValue = 1.8,
                minimum = 0.0,
                maximum = 20.0,
                unit = "rad/s per rad",
            )
        }

        assertEquals("Main Drive", doc.displayName)
        assertEquals(DrivetrainKind.FTC_MECANUM, doc.kind)
        assertEquals(DrivetrainPlatform.FTC, doc.platform)
        assertEquals(5, doc.components.size)
        assertEquals(2.4, doc.geometry.maxLinearSpeedMetersPerSecond)
        assertEquals(1, doc.parameters.size)

        val json = DrivetrainDocumentCodec.encode(doc)
        val decoded = DrivetrainDocumentCodec.decode(json)
        assertEquals(
            doc.copy(
                components = doc.components.sortedBy { it.uid },
                control = doc.control.copy(supported = doc.control.supported.sortedBy { it.name }),
            ),
            decoded,
        )
    }

    @Test
    fun `frcCtreSwerveDrivetrain DSL builds valid 4-module CTRE swerve document and round trips JSON`() {
        val doc = frcCtreSwerveDrivetrain(
            id = "swerve",
            vendorSourcePath = "src/main/java/frc/robot/generated/TunerConstants.java",
            sourceSha256 = "b".repeat(64),
            drivetrainConstantsClassName = "frc.robot.generated.TunerConstants",
            canBusName = "CANivore",
        ) {
            tuningDouble(
                key = "drive.swerve.headingKp",
                defaultValue = 4.2,
                minimum = 0.0,
                maximum = 25.0,
                unit = "rad/s per rad",
            )
        }

        assertEquals("Swerve", doc.displayName)
        assertEquals(DrivetrainKind.FRC_CTRE_SWERVE, doc.kind)
        assertEquals(DrivetrainPlatform.FRC, doc.platform)
        assertEquals(4, doc.modules.size)
        assertEquals(12, doc.components.size)

        val json = DrivetrainDocumentCodec.encode(doc)
        val decoded = DrivetrainDocumentCodec.decode(json)
        assertEquals(4, decoded.modules.size)
        assertTrue(validateDrivetrainDocument(decoded).isEmpty())
    }

    @Test
    fun `invalid drivetrain DSL fails fast with formatted diagnostic report`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ftcMecanumDrivetrain("main-drive") {
                geometry(wheelDiameterMeters = -1.0)
            }
        }
        assertTrue(error.message!!.contains("Drivetrain validation found"))
        assertTrue(error.message!!.contains("[ERROR] geometry"))
    }
}
