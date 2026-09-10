package com.areslib.control.assist

import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class ShotSetupMathAuditTest {
    @Test
    fun `intercept and feedforward sampling has no per-loop allocation`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        org.junit.jupiter.api.Assumptions.assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val allocationBean = bean!!
        allocationBean.isThreadAllocatedMemoryEnabled = true
        val setup = ShotSetup(config(keys = doubleArrayOf(0.0, 2.0, 5.0, 20.0),
            times = doubleArrayOf(0.1, 0.2, 0.5, 2.0)))
        val pose = Pose2d()
        val speeds = ChassisSpeeds(0.0, 1.0, 0.5)
        val target = Translation2d(4.0, 0.0)
        val result = ShotResult()
        fun sample(): Double {
            var sum = 0.0
            for (i in 0 until 10000) {
                speeds.vxMetersPerSecond = (i % 13) * 0.1
                setup.calculate(pose, speeds, target, result)
                sum += result.angularVelocityFeedforwardRadPerSec
            }
            return sum
        }
        sample()
        val threadId = Thread.currentThread().id
        val before = allocationBean.getThreadAllocatedBytes(threadId)
        val start = System.nanoTime()
        val sum = sample()
        val elapsed = System.nanoTime() - start
        val allocated = allocationBean.getThreadAllocatedBytes(threadId) - before
        assertTrue(result.isValid)
        assertTrue(sum.isFinite())
        assertTrue(allocated <= 4096L, "Allocated $allocated bytes over 10000 samples")
        println("ShotSetup host diagnostic: ${elapsed / 10000.0} ns/sample; $allocated bytes total")
    }

    @Test
    fun `unique affine intercepts match an independent oracle across velocity quadrants`() {
        for (vx in listOf(-4.0, -1.0, 0.0, 2.0, 4.0)) {
            for (vy in listOf(-3.0, 0.5, 3.0)) {
                val result = solve(config(times = doubleArrayOf(0.1, 10.1)), x = 0.3, y = -0.2, vx = vx, vy = vy)
                assertTrue(result.isValid)
                assertEquals(oracleAngle(0.3, -0.2, vx, vy), result.aimAngleRad, 1e-10)
            }
        }
    }

    @Test
    fun `multiple intercepts choose the earliest nonnegative flight time`() {
        val result = solve(config(keys = doubleArrayOf(0.0, 5.0, 10.0),
            times = doubleArrayOf(20.0, 0.0, 20.0)), x = -6.0, vx = 1.0)
        assertTrue(result.isValid)
        // Four roots have times 4, 20/3, 12, 20 seconds; the earliest gives distance 6m.
        assertEquals(6.0, result.aimDistanceMeters, 1e-10)
        assertEquals(0.0, result.virtualTargetX, 1e-10)
    }

    @Test
    fun `degenerate quadratic resolves as a linear intercept equation`() {
        val result = solve(config(times = doubleArrayOf(0.1, 100.1)), vx = 1.0)
        assertTrue(result.isValid)
        assertEquals(1.95, result.aimDistanceMeters, 1e-10)
    }

    @Test
    fun `invalid calculation clears every field of a reused result`() {
        val setup = ShotSetup(config())
        val result = solve(config(), vy = 1.0)
        assertTrue(result.isValid)
        setup.calculate(Pose2d(), ChassisSpeeds(Double.NaN, 0.0, 0.0), Translation2d(4.0, 0.0), result)
        assertFalse(result.isValid)
        assertEquals(0.0, result.virtualTargetX)
        assertEquals(0.0, result.virtualTargetY)
        assertEquals(0.0, result.aimAngleRad)
        assertEquals(0.0, result.robotTargetHeadingRad)
        assertEquals(0.0, result.aimDistanceMeters)
        assertEquals(0.0, result.targetFlywheelRpm)
        assertEquals(0.0, result.targetCowlAngleRotations)
        assertEquals(0.0, result.angularVelocityFeedforwardRadPerSec)
    }

    @Test
    fun `public interpolation validates its own table and preserves undefined queries`() {
        assertThrows(IllegalArgumentException::class.java) { ShotSetup.interpolate(doubleArrayOf(), doubleArrayOf(), 0.0) }
        assertThrows(IllegalArgumentException::class.java) { ShotSetup.interpolate(doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 2.0), 0.5) }
        assertTrue(ShotSetup.interpolate(doubleArrayOf(0.0), doubleArrayOf(1000.0), Double.NaN).isNaN())
        assertEquals(0.0, ShotSetup.interpolate(doubleArrayOf(-Double.MAX_VALUE, Double.MAX_VALUE), doubleArrayOf(-1.0, 1.0), 0.0))
    }

    private fun config(keys: DoubleArray = doubleArrayOf(0.0, 100.0),
                       times: DoubleArray = doubleArrayOf(0.5, 0.5),
                       offsetX: Double = 0.0, offsetY: Double = 0.0) = ShotConfig(
        offsetX, offsetY, keys, times, doubleArrayOf(0.0, 100.0),
        doubleArrayOf(1000.0, 2000.0), doubleArrayOf(0.1, 0.2), 0.0, false)

    private fun solve(config: ShotConfig, x: Double = 0.0, y: Double = 0.0,
                      heading: Double = 0.0, vx: Double = 0.0, vy: Double = 0.0,
                      omega: Double = 0.0): ShotResult = ShotResult().also {
        ShotSetup(config).calculate(Pose2d(x, y, Rotation2d(heading)),
            ChassisSpeeds(vx, vy, omega), Translation2d(4.0, 0.0), it)
    }

    @Test
    fun `constant flight time gives the exact translating intercept`() {
        val result = solve(config(), vy = 2.0)
        assertEquals(4.0, result.virtualTargetX, 1e-12)
        assertEquals(-1.0, result.virtualTargetY, 1e-12)
        assertEquals(hypot(4.0, 1.0), result.aimDistanceMeters, 1e-12)
    }

    // Independently bracket flight time; production currently iterates target coordinates.
    private fun oracleAngle(x: Double, y: Double, vx: Double, vy: Double): Double {
        var lo = 0.1
        var hi = 10.1
        repeat(90) {
            val t = (lo + hi) * 0.5
            val d = hypot(4.0 - x - vx * t, -y - vy * t)
            if (t < 0.1 + 0.1 * d.coerceIn(0.0, 100.0)) lo = t else hi = t
        }
        val t = (lo + hi) * 0.5
        return atan2(-y - vy * t, 4.0 - x - vx * t)
    }

    @Test
    fun `distance dependent intercept agrees with independently bracketed flight time`() {
        val result = solve(config(times = doubleArrayOf(0.1, 10.1)), vx = 1.0, vy = 2.0)
        assertEquals(oracleAngle(0.0, 0.0, 1.0, 2.0), result.aimAngleRad, 1e-10)
        val t = 0.1 + 0.1 * result.aimDistanceMeters
        assertEquals(4.0, result.virtualTargetX + t, 1e-10)
        assertEquals(0.0, result.virtualTargetY + 2.0 * t, 1e-10)
    }

    @Test
    fun `heading feedforward includes distance dependent flight time derivative`() {
        val result = solve(config(times = doubleArrayOf(0.1, 10.1)), vx = 1.0, vy = 2.0)
        val h = 1e-5
        val derivative = (oracleAngle(h, 2.0*h, 1.0, 2.0) -
            oracleAngle(-h, -2.0*h, 1.0, 2.0)) / (2.0*h)
        assertEquals(derivative, result.angularVelocityFeedforwardRadPerSec, 1e-8)
    }

    @Test
    fun `rotating offset feedforward includes the change in tangential velocity`() {
        val result = solve(config(offsetX = 1.0), omega = 2.0)
        fun angle(t: Double): Double {
            val ox = cos(2.0*t)
            val oy = sin(2.0*t)
            return atan2(-oy - 2.0*ox*0.5, 4.0-ox + 2.0*oy*0.5)
        }
        val h = 1e-5
        assertEquals((angle(h)-angle(-h))/(2.0*h), result.angularVelocityFeedforwardRadPerSec, 1e-8)
    }

    @Test
    fun `steep flight table resolves the endpoint solution instead of unconverged iterations`() {
        val result = solve(config(times = doubleArrayOf(0.0, 20.0)), vy = 6.0)
        assertEquals(-120.0, result.virtualTargetY, 1e-9)
        assertEquals(hypot(4.0,120.0), result.aimDistanceMeters, 1e-9)
    }

    @Test
    fun `invalid geometry cannot return a plausible flywheel target`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            val result = solve(config(), x = bad)
            assertEquals(0.0, result.targetFlywheelRpm)
            assertEquals(0.0, result.angularVelocityFeedforwardRadPerSec)
            assertTrue(result.virtualTargetX.isFinite())
        }
    }

    @Test
    fun `negative flight time is rejected at configuration`() {
        assertThrows(IllegalArgumentException::class.java) { config(times = doubleArrayOf(-0.1, 0.5)) }
    }

    @Test
    fun `interpolation preserves narrow valid segments`() {
        assertEquals(5.0, ShotSetup.interpolate(doubleArrayOf(0.0, 1e-10), doubleArrayOf(0.0, 10.0), 5e-11), 1e-12)
    }

    @Test
    fun `interpolation avoids overflowing a representable midpoint`() {
        assertEquals(0.0, ShotSetup.interpolate(doubleArrayOf(0.0, 1.0),
            doubleArrayOf(-Double.MAX_VALUE, Double.MAX_VALUE), 0.5))
    }
}
