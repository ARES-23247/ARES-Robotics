package com.ares.analytics.ui

import com.ares.analytics.ui.screens.AcademyControlMission
import com.ares.analytics.ui.screens.AcademyDriveKinematics
import com.ares.analytics.ui.screens.computeAcademyKinematicsPreview
import com.ares.analytics.ui.screens.evaluateAcademyControlMission
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AcademyEngineeringLabsTest {
    @Test
    fun `control challenge labels passing simplified evidence without recommending robot gains`() {
        val result = evaluateAcademyControlMission(
            mission = AcademyControlMission.FLYWHEEL,
            kp = 6.0,
            kd = 0.1,
            kv = 2.5,
            kg = 0.0,
        )

        assertTrue(result.bounded)
        assertTrue(result.metTeachingGoal)
        assertTrue(result.explanation.contains("simplified model"))
        assertTrue(result.explanation.contains("not a robot tuning recommendation"))
    }

    @Test
    fun `control challenge rejects nonfinite inputs before producing evidence`() {
        assertFailsWith<IllegalArgumentException> {
            evaluateAcademyControlMission(
                mission = AcademyControlMission.ARM,
                kp = Double.NaN,
                kd = 0.0,
                kv = 0.0,
                kg = 0.0,
            )
        }
    }

    @Test
    fun `mecanum preview uses production coordinate convention and uniform scaling`() {
        val forward = computeAcademyKinematicsPreview(
            AcademyDriveKinematics.MECANUM,
            vxMetersPerSecond = 1.0,
            vyMetersPerSecond = 0.0,
            omegaRadiansPerSecond = 0.0,
            trackWidthMeters = 0.4,
            wheelBaseMeters = 0.4,
            maximumWheelSpeedMetersPerSecond = 4.0,
        )
        forward.targets.forEach { assertEquals(1.0, it.speedMetersPerSecond, 1e-9) }

        val left = computeAcademyKinematicsPreview(
            AcademyDriveKinematics.MECANUM,
            vxMetersPerSecond = 0.0,
            vyMetersPerSecond = 1.0,
            omegaRadiansPerSecond = 0.0,
            trackWidthMeters = 0.4,
            wheelBaseMeters = 0.4,
            maximumWheelSpeedMetersPerSecond = 0.5,
        )
        assertEquals(listOf(-0.5, 0.5, 0.5, -0.5), left.targets.map { it.speedMetersPerSecond })
        assertEquals(0.5, left.scaleApplied, 1e-9)
    }

    @Test
    fun `swerve preview returns forward module targets in CCW radians`() {
        val preview = computeAcademyKinematicsPreview(
            AcademyDriveKinematics.SWERVE,
            vxMetersPerSecond = 1.0,
            vyMetersPerSecond = 0.0,
            omegaRadiansPerSecond = 0.0,
            trackWidthMeters = 0.6,
            wheelBaseMeters = 0.7,
            maximumWheelSpeedMetersPerSecond = 4.0,
        )

        preview.targets.forEach {
            assertEquals(1.0, it.speedMetersPerSecond, 1e-9)
            assertEquals(0.0, it.angleRadians, 1e-9)
            assertTrue(it.angleRadians in -PI..PI)
        }
    }

    @Test
    fun `kinematics preview rejects invalid geometry`() {
        assertFailsWith<IllegalArgumentException> {
            computeAcademyKinematicsPreview(
                AcademyDriveKinematics.MECANUM,
                vxMetersPerSecond = 0.0,
                vyMetersPerSecond = 0.0,
                omegaRadiansPerSecond = 0.0,
                trackWidthMeters = 0.0,
                wheelBaseMeters = 0.4,
                maximumWheelSpeedMetersPerSecond = 4.0,
            )
        }
    }
}
