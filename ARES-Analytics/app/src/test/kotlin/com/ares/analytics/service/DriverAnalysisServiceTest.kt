package com.ares.analytics.service

import com.ares.analytics.shared.DriverProfile
import com.ares.analytics.shared.TelemetryFrame
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DriverAnalysisServiceTest class.
 */
class DriverAnalysisServiceTest {

    @Test
    /**
     * testProfilesCRUD fun.
     */
    fun testProfilesCRUD() = runTest {
        val tempDb = File.createTempFile("driver_crud_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sysIdService = SysIdService(databaseService)
        val tempFile = File.createTempFile("driver_profiles", ".json")
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
        val tempDb = File.createTempFile("driver_jitter_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sysIdService = SysIdService(databaseService)
        val tempFile = File.createTempFile("driver_profiles", ".json")
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
            val value = kotlin.math.sin(2.0 * kotlin.math.PI * 1.0 * seconds) +
                0.1 * kotlin.math.sin(2.0 * kotlin.math.PI * freq * seconds)
            frames.add(TelemetryFrame(t, sessionId, gamepadX, value))
        }

        databaseService.insertTelemetryFrames(frames)
        val result = service.analyzeDriverJitter(sessionId, gamepadX, "/Gamepad1/LeftY")
        assertTrue(result.hasJitter)
        assertEquals(10.0, result.peakFrequencyHz, 0.5)
        assertEquals(1.6, result.recommendedExponent)
        assertEquals(2.5, result.recommendedSlewRate)

        tempFile.delete()
        tempDb.delete()
    }

    @Test
    fun testAnalyzeDriverCoaching() = runTest {
        val tempDb = File.createTempFile("driver_coaching_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sysIdService = SysIdService(databaseService)
        val tempFile = File.createTempFile("driver_profiles", ".json")
        tempFile.delete()
        val service = DriverAnalysisService(databaseService, sysIdService, tempFile.absolutePath)
        val sessionId = "coaching-session"

        // Generate telemetry with spin-translating (scrub)
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
        assertTrue(report.simultaneousTranslationRotationFraction > 0.50, "Should detect simultaneous translation and rotation")
        assertTrue(report.observations.isNotEmpty(), "Observations should be generated")
        assertEquals(DriverReviewConfidence.LIMITED, report.confidence)

        tempFile.delete()
        tempDb.delete()
    }

    @Test
    fun coachingJoinsTopicsByTimestampInsteadOfListPosition() = runTest {
        val tempDb = File.createTempFile("driver_alignment_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val tempFile = File.createTempFile("driver_profiles", ".json").apply { delete() }
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
        val tempDb = File.createTempFile("driver_clean_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sysIdService = SysIdService(databaseService)
        val tempFile = File.createTempFile("driver_profiles", ".json").apply { delete() }
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
        assertEquals(1.0, result.recommendedExponent)
        assertEquals(Double.MAX_VALUE, result.recommendedSlewRate)

        databaseService.close()
        tempFile.delete()
        tempDb.delete()
    }

    @Test
    fun testAnalyzeDriverCoachingOnEmptySessionReturnsInsufficientData() = runTest {
        val tempDb = File.createTempFile("driver_empty_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sysIdService = SysIdService(databaseService)
        val tempFile = File.createTempFile("driver_profiles", ".json").apply { delete() }
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
