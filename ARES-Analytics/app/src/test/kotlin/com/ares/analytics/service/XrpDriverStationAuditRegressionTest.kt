package com.ares.analytics.service

import com.ares.analytics.di.KeyboardDriveState
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XrpDriverStationAuditRegressionTest {

    @Test
    fun `keyboard disarm preserves gamepad input preference`() {
        val state = KeyboardDriveState()
        state.useGamepad = true
        state.enabled = true
        state.isWPressed = true

        state.disarm(preserveInputSource = true)

        assertFalse(state.enabled)
        assertFalse(state.isWPressed)
        assertTrue(state.useGamepad, "transient disarm must preserve operator's gamepad preference")

        state.disarm()
        assertFalse(state.useGamepad, "full shutdown disarm resets input source preference")
    }

    @Test
    fun `xrp link rejects blank host to prevent unintended loopback resolution`() {
        val dbDir = Files.createTempDirectory("ares-xrp-blank-host-").toFile()
        val db = DatabaseService(File(dbDir, "test.duckdb").absolutePath)
        val nt4 = Nt4ClientService(db)
        val xrp = XrpLinkService(nt4)
        try {
            assertFailsWith<IllegalArgumentException> {
                xrp.start(
                    host = "   ",
                    port = XrpLinkService.DEFAULT_PORT,
                    expectedProjectId = "xrp-demo",
                    expectedCanonicalContentSha256 = "a".repeat(64),
                )
            }
        } finally {
            runBlocking {
                xrp.disposeAndJoin()
                nt4.disposeAndJoin()
                db.closeAndJoin()
            }
            dbDir.deleteRecursively()
        }
    }

    @Test
    fun `acceptExternalLiveTelemetry stamps active recording sessionId instead of hardcoding live-telemetry`() = runBlocking {
        val dbDir = Files.createTempDirectory("ares-xrp-recording-").toFile()
        val db = DatabaseService(File(dbDir, "test.duckdb").absolutePath)
        val nt4 = Nt4ClientService(db)
        try {
            val session = nt4.startRecordingSession(
                teamId = "23247",
                seasonId = "2026",
                robotId = "xrp-bot",
            )
            nt4.acceptExternalLiveTelemetry("ARES/EstimatedPose/0", 1.25)
            nt4.stopRecordingSession()

            val frames = db.getTelemetryForKey(session.sessionId, "ARES/EstimatedPose/0")
            assertTrue(frames.any { it.key == "ARES/EstimatedPose/0" && it.value == 1.25 })
            assertEquals(session.sessionId, frames.first().sessionId)
        } finally {
            nt4.disposeAndJoin()
            db.closeAndJoin()
            dbDir.deleteRecursively()
        }
    }
}
