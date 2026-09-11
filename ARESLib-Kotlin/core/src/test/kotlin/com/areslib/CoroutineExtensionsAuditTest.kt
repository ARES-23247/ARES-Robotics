package com.areslib

import com.areslib.action.RobotAction
import com.areslib.state.Alliance
import com.areslib.state.RobotState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.lang.management.ManagementFactory

class CoroutineExtensionsAuditTest {
    @Test fun `slow collector receives an explicit failure instead of silently losing updates`() = runBlocking {
        supervisorScope {
            val store = Store()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val outcome = async {
                runCatching {
                    withTimeout(2000) {
                        store.asFlow().buffer(1).onEach {
                            if (!entered.isCompleted) { entered.complete(Unit); release.await() }
                        }.take(5).toList()
                    }
                }
            }
            entered.await()
            repeat(4) { store.dispatch(RobotAction.SetAlliance(Alliance.RED, it.toLong())) }
            release.complete(Unit)
            val error = outcome.await().exceptionOrNull()
            assertTrue(error is IllegalStateException, "Expected explicit overflow, got $error")
            assertTrue(error.message.orEmpty().contains("overflow", ignoreCase = true))
        }
    }

    @Test fun `finite flow without a matching state reports false`() = runBlocking {
        assertFalse(emptyFlow<RobotState>().waitUntil { true })
        assertFalse(flowOf(RobotState()).waitUntil { false })
        assertTrue(flowOf(RobotState()).waitUntil { true })
    }

    @Test fun `initial state and ordinary updates retain ordered values`() = runBlocking {
        val store = Store()
        val initial = CompletableDeferred<Unit>()
        val values = async {
            withTimeout(2000) {
                store.asFlow().onEach { initial.complete(Unit) }.take(3).toList()
            }
        }
        initial.await()
        store.dispatch(RobotAction.SetAlliance(Alliance.RED, 10L))
        store.dispatch(RobotAction.SetAlliance(Alliance.BLUE, 20L))
        assertEquals(listOf(0L, 10L, 20L), values.await().map { it.timestampMs })
    }

    @Test fun `initial snapshot cannot precede registration across a concurrent reduction`() = runBlocking {
        val store = Store()
        val producerThread = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor { work ->
            Thread(work, "audit-flow-producer").also(producerThread::set)
        }
        executor.asCoroutineDispatcher().use { dispatcher ->
            // Starting a separate collector while holding Store exposes the exact registration gate.
            val result: kotlinx.coroutines.Deferred<RobotState>
            synchronized(store) {
                result = async(kotlinx.coroutines.Dispatchers.Default) {
                    withTimeout(3000) { store.asFlow().flowOn(dispatcher).first() }
                }
                val deadline = System.nanoTime() + 2_000_000_000L
                val bean = ManagementFactory.getThreadMXBean()
                while (producerThread.get()?.let { bean.getThreadInfo(it.id)?.lockOwnerId } != Thread.currentThread().id &&
                    System.nanoTime() < deadline) Thread.sleep(1)
                assertEquals(Thread.currentThread().id,
                    producerThread.get()?.let { bean.getThreadInfo(it.id)?.lockOwnerId },
                    "Producer must be waiting on the Store monitor")
                store.dispatch(RobotAction.SetAlliance(Alliance.BLUE, 42L))
            }
            assertEquals(42L, result.await().timestampMs)
        }
    }

    @Test fun `cancellation removes the flow subscription`() = runBlocking {
        val store = Store()
        val field = Store::class.java.getDeclaredField("listeners").also { it.isAccessible = true }
        fun listenerCount() = (field.get(store) as Array<*>).size
        val subscribed = CompletableDeferred<Unit>()
        val job = launch { store.asFlow().collect { subscribed.complete(Unit) } }
        subscribed.await()
        assertEquals(1, listenerCount())
        job.cancelAndJoin()
        assertEquals(0, listenerCount())
    }

    @Test fun `waitUntil preserves failures and cancellation while timing out normally`(): Unit = runBlocking {
        assertFalse(flowOf(RobotState()).waitUntil(timeoutMs = 0) { true })
        assertFalse(kotlinx.coroutines.flow.flow<RobotState> { awaitCancellation() }.waitUntil(10) { true })
        assertFailsWith<IllegalArgumentException> {
            flowOf(RobotState()).waitUntil { throw IllegalArgumentException("predicate") }
        }
        assertFailsWith<IllegalStateException> {
            kotlinx.coroutines.flow.flow<RobotState> { error("upstream") }.waitUntil { true }
        }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            kotlinx.coroutines.flow.flow<RobotState> { throw kotlinx.coroutines.CancellationException("cancel") }
                .waitUntil { true }
        }
    }
}
