package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.mockito.Mockito.*
import kotlin.test.*

class Nt4DisposalAuditTest {
    // Observe the actual private lifetime instead of adding a production API solely for tests.
    private fun owner(client: Nt4ClientService): Job {
        val field = Nt4ClientService::class.java.getDeclaredField("serviceScope").apply { isAccessible = true }
        return (field.get(client) as CoroutineScope).coroutineContext[Job]!!
    }

    private suspend fun withClient(block: suspend (Nt4ClientService, DatabaseService, Job) -> Unit) {
        val database = mock(DatabaseService::class.java)
        val client = Nt4ClientService(database)
        val lifetime = owner(client)
        try { block(client, database, lifetime) }
        finally {
            // Baseline failures must not themselves retain the service's background workers.
            withContext(NonCancellable) { lifetime.cancelAndJoin() }
        }
    }

    @Test fun `terminal disposal joins the actual service lifetime and its workers`() = runTest {
        withClient { client, _, lifetime ->
            assertTrue(lifetime.children.any())
            assertTrue(client.disposeAndJoin())
            assertTrue(lifetime.isCompleted, "terminal disposal left the service owner active")
            assertFalse(lifetime.children.any())
        }
    }

    @Test fun `failed final persistence still releases workers and retains retry data`() = runTest {
        withClient { client, database, lifetime ->
            doAnswer { throw java.sql.SQLException("injected write failure") }.`when`(database).insertTelemetryFrames(anyList())
            client.publishFrame(TelemetryFrame(1, "live", "Audit/Value", 3.0))
            assertFalse(client.disposeAndJoin())
            assertEquals(1, client.retainedRetryFrameCount())
            assertTrue(lifetime.isCompleted, "failed persistence leaked the service owner")
            reset(database)
            assertTrue(client.flushPendingFrames())
            assertEquals(0, client.retainedRetryFrameCount())
        }
    }

    @Test fun `canceled disposal still joins workers`() = runTest {
        withClient { client, database, lifetime ->
            doAnswer { throw java.sql.SQLException("hold at retry delay") }.`when`(database).insertTelemetryFrames(anyList())
            client.publishFrame(TelemetryFrame(1, "live", "Audit/Value", 3.0))
            val disposal = launch(start = CoroutineStart.UNDISPATCHED) { client.disposeAndJoin() }
            assertTrue(disposal.isActive)
            disposal.cancelAndJoin()
            assertTrue(lifetime.isCompleted, "cancellation abandoned the service owner")
        }
    }

    @Test fun `ordinary stop preserves the reusable service lifetime`() = runTest {
        withClient { client, _, lifetime ->
            assertTrue(client.stop())
            assertTrue(lifetime.isActive)
            assertTrue(lifetime.children.any())
        }
    }

    @Test fun `terminal disposal waits for owned worker cleanup`() = runTest {
        withClient { client, _, lifetime ->
            val cleanupStarted = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val worker = CoroutineScope(lifetime + Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
            val disposal = async { client.disposeAndJoin() }
            try {
                cleanupStarted.await()
                assertFalse(disposal.isCompleted)
                releaseCleanup.complete(Unit)
                assertTrue(disposal.await())
                assertTrue(worker.isCompleted)
                assertTrue(lifetime.isCompleted)
            } finally { releaseCleanup.complete(Unit) }
        }
    }

    @Test fun `repeated terminal disposal remains complete and rejects later starts`() = runTest {
        withClient { client, _, lifetime ->
            assertTrue(client.disposeAndJoin())
            assertTrue(client.disposeAndJoin())
            client.start("127.0.0.1", "team", "season", "robot", 1)
            assertEquals(0L, client.connectionMetrics().attempts)
            assertFalse(client.isConnected.value)
            assertTrue(lifetime.isCompleted)
        }
    }
}
