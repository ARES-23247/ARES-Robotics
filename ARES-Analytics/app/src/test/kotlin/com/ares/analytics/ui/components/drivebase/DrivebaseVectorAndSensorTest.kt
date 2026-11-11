package com.ares.analytics.ui.components.drivebase

import com.ares.analytics.service.drivebase.DriveGeometry
import com.ares.analytics.service.drivebase.DriveHardwareDeclaration
import com.ares.analytics.service.drivebase.DriveHardwareRole
import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.viewmodel.drivebase.DriveLabState
import com.ares.analytics.viewmodel.drivebase.evaluateDriveLab
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrivebaseVectorAndSensorTest {

    @Test
    fun `computeDriveWheelVectors maps mecanum swerve and differential wheel vectors`() {
        val mecanumLab = evaluateDriveLab(
            kind = DrivebaseKind.FTC_MECANUM,
            input = DriveLabState(forward = 0.8, strafe = 0.0, rotate = 0.0),
            geometry = DriveGeometry(),
        )
        val mecanumVectors = computeDriveWheelVectors(DrivebaseKind.FTC_MECANUM, mecanumLab)
        assertEquals(4, mecanumVectors.size)
        assertTrue(mecanumVectors.all { abs(it.output - 0.8) < 1e-6 && abs(it.angleDegrees - 0.0) < 1e-6 })

        val swerveLab = evaluateDriveLab(
            kind = DrivebaseKind.FRC_CTRE_SWERVE,
            input = DriveLabState(forward = 0.0, strafe = 1.0, rotate = 0.0),
            geometry = DriveGeometry(wheelBaseMeters = 0.5, trackWidthMeters = 0.5),
        )
        val swerveVectors = computeDriveWheelVectors(DrivebaseKind.FRC_CTRE_SWERVE, swerveLab)
        assertEquals(4, swerveVectors.size)
        assertTrue(swerveVectors.all { abs(it.output - 1.0) < 1e-6 && abs(it.angleDegrees - 90.0) < 1e-4 })

        val diffLab = evaluateDriveLab(
            kind = DrivebaseKind.DIFFERENTIAL,
            input = DriveLabState(forward = 0.0, strafe = 0.0, rotate = 0.5),
            geometry = DriveGeometry(),
        )
        val diffVectors = computeDriveWheelVectors(DrivebaseKind.DIFFERENTIAL, diffLab)
        assertEquals(2, diffVectors.size)
        val left = diffVectors.first { it.id == "left" }
        val right = diffVectors.first { it.id == "right" }
        assertEquals(0.5, left.output, 1e-6)
        assertEquals(0.0, left.angleDegrees, 1e-6)
        assertEquals(-0.5, right.output, 1e-6)
        assertEquals(180.0, right.angleDegrees, 1e-6)
    }

    @Test
    fun `driveCommandPresetsForKind omits strafe presets for differential drivebase`() {
        val diffPresets = driveCommandPresetsForKind(DrivebaseKind.DIFFERENTIAL)
        val swervePresets = driveCommandPresetsForKind(DrivebaseKind.FRC_CTRE_SWERVE)
        assertFalse(diffPresets.any { it.strafe != 0.0 })
        assertTrue(swervePresets.any { it.strafe != 0.0 })
    }

    @Test
    fun `extractLocalizationSensorVisuals filters sensors and preserves offsets and camera pose`() {
        val hardware = listOf(
            DriveHardwareDeclaration(
                id = "fl_drive",
                displayName = "Front Left Drive",
                role = DriveHardwareRole.FRONT_LEFT,
            ),
            DriveHardwareDeclaration(
                id = "pinpoint",
                displayName = "goBILDA Pinpoint",
                role = DriveHardwareRole.ODOMETRY,
                xMeters = -0.05,
                yMeters = 0.02,
                zMeters = 0.03,
            ),
            DriveHardwareDeclaration(
                id = "limelight_front",
                displayName = "Limelight 3A Front",
                role = DriveHardwareRole.LIMELIGHT,
                xMeters = 0.18,
                yMeters = -0.08,
                zMeters = 0.24,
                yawDegrees = 15.0,
                pitchDegrees = 20.0,
            ),
        )

        val visuals = extractLocalizationSensorVisuals(hardware)
        assertEquals(2, visuals.size)
        val odo = visuals.first { it.id == "pinpoint" }
        assertFalse(odo.isVisionCamera)
        assertEquals(-0.05, odo.xMeters)
        assertEquals(0.02, odo.yMeters)

        val cam = visuals.first { it.id == "limelight_front" }
        assertTrue(cam.isVisionCamera)
        assertEquals(0.18, cam.xMeters)
        assertEquals(-0.08, cam.yMeters)
        assertEquals(0.24, cam.zMeters)
        assertEquals(15.0, cam.yawDegrees)
        assertEquals(20.0, cam.pitchDegrees)
    }
}
