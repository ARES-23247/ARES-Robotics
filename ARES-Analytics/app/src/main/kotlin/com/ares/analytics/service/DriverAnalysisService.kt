package com.ares.analytics.service

import com.ares.analytics.shared.models.DriverProfile
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.service.db.AnalysisTelemetryGroup
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

    /** Reviews normalized joystick axes for recorded 8-12 Hz components; does not identify tuning gains. */
    suspend fun analyzeDriverJitter(
        sessionId: String,
        gamepadXKey: String = TelemetryMetricCatalog.GAMEPAD_LEFT_X.canonicalKey,
        gamepadYKey: String = TelemetryMetricCatalog.GAMEPAD_LEFT_Y.canonicalKey,
    ): DriverProfileAnalysisResult = withContext(Dispatchers.Default) {
        val keys = listOf(gamepadXKey, gamepadYKey).map(TelemetryMetricCatalog::normalizeTopic).distinct()
        val input = databaseService.getAnalysisTelemetry(sessionId, listOf(AnalysisTelemetryGroup("Driver", keys))).getValue("Driver")
        RecordedDriverAnalysis.jitter(input, keys, sysIdService::performFftAnalysis)
    }

    /** Describes synchronized recorded chassis samples and observed intervals, excluding gaps. */
    suspend fun analyzeDriverCoaching(sessionId: String): DriverCoachingReport = withContext(Dispatchers.Default) {
        val input = databaseService.getAnalysisTelemetry(sessionId,
            listOf(AnalysisTelemetryGroup("Driver", RecordedDriverAnalysis.motionKeys))).getValue("Driver")
        RecordedDriverAnalysis.coaching(input)
    }
}

data class DriverProfileAnalysisResult(
    val hasJitter: Boolean,
    val peakFrequencyHz: Double,
    // A joystick spectrum cannot identify a response curve or slew rate. Unavailable, never zero.
    val recommendedExponent: Double?,
    val recommendedSlewRate: Double?,
    val message: String,
    val axes: List<DriverAxisObservation> = emptyList(),
    val inputStatus: String = "unavailable",
)

data class DriverAxisObservation(
    val sourceKey: String,
    val status: String,
    val sampleCount: Int,
    val durationSeconds: Double = 0.0,
    val peakFrequencyHz: Double? = null,
    val bandAmplitude: Double? = null,
    val thresholdCrossed: Boolean = false,
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
    val simultaneousTranslationRotationFraction: Double?,
    val directionReversalRatePerMinute: Double?,
    val confidence: DriverReviewConfidence,
    val observations: List<DriverMotionObservation>,
    val observedDurationSeconds: Double = 0.0,
    val timeCoverageFraction: Double = 0.0,
    val inputStatus: String = "unavailable",
)
