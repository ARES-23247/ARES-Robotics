package com.ares.analytics.service.db

import com.ares.analytics.service.DatabaseMetrics
import com.ares.analytics.service.MonotonicClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.duckdb.DuckDBConnection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class DatabaseCoordinatorAuditTest {
    private fun audit(clock: MonotonicClock = MonotonicClock { 0L }, block: suspend CoroutineScope.(DatabaseTransactionCoordinator, DatabaseMetrics, Mutex) -> Unit) = runTest {
        DriverManager.getConnection("jdbc:duckdb:").use { write ->
            DriverManager.getConnection("jdbc:duckdb:").use { ephemeral ->
                write.unwrap(DuckDBConnection::class.java).duplicate().use { read ->
                    ephemeral.unwrap(DuckDBConnection::class.java).duplicate().use { ephemeralRead ->
                        val metrics = DatabaseMetrics(clock); val readMutex = Mutex()
                        val coordinator = DatabaseTransactionCoordinator(write, read, ephemeral, ephemeralRead, Mutex(), readMutex, metrics)
                        block(coordinator, metrics, readMutex)
                    }
                }
            }
        }
    }
    @Test fun `connection routing uses only the exact live session identity`() = audit { co, _, _ ->
        assertSame(co.ephemeralReadConnection, co.readConnectionFor("live-telemetry"))
        assertSame(co.ephemeralWriteConnection, co.writeConnectionFor("live-telemetry"))
        for (id in listOf("recorded", "live-telemetry-other", "")) {
            assertSame(co.readConnection, co.readConnectionFor(id))
            assertSame(co.writeConnection, co.writeConnectionFor(id))
        }
    }
    @Test fun `writes count attempts without sampling an unused clock`() = audit(MonotonicClock { error("unused clock called") }) { co, metrics, _ ->
        assertEquals(42, co.write { 42 })
        assertEquals(1, metrics.snapshot().writeCount)
    }
    @Test fun `read latency measures the controlled elapsed interval`() {
        val now = AtomicLong(100)
        audit(MonotonicClock { now.get() }) { co, metrics, _ ->
            assertEquals(42, co.read { now.set(350); 42 })
            assertEquals(1, metrics.snapshot().queryCount)
            assertEquals(0.00025, metrics.snapshot().averageQueryMs)
        }
    }
    @Test fun `read failure preserves cause records attempt and releases its lock`() = audit { co, metrics, _ ->
        val error = IllegalStateException("read failed")
        assertEquals("read failed", assertFailsWith<IllegalStateException> { co.read { throw error } }.message)
        assertEquals(42, co.read { 42 })
        assertEquals(2, metrics.snapshot().queryCount)
    }
    @Test fun `cancelling a running writer releases its lock and counts the attempt`() = audit { co, metrics, _ ->
        val entered = CompletableDeferred<Unit>()
        val pending = launch { co.write { entered.complete(Unit); awaitCancellation() } }
        entered.await(); pending.cancelAndJoin()
        assertEquals(42, co.write { 42 })
        assertEquals(2, metrics.snapshot().writeCount)
    }
    @Test fun `reader can complete while the writer coordinator is occupied`() = audit { co, _, _ ->
        val entered = CompletableDeferred<Unit>()
        val pending = launch { co.write { entered.complete(Unit); awaitCancellation() } }
        try { entered.await(); assertEquals(42, co.read { 42 }) }
        finally { pending.cancelAndJoin() }
    }
    @Test fun `cancelled queued read never executes SQL and releases cancellation promptly`() {
        val started = CompletableDeferred<Unit>()
        audit(MonotonicClock { started.complete(Unit); 0L }) { co, metrics, mutex ->
            mutex.lock()
            try {
                var entered = false
                val pending = launch { co.read { entered = true } }
                started.await(); pending.cancelAndJoin()
                assertFalse(entered); assertEquals(1, metrics.snapshot().queryCount)
            } finally { mutex.unlock() }
        }
    }
    @Test fun `checkpoint runs under the writer coordinator without timing overhead`() = audit(MonotonicClock { error("unused clock called") }) { co, metrics, _ ->
        co.checkpoint()
        assertEquals(1, metrics.snapshot().writeCount)
    }
}
