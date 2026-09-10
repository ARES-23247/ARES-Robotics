package com.areslib.math.estimation

import com.areslib.logging.ARESDataLogger
import com.areslib.math.geometry.Matrix3x3
import com.areslib.math.wrapAngle
import com.areslib.state.RobotState
import com.areslib.state.VisionMeasurement
import com.areslib.state.VisionSolverType
import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import java.io.BufferedReader
import java.io.File
import java.util.zip.GZIPInputStream

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
    val visionAccepted: Boolean = false,
    /** Offline source identity scopes run IDs when fitting several independent log files. */
    val sourceId: String? = null,
    /** Surveyed heading retains signed turn count; false preserves legacy shortest-arc routes. */
    val truthHeadingUnwrapped: Boolean = false
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
            truthHeading: Double = Double.NaN,
            truthHeadingUnwrapped: Boolean = false
        ): LocalizationCalibrationSample {
            val drive = state.drive
            var mt1: VisionMeasurement? = null
            var mt2: VisionMeasurement? = null
            var representative: VisionMeasurement? = null
            val vision = state.vision
            val hasNis = vision.lastNisDegreesOfFreedom in 1..3 && vision.lastNis.isFinite() && vision.lastNis >= 0.0
            var nisMeasurement: VisionMeasurement? = null
            var nisMatches = 0
            for (measurement in measurements) {
                if (representative == null || measurement.timestampMs >= representative.timestampMs) representative = measurement
                if (hasNis && measurement.timestampMs == vision.lastNisTimestampMs &&
                    measurement.sourceId == vision.lastNisSourceId && measurement.frameId == vision.lastNisFrameId &&
                    measurement.tagId == vision.lastNisTagId && measurement.solverType == vision.lastNisSolverType) {
                    nisMeasurement = measurement
                    nisMatches++
                }
                when (measurement.solverType) {
                    VisionSolverType.MEGATAG2 -> {
                        mt2 = measurement
                        if (measurement.hasRecoveryPose) mt1 = measurement
                    }
                    VisionSolverType.MEGATAG1 -> mt1 = measurement
                    else -> Unit
                }
            }
            // Ambiguous/missing packet identity cannot justify attaching an old NIS to this row.
            if (nisMatches != 1) nisMeasurement = null
            if (nisMeasurement != null) representative = nisMeasurement
            val mt1Pose = when {
                mt1?.hasRecoveryPose == true -> mt1.recoveryPose
                mt1 != null -> mt1.targetPose
                else -> null
            }
            val mt2Pose = mt2?.targetPose
            val cov = drive.poseEstimator.copyCovariance()
            val targetSpace = representative?.robotPoseTargetSpace
            val tagDistance = if (targetSpace == null) Double.NaN else
                kotlin.math.hypot(kotlin.math.hypot(targetSpace.x, targetSpace.y), targetSpace.z)
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
                truthHeadingUnwrapped = truthHeadingUnwrapped,
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
                nis = if (nisMeasurement == null) Double.NaN else vision.lastNis,
                nisDegreesOfFreedom = if (nisMeasurement == null) 0 else vision.lastNisDegreesOfFreedom,
                visionAccepted = if (nisMeasurement == null) vision.lastMeasurementAccepted else vision.lastNisAccepted
            )
        }
    }
}

/** Asynchronous robot-side calibration recorder backed by the standard local CSV logger. */
class LocalizationCalibrationRecorder(
    platform: LocalizationCalibrationPlatform,
    logDirectory: File? = null
) : AutoCloseable {
    private val forensicPolicy = com.areslib.logging.LoggingPolicy.forProfile(
        com.areslib.logging.LoggingProfile.FORENSIC
    )
    private val logger = if (logDirectory == null) {
        ARESDataLogger(mode = "${platform.name}_LocalizationCalibration", policy = forensicPolicy)
    } else {
        ARESDataLogger(
            mode = "${platform.name}_LocalizationCalibration",
            logDirectory = logDirectory,
            policy = forensicPolicy
        )
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
        row["TruthHeadingUnwrapped"] = sample.truthHeadingUnwrapped
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
    /** Unavailable/nonrepresentable statistics are JSON null, never nonstandard NaN tokens. */
    fun toJson(): String = CalibrationReportJson.gson.toJson(this)
}

private object CalibrationReportJson {
    private val doubles = JsonSerializer<Double> { value, _, _ ->
        if (value == null || !value.isFinite()) JsonNull.INSTANCE else JsonPrimitive(value)
    }
    val gson = GsonBuilder().setPrettyPrinting().serializeNulls()
        .registerTypeAdapter(Double::class.java, doubles)
        .registerTypeAdapter(Double::class.javaObjectType, doubles)
        .create()
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
        for ((name, fit) in listOf("MegaTag1" to mt1, "MegaTag2" to mt2)) {
            if (fit.sampleCount >= 2 && (!fit.biasHeading.isFinite() || !fit.stdDevX.isFinite() ||
                    !fit.stdDevY.isFinite() || !fit.stdDevHeading.isFinite())) {
                warnings += "$name fit has ambiguous or nonrepresentable statistics"
            }
        }

        val routes = samples.filter {
            it.truthValid && it.checkpoint != LocalizationCalibrationCheckpoint.NONE &&
                (it.testType == LocalizationCalibrationTestType.ODOMETRY_TRANSLATION ||
                    it.testType == LocalizationCalibrationTestType.ODOMETRY_ROTATION)
        }.groupBy { RouteKey(it.sourceId, it.platform, it.testType, it.runId) }
        var qXMean = 0.0
        var qYMean = 0.0
        var qThetaMean = 0.0
        var routeCount = 0
        for (route in routes.values) {
            val start = route.filter { it.checkpoint == LocalizationCalibrationCheckpoint.START }
                .maxByOrNull { it.timestampMs } ?: continue
            val end = route.filter { it.checkpoint == LocalizationCalibrationCheckpoint.END && it.timestampMs > start.timestampMs }
                .maxByOrNull { it.timestampMs } ?: continue
            if (!validRoutePose(start) || !validRoutePose(end)) continue
            val truthDx = end.truthX - start.truthX
            val truthDy = end.truthY - start.truthY
            val rawTruthDHeading = end.truthHeading - start.truthHeading
            val odomDx = end.odometryX - start.odometryX
            val odomDy = end.odometryY - start.odometryY
            val rawOdomDHeading = end.odometryHeading - start.odometryHeading
            if (!rawTruthDHeading.isFinite() || !rawOdomDHeading.isFinite()) continue
            val truthDHeading = wrapAngle(rawTruthDHeading)
            val odomDHeading = wrapAngle(rawOdomDHeading)
            if (!truthDx.isFinite() || !truthDy.isFinite() || !odomDx.isFinite() || !odomDy.isFinite()) continue
            val distance = kotlin.math.hypot(truthDx, truthDy)
            val translationNormalizer = distance.coerceAtLeast(0.05)
            val surveyedRotation = if (start.truthHeadingUnwrapped && end.truthHeadingUnwrapped)
                kotlin.math.abs(rawTruthDHeading) else kotlin.math.abs(truthDHeading)
            val headingNormalizer = (distance + surveyedRotation).coerceAtLeast(0.05)
            if (!translationNormalizer.isFinite() || !headingNormalizer.isFinite()) continue
            val qX = square(odomDx - truthDx) / translationNormalizer
            val qY = square(odomDy - truthDy) / translationNormalizer
            val qTheta = square(wrapAngle(odomDHeading - truthDHeading)) / headingNormalizer
            if (!qX.isFinite() || !qY.isFinite() || !qTheta.isFinite()) continue
            routeCount++
            qXMean += (qX - qXMean) / routeCount
            qYMean += (qY - qYMean) / routeCount
            qThetaMean += (qTheta - qThetaMean) / routeCount
        }
        if (routeCount < 6) warnings += "Process-noise fit has fewer than 6 completed surveyed routes"
        val process = ProcessNoiseCalibrationFit(
            routeCount,
            if (routeCount == 0) Double.NaN else qXMean,
            if (routeCount == 0) Double.NaN else qYMean,
            if (routeCount == 0) Double.NaN else qThetaMean
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
        if (consistency.nisCount < 30) warnings += "NIS validation has fewer than 30 valid observations"
        if (consistency.neesCount < 30) warnings += "NEES validation has fewer than 30 truth-referenced observations"
        return LocalizationCalibrationReport(mt1, mt2, process, consistency, scales, warnings)
    }

    private data class RouteKey(
        val sourceId: String?, val platform: LocalizationCalibrationPlatform,
        val testType: LocalizationCalibrationTestType, val runId: Int
    )

    private fun validRoutePose(sample: LocalizationCalibrationSample): Boolean =
        sample.truthX.isFinite() && sample.truthY.isFinite() && sample.truthHeading.isFinite() &&
            sample.odometryX.isFinite() && sample.odometryY.isFinite() && sample.odometryHeading.isFinite()

    private fun validVisionSample(sample: LocalizationCalibrationSample, useMt1: Boolean): Boolean {
        if (!sample.truthX.isFinite() || !sample.truthY.isFinite() || !sample.truthHeading.isFinite()) return false
        val x = if (useMt1) sample.mt1X else sample.mt2X
        val y = if (useMt1) sample.mt1Y else sample.mt2Y
        val heading = if (useMt1) sample.mt1Heading else sample.mt2Heading
        return (if (useMt1) sample.mt1Valid else sample.mt2Valid) && heading.isFinite() &&
            (x - sample.truthX).isFinite() && (y - sample.truthY).isFinite() &&
            (heading - sample.truthHeading).isFinite()
    }

    private fun fitVision(samples: List<LocalizationCalibrationSample>, useMt1: Boolean): VisionNoiseCalibrationFit {
        val xMoments = CalibrationMoments()
        val yMoments = CalibrationMoments()
        var sinHeading = 0.0
        var cosHeading = 0.0
        for (sample in samples) {
            if (!validVisionSample(sample, useMt1)) continue
            val x = if (useMt1) sample.mt1X else sample.mt2X
            val y = if (useMt1) sample.mt1Y else sample.mt2Y
            val heading = if (useMt1) sample.mt1Heading else sample.mt2Heading
            xMoments.add(x - sample.truthX)
            yMoments.add(y - sample.truthY)
            val residual = wrapAngle(heading - sample.truthHeading)
            sinHeading += kotlin.math.sin(residual)
            cosHeading += kotlin.math.cos(residual)
        }
        val count = xMoments.count
        if (count == 0) return VisionNoiseCalibrationFit(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        // A vanishing resultant has no well-defined circular mean (for example, opposite headings).
        val meanHeading = if (kotlin.math.hypot(sinHeading, cosHeading) <= count * 1e-12) Double.NaN
            else wrapAngle(kotlin.math.atan2(sinHeading, cosHeading))
        var headingSquaredDeviation = 0.0
        if (meanHeading.isFinite() && count > 1) {
            for (sample in samples) {
                if (!validVisionSample(sample, useMt1)) continue
                val heading = if (useMt1) sample.mt1Heading else sample.mt2Heading
                val deviation = wrapAngle(wrapAngle(heading - sample.truthHeading) - meanHeading)
                headingSquaredDeviation += deviation * deviation
            }
        }
        return VisionNoiseCalibrationFit(
            count, xMoments.mean, yMoments.mean, meanHeading,
            xMoments.sampleStdDev(), yMoments.sampleStdDev(),
            if (count < 2 || !meanHeading.isFinite()) Double.NaN
            else kotlin.math.sqrt(headingSquaredDeviation / (count - 1))
        )
    }

    private class CalibrationMoments {
        var count = 0
        var mean = 0.0
        private var m2 = 0.0
        fun add(value: Double) {
            count++
            val delta = value - mean
            mean = if (delta.isFinite()) mean + delta / count
                else mean * ((count - 1.0) / count) + value / count
            m2 += delta * (value - mean)
        }
        fun sampleStdDev(): Double = if (count < 2) Double.NaN
            else kotlin.math.sqrt((m2 / (count - 1)).coerceAtLeast(0.0))
    }

    private fun square(value: Double) = value * value
}

object LocalizationCalibrationCsv {
    /** Streams rows while retaining sample objects; file identity scopes otherwise-local run IDs. */
    fun read(files: List<File>): List<LocalizationCalibrationSample> {
        val samples = ArrayList<LocalizationCalibrationSample>()
        for (file in files.distinctBy { it.absoluteFile.normalize().path }) {
            file.inputStream().use { input ->
                val decoded = if (file.name.endsWith(".gz", ignoreCase = true)) GZIPInputStream(input) else input
                decoded.bufferedReader(Charsets.UTF_8).use { reader ->
                    readRows(reader, file.absoluteFile.normalize().path, samples)
                }
            }
        }
        return samples
    }

    private fun readRows(reader: BufferedReader, sourceId: String, samples: MutableList<LocalizationCalibrationSample>) {
        val header = reader.readLine()?.removePrefix("\uFEFF")?.split(',') ?: return
        if (header.toSet().size != header.size) return
        val index = header.withIndex().associate { it.value to it.index }
        if (!index.keys.containsAll(listOf("TimestampMs", "Platform", "TestType", "RunId", "Checkpoint"))) return
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val cells = line.split(',')
            fun cell(name: String) = cells.getOrNull(index[name] ?: -1).orEmpty()
            fun double(name: String) = cell(name).toDoubleOrNull() ?: Double.NaN
            fun int(name: String) = cell(name).toIntOrNull() ?: 0
            fun bool(name: String) = cell(name).equals("true", ignoreCase = true)
            try {
                samples += LocalizationCalibrationSample(
                    timestampMs = cell("TimestampMs").toLong(),
                    platform = LocalizationCalibrationPlatform.valueOf(cell("Platform")),
                    testType = LocalizationCalibrationTestType.valueOf(cell("TestType")),
                    runId = cell("RunId").toInt(),
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
                    nisDegreesOfFreedom = if (index.containsKey("NISDegreesOfFreedom")) int("NISDegreesOfFreedom") else 3,
                    visionAccepted = bool("VisionAccepted"), sourceId = sourceId,
                    truthHeadingUnwrapped = bool("TruthHeadingUnwrapped")
                )
            } catch (_: RuntimeException) {
                // Ignore incomplete/foreign rows; the report's sample-count warnings expose sparse input.
            }
        }
    }
}
