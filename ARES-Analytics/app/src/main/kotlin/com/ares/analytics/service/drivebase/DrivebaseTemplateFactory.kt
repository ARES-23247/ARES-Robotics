package com.ares.analytics.service.drivebase

import com.ares.analytics.shared.models.League
import com.areslib.drivetrain.*

internal fun canonicalTemplate(projectId: String, kind: DrivebaseKind, league: League): DrivetrainDocument {
    require(kind.runtimeSupport(league) != DrivebaseRuntimeSupport.UNAVAILABLE_FOR_LEAGUE) {
        "$kind is not available for ${league.name}"
    }
    fun drive(
        uid: String,
        hardware: String,
        inverted: Boolean = false,
        module: String? = null,
        xMeters: Double? = null,
        yMeters: Double? = null,
    ) = DrivetrainComponentDocument(
        uid,
        uid.substringAfterLast('.').replace('-', ' ').replaceFirstChar(Char::uppercase),
        DrivetrainComponentRole.DRIVE_MOTOR,
        hardware,
        moduleUid = module,
        currentMeasurementRequired = league != League.XRP,
        currentMeasurementAvailable = league != League.XRP,
        inverted = inverted,
        xMeters = xMeters,
        yMeters = yMeters,
    )
    val components = when (kind) {
        DrivebaseKind.FTC_MECANUM -> if (league == League.XRP) listOf(
            drive("drive.front-left", "1", xMeters = .07, yMeters = .0775),
            drive("drive.front-right", "2", xMeters = .07, yMeters = -.0775),
            drive("drive.rear-left", "3", xMeters = -.07, yMeters = .0775),
            drive("drive.rear-right", "4", xMeters = -.07, yMeters = -.0775),
        ) else listOf(
            drive("drive.front-left", "fl", xMeters = .18, yMeters = .18),
            drive("drive.front-right", "fr", inverted = true, xMeters = .18, yMeters = -.18),
            drive("drive.rear-left", "rl", xMeters = -.18, yMeters = .18),
            drive("drive.rear-right", "rr", inverted = true, xMeters = -.18, yMeters = -.18),
            DrivetrainComponentDocument("drive.pinpoint", "goBILDA Pinpoint", DrivetrainComponentRole.ODOMETRY_SENSOR, "pinpoint"),
        )
        DrivebaseKind.DIFFERENTIAL -> if (league == League.XRP) listOf(
            drive("drive.left", "1", xMeters = 0.0, yMeters = .0775),
            drive("drive.right", "2", xMeters = 0.0, yMeters = -.0775),
        ) else listOf(
            drive("drive.left", "leftLeader"),
            drive("drive.right", "rightLeader", true),
            DrivetrainComponentDocument("drive.gyro", "Gyro", DrivetrainComponentRole.GYRO, "gyro"),
        )
        DrivebaseKind.FRC_CTRE_SWERVE -> listOf("front-left", "front-right", "rear-left", "rear-right").flatMapIndexed { moduleIndex, corner ->
            val module = "module.$corner"
            val firstSimulationCanId = moduleIndex * 3 + 1
            listOf(
                drive("drive.$corner", firstSimulationCanId.toString(), module = module),
                DrivetrainComponentDocument("steer.$corner", "${corner.replace('-', ' ')} steer", DrivetrainComponentRole.STEER_MOTOR, (firstSimulationCanId + 1).toString(), moduleUid = module),
                DrivetrainComponentDocument("encoder.$corner", "${corner.replace('-', ' ')} encoder", DrivetrainComponentRole.ABSOLUTE_ENCODER, (firstSimulationCanId + 2).toString(), moduleUid = module)
            )
        } + DrivetrainComponentDocument("drive.gyro", "Pigeon gyro", DrivetrainComponentRole.GYRO, "13")
        DrivebaseKind.CUSTOM -> listOf(drive("drive.custom", "custom"), DrivetrainComponentDocument("drive.gyro", "Gyro", DrivetrainComponentRole.GYRO, "gyro"))
    }
    val modules = if (kind == DrivebaseKind.FRC_CTRE_SWERVE) listOf("front-left", "front-right", "rear-left", "rear-right").map { corner ->
        val x = if (corner.startsWith("front")) .28 else -.28
        val y = if (corner.endsWith("left")) .28 else -.28
        DrivetrainModuleDocument("module.$corner", corner.replace('-', ' ').replaceFirstChar(Char::uppercase), listOf("drive.$corner", "steer.$corner", "encoder.$corner"), x, y)
    } else emptyList()
    val primary = when (kind) {
        DrivebaseKind.FTC_MECANUM -> if (league == League.XRP) {
            DrivetrainLocalizationSourceDocument("localization.wheel-imu", LocalizationSourceKind.WHEEL_ENCODERS_IMU, components.map { it.uid })
        } else {
            DrivetrainLocalizationSourceDocument("localization.pinpoint", LocalizationSourceKind.PINPOINT, listOf("drive.pinpoint"))
        }
        DrivebaseKind.FRC_CTRE_SWERVE -> DrivetrainLocalizationSourceDocument("localization.ctre", LocalizationSourceKind.CTRE_VENDOR, components.map { it.uid })
        else -> DrivetrainLocalizationSourceDocument("localization.wheel-imu", LocalizationSourceKind.WHEEL_ENCODERS_IMU, components.map { it.uid })
    }
    val diameter = if (league == League.XRP) .060 else .096
    val document = DrivetrainDocument(
        uid = "drive.primary", drivebaseId = "primary", displayName = "Primary drivebase", description = "Robot-owned drivebase contract.",
        kind = when (kind) { DrivebaseKind.FTC_MECANUM -> DrivetrainKind.FTC_MECANUM; DrivebaseKind.FRC_CTRE_SWERVE -> DrivetrainKind.FRC_CTRE_SWERVE; DrivebaseKind.DIFFERENTIAL -> DrivetrainKind.DIFFERENTIAL; DrivebaseKind.CUSTOM -> DrivetrainKind.ADVANCED_CUSTOM },
        platform = when (league) {
            League.FTC -> DrivetrainPlatform.FTC
            League.FRC -> DrivetrainPlatform.FRC
            League.XRP -> DrivetrainPlatform.XRP
        },
        components = components, modules = modules,
        geometry = DrivetrainGeometryDocument(
            diameter,
            if (league == League.XRP) .155 else .36,
            if (league == League.XRP) .140 else .36,
            1.0,
            if (kind == DrivebaseKind.FRC_CTRE_SWERVE) 1.0 else null,
            if (league == League.XRP) .85 else if (kind == DrivebaseKind.FTC_MECANUM) 1.0 else 3.0,
            if (league == League.XRP) 5.0 else if (kind == DrivebaseKind.FTC_MECANUM) 1.0 / .36 else 6.0,
        ),
        localization = DrivetrainLocalizationDocument(primary, components.firstOrNull { it.role == DrivetrainComponentRole.GYRO }?.uid ?: primary.uid),
        control = DrivetrainControlDocument(
            listOf(DrivetrainControlKind.OPEN_LOOP, DrivetrainControlKind.CHASSIS_VELOCITY),
            if (kind == DrivebaseKind.FTC_MECANUM) DrivetrainControlKind.CHASSIS_VELOCITY else DrivetrainControlKind.OPEN_LOOP,
            fieldCentric = league != League.XRP,
        ),
        safety = DrivetrainSafetyDocument(currentValidityRequired = league != League.XRP),
        simulation = if (league == League.XRP) {
            DrivetrainSimulationDocument("ares_micro.simulation.XrpModel", "ares_micro.simulation.XrpAdapter")
        } else {
            DrivetrainSimulationDocument("com.areslib.simulator.DrivetrainModel", "com.areslib.simulator.DrivetrainAdapter")
        },
        parameters = emptyList(),
        ctreImport = if (kind == DrivebaseKind.FRC_CTRE_SWERVE) CtreSwerveImportDocument("src/main/java/frc/robot/generated/TunerConstants.java", "0".repeat(64), "CTRE Tuner", "unknown", "frc.robot.generated.TunerConstants", "rio") else null,
        canonicalProfileUid = "$projectId.profile.competition"
    )
    return if (league == League.FTC && kind == DrivebaseKind.FTC_MECANUM) {
        FtcMecanumRuntimeParameters.reconcile(document)
    } else {
        document
    }
}
