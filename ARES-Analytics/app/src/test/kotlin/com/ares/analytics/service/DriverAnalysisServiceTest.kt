package com.ares.analytics.service

import com.ares.analytics.shared.models.DriverProfile
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DriverAnalysisServiceTest class.
 */
class DriverAnalysisServiceTest {
    private val databases = mutableListOf<DatabaseService>()
    private val temporaryFiles = mutableListOf<File>()

    private fun temporaryFile(prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix).also { temporaryFiles.add(it) }

    @AfterTest
    fun releaseFixtures() {
        try {
            databases.forEach { it.close() }
        } finally {
            temporaryFiles.forEach { it.delete() }
        }
    }


    @Test
    fun jitterRequiresSamplingAboveTheWholeBandNyquistLimit() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("driver_jitter_sampling").toFile()
        val database = DatabaseService(File(directory, "telemetry.db").path)
        try {
            val service = DriverAnalysisService(database, SysIdService(database), File(directory, "profiles.json").path)
            val slowFrames = List(128) { index ->
                TelemetryFrame(index * 50L, "slow", "Gamepad1/LeftX",
                    0.5 * kotlin.math.sin(2.0 * kotlin.math.PI * index / 20.0))
            }
            database.insertTelemetryFrames(slowFrames)
            val insufficient = service.analyzeDriverJitter("slow")
            kotlin.test.assertFalse(insufficient.hasJitter)
            assertTrue(insufficient.message.contains("Insufficient"),
                "20 Hz sampling cannot establish absence of jitter across the full 8-12 Hz band")

            // One undersampled axis must not hide usable evidence from the other axis.
            database.insertTelemetryFrames(List(128) { index ->
                TelemetryFrame(index * 10L, "slow", "Gamepad1/LeftY",
                    0.1 * kotlin.math.sin(2.0 * kotlin.math.PI * 10.0 * index / 100.0))
            })
            val detected = service.analyzeDriverJitter("slow")
            assertTrue(detected.hasJitter)
            assertEquals(10.0, detected.peakFrequencyHz, 0.5)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    /**
     * testProfilesCRUD fun.
     */
    fun testProfilesCRUD() = runTest {
        val tempDb = temporaryFile("driver_crud_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath).also { databases.add(it) }
        val sysIdService = SysIdService(databaseService)
        val tempFile = temporaryFile("driver_profiles", ".json")
        tempFile.delete() // Delete so DriverAnalysisService writes defaults
        val service = DriverAnalysisService(databaseService, sysIdService, tempFile.absolutePath)

        // Verify default profiles loaded
        val profiles = service.getProfiles()
        assertEquals(3, profiles.size)

        // Save new profile
        val profile = DriverProfile("Pro Driver", 1.4, 4.0)
        service.saveProfile(profile)
        val retrieved = service.getProfile("Pro Driver")
        assertEquals(1.4, retrieved?.deadbandExponent)

        // Delete profile
        service.deleteProfile("Pro Driver")
        kotlin.test.assertNull(service.getProfile("Pro Driver"))
        tempDb.delete()
    }

    @Test
    /**
     * testAnalyzeDriverJitter fun.
     */
    fun testAnalyzeDriverJitter() = runTest {
        val tempDb = temporaryFile("driver_jitter_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath).also { databases.add(it) }
        val sysIdService = SysIdService(databaseService)
        val tempFile = temporaryFile("driver_profiles", ".json")
        tempFile.delete() // Delete so DriverAnalysisService writes defaults
        val service = DriverAnalysisService(databaseService, sysIdService, tempFile.absolutePath)
        val sessionId = "test-session"
        val gamepadX = "/Gamepad1/LeftX"

        // Generate intentional 1 Hz stick motion plus smaller 10 Hz jitter. The global
        // FFT peak is 1 Hz, so detection must inspect the jitter band directly.
        val frames = mutableListOf<TelemetryFrame>()
        val sampleRate = 100.0
        val freq = 10.0 // 10 Hz
        for (i in 0 until 128) {
            val t = (i * (1000.0 / sampleRate)).toLong()
            val seconds = i / sampleRate
            val value = 0.7 * kotlin.math.sin(2.0 * kotlin.math.PI * 1.0 * seconds) +
                0.1 * kotlin.math.sin(2.0 * kotlin.math.PI * freq * seconds)
            frames.add(TelemetryFrame(t, sessionId, gamepadX, value))
        }

        databaseService.insertTelemetryFrames(frames)
        val result = service.analyzeDriverJitter(sessionId, gamepadX, "/Gamepad1/LeftY")
        assertTrue(result.hasJitter)
        assertEquals(10.0, result.peakFrequencyHz, 0.5)
        kotlin.test.assertNull(result.recommendedExponent)
        kotlin.test.assertNull(result.recommendedSlewRate)

        tempFile.delete()
        tempDb.delete()
    }

    @Test
    fun testAnalyzeDriverCoaching() = runTest {
        val tempDb = temporaryFile("driver_coaching_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath).also { databases.add(it) }
        val sysIdService = SysIdService(databaseService)
        val tempFile = temporaryFile("driver_profiles", ".json")
        tempFile.delete()
        val service = DriverAnalysisService(databaseService, sysIdService, tempFile.absolutePath)
        val sessionId = "coaching-session"

        // Generate simultaneous translation and rotation; this alone does not prove wheel scrub
        val frames = mutableListOf<TelemetryFrame>()
        for (i in 0 until 100) {
            val t = (i * 20).toLong()
            frames.add(TelemetryFrame(t, sessionId, "Drive/ChassisSpeeds/vx", 1.5))
            frames.add(TelemetryFrame(t, sessionId, "Drive/ChassisSpeeds/vy", 0.0))
            frames.add(TelemetryFrame(t, sessionId, "Drive/ChassisSpeeds/omega", 2.5))
        }

        databaseService.insertTelemetryFrames(frames)
        val report = service.analyzeDriverCoaching(sessionId)

        assertEquals(100, report.synchronizedSampleCount)
        assertTrue(kotlin.test.assertNotNull(report.simultaneousTranslationRotationFraction) > 0.50, "Should detect simultaneous translation and rotation")
        assertTrue(report.observations.isNotEmpty(), "Observations should be generated")
        assertEquals(DriverReviewConfidence.LIMITED, report.confidence)

        tempFile.delete()
        tempDb.delete()
    }

    @Test
    fun coachingJoinsTopicsByTimestampInsteadOfListPosition() = runTest {
        val tempDb = temporaryFile("driver_alignment_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath).also { databases.add(it) }
        val tempFile = temporaryFile("driver_profiles", ".json").apply { delete() }
        val service = DriverAnalysisService(databaseService, SysIdService(databaseService), tempFile.absolutePath)
        val sessionId = "alignment-session"
        val frames = buildList {
            repeat(40) { index ->
                val timestampMs = index * 20L
                add(TelemetryFrame(timestampMs, sessionId, "Drive/ChassisSpeeds/vx", 1.0))
                if (index != 7) add(TelemetryFrame(timestampMs, sessionId, "Drive/ChassisSpeeds/vy", 0.0))
                add(TelemetryFrame(timestampMs, sessionId, "Drive/ChassisSpeeds/omega", 0.0))
            }
        }
        databaseService.insertTelemetryFrames(frames)

        val report = service.analyzeDriverCoaching(sessionId)

        assertEquals(39, report.synchronizedSampleCount)
        assertEquals(40, report.sourceSampleCount)
        assertTrue(report.coverageFraction < 1.0)
        databaseService.close()
        tempFile.delete()
        tempDb.delete()
    }

    @Test
    fun testAnalyzeDriverJitterOnCleanSignalReturnsNoJitter() = runTest {
        val tempDb = temporaryFile("driver_clean_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath).also { databases.add(it) }
        val sysIdService = SysIdService(databaseService)
        val tempFile = temporaryFile("driver_profiles", ".json").apply { delete() }
        val service = DriverAnalysisService(databaseService, sysIdService, tempFile.absolutePath)
        val sessionId = "clean-session"
        val gamepadX = "/Gamepad1/LeftX"

        val frames = mutableListOf<TelemetryFrame>()
        val sampleRate = 100.0
        for (i in 0 until 128) {
            val t = (i * (1000.0 / sampleRate)).toLong()
            val seconds = i / sampleRate
            val value = kotlin.math.sin(2.0 * kotlin.math.PI * 0.5 * seconds)
            frames.add(TelemetryFrame(t, sessionId, gamepadX, value))
        }

        databaseService.insertTelemetryFrames(frames)
        val result = service.analyzeDriverJitter(sessionId, gamepadX, "/Gamepad1/LeftY")
        kotlin.test.assertFalse(result.hasJitter)
        kotlin.test.assertNull(result.recommendedExponent)
        kotlin.test.assertNull(result.recommendedSlewRate)

        databaseService.close()
        tempFile.delete()
        tempDb.delete()
    }

    @Test
    fun testAnalyzeDriverCoachingOnEmptySessionReturnsInsufficientData() = runTest {
        val tempDb = temporaryFile("driver_empty_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath).also { databases.add(it) }
        val sysIdService = SysIdService(databaseService)
        val tempFile = temporaryFile("driver_profiles", ".json").apply { delete() }
        val service = DriverAnalysisService(databaseService, sysIdService, tempFile.absolutePath)

        val report = service.analyzeDriverCoaching("non-existent-session")
        assertEquals(0, report.synchronizedSampleCount)
        assertEquals(0, report.sourceSampleCount)
        assertEquals(0.0, report.coverageFraction)
        assertEquals(DriverReviewConfidence.INSUFFICIENT, report.confidence)
        assertTrue(report.observations.isNotEmpty())

        databaseService.close()
        tempFile.delete()
        tempDb.delete()
    }
}
