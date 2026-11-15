package com.ares.analytics.mcp.engine

import com.areslib.drivetrain.DrivetrainDocument
import com.areslib.drivetrain.DrivetrainKind
import com.areslib.drivetrain.DrivetrainPlatform
import com.areslib.drivetrain.LocalizationSourceKind
import com.areslib.drivetrain.drivetrain
import com.areslib.drivetrain.frcCtreSwerveDrivetrain
import com.areslib.drivetrain.ftcMecanumDrivetrain
import com.areslib.project.AresLeague

/**
 * Builds complete, schema-validated [DrivetrainDocument] presets (FTC mecanum, FRC CTRE swerve,
 * XRP differential, and generic differential) with baseline tuning declarations.
 */
internal object DrivetrainPresetBuilder {
    fun build(
        presetRaw: String,
        league: AresLeague,
        drivebaseId: String,
        driveUid: String,
        canonicalProfileUid: String,
    ): DrivetrainDocument {
        val preset = presetRaw.trim().uppercase()
        return when (preset) {
            "FTC_MECANUM" -> {
                require(league == AresLeague.FTC) { "FTC_MECANUM preset requires an FTC project (found ${league.name})." }
                ftcMecanumDrivetrain(
                    id = drivebaseId,
                    uid = driveUid,
                    canonicalProfileUid = canonicalProfileUid,
                ) {
                    tuningDouble(
                        key = "drive.headingKp",
                        defaultValue = 1.8,
                        minimum = 0.0,
                        maximum = 20.0,
                        unit = "rad/s per rad",
                    )
                }
            }
            "FRC_CTRE_SWERVE" -> {
                require(league == AresLeague.FRC) { "FRC_CTRE_SWERVE preset requires an FRC project (found ${league.name})." }
                frcCtreSwerveDrivetrain(
                    id = drivebaseId,
                    vendorSourcePath = "src/main/java/frc/robot/generated/TunerConstants.java",
                    sourceSha256 = "0".repeat(64),
                    drivetrainConstantsClassName = "frc.robot.generated.TunerConstants",
                    canBusName = "rio",
                    uid = driveUid,
                    canonicalProfileUid = canonicalProfileUid,
                ) {
                    tuningDouble(
                        key = "drive.pathTranslationKp",
                        defaultValue = 1.8,
                        minimum = 0.0,
                        maximum = 20.0,
                    )
                    tuningDouble(
                        key = "drive.pathRotationKp",
                        defaultValue = 3.0,
                        minimum = 0.0,
                        maximum = 20.0,
                    )
                    tuningDouble(
                        key = "drive.pathVelocityScale",
                        defaultValue = 0.5,
                        minimum = 0.05,
                        maximum = 1.0,
                    )
                    tuningDouble(
                        key = "drive.pathAccelerationLimit",
                        defaultValue = 2.0,
                        minimum = 0.05,
                        maximum = 20.0,
                        unit = "m/s^2",
                    )
                }
            }
            "XRP_DIFFERENTIAL" -> {
                require(league == AresLeague.XRP) { "XRP_DIFFERENTIAL preset requires an XRP project (found ${league.name})." }
                buildDifferential(
                    platform = DrivetrainPlatform.XRP,
                    drivebaseId = drivebaseId,
                    driveUid = driveUid,
                    canonicalProfileUid = canonicalProfileUid,
                    wheelDiameterMeters = 0.060,
                    trackWidthMeters = 0.155,
                    wheelBaseMeters = 0.140,
                    maxLinearMps = 0.85,
                    maxAngularRadPerSec = 5.0,
                    fieldCentric = false,
                )
            }
            "DIFFERENTIAL" -> {
                val platform = when (league) {
                    AresLeague.FTC -> DrivetrainPlatform.FTC
                    AresLeague.FRC -> DrivetrainPlatform.FRC
                    AresLeague.XRP -> DrivetrainPlatform.XRP
                }
                buildDifferential(
                    platform = platform,
                    drivebaseId = drivebaseId,
                    driveUid = driveUid,
                    canonicalProfileUid = canonicalProfileUid,
                    wheelDiameterMeters = if (platform == DrivetrainPlatform.XRP) 0.060 else 0.1016,
                    trackWidthMeters = if (platform == DrivetrainPlatform.XRP) 0.155 else 0.50,
                    wheelBaseMeters = if (platform == DrivetrainPlatform.XRP) 0.140 else 0.50,
                    maxLinearMps = if (platform == DrivetrainPlatform.XRP) 0.85 else 3.0,
                    maxAngularRadPerSec = if (platform == DrivetrainPlatform.XRP) 5.0 else 6.0,
                    fieldCentric = false,
                )
            }
            else -> throw IllegalArgumentException(
                "Unknown drivetrain preset '$presetRaw'; expected FTC_MECANUM, FRC_CTRE_SWERVE, XRP_DIFFERENTIAL, or DIFFERENTIAL.",
            )
        }
    }

    private fun buildDifferential(
        platform: DrivetrainPlatform,
        drivebaseId: String,
        driveUid: String,
        canonicalProfileUid: String,
        wheelDiameterMeters: Double,
        trackWidthMeters: Double,
        wheelBaseMeters: Double,
        maxLinearMps: Double,
        maxAngularRadPerSec: Double,
        fieldCentric: Boolean,
    ): DrivetrainDocument = drivetrain(
        id = drivebaseId,
        kind = DrivetrainKind.DIFFERENTIAL,
        platform = platform,
        uid = driveUid,
        canonicalProfileUid = canonicalProfileUid,
    ) {
        description = "${platform.name} two-side differential drivetrain preset."
        geometry(
            wheelDiameterMeters = wheelDiameterMeters,
            trackWidthMeters = trackWidthMeters,
            wheelBaseMeters = wheelBaseMeters,
            driveGearRatio = 1.0,
            maxLinearSpeedMetersPerSecond = maxLinearMps,
            maxAngularSpeedRadiansPerSecond = maxAngularRadPerSec,
        )
        val hasCurrent = platform != DrivetrainPlatform.XRP
        val left = driveMotor(
            hardwareId = "left",
            uid = "drive.motor.left",
            displayName = "Left Drive Motor",
            currentMeasurementRequired = hasCurrent,
            currentMeasurementAvailable = hasCurrent,
        )
        val right = driveMotor(
            hardwareId = "right",
            uid = "drive.motor.right",
            displayName = "Right Drive Motor",
            inverted = true,
            currentMeasurementRequired = hasCurrent,
            currentMeasurementAvailable = hasCurrent,
        )
        val imu = gyro("imu", uid = "drive.gyro.imu", displayName = "Drive IMU")
        localization(
            primarySource = LocalizationSourceKind.WHEEL_ENCODERS_IMU,
            primaryComponentUids = listOf(left.uid, right.uid),
            headingSourceUid = imu.uid,
            primaryUid = "localization.differential",
        )
        control(fieldCentric = fieldCentric)
        safety(currentValidityRequired = hasCurrent)
        if (platform == DrivetrainPlatform.XRP) {
            simulation("ares_micro.simulation.XrpDifferentialModel", "ares_micro.simulation.XrpDifferentialAdapter")
        }
    }
}
