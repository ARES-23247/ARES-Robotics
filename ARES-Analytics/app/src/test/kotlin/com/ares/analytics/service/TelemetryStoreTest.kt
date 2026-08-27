package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TelemetryStoreTest {
    @Test
    fun `indexes canonical topics and isolates subscriptions`() = runTest {
        val store = TelemetryStore()
        store.accept(frame("  ///Drive/Pose_X", 1_000, 2.5))
        store.accept(frame("Drive/Pose_Y", 1_001, 3.5))

        assertEquals("Drive/Pose_X", store.observe("///Drive/Pose_X").value?.key)
        assertEquals(2.5, store.latest("Drive/Pose_X")?.value)
        assertEquals("Drive/Pose_X", store.observe(setOf("Drive/Pose_X")).first().key)
    }

    @Test
    fun `bounds history by age and frame count`() = runTest {
        val store = TelemetryStore(historyWindowMs = 100, maxFramesPerTopic = 3)
        store.accept(frame("Motor/Current", 0, 0.0))
        store.accept(frame("Motor/Current", 100, 1.0))
        store.accept(frame("Motor/Current", 150, 2.0))
        store.accept(frame("Motor/Current", 175, 3.0))
        store.accept(frame("Motor/Current", 200, 4.0))

        assertEquals(listOf(2.0, 3.0, 4.0), store.history("/Motor/Current").map { it.value })
        assertEquals(3L, store.snapshotMetrics().bufferedFrames)
    }

    @Test
    fun `silent indexing preserves history without notifying topic observers`() = runTest {
        val store = TelemetryStore()
        store.accept(frame("Drive/Pose_X", 1_000, 1.0))
        store.accept(frame("Drive/Pose_X", 1_001, 2.0), notifyConsumers = false)

        assertEquals(2.0, store.latest("Drive/Pose_X")?.value)
        assertEquals(1.0, store.observe("Drive/Pose_X").value?.value)

        store.clear()
        assertNull(store.latest("Drive/Pose_X"))
        assertNull(store.observe("Drive/Pose_X").value)
    }

    @Test
    fun `dynamic topic churn is bounded and evicts the oldest tracked topics`() = runTest {
        val store = TelemetryStore(maxTrackedTopics = 3)

        repeat(5) { index ->
            store.accept(frame("Dynamic/$index", index.toLong(), index.toDouble()))
        }

        assertNull(store.latest("Dynamic/0"))
        assertNull(store.latest("Dynamic/1"))
        assertEquals(2.0, store.latest("Dynamic/2")?.value)
        assertEquals(3, store.snapshotMetrics().activeTopics)
        assertEquals(3L, store.snapshotMetrics().bufferedFrames)
    }

    @Test
    fun `recently refreshed topic survives least recently used eviction`() = runTest {
        val store = TelemetryStore(maxTrackedTopics = 3)
        store.accept(frame("Dynamic/A", 1, 1.0))
        store.accept(frame("Dynamic/B", 2, 2.0))
        store.accept(frame("Dynamic/C", 3, 3.0))

        store.accept(frame("Dynamic/A", 4, 4.0))
        store.accept(frame("Dynamic/D", 5, 5.0))

        assertEquals(4.0, store.latest("Dynamic/A")?.value)
        assertNull(store.latest("Dynamic/B"))
        assertEquals(3.0, store.latest("Dynamic/C")?.value)
        assertEquals(5.0, store.latest("Dynamic/D")?.value)
    }

    @Test
    fun `ingestion allocates single-topic flows only for explicit observers`() = runTest {
        val store = TelemetryStore()

        repeat(100) { index ->
            store.accept(frame("Unobserved/$index", index.toLong(), index.toDouble()))
        }
        assertEquals(0, store.topicObserverCount)

        val observed = store.observe("Unobserved/99")
        assertEquals(1, store.topicObserverCount)
        store.accept(frame("Unobserved/99", 101L, 101.0))
        assertEquals(101.0, observed.value?.value)
    }

    @Test
    fun `slow dashboard collector cannot stall live telemetry producers`() = runTest {
        val store = TelemetryStore()
        val releaseCollector = CompletableDeferred<Unit>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.updates.collect {
                releaseCollector.await()
            }
        }

        withTimeout(1_000) {
            // Exceeds replay + extra buffer capacity. BufferOverflow.SUSPEND deadlocks here
            // behind the deliberately stalled collector; DROP_OLDEST remains non-blocking.
            repeat(5_000) { index ->
                store.accept(frame("Drive/Live", index.toLong(), index.toDouble()))
            }
        }

        assertEquals(4_999.0, store.latest("Drive/Live")?.value)
        releaseCollector.complete(Unit)
        collector.cancel()
    }

    @Test
    fun `clear removes replayed frames from the previous target`() = runTest {
        val store = TelemetryStore()
        store.accept(frame("Drive/Pose_X", 1_000, 1.0))

        store.clear()

        assertNull(withTimeoutOrNull(50) { store.updates.first() })
        store.accept(frame("Drive/Pose_X", 1_001, 2.0))
        assertEquals(2.0, store.updates.first().value)
    }

    private fun frame(key: String, timestampMs: Long, value: Double) = TelemetryFrame(
        timestampMs = timestampMs,
        sessionId = "test",
        key = key,
        value = value
    )
}
