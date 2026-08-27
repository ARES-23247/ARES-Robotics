package com.ares.analytics.ui.input

import com.ares.analytics.shared.League
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDriveCommandMapperTest {
    @Test
    fun `forward drives toward opposing station along positive field Y from red`() {
        val command = mapDesktopFieldCentricDrive(
            league = League.FTC,
            forward = 1.0,
            right = 0.0,
            counterClockwise = 0.0,
        )

        assertEquals(0.0, command.vxMetersPerSecond)
        assertEquals(4.0, command.vyMetersPerSecond)
        assertEquals(0.0, command.omegaRadiansPerSecond)
    }

    @Test
    fun `right strafe maps to positive field X and clockwise remains negative`() {
        val command = mapDesktopFieldCentricDrive(
            league = League.FTC,
            forward = 0.0,
            right = 1.0,
            counterClockwise = -1.0,
        )

        assertEquals(4.0, command.vxMetersPerSecond)
        assertEquals(0.0, command.vyMetersPerSecond)
        assertEquals(-4.0, command.omegaRadiansPerSecond)
    }

    @Test
    fun `FRC forward crosses the field along positive X from blue`() {
        val command = mapDesktopFieldCentricDrive(
            league = League.FRC,
            forward = 1.0,
            right = 0.0,
            counterClockwise = 0.0,
        )

        assertEquals(4.0, command.vxMetersPerSecond)
        assertEquals(-0.0, command.vyMetersPerSecond)
        assertEquals(0.0, command.omegaRadiansPerSecond)
    }

    @Test
    fun `FRC driver right is negative field Y and rotation remains CCW positive`() {
        val command = mapDesktopFieldCentricDrive(
            league = League.FRC,
            forward = 0.0,
            right = 1.0,
            counterClockwise = 1.0,
        )

        assertEquals(0.0, command.vxMetersPerSecond)
        assertEquals(-4.0, command.vyMetersPerSecond)
        assertEquals(Math.PI, command.omegaRadiansPerSecond)
    }

    @Test
    fun `robot centric mapping is league independent`() {
        val command = mapDesktopRobotCentricDrive(
            forward = 1.0,
            right = 1.0,
            counterClockwise = 1.0,
        )

        assertEquals(4.0, command.vxMetersPerSecond)
        assertEquals(-4.0, command.vyMetersPerSecond)
        assertEquals(4.0, command.omegaRadiansPerSecond)
    }

    @Test
    fun `desktop frames always select field centric and preserve alliance for season mirroring`() {
        val redFlags = desktopDriveModeFlags(isRedAlliance = true)
        val blueFlags = desktopDriveModeFlags(isRedAlliance = false)

        assertTrue(redFlags and (1L shl 3) != 0L)
        assertTrue(redFlags and (1L shl 4) != 0L)
        assertTrue(redFlags and (1L shl 5) != 0L)
        assertTrue(blueFlags and (1L shl 3) != 0L)
        assertTrue(blueFlags and (1L shl 4) != 0L)
        assertEquals(0L, blueFlags and (1L shl 5))
    }

    @Test
    fun `non finite desktop input fails closed`() {
        val command = mapDesktopFieldCentricDrive(
            league = League.FTC,
            forward = Double.NaN,
            right = Double.POSITIVE_INFINITY,
            counterClockwise = Double.NEGATIVE_INFINITY,
        )

        assertEquals(DesktopFieldDriveCommand(0.0, 0.0, 0.0), command)
    }
}
