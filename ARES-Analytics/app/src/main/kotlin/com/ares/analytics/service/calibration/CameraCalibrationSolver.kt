package com.ares.analytics.service.calibration

import com.ares.analytics.service.CalibrationDiagnostics
import com.ares.analytics.service.CalibrationMeasurement
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Pose3d
import com.ares.analytics.shared.TelemetryFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ejml.simple.SimpleMatrix
import kotlin.math.*

/**
 * 6-DOF Camera Extrinsic Calibration Solver using non-linear least squares optimization (Gauss-Newton / Levenberg-Marquardt).
 *
 * Estimates 3D camera mounting pose $(dx, dy, dz, \text{roll}, \text{pitch}, \text{yaw})$ relative to the physical robot center
 * by processing target-space vision measurements across diverse calibration poses.
 *
 * ### Coordinate System & Rotation Conventions:
 * - Robot Center Frame: $+X$ forward, $+Y$ left, $+Z$ up (meters $m$).
 * - Heading ($\text{yaw}$): Radians ($rad$), **CCW-positive** (0 = +X).
 * - Target-Space Axes: $+X$ right, $+Y$ vertical up, $+Z$ depth outward from tag face ($m$).
 * - Rotation Mapping: $\text{robotYaw} = -\text{targetSpaceYaw}$ (Limelight Y-axis rotation).
 *
 * ### Optimization Formulation:
 * Minimizes reprojection residual error sum:
 * $$E = \sum_{i=1}^{N} \left\| \mathbf{P}_{\text{cam}} - \mathbf{T}_{\text{extrinsic}} \mathbf{P}_{\text{tag}, i} \right\|^2$$
 *
 * ### Thread Safety & Performance Guarantees:
 * Execution uses EJML linear algebra matrices on `Dispatchers.IO` or caller thread without thread contention.
 *
 * @param databaseService Primary DuckDB telemetry database service.
 *
 * @see OdometryCalibrationSolver
 * @see com.ares.analytics.service.Pose3d
 */
class CameraCalibrationSolver(private val databaseService: DatabaseService) {

    private fun initialParameters(measurements: List<CalibrationMeasurement>): DoubleArray {
        var sumDx = 0.0
        var sumDy = 0.0
        var sumDz = 0.0
        for (m in measurements) {
            val cosG = cos(m.gyroHeading)
            val sinG = sin(m.gyroHeading)
            // Rotate the known field-tag position into the robot frame, then
            // subtract the measured target-space translation. This gives a
            // physically anchored translation seed instead of fitting an
            // unconstrained synthetic tag position alongside the camera pose.
            sumDx += m.tagFieldX * cosG + m.tagFieldY * sinG - m.targetSpaceZ
            sumDy += -m.tagFieldX * sinG + m.tagFieldY * cosG - m.targetSpaceX
            sumDz += m.tagFieldZ - m.targetSpaceY
        }
        val count = measurements.size.toDouble()
        return doubleArrayOf(sumDx / count, sumDy / count, sumDz / count, 0.0, 0.0, 0.0)
    }

    /**
     * Solves 6-DOF camera extrinsic mounting pose $(x, y, z, \text{roll}, \text{pitch}, \text{yaw})$ from a dataset of AprilTag calibration observations.
     *
     * @param measurements List of target-space optical observations recorded during calibration rotation maneuvers.
     * @return 6-DOF camera extrinsic mounting pose relative to robot origin $(x, y, z, \text{roll}, \text{pitch}, \text{yaw})$.
     */
    fun solveCameraExtrinsics(measurements: List<CalibrationMeasurement>): Pose3d {
        if (measurements.isEmpty()) {
            return Pose3d(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val p = initialParameters(measurements)
        val numParams = p.size
        val numResiduals = measurements.size * 3
        var lambda = 0.001
        var cost = computeCost(p, measurements)
        val maxIterations = 200
        val tolerance = 1e-8

        for (iter in 0 until maxIterations) {
            val J = SimpleMatrix(numResiduals, numParams)
            val r = SimpleMatrix(numResiduals, 1)
            val rCurrent = computeResiduals(p, measurements)
            for (i in 0 until numResiduals) {
                r.set(i, 0, rCurrent[i])
            }
            val epsilon = 1e-6
            for (j in 0 until numParams) {
                val pPerturbed = p.clone()
                pPerturbed[j] += epsilon
                val rPerturbed = computeResiduals(pPerturbed, measurements)
                for (i in 0 until numResiduals) {
                    J.set(i, j, (rPerturbed[i] - rCurrent[i]) / epsilon)
                }
            }
            val Jt = J.transpose()
            val JtJ = Jt.mult(J)
            val Jtr = Jt.mult(r)
            val identity = SimpleMatrix.identity(numParams)
            val lhs = JtJ.plus(identity.scale(lambda))
            val delta: SimpleMatrix
            try {
                delta = lhs.solve(Jtr.scale(-1.0))
            } catch (e: Exception) {
                lambda *= 10.0
                continue
            }
            val pNext = DoubleArray(numParams)
            for (j in 0 until numParams) {
                pNext[j] = p[j] + delta.get(j, 0)
            }
            val nextCost = computeCost(pNext, measurements)
            if (nextCost < cost) {
                val costDiff = cost - nextCost
                cost = nextCost
                System.arraycopy(pNext, 0, p, 0, numParams)
                lambda /= 10.0

                if (costDiff < tolerance) {
                    break
                }
            } else {
                lambda *= 10.0
            }
        }

        return Pose3d(
            x = p[0],
            y = p[1],
            z = p[2],
            roll = p[3],
            pitch = p[4],
            yaw = p[5]
        )
    }

    private fun getRotationMatrix(roll: Double, pitch: Double, yaw: Double): SimpleMatrix {
        val cr = cos(roll)
        val sr = sin(roll)
        val cp = cos(pitch)
        val sp = sin(pitch)
        val cy = cos(yaw)
        val sy = sin(yaw)
        val Rx = SimpleMatrix(arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, cr, -sr),
            doubleArrayOf(0.0, sr, cr)
        ))
        val Ry = SimpleMatrix(arrayOf(
            doubleArrayOf(cp, 0.0, sp),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(-sp, 0.0, cp)
        ))
        val Rz = SimpleMatrix(arrayOf(
            doubleArrayOf(cy, -sy, 0.0),
            doubleArrayOf(sy, cy, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0)
        ))

        return Rz.mult(Ry).mult(Rx)
    }

    private fun computeResiduals(p: DoubleArray, measurements: List<CalibrationMeasurement>): DoubleArray {
        val dx = p[0]
        val dy = p[1]
        val dz = p[2]
        val roll = p[3]
        val pitch = p[4]
        val yaw = p[5]
        val R_cam = getRotationMatrix(roll, pitch, yaw)
        val residuals = DoubleArray(measurements.size * 3)

        for (i in measurements.indices) {
            val m = measurements[i]
            val cosG = cos(m.gyroHeading)
            val sinG = sin(m.gyroHeading)
            val pMeas = SimpleMatrix(arrayOf(
                doubleArrayOf(m.targetSpaceX),
                doubleArrayOf(m.targetSpaceY),
                doubleArrayOf(m.targetSpaceZ)
            ))
            val pRotated = R_cam.mult(pMeas)
            val xRot = pRotated.get(0, 0)
            val yRot = pRotated.get(1, 0)
            val zRot = pRotated.get(2, 0)

            residuals[i * 3 + 0] = (zRot + dx) * cosG - (xRot + dy) * sinG - m.tagFieldX
            residuals[i * 3 + 1] = (zRot + dx) * sinG + (xRot + dy) * cosG - m.tagFieldY
            residuals[i * 3 + 2] = yRot + dz - m.tagFieldZ
        }

        return residuals
    }

    private fun computeCost(p: DoubleArray, measurements: List<CalibrationMeasurement>): Double {
        val r = computeResiduals(p, measurements)
        var sum = 0.0
        for (v in r) {
            sum += v * v
        }
        return sum
    }

    suspend fun runExtrinsicCalibration(
        sessionId: String,
        cameraIndex: Int
    ): Pose3d = withContext(Dispatchers.Default) {
        val measurements = loadCalibrationMeasurements(sessionId, cameraIndex)
        if (!hasObservableSweep(measurements)) {
            return@withContext Pose3d(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        solveCameraExtrinsics(measurements)
    }

    fun solveCameraExtrinsicsWithDiagnostics(measurements: List<CalibrationMeasurement>): CalibrationDiagnostics {
        if (measurements.isEmpty()) {
            return CalibrationDiagnostics(
                pose = Pose3d(0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                standardErrors = DoubleArray(6),
                covarianceMatrix = Array(6) { DoubleArray(6) },
                reducedChiSquared = 0.0
            )
        }
        val p = initialParameters(measurements)
        val numParams = p.size
        val numResiduals = measurements.size * 3
        var lambda = 0.001
        var cost = computeCost(p, measurements)
        val maxIterations = 200
        val tolerance = 1e-8

        for (iter in 0 until maxIterations) {
            val J = SimpleMatrix(numResiduals, numParams)
            val r = SimpleMatrix(numResiduals, 1)
            val rCurrent = computeResiduals(p, measurements)
            for (i in 0 until numResiduals) {
                r.set(i, 0, rCurrent[i])
            }
            val epsilon = 1e-6
            for (j in 0 until numParams) {
                val pPerturbed = p.clone()
                pPerturbed[j] += epsilon
                val rPerturbed = computeResiduals(pPerturbed, measurements)
                for (i in 0 until numResiduals) {
                    J.set(i, j, (rPerturbed[i] - rCurrent[i]) / epsilon)
                }
            }
            val Jt = J.transpose()
            val JtJ = Jt.mult(J)
            val Jtr = Jt.mult(r)
            val identity = SimpleMatrix.identity(numParams)
            val lhs = JtJ.plus(identity.scale(lambda))
            val delta: SimpleMatrix
            try {
                delta = lhs.solve(Jtr.scale(-1.0))
            } catch (e: Exception) {
                lambda *= 10.0
                continue
            }
            val pNext = DoubleArray(numParams)
            for (j in 0 until numParams) {
                pNext[j] = p[j] + delta.get(j, 0)
            }
            val nextCost = computeCost(pNext, measurements)
            if (nextCost < cost) {
                val costDiff = cost - nextCost
                cost = nextCost
                System.arraycopy(pNext, 0, p, 0, numParams)
                lambda /= 10.0

                if (costDiff < tolerance) {
                    break
                }
            } else {
                lambda *= 10.0
            }
        }
        val finalJ = SimpleMatrix(numResiduals, numParams)
        val rCurrent = computeResiduals(p, measurements)
        val epsilon = 1e-6
        for (j in 0 until numParams) {
            val pPerturbed = p.clone()
            pPerturbed[j] += epsilon
            val rPerturbed = computeResiduals(pPerturbed, measurements)
            for (i in 0 until numResiduals) {
                finalJ.set(i, j, (rPerturbed[i] - rCurrent[i]) / epsilon)
            }
        }
        val Jt = finalJ.transpose()
        val JtJ = Jt.mult(finalJ)
        val covMatrix = try {
            JtJ.invert()
        } catch (e: Exception) {
            val regularizedJtJ = JtJ.plus(SimpleMatrix.identity(numParams).scale(1e-6))
            try {
                regularizedJtJ.invert()
            } catch (e2: Exception) {
                SimpleMatrix(numParams, numParams)
            }
        }
        val sumSquaredResiduals = computeCost(p, measurements)
        val degreesOfFreedom = (numResiduals - numParams).coerceAtLeast(1)
        val chiSquaredReduced = sumSquaredResiduals / degreesOfFreedom
        val standardErrors = DoubleArray(6)
        for (j in 0 until 6) {
            val variance = covMatrix.get(j, j)
            standardErrors[j] = sqrt(max(0.0, variance) * chiSquaredReduced)
        }
        val covariance6x6 = Array(6) { r ->
            DoubleArray(6) { c ->
                covMatrix.get(r, c) * chiSquaredReduced
            }
        }
        val solvedPose = Pose3d(
            x = p[0],
            y = p[1],
            z = p[2],
            roll = p[3],
            pitch = p[4],
            yaw = p[5]
        )

        return CalibrationDiagnostics(
            pose = solvedPose,
            standardErrors = standardErrors,
            covarianceMatrix = covariance6x6,
            reducedChiSquared = chiSquaredReduced
        )
    }

    suspend fun runExtrinsicCalibrationWithDiagnostics(
        sessionId: String,
        cameraIndex: Int
    ): CalibrationDiagnostics = withContext(Dispatchers.Default) {
        val measurements = loadCalibrationMeasurements(sessionId, cameraIndex)
        if (!hasObservableSweep(measurements)) {
            return@withContext CalibrationDiagnostics(
                pose = Pose3d(0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                standardErrors = DoubleArray(6),
                covarianceMatrix = Array(6) { DoubleArray(6) },
                reducedChiSquared = 0.0
            )
        }
        solveCameraExtrinsicsWithDiagnostics(measurements)
    }

    private suspend fun loadCalibrationMeasurements(
        sessionId: String,
        cameraIndex: Int
    ): List<CalibrationMeasurement> {
        val frames = databaseService.getTelemetryRange(sessionId, 0L, Long.MAX_VALUE)
        fun normalizedKey(frame: TelemetryFrame) = frame.key.removePrefix("/")

        val gyroFrames = frames.filter { normalizedKey(it) == "Calibration/GyroHeading" }.sortedBy { it.timestampMs }
        val tagIdFrames = frames.filter { normalizedKey(it) == "Calibration/TagIndex" }.sortedBy { it.timestampMs }
        val cameraIndexFrames = frames.filter { normalizedKey(it) == "Calibration/CameraIndex" }.sortedBy { it.timestampMs }
        val componentFrames = Array(6) { mutableListOf<TelemetryFrame>() }
        val tagFieldFrames = Array(3) { mutableListOf<TelemetryFrame>() }

        for (frame in frames) {
            val key = normalizedKey(frame)
            val destination = when {
                key.startsWith("Calibration/CameraToTag") -> componentFrames to key.removePrefix("Calibration/CameraToTag")
                key.startsWith("Calibration/TagField") -> tagFieldFrames to key.removePrefix("Calibration/TagField")
                else -> continue
            }
            val suffix = destination.second
            val indices = Regex("\\d+").findAll(suffix).map { it.value.toInt() }.toList()
            val componentIndex = when {
                indices.size == 1 -> indices[0]
                indices.size >= 2 && indices[0] == cameraIndex -> indices[1]
                else -> -1
            }
            if (componentIndex in destination.first.indices) destination.first[componentIndex].add(frame)
        }
        componentFrames.forEach { it.sortBy(TelemetryFrame::timestampMs) }
        tagFieldFrames.forEach { it.sortBy(TelemetryFrame::timestampMs) }

        if (gyroFrames.isEmpty() || tagIdFrames.isEmpty() ||
            componentFrames.any { it.isEmpty() } || tagFieldFrames.any { it.isEmpty() }
        ) {
            return emptyList()
        }
        val measurements = mutableListOf<CalibrationMeasurement>()

        for (tagFrame in tagIdFrames) {
            val t = tagFrame.timestampMs
            if (cameraIndexFrames.isNotEmpty() && getValOrNull(cameraIndexFrames, t)?.toInt() != cameraIndex) continue
            val gyro = getValOrNull(gyroFrames, t) ?: continue
            val tagId = tagFrame.value.toInt()
            val tagFieldX = getValOrNull(tagFieldFrames[0], t) ?: continue
            val tagFieldY = getValOrNull(tagFieldFrames[1], t) ?: continue
            val tagFieldZ = getValOrNull(tagFieldFrames[2], t) ?: continue
            val targetValues = DoubleArray(6) { index -> getValOrNull(componentFrames[index], t) ?: Double.NaN }
            if (!gyro.isFinite() || !tagFrame.value.isFinite() ||
                !tagFieldX.isFinite() || !tagFieldY.isFinite() || !tagFieldZ.isFinite() ||
                targetValues.any { !it.isFinite() }
            ) continue

            measurements.add(
                CalibrationMeasurement(
                    gyroHeading = gyro,
                    tagId = tagId,
                    tagFieldX = tagFieldX,
                    tagFieldY = tagFieldY,
                    tagFieldZ = tagFieldZ,
                    targetSpaceX = targetValues[0],
                    targetSpaceY = targetValues[1],
                    targetSpaceZ = targetValues[2],
                    targetSpaceRoll = targetValues[3],
                    targetSpacePitch = targetValues[4],
                    targetSpaceYaw = targetValues[5]
                )
            )
        }
        return measurements
    }

    private fun getValOrNull(targetList: List<TelemetryFrame>, timestampMs: Long): Double? {
        if (targetList.isEmpty()) return null
        val targetTime = timestampMs
        var low = 0
        var high = targetList.size - 1
        var bestFrame = targetList[0]
        var minDiff = abs(bestFrame.timestampMs - targetTime)

        while (low <= high) {
            val mid = (low + high) ushr 1
            val midFrame = targetList[mid]
            val diff = abs(midFrame.timestampMs - targetTime)
            if (diff < minDiff) {
                minDiff = diff
                bestFrame = midFrame
            }
            if (midFrame.timestampMs == targetTime) {
                return midFrame.value
            } else if (midFrame.timestampMs < targetTime) {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return if (minDiff <= MAX_SAMPLE_SKEW_MS) bestFrame.value else null
    }

    private fun hasObservableSweep(measurements: List<CalibrationMeasurement>): Boolean {
        if (measurements.size < MIN_CALIBRATION_SAMPLES) return false
        var maxSeparation = 0.0
        for (i in measurements.indices) {
            for (j in i + 1 until measurements.size) {
                val delta = kotlin.math.atan2(
                    kotlin.math.sin(measurements[j].gyroHeading - measurements[i].gyroHeading),
                    kotlin.math.cos(measurements[j].gyroHeading - measurements[i].gyroHeading)
                )
                maxSeparation = maxOf(maxSeparation, kotlin.math.abs(delta))
            }
        }
        return maxSeparation >= MIN_HEADING_SPAN_RADIANS
    }

    private companion object {
        const val MAX_SAMPLE_SKEW_MS = 100L
        const val MIN_CALIBRATION_SAMPLES = 3
        const val MIN_HEADING_SPAN_RADIANS = 0.35
    }
}
