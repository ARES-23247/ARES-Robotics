package com.areslib.ftc.drivetrain

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class MecanumFallbackOdometryTest {
    @Test
    fun `fallback integrates a quarter circle with unit arc length`() {
        val odometry = MecanumFallbackOdometry()
        odometry.getFallbackPoseUpdate(0L, 0.0, 0.0, 0.0, 0.0, 100.0, 100.0, 0.0)
        val moved = odometry.getFallbackPoseUpdate(
            1000L, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, Math.PI / 2.0)
        assertEquals(2.0 / Math.PI, moved.xMeters, 1e-12)
        assertEquals(2.0 / Math.PI, moved.yMeters, 1e-12)
    }

    @Test
    fun `fallback starts at fused pose and preserves independent imu alignment`() {
        val odometry = MecanumFallbackOdometry()
        val start = Pose2d(3.0, 4.0, Rotation2d(1.0))
        odometry.reset(start, rawHeadingRadians = 0.2)

        val seeded = odometry.getFallbackPoseUpdate(
            timestampMs = 1_000L,
            flPosTicks = 0.0,
            frPosTicks = 0.0,
            rlPosTicks = 0.0,
            rrPosTicks = 0.0,
            ticksPerMeterSetting = 100.0,
            defaultTicksPerMeter = 100.0,
            headingRadians = 0.2
        )
        assertEquals(3.0, seeded.xMeters, 1e-9)
        assertEquals(4.0, seeded.yMeters, 1e-9)
        assertEquals(1.0, seeded.headingRadians, 1e-9)

        val moved = odometry.getFallbackPoseUpdate(
            timestampMs = 2_000L,
            flPosTicks = 100.0,
            frPosTicks = 100.0,
            rlPosTicks = 100.0,
            rrPosTicks = 100.0,
            ticksPerMeterSetting = 100.0,
            defaultTicksPerMeter = 100.0,
            headingRadians = 0.2
        )
        assertEquals(3.0 + cos(1.0), moved.xMeters, 1e-9)
        assertEquals(4.0 + sin(1.0), moved.yMeters, 1e-9)
    }
}
