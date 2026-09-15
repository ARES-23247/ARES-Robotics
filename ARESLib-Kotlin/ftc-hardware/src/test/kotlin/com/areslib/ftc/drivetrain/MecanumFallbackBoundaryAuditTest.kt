package com.areslib.ftc.drivetrain

import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.math.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MecanumFallbackBoundaryAuditTest {
    @Test
    fun `stationary encoders do not move when scale changes`() {
        val odometry = MecanumFallbackOdometry()
        sample(odometry, 0, 100.0)
        val pose = sample(odometry, 1000, 100.0, scale = 200.0)
        assertEquals(0.0, pose.xMeters, 0.0)
        assertEquals(0.0, pose.xVelocityMetersPerSecond, 0.0)
    }

    @Test
    fun `new scale applies to current tick displacement only`() {
        val odometry = MecanumFallbackOdometry()
        sample(odometry, 0, 100.0)
        val pose = sample(odometry, 1000, 200.0, scale = 200.0)
        assertEquals(0.5, pose.xMeters, 1e-12)
        assertEquals(0.5, pose.xVelocityMetersPerSecond, 1e-12)
    }

    @Test
    fun `large cumulative positions retain a single tick of displacement`() {
        val odometry = MecanumFallbackOdometry()
        val position = 2_147_483_648.0
        sample(odometry, 0, position, scale = 2000.0)
        val pose = sample(odometry, 1000, position + 1.0, scale = 2000.0)
        assertEquals(0.0005, pose.xMeters, 1e-15)
    }

    @Test
    fun `nonfinite encoders reject without changing any baseline`() {
        for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (wheel in 0..3) {
                val odometry = seeded()
                val wheels = DoubleArray(4) { 50.0 }.also { it[wheel] = bad }
                assertThrows(IllegalArgumentException::class.java) {
                    odometry.getFallbackPoseUpdate(500, wheels[0], wheels[1], wheels[2], wheels[3], 100.0, 100.0, 0.3)
                }
                assertOneMeter(sample(odometry, 1000, 100.0))
            }
        }
    }

    @Test
    fun `raw nonfinite headings are rejected before wrapping masks them`() {
        for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val odometry = seeded()
            assertThrows(IllegalArgumentException::class.java) { sample(odometry, 500, 50.0, heading = bad) }
            assertOneMeter(sample(odometry, 1000, 100.0))
        }
    }

    @Test
    fun `invalid primary scale uses a valid configured fallback`() {
        for (bad in doubleArrayOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val odometry = MecanumFallbackOdometry()
            sample(odometry, 0, 0.0, scale = bad)
            assertOneMeter(sample(odometry, 1000, 100.0, scale = bad))
        }
    }

    @Test
    fun `invalid selected scale rejects without poisoning initial or existing state`() {
        for (bad in doubleArrayOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val odometry = MecanumFallbackOdometry()
            assertThrows(IllegalArgumentException::class.java) { sample(odometry, 0, 500.0, scale = 0.0, fallback = bad) }
            sample(odometry, 0, 0.0)
            assertThrows(IllegalArgumentException::class.java) { sample(odometry, 500, 50.0, scale = 0.0, fallback = bad) }
            assertOneMeter(sample(odometry, 1000, 100.0))
        }
        val odometry = MecanumFallbackOdometry()
        sample(odometry, 0, 0.0, fallback = Double.NaN)
        assertOneMeter(sample(odometry, 1000, 100.0, fallback = Double.NaN))
    }

    @Test
    fun `invalid reset pose or raw heading preserves prior alignment and encoders`() {
        for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (field in 0..3) {
                val odometry = seeded()
                val pose = Pose2d(if (field == 0) bad else 3.0, if (field == 1) bad else 4.0,
                    Rotation2d(if (field == 2) bad else 0.8))
                assertThrows(IllegalArgumentException::class.java) {
                    odometry.reset(pose, if (field == 3) bad else 0.2)
                }
                assertOneMeter(sample(odometry, 1000, 100.0))
            }
        }
    }

    @Test
    fun `duplicate timestamps preserve wheel and heading baselines for next frame`() {
        val odometry = seeded()
        val duplicate = sample(odometry, 0, 50.0, heading = 0.5)
        assertEquals(0.0, duplicate.xMeters, 0.0)
        assertEquals(0.0, duplicate.headingRadians, 0.0)
        assertFalse(duplicate.motionMeasurementsValid)
        assertOneMeter(sample(odometry, 1000, 100.0))
    }

    @Test
    fun `rewound time is rejected without consuming motion`() {
        val odometry = seeded(1000)
        assertThrows(IllegalArgumentException::class.java) { sample(odometry, 500, 50.0, heading = 0.5) }
        assertOneMeter(sample(odometry, 2000, 100.0))
    }

    @Test
    fun `elapsed time overflow is rejected without consuming motion`() {
        val odometry = seeded(Long.MIN_VALUE)
        assertThrows(IllegalArgumentException::class.java) { sample(odometry, Long.MAX_VALUE, 50.0) }
        assertOneMeter(sample(odometry, Long.MIN_VALUE + 1000, 100.0))
    }

    @Test
    fun `unrepresentable displacement does not corrupt a subsequent valid update`() {
        val odometry = seeded()
        assertThrows(IllegalArgumentException::class.java) { sample(odometry, 500, Double.MAX_VALUE, scale = Double.MIN_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { sample(odometry, 1, 1e308, scale = 1.0) }
        assertOneMeter(sample(odometry, 1000, 100.0))
    }

    @Test
    fun `forward kinematics and heading wrap agree with independent SE2 exponential`() {
        val random = java.util.Random(215)
        val angles = doubleArrayOf(0.0, 1e-10, -1e-10, 1e-5, -1e-5, 0.4, -0.4, 2.5, -2.5, -Math.PI)
        repeat(240) { index ->
            val turn = angles[index % angles.size]
            val h = if (turn == -Math.PI) 0.0 else random.nextDouble() * 6.0 - 3.0
            val dx = random.nextDouble() * 6.0 - 3.0
            val dy = random.nextDouble() * 6.0 - 3.0
            val odometry = MecanumFallbackOdometry()
            odometry.reset(Pose2d(2.0, -4.0, Rotation2d(h)), h)
            sample(odometry, 100, 0.0, heading = h)
            val pose = odometry.getFallbackPoseUpdate(350,
                (dx-dy)*100.0, (dx+dy)*100.0, (dx+dy)*100.0, (dx-dy)*100.0,
                100.0, 100.0, h+turn)
            // Resolve the represented wrapped turn independently, including the pi tie.
            val delta = ((h+turn-h+Math.PI) % (2*Math.PI) + 2*Math.PI) % (2*Math.PI) - Math.PI
            val q = delta*delta
            val s = if (abs(delta) < 1e-4) 1-q/6+q*q/120-q*q*q/5040 else sin(delta)/delta
            val c = if (abs(delta) < 1e-4) delta*(0.5-q/24+q*q/720) else (1-cos(delta))/delta
            val localX = s*dx-c*dy
            val localY = c*dx+s*dy
            val fieldX = cos(h)*localX-sin(h)*localY
            val fieldY = sin(h)*localX+cos(h)*localY
            assertEquals(2.0+fieldX, pose.xMeters, 1e-10, "case $index")
            assertEquals(-4.0+fieldY, pose.yMeters, 1e-10, "case $index")
            assertEquals(fieldX/0.25, pose.xVelocityMetersPerSecond, 1e-9)
            assertEquals(fieldY/0.25, pose.yVelocityMetersPerSecond, 1e-9)
            assertEquals(0.0, atan2(sin(pose.headingRadians-h-turn), cos(pose.headingRadians-h-turn)), 1e-12)
        }
    }

    @Test
    fun `returned actions have independent ownership and cannot mutate internal pose`() {
        val odometry = seeded()
        val first = sample(odometry, 1000, 100.0)
        val second = sample(odometry, 2000, 200.0)
        assertNotSame(first, second)
        assertEquals(1.0, first.xMeters, 1e-12)
        first.xMeters = -1e9
        second.headingRadians = 2.0
        val third = sample(odometry, 3000, 300.0)
        assertEquals(3.0, third.xMeters, 1e-12)
        assertEquals(0.0, third.headingRadians, 0.0)
    }

    @Test
    fun `reset permits a new epoch and first reading seeds encoders at fused pose`() {
        val odometry = seeded(5000)
        odometry.reset(Pose2d(3.0, 4.0, Rotation2d(1.0)), 0.2)
        val first = sample(odometry, -1000, 900.0, heading = 0.2)
        assertEquals(3.0, first.xMeters, 0.0)
        assertEquals(4.0, first.yMeters, 0.0)
        assertEquals(1.0, first.headingRadians, 1e-12)
        val next = sample(odometry, 0, 1000.0, heading = 0.2)
        assertEquals(3.0+cos(1.0), next.xMeters, 1e-12)
        assertEquals(4.0+sin(1.0), next.yMeters, 1e-12)
    }

    @Test
    fun `invalid optional gyro rate marks motion unavailable while retaining finite pose`() {
        for (rate in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val odometry = seeded()
            val pose = sample(odometry, 1000, 100.0, rate = rate)
            assertEquals(1.0, pose.xMeters, 1e-12)
            assertFalse(pose.motionMeasurementsValid)
        }
    }

    @Test
    fun `warm integration allocates only its independently owned action`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        val calibration = arrayOfNulls<RobotAction.PoseUpdate>(10_000)
        val results = arrayOfNulls<RobotAction.PoseUpdate>(10_000)
        retained = calibration
        val odometry = MecanumFallbackOdometry()
        repeat(100_000) { sample(odometry, it.toLong(), it.toDouble()) }
        var before = bean.getThreadAllocatedBytes(threadId)
        repeat(calibration.size) { calibration[it] = RobotAction.PoseUpdate(0.0, 0.0, 0.0, it.toLong()) }
        val actionBytes = bean.getThreadAllocatedBytes(threadId)-before
        retained = results
        before = bean.getThreadAllocatedBytes(threadId)
        repeat(results.size) { results[it] = sample(odometry, 100_000L+it, 100_000.0+it) }
        val bytes = bean.getThreadAllocatedBytes(threadId)-before
        assertTrue(actionBytes > 1_000_000)
        assertTrue(bytes <= actionBytes+4096, "Integration: $bytes bytes; actions alone: $actionBytes")
        assertEquals(1000.0, results.first()!!.xMeters, 1e-7)
        println("Fallback odometry: $bytes bytes / 10,000 updates; independent action calibration $actionBytes bytes (desktop JVM)")
        retained = null
    }

    @Test
    fun `representable mean does not overflow when summing large wheel displacements`() {
        val odometry = MecanumFallbackOdometry()
        sample(odometry, 0, 0.0, scale = 1.0)
        val pose = sample(odometry, 1000, Double.MAX_VALUE, scale = 1.0)
        assertEquals(Double.MAX_VALUE, pose.xMeters, 0.0)
        assertEquals(0.0, pose.yMeters, 0.0)
    }

    @Test
    fun `scaled tick difference remains usable when raw subtraction overflows`() {
        val odometry = MecanumFallbackOdometry()
        sample(odometry, 0, -Double.MAX_VALUE, scale = Double.MAX_VALUE)
        val pose = sample(odometry, 1000, Double.MAX_VALUE, scale = Double.MAX_VALUE)
        assertEquals(2.0, pose.xMeters, 0.0)
    }

    @Test
    fun `unbounded raw heading retains reset alignment`() {
        val odometry = MecanumFallbackOdometry()
        val raw = 1e20
        odometry.reset(Pose2d(0.0, 0.0, Rotation2d(0.7)), raw)
        val pose = sample(odometry, 0, 0.0, heading = raw)
        assertEquals(0.7, pose.headingRadians, 1e-12)
    }

    @Test
    fun `pose accumulation overflow is rejected before committing the sample`() {
        val odometry = MecanumFallbackOdometry()
        odometry.reset(Pose2d(Double.MAX_VALUE, 0.0, Rotation2d()))
        sample(odometry, 0, 0.0, scale = 1.0)
        assertThrows(IllegalArgumentException::class.java) {
            sample(odometry, 1000, Double.MAX_VALUE, scale = 1.0)
        }
        val still = sample(odometry, 2000, 0.0, scale = 1.0)
        assertEquals(Double.MAX_VALUE, still.xMeters, 0.0)
        assertEquals(0.0, still.xVelocityMetersPerSecond, 0.0)
    }

    private fun seeded(timestamp: Long = 0) = MecanumFallbackOdometry().also { sample(it, timestamp, 0.0) }
    private fun sample(odometry: MecanumFallbackOdometry, time: Long, ticks: Double,
        scale: Double = 100.0, fallback: Double = 100.0, heading: Double = 0.0, rate: Double = 0.0) =
        odometry.getFallbackPoseUpdate(time, ticks, ticks, ticks, ticks, scale, fallback, heading, rate)
    private fun assertOneMeter(pose: RobotAction.PoseUpdate) {
        assertEquals(1.0, pose.xMeters, 1e-12)
        assertEquals(0.0, pose.yMeters, 1e-12)
        assertEquals(0.0, pose.headingRadians, 1e-12)
        assertEquals(1.0, pose.xVelocityMetersPerSecond, 1e-12)
    }
    companion object { @Volatile private var retained: Array<RobotAction.PoseUpdate?>? = null }
}
