package com.areslib.kinematics

import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.math.wrapAngle
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SwerveDynamicsAuditTest {
    @Test fun `variable-step commands respect drive and steering derivative bounds`() {
        val rng = Random(4701)
        val kinematics = SwerveKinematics(listOf(Translation2d(0.3, 0.2), Translation2d(-0.3, -0.2)), 3.0, 5.0, 4.0)
        var previous = kinematics.toSwerveModuleStates(ChassisSpeeds())
        val previousSteerVelocity = DoubleArray(2)
        repeat(5000) { tick ->
            val dt = listOf(0.01, 0.02, 0.04)[tick % 3]
            val speeds = ChassisSpeeds(rng.nextDouble(-4.0, 4.0), rng.nextDouble(-4.0, 4.0), rng.nextDouble(-8.0, 8.0))
            val next = kinematics.toSwerveModuleStates(speeds, dt)
            for (i in next.indices) {
                val steerVelocity = wrapAngle(next[i].angle.radians - previous[i].angle.radians) / dt
                assertTrue(abs(steerVelocity) <= 3.0 + 1e-12, "velocity tick=$tick module=$i")
                assertTrue(abs(steerVelocity - previousSteerVelocity[i]) <= 5.0 * dt + 2e-12,
                    "acceleration tick=$tick module=$i")
                assertTrue(abs(next[i].speedMetersPerSecond - previous[i].speedMetersPerSecond) <= 4.0 * dt + 1e-12)
                previousSteerVelocity[i] = steerVelocity
            }
            previous = next
        }
    }

    @Test fun `initial call seeds unconstrained targets and zero limits freeze subsequent commands`() {
        val kinematics = SwerveKinematics(listOf(Translation2d()), 0.0, 0.0, 0.0)
        val seed = kinematics.toSwerveModuleStates(ChassisSpeeds(1.0, 1.0, 0.0))[0]
        assertEquals(Math.sqrt(2.0), seed.speedMetersPerSecond, 1e-12)
        val next = kinematics.toSwerveModuleStates(ChassisSpeeds(-2.0, 1.0, 0.0))[0]
        assertEquals(seed, next)
        val stopped = kinematics.toSwerveModuleStates(ChassisSpeeds())[0]
        assertEquals(0.0, stopped.speedMetersPerSecond)
        assertEquals(seed.angle, stopped.angle)
    }

    @Test fun `overflowing allowed drive change reaches finite opposite target`() {
        val kinematics = SwerveKinematics(listOf(Translation2d()), maxDriveAccelMps2 = Double.MAX_VALUE)
        kinematics.toSwerveModuleStates(ChassisSpeeds(Double.MAX_VALUE, 0.0, 0.0))
        val state = kinematics.toSwerveModuleStates(ChassisSpeeds(-Double.MAX_VALUE, 0.0, 0.0), 2.0)[0]
        assertEquals(-Double.MAX_VALUE, state.speedMetersPerSecond)
        assertEquals(0.0, state.angle.radians)
    }

    @Test fun `invalid time and command neutralize while preserving last valid angle`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
            val kinematics = SwerveKinematics(Translation2d())
            val seed = kinematics.toSwerveModuleStates(ChassisSpeeds(1.0, 1.0, 0.0))[0]
            val stopped = kinematics.toSwerveModuleStates(ChassisSpeeds(1.0, 0.0, 0.0), bad)[0]
            assertEquals(0.0, stopped.speedMetersPerSecond)
            assertEquals(seed.angle, stopped.angle)
            val resumed = kinematics.toSwerveModuleStates(ChassisSpeeds(1.0, 0.0, 0.0))[0]
            assertTrue(abs(resumed.speedMetersPerSecond) <= 8.0 * 0.02)
            val invalid = kinematics.toSwerveModuleStates(ChassisSpeeds(Double.NaN, 0.0, 0.0))[0]
            assertEquals(0.0, invalid.speedMetersPerSecond)
        }
    }

    @Test fun `output ownership and spare capacity cannot alter solver state`() {
        val kinematics = SwerveKinematics(Translation2d())
        val command = ChassisSpeeds(1.0, 1.0, 0.0)
        val owned = kinematics.toSwerveModuleStates(command)
        val expected = owned[0].copy()
        owned[0].speedMetersPerSecond = 100.0; owned[0].angle = Rotation2d(-2.0)
        val extra = SwerveModuleState(42.0, Rotation2d(0.3))
        val output = arrayOf(SwerveModuleState(), extra)
        kinematics.toSwerveModuleStates(command, 0.02, output)
        assertEquals(expected, output[0])
        assertEquals(SwerveModuleState(42.0, Rotation2d(0.3)), extra)
        val empty = SwerveKinematics(emptyList())
        empty.toSwerveModuleStates(command, 0.02, output)
        assertEquals(expected, output[0])
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun `null Java output element rejects before changing preceding outputs`() {
        val kinematics = SwerveKinematics(Translation2d(), Translation2d(0.2, 0.2))
        val first = SwerveModuleState(42.0)
        val output = arrayOf<SwerveModuleState?>(first, null) as Array<SwerveModuleState>
        assertFailsWith<IllegalArgumentException> { kinematics.toSwerveModuleStates(ChassisSpeeds(1.0, 0.0, 0.0), 0.02, output) }
        assertEquals(42.0, first.speedMetersPerSecond)
    }
}
