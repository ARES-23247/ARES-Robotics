package com.areslib.math.estimation

import com.areslib.logging.ARESDataLogger
import com.areslib.math.geometry.Matrix3x3
import com.areslib.math.wrapAngle
import com.areslib.state.RobotState
import com.areslib.state.VisionMeasurement
import com.areslib.state.VisionSolverType
import com.google.gson.GsonBuilder
import java.io.File

enum class LocalizationCalibrationPlatform { FTC, FRC }

enum class LocalizationCalibrationTestType {
    VISION_STATIONARY,
    ODOMETRY_TRANSLATION,
    ODOMETRY_ROTATION,
    COMBINED_VALIDATION
}

enum class LocalizationCalibrationCheckpoint { NONE, START, END }

/** One synchronized, portable localization-calibration observation. */
data class LocalizationCalibrationSample(
    val timestampMs: Long,
    val platform: LocalizationCalibrationPlatform,
    val testType: LocalizationCalibrationTestType,
    val runId: Int,
    val checkpoint: LocalizationCalibrationCheckpoint = LocalizationCalibrationCheckpoint.NONE,
    val truthValid: Boolean = false,
    val truthX: Double = Double.NaN,
    val truthY: Double = Double.NaN,
    val truthHeading: Double = Double.NaN,
    val odometryX: Double,
    val odometryY: Double,
    val odometryHeading: Double,
    val estimateX: Double,
    val estimateY: Double,
    val estimateHeading: Double,
    val covariance: DoubleArray,
    val linearVelocityMps: Double,
    val angularVelocityRadPerSec: Double,
    val mt1Valid: Boolean = false,
    val mt1X: Double = Double.NaN,
    val mt1Y: Double = Double.NaN,
    val mt1Heading: Double = Double.NaN,
    val mt2Valid: Boolean = false,
    val mt2X: Double = Double.NaN,
    val mt2Y: Double = Double.NaN,
    val mt2Heading: Double = Double.NaN,
    val tagCount: Int = 0,
    val tagDistanceMeters: Double = Double.NaN,
    val visionLatencyMs: Double = Double.NaN,
    val nis: Double = Double.NaN,
    /** MegaTag2 translation updates are 2-DOF; full-pose updates are 3-DOF. */
    val nisDegreesOfFreedom: Int = 3,
    val visionAccepted: Boolean = false
) {
    init {
        require(covariance.size == 9) { "Localization covariance must contain 9 elements" }
    }

    companion object {
        fun capture(
            timestampMs: Long,
            platform: LocalizationCalibrationPlatform,
            testType: LocalizationCalibrationTestType,
            runId: Int,
            state: RobotState,
            measurements: List<VisionMeasurement>,
            checkpoint: LocalizationCalibrationCheckpoint = LocalizationCalibrationCheckpoint.NONE,
            truthValid: Boolean = false,
            truthX: Double = Double.NaN,
            truthY: Double = Double.NaN,
            truthHeading: Double = Double.NaN
        ): LocalizationCalibrationSample {
            val drive = state.drive
            var mt1: VisionMeasurement? = null
            var mt2: VisionMeasurement? = null
            var representative: VisionMeasurement? = null
            for (measurement in measurements) {
                representative = measurement
                when (measurement.solverType) {
                    VisionSolverType.MEGATAG2 -> {
                        mt2 = measurement
                        if (measurement.hasRecoveryPose) mt1 = measurement
                    }
                    VisionSolverType.MEGATAG1 -> mt1 = measurement
                    else -> Unit
                }
            }
            val mt1Pose = when {
                mt1?.hasRecoveryPose == true -> mt1.recoveryPose
                mt1 != null -> mt1.targetPose
                else -> null
            }
            val mt2Pose = mt2?.targetPose
            val cov = drive.poseEstimator.copyCovariance()
            val targetSpace = representative?.robotPoseTargetSpace
            val tagDistance = if (targetSpace == null) Double.NaN else kotlin.math.sqrt(
                targetSpace.x * targetSpace.x + targetSpace.y * targetSpace.y + targetSpace.z * targetSpace.z
            )
            return LocalizationCalibrationSample(
                timestampMs = timestampMs,
                platform = platform,
                testType = testType,
                runId = runId,
                checkpoint = checkpoint,
                truthValid = truthValid,
                truthX = truthX,
                truthY = truthY,
                truthHeading = truthHeading,
                odometryX = drive.odometryX,
                odometryY = drive.odometryY,
                odometryHeading = drive.odometryHeading,
                estimateX = drive.poseEstimator.estimatedPoseX,
                estimateY = drive.poseEstimator.estimatedPoseY,
                estimateHeading = drive.poseEstimator.estimatedPoseHeading,
                covariance = cov,
                linearVelocityMps = kotlin.math.hypot(
                    drive.measuredFieldXVelocityMetersPerSecond,
                    drive.measuredFieldYVelocityMetersPerSecond
                ),
                angularVelocityRadPerSec = drive.measuredAngularVelocityRadiansPerSecond,
                mt1Valid = mt1Pose != null,
                mt1X = mt1Pose?.x ?: Double.NaN,
                mt1Y = mt1Pose?.y ?: Double.NaN,
                mt1Heading = mt1Pose?.rotation?.z ?: Double.NaN,
                mt2Valid = mt2Pose != null,
                mt2X = mt2Pose?.x ?: Double.NaN,
                mt2Y = mt2Pose?.y ?: Double.NaN,
                mt2Heading = mt2Pose?.rotation?.z ?: Double.NaN,
                tagCount = representative?.tagCount ?: 0,
                tagDistanceMeters = tagDistance,
                visionLatencyMs = representative?.latencyMs ?: Double.NaN,
                nis = drive.poseEstimator.lastNormalizedInnovationSquared,
                nisDegreesOfFreedom = if (representative?.solverType == VisionSolverType.MEGATAG2) 2 else 3,
                visionAccepted = state.vision.lastMeasurementAccepted
            )
        }
    }
}

/** Asynchronous robot-side calibration recorder backed by the standard local CSV logger. */
class LocalizationCalibrationRecorder(
    platform: LocalizationCalibrationPlatform,
    logDirectory: File? = null
) : AutoCloseable {
    private val logger = if (logDirectory == null) {
        ARESDataLogger(mode = "${platform.name}_LocalizationCalibration")
    } else {
        ARESDataLogger(mode = "${platform.name}_LocalizationCalibration", logDirectory = logDirectory)
    }

    val droppedSampleCount: Long get() = logger.droppedFrameCount

    fun record(sample: LocalizationCalibrationSample) {
        val row = logger.obtainMap()
        row["TimestampMs"] = sample.timestampMs
        row["Platform"] = sample.platform.name
        row["TestType"] = sample.testType.name
        row["RunId"] = sample.runId
        row["Checkpoint"] = sample.checkpoint.name
        row["TruthValid"] = sample.truthValid
        row["TruthX"] = sample.truthX
        row["TruthY"] = sample.truthY
        row["TruthHeading"] = sample.truthHeading
        row["OdomX"] = sample.odometryX
        row["OdomY"] = sample.odometryY
        row["OdomHeading"] = sample.odometryHeading
        row["EstimateX"] = sample.estimateX
        row["EstimateY"] = sample.estimateY
        row["EstimateHeading"] = sample.estimateHeading
        for (i in 0 until 9) row["P$i"] = sample.covariance[i]
        row["LinearVelocityMps"] = sample.linearVelocityMps
        row["AngularVelocityRadPerSec"] = sample.angularVelocityRadPerSec
        row["Mt1Valid"] = sample.mt1Valid
        row["Mt1X"] = sample.mt1X
        row["Mt1Y"] = sample.mt1Y
        row["Mt1Heading"] = sample.mt1Heading
        row["Mt2Valid"] = sample.mt2Valid
        row["Mt2X"] = sample.mt2X
        row["Mt2Y"] = sample.mt2Y
        row["Mt2Heading"] = sample.mt2Heading
        row["TagCount"] = sample.tagCount
        row["TagDistanceMeters"] = sample.tagDistanceMeters
        row["VisionLatencyMs"] = sample.visionLatencyMs
        row["NIS"] = sample.nis
        row["NISDegreesOfFreedom"] = sample.nisDegreesOfFreedom
        row["VisionAccepted"] = sample.visionAccepted
        logger.logFrame(row)
    }

    override fun close() = logger.stop()
}

data class VisionNoiseCalibrationFit(
    val sampleCount: Int,
    val biasX: Double,
    val biasY: Double,
    val biasHeading: Double,
    val stdDevX: Double,
    val stdDevY: Double,
    val stdDevHeading: Double
)

data class ProcessNoiseCalibrationFit(
    val routeCount: Int,
    val qX: Double,
    val qY: Double,
    val qTheta: Double
)

data class LocalizationCalibrationReport(
    val mt1: VisionNoiseCalibrationFit,
    val mt2: VisionNoiseCalibrationFit,
    val processNoise: ProcessNoiseCalibrationFit,
    val consistency: LocalizationConsistencySnapshot,
    val consistencyScale: ConsistencyScaleRecommendation,
    val warnings: List<String>
) {
    fun toJson(): String = GsonBuilder().setPrettyPrinting().create().toJson(this)
}

/** First-pass multipliers; rerun validation after applying them rather than compounding blindly. */
data class ConsistencyScaleRecommendation(
    /** Multiply vision R by this value when normalized NIS is systematically high/low. */
    val visionRScale: Double,
    /** Multiply process Q by this value when normalized NEES is systematically high/low. */
    val processQScale: Double
)

/** Deterministic offline fitter. It recommends values but never mutates robot tuning. */
object LocalizationCalibrationFitter {
    fun fit(samples: List<LocalizationCalibrationSample>): LocalizationCalibrationReport {
        val warnings = ArrayList<String>()
        val stationary = samples.filter {
            it.truthValid && (it.testType == LocalizationCalibrationTestType.VISION_STATIONARY ||
                it.testType == LocalizationCalibrationTestType.COMBINED_VALIDATION)
        }
        val mt1 = fitVision(stationary, useMt1 = true)
        val mt2 = fitVision(stationary, useMt1 = false)
        if (mt1.sampleCount < 30) warnings += "MegaTag1 fit has fewer than 30 truth-referenced frames"
        if (mt2.sampleCount < 30) warnings += "MegaTag2 fit has fewer than 30 truth-referenced frames"

        val routes = samples.filter {
            it.truthValid && it.checkpoint != LocalizationCalibrationCheckpoint.NONE &&
                (it.testType == LocalizationCalibrationTestType.ODOMETRY_TRANSLATION ||
                    it.testType == LocalizationCalibrationTestType.ODOMETRY_ROTATION)
        }.groupBy { Triple(it.platform, it.testType, it.runId) }
        var qXSum = 0.0
        var qYSum = 0.0
        var qThetaSum = 0.0
        var routeCount = 0
        for (route in routes.values) {
            val start = route.lastOrNull { it.checkpoint == LocalizationCalibrationCheckpoint.START } ?: continue
            val end = route.lastOrNull { it.checkpoint == LocalizationCalibrationCheckpoint.END } ?: continue
            val truthDx = end.truthX - start.truthX
            val truthDy = end.truthY - start.truthY
            val truthDHeading = wrapAngle(end.truthHeading - start.truthHeading)
            val odomDx = end.odometryX - start.odometryX
            val odomDy = end.odometryY - start.odometryY
            val odomDHeading = wrapAngle(end.odometryHeading - start.odometryHeading)
            val distance = kotlin.math.hypot(truthDx, truthDy)
            val translationNormalizer = distance.coerceAtLeast(0.05)
            val headingNormalizer = (distance + kotlin.math.abs(truthDHeading)).coerceAtLeast(0.05)
            qXSum += square(odomDx - truthDx) / translationNormalizer
            qYSum += square(odomDy - truthDy) / translationNormalizer
            qThetaSum += square(wrapAngle(odomDHeading - truthDHeading)) / headingNormalizer
            routeCount++
        }
        if (routeCount < 6) warnings += "Process-noise fit has fewer than 6 completed surveyed routes"
        val process = ProcessNoiseCalibrationFit(
            routeCount,
            if (routeCount == 0) Double.NaN else qXSum / routeCount,
            if (routeCount == 0) Double.NaN else qYSum / routeCount,
            if (routeCount == 0) Double.NaN else qThetaSum / routeCount
        )

        val evaluator = LocalizationConsistencyEvaluator()
        for (sample in samples) {
            if (sample.nis.isFinite()) evaluator.recordNis(sample.nis, sample.nisDegreesOfFreedom)
            if (sample.truthValid) {
                val p = sample.covariance
                evaluator.recordNees(
                    sample.estimateX, sample.estimateY, sample.estimateHeading,
                    sample.truthX, sample.truthY, sample.truthHeading,
                    Matrix3x3(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8])
                )
            }
        }
        val consistency = evaluator.snapshot()
        val scales = ConsistencyScaleRecommendation(
            visionRScale = consistency.meanNormalizedNis,
            processQScale = if (consistency.meanNees.isFinite()) consistency.meanNees / 3.0 else Double.NaN
        )
        if (consistency.nisCount < 30) warnings += "NIS validation has fewer than 30 accepted observations"
        if (consistency.neesCount < 30) warnings += "NEES validation has fewer than 30 truth-referenced observations"
        return LocalizationCalibrationReport(mt1, mt2, process, consistency, scales, warnings)
    }

    private fun fitVision(samples: List<LocalizationCalibrationSample>, useMt1: Boolean): VisionNoiseCalibrationFit {
        var count = 0
        var sx = 0.0; var sy = 0.0; var sh = 0.0
        var sx2 = 0.0; var sy2 = 0.0; var sh2 = 0.0
        for (sample in samples) {
            val valid = if (useMt1) sample.mt1Valid else sample.mt2Valid
            if (!valid) continue
            val x = if (useMt1) sample.mt1X else sample.mt2X
            val y = if (useMt1) sample.mt1Y else sample.mt2Y
            val h = if (useMt1) sample.mt1Heading else sample.mt2Heading
            if (!x.isFinite() || !y.isFinite() || !h.isFinite()) continue
            val ex = x - sample.truthX
            val ey = y - sample.truthY
            val eh = wrapAngle(h - sample.truthHeading)
            count++
            sx += ex; sy += ey; sh += eh
            sx2 += ex * ex; sy2 += ey * ey; sh2 += eh * eh
        }
        if (count == 0) return VisionNoiseCalibrationFit(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        val meanX = sx / count; val meanY = sy / count; val meanH = sh / count
        val denominator = (count - 1).coerceAtLeast(1).toDouble()
        return VisionNoiseCalibrationFit(
            count,
            meanX,
            meanY,
            meanH,
            kotlin.math.sqrt(((sx2 - count * meanX * meanX) / denominator).coerceAtLeast(0.0)),
            kotlin.math.sqrt(((sy2 - count * meanY * meanY) / denominator).coerceAtLeast(0.0)),
            kotlin.math.sqrt(((sh2 - count * meanH * meanH) / denominator).coerceAtLeast(0.0))
        )
    }

    private fun square(value: Double) = value * value
}

object LocalizationCalibrationCsv {
    fun read(files: List<File>): List<LocalizationCalibrationSample> {
        val samples = ArrayList<LocalizationCalibrationSample>()
        for (file in files) {
            val lines = file.readLines()
            if (lines.isEmpty()) continue
            val header = lines[0].split(',')
            val index = header.withIndex().associate { it.value to it.index }
            for (lineIndex in 1 until lines.size) {
                val cells = lines[lineIndex].split(',')
                fun cell(name: String) = cells.getOrNull(index[name] ?: -1).orEmpty()
                fun double(name: String) = cell(name).toDoubleOrNull() ?: Double.NaN
                fun int(name: String) = cell(name).toIntOrNull() ?: 0
                fun bool(name: String) = cell(name).equals("true", ignoreCase = true)
                try {
                    samples += LocalizationCalibrationSample(
                        timestampMs = cell("TimestampMs").toLong(),
                        platform = LocalizationCalibrationPlatform.valueOf(cell("Platform")),
                        testType = LocalizationCalibrationTestType.valueOf(cell("TestType")),
                        runId = int("RunId"),
                        checkpoint = LocalizationCalibrationCheckpoint.valueOf(cell("Checkpoint")),
                        truthValid = bool("TruthValid"),
                        truthX = double("TruthX"), truthY = double("TruthY"), truthHeading = double("TruthHeading"),
                        odometryX = double("OdomX"), odometryY = double("OdomY"), odometryHeading = double("OdomHeading"),
                        estimateX = double("EstimateX"), estimateY = double("EstimateY"), estimateHeading = double("EstimateHeading"),
                        covariance = DoubleArray(9) { double("P$it") },
                        linearVelocityMps = double("LinearVelocityMps"),
                        angularVelocityRadPerSec = double("AngularVelocityRadPerSec"),
                        mt1Valid = bool("Mt1Valid"), mt1X = double("Mt1X"), mt1Y = double("Mt1Y"), mt1Heading = double("Mt1Heading"),
                        mt2Valid = bool("Mt2Valid"), mt2X = double("Mt2X"), mt2Y = double("Mt2Y"), mt2Heading = double("Mt2Heading"),
                        tagCount = int("TagCount"), tagDistanceMeters = double("TagDistanceMeters"),
                        visionLatencyMs = double("VisionLatencyMs"), nis = double("NIS"),
                        nisDegreesOfFreedom = int("NISDegreesOfFreedom").takeIf { it in 1..3 } ?: 3,
                        visionAccepted = bool("VisionAccepted")
                    )
                } catch (_: RuntimeException) {
                    // Ignore incomplete/foreign rows; the report's sample-count warnings expose sparse input.
                }
            }
        }
        return samples
    }
}
