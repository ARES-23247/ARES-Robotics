package com.areslib.test

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.kinematics.MecanumKinematics
import com.areslib.math.estimation.PoseEstimator
import com.areslib.math.estimation.PoseEstimatorState
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.state.RobotState
import com.areslib.state.Pose3dSnapshot
import com.areslib.state.SuperstructureState
import com.areslib.state.VisionMeasurementSnapshot
import com.areslib.state.VisionState
import com.areslib.telemetry.ARESNetworkStatePublisher
import com.areslib.telemetry.ITelemetry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import com.sun.management.ThreadMXBean

class ZeroGcRegressionTest {

    private object NoOpTelemetry : ITelemetry {
        override fun putNumber(key: String, value: Double) = Unit
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putString(key: String, value: String) = Unit
        override fun putDoubleArray(key: String, value: DoubleArray) = Unit
        override fun getNumber(key: String, defaultValue: Double): Double = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
        override fun getString(key: String, defaultValue: String): String = defaultValue
    }

    private fun getAllocatedBytes(): Long {
        val mxBean = ManagementFactory.getThreadMXBean()
        return if (mxBean is ThreadMXBean) {
            mxBean.getThreadAllocatedBytes(Thread.currentThread().id)
        } else {
            0L
        }
    }

    @Test
    fun testHotPathZeroGcExecution() {
        var poseState = PoseEstimatorState(
            estimatedPoseX = 0.0,
            estimatedPoseY = 0.0,
            estimatedPoseHeading = 0.0
        )
        val deltaTrans = Translation2d(0.01, 0.02)
        val deltaRot = Rotation2d(0.005)
        val kinematics = MecanumKinematics(0.4, 0.4)
        val outSpeeds = DoubleArray(4)

        // Warmup JIT
        for (i in 0 until 1000) {
            poseState = PoseEstimator.addOdometryObservation(
                state = poseState,
                timestampMs = 1000L + i * 20L,
                deltaTranslation = deltaTrans,
                deltaHeading = deltaRot,
                dtSeconds = 0.02
            )
            kinematics.toWheelSpeeds(1.0, 0.5, 0.2, outSpeeds)
        }

        val startBytes = getAllocatedBytes()

        for (i in 0 until 1000) {
            poseState = PoseEstimator.addOdometryObservation(
                state = poseState,
                timestampMs = 20000L + i * 20L,
                deltaTranslation = deltaTrans,
                deltaHeading = deltaRot,
                dtSeconds = 0.02
            )
            kinematics.toWheelSpeeds(1.0, 0.5, 0.2, outSpeeds)
        }

        val allocatedBytes = getAllocatedBytes() - startBytes
        println("[ZeroGC Test] Allocated bytes over 1,000 hot-path iterations: $allocatedBytes bytes")

        assertTrue(
            allocatedBytes <= 4096L,
            "Hot-path execution should allocate minimal bytes (was $allocatedBytes bytes)"
        )
    }

    @Test
    fun `store drive reduction does not clone the full EKF history`() {
        val store = Store()
        val update = RobotAction.PoseUpdate(
            xMeters = 0.0,
            yMeters = 0.0,
            headingRadians = 0.0,
            timestampMs = 1L,
            isReset = true
        )
        store.dispatch(update)

        // Warm the Store, middleware, reducers, and EKF until the history ring is full.
        for (i in 1..2_000) {
            update.xMeters += 0.001
            update.timestampMs += 20L
            update.isReset = false
            store.dispatch(update)
        }

        val startBytes = getAllocatedBytes()
        for (i in 1..1_000) {
            update.xMeters += 0.001
            update.timestampMs += 20L
            store.dispatch(update)
        }
        val allocatedBytes = getAllocatedBytes() - startBytes
        println("[Store EKF Test] Allocated bytes over 1,000 reductions: $allocatedBytes bytes")

        // A 150-entry deep copy allocated tens of megabytes here. Redux snapshots still allocate
        // small immutable state objects, but the bounded runtime history must never be cloned.
        assertTrue(
            allocatedBytes <= 1_500_000L,
            "Store drive reduction must not clone EKF history (allocated $allocatedBytes bytes)"
        )
    }

    @Test
    fun `state telemetry publishing stays within its bounded allocation budget`() {
        val publisher = ARESNetworkStatePublisher(NoOpTelemetry)
        val state = RobotState(
            vision = VisionState(
                measurements = listOf(
                    VisionMeasurementSnapshot(
                        timestampMs = 1L,
                        targetPose = Pose3dSnapshot(x = 1.0, y = 2.0)
                    )
                )
            ),
            superstructure = SuperstructureState(
                indicatorLights = mapOf("status" to 0.5)
            )
        )
        repeat(1_000) { publisher.publish(state, flush = false) }

        val startBytes = getAllocatedBytes()
        repeat(1_000) { publisher.publish(state, flush = false) }
        val allocatedBytes = getAllocatedBytes() - startBytes
        println("[Telemetry Test] Allocated bytes over 1,000 publishes: $allocatedBytes bytes")

        // The first measured window may absorb final JVM bookkeeping after warmup. State pose,
        // covariance, vision, gamepad, and stable indicator publication must not allocate per frame.
        assertTrue(
            allocatedBytes <= 4_096L,
            "State telemetry publishing exceeded its bounded allocation budget ($allocatedBytes bytes)"
        )
    }

    @Test
    fun testMatrix3x3InPlaceZeroGcExecution() {
        val target = com.areslib.math.geometry.Matrix3x3()
        val source = com.areslib.math.geometry.Matrix3x3(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)
        val allocationBean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        val threadId = Thread.currentThread().id

        fun runMeasuredWindow(): Long {
            val startBytes = allocationBean.getThreadAllocatedBytes(threadId)
            repeat(10_000) {
                target.setTo(source)
                target.addInPlace(source)
                target.multiplyInPlace(0.5)
            }
            return allocationBean.getThreadAllocatedBytes(threadId) - startBytes
        }

        // Warmup JIT
        repeat(10_000) {
            target.setTo(source)
            target.addInPlace(source)
            target.multiplyInPlace(0.5)
        }

        // The first measured window absorbs any final JVM profiling/OSR bookkeeping. The second
        // window is the steady-state contract and must remain allocation-free.
        val firstWindowBytes = runMeasuredWindow()
        val steadyStateBytes = runMeasuredWindow()
        println(
            "[Matrix3x3 ZeroGC Test] first=$firstWindowBytes bytes, " +
                "steady-state=$steadyStateBytes bytes over 10,000 in-place matrix ops"
        )

        assertTrue(
            firstWindowBytes <= 4096L,
            "JVM bookkeeping exceeded the bounded first-window allowance ($firstWindowBytes bytes)"
        )
        assertTrue(
            steadyStateBytes == 0L,
            "In-place matrix scratchpad operations must allocate zero steady-state bytes " +
                "(was $steadyStateBytes bytes)"
        )
    }
}
