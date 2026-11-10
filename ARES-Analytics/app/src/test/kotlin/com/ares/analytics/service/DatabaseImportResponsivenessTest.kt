package com.ares.analytics.service

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.sql.Connection

class DatabaseImportResponsivenessTest {
    @Test
    fun `workspace metadata stays available while a historical analysis owns its connection`() = withDatabase { db ->
        val lock = databaseLock(db, "readMutex")
        lock.lock()
        try {
            val sessions = withTimeout(2_000) { db.getSessionsForWorkspace("team", "2026", "robot") }
            assertEquals(listOf("recorded"), sessions.map { it.sessionId })
        } finally { lock.unlock() }
    }

    @Test
    fun `live reads stay available while a historical analysis owns its connection`() = withDatabase { db ->
        val lock = databaseLock(db, "readMutex")
        lock.lock()
        try {
            val frames = withTimeout(2_000) { db.getTelemetryRange("live-telemetry", 0, 10_000) }
            assertEquals(listOf(1.0), frames.map { it.value })
        } finally { lock.unlock() }
    }

    @Test
    fun `live ingestion and pruning stay available while a native import owns the writer`() = withDatabase { db ->
        val lock = databaseLock(db, "dbMutex")
        lock.lock()
        try {
            withTimeout(2_000) {
                db.insertTelemetryFrames(listOf(TelemetryFrame(2_000, "live-telemetry", "Drive/Power", 2.0)))
                db.pruneTelemetryFrames("live-telemetry", 1_500)
                assertEquals(listOf(2.0), db.getTelemetryRange("live-telemetry", 0, 10_000).map { it.value })
                db.deleteTelemetryFrames("live-telemetry")
                assertEquals(0L, db.countTelemetryFrames("live-telemetry"))
            }
        } finally { lock.unlock() }
    }

    @Test
    fun `a mixed recording boundary batch flushes live rows before waiting for persistent storage`() = withDatabase { db ->
        val lock = databaseLock(db, "dbMutex")
        lock.lock()
        val pending = async {
            db.insertTelemetryFrames(listOf(
                TelemetryFrame(2_000, "recorded", "Drive/Power", 3.0),
                TelemetryFrame(2_000, "live-telemetry", "Drive/Power", 2.0),
            ))
        }
        try {
            withTimeout(2_000) {
                while (db.countTelemetryFrames("live-telemetry") != 2L) delay(1)
            }
            assertFalse(pending.isCompleted, "Persistent half must still respect the import writer lock")
        } finally { lock.unlock() }
        pending.await()
        assertEquals(listOf(3.0), db.getTelemetryRange("recorded", 0, 10_000).map { it.value })
        assertEquals(listOf(1.0, 2.0), db.getTelemetryRange("live-telemetry", 0, 10_000).map { it.value })
    }

    @Test
    fun `cancelled close releases already acquired writer locks and a later close drains every connection`() = withDatabase { db ->
        val reader = databaseLock(db, "readMutex")
        val writer = databaseLock(db, "dbMutex")
        val liveWriter = databaseLock(db, "liveWriteMutex")
        reader.lock()
        val closing = launch(Dispatchers.IO) { db.closeAndJoin() }
        try {
            withTimeout(2_000) { while (!writer.isLocked || !liveWriter.isLocked) delay(1) }
            closing.cancelAndJoin()
            assertFalse(writer.isLocked)
            assertFalse(liveWriter.isLocked)
        } finally {
            closing.cancelAndJoin()
            reader.unlock()
        }
        db.closeAndJoin()
        val connections = DatabaseService::class.java.declaredFields
            .filter { Connection::class.java.isAssignableFrom(it.type) }
            .map { it.isAccessible = true; it.get(db) as Connection }
        assertEquals(5, connections.size)
        assertTrue(connections.all { it.isClosed })
    }

    private fun databaseLock(db: DatabaseService, name: String): Mutex =
        DatabaseService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(db) as Mutex

    private fun withDatabase(block: suspend CoroutineScope.(DatabaseService) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("ares-import-responsiveness-")
        val db = DatabaseService(directory.resolve("telemetry.duckdb").toString())
        try {
            db.insertSession(Session(sessionId = "recorded", teamId = "team", seasonId = "2026", robotId = "robot", createdAt = 1_000))
            db.insertTelemetryFrames(listOf(TelemetryFrame(1_000, "live-telemetry", "Drive/Power", 1.0)))
            block(db)
        } finally {
            db.close()
            directory.toFile().deleteRecursively()
        }
    }
}
