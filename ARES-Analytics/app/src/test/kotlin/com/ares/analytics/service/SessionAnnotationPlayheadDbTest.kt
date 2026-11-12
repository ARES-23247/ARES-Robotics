package com.ares.analytics.service

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionAnnotation
import com.ares.analytics.shared.models.TelemetryFrame
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SessionAnnotationPlayheadDbTest {

    @Test
    fun `playheadMs persists round-trips orders before late createdAt and supports deleteAnnotation`() = runTest {
        val tempDir = Files.createTempDirectory("ares_playhead_note_test").toFile()
        val dbFile = File(tempDir, "telemetry.duckdb")
        val database = DatabaseService(dbFile.absolutePath)
        try {
            val session = Session(
                sessionId = "qual-12",
                teamId = "23247",
                seasonId = "2026",
                robotId = "ares-bot",
                createdAt = 10_000L,
                durationMs = 150_000L,
            )
            database.insertSession(session)

            // Post-match note added hours later (createdAt = 9_999_999L) but pinned to playhead 42_150L.
            val postMatchPinned = SessionAnnotation(
                annotationId = "note-pinned",
                sessionId = session.sessionId,
                text = "Driver full-stick launch sag",
                createdAt = 9_999_999L,
                authorId = "Coach",
                playheadMs = 42_150L,
            )
            // Legacy note without playheadMs (falls back to createdAt = 55_000L).
            val legacyNote = SessionAnnotation(
                annotationId = "note-legacy",
                sessionId = session.sessionId,
                text = "Endgame climb start",
                createdAt = 55_000L,
                authorId = "Mentor",
                playheadMs = null,
            )

            database.insertAnnotation(legacyNote)
            database.insertAnnotation(postMatchPinned)

            val loaded = database.getAnnotations(session.sessionId)
            assertEquals(2, loaded.size)
            // Ordered by COALESCE(playhead_ms, created_at): 42_150L comes before 55_000L!
            assertEquals("note-pinned", loaded[0].annotationId)
            assertEquals(42_150L, loaded[0].playheadMs)
            assertEquals(42_150L, loaded[0].effectiveTimestampMs())

            assertEquals("note-legacy", loaded[1].annotationId)
            assertNull(loaded[1].playheadMs)
            assertEquals(55_000L, loaded[1].effectiveTimestampMs())

            database.deleteAnnotation("note-pinned")
            val afterDelete = database.getAnnotations(session.sessionId)
            assertEquals(1, afterDelete.size)
            assertEquals("note-legacy", afterDelete.single().annotationId)
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `DatabaseSchemaInitializer migrates legacy session_annotations table missing playhead_ms`() = runTest {
        val tempDir = Files.createTempDirectory("ares_legacy_annot_migration").toFile()
        val dbFile = File(tempDir, "legacy.duckdb")
        try {
            Class.forName("org.duckdb.DuckDBDriver")
            DriverManager.getConnection("jdbc:duckdb:${dbFile.absolutePath}").use { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        """
                        CREATE TABLE session_annotations (
                            annotation_id VARCHAR PRIMARY KEY,
                            session_id VARCHAR NOT NULL,
                            text VARCHAR NOT NULL,
                            created_at BIGINT NOT NULL,
                            author_id VARCHAR
                        );
                        INSERT INTO session_annotations VALUES ('legacy-1', 's-old', 'Pre-migration note', 12345, 'Scout');
                        """.trimIndent()
                    )
                }
            }

            val database = DatabaseService(dbFile.absolutePath)
            try {
                val migrated = database.getAnnotations("s-old")
                assertEquals(1, migrated.size)
                assertEquals("legacy-1", migrated.single().annotationId)
                assertNull(migrated.single().playheadMs)
                assertEquals(12_345L, migrated.single().effectiveTimestampMs())

                database.insertAnnotation(
                    SessionAnnotation(
                        annotationId = "new-2",
                        sessionId = "s-old",
                        text = "Post-migration playhead note",
                        createdAt = 900_000L,
                        playheadMs = 8_000L,
                    )
                )
                val both = database.getAnnotations("s-old")
                assertEquals(listOf("new-2", "legacy-1"), both.map { it.annotationId })
            } finally {
                database.close()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `ReplayEngineService pins deletes notes and loops within In Out slice bounds`() = runTest {
        val tempDir = Files.createTempDirectory("ares_replay_slice_loop").toFile()
        val dbFile = File(tempDir, "replay.duckdb")
        val database = DatabaseService(dbFile.absolutePath)
        val replay = ReplayEngineService(
            databaseService = database,
            clock = ReplayClock { testScheduler.currentTime },
            replayDispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            val session = Session(
                sessionId = "slice-run",
                teamId = "23247",
                seasonId = "2026",
                robotId = "ares-bot",
                createdAt = 1_000L,
                durationMs = 4_000L,
            )
            database.insertSession(session)
            database.insertTelemetryFrames(
                (0..20).map { step ->
                    TelemetryFrame(
                        timestampMs = 1_000L + step * 200L,
                        sessionId = session.sessionId,
                        key = "Drive/Velocity",
                        value = step.toDouble(),
                    )
                }
            )

            replay.loadSession(session.sessionId)
            replay.seekToTimestamp(2_400L)
            val added = replay.addPlayheadAnnotation("Check wheel slip here", authorId = "Driver")
            assertNotNull(added)
            assertEquals(2_400L, added.playheadMs)
            assertEquals(1, replay.sessionAnnotations.value.size)

            replay.deleteAnnotation(added.annotationId)
            assertTrue(replay.sessionAnnotations.value.isEmpty())

            // Configure [In = 2000, Out = 3000] with looping enabled and verify playback wraps inside [2000, 3000].
            replay.setSliceIn(2_000L)
            replay.setSliceOut(3_000L)
            replay.setLooping(true)
            replay.seekToTimestamp(2_800L)
            replay.play()
            runCurrent()
            advanceTimeBy(500L)
            runCurrent()

            val playheadAfterWrap = replay.playheadTimestampMs.value
            assertTrue(
                playheadAfterWrap in 2_000L..3_000L,
                "Expected looped slice playhead inside 2000..3000, got $playheadAfterWrap",
            )
            replay.pause()
        } finally {
            replay.disposeAndJoin()
            database.close()
            tempDir.deleteRecursively()
        }
    }
}
