package com.areslib.math

import com.areslib.control.feedback.PIDController
import com.areslib.kinematics.*
import com.areslib.math.estimation.*
import com.areslib.math.geometry.*
import com.areslib.state.VisionMeasurement
import kotlin.math.PI
import kotlin.test.*

class MathematicalAuditTest {
    @Test
    fun `centered joystick remains neutral when the deadband is disabled`() {
        assertEquals(0.0 to 0.0, InputMath.processJoystickVector(0.0, 0.0, deadband = 0.0))
    }

    @Test
    fun `invalid linkage targets and gravity offsets cannot produce NaN commands`() {
        val linkage = com.areslib.math.kinematics.TwoDofLinkageKinematics(
            com.areslib.math.kinematics.TwoDofLinkageParameters(1.0, 1.0, 1.0, 1.0))
        assertNull(linkage.inverseKinematics(Double.NaN, 0.0))
        assertEquals(0.0, com.areslib.control.feedback.GravityFeedforward.calculateArm(0.0, 1.0, Double.NaN))
    }

    @Test
    fun `older camera frames cannot erase an already accepted correction`() {
        val state = PoseEstimatorState()
        for (timestamp in listOf(0L, 50L, 100L)) {
            PoseEstimator.addOdometryObservationDirect(state, timestamp, 0.0, 0.0, 0.0)
        }
        val measurement = VisionMeasurement(timestampMs = 100L, targetPose = Pose3d(Translation3d(1.0, 0.0, 0.0)))
        PoseEstimator.addVisionMeasurementDirect(state, measurement, 0.1, 0.1, 0.1, useMahalanobisRejection = false)
        assertTrue(state.lastMeasurementAccepted)
        val before = state.deepCopy()
        measurement.timestampMs = 50L
        measurement.targetPose.translation.x = 0.0
        PoseEstimator.addVisionMeasurementDirect(state, measurement, 0.1, 0.1, 0.1, useMahalanobisRejection = false)
        assertFalse(state.lastMeasurementAccepted)
        assertEquals("vision_out_of_order", state.lastRejectionReason)
        assertEquals(before.estimatedPoseX, state.estimatedPoseX)
        assertContentEquals(before.covarianceArray, state.covarianceArray)
    }

    @Test
    fun `continuous derivative follows the short angular displacement in either direction`() {
        for (direction in listOf(-1.0, 1.0)) {
            val pid = PIDController(0.0, 0.0, 1.0)
            pid.enableContinuousInput(-PI, PI)
            pid.calculate(direction * Math.toRadians(179.0), 0.0, 0.02)
            val output = pid.calculate(-direction * Math.toRadians(179.0), 0.0, 0.02)
            assertEquals(-direction * 0.2 * Math.toRadians(2.0) / 0.02, output, 1e-12)
        }
    }

    @Test
    fun `scalar Kalman gain is invariant to covariance units`() {
        for (variance in listOf(1.0, 1e-16)) {
            val filter = KalmanFilter(0.0, variance)
            filter.reset(0.0, variance)
            assertEquals(1.0, filter.calculate(2.0), 1e-12)
            assertEquals(4.0 / 3.0, filter.calculate(2.0), 1e-12)
        }
    }

    @Test
    fun `matrix inverse is invariant to uniform scale`() {
        val original = Matrix3x3(4.0, 1.0, 0.5, 1.0, 3.0, -0.2, 0.5, -0.2, 2.0)
        for (scale in listOf(1.0, 1e-12, 1e120, 1e-120)) {
            val matrix = original * scale
            val identity = matrix * matrix.inverse()
            assertEquals(1.0, identity.m00, 1e-12)
            assertEquals(1.0, identity.m11, 1e-12)
            assertEquals(1.0, identity.m22, 1e-12)
            assertEquals(0.0, identity.m01, 1e-12)
            assertEquals(0.0, identity.m12, 1e-12)
            assertEquals(0.0, identity.m20, 1e-12)
        }
    }

    @Test
    fun `invalid speed limits never reverse wheel commands`() {
        for (limit in listOf(-1.0, Double.NaN)) {
            val differential = DifferentialWheelSpeeds(2.0, -1.0).normalize(limit)
            assertEquals(DifferentialWheelSpeeds(), differential)
            val mecanum = MecanumWheelSpeeds(2.0, -1.0, 1.0, -2.0).normalize(limit)
            assertEquals(MecanumWheelSpeeds(), mecanum)
            val states = arrayOf(SwerveModuleState(2.0), SwerveModuleState(-1.0))
            SwerveKinematics(Translation2d(0.2, 0.2), Translation2d(-0.2, -0.2))
                .desaturateWheelSpeeds(states, limit)
            assertTrue(states.all { it.speedMetersPerSecond == 0.0 })
        }
    }

    @Test
    fun `nonfinite wheel commands neutralize the full coupled drive vector`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val differential = doubleArrayOf(1.0, invalid)
            DifferentialDriveKinematics.normalize(differential, 2.0)
            assertTrue(differential.all { it == 0.0 })
            val mecanum = doubleArrayOf(1.0, invalid, -1.0, 0.5)
            MecanumKinematics.normalize(mecanum, 2.0)
            assertTrue(mecanum.all { it == 0.0 })
        }
    }

    @Test
    fun `disabling outlier gating cannot admit nonfinite vision`() {
        val state = PoseEstimatorState()
        PoseEstimator.addOdometryObservationDirect(state, 100L, 0.0, 0.0, 0.0)
        val before = state.deepCopy()
        val measurement = VisionMeasurement(
            tagId = -1,
            targetPose = Pose3d(Translation3d(Double.POSITIVE_INFINITY, 0.0, 0.0)),
            timestampMs = 100L
        )
        PoseEstimator.addVisionMeasurementDirect(state, measurement, 0.1, 0.1, 0.1,
            useMahalanobisRejection = false)
        assertFalse(state.lastMeasurementAccepted)
        assertEquals(before.estimatedPoseX, state.estimatedPoseX)
        assertContentEquals(before.covarianceArray, state.covarianceArray)
    }

    @Test
    fun `odometry rotation grows heading uncertainty without an independent gyro sample`() {
        val still = PoseEstimatorState()
        val turning = PoseEstimatorState()
        PoseEstimator.addOdometryObservationDirect(still, 100L, 0.0, 0.0, 0.0,
            applyGyroBiasCorrection = false)
        PoseEstimator.addOdometryObservationDirect(turning, 100L, 0.0, 0.0, 0.2,
            applyGyroBiasCorrection = false)
        assertTrue(turning.covariance.m22 > still.covariance.m22 + 1e-4)
    }
}
