package com.ares.analytics.service

import com.ares.analytics.shared.models.DriverProfile
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.service.db.AnalysisTelemetryGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Recorded driver analysis and local profile persistence. Profile values are input shaping
 * parameters, not motion measurements or automatically identified tuning gains.
 *
 * Construction loads the profile file synchronously. Saves/deletes run on Dispatchers.IO and
 * share one commit lock and immutable snapshot per canonical file within this process. Reads
 * return the latest successful local snapshot; they do not watch external file changes. Mutations
 * incorporate valid edits already on disk, but concurrent other-process writes require one owner.
 */
class DriverAnalysisService(
    private val databaseService: DatabaseService,
    private val sysIdService: SysIdService,
    profilesPath: String = AppDataPaths.file("driver_profiles.json").path,
    private val profilesWriter: (File, ByteArray) -> Unit = WRITE_DRIVER_PROFILES,
) {
    private val profiles = DriverProfileStore.open(profilesPath, profilesWriter)

    fun getProfiles(): List<DriverProfile> = profiles.all()
    fun getProfile(name: String): DriverProfile? = profiles.get(name)

    suspend fun saveProfile(profile: DriverProfile) = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        profiles.save(profile, profilesWriter) { context.ensureActive() }
    }

    suspend fun deleteProfile(name: String) = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        profiles.delete(name, profilesWriter) { context.ensureActive() }
    }

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
