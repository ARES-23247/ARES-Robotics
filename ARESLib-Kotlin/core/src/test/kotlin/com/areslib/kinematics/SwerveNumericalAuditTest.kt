package com.areslib.kinematics

import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwerveNumericalAuditTest {
    @Test fun `seeded module vectors agree with independent decimal products`() {
        val rng = Random(4702)
        repeat(2000) { iteration ->
            val modules = List(4) { Translation2d(rng.nextDouble(-0.6, 0.6), rng.nextDouble(-0.6, 0.6)) }
            val command = ChassisSpeeds(rng.nextDouble(-8.0, 8.0), rng.nextDouble(-8.0, 8.0), rng.nextDouble(-12.0, 12.0))
            val states = SwerveKinematics(modules).toSwerveModuleStates(command)
            for (i in states.indices) {
                val x = BigDecimal(command.vxMetersPerSecond).subtract(BigDecimal(command.omegaRadiansPerSecond).multiply(BigDecimal(modules[i].y))).toDouble()
                val y = BigDecimal(command.vyMetersPerSecond).add(BigDecimal(command.omegaRadiansPerSecond).multiply(BigDecimal(modules[i].x))).toDouble()
                val expectedSpeed = hypot(x, y)
                val tolerance = 2e-14 * maxOf(1.0, expectedSpeed)
                assertEquals(expectedSpeed, abs(states[i].speedMetersPerSecond), tolerance, "speed case=$iteration module=$i")
                assertEquals(x, states[i].speedMetersPerSecond * states[i].angle.cos, tolerance, "X case=$iteration module=$i")
                assertEquals(y, states[i].speedMetersPerSecond * states[i].angle.sin, tolerance, "Y case=$iteration module=$i")
                assertTrue(abs(states[i].angle.radians) <= Math.PI / 2.0 + 1e-14)
            }
        }
    }

    @Test fun `optimizer preserves velocity across wrap and right-angle ties`() {
        val kinematics = SwerveKinematics(emptyList())
        for (current in listOf(-Math.PI, Math.nextDown(Math.PI), -1.0, 0.0, 1.0)) {
            for (target in listOf(-Math.PI, Math.nextDown(Math.PI), -Math.PI / 2.0, Math.PI / 2.0, 1e-200)) {
                val desired = SwerveModuleState(2.0, Rotation2d(target))
                val out = kinematics.optimizeModuleState(desired, Rotation2d(current))
                assertEquals(2.0 * Math.cos(target), out.speedMetersPerSecond * out.angle.cos, 3e-15)
                assertEquals(2.0 * Math.sin(target), out.speedMetersPerSecond * out.angle.sin, 3e-15)
                val error = Math.atan2(Math.sin(out.angle.radians - current), Math.cos(out.angle.radians - current))
                assertTrue(abs(error) <= Math.PI / 2.0 + 1e-14)
                kinematics.optimizeModuleState(desired, Rotation2d(current), desired)
                assertEquals(out, desired, "In-place alias must match owning optimizer")
            }
        }
    }
}
