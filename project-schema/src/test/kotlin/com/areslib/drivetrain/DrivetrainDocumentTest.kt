package com.areslib.drivetrain

import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DrivetrainDocumentTest {
    @Test
    fun `mecanum contract is strict deterministic and round trips`() {
        val document = mecanum()
        val json = DrivetrainDocumentCodec.encode(document)
        val decoded = DrivetrainDocumentCodec.decode(json)

        assertEquals(
            document.copy(control = document.control.copy(supported = document.control.supported.sortedBy { it.name })),
            decoded,
        )
        assertEquals(64, DrivetrainDocumentCodec.contentHash(document).length)
        assertTrue(validateDrivetrainDocument(document).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            DrivetrainDocumentCodec.decode(json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 0"))
        }
    }

    @Test
    fun `invalid localization safety ownership and geometry fail closed`() {
        val broken = mecanum().copy(
            geometry = mecanum().geometry.copy(trackWidthMeters = Double.NaN),
            localization = mecanum().localization.copy(headingCcwPositive = false, headingSourceUid = "missing"),
            safety = mecanum().safety.copy(faultLatchingRequired = false),
            parameters = mecanum().parameters.map { it.copy(componentUid = "unknown.component") },
            components = mecanum().components.map { it.copy(currentMeasurementAvailable = false) },
        )
        val messages = validateDrivetrainDocument(broken).map { it.message }
        assertTrue(messages.any { it.contains("Geometry") })
        assertTrue(messages.any { it.contains("CCW-positive") })
        assertTrue(messages.any { it.contains("Heading source") })
        assertTrue(messages.any { it.contains("fail closed") })
        assertTrue(messages.any { it.contains("Unknown component/module/drivebase") })
        assertTrue(messages.any { it.contains("current measurement") })
    }

    @Test
    fun `CTRE swerve requires four explicit complete modules and read-only vendor provenance`() {
        val base = mecanum().copy(
            uid = "drive.swerve",
            drivebaseId = "swerve",
            kind = DrivetrainKind.FRC_CTRE_SWERVE,
            platform = DrivetrainPlatform.FRC,
            ctreImport = CtreSwerveImportDocument(
                vendorSourcePath = "src/main/java/example/TunerConstants.java",
                sourceSha256 = "a".repeat(64),
                generatorName = "CTRE Tuner X",
                generatorVersion = "2026.1",
                drivetrainConstantsClassName = "example.TunerConstants",
                canBusName = "CAN2",
            ),
        )
        assertTrue(validateDrivetrainDocument(base).any { it.path == "modules" })
    }

    @Test
    fun `CTRE modules require bijective drive steer and encoder membership`() {
        fun component(uid: String, role: DrivetrainComponentRole) = DrivetrainComponentDocument(
            uid, uid, role, uid, moduleUid = "module.front-left",
        )
        val drive = component("drive.front-left", DrivetrainComponentRole.DRIVE_MOTOR)
        val steer = component("steer.front-left", DrivetrainComponentRole.STEER_MOTOR)
        val encoder = component("encoder.front-left", DrivetrainComponentRole.ABSOLUTE_ENCODER)
        val broken = mecanum().copy(
            kind = DrivetrainKind.FRC_CTRE_SWERVE,
            platform = DrivetrainPlatform.FRC,
            components = listOf(drive, steer, encoder),
            modules = listOf(DrivetrainModuleDocument(
                "module.front-left", "Front left",
                listOf(drive.uid, drive.uid, steer.uid), 0.25, 0.25,
            )),
            ctreImport = CtreSwerveImportDocument(
                "TunerConstants.java", "a".repeat(64), "CTRE Tuner", "2026", "example.TunerConstants", "CAN2",
            ),
            localization = DrivetrainLocalizationDocument(
                DrivetrainLocalizationSourceDocument("localization.ctre", LocalizationSourceKind.CTRE_VENDOR, listOf(drive.uid)),
                "localization.ctre",
            ),
        )
        val issues = validateDrivetrainDocument(broken).map { it.message }
        assertTrue(issues.any { it.contains("duplicated in the module") })
        assertTrue(issues.any { it.contains("exactly one absolute encoder") })
        assertTrue(issues.any { it.contains("appear exactly once") })
    }

    companion object {
        fun mecanum(): DrivetrainDocument {
            val motors = listOf("fl", "fr", "rl", "rr").map { id ->
                DrivetrainComponentDocument(
                    uid = "drive.motor.$id", displayName = id.uppercase(), role = DrivetrainComponentRole.DRIVE_MOTOR,
                    hardwareId = id, controllerModel = "REV HD Hex", encoderModel = "Integrated encoder",
                    currentMeasurementRequired = true, currentMeasurementAvailable = true,
                )
            }
            val pinpoint = DrivetrainComponentDocument(
                uid = "drive.odometry.pinpoint", displayName = "Pinpoint", role = DrivetrainComponentRole.ODOMETRY_SENSOR,
                hardwareId = "pinpoint",
            )
            val geometry = DrivetrainGeometryDocument(0.096, 0.34, 0.32, 19.2, null, 2.2, 5.0)
            return DrivetrainDocument(
                uid = "drive.main", drivebaseId = "main-drive", displayName = "Competition mecanum",
                description = "Four-wheel FTC mecanum drivebase", kind = DrivetrainKind.FTC_MECANUM,
                platform = DrivetrainPlatform.FTC, components = motors + pinpoint, geometry = geometry,
                localization = DrivetrainLocalizationDocument(
                    primaryOdometry = DrivetrainLocalizationSourceDocument(
                        "localization.pinpoint", LocalizationSourceKind.PINPOINT, listOf(pinpoint.uid), "example.PinpointIO",
                    ),
                    headingSourceUid = pinpoint.uid,
                ),
                control = DrivetrainControlDocument(
                    listOf(DrivetrainControlKind.OPEN_LOOP, DrivetrainControlKind.CHASSIS_VELOCITY, DrivetrainControlKind.TRAJECTORY),
                    DrivetrainControlKind.CHASSIS_VELOCITY,
                ),
                simulation = DrivetrainSimulationDocument("example.MecanumModel", "example.MockMecanumIO"),
                parameters = listOf(
                    TuningParameterDeclaration(
                        uid = "drive.main.heading.kp", key = "drive.heading.kP", componentUid = "drive.main",
                        displayName = "Heading P", description = "Heading proportional gain", type = TuningParameterType.DOUBLE,
                        unit = "rad/s per rad", minimum = 0.0, maximum = 20.0, defaultValue = TuningValue(doubleValue = 1.8),
                        applyPolicy = TuningApplyPolicy.LIVE_SAFE,
                    )
                ),
                canonicalProfileUid = "profile.competition",
            )
        }
    }
}
