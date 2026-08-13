package com.areslib.codegen

import com.areslib.drivetrain.*
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningAssignment
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningProfileAuthority
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DrivetrainKotlinGeneratorTest {
    @Test
    fun `generation exposes direct drivebase and project constants deterministically`() {
        val drivetrain = mecanumDocument()
        val vision = TuningParameterDeclaration(
            "vision.main.stddev", "vision.stdDev", "vision.main", "Vision standard deviation",
            "Camera measurement standard deviation", TuningParameterType.DOUBLE, "m", 0.01, 5.0,
            TuningValue(doubleValue = 0.35), applyPolicy = TuningApplyPolicy.LIVE_SAFE,
        )
        val declarations = drivetrain.parameters + vision
        val profile = TuningProfileDocument(
            uid = drivetrain.canonicalProfileUid, profileId = "competition", displayName = "Competition",
            description = "Canonical competition profile", projectUid = "project.ftc", drivebaseUid = drivetrain.uid,
            authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
            values = declarations.map { TuningAssignment(it.uid, it.defaultValue) },
        )

        val drive = DrivetrainKotlinGenerator.generate(drivetrain, listOf(profile), "example.generated", listOf(vision))
        val project = DrivetrainKotlinGenerator.generateProjectTuning(
            profile.projectUid, profile.uid, drivetrain.uid, declarations, listOf(profile), "example.generated",
        )
        assertEquals("GeneratedAresDrivebaseConfig.kt", drive.relativePath)
        assertEquals("GeneratedAresTuningConfig.kt", project.relativePath)
        assertTrue(drive.content.contains("const val DRIVE_HEADING_KP: Double = 1.8"))
        assertTrue(project.content.contains("const val VISION_STDDEV: Double = 0.35"))
        assertTrue(drive.content.contains("TRACK_WIDTH_METERS"))
        assertTrue(drive.content.contains("object DRIVE_MOTOR_FL"))
        assertTrue(drive.content.contains("const val HARDWARE_ID: String = \"fl\""))
        assertTrue(drive.content.contains("const val INVERTED: Boolean = false"))
        assertTrue(drive.content.contains("object PRIMARY_ODOMETRY"))
        assertTrue(drive.content.contains("const val HEADING_SOURCE_UID: String = \"drive.odometry.pinpoint\""))
        assertTrue(!drive.content.contains("TunerConstants.java"))
        assertEquals(
            drive,
            DrivetrainKotlinGenerator.generate(drivetrain, listOf(profile), "example.generated", listOf(vision)),
        )
    }

    @Test
    fun `local overlays are categorically excluded from build generation`() {
        val drivetrain = mecanumDocument()
        val local = TuningProfileDocument(
            uid = "local.test", profileId = "local-test", displayName = "Local", description = "Not authoritative",
            projectUid = "project.ftc", drivebaseUid = drivetrain.uid,
            authority = TuningProfileAuthority.LOCAL_EXPERIMENTAL, values = emptyList(),
        )
        val error = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            DrivetrainKotlinGenerator.generate(drivetrain, listOf(local), "example.generated")
        }
        assertTrue(error.message.orEmpty().contains("checked-in canonical"))
    }

    @Test
    fun `project tuning generation supports subsystem-only robots without fabricating a drivebase`() {
        val declaration = TuningParameterDeclaration(
            "arm.main.kp", "arm.position.kP", "arm.main", "Arm position P",
            "Arm position proportional gain", TuningParameterType.DOUBLE, null, 0.0, 20.0,
            TuningValue(doubleValue = 2.0), applyPolicy = TuningApplyPolicy.DISABLED_ONLY,
        )
        val profile = TuningProfileDocument(
            uid = "profile.competition", profileId = "competition", displayName = "Competition",
            description = "Subsystem-only robot profile", projectUid = "project.arm",
            drivebaseUid = null, authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
            values = listOf(TuningAssignment(declaration.uid, declaration.defaultValue)),
        )

        val generated = DrivetrainKotlinGenerator.generateProjectTuning(
            profile.projectUid, profile.uid, null, listOf(declaration), listOf(profile), "example.generated",
        )

        assertTrue(generated.content.contains("val DRIVEBASE_UID: String? = null"))
        assertTrue(generated.content.contains("drivebaseUid = DRIVEBASE_UID"))
        assertTrue(generated.content.contains("const val ARM_POSITION_KP: Double = 2.0"))
    }

    @Test
    fun `generated strings are escaped and Kotlin identifier collisions fail closed`() {
        val drivetrain = mecanumDocument()
        val escaped = drivetrain.parameters.single().copy(description = "Dollar ${'$'} and\nnext line")
        val colliding = escaped.copy(uid = "drive.other", key = "drive.heading.kp")
        val profile = TuningProfileDocument(
            uid = drivetrain.canonicalProfileUid, profileId = "competition", displayName = "Competition",
            description = "Canonical", projectUid = "project.ftc", drivebaseUid = drivetrain.uid,
            authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
            values = listOf(TuningAssignment(escaped.uid, escaped.defaultValue)),
        )

        val generated = DrivetrainKotlinGenerator.generateProjectTuning(
            profile.projectUid, profile.uid, drivetrain.uid, listOf(escaped), listOf(profile), "example.generated",
        )
        assertTrue(generated.content.contains("Dollar \\$ and\\nnext line"))
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            DrivetrainKotlinGenerator.generateProjectTuning(
                profile.projectUid, profile.uid, drivetrain.uid, listOf(escaped, colliding),
                listOf(profile.copy(values = listOf(
                    TuningAssignment(escaped.uid, escaped.defaultValue),
                    TuningAssignment(colliding.uid, colliding.defaultValue),
                ))), "example.generated",
            )
        }
    }

    private fun mecanumDocument(): DrivetrainDocument {
        val motors = listOf("fl", "fr", "rl", "rr").map { id ->
            DrivetrainComponentDocument(
                uid = "drive.motor.$id",
                displayName = id.uppercase(),
                role = DrivetrainComponentRole.DRIVE_MOTOR,
                hardwareId = id,
                currentMeasurementRequired = true,
                currentMeasurementAvailable = true,
            )
        }
        val odometry = DrivetrainComponentDocument(
            uid = "drive.odometry.pinpoint",
            displayName = "Pinpoint",
            role = DrivetrainComponentRole.ODOMETRY_SENSOR,
            hardwareId = "pinpoint",
        )
        val heading = TuningParameterDeclaration(
            uid = "drive.main.heading.kp",
            key = "drive.heading.kP",
            componentUid = "drive.main",
            displayName = "Heading P",
            description = "Heading proportional gain",
            type = TuningParameterType.DOUBLE,
            minimum = 0.0,
            maximum = 20.0,
            defaultValue = TuningValue(doubleValue = 1.8),
            applyPolicy = TuningApplyPolicy.LIVE_SAFE,
        )
        return DrivetrainDocument(
            uid = "drive.main",
            drivebaseId = "main-drive",
            displayName = "Competition mecanum",
            description = "Four-wheel FTC mecanum drivebase",
            kind = DrivetrainKind.FTC_MECANUM,
            platform = DrivetrainPlatform.FTC,
            components = motors + odometry,
            geometry = DrivetrainGeometryDocument(0.096, 0.34, 0.32, 19.2, null, 2.2, 5.0),
            localization = DrivetrainLocalizationDocument(
                primaryOdometry = DrivetrainLocalizationSourceDocument(
                    uid = "localization.pinpoint",
                    source = LocalizationSourceKind.PINPOINT,
                    componentUids = listOf(odometry.uid),
                    implementationClassName = "example.PinpointIO",
                ),
                headingSourceUid = odometry.uid,
            ),
            control = DrivetrainControlDocument(
                supported = listOf(DrivetrainControlKind.OPEN_LOOP, DrivetrainControlKind.CHASSIS_VELOCITY),
                defaultControl = DrivetrainControlKind.CHASSIS_VELOCITY,
            ),
            simulation = DrivetrainSimulationDocument("example.MecanumModel", "example.MockMecanumIO"),
            parameters = listOf(heading),
            canonicalProfileUid = "profile.competition",
        )
    }
}
