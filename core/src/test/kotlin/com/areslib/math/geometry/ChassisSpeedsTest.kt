package com.areslib.math.geometry

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ChassisSpeedsTest {

    @Test
    fun `default constructor initializes with zero velocities`() {
        val speeds = ChassisSpeeds()
        assertEquals(0.0, speeds.vxMetersPerSecond)
        assertEquals(0.0, speeds.vyMetersPerSecond)
        assertEquals(0.0, speeds.omegaRadiansPerSecond)
    }

    @Test
    fun `fromFieldRelativeSpeeds maps directly at zero heading`() {
        val heading = Rotation2d(0.0) // cos=1, sin=0
        val speeds = ChassisSpeeds.fromFieldRelativeSpeeds(1.0, 0.5, 0.1, heading)
        assertEquals(1.0, speeds.vxMetersPerSecond, 0.001)
        assertEquals(0.5, speeds.vyMetersPerSecond, 0.001)
        assertEquals(0.1, speeds.omegaRadiansPerSecond, 0.001)
    }

    @Test
    fun `fromFieldRelativeSpeeds rotates by inverse at 90 degrees`() {
        val heading = Rotation2d(Math.PI / 2) // cos=0, sin=1
        val speeds = ChassisSpeeds.fromFieldRelativeSpeeds(1.0, 0.0, 0.1, heading)
        // If we command X on the field, and we are facing 90 deg (left), our robot-centric Y should be -1.0
        assertEquals(0.0, speeds.vxMetersPerSecond, 0.001)
        assertEquals(-1.0, speeds.vyMetersPerSecond, 0.001)
    }

    @Test
    fun `fromFieldRelativeSpeeds rotates by inverse at 180 degrees`() {
        val heading = Rotation2d(Math.PI) // cos=-1, sin=0
        val speeds = ChassisSpeeds.fromFieldRelativeSpeeds(1.0, 0.5, 0.25, heading)
        assertEquals(-1.0, speeds.vxMetersPerSecond, 1e-9)
        assertEquals(-0.5, speeds.vyMetersPerSecond, 1e-9)
        assertEquals(0.25, speeds.omegaRadiansPerSecond, 1e-9)
    }

    @Test
    fun `fromFieldRelativeSpeeds rotates by inverse at 270 degrees`() {
        val heading = Rotation2d(-Math.PI / 2) // cos=0, sin=-1
        val speeds = ChassisSpeeds.fromFieldRelativeSpeeds(1.0, 0.5, 0.25, heading)
        assertEquals(-0.5, speeds.vxMetersPerSecond, 1e-9)
        assertEquals(1.0, speeds.vyMetersPerSecond, 1e-9)
        assertEquals(0.25, speeds.omegaRadiansPerSecond, 1e-9)
    }

    @Test
    fun `discretize uses exact inverse SE2 translation Jacobian`() {
        val speeds = ChassisSpeeds.discretize(
            vxMetersPerSecond = 1.0,
            vyMetersPerSecond = 0.0,
            omegaRadiansPerSecond = Math.PI,
            dtSeconds = 1.0
        )

        assertEquals(0.0, speeds.vxMetersPerSecond, 1e-9)
        assertEquals(-Math.PI / 2.0, speeds.vyMetersPerSecond, 1e-9)
        assertEquals(Math.PI, speeds.omegaRadiansPerSecond, 1e-9)
    }

    @Test
    fun `discretize returns safe zero for invalid timestep`() {
        val speeds = ChassisSpeeds.discretize(1.0, 2.0, 3.0, Double.NaN)
        assertEquals(0.0, speeds.vxMetersPerSecond)
        assertEquals(0.0, speeds.vyMetersPerSecond)
        assertEquals(0.0, speeds.omegaRadiansPerSecond)
    }

    @Test
    fun `discretize returns safe zero for non-finite velocities or non-positive timestep`() {
        val nonFiniteValues = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

        for (v in nonFiniteValues) {
            val badVx = ChassisSpeeds.discretize(v, 1.0, 1.0, 0.02)
            assertEquals(0.0, badVx.vxMetersPerSecond)
            assertEquals(0.0, badVx.vyMetersPerSecond)
            assertEquals(0.0, badVx.omegaRadiansPerSecond)

            val badVy = ChassisSpeeds.discretize(1.0, v, 1.0, 0.02)
            assertEquals(0.0, badVy.vxMetersPerSecond)
            assertEquals(0.0, badVy.vyMetersPerSecond)
            assertEquals(0.0, badVy.omegaRadiansPerSecond)

            val badOmega = ChassisSpeeds.discretize(1.0, 1.0, v, 0.02)
            assertEquals(0.0, badOmega.vxMetersPerSecond)
            assertEquals(0.0, badOmega.vyMetersPerSecond)
            assertEquals(0.0, badOmega.omegaRadiansPerSecond)

            val badDt = ChassisSpeeds.discretize(1.0, 1.0, 1.0, v)
            assertEquals(0.0, badDt.vxMetersPerSecond)
            assertEquals(0.0, badDt.vyMetersPerSecond)
            assertEquals(0.0, badDt.omegaRadiansPerSecond)
        }

        for (dt in listOf(0.0, -0.0, -0.02, -1.0)) {
            val badDt = ChassisSpeeds.discretize(1.0, 2.0, 3.0, dt)
            assertEquals(0.0, badDt.vxMetersPerSecond)
            assertEquals(0.0, badDt.vyMetersPerSecond)
            assertEquals(0.0, badDt.omegaRadiansPerSecond)
        }
    }

    @Test
    fun `discretize with zero omega preserves linear velocities`() {
        val speeds = ChassisSpeeds.discretize(2.5, -1.5, 0.0, 0.02)
        assertEquals(2.5, speeds.vxMetersPerSecond, 1e-9)
        assertEquals(-1.5, speeds.vyMetersPerSecond, 1e-9)
        assertEquals(0.0, speeds.omegaRadiansPerSecond, 1e-9)
    }

    @Test
    fun `discretize with near-zero omega applies Taylor series expansion without singularity`() {
        val vx = 2.0
        val vy = -1.0
        val dt = 0.02

        // Positive near-zero omega: dTheta = 2e-7 < 1e-6
        val omegaPos = 1e-5
        val dThetaPos = omegaPos * dt
        val halfThetaPos = dThetaPos * 0.5
        val taylorFactorPos = 1.0 - (dThetaPos * dThetaPos) / 12.0
        val expectedVxPos = taylorFactorPos * vx + halfThetaPos * vy
        val expectedVyPos = -halfThetaPos * vx + taylorFactorPos * vy

        val speedsPos = ChassisSpeeds.discretize(vx, vy, omegaPos, dt)
        assertEquals(expectedVxPos, speedsPos.vxMetersPerSecond, 1e-12)
        assertEquals(expectedVyPos, speedsPos.vyMetersPerSecond, 1e-12)
        assertEquals(omegaPos, speedsPos.omegaRadiansPerSecond, 1e-12)

        // Negative near-zero omega: dTheta = -2e-7, |dTheta| < 1e-6
        val omegaNeg = -1e-5
        val dThetaNeg = omegaNeg * dt
        val halfThetaNeg = dThetaNeg * 0.5
        val taylorFactorNeg = 1.0 - (dThetaNeg * dThetaNeg) / 12.0
        val expectedVxNeg = taylorFactorNeg * vx + halfThetaNeg * vy
        val expectedVyNeg = -halfThetaNeg * vx + taylorFactorNeg * vy

        val speedsNeg = ChassisSpeeds.discretize(vx, vy, omegaNeg, dt)
        assertEquals(expectedVxNeg, speedsNeg.vxMetersPerSecond, 1e-12)
        assertEquals(expectedVyNeg, speedsNeg.vyMetersPerSecond, 1e-12)
        assertEquals(omegaNeg, speedsNeg.omegaRadiansPerSecond, 1e-12)
    }
}
