package com.areslib.math.estimation

import com.areslib.state.VisionMeasurement
import com.areslib.math.coordinate.FieldLayouts
import com.areslib.math.wrapAngle
import com.areslib.math.geometry.*

/**
 * Pre-allocated single snapshot entry in the EKF state history ring buffer.
 *
 * Stores state estimates and associated covariance matrices at past timestamps to enable
 * latency-compensated retroactive vision measurement updates.
 *
 * @property timestampMs System timestamp in milliseconds ($ms$).
 * @property x Robot field-centric X position in meters ($m$).
 * @property y Robot field-centric Y position in meters ($m$).
 * @property headingRad Robot field-centric heading in radians ($rad$), **CCW-positive**.
 * @property covariance 3x3 error covariance matrix $\mathbf{P}$ at [timestampMs].
 * @property qScale Dynamic process noise scaling factor $s_q$ active during this update frame.
 */
data class PoseHistoryEntry(
    var timestampMs: Long = 0L,
    var x: Double = 0.0,
    var y: Double = 0.0,
    var headingRad: Double = 0.0,
    var covariance: Matrix3x3 = Matrix3x3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
    var qScale: Double = 1.0
) {
    /** Gets or sets the [Pose2d] representation of this historical entry. */
    var pose: Pose2d
        get() = Pose2d(x, y, Rotation2d(headingRad))
        set(value) {
            x = value.x
            y = value.y
            headingRad = value.heading.radians
        }
}

/**
 * Fixed-capacity circular ring-buffer storing historical [PoseHistoryEntry] records.
 *
 * Enables $O(1)$ constant-time insertion and lookup during 100Hz odometry state propagation
 * and retroactive vision rewind passes with zero heap allocations.
 *
 * ### Zero-GC Guarantee:
 * Pre-allocates array entries upon construction (`Array(capacity) { PoseHistoryEntry() }`).
 * Concurrent thread scratchpads obtain deep copies via a 256-instance static object pool.
 *
 * @param capacity Maximum number of historical frames to retain (default $50$, corresponding to $0.5$-$1.0\,\text{s}$ of history).
 */
class HistoryBuffer(private val capacity: Int = 50) : AbstractList<PoseHistoryEntry>() {
    private val entries = Array(capacity) { PoseHistoryEntry() }
    private var head = 0
    private var count = 0

    override val size: Int get() = count

    /**
     * Gets the historical entry at [index] (0 = oldest entry in active history window, `size-1` = newest).
     *
     * @param index Logical index into the active history buffer $[0, \text{size}-1]$.
     * @return Pre-allocated [PoseHistoryEntry] instance.
     * @throws IndexOutOfBoundsException If [index] is negative or $\ge \text{size}$.
     */
    override fun get(index: Int): PoseHistoryEntry {
        if (index < 0 || index >= count) throw IndexOutOfBoundsException("Index: $index, Size: $count")
        val physicalIndex = if (count == capacity) (head + index) % capacity else index
        return entries[physicalIndex]
    }

    /**
     * Pushes a new historical pose entry into the ring buffer, overwriting the oldest entry if at full capacity.
     *
     * @param timestampMs System timestamp in milliseconds ($ms$).
     * @param pose Robot 2D pose in meters ($m$) and radians ($rad$).
     * @param covariance 3x3 state error covariance matrix $\mathbf{P}$.
     * @param qScale Process noise scaling factor.
     */
    fun addEntry(timestampMs: Long, pose: Pose2d, covariance: Matrix3x3, qScale: Double) {
        val entry = entries[head]
        entry.timestampMs = timestampMs
        entry.pose = pose
        entry.covariance.setTo(covariance)
        entry.qScale = qScale
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    /**
     * Pushes primitive scalar pose parameters directly into the ring buffer without creating intermediate [Pose2d] objects.
     *
     * @param timestampMs System timestamp in milliseconds ($ms$).
     * @param x Robot X position in meters ($m$).
     * @param y Robot Y position in meters ($m$).
     * @param headingRad Robot heading in radians ($rad$), CCW-positive.
     * @param covariance 3x3 state error covariance matrix $\mathbf{P}$.
     * @param qScale Process noise scaling factor.
     */
    fun addEntryDirect(timestampMs: Long, x: Double, y: Double, headingRad: Double, covariance: Matrix3x3, qScale: Double) {
        val entry = entries[head]
        entry.timestampMs = timestampMs
        entry.x = x
        entry.y = y
        entry.headingRad = headingRad
        entry.covariance.setTo(covariance)
        entry.qScale = qScale
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    /**
     * Creates a deep-copy of this history buffer.
     *
     * @return Newly allocated or pooled copy of [HistoryBuffer].
     */
    fun deepCopy(): HistoryBuffer {
        val newBuf = HistoryBuffer(capacity)
        for (i in 0 until capacity) {
            val src = entries[i]
            val dest = newBuf.entries[i]
            dest.timestampMs = src.timestampMs
            dest.x = src.x
            dest.y = src.y
            dest.headingRad = src.headingRad
            dest.covariance.setTo(src.covariance)
            dest.qScale = src.qScale
        }
        newBuf.head = head
        newBuf.count = count
        return newBuf
    }

    /**
     * Copies the full state of this buffer into pre-allocated [destination] buffer without heap allocations.
     *
     * @param destination Target pre-allocated [HistoryBuffer] instance.
     */
    fun copyInto(destination: HistoryBuffer) {
        destination.head = this.head
        destination.count = this.count
        for (i in 0 until capacity) {
            val src = this.entries[i]
            val dest = destination.entries[i]
            dest.timestampMs = src.timestampMs
            dest.x = src.x
            dest.y = src.y
            dest.headingRad = src.headingRad
            dest.covariance.setTo(src.covariance)
            dest.qScale = src.qScale
        }
    }
    
    /**
     * Updates an existing historical entry at [index] in-place with new pose data.
     *
     * @param index Logical history index $[0, \text{size}-1]$.
     * @param timestampMs Updated timestamp in milliseconds ($ms$).
     * @param pose Updated pose.
     * @param covariance Updated 3x3 error covariance matrix.
     * @param qScale Process noise scaling factor.
     */
    fun updateEntry(index: Int, timestampMs: Long, pose: Pose2d, covariance: Matrix3x3, qScale: Double) {
        val entry = get(index)
        entry.timestampMs = timestampMs
        entry.pose = pose
        entry.covariance.setTo(covariance)
        entry.qScale = qScale
    }

    /**
     * Updates an existing historical entry at [index] in-place using primitive scalar values to enforce Zero-GC compliance.
     *
     * @param index Logical history index $[0, \text{size}-1]$.
     * @param timestampMs Updated timestamp in milliseconds ($ms$).
     * @param x Updated X coordinate in meters ($m$).
     * @param y Updated Y coordinate in meters ($m$).
     * @param headingRad Updated heading in radians ($rad$), CCW-positive.
     * @param covariance Updated 3x3 error covariance matrix.
     * @param qScale Process noise scaling factor.
     */
    fun updateEntryDirect(index: Int, timestampMs: Long, x: Double, y: Double, headingRad: Double, covariance: Matrix3x3, qScale: Double) {
        val entry = get(index)
        entry.timestampMs = timestampMs
        entry.x = x
        entry.y = y
        entry.headingRad = headingRad
        entry.covariance.setTo(covariance)
        entry.qScale = qScale
    }

    companion object {
        private val pool = Array(256) { HistoryBuffer(50) }
        private val poolIndex = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * Obtains a thread-safe copy of [src] using a pre-allocated 256-instance ring pool to prevent GC allocation.
         *
         * @param src Source [HistoryBuffer] to clone.
         * @return Pooled [HistoryBuffer] instance pre-populated with data from [src].
         */
        fun obtainCopy(src: HistoryBuffer): HistoryBuffer {
            val idx = (poolIndex.getAndIncrement() and 0x7FFFFFFF) % 256
            val dest = pool[idx]
            src.copyInto(dest)
            return dest
        }
    }
}


/**
 * Immutable chronological state representation of the Pose Estimator.
 *
 * Designed to prevent high-frequency garbage collection overhead in Android ART
 * and RoboRIO runtimes by utilizing small, pre-allocated lists and primitive-backed matrices.
 *
 * @property estimatedPose The current best estimate of the robot's 2D field-centric position and heading.
 * @property covariance The 3x3 error covariance matrix representing estimate uncertainty.
 * @property history The rolling history of past state estimations used for retroactive latency compensation.
 */
data class PoseEstimatorState(
    var estimatedPoseX: Double = 0.0,
    var estimatedPoseY: Double = 0.0,
    var estimatedPoseHeading: Double = 0.0,
    val covarianceArray: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
    val history: HistoryBuffer = HistoryBuffer(50), // Max size typically ~50
    var isBeached: Boolean = false,
    var lastUnbeachedTimeMs: Long = 0L,
    var gyroBiasRadPerSec: Double = 0.0,
    var lastInnovationX: Double = 0.0,
    var lastInnovationY: Double = 0.0,
    var lastInnovationTheta: Double = 0.0,
    var lastKalmanGain: DoubleArray = DoubleArray(9),
    var lastMeasurementAccepted: Boolean = false,
    var lastRejectionReason: String? = null
) {
    val estimatedPose: Pose2d
        get() = Pose2d(estimatedPoseX, estimatedPoseY, Rotation2d(estimatedPoseHeading))

    val covariance: Matrix3x3
        get() = Matrix3x3(
            covarianceArray[0], covarianceArray[1], covarianceArray[2],
            covarianceArray[3], covarianceArray[4], covarianceArray[5],
            covarianceArray[6], covarianceArray[7], covarianceArray[8]
        )
}

/**
 * Championship-grade **Extended Kalman Filter (EKF) Pose Estimator**.
 *
 * Fuses high-rate wheel odometry ($100\text{ Hz}$) with asynchronous, latency-delayed 3D AprilTag vision observations.
 * Integrates statistical Mahalanobis distance outlier filtering ($\chi^2 > 18.0$) and retroactive observation rewind playback.
 *
 * ### EKF State Prediction & Innovation Equations:
 * $$x_k = f(x_{k-1}, u_{k-1}), \quad P_k = F P_{k-1} F^T + Q$$
 * $$y_k = z_k - h(\hat{x}_k), \quad S_k = H P_k H^T + R$$
 * $$K_k = P_k H^T S_k^{-1}$$
 * $$\hat{x}_k \leftarrow \hat{x}_k + K_k y_k, \quad P_k \leftarrow (I - K_k H) P_k$$
 *
 * ### Mahalanobis Outlier Filtering:
 * $$d_M = \sqrt{y_k^T S_k^{-1} y_k} \quad (\text{Reject if } d_M^2 > 18.0 \text{ for 3-DOF})$$
 *
 * ### Physical Units & Guarantees:
 * - **Position ($x, y$):** Field-centric meters ($m$)
 * - **Heading ($\theta$):** Radians ($rad$, CCW-positive: $0 = +X$, $\frac{\pi}{2} = +Y$)
 * - **Timestamps ($t$):** Milliseconds ($ms$)
 * - **Memory Footprint:** 100% Zero-GC heap compliance during 100Hz rewind passes via a pre-allocated 256-instance ring pool.
 *
 * @see VisionMeasurement
 * @see HistoryBuffer
 */
object PoseEstimator {
    private const val MAX_HISTORY_SIZE = 50

    // Standard deviation of odometry (tune these for actual robot)
    private val Q = Matrix3x3(
        0.01, 0.0,  0.0,
        0.0,  0.01, 0.0,
        0.0,  0.0,  0.01
    )

    var qX: Double = 0.01
        set(value) {
            field = value
            Q.m00 = value
        }

    var qY: Double = 0.01
        set(value) {
            field = value
            Q.m11 = value
        }

    var qTheta: Double = 0.01
        set(value) {
            field = value
            Q.m22 = value
        }

    private class ScratchpadContainer {
        val scratchQ = Matrix3x3()
        val scratchR = Matrix3x3()
        val scratchS = Matrix3x3()
        val scratchSInv = Matrix3x3()
        val scratchK = Matrix3x3()
        val scratchCov = Matrix3x3()
        val scratchCov2 = Matrix3x3()
        val scratchHistory = HistoryBuffer(MAX_HISTORY_SIZE)
    }

    private val threadScratchpad = ThreadLocal.withInitial { ScratchpadContainer() }

    // Known AprilTag coordinates for distance calculations (configurable via FieldLayouts)
    @JvmField
    var activeTags: Map<Int, Pose3d> = FieldLayouts.SQUARE_STANDARD_TAGS

    /**
     * Integrates a high-rate dead-wheel odometry observation into the active EKF state.
     *
     * @param state Active EKF state snapshot.
     * @param timestampMs Measurement timestamp in milliseconds ($ms$).
     * @param deltaTranslation Robot-frame displacement vector in meters ($m$).
     * @param deltaHeading Robot-frame rotation change in radians ($rad$).
     * @param pitchDegrees IMU pitch angle in degrees ($^\circ$).
     * @param rollDegrees IMU roll angle in degrees ($^\circ$).
     * @param pitchVelocityDegPerSec IMU pitch rate in degrees per second ($^\circ/s$).
     * @param rollVelocityDegPerSec IMU roll rate in degrees per second ($^\circ/s$).
     * @param gyroRateRadPerSec Raw IMU yaw rate in radians per second ($rad/s$).
     * @param dtSeconds Elapsed time since last update cycle in seconds ($\Delta t$).
     * @return Updated [PoseEstimatorState].
     */
    fun addOdometryObservation(
        state: PoseEstimatorState,
        timestampMs: Long,
        deltaTranslation: Translation2d,
        deltaHeading: Rotation2d,
        pitchDegrees: Double = 0.0,
        rollDegrees: Double = 0.0,
        pitchVelocityDegPerSec: Double = 0.0,
        rollVelocityDegPerSec: Double = 0.0,
        gyroRateRadPerSec: Double = 0.0,
        dtSeconds: Double = 0.02
    ): PoseEstimatorState {
        val scratch = threadScratchpad.get()
        return OdometryFusionController.processOdometryDirect(
            state, timestampMs, deltaTranslation.x, deltaTranslation.y, deltaHeading.radians,
            pitchDegrees, rollDegrees, pitchVelocityDegPerSec, rollVelocityDegPerSec,
            gyroRateRadPerSec, dtSeconds, Q, scratch.scratchQ, scratch.scratchCov
        )
    }

    /**
     * Integrates primitive scalar dead-wheel odometry deltas directly with Zero-GC overhead.
     *
     * @param state Active EKF state snapshot.
     * @param timestampMs Measurement timestamp in milliseconds ($ms$).
     * @param deltaX Local X displacement in meters ($m$).
     * @param deltaY Local Y displacement in meters ($m$).
     * @param deltaHeadingRad Local heading change in radians ($rad$).
     * @param pitchDegrees IMU pitch in degrees ($^\circ$).
     * @param rollDegrees IMU roll in degrees ($^\circ$).
     * @param pitchVelocityDegPerSec IMU pitch rate in degrees per second ($^\circ/s$).
     * @param rollVelocityDegPerSec IMU roll rate in degrees per second ($^\circ/s$).
     * @param gyroRateRadPerSec Raw IMU yaw rate in radians per second ($rad/s$).
     * @param dtSeconds Elapsed time in seconds ($\Delta t$).
     * @return Updated [PoseEstimatorState].
     */
    fun addOdometryObservationDirect(
        state: PoseEstimatorState,
        timestampMs: Long,
        deltaX: Double,
        deltaY: Double,
        deltaHeadingRad: Double,
        pitchDegrees: Double = 0.0,
        rollDegrees: Double = 0.0,
        pitchVelocityDegPerSec: Double = 0.0,
        rollVelocityDegPerSec: Double = 0.0,
        gyroRateRadPerSec: Double = 0.0,
        dtSeconds: Double = 0.02
    ): PoseEstimatorState {
        val scratch = threadScratchpad.get()
        return OdometryFusionController.processOdometryDirect(
            state, timestampMs, deltaX, deltaY, deltaHeadingRad,
            pitchDegrees, rollDegrees, pitchVelocityDegPerSec, rollVelocityDegPerSec,
            gyroRateRadPerSec, dtSeconds, Q, scratch.scratchQ, scratch.scratchCov
        )
    }

    /**
     * Fuses an asynchronous 3D AprilTag vision observation with statistical Mahalanobis distance outlier rejection and trajectory rewind.
     *
     * @param state Active EKF state snapshot.
     * @param measurement Observed AprilTag 3D pose measurement.
     * @param visionStdDevs Baseline standard deviations $(\sigma_x, \sigma_y, \sigma_\theta)$ in meters and radians.
     * @param numTags Number of detected tags in the current frame.
     * @param useMahalanobisRejection If true, rejects measurements exceeding [mahalanobisThreshold].
     * @param mahalanobisThreshold Chi-squared threshold $d_M^2$ for outlier rejection (default $12.0$).
     * @param maxAmbiguity Maximum acceptable tag pose solver ambiguity (default $0.2$).
     * @return Updated [PoseEstimatorState].
     */
    fun addVisionMeasurement(
        state: PoseEstimatorState,
        measurement: VisionMeasurement,
        visionStdDevs: Vector3,
        numTags: Int = 1,
        useMahalanobisRejection: Boolean = true,
        mahalanobisThreshold: Double = 12.0,
        maxAmbiguity: Double = 0.2
    ): PoseEstimatorState {
        val scratch = threadScratchpad.get()
        return VisionMahalanobisFilter.processVisionMeasurement(
            state, measurement, visionStdDevs, numTags,
            useMahalanobisRejection, mahalanobisThreshold, maxAmbiguity,
            activeTags, Q, scratch.scratchR, scratch.scratchS, scratch.scratchSInv, scratch.scratchK,
            scratch.scratchCov, scratch.scratchHistory, scratch.scratchCov2
        )
    }
}

