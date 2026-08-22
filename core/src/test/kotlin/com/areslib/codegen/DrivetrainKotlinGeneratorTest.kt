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
    fun `FTC zero-code runtime maps canonical physical identity tuning speed and neutral policy`() {
        val drivetrain = runtimeReadyMecanumDocument()
        val profile = canonicalProfile(drivetrain)

        val generated = DrivetrainKotlinGenerator.generateFtcMecanumRuntime(
            drivetrain,
            listOf(profile),
            "example.generated",
        )

        assertEquals("GeneratedAresFtcMecanumRuntimeConfig.kt", generated.relativePath)
        assertTrue(generated.content.contains("fun createRobot(\n        hardwareMap: HardwareMap"))
        assertTrue(generated.content.contains("limelightProxyEnabled: Boolean = false"))
        assertTrue(generated.content.contains("limelightProxyEnabled = limelightProxyEnabled"))
        assertTrue(generated.content.contains("flName = GeneratedAresDrivebaseConfig.Components.DRIVE_MOTOR_FL.HARDWARE_ID"))
        assertTrue(generated.content.contains("maxWheelSpeedMetersPerSecond = GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND"))
        assertTrue(generated.content.contains("val driveZeroPowerBehavior: DcMotor.ZeroPowerBehavior get() = DcMotor.ZeroPowerBehavior.BRAKE"))
        assertTrue(generated.content.contains("val frontRightDirection: DcMotorSimple.Direction"))
        assertTrue(generated.content.contains("hardwareMap.get(com.qualcomm.hardware.gobilda.GoBildaPinpointDriver::class.java"))
        assertTrue(generated.content.contains("fun supportsRuntimeParameter"))
        assertEquals(
            generated,
            DrivetrainKotlinGenerator.generateFtcMecanumRuntime(drivetrain, listOf(profile), "example.generated"),
        )
    }

    @Test
    fun `FTC zero-code runtime rejects incomplete mechanical tuning instead of guessing`() {
        val drivetrain = runtimeReadyMecanumDocument().let { document ->
            document.copy(parameters = document.parameters.filterNot { it.key == "drive.feedforwardKv" })
        }
        val profile = canonicalProfile(drivetrain)

        val failure = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            DrivetrainKotlinGenerator.generateFtcMecanumRuntime(
                drivetrain,
                listOf(profile),
                "example.generated",
            )
        }

        assertTrue(failure.message.orEmpty().contains("drive.feedforwardKv"))
    }

    @Test
    fun `FTC wheel encoder IMU runtime does not require or expose Pinpoint tuning`() {
        val pinpoint = runtimeReadyMecanumDocument()
        val imu = DrivetrainComponentDocument(
            uid = "drive.localization.imu",
            displayName = "Control Hub IMU",
            role = DrivetrainComponentRole.GYRO,
            hardwareId = "imu",
            required = true,
        )
        val wheelImu = pinpoint.copy(
            components = pinpoint.components.filterNot {
                it.role == DrivetrainComponentRole.ODOMETRY_SENSOR
            } + imu,
            localization = DrivetrainLocalizationDocument(
                primaryOdometry = DrivetrainLocalizationSourceDocument(
                    uid = "localization.wheel-imu",
                    source = LocalizationSourceKind.WHEEL_ENCODERS_IMU,
                    componentUids = pinpoint.components.filter {
                        it.role == DrivetrainComponentRole.DRIVE_MOTOR
                    }.map { it.uid } + imu.uid,
                    implementationClassName = "com.areslib.ftc.drivetrain.MecanumHardwareIO",
                ),
                headingSourceUid = "localization.wheel-imu",
            ),
            parameters = pinpoint.parameters.filterNot { it.key.startsWith("localization.pinpoint") },
        )
        val profile = canonicalProfile(wheelImu)

        val generated = DrivetrainKotlinGenerator.generateFtcMecanumRuntime(
            wheelImu,
            listOf(profile),
            "example.generated",
        )

        assertTrue(generated.content.contains("pinpointName = null"))
        assertTrue(generated.content.contains("hardwareMap.get(com.qualcomm.robotcore.hardware.IMU::class.java"))
        assertTrue(generated.content.contains("pinpointEncoderResolution = null"))
        assertTrue(!generated.content.contains("GeneratedAresTuningConfig.Parameters.LOCALIZATION_PINPOINT"))
        assertTrue(!generated.content.contains("FtcPinpointTuningState("))
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

    private fun runtimeReadyMecanumDocument(): DrivetrainDocument {
        val base = mecanumDocument()
        val positions = mapOf(
            "fl" to (0.16 to 0.17),
            "fr" to (0.16 to -0.17),
            "rl" to (-0.16 to 0.17),
            "rr" to (-0.16 to -0.17),
        )
        val components = base.components.map { component ->
            if (component.role == DrivetrainComponentRole.DRIVE_MOTOR) {
                val id = component.hardwareId
                val position = positions.getValue(id)
                component.copy(xMeters = position.first, yMeters = position.second, required = true)
            } else {
                component.copy(required = true)
            }
        }
        val parameters = runtimeParameterTypes().map { (key, type) ->
            TuningParameterDeclaration(
                uid = "runtime.${key.replace(Regex("[^A-Za-z0-9]+"), "-").lowercase()}",
                key = key,
                componentUid = base.uid,
                displayName = key,
                description = "Canonical $key value",
                type = type,
                minimum = if (type == TuningParameterType.DOUBLE) 0.0 else null,
                maximum = if (type == TuningParameterType.DOUBLE) 100_000.0 else null,
                defaultValue = when (type) {
                    TuningParameterType.BOOLEAN -> TuningValue(
                        booleanValue = key == "localization.pinpointCcwPositive",
                    )
                    TuningParameterType.DOUBLE -> TuningValue(
                        doubleValue = when (key) {
                            "drive.feedforwardKv" -> 1.0
                            "drive.ticksPerMeter" -> 2_000.0
                            else -> 0.1
                        },
                    )
                    else -> error("Unsupported test parameter type")
                },
                applyPolicy = TuningApplyPolicy.DISABLED_ONLY,
            )
        }
        val k = (base.geometry.trackWidthMeters + base.geometry.wheelBaseMeters) * 0.5
        return base.copy(
            components = components,
            geometry = base.geometry.copy(
                maxAngularSpeedRadiansPerSecond = base.geometry.maxLinearSpeedMetersPerSecond / k,
            ),
            parameters = parameters,
        )
    }

    private fun canonicalProfile(document: DrivetrainDocument): TuningProfileDocument =
        TuningProfileDocument(
            uid = document.canonicalProfileUid,
            profileId = "competition",
            displayName = "Competition",
            description = "Canonical competition profile",
            projectUid = "project.ftc",
            drivebaseUid = document.uid,
            authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
            values = document.parameters.map { TuningAssignment(it.uid, it.defaultValue) },
        )

    private fun runtimeParameterTypes(): Map<String, TuningParameterType> = linkedMapOf(
        "drive.closedLoopVelocity" to TuningParameterType.BOOLEAN,
        "drive.feedforwardKs" to TuningParameterType.DOUBLE,
        "drive.feedforwardKv" to TuningParameterType.DOUBLE,
        "drive.feedforwardKa" to TuningParameterType.DOUBLE,
        "drive.motorKp" to TuningParameterType.DOUBLE,
        "drive.motorKi" to TuningParameterType.DOUBLE,
        "drive.motorKd" to TuningParameterType.DOUBLE,
        "drive.motorKf" to TuningParameterType.DOUBLE,
        "drive.headingKp" to TuningParameterType.DOUBLE,
        "drive.headingKi" to TuningParameterType.DOUBLE,
        "drive.headingKd" to TuningParameterType.DOUBLE,
        "drive.headingDeadzoneDeg" to TuningParameterType.DOUBLE,
        "drive.pathTranslationKp" to TuningParameterType.DOUBLE,
        "drive.pathTranslationKd" to TuningParameterType.DOUBLE,
        "drive.pathRotationKp" to TuningParameterType.DOUBLE,
        "drive.pathRotationKd" to TuningParameterType.DOUBLE,
        "drive.pathVelocityScale" to TuningParameterType.DOUBLE,
        "drive.pathAccelerationLimit" to TuningParameterType.DOUBLE,
        "drive.ticksPerMeter" to TuningParameterType.DOUBLE,
        "localization.pinpointCcwPositive" to TuningParameterType.BOOLEAN,
        "localization.pinpointXOffsetMm" to TuningParameterType.DOUBLE,
        "localization.pinpointYOffsetMm" to TuningParameterType.DOUBLE,
        "localization.pinpointEncoderResolution" to TuningParameterType.DOUBLE,
        "localization.pinpointXReversed" to TuningParameterType.BOOLEAN,
        "localization.pinpointYReversed" to TuningParameterType.BOOLEAN,
        "localization.ekfQx" to TuningParameterType.DOUBLE,
        "localization.ekfQy" to TuningParameterType.DOUBLE,
        "localization.ekfQtheta" to TuningParameterType.DOUBLE,
    )
}
