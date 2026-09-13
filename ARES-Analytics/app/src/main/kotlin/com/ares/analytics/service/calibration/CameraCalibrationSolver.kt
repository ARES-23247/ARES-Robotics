package com.ares.analytics.service.calibration

import com.ares.analytics.service.CalibrationDiagnostics
import com.ares.analytics.service.CalibrationMeasurement
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Pose3d
import com.ares.analytics.service.db.TelemetryExportCursor
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ejml.simple.SimpleMatrix
import org.ejml.simple.SimpleSVD
import kotlin.math.*

/**
 * Equal-weight rigid alignment of camera-to-tag translations against known tag positions.
 * Optical camera axes are right/down/forward; the aligned camera and robot axes are
 * forward/left/up. The fitted rotation is robot-frame Rz(yaw) Ry(pitch) Rx(roll), in radians.
 * This is NOT a fit of camera-in-target-space poses or pixel reprojection error.
 *
 * The robot rotates in place at the field origin, level with field Z=0. Tag positions must
 * be expressed relative to that fixed rotation center. Robot translation, roll/pitch motion,
 * unknown tag positions and systematic vision bias cannot be estimated from this dataset.
 * At least three noncollinear point correspondences are required; heading span alone does
 * not establish observability. Missing, nonfinite or degenerate input throws instead of
 * presenting a zero pose and perfect uncertainty. No files or robot values are written.
 *
 * A 3x3 SVD solves the rigid least-squares problem globally. All O(N) work and storage
 * belongs to this explicit desktop action, with no per-residual EJML matrices or iterative
 * finite-difference sweeps. Each invocation owns its workspace.
 */
class CameraCalibrationSolver(private val databaseService: DatabaseService) {
    fun solveCameraExtrinsics(measurements: List<CalibrationMeasurement>): Pose3d = fit(measurements).pose

    fun solveCameraExtrinsicsWithDiagnostics(measurements: List<CalibrationMeasurement>): CalibrationDiagnostics {
        val fit = fit(measurements)
        val information = SimpleMatrix(6, 6)
        val rotation = fit.rotation
        val rollAxis = DoubleArray(3) { rotation.get(it, 0) }
        val pitchAxis = doubleArrayOf(-sin(fit.pose.yaw), cos(fit.pose.yaw), 0.0)
        val jacobian = Array(3) { DoubleArray(6) }
        for (point in fit.cameraPoints) {
            val v = multiply(rotation, point)
            for (row in 0..2) {
                val a = (row + 1) % 3; val b = (row + 2) % 3
                jacobian[row][row] = 1.0
                jacobian[row][3] = rollAxis[a] * v[b] - rollAxis[b] * v[a]
                jacobian[row][4] = pitchAxis[a] * v[b] - pitchAxis[b] * v[a]
            }
            jacobian[0][5] = -v[1]; jacobian[1][5] = v[0]; jacobian[2][5] = 0.0
            for (row in jacobian) for (a in 0..5) for (b in 0..5) {
                information.set(a, b, information.get(a, b) + row[a] * row[b])
            }
        }
        require(information.isFinite()) { "Calibration uncertainty exceeds the numeric range" }
        val svd = information.svd()
        val largest = svd.getSingleValue(0)
        require(largest.isFinite() && svd.getSingleValue(5) > largest * 1e-12) {
            "Calibration Euler-angle uncertainty is singular or ill-conditioned; collect better geometry or avoid pitch near 90 degrees"
        }
        // Invert only an identified information matrix; regularization must not invent certainty.
        val variance = fit.squaredError / (3.0 * measurements.size - 6.0)
        val vectors = svd.v
        val covariance = Array(6) { a -> DoubleArray(6) { b ->
            var value = 0.0
            for (k in 0..5) value += vectors.get(a,k) * vectors.get(b,k) / svd.getSingleValue(k)
            value * variance
        } }
        require(covariance.all { row -> row.all { it.isFinite() } }) { "Calibration covariance is not finite" }
        return CalibrationDiagnostics(fit.pose, DoubleArray(6) { sqrt(max(0.0,covariance[it][it])) }, covariance, variance)
    }

    suspend fun runExtrinsicCalibration(sessionId: String, cameraIndex: Int): Pose3d =
        withContext(Dispatchers.Default) { solveCameraExtrinsics(loadMeasurements(sessionId,cameraIndex)) }

    suspend fun runExtrinsicCalibrationWithDiagnostics(sessionId: String, cameraIndex: Int): CalibrationDiagnostics =
        withContext(Dispatchers.Default) { solveCameraExtrinsicsWithDiagnostics(loadMeasurements(sessionId,cameraIndex)) }

    private suspend fun loadMeasurements(sessionId: String, cameraIndex: Int): List<CalibrationMeasurement> {
        require(cameraIndex >= 0) { "Camera index must be nonnegative" }
        val keys = calibrationTopicKeys(cameraIndex)
        val count = databaseService.getTelemetryExportPreflight(sessionId, keys, MAX_CALIBRATION_FRAMES).boundedFrameCount
        require(count in 0L..MAX_CALIBRATION_FRAMES.toLong()) { "Calibration run exceeds 100000 selected topic updates; use a shorter recording" }
        val frames = ArrayList<TelemetryFrame>(count.toInt())
        var cursor: TelemetryExportCursor? = null
        while (true) {
            val page = databaseService.getTelemetryExportPage(sessionId, keys, cursor, 25_000)
            if (page.isEmpty()) break
            require(frames.size.toLong() + page.size <= MAX_CALIBRATION_FRAMES) { "Calibration recording grew during loading; finish recording before fitting" }
            frames.addAll(page)
            val last = page.last()
            cursor = TelemetryExportCursor(last.timestampUs, last.sampleOrder, last.key)
        }
        require(frames.size.toLong() == count) { "Calibration recording changed during loading; finish recording before fitting" }
        return calibrationMeasurements(frames, cameraIndex)
    }

    private companion object {
        const val MAX_CALIBRATION_FRAMES = 100_000
    }

    private data class Fit(val pose:Pose3d,val rotation:SimpleMatrix,val cameraPoints:Array<DoubleArray>,val squaredError:Double)

    private fun fit(measurements:List<CalibrationMeasurement>):Fit {
        require(measurements.size >= 3) { "Calibration requires at least three noncollinear observations" }
        val camera = Array(measurements.size) { DoubleArray(3) }
        val robot = Array(measurements.size) { DoubleArray(3) }
        measurements.forEachIndexed { i,m ->
            require(m.tagId >= 0 && m.gyroHeading.isFinite() && m.tagFieldX.isFinite() && m.tagFieldY.isFinite() &&
                m.tagFieldZ.isFinite() && m.targetSpaceX.isFinite() && m.targetSpaceY.isFinite() && m.targetSpaceZ.isFinite()) {
                "Calibration observation $i contains invalid tag identity or nonfinite geometry"
            }
            // Limelight targetpose_cameraspace, not camerapose_targetspace. Convert basis BEFORE mounting rotation.
            camera[i][0] = m.targetSpaceZ; camera[i][1] = -m.targetSpaceX; camera[i][2] = -m.targetSpaceY
            val c=cos(m.gyroHeading); val s=sin(m.gyroHeading)
            robot[i][0] = c*m.tagFieldX+s*m.tagFieldY
            robot[i][1] = -s*m.tagFieldX+c*m.tagFieldY
            robot[i][2] = m.tagFieldZ
        }
        val cameraMean=mean(camera);val robotMean=mean(robot)
        val cross=SimpleMatrix(3,3)
        for (i in camera.indices) for (a in 0..2) for (b in 0..2) {
            val ca = camera[i][a] - cameraMean[a]
            val rb = robot[i][b] - robotMean[b]
            cross.set(a,b,cross.get(a,b)+ca*rb)
        }
        // Rank >= 2 of cross-covariance also requires noncollinear inputs on both sides.
        val svd = observableSvd(cross)
        val u = svd.u
        val v = svd.v
        val orientation=SimpleMatrix.identity(3)
        if (v.mult(u.transpose()).determinant() < 0.0) {
            require(svd.getSingleValue(1) - svd.getSingleValue(2) > svd.getSingleValue(0) * 1e-10) {
                "Reflected calibration geometry has no uniquely identified proper rotation"
            }
            orientation.set(2, 2, -1.0)
        }
        val rotation=v.mult(orientation).mult(u.transpose())
        val rotatedMean=multiply(rotation,cameraMean)
        val translation=DoubleArray(3){robotMean[it]-rotatedMean[it]}
        val pitch=atan2(-rotation.get(2,0),hypot(rotation.get(0,0),rotation.get(1,0)))
        val gimbalLock=abs(cos(pitch))<1e-10
        val roll=if(gimbalLock) 0.0 else atan2(rotation.get(2,1),rotation.get(2,2))
        val yaw=if(gimbalLock) atan2(-rotation.get(0,1),rotation.get(1,1)) else atan2(rotation.get(1,0),rotation.get(0,0))
        var squaredError=0.0
        for(i in camera.indices) {
            val predicted=multiply(rotation,camera[i])
            for(j in 0..2) {val residual=predicted[j]+translation[j]-robot[i][j];squaredError+=residual*residual}
        }
        require(rotation.isFinite() && translation.all { it.isFinite() } && squaredError.isFinite()) {
            "Calibration fit exceeds the numeric range"
        }
        return Fit(Pose3d(translation[0],translation[1],translation[2],roll,pitch,yaw),rotation,camera,squaredError)
    }

    private fun mean(points:Array<DoubleArray>):DoubleArray {
        // Sum relative to an anchor so a common offset does not accumulate once per sample.
        val anchor=points[0]
        return DoubleArray(3) { axis ->
            var sum=0.0
            for(point in points) sum+=(point[axis]-anchor[axis])/points.size
            anchor[axis]+sum
        }.also { require(it.all(Double::isFinite)) { "Calibration centroid exceeds the numeric range" } }
    }

    private fun observableSvd(matrix: SimpleMatrix): SimpleSVD<SimpleMatrix> {
        require(matrix.isFinite()) { "Calibration geometry exceeds the numeric range" }
        val singular=matrix.svd()
        val largest=singular.getSingleValue(0)
        require(largest>0.0 && singular.getSingleValue(1)>largest*1e-10) {
            "Calibration geometry is repeated, collinear or ill-conditioned"
        }
        return singular
    }

    private fun SimpleMatrix.isFinite():Boolean {
        for(i in 0 until numRows) for(j in 0 until numCols) if(!get(i,j).isFinite()) return false
        return true
    }

    private fun multiply(matrix:SimpleMatrix,point:DoubleArray):DoubleArray = DoubleArray(3) { row ->
        matrix.get(row,0)*point[0]+matrix.get(row,1)*point[1]+matrix.get(row,2)*point[2]
    }
}
