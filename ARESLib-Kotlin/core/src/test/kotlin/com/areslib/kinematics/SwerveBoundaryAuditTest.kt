package com.areslib.kinematics

import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SwerveBoundaryAuditTest {
    @Test fun `geometry is owned independently from the caller list`() {
        val supplied = mutableListOf(Translation2d(0.5, 0.5))
        val kinematics = SwerveKinematics(supplied)
        supplied.clear()
        val result = kinematics.toSwerveModuleStates(ChassisSpeeds(0.0, 0.0, 1.0))
        assertEquals(1, kinematics.moduleTranslations.size)
        assertEquals(-0.5, result[0].speedMetersPerSecond * result[0].angle.cos, 1e-12)
        assertEquals(0.5, result[0].speedMetersPerSecond * result[0].angle.sin, 1e-12)
        assertFailsWith<UnsupportedOperationException> {
            (kinematics.moduleTranslations as MutableList<Translation2d>).clear()
        }
    }

    @Test fun `short output rejects before changing output or steering history`() {
        val positions = listOf(Translation2d(0.5, 0.5), Translation2d(-0.5, -0.5))
        val checked = SwerveKinematics(positions)
        val reference = SwerveKinematics(positions)
        val initial = ChassisSpeeds(1.0, 1.0, 0.0)
        checked.toSwerveModuleStates(initial); reference.toSwerveModuleStates(initial)
        val short = arrayOf(SwerveModuleState(123.0, Rotation2d(0.4)))
        assertFailsWith<IllegalArgumentException> { checked.toSwerveModuleStates(ChassisSpeeds(), 0.02, short) }
        assertEquals(123.0, short[0].speedMetersPerSecond)
        assertEquals(0.4, short[0].angle.radians)
        assertEquals(reference.toSwerveModuleStates(initial).toList(), checked.toSwerveModuleStates(initial).toList())
    }

    @Test fun `aliased outputs reject before changing a module`() {
        val kinematics = SwerveKinematics(Translation2d(0.5, 0.5), Translation2d(-0.5, -0.5))
        val shared = SwerveModuleState(12.0)
        assertFailsWith<IllegalArgumentException> {
            kinematics.toSwerveModuleStates(ChassisSpeeds(1.0, 0.0, 1.0), 0.02, arrayOf(shared, shared))
        }
        assertEquals(12.0, shared.speedMetersPerSecond)
    }

    @Test fun `desaturation rejects aliases instead of scaling the same wheel twice`() {
        val kinematics = SwerveKinematics(emptyList())
        val shared = SwerveModuleState(4.0)
        assertFailsWith<IllegalArgumentException> { kinematics.desaturateWheelSpeeds(arrayOf(shared, shared), 2.0) }
        assertEquals(4.0, shared.speedMetersPerSecond)
    }

    @Test fun `nonfinite geometry and invalid rate limits reject at construction`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { SwerveKinematics(Translation2d(bad, 0.0)) }
            assertFailsWith<IllegalArgumentException> { SwerveKinematics(Translation2d(0.0, bad)) }
        }
        for (bad in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { SwerveKinematics(emptyList(), maxSteerVelRadPerSec = bad) }
            assertFailsWith<IllegalArgumentException> { SwerveKinematics(emptyList(), maxSteerAccelRadPerSec2 = bad) }
            assertFailsWith<IllegalArgumentException> { SwerveKinematics(emptyList(), maxDriveAccelMps2 = bad) }
        }
    }

    @Test fun `small nonzero commands retain their direction`() {
        val kinematics = SwerveKinematics(Translation2d())
        val state = kinematics.toSwerveModuleStates(ChassisSpeeds(0.0, 1e-10, 0.0))[0]
        assertEquals(1e-10, state.speedMetersPerSecond, 1e-25)
        assertEquals(Math.PI / 2.0, state.angle.radians, 1e-14)
    }

    @Test fun `reset clears all steering history including held zero-speed angles`() {
        val kinematics = SwerveKinematics(Translation2d())
        kinematics.toSwerveModuleStates(ChassisSpeeds(1.0, 1.0, 0.0))
        kinematics.reset()
        assertEquals(0.0, kinematics.toSwerveModuleStates(ChassisSpeeds())[0].angle.radians)
    }

    @Test fun `overflowing module vector neutralizes the whole coupled output`() {
        val kinematics = SwerveKinematics(Translation2d(), Translation2d(Double.MAX_VALUE, 0.0))
        val states = kinematics.toSwerveModuleStates(ChassisSpeeds(1.0, 0.0, 2.0))
        for (state in states) {
            assertEquals(0.0, state.speedMetersPerSecond)
            assertTrue(state.angle.radians.isFinite())
        }
    }

    @Test fun `overflowing rotational product preserves representable cancellation`() {
        val kinematics = SwerveKinematics(Translation2d(0.0, 2.0))
        val state = kinematics.toSwerveModuleStates(ChassisSpeeds(Double.MAX_VALUE, 0.0, Double.MAX_VALUE))[0]
        assertEquals(-Double.MAX_VALUE, state.speedMetersPerSecond)
        assertEquals(0.0, state.angle.radians, 1e-14)
    }

    @Test fun `invalid optimizer input cannot produce nonfinite actuator targets`() {
        val kinematics = SwerveKinematics(emptyList())
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val invalidSpeed = kinematics.optimizeModuleState(SwerveModuleState(bad), Rotation2d(0.4))
            assertEquals(0.0, invalidSpeed.speedMetersPerSecond)
            assertEquals(0.4, invalidSpeed.angle.radians)
            val invalidAngle = kinematics.optimizeModuleState(SwerveModuleState(1.0, Rotation2d(bad)), Rotation2d(0.4))
            assertEquals(0.0, invalidAngle.speedMetersPerSecond)
            assertEquals(0.4, invalidAngle.angle.radians)
            val invalidCurrent = kinematics.optimizeModuleState(SwerveModuleState(1.0), Rotation2d(bad))
            assertEquals(0.0, invalidCurrent.speedMetersPerSecond)
            assertEquals(0.0, invalidCurrent.angle.radians)
        }
    }
}
