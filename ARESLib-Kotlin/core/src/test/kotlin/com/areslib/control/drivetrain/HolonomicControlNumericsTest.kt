package com.areslib.control.drivetrain

import com.areslib.control.feedback.PIDController
import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.telemetry.ITelemetry
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.*
import kotlin.test.*

class HolonomicControlNumericsTest {
    private fun pid(p: Double = 0.0) = PIDController(p, 0.0, 0.0)

    @Test fun `seeded independent SE2 oracle matches translation rotation and final clamp`() {
        val random = Random(5801)
        val drive = HolonomicDriveController(pid(0.7), pid(0.9), pid(1.1))
        repeat(3000) {
            val h = random.nextDouble() * 6.0 - 3.0
            val targetH = random.nextDouble() * 6.0 - 3.0
            val x = random.nextDouble() * 20.0 - 10.0
            val y = random.nextDouble() * 20.0 - 10.0
            val tangent = random.nextDouble() * 6.0 - 3.0
            val speed = random.nextDouble() * 12.0 - 6.0
            val dt = random.nextDouble() * 0.08 + 0.001
            var angleError = targetH - h
            if (angleError >= PI) angleError -= 2 * PI
            if (angleError < -PI) angleError += 2 * PI
            val omega = 1.1 * angleError
            val fieldX = speed * cos(tangent) + 0.7 * x
            val fieldY = speed * sin(tangent) + 0.9 * y
            val robotX = fieldX * cos(h) + fieldY * sin(h)
            val robotY = -fieldX * sin(h) + fieldY * cos(h)
            val angle = omega * dt
            // Invert the exponential's 2x2 matrix independently of the production half-angle form.
            val a = if (abs(angle) < 1e-8) 1.0 else sin(angle) / angle
            val b = if (abs(angle) < 1e-8) angle * 0.5 else (1.0 - cos(angle)) / angle
            var vx = (a * robotX + b * robotY) / (a * a + b * b)
            var vy = (-b * robotX + a * robotY) / (a * a + b * b)
            val norm = hypot(vx, vy)
            if (norm > 4.0) { vx *= 4.0 / norm; vy *= 4.0 / norm }
            val actual = drive.calculateDirect(0.0, 0.0, h, x, y, targetH, speed, dt, tangent)
            assertEquals(vx, actual.vxMetersPerSecond, 2e-10)
            assertEquals(vy, actual.vyMetersPerSecond, 2e-10)
            assertEquals(omega, actual.omegaRadiansPerSecond, 1e-12)
        }
    }

    @Test fun `overflowing vector norm still yields the requested limited diagonal`() {
        val drive = HolonomicDriveController(pid(1.3e308), pid(1.3e308), pid())
        for (h in doubleArrayOf(0.0, PI / 4, -PI / 4)) {
            val out = drive.calculateDirect(0.0, 0.0, h, 1.0, 1.0, h, 0.0, 0.02)
            assertEquals(4.0, hypot(out.vxMetersPerSecond, out.vyMetersPerSecond), 1e-14)
            assertEquals(4.0 * cos(PI / 4 - h), out.vxMetersPerSecond, 1e-14)
            assertEquals(4.0 * sin(PI / 4 - h), out.vyMetersPerSecond, 1e-14)
        }
    }

    @Test fun `shared controller cannot overwrite evidence of a failed earlier axis`() {
        val shared = pid(Double.MAX_VALUE)
        val drive = HolonomicDriveController(shared, shared, pid())
        assertEquals(ChassisSpeeds(), drive.calculateDirect(0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 1.0, 0.02))
    }

    @Test fun `telemetry failure clears reusable output and propagates original failure`() {
        val failure = IllegalStateException("backend failed")
        val backend = object : ITelemetry {
            override fun putNumber(key: String, value: Double) { throw failure }
            override fun putBoolean(key: String, value: Boolean) = Unit
            override fun putString(key: String, value: String) = Unit
            override fun putDoubleArray(key: String, value: DoubleArray) = Unit
            override fun getNumber(key: String, defaultValue: Double) = defaultValue
            override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
            override fun getString(key: String, defaultValue: String) = defaultValue
        }
        val drive = HolonomicDriveController(pid(), pid(), pid(), telemetry = backend)
        val out = ChassisSpeeds(2.0, 3.0, 4.0)
        assertSame(failure, assertFailsWith<IllegalStateException> {
            drive.calculateInto(out, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.02)
        })
        assertEquals(ChassisSpeeds(), out)
    }

    @Test fun `fallback tangent and signed curvature preserve reverse travel`() {
        val drive = HolonomicDriveController(pid(), pid(), pid())
        val out = drive.calculateDirect(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, -4.0,
            0.02, curvature = -2.0, maxCentripetalAccel = 2.0)
        assertEquals(0.0, out.vxMetersPerSecond, 1e-12)
        assertEquals(-1.0, out.vyMetersPerSecond, 1e-12)
        val near = drive.calculateDirect(0.0, 0.0, 0.0, 0.001, 0.0, PI / 2, 1.0, 0.02)
        assertEquals(1.0, near.vyMetersPerSecond, 1e-12)
    }
}
