package com.ares.analytics.service

import com.ares.analytics.shared.models.DriverProfile
import com.ares.analytics.shared.TelemetryMetricCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Service managing human driver control input profiles and joystick exponent/deadband response curves.
 *
 * Persists driver control parameters (joystick exponent curves, maximum rotational speed $rad/s$, translational velocity $m/s$)
 * to JSON files (`driver_profiles.json`), allowing customizable driver station input mapping across practice and competition runs.
 *
 * ### Thread Safety & Performance Guarantees:
 * Thread-safe state management utilizing `ConcurrentHashMap` and asynchronous IO disk reads/writes on `Dispatchers.IO`.
 *
 * @param databaseService Primary DuckDB telemetry database service.
 * @param sysIdService Actuator characterization engine for driver responsiveness analysis.
 * @param profilesPath Absolute filesystem path to persistent JSON driver profile storage.
 *
 * @see com.ares.analytics.shared.models.DriverProfile
 */
class DriverAnalysisService(
    private val databaseService: DatabaseService,
    private val sysIdService: SysIdService,
    private val profilesPath: String = AppDataPaths.file("driver_profiles.json").path
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val profiles = ConcurrentHashMap<String, DriverProfile>()
    private val persistenceMutex = Mutex()

    init {
        loadProfiles()
    }

    private fun loadProfiles() {
        val file = File(profilesPath).canonicalFile
        if (!file.exists()) {
            defaultProfiles().forEach { profiles[it.name] = it }
            persistProfiles()
            return
        }

        try {
            val list = json.decodeFromString<List<DriverProfile>>(file.readText())
            require(list.all(::isValidProfile)) { "Driver profile file contains invalid values" }
            list.forEach { profiles[it.name] = it }
        } catch (e: Exception) {
            val backup = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
            runCatching { Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            profiles.clear()
            defaultProfiles().forEach { profiles[it.name] = it }
            persistProfiles()
        }
    }

    fun getProfiles(): List<DriverProfile> = profiles.values.toList()

    fun getProfile(name: String): DriverProfile? = profiles[name]

    suspend fun saveProfile(profile: DriverProfile) = withContext(Dispatchers.IO) {
        require(isValidProfile(profile)) { "Profile values must be finite, positive, and have a non-blank name" }
        persistenceMutex.withLock {
            profiles[profile.name] = profile
            persistProfiles()
        }
    }

    suspend fun deleteProfile(name: String) = withContext(Dispatchers.IO) {
        persistenceMutex.withLock {
            profiles.remove(name)
            persistProfiles()
        }
    }

    private fun persistProfiles() {
        val file = File(profilesPath).canonicalFile
        file.parentFile?.let { Files.createDirectories(it.toPath()) }
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(json.encodeToString(profiles.values.sortedBy { it.name }))
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun defaultProfiles(): List<DriverProfile> = listOf(
        DriverProfile("Default Alpha", 1.2, 3.5),
        DriverProfile("Precision Mode", 1.5, 2.0),
        DriverProfile("Aggressive Mode", 1.0, Double.MAX_VALUE)
    )

    private fun isValidProfile(profile: DriverProfile): Boolean =
        profile.name.isNotBlank() &&
            profile.deadbandExponent.isFinite() && profile.deadbandExponent > 0.0 &&
            profile.slewRateLimit.isFinite() && profile.slewRateLimit > 0.0 &&
            profile.jitterPeakFrequencyHz.isFinite() && profile.jitterAmplitude.isFinite()

/**
     * Sweeps gamepad telemetry keys (X, Y, Omega) to detect 8-12Hz jitter and recommends a profile.
     */
    suspend fun analyzeDriverJitter(
        sessionId: String,
        gamepadXKey: String = TelemetryMetricCatalog.GAMEPAD_LEFT_X.canonicalKey,
        gamepadYKey: String = TelemetryMetricCatalog.GAMEPAD_LEFT_Y.canonicalKey
    ): DriverProfileAnalysisResult = withContext(Dispatchers.Default) {
        val xFrames = getTelemetryForTopic(sessionId, gamepadXKey)
        val yFrames = getTelemetryForTopic(sessionId, gamepadYKey)

        val analyses = listOf(xFrames, yFrames).mapNotNull { analyzeSignal(it) }
        if (analyses.isEmpty()) {
            return@withContext DriverProfileAnalysisResult(
                hasJitter = false,
                peakFrequencyHz = 0.0,
                recommendedExponent = 1.0,
                recommendedSlewRate = Double.MAX_VALUE,
                message = "Insufficient gamepad telemetry data to analyze driver inputs."
            )
        }
        // Pick the strongest 8-12 Hz component from either stick axis. Looking only at
        // the global dominant FFT bin misses jitter whenever intentional low-frequency
        // driver motion has more energy.
        val strongest = analyses.maxBy { it.bandAmplitude }
        val isJitterPresent = strongest.bandAmplitude >= MIN_JITTER_AMPLITUDE &&
            strongest.bandAmplitude >= strongest.noiseFloor * MIN_SIGNAL_TO_NOISE
        val peakFreq = strongest.bandFrequencyHz

        // Calculate recommendations
        var recommendedExp = 1.0
        var recommendedSlew = Double.MAX_VALUE
        var msg = "Driver inputs are smooth and stable. No high-frequency jitter detected."

        if (isJitterPresent) {
            // Recommend exponential deadband to make stick center less sensitive
            recommendedExp = 1.6
            // Recommend a slew rate limit to damp out rapid oscillation
            recommendedSlew = 2.5
            msg = "Dominant input oscillation detected at ${String.format("%.2f", peakFreq)} Hz. Recommending Deadband Exponent = 1.6 and Slew Rate Limit = 2.5."
        }

        DriverProfileAnalysisResult(
            hasJitter = isJitterPresent,
            peakFrequencyHz = peakFreq,
            recommendedExponent = recommendedExp,
            recommendedSlewRate = recommendedSlew,
            message = msg
        )
    }

    private suspend fun getTelemetryForTopic(
        sessionId: String,
        requestedKey: String
    ): List<com.ares.analytics.shared.models.TelemetryFrame> {
        val canonicalKey = TelemetryMetricCatalog.normalizeTopic(requestedKey)
        val canonicalFrames = databaseService.getTelemetryForKey(sessionId, canonicalKey)
        if (canonicalFrames.isNotEmpty()) return canonicalFrames
        return databaseService.getTelemetryForKey(sessionId, "/$canonicalKey")
    }

    private fun analyzeSignal(frames: List<com.ares.analytics.shared.models.TelemetryFrame>): SignalSpectrum? {
        if (frames.size < MIN_SAMPLES) return null
        val sorted = frames.sortedBy { it.timestampMs }
        if (sorted.any { !it.value.isFinite() }) return null
        val deltas = LongArray(sorted.size - 1) { index ->
            sorted[index + 1].timestampMs - sorted[index].timestampMs
        }.filter { it > 0L }.sorted()
        if (deltas.size < sorted.size - 1) return null
        val medianDtMs = deltas[deltas.size / 2].toDouble()
        if (!medianDtMs.isFinite() || medianDtMs <= 0.0) return null
        // An FFT assumes uniform sampling. Reject heavily gapped captures instead of
        // reporting an aliased frequency with false precision.
        if (deltas.any { kotlin.math.abs(it - medianDtMs) > medianDtMs * MAX_SAMPLE_JITTER_FRACTION }) return null

        val fft = sysIdService.performFftAnalysis(
            DoubleArray(sorted.size) { sorted[it].value },
            1000.0 / medianDtMs
        )
        if (fft.frequencies.isEmpty()) return null

        var bandAmplitude = 0.0
        var bandFrequency = 0.0
        val nonDcMagnitudes = ArrayList<Double>(fft.magnitudes.size - 1)
        for (index in 1 until fft.frequencies.size) {
            val magnitude = fft.magnitudes[index]
            nonDcMagnitudes.add(magnitude)
            if (fft.frequencies[index] in JITTER_BAND_HZ && magnitude > bandAmplitude) {
                bandAmplitude = magnitude
                bandFrequency = fft.frequencies[index]
            }
        }
        nonDcMagnitudes.sort()
        val noiseFloor = if (nonDcMagnitudes.isEmpty()) 0.0 else nonDcMagnitudes[nonDcMagnitudes.size / 2]
        return SignalSpectrum(bandFrequency, bandAmplitude, noiseFloor)
    }

    /**
     * Reviews synchronized chassis-motion observations for practice patterns.
     *
     * This method deliberately does not infer wheel slip, electrical energy, or scored cycles:
     * those require current/voltage, wheel-state, game-piece, and match-event evidence that may not
     * exist in a session. Samples from the three chassis topics are joined by source timestamp so a
     * dropped topic update cannot silently correlate unrelated frames.
     */
    suspend fun analyzeDriverCoaching(sessionId: String): DriverCoachingReport = withContext(Dispatchers.Default) {
        val vxFrames = getTelemetryForTopic(sessionId, "Drive/ChassisSpeeds/vx")
        val vyFrames = getTelemetryForTopic(sessionId, "Drive/ChassisSpeeds/vy")
        val omegaFrames = getTelemetryForTopic(sessionId, "Drive/ChassisSpeeds/omega")
        val vyByTimestamp = vyFrames.associateBy { it.timestampUs }
        val omegaByTimestamp = omegaFrames.associateBy { it.timestampUs }
        val samples = vxFrames.asSequence()
            .mapNotNull { vx ->
                val vy = vyByTimestamp[vx.timestampUs] ?: return@mapNotNull null
                val omega = omegaByTimestamp[vx.timestampUs] ?: return@mapNotNull null
                if (!vx.value.isFinite() || !vy.value.isFinite() || !omega.value.isFinite()) {
                    return@mapNotNull null
                }
                DriverMotionSample(vx.timestampUs, vx.value, vy.value, omega.value)
            }
            .sortedBy(DriverMotionSample::timestampUs)
            .toList()
        val sourceSampleCount = maxOf(vxFrames.size, vyFrames.size, omegaFrames.size)
        val coverageFraction = if (sourceSampleCount == 0) 0.0 else samples.size.toDouble() / sourceSampleCount
        val durationSeconds = samples.lastOrNull()?.let { last ->
            (last.timestampUs - samples.first().timestampUs).coerceAtLeast(0L) / 1_000_000.0
        } ?: 0.0

        if (samples.size < MIN_COACHING_SAMPLES || durationSeconds < MIN_COACHING_DURATION_SECONDS) {
            return@withContext DriverCoachingReport(
                synchronizedSampleCount = samples.size,
                sourceSampleCount = sourceSampleCount,
                durationSeconds = durationSeconds,
                coverageFraction = coverageFraction,
                simultaneousTranslationRotationFraction = 0.0,
                directionReversalRatePerMinute = 0.0,
                confidence = DriverReviewConfidence.INSUFFICIENT,
                observations = listOf(
                    DriverMotionObservation(
                        title = "More synchronized drive data is needed",
                        evidence = "${samples.size} complete chassis samples cover ${"%.2f".format(durationSeconds)} seconds.",
                        practiceIdea = "Record a longer simulator or robot run with vx, vy, and omega published in the same telemetry frame."
                    )
                )
            )
        }

        var combinedMotionSamples = 0
        var reversals = 0
        var previousMovingSample: DriverMotionSample? = null
        for (sample in samples) {
            val speed = kotlin.math.hypot(sample.vx, sample.vy)
            if (speed >= COMBINED_TRANSLATION_THRESHOLD_MPS &&
                kotlin.math.abs(sample.omega) >= COMBINED_ROTATION_THRESHOLD_RAD_PER_SEC
            ) {
                combinedMotionSamples++
            }

            if (speed >= REVERSAL_MINIMUM_SPEED_MPS) {
                val previous = previousMovingSample
                if (previous != null) {
                    val previousSpeed = kotlin.math.hypot(previous.vx, previous.vy)
                    val cosine = (previous.vx * sample.vx + previous.vy * sample.vy) / (previousSpeed * speed)
                    if (cosine <= REVERSAL_COSINE_THRESHOLD) reversals++
                }
                previousMovingSample = sample
            }
        }

        val combinedFraction = combinedMotionSamples.toDouble() / samples.size
        val reversalRate = reversals / (durationSeconds / 60.0)
        val confidence = when {
            samples.size >= 200 && durationSeconds >= 10.0 && coverageFraction >= 0.90 -> DriverReviewConfidence.STRONG
            coverageFraction >= 0.60 -> DriverReviewConfidence.LIMITED
            else -> DriverReviewConfidence.INSUFFICIENT
        }
        val observations = buildList {
            if (combinedFraction >= 0.15) {
                add(
                    DriverMotionObservation(
                        title = "Frequent combined translation and rotation",
                        evidence = "${"%.0f".format(combinedFraction * 100.0)}% of synchronized samples exceeded " +
                            "${COMBINED_TRANSLATION_THRESHOLD_MPS} m/s and ${COMBINED_ROTATION_THRESHOLD_RAD_PER_SEC} rad/s.",
                        practiceIdea = "Compare this interval with driver video, wheel states, current, and voltage. The motion pattern alone does not prove wheel slip or wasted energy."
                    )
                )
            }
            if (reversalRate >= 40.0) {
                add(
                    DriverMotionObservation(
                        title = "Frequent large direction changes",
                        evidence = "${"%.0f".format(reversalRate)} changes per minute turned the translation vector by at least 120 degrees while moving.",
                        practiceIdea = "Review those timestamps with the driver. If they were unintentional, compare a short practice run with a gentler response curve; keep the change only if the driver prefers it."
                    )
                )
            }
            if (isEmpty()) {
                add(
                    DriverMotionObservation(
                        title = "No configured motion-pattern threshold was crossed",
                        evidence = "The synchronized chassis samples stayed below this review's combined-motion and reversal thresholds.",
                        practiceIdea = "Use the timeline and driver video for context. This result is not a score and does not prove efficient or safe driving."
                    )
                )
            }
        }

        DriverCoachingReport(
            synchronizedSampleCount = samples.size,
            sourceSampleCount = sourceSampleCount,
            durationSeconds = durationSeconds,
            coverageFraction = coverageFraction,
            simultaneousTranslationRotationFraction = combinedFraction,
            directionReversalRatePerMinute = reversalRate,
            confidence = confidence,
            observations = observations
        )
    }

    private data class SignalSpectrum(
        val bandFrequencyHz: Double,
        val bandAmplitude: Double,
        val noiseFloor: Double
    )

    private companion object {
        const val MIN_SAMPLES = 64
        val JITTER_BAND_HZ = 8.0..12.0
        const val MIN_JITTER_AMPLITUDE = 0.02
        const val MIN_SIGNAL_TO_NOISE = 3.0
        const val MAX_SAMPLE_JITTER_FRACTION = 0.5
        const val MIN_COACHING_SAMPLES = 30
        const val MIN_COACHING_DURATION_SECONDS = 0.5
        const val COMBINED_TRANSLATION_THRESHOLD_MPS = 0.8
        const val COMBINED_ROTATION_THRESHOLD_RAD_PER_SEC = 1.5
        const val REVERSAL_MINIMUM_SPEED_MPS = 0.20
        const val REVERSAL_COSINE_THRESHOLD = -0.5
    }

    private data class DriverMotionSample(
        val timestampUs: Long,
        val vx: Double,
        val vy: Double,
        val omega: Double
    )
}

data class DriverProfileAnalysisResult(
    val hasJitter: Boolean,
    val peakFrequencyHz: Double,
    val recommendedExponent: Double,
    val recommendedSlewRate: Double,
    val message: String
)

enum class DriverReviewConfidence {
    INSUFFICIENT,
    LIMITED,
    STRONG
}

data class DriverMotionObservation(
    val title: String,
    val evidence: String,
    val practiceIdea: String
)

data class DriverCoachingReport(
    val synchronizedSampleCount: Int,
    val sourceSampleCount: Int,
    val durationSeconds: Double,
    val coverageFraction: Double,
    val simultaneousTranslationRotationFraction: Double,
    val directionReversalRatePerMinute: Double,
    val confidence: DriverReviewConfidence,
    val observations: List<DriverMotionObservation>
)
